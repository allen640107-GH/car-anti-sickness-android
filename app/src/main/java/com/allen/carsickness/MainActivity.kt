package com.allen.carsickness

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvSpeed: TextView
    private lateinit var tvOverlayPerm: TextView
    private lateinit var tvLocationPerm: TextView
    private lateinit var tvNotifPerm: TextView
    private lateinit var btnSetup: MaterialButton
    private lateinit var btnToggle: MaterialButton
    private lateinit var btnForce: MaterialButton
    private lateinit var btnBattery: MaterialButton
    private lateinit var switchAuto: SwitchMaterial
    private lateinit var switchBoot: SwitchMaterial
    private lateinit var switchKeep: SwitchMaterial

    private var awaitingOverlayReturn = false
    private var awaitingBatteryReturn = false

    private val locPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshPermUi()
        // Location is optional for start — continue onboarding / start if overlay OK
        maybeContinueOnboarding()
    }

    private val notifPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { refreshPermUi(); maybeContinueOnboarding() }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != OverlayService.ACTION_STATUS) return
            val speed = intent.getFloatExtra(OverlayService.EXTRA_SPEED, 0f)
            val active = intent.getBooleanExtra(OverlayService.EXTRA_ACTIVE, false)
            val running = intent.getBooleanExtra(OverlayService.EXTRA_RUNNING, false)
            val paused = intent.getBooleanExtra(OverlayService.EXTRA_PAUSED, false)
            updateStatus(running, paused, active, speed)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvSpeed = findViewById(R.id.tvSpeed)
        tvOverlayPerm = findViewById(R.id.tvOverlayPerm)
        tvLocationPerm = findViewById(R.id.tvLocationPerm)
        tvNotifPerm = findViewById(R.id.tvNotifPerm)
        btnSetup = findViewById(R.id.btnSetup)
        btnToggle = findViewById(R.id.btnToggle)
        btnForce = findViewById(R.id.btnForceOverlay)
        btnBattery = findViewById(R.id.btnBattery)
        switchAuto = findViewById(R.id.switchAuto)
        switchBoot = findViewById(R.id.switchBoot)
        switchKeep = findViewById(R.id.switchKeep)

        switchAuto.isChecked = Prefs.isAutoDetect(this)
        switchBoot.isChecked = Prefs.isStartOnBoot(this)
        switchKeep.isChecked = Prefs.isKeepOverlayWhileMonitoring(this)

        btnSetup.setOnClickListener { startOnboarding() }
        btnToggle.setOnClickListener { togglePause() }
        btnForce.setOnClickListener { forceOverlay() }
        btnBattery.setOnClickListener { requestBatteryUnrestricted() }
        switchAuto.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoDetect(this, checked)
            if (OverlayService.isRunning) OverlayService.start(this)
        }
        switchBoot.setOnCheckedChangeListener { _, checked ->
            Prefs.setStartOnBoot(this, checked)
        }
        switchKeep.setOnCheckedChangeListener { _, checked ->
            Prefs.setKeepOverlayWhileMonitoring(this, checked)
            if (OverlayService.isRunning) OverlayService.start(this)
        }

        refreshPermUi()
        refreshBatteryBtn()
        // Always show force if overlay permitted — doesn't need location
        if (Settings.canDrawOverlays(this)) {
            btnForce.visibility = View.VISIBLE
        }
        if (OverlayService.isRunning || Prefs.isMonitoring(this) || Prefs.isForceOverlay(this)) {
            btnToggle.visibility = View.VISIBLE
            btnForce.visibility = View.VISIBLE
            btnSetup.text = getString(R.string.btn_recheck_start)
            if (!OverlayService.isRunning && canStartService()) {
                startServiceIntent()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermUi()
        refreshBatteryBtn()
        if (awaitingOverlayReturn) {
            awaitingOverlayReturn = false
            if (Settings.canDrawOverlays(this)) {
                // Auto-continue: request remaining / start FGS
                maybeContinueOnboarding()
            }
        }
        if (awaitingBatteryReturn) {
            awaitingBatteryReturn = false
            refreshBatteryBtn()
        }
        ContextCompat.registerReceiver(
            this, statusReceiver,
            IntentFilter(OverlayService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (OverlayService.isRunning) {
            updateStatus(true, Prefs.isPaused(this), Prefs.isForceOverlay(this), 0f)
        } else if (Prefs.isMonitoring(this) && canStartService()) {
            // Process died — sticky prefs say we should be monitoring
            startServiceIntent()
        }
    }

    override fun onPause() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onPause()
    }

    /**
     * One-tap path: overlay → (optional notif) → start FGS.
     * Location is requested for auto-detect but NOT required to start / force-show.
     */
    private fun startOnboarding() {
        when {
            !Settings.canDrawOverlays(this) -> {
                Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_LONG).show()
                awaitingOverlayReturn = true
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                )
            }
            Build.VERSION.SDK_INT >= 33 && !hasNotifications() -> {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> {
                // Ask location in background of flow if missing (for auto-detect)
                if (!hasLocation()) {
                    locPermission.launch(
                        arrayOf(
                            Manifest.permission.ACCESS_FINE_LOCATION,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        )
                    )
                }
                Prefs.setPaused(this, false)
                Prefs.setMonitoring(this, true)
                startServiceIntent()
                promptBatteryIfNeeded()
                btnToggle.visibility = View.VISIBLE
                btnForce.visibility = View.VISIBLE
                Toast.makeText(this, R.string.toast_started, Toast.LENGTH_LONG).show()
                updateStatus(true, false, Prefs.isForceOverlay(this), 0f)
            }
        }
    }

    private fun maybeContinueOnboarding() {
        if (!Settings.canDrawOverlays(this)) return
        if (Build.VERSION.SDK_INT >= 33 && !hasNotifications()) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            return
        }
        // Overlay (+ notif) enough to start
        Prefs.setPaused(this, false)
        Prefs.setMonitoring(this, true)
        startServiceIntent()
        promptBatteryIfNeeded()
        btnToggle.visibility = View.VISIBLE
        btnForce.visibility = View.VISIBLE
        Toast.makeText(this, R.string.toast_started, Toast.LENGTH_LONG).show()
        updateStatus(true, false, Prefs.isForceOverlay(this), 0f)
        // Soft-request location after start if needed
        if (!hasLocation()) {
            locPermission.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
        }
    }

    private fun canStartService(): Boolean {
        if (!Settings.canDrawOverlays(this)) return false
        if (Build.VERSION.SDK_INT >= 33 && !hasNotifications()) return false
        return true
    }

    private fun hasLocation(): Boolean {
        val fine = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(
            this, Manifest.permission.ACCESS_COARSE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
        return fine || coarse
    }

    private fun hasNotifications(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isBatteryUnrestricted(): Boolean {
        if (Build.VERSION.SDK_INT < 23) return true
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        return pm.isIgnoringBatteryOptimizations(packageName)
    }

    private fun refreshBatteryBtn() {
        if (isBatteryUnrestricted()) {
            btnBattery.visibility = View.GONE
        } else {
            btnBattery.visibility = View.VISIBLE
        }
    }

    private fun promptBatteryIfNeeded() {
        if (!isBatteryUnrestricted()) {
            Toast.makeText(this, R.string.toast_battery_hint, Toast.LENGTH_LONG).show()
            refreshBatteryBtn()
        }
    }

    private fun requestBatteryUnrestricted() {
        if (Build.VERSION.SDK_INT < 23) return
        try {
            awaitingBatteryReturn = true
            val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(i)
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, R.string.toast_battery_manual, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun refreshPermUi() {
        val ok = getColor(R.color.ok)
        val warn = getColor(R.color.warn)
        if (Settings.canDrawOverlays(this)) {
            tvOverlayPerm.text = getString(R.string.perm_overlay_ok)
            tvOverlayPerm.setTextColor(ok)
        } else {
            tvOverlayPerm.text = getString(R.string.perm_overlay_no)
            tvOverlayPerm.setTextColor(warn)
        }
        if (hasLocation()) {
            tvLocationPerm.text = getString(R.string.perm_loc_ok)
            tvLocationPerm.setTextColor(ok)
        } else {
            tvLocationPerm.text = getString(R.string.perm_loc_no)
            tvLocationPerm.setTextColor(warn)
        }
        if (hasNotifications()) {
            tvNotifPerm.text = getString(R.string.perm_notif_ok)
            tvNotifPerm.setTextColor(ok)
        } else {
            tvNotifPerm.text = getString(R.string.perm_notif_no)
            tvNotifPerm.setTextColor(warn)
        }
    }

    private fun startServiceIntent() {
        OverlayService.start(this)
    }

    private fun togglePause() {
        if (!OverlayService.isRunning) {
            if (!Settings.canDrawOverlays(this)) {
                startOnboarding()
                return
            }
            Prefs.setPaused(this, false)
            Prefs.setMonitoring(this, true)
            startServiceIntent()
            return
        }
        val action = if (Prefs.isPaused(this)) OverlayService.ACTION_RESUME
        else OverlayService.ACTION_PAUSE
        startService(Intent(this, OverlayService::class.java).setAction(action))
    }

    /** Force-show: only needs overlay permission — no location required. */
    private fun forceOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.toast_need_overlay, Toast.LENGTH_SHORT).show()
            awaitingOverlayReturn = true
            startActivity(
                Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
            )
            return
        }
        Prefs.setForceOverlay(this, true)
        Prefs.setPaused(this, false)
        Prefs.setMonitoring(this, true)
        if (Build.VERSION.SDK_INT >= 33 && !hasNotifications()) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        startServiceIntent()
        promptBatteryIfNeeded()
        btnToggle.visibility = View.VISIBLE
        btnForce.visibility = View.VISIBLE
        Toast.makeText(this, R.string.toast_force, Toast.LENGTH_SHORT).show()
        updateStatus(true, false, true, 0f)
    }

    private fun updateStatus(running: Boolean, paused: Boolean, active: Boolean, speed: Float) {
        tvStatus.text = when {
            !running -> getString(R.string.status_idle)
            paused -> getString(R.string.status_paused)
            active -> getString(R.string.status_driving)
            else -> getString(R.string.status_monitoring)
        }
        tvSpeed.text = if (running) getString(R.string.speed_fmt, speed) else getString(R.string.speed_none)
        btnToggle.visibility = if (running || Prefs.isMonitoring(this)) View.VISIBLE else View.GONE
        btnForce.visibility = if (running || Settings.canDrawOverlays(this)) View.VISIBLE else View.GONE
        btnToggle.text = if (paused) getString(R.string.btn_resume) else getString(R.string.btn_pause)
        if (running) btnSetup.text = getString(R.string.btn_recheck_start)
    }
}
