package com.allen.carsickness

import android.content.Context

object Prefs {
    private const val NAME = "cas_prefs"
    private const val KEY_AUTO = "auto_detect"
    private const val KEY_BOOT = "start_on_boot"
    private const val KEY_MONITORING = "monitoring"
    private const val KEY_PAUSED = "paused"
    private const val KEY_FORCE = "force_overlay"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun isAutoDetect(ctx: Context) = sp(ctx).getBoolean(KEY_AUTO, true)
    fun setAutoDetect(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_AUTO, v).apply()

    fun isStartOnBoot(ctx: Context) = sp(ctx).getBoolean(KEY_BOOT, false)
    fun setStartOnBoot(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_BOOT, v).apply()

    fun isMonitoring(ctx: Context) = sp(ctx).getBoolean(KEY_MONITORING, false)
    fun setMonitoring(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_MONITORING, v).apply()

    fun isPaused(ctx: Context) = sp(ctx).getBoolean(KEY_PAUSED, false)
    fun setPaused(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_PAUSED, v).apply()

    fun isForceOverlay(ctx: Context) = sp(ctx).getBoolean(KEY_FORCE, false)
    fun setForceOverlay(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean(KEY_FORCE, v).apply()
}
