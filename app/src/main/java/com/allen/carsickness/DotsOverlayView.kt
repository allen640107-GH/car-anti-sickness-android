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
 * (https://youtube.com/shorts/cl3lPLPKBJI) — v1.1.3.
 *
 * Idle: ~4 primary dual-layer dots per L/R edge (light halo + dark core)
 * so they stay readable on light AND dark UIs. Mid-dots at ~0 alpha.
 *
 * Motion ↑: column speeds up; intercalated mid-dots fade in at vertical
 * midpoints AND shifted inward (~5–8% width / ~55px @1080) toward center.
 * Left mid ≈90–95% primary diameter; right mid ≈70–75%. Soft spring;
 * per-dot phase alpha; extras fade when calm.
 */
class DotsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    companion object {
        private const val PRIMARY_COUNT = 4

        /** Primary diameter ≈2.8% screen width (Short stills ~2.5–3.3%). */
        private const val PRIMARY_DIAMETER_FRACTION = 0.028f

        /**
         * Short t28.2: left intercalated ~90–95% primary diameter;
         * right intercalated clearly smaller ~70–75%.
         */
        private const val MID_SIZE_RATIO_LEFT = 0.93f
        private const val MID_SIZE_RATIO_RIGHT = 0.72f
        private const val DENSE_SIZE_RATIO_LEFT = 0.70f
        private const val DENSE_SIZE_RATIO_RIGHT = 0.55f

        /** Halo slightly larger than core (Apple-style contrast ring). */
        private const val HALO_SCALE = 1.28f

        /** Slight primary swell under motion. */
        private const val PRIMARY_SWELL = 0.08f

        private const val EDGE_INSET_DIAMETERS = 1.35f
        private const val TOP_PAD_FRACTION = 0.10f
        private const val BOTTOM_PAD_FRACTION = 0.10f

        /**
         * Inward offset for intercalated mid-tier (~55px @1080 ≈ 5.1%;
         * Short analysis: 5–8% of width toward center).
         */
        private const val MID_INWARD_FRACTION = 0.055f
        private const val DENSE_INWARD_FRACTION = 0.075f

        private const val MAX_TRAVEL_Y_DP = 36f
        private const val MAX_TRAVEL_X_DP = 5f
        private const val GAIN_Y = 16f
        private const val GAIN_X = 3.2f

        /** Spring-damper toward sensor target (natural return, no snap). */
        private const val SPRING_STIFFNESS = 0.20f
        private const val SPRING_DAMPING = 0.78f

        private const val GRAVITY_ALPHA = 0.88f

        /** Quiet ~25–70 px/s; motion ~100–165 px/s (scaled via period + amp). */
        private const val IDLE_DRIFT_Y_DP_MIN = 6.0f
        private const val IDLE_DRIFT_Y_DP_MAX = 18.0f
        private const val IDLE_DRIFT_X_DP = 0.8f
        private const val IDLE_PERIOD_MS_SLOW = 4800f
        private const val IDLE_PERIOD_MS_FAST = 1800f

        /** Dark core — high rest alpha so force-show is obvious. */
        private const val CORE_ALPHA_BASE = 0.88f
        private const val CORE_ALPHA_MAX = 0.96f
        /** Soft light halo — readable on dark Android chrome. */
        private const val HALO_ALPHA_BASE = 0.55f
        private const val HALO_ALPHA_MAX = 0.70f

        private const val MID_ALPHA_MAX = 0.90f
        private const val DENSE_ALPHA_MAX = 0.75f

        /** Per-dot phase: mild translucency wobble (keep from 1.1.2). */
        private const val PHASE_ALPHA_AMP = 0.08f

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

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0x0A, 0x0A, 0x0A)
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0xF5, 0xF5, 0xF5)
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

        velX = (velX + (targetX - offsetX) * SPRING_STIFFNESS) * SPRING_DAMPING
        velY = (velY + (targetY - offsetY) * SPRING_STIFFNESS) * SPRING_DAMPING
        offsetX += velX
        offsetY += velY

        val iRate = if (targetIntensity > intensity) INTENSITY_ATTACK else INTENSITY_RELEASE
        intensity += (targetIntensity - intensity) * iRate

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        val primaryDiam = (w * PRIMARY_DIAMETER_FRACTION).coerceIn(10f * density, 18f * density)
        val swell = 1f + PRIMARY_SWELL * intensity
        val primaryR = primaryDiam * 0.5f * swell
        val midRL = primaryDiam * 0.5f * MID_SIZE_RATIO_LEFT
        val midRR = primaryDiam * 0.5f * MID_SIZE_RATIO_RIGHT
        val denseRL = primaryDiam * 0.5f * DENSE_SIZE_RATIO_LEFT
        val denseRR = primaryDiam * 0.5f * DENSE_SIZE_RATIO_RIGHT
        val edgeInset = primaryDiam * EDGE_INSET_DIAMETERS
        val midInward = w * MID_INWARD_FRACTION
        val denseInward = w * DENSE_INWARD_FRACTION

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

        val leftBaseX = edgeInset
        val rightBaseX = w - edgeInset
        // Keep primaries near the physical edge (black L/R edge cues).
        val primaryMargin = edgeInset + primaryDiam * 0.55f

        val coreA = CORE_ALPHA_BASE + (CORE_ALPHA_MAX - CORE_ALPHA_BASE) * intensity
        val haloA = HALO_ALPHA_BASE + (HALO_ALPHA_MAX - HALO_ALPHA_BASE) * intensity

        // Primary: dual-layer contrast, same X as edge column
        for (i in 0 until PRIMARY_COUNT) {
            val c = phasedAlpha(coreA, tau, primaryPhase[i])
            val hl = phasedAlpha(haloA, tau, primaryPhase[i] + 0.3f)
            drawDualPair(
                canvas, primaryYNorm[i], primaryPhase[i],
                primaryR, primaryR,
                topPad, bottomPad, usable, h, w,
                leftBaseX, rightBaseX, primaryMargin, primaryMargin,
                0f, idleYMax, idleXMax, tau, c, hl
            )
        }

        // Mid intercalated: vertical midpoints + inward X offset
        for (i in midYNorm.indices) {
            val local = ((intensity - (MID_FADE_START + midStagger[i])) /
                (MID_FADE_END - MID_FADE_START)).coerceIn(0f, 1f)
            val envelope = smoothstep(local) * MID_ALPHA_MAX
            if (envelope < 0.01f) continue
            val c = phasedAlpha(envelope * 0.95f, tau, midPhase[i])
            val hl = phasedAlpha(envelope * 0.60f, tau, midPhase[i] + 0.25f)
            val midMarginL = leftBaseX + midInward + midRL * 1.2f
            val midMarginR = w - (rightBaseX - midInward) + midRR * 1.2f
            drawDualPair(
                canvas, midYNorm[i], midPhase[i],
                midRL, midRR,
                topPad, bottomPad, usable, h, w,
                leftBaseX + midInward, rightBaseX - midInward,
                midMarginL.coerceAtLeast(midRL), midMarginR.coerceAtLeast(midRR),
                midInward, idleYMax * 1.05f, idleXMax, tau, c, hl
            )
        }

        // Dense second-tier under stronger motion (further inward, smaller)
        for (i in denseYNorm.indices) {
            val local = ((intensity - (DENSE_FADE_START + denseStagger[i])) /
                (DENSE_FADE_END - DENSE_FADE_START)).coerceIn(0f, 1f)
            val envelope = smoothstep(local) * DENSE_ALPHA_MAX
            if (envelope < 0.01f) continue
            val c = phasedAlpha(envelope * 0.92f, tau, densePhase[i])
            val hl = phasedAlpha(envelope * 0.55f, tau, densePhase[i] + 0.2f)
            drawDualPair(
                canvas, denseYNorm[i], densePhase[i],
                denseRL, denseRR,
                topPad, bottomPad, usable, h, w,
                leftBaseX + denseInward, rightBaseX - denseInward,
                leftBaseX + denseInward + denseRL * 1.2f,
                w - (rightBaseX - denseInward) + denseRR * 1.2f,
                denseInward, idleYMax * 1.1f, idleXMax, tau, c, hl
            )
        }

        if (running) postInvalidateOnAnimation()
    }

    private fun phasedAlpha(base: Float, tau: Float, phase: Float): Float {
        val wobble = 1f + PHASE_ALPHA_AMP * sin(tau * 0.65f + phase * 1.45f)
        return (base * wobble).coerceIn(0.20f, 1f)
    }

    /**
     * Draw L+R dual-layer dots. Halo first (light), then dark core on top.
     * [inwardHint] unused for layout (bases already shifted); kept for clarity.
     */
    private fun drawDualPair(
        canvas: Canvas,
        t: Float,
        phase: Float,
        radiusL: Float,
        radiusR: Float,
        topPad: Float,
        bottomPad: Float,
        usable: Float,
        h: Float,
        w: Float,
        leftBaseX: Float,
        rightBaseX: Float,
        marginMaxL: Float,
        marginMaxR: Float,
        @Suppress("UNUSED_PARAMETER") inwardHint: Float,
        idleYMax: Float,
        idleXMax: Float,
        tau: Float,
        coreAlpha: Float,
        haloAlpha: Float
    ) {
        val idleY = idleYMax * sin(tau * 0.9f + phase)
        val idleX = idleXMax * sin(tau * 0.45f + phase * 1.2f)

        val yBase = topPad + usable * t
        val yLeft = (yBase + offsetY + idleY).coerceIn(topPad, h - bottomPad)
        val yRight = (yBase + offsetY - idleY * 0.7f).coerceIn(topPad, h - bottomPad)

        val lx = (leftBaseX + offsetX + idleX).coerceIn(radiusL, marginMaxL.coerceAtLeast(radiusL))
        val rx = (rightBaseX + offsetX - idleX).coerceIn(
            (w - marginMaxR).coerceAtMost(w - radiusR),
            w - radiusR
        )

        // Left: halo then core
        haloPaint.alpha = (haloAlpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(lx, yLeft, radiusL * HALO_SCALE, haloPaint)
        corePaint.alpha = (coreAlpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(lx, yLeft, radiusL, corePaint)

        // Right: halo then core
        haloPaint.alpha = (haloAlpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(rx, yRight, radiusR * HALO_SCALE, haloPaint)
        corePaint.alpha = (coreAlpha * 255f).toInt().coerceIn(0, 255)
        canvas.drawCircle(rx, yRight, radiusR, corePaint)
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
