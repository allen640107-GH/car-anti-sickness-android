package com.allen.carsickness

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.core.content.ContextCompat

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!Prefs.isStartOnBoot(context)) return
        if (!Prefs.isMonitoring(context) && !Prefs.isStartOnBoot(context)) return
        if (!Settings.canDrawOverlays(context)) return
        Prefs.setMonitoring(context, true)
        Prefs.setPaused(context, false)
        val i = Intent(context, OverlayService::class.java)
        ContextCompat.startForegroundService(context, i)
    }
}
