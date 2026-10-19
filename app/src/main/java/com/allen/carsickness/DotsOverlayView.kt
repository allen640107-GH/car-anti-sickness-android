package com.allen.carsickness

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * iPhone Vehicle Motion Cues look-alike (iOS 18).
 *
 * Soft black / dark-gray feathered dots only on the left and right edges.
 * Intrinsic idle drift + breathe (always animated) combined with motion that
 * is opposite to linear acceleration (vestibular match).
 */
class DotsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    companion object {
        /** ~8–12 per side (Apple Almond / Control Center demos). */
        private const val DOT_COUNT = 10
        /** Soft peripheral band (~6–10% width). */
        private const val EDGE_INSET_FRACTION = 0.018f
        private const val TOP_PAD_FRACTION = 0.07f
        private const val MAX_TRAVEL_X = 48f
        private const val MAX_TRAVEL_Y = 36f
        private const val GAIN = 14f
        private const val SMOOTH = 0.32f
        private const val GRAVITY_ALPHA = 0.85f

        /** iPhone-like core radius in dp (small black edge dots). */
        private const val CORE_DP_MIN = 4.0f
        private const val CORE_DP_MAX = 7.5f
        /** Soft halo extends a little past the core. */
        private const val HALO_SCALE = 2.6f

        // Idle animation (always on — iOS cues are never frozen)
        private const val IDLE_DRIFT_X_DP = 2.2f
        private const val IDLE_DRIFT_Y_DP = 5.5f
        private const val IDLE_BREATHE = 0.08f
        private const val IDLE_PERIOD_MS = 4200f

