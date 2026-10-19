package com.allen.carsickness

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
import android.provider.Settings
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

class OverlayService : Service(), LocationListener, SensorEventListener {

    companion object {
        const val CHANNEL_ID = "cas_monitor"
        const val NOTIF_ID = 6401
        const val ACTION_PAUSE = "com.allen.carsickness.PAUSE"
        const val ACTION_RESUME = "com.allen.carsickness.RESUME"
        const val ACTION_STOP = "com.allen.carsickness.STOP"
        const val ACTION_STATUS = "com.allen.carsickness.STATUS"
        const val EXTRA_SPEED = "speed"
        const val EXTRA_ACTIVE = "active"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_PAUSED = "paused"

        @Volatile var isRunning = false
            private set
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
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        paused = Prefs.isPaused(this)
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
                stopSelfSafely()
                return START_NOT_STICKY
            }
        }

        Prefs.setMonitoring(this, true)
        isRunning = true
        detector.enabled = Prefs.isAutoDetect(this)
        if (Prefs.isForceOverlay(this)) {
            detector.setManualActive(true)
        }

        val notif = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notif,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            )
        } else {
            startForeground(NOTIF_ID, notif)
        }

        startLocation()
        startMotion()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private fun stopSelfSafely() {
        isRunning = false
        Prefs.setMonitoring(this, false)
        Prefs.setForceOverlay(this, false)
        hideOverlay()
        stopLocation()
        stopMotion()
        handler.removeCallbacks(tick)
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
            // Also try fused-like dual listen on network as backup
            if (provider == LocationManager.GPS_PROVIDER &&
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            ) {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER, 2000L, 5f, this, Looper.getMainLooper()
                )
            }
        } catch (_: SecurityException) {
            // Permission not granted — motion fallback still works
        }
    }

    private fun stopLocation() {
        try {
            locationManager?.removeUpdates(this)
        } catch (_: Exception) {
        }
    }

    private fun startMotion() {
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    private fun stopMotion() {
        sensorManager?.unregisterListener(this)
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
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val ax = event.values[0]
        val ay = event.values[1]
        val az = event.values[2]
        val mag = kotlin.math.sqrt(ax * ax + ay * ay + az * az)
        val now = System.nanoTime()
        val dt = if (lastMotionTs == 0L) 0.05f
        else ((now - lastMotionTs) / 1_000_000_000f).coerceIn(0.01f, 0.5f)
        lastMotionTs = now
        detector.onMotion(mag, dt)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateOverlayVisibility() {
        if (paused) {
            hideOverlay()
            return
        }
        val force = Prefs.isForceOverlay(this)
        if (force) detector.setManualActive(true)
        else if (Prefs.isAutoDetect(this)) detector.setManualActive(null)

        if (detector.active || force) showOverlay() else hideOverlay()
    }

    private fun showOverlay() {
        if (overlayShown) return
        if (!Settings.canDrawOverlays(this)) return
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
            view.startSensors()
            overlayView = view
            overlayShown = true
        } catch (_: Exception) {
            overlayShown = false
        }
    }

    private fun hideOverlay() {
        val view = overlayView ?: return
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
        val ch = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
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
        val text = when {
            paused -> getString(R.string.notif_text_paused)
            detector.active || Prefs.isForceOverlay(this) -> getString(R.string.notif_text_driving)
            else -> getString(R.string.notif_text_idle)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(pauseResumeAction)
            .addAction(stopAction)
            .setPriority(NotificationCompat.PRIORITY_LOW)
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
        sendBroadcast(
            Intent(ACTION_STATUS).setPackage(packageName)
                .putExtra(EXTRA_SPEED, detector.speedKmh)
                .putExtra(EXTRA_ACTIVE, detector.active || Prefs.isForceOverlay(this))
                .putExtra(EXTRA_RUNNING, isRunning)
                .putExtra(EXTRA_PAUSED, paused)
        )
    }

    override fun onDestroy() {
        isRunning = false
        hideOverlay()
        stopLocation()
        stopMotion()
        handler.removeCallbacks(tick)
        super.onDestroy()
    }
}
