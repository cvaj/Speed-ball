package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class DeviceLevelReferenceCaptureTest {
    @Test
    fun sourceUsesGravityFirstAccelerometerFallbackAndGyroStillness() {
        val source = Files.readAllBytes(sourcePath("DeviceLevelReferenceCapture.kt")).toString(Charsets.UTF_8)

        assertTrue(source.contains("Sensor.TYPE_GRAVITY"))
        assertTrue(source.contains("Sensor.TYPE_ACCELEROMETER"))
        assertTrue(source.contains("Sensor.TYPE_GYROSCOPE"))
        assertTrue(source.contains("LevelReferenceCalculator.buildSnapshot"))
        assertTrue(source.contains("displayRotation"))
        assertTrue(source.contains("fun observeLive("))
        assertTrue(source.contains("LIVE_LEVEL_CALLBACK_INTERVAL_MILLIS"))
        assertTrue(source.contains("SensorManager.SENSOR_DELAY_GAME"))
    }

    private fun sourcePath(fileName: String): Path {
        val appPath = Path.of("app/src/main/java/com/speedball/app/capture/$fileName")
        return if (appPath.exists()) {
            appPath
        } else {
            Path.of("src/main/java/com/speedball/app/capture/$fileName")
        }
    }
}
