package com.allen.carsickness

/**
 * GPS speed (m/s → km/h) + accelerometer deviation heuristic.
 * Once driving is detected, stay active through brief GPS gaps and slowdowns
 * (long OFF hysteresis) so the overlay does not vanish mid-trip.
 */
class VehicleDetector(
    var speedThresholdKmh: Float = 12f,
    /** How long above threshold before turning ON. */
    var sustainSeconds: Long = 5_000L,
    /** How long below threshold before turning OFF (was 45s — too short in traffic). */
    var stopSeconds: Long = 180_000L,
    /** Ignore "stopped" if GPS has been stale this long while already active. */
    var gpsStaleGraceMs: Long = 90_000L,
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
    private var lastGeoTs: Long = 0L
    private var activatedAt: Long = 0L

    /** Minimum time to stay ON after activation (sticky hold). */
    var minHoldMs: Long = 120_000L

    fun setManualActive(v: Boolean?) {
        manual = v
        if (v != null) active = v
        recompute()
    }

    fun onLocation(speedMps: Float?, accuracyM: Float?) {
        val now = System.currentTimeMillis()
        if (speedMps != null && speedMps >= 0f && !speedMps.isNaN()) {
            speedKmh = speedMps * 3.6f
            source = "geo"
            lastGeoTs = now
        }
        recompute()
    }

    /** Call with accelerationIncludingGravity magnitude (m/s²). */
    fun onMotion(magnitude: Float, dtSec: Float) {
        val deviation = kotlin.math.abs(magnitude - 9.8f)
        motionScore = motionScore * 0.92f + deviation * 0.08f
        val now = System.currentTimeMillis()
        val geoFresh = lastGeoTs > 0L && now - lastGeoTs < 15_000L
        if (!geoFresh || speedKmh < 1f) {
            if (motionScore > 0.28f) {
                speedKmh = maxOf(speedKmh, 10f + motionScore * 18f)
                source = "motion"
            } else if (source == "motion") {
                speedKmh = maxOf(0f, speedKmh - dtSec * 4f)
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
        lastGeoTs = 0L
        activatedAt = 0L
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
        val geoStale = lastGeoTs == 0L || now - lastGeoTs > gpsStaleGraceMs
        // While active and GPS briefly dead, do not treat as "stopped".
        val effectiveSpeed = if (active && geoStale && source == "geo") {
            maxOf(speedKmh, speedThresholdKmh)
        } else {
            speedKmh
        }
        val above = effectiveSpeed >= speedThresholdKmh
        moving = above
        if (above) {
            belowSince = null
            if (aboveSince == null) aboveSince = now
            if (!active && now - (aboveSince ?: now) >= sustainSeconds) {
                active = true
                activatedAt = now
            }
        } else {
            aboveSince = null
            if (active) {
                // Sticky min hold after first activation
                if (activatedAt > 0L && now - activatedAt < minHoldMs) {
                    belowSince = null
                    return
                }
                if (belowSince == null) belowSince = now
                if (now - (belowSince ?: now) >= stopSeconds) {
                    active = false
                    belowSince = null
                    activatedAt = 0L
                }
            } else {
                belowSince = null
            }
        }
    }
}
