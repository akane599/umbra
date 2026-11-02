package io.github.akane599.umbra

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import kotlin.math.sqrt

/** Calls [onShake] after 3 hard shakes within 1.5 s. */
class ShakeDetector(context: Context, private val onShake: () -> Unit) : SensorEventListener {

    private val sm = context.getSystemService(SensorManager::class.java)
    private val peaks = ArrayDeque<Long>()
    private var lastPeak = 0L

    fun start(): Boolean {
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return false
        peaks.clear()
        return sm.registerListener(this, acc, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() = sm.unregisterListener(this)

    override fun onSensorChanged(event: SensorEvent) {
        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
        val g = sqrt(x * x + y * y + z * z) / SensorManager.GRAVITY_EARTH
        if (g < THRESHOLD_G) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastPeak < MIN_GAP_MS) return
        lastPeak = now
        peaks.addLast(now)
        while (peaks.isNotEmpty() && now - peaks.first() > WINDOW_MS) peaks.removeFirst()
        if (peaks.size >= PEAKS) {
            peaks.clear()
            onShake()
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        const val THRESHOLD_G = 2.5f
        const val MIN_GAP_MS = 150L
        const val WINDOW_MS = 1500L
        const val PEAKS = 3
    }
}
