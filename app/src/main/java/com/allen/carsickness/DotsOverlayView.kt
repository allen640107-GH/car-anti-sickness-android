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
 * Soft black / dark-gray feathered dots only on the left and right edges,
 * moving opposite to linear acceleration so vision matches vestibular motion.
 *
 * Visual: dual-layer (faint light halo + dark core) with RadialGradient
 * feathering and a short inward soft falloff; center content stays clear.
 */
class DotsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    companion object {
        /** ~8–12 per side, matching Apple Almond / Control Center demos. */
        private const val DOT_COUNT = 10
        /** Soft band as fraction of screen width (inward bloom reach). */
        private const val BAND_FRACTION = 0.085f
        private const val EDGE_INSET_FRACTION = 0.022f
        private const val TOP_PAD_FRACTION = 0.07f
        private const val MAX_TRAVEL_X = 48f
        private const val MAX_TRAVEL_Y = 36f
        /** High enough that 強制顯示 + tilt/shake clearly moves dots. */
        private const val GAIN = 14f
        private const val SMOOTH = 0.32f
        private const val GRAVITY_ALPHA = 0.85f

        // Soft charcoal core (iOS dark dots)
        private val CORE_RGB = intArrayOf(0x1A, 0x1A, 0x1C)
        // Faint light halo under dark core for contrast on dark UIs
        private val HALO_RGB = intArrayOf(0xF2, 0xF2, 0xF5)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    /** Organic size multipliers (stable across frames). */
    private val sizeJitter: FloatArray = FloatArray(DOT_COUNT) { i ->
        0.72f + 0.36f * (0.5f + 0.5f * sin(i * 2.713f + 1.1f))
    }

    /**
     * Irregular vertical spacing (not a ruler). Values in [0,1] along usable height.
     * Built once so L/R columns share the same organic rhythm.
     */
    private val yNorm: FloatArray = run {
        val raw = FloatArray(DOT_COUNT) { i ->
            val t = if (DOT_COUNT == 1) 0.5f else i / (DOT_COUNT - 1).toFloat()
            // Irregular wobble; keep endpoints near top/bottom pads
            val wobble = 0.028f * sin(i * 3.37f + 0.4f) + 0.018f * sin(i * 5.91f)
            (t + wobble).coerceIn(0.02f, 0.98f)
        }
        // Ensure strictly increasing after wobble
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

    /** Subtle opposite vertical parallax between L/R columns. */
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
        // Vestibular match (opposite to acceleration):
        //   accel right → dots left; left → right
        //   accel up/forward → dots down; brake/down → dots up
        // Sensor +X = device right; Canvas +X = right → negate for opposite.
        // Sensor +Y = device up; Canvas +Y = down → keep sign for opposite.
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

        val band = max(36f, w * BAND_FRACTION)
        val edgeInset = max(12f, w * EDGE_INSET_FRACTION)
        val topPad = h * TOP_PAD_FRACTION
        val usable = h - topPad * 2f
        // Soft circular dots — slightly larger mid-edge
        val baseR = min(max(5.5f, edgeInset * 0.55f), 11f)

        for (i in 0 until DOT_COUNT) {
            val t = yNorm[i]
            val midBoost = 1f - abs(t - 0.5f) * 0.65f
            val coreR = baseR * midBoost * sizeJitter[i]
            // Feather radius: soft bloom, short inward reach (~band)
            val featherR = band * (0.42f + 0.38f * midBoost) * (0.88f + 0.18f * sizeJitter[i])

            // Stronger mid-column, softer toward corners
            val midWeight = (1f - abs(t - 0.5f) * 1.05f).coerceIn(0.35f, 1f)
            val coreAlpha = (0x58 + (midWeight * 0x48).toInt()).coerceIn(0x40, 0xA8)
            val haloAlpha = (0x18 + (midWeight * 0x1C).toInt()).coerceIn(0x10, 0x38)

            val yBase = topPad + usable * t
            val yLeft = yBase + offsetY + parallaxY
            val yRight = yBase + offsetY - parallaxY

            val lx = edgeInset + offsetX
            val rx = w - edgeInset + offsetX

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
        // Mild X stretch so soft falloff fills the edge band toward content
        val stretchX = 1.85f
        val nudge = if (inwardRight) featherR * 0.10f else -featherR * 0.10f

        canvas.save()
        canvas.translate(cx + nudge, cy)
        canvas.scale(stretchX, 1f)

        // 1) Faint light halo (helps dark cores read on dark UIs)
        val haloOuter = Color.argb(haloAlpha, HALO_RGB[0], HALO_RGB[1], HALO_RGB[2])
        val haloMid = Color.argb((haloAlpha * 0.4f).toInt(), HALO_RGB[0], HALO_RGB[1], HALO_RGB[2])
        paint.shader = RadialGradient(
            0f, 0f, featherR / stretchX,
            intArrayOf(haloOuter, haloMid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(0f, 0f, featherR / stretchX, paint)

        // 2) Dark soft core with feathered edge (main iOS look)
        val darkCore = Color.argb(coreAlpha, CORE_RGB[0], CORE_RGB[1], CORE_RGB[2])
        val darkMid = Color.argb((coreAlpha * 0.45f).toInt(), CORE_RGB[0], CORE_RGB[1], CORE_RGB[2])
        val darkR = max(coreR * 2.4f, featherR * 0.55f) / stretchX
        paint.shader = RadialGradient(
            0f, 0f, darkR,
            intArrayOf(darkCore, darkMid, Color.TRANSPARENT),
            floatArrayOf(0f, 0.38f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(0f, 0f, darkR, paint)
        paint.shader = null
        canvas.restore()
    }
}
