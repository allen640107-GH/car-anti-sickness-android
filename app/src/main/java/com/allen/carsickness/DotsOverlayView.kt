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
 * (https://youtube.com/shorts/cl3lPLPKBJI) — v1.1.7.
 *
 * Motion model (iPhone VMC): one shared offset for the whole column,
 * critically/over-damped exponential follow — continuous glide with
 * inertial lag, NO spring bounce/overshoot. Calm = nearly still sparse
 * edge column; motion = soft mid/dense fade-in. Dual-layer: light halo
 * (#F5F5F5) under near-black core — readable on light and dark UIs.
 *
 * Idle: ~4 primary dual-layer dots per L/R edge. Mid intercalated at
 * ~5.5% width inward; size ratios ~93% L / ~72% R. Soft envelopes.
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

        /** Soft light halo slightly larger than core (contrast ring). */
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

        private const val MAX_TRAVEL_Y_DP = 432f
        /**
         * Horizontal travel must be large enough to FEEL (Allen: 左右也要滑動).
         * Drawn X is clamped to ±this from each edge base — was previously
         * locked to a ~1-diameter band so offsetX was invisible.
         */
        private const val MAX_TRAVEL_X_DP = 60f
        private const val GAIN_Y = 180f
        private const val GAIN_X = 36.0f

        /**
         * Exponential smoothing toward sensor target (per frame @~60fps).
         * Replaces underdamped spring (was STIFFNESS=0.20 / DAMPING=0.78).
         * Higher FOLLOW = snappier; keep low for iPhone-like viscous glide.
         * No velocity → no overshoot.
         */
        private const val OFFSET_FOLLOW = 0.085f

        /** Low-pass on raw accel→target before OFFSET_FOLLOW (kills micro-jitter). */
        private const val TARGET_LP = 0.12f

        private const val GRAVITY_ALPHA = 0.92f

        /**
         * Idle: iPhone at rest is calm / nearly still. Was 6–18 dp Y sin
         * wobble + 0.8 dp X; now tiny optional drift only under motion.
         */
        private const val IDLE_DRIFT_Y_DP_MIN = 0.0f
        private const val IDLE_DRIFT_Y_DP_MAX = 2.5f
        private const val IDLE_DRIFT_X_DP = 0.15f
        private const val IDLE_PERIOD_MS_SLOW = 9000f
        private const val IDLE_PERIOD_MS_FAST = 5500f

        /** Near-black core — high rest alpha so force-show is obvious. */
        private const val CORE_ALPHA_BASE = 0.88f
        private const val CORE_ALPHA_MAX = 0.96f
        /** Soft light halo — readable on dark Android chrome. */
        private const val HALO_ALPHA_BASE = 0.55f
        private const val HALO_ALPHA_MAX = 0.70f

        private const val MID_ALPHA_MAX = 0.90f
        private const val DENSE_ALPHA_MAX = 0.75f

        /** Was 0.08 — high-freq opacity wobble read as 碎; nearly off. */
        private const val PHASE_ALPHA_AMP = 0.015f

        /** Slightly higher deadzone to ignore tiny sensor noise. */
        private const val INTENSITY_DEADZONE = 0.18f
        private const val INTENSITY_FULL = 1.6f
        /** Slower envelopes so mid-dots glide in, not pop. */
        private const val INTENSITY_ATTACK = 0.055f
        private const val INTENSITY_RELEASE = 0.028f

        private const val MID_FADE_START = 0.08f
        private const val MID_FADE_END = 0.48f
        private const val DENSE_FADE_START = 0.42f
        private const val DENSE_FADE_END = 0.88f

        /** Accel deadzone (m/s²) before mapping into travel — cuts micro-jitter. */
        private const val ACCEL_DEADZONE = 0.06f
    }

    private val density = resources.displayMetrics.density

    private val corePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.rgb(0x0A, 0x0A, 0x0A)
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        // Light outer ring under dark core (v1.1.3/1.1.4 dual-layer)
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

    /** Shared column offset — whole cue layer moves as one (iPhone look). */
    private var offsetX = 0f
    private var offsetY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private var smoothTargetX = 0f
    private var smoothTargetY = 0f

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
        targetX = 0f
        targetY = 0f
        smoothTargetX = 0f
        smoothTargetY = 0f
        offsetX = 0f
        offsetY = 0f
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

        val axDz = deadzone(ax, ACCEL_DEADZONE)
        val ayDz = deadzone(ay, ACCEL_DEADZONE)

        val maxX = MAX_TRAVEL_X_DP * density
        val maxY = MAX_TRAVEL_Y_DP * density
        // Reverse cue: dots lag opposite to phone accel (inertial reference).
        targetX = (-axDz * GAIN_X).coerceIn(-maxX, maxX)
        targetY = (-ayDz * GAIN_Y).coerceIn(-maxY, maxY)

        val mag = hypot(axDz.toDouble(), ayDz.toDouble()).toFloat()
        val raw = ((mag - INTENSITY_DEADZONE) / (INTENSITY_FULL - INTENSITY_DEADZONE))
            .coerceIn(0f, 1f)
        targetIntensity = smoothstep(raw)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // Two-stage low-pass: sensor target → smoothTarget → shared offset.
        // No spring velocity → no overshoot / bounce (iPhone VMC glide).
        smoothTargetX += (targetX - smoothTargetX) * TARGET_LP
        smoothTargetY += (targetY - smoothTargetY) * TARGET_LP
        offsetX += (smoothTargetX - offsetX) * OFFSET_FOLLOW
        offsetY += (smoothTargetY - offsetY) * OFFSET_FOLLOW

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

        // Idle drift only rises gently with intensity; calm ≈ 0.
        val driftYDp = IDLE_DRIFT_Y_DP_MIN +
            (IDLE_DRIFT_Y_DP_MAX - IDLE_DRIFT_Y_DP_MIN) * intensity
        val idleYMax = driftYDp * density
        val idleXMax = IDLE_DRIFT_X_DP * density * intensity
        val period = IDLE_PERIOD_MS_SLOW +
            (IDLE_PERIOD_MS_FAST - IDLE_PERIOD_MS_SLOW) * intensity

        val elapsed = if (animStartMs == 0L) 0f
        else (System.currentTimeMillis() - animStartMs).toFloat()
        val tau = (elapsed / period) * (Math.PI.toFloat() * 2f)

        // One shared column drift (not per-dot independent bounce).
        val sharedIdleY = if (idleYMax > 0.01f) idleYMax * sin(tau * 0.35f) else 0f
        val sharedIdleX = if (idleXMax > 0.01f) idleXMax * sin(tau * 0.22f) else 0f

        val leftBaseX = edgeInset
        val rightBaseX = w - edgeInset
        // Allow real horizontal glide (±MAX_TRAVEL_X from edge base).
        // Old marginMax clamp was ~1 diameter wide → offsetX looked like "only Y".
        val xTravel = (MAX_TRAVEL_X_DP * density).coerceAtMost(w * 0.14f)

        val coreA = CORE_ALPHA_BASE + (CORE_ALPHA_MAX - CORE_ALPHA_BASE) * intensity
        val haloA = HALO_ALPHA_BASE + (HALO_ALPHA_MAX - HALO_ALPHA_BASE) * intensity

        // Primary: dual-layer contrast, same X as edge column
        for (i in 0 until PRIMARY_COUNT) {
            val c = phasedAlpha(coreA, tau, primaryPhase[i])
            val hl = phasedAlpha(haloA, tau, primaryPhase[i] + 0.3f)
            drawDualPair(
                canvas, primaryYNorm[i],
                primaryR, primaryR,
                topPad, bottomPad, usable, h, w,
                leftBaseX, rightBaseX, xTravel,
                sharedIdleY, sharedIdleX, c, hl
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
            drawDualPair(
                canvas, midYNorm[i],
                midRL, midRR,
                topPad, bottomPad, usable, h, w,
                leftBaseX + midInward, rightBaseX - midInward, xTravel,
                sharedIdleY, sharedIdleX, c, hl
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
                canvas, denseYNorm[i],
                denseRL, denseRR,
                topPad, bottomPad, usable, h, w,
                leftBaseX + denseInward, rightBaseX - denseInward, xTravel,
                sharedIdleY, sharedIdleX, c, hl
            )
        }

        if (running) postInvalidateOnAnimation()
    }

    private fun phasedAlpha(base: Float, tau: Float, phase: Float): Float {
        if (PHASE_ALPHA_AMP < 0.001f) return base.coerceIn(0.20f, 1f)
        val wobble = 1f + PHASE_ALPHA_AMP * sin(tau * 0.25f + phase * 0.8f)
        return (base * wobble).coerceIn(0.20f, 1f)
    }

    /**
     * Draw L+R dual-layer dots. Halo first (light), then dark core on top.
     * Shared [idleY]/[idleX] — whole column glides together (no per-dot sin).
     * [xTravel] = max horizontal slide from each edge base (fixes invisible X).
     */
    private fun drawDualPair(
        canvas: Canvas,
        t: Float,
        radiusL: Float,
        radiusR: Float,
        topPad: Float,
        bottomPad: Float,
        usable: Float,
        h: Float,
        w: Float,
        leftBaseX: Float,
        rightBaseX: Float,
        xTravel: Float,
        idleY: Float,
        idleX: Float,
        coreAlpha: Float,
        haloAlpha: Float
    ) {
        val yBase = topPad + usable * t
        val yLeft = (yBase + offsetY + idleY).coerceIn(topPad, h - bottomPad)
        val yRight = (yBase + offsetY + idleY).coerceIn(topPad, h - bottomPad)

        // Shared offsetX moves both columns together (iPhone cue layer).
        // Clamp to ±xTravel from base so L/R stay near edges but X is visible.
        val lx = (leftBaseX + offsetX + idleX).coerceIn(
            (leftBaseX - xTravel).coerceAtLeast(radiusL),
            (leftBaseX + xTravel).coerceAtMost(w * 0.45f)
        )
        val rx = (rightBaseX + offsetX - idleX).coerceIn(
            (rightBaseX - xTravel).coerceAtLeast(w * 0.55f),
            (rightBaseX + xTravel).coerceAtMost(w - radiusR)
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

    private fun deadzone(v: Float, dz: Float): Float {
        return when {
            v > dz -> v - dz
            v < -dz -> v + dz
            else -> 0f
        }
    }

    private fun smoothstep(x: Float): Float {
        val t = x.coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
