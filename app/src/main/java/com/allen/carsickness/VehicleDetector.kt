package com.allen.carsickness

/**
 * Port of PWA vehicle-detect.js:
 * GPS speed (m/s → km/h) + accelerometer deviation heuristic.
 * Sustained speed above threshold turns active ON; sustained stop turns OFF.
 */
class VehicleDetector(
    var speedThresholdKmh: Float = 18f,
    var sustainSeconds: Long = 8_000L,
    var stopSeconds: Long = 45_000L,
    var enabled: Boolean = true
) {
    var speedKmh: Float = 0f
        private set
    var moving: Boolean = false
        private set
    var active: Boolean = false
        private set
    var source: String = "none"
        private set

    private var aboveSince: Long? = null
    private var belowSince: Long? = null
    private var motionScore: Float = 0f
    private var manual: Boolean? = null

    fun setManualActive(v: Boolean?) {
        manual = v
        if (v != null) active = v
        recompute()
    }

    fun onLocation(speedMps: Float?, accuracyM: Float?) {
        if (speedMps != null && speedMps >= 0f && !speedMps.isNaN()) {
            speedKmh = speedMps * 3.6f
            source = "geo"
        }
        recompute()
    }

    /** Call with accelerationIncludingGravity magnitude (m/s²). */
    fun onMotion(magnitude: Float, dtSec: Float) {
        val deviation = kotlin.math.abs(magnitude - 9.8f)
        motionScore = motionScore * 0.92f + deviation * 0.08f
        if (source != "geo" || speedKmh < 1f) {
            if (motionScore > 0.35f) {
                speedKmh = maxOf(speedKmh, 12f + motionScore * 20f)
                source = "motion"
            } else if (source == "motion") {
                speedKmh = maxOf(0f, speedKmh - dtSec * 8f)
            }
        }
        recompute()
    }

    fun reset() {
        speedKmh = 0f
        moving = false
        active = false
        source = "none"
        aboveSince = null
        belowSince = null
        motionScore = 0f
        manual = null
    }

    private fun recompute() {
        if (manual != null) {
            active = manual == true
            moving = speedKmh >= speedThresholdKmh
            return
        }
        if (!enabled) {
            active = false
            return
        }
        val now = System.currentTimeMillis()
        val above = speedKmh >= speedThresholdKmh
        moving = above
        if (above) {
            belowSince = null
            if (aboveSince == null) aboveSince = now
            if (!active && now - (aboveSince ?: now) >= sustainSeconds) {
                active = true
            }
        } else {
            aboveSince = null
            if (active) {
                if (belowSince == null) belowSince = now
                if (now - (belowSince ?: now) >= stopSeconds) {
                    active = false
                    belowSince = null
                }
            } else {
                belowSince = null
            }
        }
    }
}
