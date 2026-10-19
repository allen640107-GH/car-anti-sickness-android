package com.allen.carsickness

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Choreographer
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/**
 * Foreground overlay service. Keeps dots alive across app backgrounding,
 * brief GPS gaps, and process death (START_STICKY + AlarmManager restart).
 * Accel is registered on the Service (GAME rate) and forwarded into the view
 * so motion does not depend on MainActivity being resumed.
 */
class OverlayService : Service(), LocationListener, SensorEventListener {

    companion object {
        const val CHANNEL_ID = "cas_monitor"
        const val NOTIF_ID = 6401
        const val ACTION_PAUSE = "com.allen.carsickness.PAUSE"
        const val ACTION_RESUME = "com.allen.carsickness.RESUME"
        const val ACTION_STOP = "com.allen.carsickness.STOP"
        const val ACTION_STATUS = "com.allen.carsickness.STATUS"
        const val ACTION_RESTART = "com.allen.carsickness.RESTART"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_PAUSED = "paused"

        private const val RESTART_REQ = 6402
        private const val RESTART_DELAY_MS = 2_000L

        @Volatile var isRunning = false
            private set

        fun start(context: Context) {
            val i = Intent(context, OverlayService::class.java)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }
    }

    private var windowManager: WindowManager? = null
    private var overlayView: DotsOverlayView? = null
    private var overlayShown = false
    private val detector = VehicleDetector()
    private var locationManager: LocationManager? = null
    private var sensorManager: SensorManager? = null
    private var lastMotionTs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var paused = false
    private var userRequestedStop = false

    private val gravity = FloatArray(3)
    private var gravityInitialized = false
    private var linearSensor: Sensor? = null
    private var accelSensor: Sensor? = null

