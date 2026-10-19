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
import kotlin.math.hypot
import kotlin.math.sin

/**
 * YouTube Short ground-truth Vehicle Motion Cues look-alike
 * (https://youtube.com/shorts/cl3lPLPKBJI).
 *
 * Idle: ~4 subtler primary black dots per L/R edge (mid-dots ~0 alpha).
 * Motion ↑: primary opacity ramps toward solid; smaller intercalated dots
 * fade in BETWEEN primaries; denser tier under strong motion; slight swell;
 * per-dot phase alpha (半透明變化). Soft spring toward accel displacement;
 * extras fade out when calm.
 *
 * Black filled only — no cyan bloom. Vertical-primary motion opposite accel.
 */
class DotsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    companion object {
        private const val PRIMARY_COUNT = 4

        /** Primary diameter ≈2.8% screen width (Short stills ~2.5–3.3%). */
        private const val PRIMARY_DIAMETER_FRACTION = 0.028f

        /** Mid/primary ≈0.42 from Short measurements (~0.35–0.45). */
        private const val MID_SIZE_RATIO = 0.42f
        private const val DENSE_SIZE_RATIO = 0.30f

        /** Slight primary swell under motion. */
        private const val PRIMARY_SWELL = 0.10f

        private const val EDGE_INSET_DIAMETERS = 1.5f
        private const val TOP_PAD_FRACTION = 0.10f
        private const val BOTTOM_PAD_FRACTION = 0.10f

        private const val MAX_TRAVEL_Y_DP = 30f
        private const val MAX_TRAVEL_X_DP = 4f
        private const val GAIN_Y = 14f
        private const val GAIN_X = 3.0f

        /**
         * Spring-damper toward sensor target (natural return, no snap).
         * vel += (target - pos) * STIFFNESS; vel *= DAMPING; pos += vel
         */
        private const val SPRING_STIFFNESS = 0.20f
        private const val SPRING_DAMPING = 0.78f

        private const val GRAVITY_ALPHA = 0.88f

        private const val IDLE_DRIFT_Y_DP_MIN = 5.0f
        private const val IDLE_DRIFT_Y_DP_MAX = 13.0f
        private const val IDLE_DRIFT_X_DP = 0.7f
        private const val IDLE_PERIOD_MS_SLOW = 5200f
        private const val IDLE_PERIOD_MS_FAST = 2400f

        private val DOT_RGB = intArrayOf(0x0A, 0x0A, 0x0A)

        /**
         * Opacity research (clones/demos; Apple unpublished):
         * rest primary ~0.3–0.5; motion → ~0.85–1.0; mid near 0 at rest.
         */
        private const val PRIMARY_ALPHA_BASE = 0.38f
        private const val PRIMARY_ALPHA_MAX = 0.92f
        private const val MID_ALPHA_MAX = 0.86f
        private const val DENSE_ALPHA_MAX = 0.72f

        /** Per-dot phase: some more translucent, some denser (半透明變化). */
        private const val PHASE_ALPHA_AMP = 0.14f

        private const val INTENSITY_DEADZONE = 0.12f
        private const val INTENSITY_FULL = 1.6f
        private const val INTENSITY_ATTACK = 0.12f
        private const val INTENSITY_RELEASE = 0.045f

