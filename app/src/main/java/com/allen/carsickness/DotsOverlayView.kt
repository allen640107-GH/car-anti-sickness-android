package com.allen.carsickness

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.sin

/**
 * YouTube Short ground-truth Vehicle Motion Cues look-alike
 * (https://youtube.com/shorts/cl3lPLPKBJI).
 *
 * Near-solid black filled circles on left + right content margins only.
 * No cyan, no inward bloom/glow/halo — slight anti-alias only.
 * ~4 per side, even size (~2–3% of screen width), irregular vertical spacing.
 * Motion is primarily vertical along the edge (opposite acceleration);
 * horizontal stays inside the margin band. Continuous gentle idle drift
 * along the edge (not size pulse, not crossing the screen).
 */
class DotsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    companion object {
        /** Ground truth: ~3–5 visible per side; use 4. */
        private const val DOT_COUNT = 4

        /** Diameter as fraction of screen width (~2–3%). */
        private const val DIAMETER_FRACTION = 0.025f

        /** Horizontal inset from screen edge in diameters (~1.5×). */
        private const val EDGE_INSET_DIAMETERS = 1.5f

        /** Keep dots away from top/bottom status/home areas. */
        private const val TOP_PAD_FRACTION = 0.10f
        private const val BOTTOM_PAD_FRACTION = 0.10f

        /** Travel: primarily vertical along the edge. */
        private const val MAX_TRAVEL_Y_DP = 28f
        private const val MAX_TRAVEL_X_DP = 4f
        private const val GAIN_Y = 16f
        private const val GAIN_X = 3.5f
        private const val SMOOTH = 0.28f
        private const val GRAVITY_ALPHA = 0.85f

        /** Idle: gentle vertical drift only (no size breathe). */
        private const val IDLE_DRIFT_Y_DP = 6.0f
        private const val IDLE_DRIFT_X_DP = 0.8f
        private const val IDLE_PERIOD_MS = 4800f

        /** Near-solid black (~90% opacity). */
        private const val DOT_ALPHA = 0xE6
        private val DOT_RGB = intArrayOf(0x0A, 0x0A, 0x0A)
    }

    private val density = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(DOT_ALPHA, DOT_RGB[0], DOT_RGB[1], DOT_RGB[2])
    }

    /** Per-dot phase offsets for idle drift (desynchronized). */
    private val idlePhase: FloatArray = FloatArray(DOT_COUNT) { i ->
        i * 1.37f + 0.55f * sin(i * 2.1f)
    }

    /**
     * Irregular vertical spacing in [0,1] within the usable band.
     * Sparse, uneven gaps matching the Short stills (~4 per side).
     */
    private val yNorm: FloatArray = floatArrayOf(0.12f, 0.38f, 0.61f, 0.86f)

    private var offsetX = 0f
    private var offsetY = 0f
    private var targetX = 0f
    private var targetY = 0f

    private var sensorManager: SensorManager? = null
    private var running = false

    private val gravity = FloatArray(3)
    private var gravityInitialized = false

    private var animStartMs = 0L

    fun startSensors() {
        if (running) return
        running = true
        gravityInitialized = false
        animStartMs = System.currentTimeMillis()
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        val sm = sensorManager ?: return
        val linear = sm.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
        if (linear != null) {
            sm.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME)
        } else {
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
            }
        }
        postInvalidateOnAnimation()
    }

    fun stopSensors() {
        running = false
        sensorManager?.unregisterListener(this)
        sensorManager = null
    }

    override fun onDetachedFromWindow() {
        stopSensors()
        super.onDetachedFromWindow()
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null) return
        val ax: Float
        val ay: Float
        when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                ax = event.values[0]
                ay = event.values[1]
            }
            Sensor.TYPE_ACCELEROMETER -> {
                if (!gravityInitialized) {
                    gravity[0] = event.values[0]
                    gravity[1] = event.values[1]
                    gravity[2] = event.values[2]
                    gravityInitialized = true
                } else {
                    gravity[0] = GRAVITY_ALPHA * gravity[0] + (1f - GRAVITY_ALPHA) * event.values[0]
                    gravity[1] = GRAVITY_ALPHA * gravity[1] + (1f - GRAVITY_ALPHA) * event.values[1]
                    gravity[2] = GRAVITY_ALPHA * gravity[2] + (1f - GRAVITY_ALPHA) * event.values[2]
                }
                ax = event.values[0] - gravity[0]
                ay = event.values[1] - gravity[1]
            }
            else -> return
        }
        val maxX = MAX_TRAVEL_X_DP * density
        val maxY = MAX_TRAVEL_Y_DP * density
        // Opposite to acceleration (vestibular match). Vertical is primary.
        targetX = (-ax * GAIN_X).coerceIn(-maxX, maxX)
        targetY = (-ay * GAIN_Y).coerceIn(-maxY, maxY)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        offsetX += (targetX - offsetX) * SMOOTH
        offsetY += (targetY - offsetY) * SMOOTH

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // Even size: ~2.5% of screen width diameter
        val diameter = (w * DIAMETER_FRACTION).coerceIn(8f * density, 14f * density)
        val radius = diameter * 0.5f
        val edgeInset = diameter * EDGE_INSET_DIAMETERS

        val topPad = h * TOP_PAD_FRACTION
        val bottomPad = h * BOTTOM_PAD_FRACTION
        val usable = h - topPad - bottomPad

        val idleYMax = IDLE_DRIFT_Y_DP * density
        val idleXMax = IDLE_DRIFT_X_DP * density
        val elapsed = if (animStartMs == 0L) 0f
        else (System.currentTimeMillis() - animStartMs).toFloat()
        val tau = (elapsed / IDLE_PERIOD_MS) * (Math.PI.toFloat() * 2f)

        // Margin band clamp so dots never leave the side strip
        val marginMax = edgeInset + diameter * 0.6f
        val leftBaseX = edgeInset
        val rightBaseX = w - edgeInset

        for (i in 0 until DOT_COUNT) {
            val t = yNorm[i]
            val phase = idlePhase[i]

            // Gentle idle along the edge (mostly vertical)
            val idleY = idleYMax * sin(tau * 0.9f + phase)
            val idleX = idleXMax * sin(tau * 0.45f + phase * 1.2f)

            val yBase = topPad + usable * t
            val yLeft = (yBase + offsetY + idleY).coerceIn(topPad, h - bottomPad)
            val yRight = (yBase + offsetY - idleY * 0.7f).coerceIn(topPad, h - bottomPad)

            // Horizontal: stay in the side margin band
            val lx = (leftBaseX + offsetX + idleX).coerceIn(radius, marginMax)
            val rx = (rightBaseX + offsetX - idleX).coerceIn(w - marginMax, w - radius)

            canvas.drawCircle(lx, yLeft, radius, paint)
            canvas.drawCircle(rx, yRight, radius, paint)
        }

        if (running) postInvalidateOnAnimation()
    }
}
