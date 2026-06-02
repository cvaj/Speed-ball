package com.speedball.app.capture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.speedball.app.measurement.GravityVectorSample
import com.speedball.app.measurement.LevelReferenceCalculator
import com.speedball.app.measurement.LevelReferenceDisplayRotation
import com.speedball.app.measurement.LevelReferenceOutcome
import com.speedball.app.measurement.LevelReferenceSource
import kotlin.math.sqrt

/** Captures one still-phone IMU gravity snapshot for static tripod level. */
class DeviceLevelReferenceCapture(context: Context) {
    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val handler = Handler(Looper.getMainLooper())
    private var activeListener: SensorEventListener? = null
    private var timeoutRunnable: Runnable? = null

    fun capture(
        displayRotation: Int,
        capturedAtEpochMillis: Long = System.currentTimeMillis(),
        callback: (LevelReferenceOutcome) -> Unit,
    ) {
        stop()
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val selectedSensor = gravitySensor ?: accelerometer
        if (selectedSensor == null) {
            callback(LevelReferenceOutcome.Failure("No gravity or accelerometer sensor is available for level."))
            return
        }
        val source = if (gravitySensor != null) {
            LevelReferenceSource.GRAVITY_SENSOR
        } else {
            LevelReferenceSource.ACCELEROMETER_FALLBACK
        }
        val display = displayRotation.toLevelDisplayRotation()
        val gyro = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
        val samples = mutableListOf<GravityVectorSample>()
        var maxGyroMagnitude: Double? = null
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_GRAVITY, Sensor.TYPE_ACCELEROMETER -> {
                        samples += GravityVectorSample(
                            xMetersPerSecondSquared = event.values.getOrNull(0)?.toDouble() ?: Double.NaN,
                            yMetersPerSecondSquared = event.values.getOrNull(1)?.toDouble() ?: Double.NaN,
                            zMetersPerSecondSquared = event.values.getOrNull(2)?.toDouble() ?: Double.NaN,
                        )
                        if (samples.size >= TARGET_LEVEL_SAMPLE_COUNT) {
                            finish(
                                samples = samples,
                                source = source,
                                displayRotation = display,
                                maxGyroMagnitude = maxGyroMagnitude,
                                capturedAtEpochMillis = capturedAtEpochMillis,
                                callback = callback,
                            )
                        }
                    }
                    Sensor.TYPE_GYROSCOPE -> {
                        val magnitude = sqrt(
                            event.values.getOrNull(0).orZeroSquared() +
                                event.values.getOrNull(1).orZeroSquared() +
                                event.values.getOrNull(2).orZeroSquared(),
                        )
                        maxGyroMagnitude = maxOf(maxGyroMagnitude ?: 0.0, magnitude)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        activeListener = listener
        sensorManager.registerListener(listener, selectedSensor, SensorManager.SENSOR_DELAY_GAME, handler)
        gyro?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_GAME, handler) }
        timeoutRunnable = Runnable {
            finish(
                samples = samples,
                source = source,
                displayRotation = display,
                maxGyroMagnitude = maxGyroMagnitude,
                capturedAtEpochMillis = capturedAtEpochMillis,
                callback = callback,
            )
        }.also { handler.postDelayed(it, LEVEL_CAPTURE_TIMEOUT_MILLIS) }
    }

    fun observeLive(
        displayRotation: Int,
        callback: (LevelReferenceOutcome.Success) -> Unit,
    ): Boolean {
        stop()
        val gravitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_GRAVITY)
        val accelerometer = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        val selectedSensor = gravitySensor ?: accelerometer ?: return false
        val source = if (gravitySensor != null) {
            LevelReferenceSource.GRAVITY_SENSOR
        } else {
            LevelReferenceSource.ACCELEROMETER_FALLBACK
        }
        val display = displayRotation.toLevelDisplayRotation()
        val samples = mutableListOf<GravityVectorSample>()
        var lastCallbackAtMillis = 0L
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                if (event.sensor.type != Sensor.TYPE_GRAVITY && event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
                samples += GravityVectorSample(
                    xMetersPerSecondSquared = event.values.getOrNull(0)?.toDouble() ?: Double.NaN,
                    yMetersPerSecondSquared = event.values.getOrNull(1)?.toDouble() ?: Double.NaN,
                    zMetersPerSecondSquared = event.values.getOrNull(2)?.toDouble() ?: Double.NaN,
                )
                while (samples.size > TARGET_LEVEL_SAMPLE_COUNT) {
                    samples.removeAt(0)
                }
                val now = System.currentTimeMillis()
                if (samples.size < TARGET_LEVEL_SAMPLE_COUNT || now - lastCallbackAtMillis < LIVE_LEVEL_CALLBACK_INTERVAL_MILLIS) return
                val outcome = LevelReferenceCalculator.buildSnapshot(
                    samples = samples.toList(),
                    source = source,
                    displayRotation = display,
                    maxGyroMagnitudeRadPerSecond = null,
                    capturedAtEpochMillis = now,
                )
                if (outcome is LevelReferenceOutcome.Success) {
                    lastCallbackAtMillis = now
                    callback(outcome)
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        activeListener = listener
        sensorManager.registerListener(listener, selectedSensor, SensorManager.SENSOR_DELAY_GAME, handler)
        return true
    }

    fun stop() {
        timeoutRunnable?.let(handler::removeCallbacks)
        timeoutRunnable = null
        activeListener?.let(sensorManager::unregisterListener)
        activeListener = null
    }

    private fun finish(
        samples: List<GravityVectorSample>,
        source: LevelReferenceSource,
        displayRotation: LevelReferenceDisplayRotation,
        maxGyroMagnitude: Double?,
        capturedAtEpochMillis: Long,
        callback: (LevelReferenceOutcome) -> Unit,
    ) {
        activeListener ?: return
        stop()
        val outcome = LevelReferenceCalculator.buildSnapshot(
            samples = samples.toList(),
            source = source,
            displayRotation = displayRotation,
            maxGyroMagnitudeRadPerSecond = maxGyroMagnitude,
            capturedAtEpochMillis = capturedAtEpochMillis,
        )
        callback(outcome)
    }

    private fun Int.toLevelDisplayRotation(): LevelReferenceDisplayRotation =
        when (this) {
            Surface.ROTATION_90 -> LevelReferenceDisplayRotation.ROTATION_90
            Surface.ROTATION_180 -> LevelReferenceDisplayRotation.ROTATION_180
            Surface.ROTATION_270 -> LevelReferenceDisplayRotation.ROTATION_270
            else -> LevelReferenceDisplayRotation.ROTATION_0
        }

    private fun Float?.orZeroSquared(): Double {
        val value = this?.toDouble() ?: 0.0
        return value * value
    }

    private companion object {
        const val TARGET_LEVEL_SAMPLE_COUNT = 30
        const val LEVEL_CAPTURE_TIMEOUT_MILLIS = 1_000L
        const val LIVE_LEVEL_CALLBACK_INTERVAL_MILLIS = 100L
    }
}
