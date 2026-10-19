package com.allen.carsickness

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Edge-only semi-transparent dots (iOS Vehicle Motion Cues style).
 * Dots shift with accelerometer so eyes track vehicle motion.
 */
class DotsOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs), SensorEventListener {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = android.graphics.Color.argb(0x99, 0xB3, 0xE5, 0xFC)
        style = Paint.Style.FILL
    }

    private val edgeFraction = 0.045f  // ~4.5% of width on each side
    private val dotCount = 7
    private var offsetX = 0f
    private var offsetY = 0f
    private var targetX = 0f
    private var targetY = 0f
    private val smooth = 0.12f
    private var sensorManager: SensorManager? = null
    private var running = false
    private var lastSensorNs = 0L

    fun startSensors() {
        if (running) return
        running = true
        sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
            sensorManager?.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME)
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
        if (event?.sensor?.type != Sensor.TYPE_ACCELEROMETER) return
        val ax = event.values[0]
        val ay = event.values[1]
        // Phone typically upright in landscape/portrait car cradle:
        // lateral accel → horizontal shift; forward/back → vertical
        val gain = 4.5f
        targetX = (-ax * gain).coerceIn(-18f, 18f)
        targetY = (ay * gain * 0.35f).coerceIn(-12f, 12f)
        lastSensorNs = event.timestamp
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        offsetX += (targetX - offsetX) * smooth
        offsetY += (targetY - offsetY) * smooth

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0 || h <= 0) return

        val edgeW = max(18f, w * edgeFraction)
        val radius = min(edgeW * 0.28f, 7f)
        val topPad = h * 0.12f
        val usable = h - topPad * 2

        for (i in 0 until dotCount) {
            val t = if (dotCount == 1) 0.5f else i / (dotCount - 1).toFloat()
            val y = topPad + usable * t + offsetY
            // Left column
            val lx = edgeW * 0.5f + offsetX
            // Right column (mirrored offset)
            val rx = w - edgeW * 0.5f + offsetX

            val alphaBase = 0x66 + ((1f - kotlin.math.abs(t - 0.5f) * 1.2f) * 0x40).toInt()
            paint.alpha = alphaBase.coerceIn(0x40, 0xA0)

            canvas.drawCircle(lx, y, radius, paint)
            canvas.drawCircle(rx, y, radius, paint)
        }

        if (running) postInvalidateOnAnimation()
    }
}