    private var choreographer: Choreographer? = null
    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!isRunning) return
            overlayView?.tickFrame()
            choreographer?.postFrameCallback(this)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!isRunning) return
            updateOverlayVisibility()
            updateNotification()
            broadcastStatus()
            handler.postDelayed(this, 1500)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        // Application context — not tied to Activity lifecycle / OEM view throttling.
        sensorManager = applicationContext.getSystemService(SENSOR_SERVICE) as SensorManager
        paused = Prefs.isPaused(this)
        choreographer = Choreographer.getInstance()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PAUSE -> {
                paused = true
                Prefs.setPaused(this, true)
                Prefs.setForceOverlay(this, false)
                hideOverlay()
                updateNotification()
                broadcastStatus()
                return START_STICKY
            }
            ACTION_RESUME -> {
                paused = false
                Prefs.setPaused(this, false)
                updateOverlayVisibility()
                updateNotification()
                broadcastStatus()
                return START_STICKY
            }
            ACTION_STOP -> {
                userRequestedStop = true
                cancelScheduledRestart()
                stopSelfSafely()
                return START_NOT_STICKY
            }
            ACTION_RESTART -> {
                // Fall through to normal start
            }
        }

        userRequestedStop = false
        Prefs.setMonitoring(this, true)
        isRunning = true
        detector.enabled = Prefs.isAutoDetect(this)
        if (Prefs.isForceOverlay(this)) {
            detector.setManualActive(true)
        } else if (Prefs.isAutoDetect(this)) {
            detector.setManualActive(null)
        }

        val notif = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                ServiceCompat.startForeground(
                    this, NOTIF_ID, notif,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                )
            } else {
                startForeground(NOTIF_ID, notif)
            }
        } catch (_: Exception) {
            // Still try to run; some OEMs throw if type mismatch
            try { startForeground(NOTIF_ID, notif) } catch (_: Exception) {}
        }

        startLocation()
        startServiceSensors()
        handler.removeCallbacks(tick)
        handler.post(tick)
        choreographer?.removeFrameCallback(frameCallback)
        choreographer?.postFrameCallback(frameCallback)
        // Restore overlay immediately after sticky restart / force
        updateOverlayVisibility()
        return START_STICKY
    }

    private fun stopSelfSafely() {
        isRunning = false
        Prefs.setMonitoring(this, false)
        Prefs.setForceOverlay(this, false)
        hideOverlay()
        stopLocation()
        stopServiceSensors()
        handler.removeCallbacks(tick)
        choreographer?.removeFrameCallback(frameCallback)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        broadcastStatus()
    }

    private fun startLocation() {
        try {
            val lm = locationManager ?: return
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> LocationManager.GPS_PROVIDER
            }
            lm.requestLocationUpdates(provider, 1000L, 1f, this, Looper.getMainLooper())
            if (provider == LocationManager.GPS_PROVIDER &&
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            ) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 2000L, 5f, this, Looper.getMainLooper()
                )
            }
            // Seed with last known if available
            try {
                lm.getLastKnownLocation(provider)?.let { onLocationChanged(it) }
            } catch (_: SecurityException) {}
        } catch (_: SecurityException) {
            // Permission not granted — motion fallback + force still work
        }
    }

    private fun stopLocation() {
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    /** Service-owned GAME-rate sensors; forwarded into overlay view. */
    private fun startServiceSensors() {
        val sm = sensorManager ?: return
        gravityInitialized = false
        linearSensor = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        accelSensor = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val primary = linearSensor ?: accelSensor
        primary?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
        }
        // Detector still gets magnitude from accel for driving heuristic
        if (linearSensor != null && accelSensor != null) {
            sm.registerListener(this, accelSensor, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopServiceSensors() {
        try {
            sensorManager?.unregisterListener(this)
        } catch (_: Exception) {
        }
    }

    override fun onLocationChanged(location: Location) {
        val sp = if (location.hasSpeed()) location.speed else null
        detector.onLocation(sp, if (location.hasAccuracy()) location.accuracy else null)
    }

    @Deprecated("Deprecated in API")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val type = event.sensor.type
        if (type == Sensor.TYPE_LINEAR_ACCELERATION) {
            overlayView?.feedLinearAccel(event.values[0], event.values[1])
        } else if (type == Sensor.TYPE_ACCELEROMETER) {
            if (!gravityInitialized) {
                gravity[0] = event.values[0]
                gravity[1] = event.values[1]
                gravity[2] = event.values[2]
                gravityInitialized = true
            } else {
                val a = 0.92f
                gravity[0] = a * gravity[0] + (1f - a) * event.values[0]
                gravity[1] = a * gravity[1] + (1f - a) * event.values[1]
                gravity[2] = a * gravity[2] + (1f - a) * event.values[2]
            }
            val ax = event.values[0] - gravity[0]
            val ay = event.values[1] - gravity[1]
            // If no linear sensor, feed linearised accel to the view
            if (linearSensor == null) {
                overlayView?.feedLinearAccel(ax, ay)
            }
            val mag = kotlin.math.sqrt(
                event.values[0] * event.values[0] +
                    event.values[1] * event.values[1] +
                    event.values[2] * event.values[2]
            )
            val now = System.nanoTime()
            val dt = if (lastMotionTs == 0L) 0.05f
            else ((now - lastMotionTs) / 1_000_000_000f).coerceIn(0.01f, 0.5f)
            lastMotionTs = now
            detector.onMotion(mag, dt)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateOverlayVisibility() {
        if (paused) {
            hideOverlay()
            return
        }
        val force = Prefs.isForceOverlay(this)
        if (force) {
            detector.setManualActive(true)
        } else if (Prefs.isAutoDetect(this)) {
            detector.setManualActive(null)
        }

        // Show when: force, detector active, OR keep-visible-while-monitoring
        // (sticky mode: once monitoring, hold dots until pause/stop — avoids mid-trip vanish)
        val keepWhileMonitoring = Prefs.isKeepOverlayWhileMonitoring(this) &&
            Prefs.isMonitoring(this)
        if (detector.active || force || keepWhileMonitoring) {
            showOverlay()
        } else {
            hideOverlay()
        }
    }

    private fun showOverlay() {
        if (overlayShown && overlayView != null) return
        if (!Settings.canDrawOverlays(this)) return
        // Clean up stale view if flag drifted
        if (overlayView != null) {
            try {
                overlayView?.stopSensors()
                windowManager?.removeView(overlayView)
            } catch (_: Exception) {}
            overlayView = null
            overlayShown = false
        }
        val view = DotsOverlayView(this)
        val type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        try {
            windowManager?.addView(view, params)
            // Prefer Service-fed sensors (external) so Activity resume is irrelevant
            view.startSensors(preferExternal = true)
            overlayView = view
            overlayShown = true
        } catch (_: Exception) {
            overlayShown = false
            overlayView = null
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: run {
            overlayShown = false
            return
        }
        try {
            view.stopSensors()
            windowManager?.removeView(view)
        } catch (_: Exception) {
        }
        overlayView = null
        overlayShown = false
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        // DEFAULT (not LOW) — harder for OEMs to deprioritize / kill FGS
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
            setSound(null, null)
        }
        mgr.createNotificationChannel(ch)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val pauseResumeAction = if (paused) {
            NotificationCompat.Action(
                0, getString(R.string.notif_action_resume),
                pendingService(ACTION_RESUME, 1)
            )
        } else {
            NotificationCompat.Action(
                0, getString(R.string.notif_action_pause),
                pendingService(ACTION_PAUSE, 2)
            )
        }
        val stopAction = NotificationCompat.Action(
            0, getString(R.string.notif_action_stop),
            pendingService(ACTION_STOP, 3)
        )
        val force = Prefs.isForceOverlay(this)
        val keep = Prefs.isKeepOverlayWhileMonitoring(this)
        val text = when {
            paused -> getString(R.string.notif_text_paused)
            detector.active || force || (keep && overlayShown) ->
                getString(R.string.notif_text_driving)
            else -> getString(R.string.notif_text_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(pauseResumeAction)
            .addAction(stopAction)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun pendingService(action: String, req: Int): PendingIntent {
        val i = Intent(this, OverlayService::class.java).setAction(action)
        return PendingIntent.getService(
            this, req, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun updateNotification() {
        val mgr = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        mgr.notify(NOTIF_ID, buildNotification())
    }

    private fun broadcastStatus() {
        val showing = overlayShown || Prefs.isForceOverlay(this) || detector.active
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_SPEED, detector.speedKmh)
                .putExtra(EXTRA_ACTIVE, showing && !paused)
                .putExtra(EXTRA_RUNNING, isRunning)
                .putExtra(EXTRA_PAUSED, paused)
        )
    }

    private fun scheduleRestart() {
        if (userRequestedStop) return
        if (!Prefs.isMonitoring(this) && !Prefs.isForceOverlay(this)) return
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                this, RESTART_REQ,
                Intent(this, OverlayService::class.java).setAction(ACTION_RESTART),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val trigger = SystemClock.elapsedRealtime() + RESTART_DELAY_MS
            if (Build.VERSION.SDK_INT >= 23) {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            } else {
                am.set(AlarmManager.ELAPSED_REALTIME_WAKEUP, trigger, pi)
            }
        } catch (_: Exception) {
        }
    }

    private fun cancelScheduledRestart() {
        try {
            val am = getSystemService(ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getService(
                this, RESTART_REQ,
                Intent(this, OverlayService::class.java).setAction(ACTION_RESTART),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
        } catch (_: Exception) {
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped away — keep FGS; schedule backup restart if OEM kills us next
        if (!userRequestedStop && Prefs.isMonitoring(this)) {
            scheduleRestart()
        }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        isRunning = false
        hideOverlay()
        stopLocation()
        stopServiceSensors()
        handler.removeCallbacks(tick)
        choreographer?.removeFrameCallback(frameCallback)
        if (!userRequestedStop && Prefs.isMonitoring(this)) {
            Prefs.setMonitoring(this, true) // ensure sticky restart path
            scheduleRestart()
        }
        super.onDestroy()
    }
}
