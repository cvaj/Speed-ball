package com.speedball.core.velocity

import com.speedball.core.measurement.MeasurementFailure
import com.speedball.core.model.Detection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

class VelocityFitTest {
    private val options = VelocityFitOptions(
        minTimeSpreadSecondsSquared = 1e-9,
        maxOutlierPasses = 0,
        minOutlierRmsImprovementPx = 0.0,
    )

    @Test
    fun fitsExactLineWithKnownVelocityAndAngle() {
        val fit = assertSuccess(VelocityFit.fit(exactLineDetections(), options))

        assertEquals(10.0, fit.xInterceptPx, 1e-9)
        assertEquals(100.0, fit.yInterceptPx, 1e-9)
        assertEquals(30.0, fit.vxPxPerSecond, 1e-9)
        assertEquals(-40.0, fit.vyPxPerSecond, 1e-9)
        assertEquals(50.0, fit.speedPxPerSecond, 1e-9)
        assertEquals(53.13010235415598, fit.launchAngleDegrees, 1e-9)
        assertEquals(1.0, fit.rSquaredX, 1e-12)
        assertEquals(1.0, fit.rSquaredY, 1e-12)
        assertEquals(0.0, fit.rmsResidualPx, 1e-9)
        assertEquals(listOf(0, 1, 2, 3), fit.usedOriginalIndices)
    }

    @Test
    fun knownAngleGoldenCatchesRadiansDegreesMistakes() {
        val detections = listOf(
            Detection(0.0, 1.0, 4.0),
            Detection(1.0, 11.0, -6.0),
            Detection(2.0, 21.0, -16.0),
        )

        val fit = assertSuccess(VelocityFit.fit(detections, options))

        assertEquals(45.0, fit.launchAngleDegrees, 1e-9)
    }

    @Test
    fun translatingCoordinatesPreservesSpeedAndLaunchAngle() {
        val base = assertSuccess(VelocityFit.fit(exactLineDetections(), options))
        val translated = exactLineDetections().map {
            it.copy(xPx = it.xPx + 400.0, yPx = it.yPx - 75.0)
        }

        val shifted = assertSuccess(VelocityFit.fit(translated, options))

        assertEquals(base.speedPxPerSecond, shifted.speedPxPerSecond, 1e-9)
        assertEquals(base.launchAngleDegrees, shifted.launchAngleDegrees, 1e-9)
    }

    @Test
    fun rotatingInputCoordinatesAndRefittingPreservesPixelSpeed() {
        val base = assertSuccess(VelocityFit.fit(exactLineDetections(), options))
        val radians = 37.0 * PI / 180.0
        val rotated = exactLineDetections().map {
            val x = it.xPx * cos(radians) - it.yPx * sin(radians)
            val y = it.xPx * sin(radians) + it.yPx * cos(radians)
            it.copy(xPx = x, yPx = y)
        }

        val rotatedFit = assertSuccess(VelocityFit.fit(rotated, options))

        assertEquals(base.speedPxPerSecond, rotatedFit.speedPxPerSecond, 1e-9)
    }

    @Test
    fun badInputsFailLoudly() {
        assertEquals(
            MeasurementFailure.INSUFFICIENT_DETECTIONS,
            assertFailure(VelocityFit.fit(exactLineDetections().take(2), options)).reason,
        )
        assertEquals(
            MeasurementFailure.INVALID_DETECTION,
            assertFailure(VelocityFit.fit(listOf(
                Detection(0.0, 0.0, 0.0),
                Detection(1.0, Double.NaN, 0.0),
                Detection(2.0, 2.0, 2.0),
            ), options)).reason,
        )
        assertEquals(
            MeasurementFailure.BAD_TIMESTAMP,
            assertFailure(VelocityFit.fit(listOf(
                Detection(0.0, 0.0, 0.0),
                Detection(1.0, 1.0, 1.0),
                Detection(1.0, 2.0, 2.0),
            ), options)).reason,
        )
        assertEquals(
            MeasurementFailure.BAD_TIMESTAMP,
            assertFailure(VelocityFit.fit(listOf(
                Detection(0.0, 0.0, 0.0),
                Detection(1e-12, 1.0, 1.0),
                Detection(2e-12, 2.0, 2.0),
            ), options)).reason,
        )
        assertEquals(
            MeasurementFailure.INVALID_OPTIONS,
            assertFailure(VelocityFit.fit(exactLineDetections(), options.copy(minTimeSpreadSecondsSquared = Double.NaN))).reason,
        )
        assertEquals(
            MeasurementFailure.INVALID_OPTIONS,
            assertFailure(VelocityFit.fit(exactLineDetections(), options.copy(maxOutlierPasses = -1))).reason,
        )
        assertEquals(
            MeasurementFailure.INVALID_OPTIONS,
            assertFailure(VelocityFit.fit(exactLineDetections(), options.copy(minOutlierRmsImprovementPx = Double.NaN))).reason,
        )
    }

    private fun exactLineDetections(): List<Detection> =
        listOf(
            Detection(0.0, 10.0, 100.0),
            Detection(1.0, 40.0, 60.0),
            Detection(2.0, 70.0, 20.0),
            Detection(3.0, 100.0, -20.0),
        )
}

internal fun assertSuccess(outcome: VelocityFitOutcome): VelocityFitResult =
    assertInstanceOf(VelocityFitOutcome.Success::class.java, outcome).fit

internal fun assertFailure(outcome: VelocityFitOutcome): VelocityFitOutcome.Failure =
    assertInstanceOf(VelocityFitOutcome.Failure::class.java, outcome)