        private val CORE_RGB = intArrayOf(0x1A, 0x1A, 0x1C)
        private val HALO_RGB = intArrayOf(0xF2, 0xF2, 0xF5)
    }

    private val density = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Organic size multipliers (stable). Range keeps cores ~4–8 dp. */
    private val sizeJitter: FloatArray = FloatArray(DOT_COUNT) { i ->
        0.78f + 0.28f * (0.5f + 0.5f * sin(i * 2.713f + 1.1f))
    }

    /** Per-dot phase offsets for idle drift (not synchronized). */
    private val idlePhase: FloatArray = FloatArray(DOT_COUNT) { i ->
        i * 0.73f + 0.4f * sin(i * 1.9f)
    }

    /** Irregular vertical spacing in [0,1]. */
    private val yNorm: FloatArray = run {
        val raw = FloatArray(DOT_COUNT) { i ->
            val t = if (DOT_COUNT == 1) 0.5f else i / (DOT_COUNT - 1).toFloat()
            val wobble = 0.028f * sin(i * 3.37f + 0.4f) + 0.018f * sin(i * 5.91f)
            (t + wobble).coerceIn(0.02f, 0.98f)
        }
        for (i in 1 until DOT_COUNT) {
            if (raw[i] <= raw[i - 1] + 0.035f) {
                raw[i] = (raw[i - 1] + 0.035f).coerceAtMost(0.98f)
            }
        }
        raw
    }

    private var offsetX = 0f
    private var offsetY = 0f
    private var targetX = 0f
    private var targetY = 0f

    private var sensorManager: SensorManager? = null
    private var running = false
    private var usingLinearAccel = false

    private val gravity = FloatArray(3)
    private var gravityInitialized = false
    private var parallaxY = 0f

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
            usingLinearAccel = true
            sm.registerListener(this, linear, SensorManager.SENSOR_DELAY_GAME)
        } else {
            usingLinearAccel = false
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
        // Opposite to acceleration (vestibular match)
        targetX = (-ax * GAIN).coerceIn(-MAX_TRAVEL_X, MAX_TRAVEL_X)
        targetY = (ay * GAIN * 0.72f).coerceIn(-MAX_TRAVEL_Y, MAX_TRAVEL_Y)
        parallaxY = (ax * GAIN * 0.16f).coerceIn(-12f, 12f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        offsetX += (targetX - offsetX) * SMOOTH
        offsetY += (targetY - offsetY) * SMOOTH

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val edgeInset = max(8f * density, w * EDGE_INSET_FRACTION)
        val topPad = h * TOP_PAD_FRACTION
        val usable = h - topPad * 2f

        // Small iPhone-like cores: ~4–7.5 dp, mid-edge slightly larger
        val coreDpBase = (CORE_DP_MIN + CORE_DP_MAX) * 0.5f
        val baseR = coreDpBase * density

        val idleDriftXMax = IDLE_DRIFT_X_DP * density
        val idleDriftYMax = IDLE_DRIFT_Y_DP * density
        val elapsed = if (animStartMs == 0L) 0f
        else (System.currentTimeMillis() - animStartMs).toFloat()
        val tau = (elapsed / IDLE_PERIOD_MS) * (Math.PI.toFloat() * 2f)

        for (i in 0 until DOT_COUNT) {
            val t = yNorm[i]
            val midBoost = 1f - abs(t - 0.5f) * 0.55f
            val phase = idlePhase[i]

            // Intrinsic idle: soft vertical drift + slight inward breathe
            val idleY = idleDriftYMax * sin(tau * 0.85f + phase)
            val idleX = idleDriftXMax * sin(tau * 0.55f + phase * 1.3f)
            val breathe = 1f + IDLE_BREATHE * sin(tau * 1.1f + phase * 0.7f)

            val coreR = (baseR * midBoost * sizeJitter[i] * breathe)
                .coerceIn(CORE_DP_MIN * density * 0.85f, CORE_DP_MAX * density * 1.15f)
            val featherR = coreR * HALO_SCALE

            val midWeight = (1f - abs(t - 0.5f) * 1.05f).coerceIn(0.35f, 1f)
            val coreAlpha = (0x55 + (midWeight * 0x40).toInt()).coerceIn(0x40, 0x98)
            val haloAlpha = (0x14 + (midWeight * 0x18).toInt()).coerceIn(0x0C, 0x30)

            val yBase = topPad + usable * t
            val yLeft = yBase + offsetY + parallaxY + idleY
            val yRight = yBase + offsetY - parallaxY - idleY * 0.85f

            // Idle X drifts slightly inward on each side
            val lx = edgeInset + offsetX + idleX
            val rx = w - edgeInset + offsetX - idleX

            drawCue(canvas, lx, yLeft, coreR, featherR, coreAlpha, haloAlpha, inwardRight = true)
            drawCue(canvas, rx, yRight, coreR, featherR, coreAlpha, haloAlpha, inwardRight = false)
        }

        if (running) postInvalidateOnAnimation()
    }

    /**
     * Dual-layer soft cue: faint light halo + dark charcoal core, both feathered.
     * Opacity stronger at the physical edge; soft falloff extends slightly inward.
     */
    private fun drawCue(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        coreR: Float,
        featherR: Float,
        coreAlpha: Int,
        haloAlpha: Int,
        inwardRight: Boolean
    ) {
        val stretchX = 1.55f
        val nudge = if (inwardRight) featherR * 0.08f else -featherR * 0.08f

        canvas.save()
        canvas.translate(cx + nudge, cy)
        canvas.scale(stretchX, 1f)

        // 1) Faint light halo (contrast on dark UIs)
        val haloOuter = Color.argb(haloAlpha, HALO_RGB[0], HALO_RGB[1], HALO_RGB[2])
        val haloMid = Color.argb((haloAlpha * 0.35f).toInt(), HALO_RGB[0], HALO_RGB[1], HALO_RGB[2])
        paint.shader = RadialGradient(
            0f, 0f, featherR / stretchX,
            intArrayOf(haloOuter, haloMid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.42f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(0f, 0f, featherR / stretchX, paint)

        // 2) Dark soft core (main iOS look) — small, feathered
        val darkCore = Color.argb(coreAlpha, CORE_RGB[0], CORE_RGB[1], CORE_RGB[2])
        val darkMid = Color.argb((coreAlpha * 0.42f).toInt(), CORE_RGB[0], CORE_RGB[1], CORE_RGB[2])
        val darkR = max(coreR * 1.85f, featherR * 0.62f) / stretchX
        paint.shader = RadialGradient(
            0f, 0f, darkR,
            intArrayOf(darkCore, darkMid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.36f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(0f, 0f, darkR, paint)
        paint.shader = null
        canvas.restore()
    }
}
