package com.allen.carsickness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_LOCKED_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        // Restart if user enabled boot start OR monitoring was left on / force-show
        val should = Prefs.isStartOnBoot(context) ||
            Prefs.isMonitoring(context) ||
            Prefs.isForceOverlay(context)
        if (!should) return
        if (!Settings.canDrawOverlays(context)) return
        Prefs.setMonitoring(context, true)
        Prefs.setPaused(context, false)
        ContextCompat.startForegroundService(
            context, Intent(context, OverlayService::class.java)
        )
    }
}
