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
 * Edge-only semi-transparent motion cues (iOS Vehicle Motion Cues style).
 *
 * - Soft cyan/gray-blue cores with inward-stretching radial bloom
 * - Slightly varied radii along each column (larger mid-edge)
 * - Driven by TYPE_LINEAR_ACCELERATION (or high-pass accelerometer)
 */
class DotsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    companion object {
        /** ~"More Dots" density on each side. */
        private const val DOT_COUNT = 11
        /** Soft band as fraction of screen width (inward bloom reach). */
        private const val BLOOM_FRACTION = 0.12f
        private const val EDGE_INSET_FRACTION = 0.028f
        private const val TOP_PAD_FRACTION = 0.08f
        private const val MAX_TRAVEL_X = 36f
        private const val MAX_TRAVEL_Y = 28f
        private const val GAIN = 9.5f
        private const val SMOOTH = 0.28f
        /** Gravity low-pass for accelerometer fallback. */
        private const val GRAVITY_ALPHA = 0.85f
        // iOS-like light cyan / gray-blue
        private val CORE_RGB = intArrayOf(0xB8, 0xE2, 0xF0)
        private val HALO_RGB = intArrayOf(0x9E, 0xD4, 0xE8)
    }

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val bloomPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Per-index organic size multipliers (stable, not random each frame). */
    private val sizeJitter: FloatArray = FloatArray(DOT_COUNT) { i ->
        0.78f + 0.28f * (0.5f + 0.5f * sin(i * 2.399f + 0.7f))
    }

    private var offsetX = 0f
    private var offsetY = 0f
    private var targetX = 0f
    private var targetY = 0f

    private var sensorManager: SensorManager? = null
    private var running = false
    private var usingLinearAccel = false

    // High-pass gravity estimate when falling back to raw accelerometer
    private val gravity = FloatArray(3)
    private var gravityInitialized = false

    // Subtle mirrored vertical parallax between columns
    private var parallaxY = 0f

    fun startSensors() {
        if (running) return
        running = true
        gravityInitialized = false
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
        // Device upright: lateral → X shift; forward/back → Y
        targetX = (-ax * GAIN).coerceIn(-MAX_TRAVEL_X, MAX_TRAVEL_X)
        targetY = (ay * GAIN * 0.55f).coerceIn(-MAX_TRAVEL_Y, MAX_TRAVEL_Y)
        // Soft opposite vertical parallax between L/R columns
        parallaxY = (ax * GAIN * 0.18f).coerceIn(-10f, 10f)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        offsetX += (targetX - offsetX) * SMOOTH
        offsetY += (targetY - offsetY) * SMOOTH

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val bloomReach = max(48f, w * BLOOM_FRACTION)
        val edgeInset = max(14f, w * EDGE_INSET_FRACTION)
        val topPad = h * TOP_PAD_FRACTION
        val usable = h - topPad * 2f
        val baseCoreR = min(max(3.2f, edgeInset * 0.38f), 6.5f)

        for (i in 0 until DOT_COUNT) {
            val t = if (DOT_COUNT == 1) 0.5f else i / (DOT_COUNT - 1).toFloat()
            // Larger near mid-edge, smaller toward corners + organic jitter
            val midBoost = 1f - abs(t - 0.5f) * 0.7f
            val coreR = baseCoreR * midBoost * sizeJitter[i]
            val bloomR = bloomReach * (0.55f + 0.45f * midBoost) * (0.9f + 0.15f * sizeJitter[i])

            // Alpha stronger mid-column (peripheral contrast feel)
            val midWeight = 1f - abs(t - 0.5f) * 1.15f
            val coreAlpha = (0x55 + (midWeight * 0x55).toInt()).coerceIn(0x48, 0xB0)
            val bloomAlpha = (0x28 + (midWeight * 0x38).toInt()).coerceIn(0x18, 0x70)

            val yBase = topPad + usable * t
            val yLeft = yBase + offsetY + parallaxY
            val yRight = yBase + offsetY - parallaxY

            val lx = edgeInset + offsetX
            val rx = w - edgeInset + offsetX

            drawCue(canvas, lx, yLeft, coreR, bloomR, coreAlpha, bloomAlpha, inwardRight = true)
            drawCue(canvas, rx, yRight, coreR, bloomR, coreAlpha, bloomAlpha, inwardRight = false)
        }

        if (running) postInvalidateOnAnimation()
    }

    /**
     * Core disc + soft radial bloom stretched toward screen center.
     * @param inwardRight true for left-edge cues (bloom extends +X); false for right-edge.
     */
    private fun drawCue(
        canvas: Canvas,
        cx: Float,
        cy: Float,
        coreR: Float,
        bloomR: Float,
        coreAlpha: Int,
        bloomAlpha: Int,
        inwardRight: Boolean
    ) {
        // Elongated inward bloom: scale X so most of the glow sits toward content
        val stretchX = 2.6f
        canvas.save()
        canvas.translate(cx, cy)
        // Nudge bloom center slightly inward so soft falloff fills the edge band
        val nudge = if (inwardRight) bloomR * 0.12f else -bloomR * 0.12f
        canvas.translate(nudge, 0f)
        canvas.scale(stretchX, 1f)

        val haloColor = Color.argb(bloomAlpha, HALO_RGB[0], HALO_RGB[1], HALO_RGB[2])
        val midColor = Color.argb((bloomAlpha * 0.45f).toInt(), HALO_RGB[0], HALO_RGB[1], HALO_RGB[2])
        bloomPaint.shader = RadialGradient(
            0f, 0f, bloomR / stretchX,
            intArrayOf(haloColor, midColor, Color.TRANSPARENT),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(0f, 0f, bloomR / stretchX, bloomPaint)
        bloomPaint.shader = null
        canvas.restore()

        // Brighter soft core (slight secondary halo for Apple-like softness)
        val softCore = Color.argb((coreAlpha * 0.35f).toInt(), CORE_RGB[0], CORE_RGB[1], CORE_RGB[2])
        corePaint.shader = RadialGradient(
            cx, cy, coreR * 2.1f,
            intArrayOf(softCore, Color.TRANSPARENT),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreR * 2.1f, corePaint)

        corePaint.shader = null
        corePaint.color = Color.argb(coreAlpha, CORE_RGB[0], CORE_RGB[1], CORE_RGB[2])
        canvas.drawCircle(cx, cy, coreR, corePaint)
    }
}
