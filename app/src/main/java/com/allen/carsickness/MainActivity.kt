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
    private lateinit var switchAuto: SwitchMaterial
    private lateinit var switchBoot: SwitchMaterial

    private var awaitingOverlayReturn = false

    private val locPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { refreshPermUi(); maybeContinueOnboarding() }

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
        switchAuto = findViewById(R.id.switchAuto)
        switchBoot = findViewById(R.id.switchBoot)

        switchAuto.isChecked = Prefs.isAutoDetect(this)
        switchBoot.isChecked = Prefs.isStartOnBoot(this)

        btnSetup.setOnClickListener { startOnboarding() }
        btnToggle.setOnClickListener { togglePause() }
        btnForce.setOnClickListener { forceOverlay() }
        switchAuto.setOnCheckedChangeListener { _, checked ->
            Prefs.setAutoDetect(this, checked)
            if (OverlayService.isRunning) {
                // restart service intent to pick up setting
                startServiceIntent()
            }
        }
        switchBoot.setOnCheckedChangeListener { _, checked ->
            Prefs.setStartOnBoot(this, checked)
        }

        refreshPermUi()
        if (OverlayService.isRunning || Prefs.isMonitoring(this)) {
            btnToggle.visibility = View.VISIBLE
            btnForce.visibility = View.VISIBLE
            btnSetup.text = "重新檢查權限並啟動"
            if (!OverlayService.isRunning && hasAllRequired()) {
                startServiceIntent()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshPermUi()
        if (awaitingOverlayReturn) {
            awaitingOverlayReturn = false
            maybeContinueOnboarding()
        }
        ContextCompat.registerReceiver(
            this, statusReceiver,
            IntentFilter(OverlayService.ACTION_STATUS),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        if (OverlayService.isRunning) {
            updateStatus(true, Prefs.isPaused(this), false, 0f)
        }
    }

    override fun onPause() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onPause()
    }

    private fun startOnboarding() {
        when {
            !Settings.canDrawOverlays(this) -> {
                Toast.makeText(this, "請開啟「顯示在其他應用程式上層」", Toast.LENGTH_LONG).show()
                awaitingOverlayReturn = true
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")
                )
                startActivity(intent)
            }
            !hasLocation() -> {
                locPermission.launch(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                )
            }
            Build.VERSION.SDK_INT >= 33 && !hasNotifications() -> {
                notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
            else -> {
                Prefs.setPaused(this, false)
                startServiceIntent()
                btnToggle.visibility = View.VISIBLE
                btnForce.visibility = View.VISIBLE
                Toast.makeText(this, "已開始監控，可切換至其他 App", Toast.LENGTH_LONG).show()
                updateStatus(true, false, false, 0f)
            }
        }
    }

    private fun maybeContinueOnboarding() {
        if (hasAllRequired()) {
            startOnboarding()
        } else if (Settings.canDrawOverlays(this) && !hasLocation()) {
            startOnboarding()
        } else if (hasLocation() && Build.VERSION.SDK_INT >= 33 && !hasNotifications()) {
            startOnboarding()
        }
    }

    private fun hasAllRequired(): Boolean {
        val overlay = Settings.canDrawOverlays(this)
        val loc = hasLocation()
        val notif = Build.VERSION.SDK_INT < 33 || hasNotifications()
        return overlay && loc && notif
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

    private fun refreshPermUi() {
        val ok = getColor(R.color.ok)
        val warn = getColor(R.color.warn)
        if (Settings.canDrawOverlays(this)) {
            tvOverlayPerm.text = "① 顯示在其他應用程式上層：已授權 ✓"
            tvOverlayPerm.setTextColor(ok)
        } else {
            tvOverlayPerm.text = "① 顯示在其他應用程式上層：未授權"
            tvOverlayPerm.setTextColor(warn)
        }
        if (hasLocation()) {
            tvLocationPerm.text = "② 定位：已授權 ✓"
            tvLocationPerm.setTextColor(ok)
        } else {
            tvLocationPerm.text = "② 定位：未授權"
            tvLocationPerm.setTextColor(warn)
        }
        if (hasNotifications()) {
            tvNotifPerm.text = "③ 通知：已授權 ✓"
            tvNotifPerm.setTextColor(ok)
        } else {
            tvNotifPerm.text = "③ 通知：未授權"
            tvNotifPerm.setTextColor(warn)
        }
    }

    private fun startServiceIntent() {
        val i = Intent(this, OverlayService::class.java)
        ContextCompat.startForegroundService(this, i)
    }

    private fun togglePause() {
        if (!OverlayService.isRunning) {
            startServiceIntent()
            return
        }
        val action = if (Prefs.isPaused(this)) OverlayService.ACTION_RESUME
        else OverlayService.ACTION_PAUSE
        startService(Intent(this, OverlayService::class.java).setAction(action))
    }

    private fun forceOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "請先授權「顯示在其他應用程式上層」", Toast.LENGTH_SHORT).show()
            return
        }
        Prefs.setForceOverlay(this, true)
        Prefs.setPaused(this, false)
        if (!OverlayService.isRunning) startServiceIntent()
        else startService(Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_RESUME))
        Toast.makeText(this, "已強制顯示邊緣提示點（測試）", Toast.LENGTH_SHORT).show()
    }

    private fun updateStatus(running: Boolean, paused: Boolean, active: Boolean, speed: Float) {
        tvStatus.text = when {
            !running -> "尚未啟動"
            paused -> "已暫停"
            active -> "行駛中 — 提示點開啟"
            else -> "監控中（等待行駛）"
        }
        tvSpeed.text = if (running) "速度：${"%.1f".format(speed)} km/h" else "速度：—"
        btnToggle.visibility = if (running) View.VISIBLE else View.GONE
        btnForce.visibility = if (running || Settings.canDrawOverlays(this)) View.VISIBLE else View.GONE
        btnToggle.text = if (paused) "繼續" else "暫停"
    }
}