        private const val MID_FADE_START = 0.08f
        private const val MID_FADE_END = 0.42f
        private const val DENSE_FADE_START = 0.40f
        private const val DENSE_FADE_END = 0.85f
    }

    private val density = resources.displayMetrics.density

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.argb(255, DOT_RGB[0], DOT_RGB[1], DOT_RGB[2])
    }

    private val primaryYNorm: FloatArray = floatArrayOf(0.12f, 0.38f, 0.61f, 0.86f)

    private val midYNorm: FloatArray = FloatArray(PRIMARY_COUNT - 1) { i ->
        (primaryYNorm[i] + primaryYNorm[i + 1]) * 0.5f
    }

    private val denseYNorm: FloatArray = FloatArray((PRIMARY_COUNT - 1) * 2) { i ->
        val pair = i / 2
        val which = i % 2
        val a = primaryYNorm[pair]
        val b = primaryYNorm[pair + 1]
        if (which == 0) a + (b - a) * 0.25f else a + (b - a) * 0.75f
    }

    private val primaryPhase: FloatArray = FloatArray(PRIMARY_COUNT) { i ->
        i * 1.37f + 0.55f * sin(i * 2.1f)
    }
    private val midPhase: FloatArray = FloatArray(midYNorm.size) { i ->
        i * 1.91f + 0.73f * sin(i * 1.7f + 0.4f)
    }
    private val densePhase: FloatArray = FloatArray(denseYNorm.size) { i ->
        i * 1.13f + 0.61f * sin(i * 2.4f + 1.1f)
    }

    private val midStagger: FloatArray = floatArrayOf(0.00f, 0.06f, 0.03f)
    private val denseStagger: FloatArray = FloatArray(denseYNorm.size) { i ->
        (i % 3) * 0.04f
    }

    private var offsetX = 0f
    private var offsetY = 0f
    private var velX = 0f
    private var velY = 0f
    private var targetX = 0f
    private var targetY = 0f

    private var intensity = 0f
    private var targetIntensity = 0f

    private var sensorManager: SensorManager? = null
    private var running = false

    private val gravity = FloatArray(3)
    private var gravityInitialized = false

    private var animStartMs = 0L

    fun startSensors() {
        if (running) return
        running = true
        gravityInitialized = false
        intensity = 0f
        targetIntensity = 0f
        velX = 0f
        velY = 0f
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
        // Opposite accel (vestibular). Displacement ∝ lateral G; rest when →0.
        targetX = (-ax * GAIN_X).coerceIn(-maxX, maxX)
        targetY = (-ay * GAIN_Y).coerceIn(-maxY, maxY)

        val mag = hypot(ax.toDouble(), ay.toDouble()).toFloat()
        val raw = ((mag - INTENSITY_DEADZONE) / (INTENSITY_FULL - INTENSITY_DEADZONE))
            .coerceIn(0f, 1f)
        targetIntensity = smoothstep(raw)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Spring-damper: soft pull to target, damped velocity, natural settle
        velX = (velX + (targetX - offsetX) * SPRING_STIFFNESS) * SPRING_DAMPING
        velY = (velY + (targetY - offsetY) * SPRING_STIFFNESS) * SPRING_DAMPING
        offsetX += velX
        offsetY += velY

        val iRate = if (targetIntensity > intensity) INTENSITY_ATTACK else INTENSITY_RELEASE
        intensity += (targetIntensity - intensity) * iRate

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val primaryDiam = (w * PRIMARY_DIAMETER_FRACTION).coerceIn(9f * density, 16f * density)
        val primaryR = primaryDiam * 0.5f * (1f + PRIMARY_SWELL * intensity)
        val midR = primaryDiam * 0.5f * MID_SIZE_RATIO
        val denseR = primaryDiam * 0.5f * DENSE_SIZE_RATIO
        val edgeInset = primaryDiam * EDGE_INSET_DIAMETERS

        val topPad = h * TOP_PAD_FRACTION
        val bottomPad = h * BOTTOM_PAD_FRACTION
        val usable = h - topPad - bottomPad

        val driftYDp = IDLE_DRIFT_Y_DP_MIN +
            (IDLE_DRIFT_Y_DP_MAX - IDLE_DRIFT_Y_DP_MIN) * intensity
        val idleYMax = driftYDp * density
        val idleXMax = IDLE_DRIFT_X_DP * density
        val period = IDLE_PERIOD_MS_SLOW +
            (IDLE_PERIOD_MS_FAST - IDLE_PERIOD_MS_SLOW) * intensity

        val elapsed = if (animStartMs == 0L) 0f
        else (System.currentTimeMillis() - animStartMs).toFloat()
        val tau = (elapsed / period) * (Math.PI.toFloat() * 2f)

        val marginMax = edgeInset + primaryDiam * 0.6f
        val leftBaseX = edgeInset
        val rightBaseX = w - edgeInset

        // Primary: base→max alpha with intensity + per-dot phase translucency
        val primaryA = PRIMARY_ALPHA_BASE +
            (PRIMARY_ALPHA_MAX - PRIMARY_ALPHA_BASE) * intensity
        for (i in 0 until PRIMARY_COUNT) {
            val a = phasedAlpha(primaryA, tau, primaryPhase[i])
            paint.alpha = (a * 255f).toInt().coerceIn(0, 255)
            drawPair(
                canvas, primaryYNorm[i], primaryPhase[i], primaryR,
                topPad, bottomPad, usable, h, w,
                leftBaseX, rightBaseX, marginMax,
                idleYMax, idleXMax, tau
            )
        }

        // Mid intercalated: 0 at rest → fade in between primaries
        for (i in midYNorm.indices) {
            val local = ((intensity - (MID_FADE_START + midStagger[i])) /
                (MID_FADE_END - MID_FADE_START)).coerceIn(0f, 1f)
            val envelope = smoothstep(local) * MID_ALPHA_MAX
            if (envelope < 0.01f) continue
            val a = phasedAlpha(envelope, tau, midPhase[i])
            paint.alpha = (a * 255f).toInt().coerceIn(0, 255)
            drawPair(
                canvas, midYNorm[i], midPhase[i], midR,
                topPad, bottomPad, usable, h, w,
                leftBaseX, rightBaseX, marginMax,
                idleYMax * 1.05f, idleXMax, tau
            )
        }

        // Dense second-tier under stronger motion
        for (i in denseYNorm.indices) {
            val local = ((intensity - (DENSE_FADE_START + denseStagger[i])) /
                (DENSE_FADE_END - DENSE_FADE_START)).coerceIn(0f, 1f)
            val envelope = smoothstep(local) * DENSE_ALPHA_MAX
            if (envelope < 0.01f) continue
            val a = phasedAlpha(envelope, tau, densePhase[i])
            paint.alpha = (a * 255f).toInt().coerceIn(0, 255)
            drawPair(
                canvas, denseYNorm[i], densePhase[i], denseR,
                topPad, bottomPad, usable, h, w,
                leftBaseX, rightBaseX, marginMax,
                idleYMax * 1.1f, idleXMax, tau
            )
        }

        if (running) postInvalidateOnAnimation()
    }

    private fun phasedAlpha(base: Float, tau: Float, phase: Float): Float {
        val wobble = 1f + PHASE_ALPHA_AMP * sin(tau * 0.65f + phase * 1.45f)
        return (base * wobble).coerceIn(0.08f, 1f)
    }

    private fun drawPair(
        canvas: Canvas,
        t: Float,
        phase: Float,
        radius: Float,
        topPad: Float,
        bottomPad: Float,
        usable: Float,
        h: Float,
        w: Float,
        leftBaseX: Float,
        rightBaseX: Float,
        marginMax: Float,
        idleYMax: Float,
        idleXMax: Float,
        tau: Float
    ) {
        val idleY = idleYMax * sin(tau * 0.9f + phase)
        val idleX = idleXMax * sin(tau * 0.45f + phase * 1.2f)

        val yBase = topPad + usable * t
        val yLeft = (yBase + offsetY + idleY).coerceIn(topPad, h - bottomPad)
        val yRight = (yBase + offsetY - idleY * 0.7f).coerceIn(topPad, h - bottomPad)

        val lx = (leftBaseX + offsetX + idleX).coerceIn(radius, marginMax)
        val rx = (rightBaseX + offsetX - idleX).coerceIn(w - marginMax, w - radius)

        canvas.drawCircle(lx, yLeft, radius, paint)
        canvas.drawCircle(rx, yRight, radius, paint)
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
