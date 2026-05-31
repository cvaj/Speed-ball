package com.speedball.core.measurement

import com.speedball.core.model.Detection
import com.speedball.core.velocity.VelocityFitResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class VelocityMeasurementTest {
    private val options = MeasurementOptions(
        maxRmsResidualPx = 0.25,
        minTimeSpreadSecondsSquared = 1e-9,
        maxOutlierPasses = 0,
        minOutlierRmsImprovementPx = 0.0,
    )

    @Test
    fun syntheticEndToEndPipelineGoldenComputesMphAndAngle() {
        val detections = listOf(
            Detection(0.0, 10.0, 100.0),
            Detection(1.0, 40.0, 60.0),
            Detection(2.0, 70.0, 20.0),
            Detection(3.0, 100.0, -20.0),
        )

        val outcome = VelocityMeasurementCalculator.measure(
            detections = detections,
            pixelsPerFoot = 10.0,
            options = options,
        )

        val measurement = assertSuccess(outcome)
        assertEquals(50.0, measurement.fit.speedPxPerSecond, 1e-9)
        assertEquals(5.0, measurement.feetPerSecond, 1e-9)
        assertEquals(3.409091, measurement.milesPerHour, 1e-9)
        assertEquals(53.13010235415598, measurement.launchAngleDegrees, 1e-9)
    }

    @Test
    fun mapsInputFailuresToSpecificReasons() {
        assertEquals(
            MeasurementFailure.INSUFFICIENT_DETECTIONS,
            assertFailure(VelocityMeasurementCalculator.measure(
                detections = listOf(Detection(0.0, 0.0, 0.0), Detection(1.0, 1.0, 1.0)),
                pixelsPerFoot = 10.0,
                options = options,
            )).reason,
        )
        assertEquals(
            MeasurementFailure.INVALID_DETECTION,
            assertFailure(VelocityMeasurementCalculator.measure(
                detections = listOf(
                    Detection(0.0, 0.0, 0.0),
                    Detection(1.0, Double.NaN, 1.0),
                    Detection(2.0, 2.0, 2.0),
                ),
                pixelsPerFoot = 10.0,
                options = options,
            )).reason,
        )
        assertEquals(
            MeasurementFailure.INVALID_CALIBRATION,
            assertFailure(VelocityMeasurementCalculator.measure(
                detections = validDetections(),
                pixelsPerFoot = 0.0,
                options = options,
            )).reason,
        )
        assertEquals(
            MeasurementFailure.BAD_TIMESTAMP,
            assertFailure(VelocityMeasurementCalculator.measure(
                detections = listOf(
                    Detection(0.0, 0.0, 0.0),
                    Detection(1.0, 1.0, 1.0),
                    Detection(1.0, 2.0, 2.0),
                ),
                pixelsPerFoot = 10.0,
                options = options,
            )).reason,
        )
        assertEquals(
            MeasurementFailure.INVALID_OPTIONS,
            assertFailure(VelocityMeasurementCalculator.measure(
                detections = validDetections(),
                pixelsPerFoot = 10.0,
                options = options.copy(maxRmsResidualPx = Double.POSITIVE_INFINITY),
            )).reason,
        )
        assertEquals(
            MeasurementFailure.INVALID_OPTIONS,
            assertFailure(VelocityMeasurementCalculator.measure(
                detections = validDetections(),
                pixelsPerFoot = 10.0,
                options = options.copy(minTimeSpreadSecondsSquared = 0.0),
            )).reason,
        )
        assertEquals(
            MeasurementFailure.INVALID_OPTIONS,
            assertFailure(VelocityMeasurementCalculator.measure(
                detections = validDetections(),
                pixelsPerFoot = 10.0,
                options = options.copy(maxOutlierPasses = -1),
            )).reason,
        )
        assertEquals(
            MeasurementFailure.INVALID_OPTIONS,
            assertFailure(VelocityMeasurementCalculator.measure(
                detections = validDetections(),
                pixelsPerFoot = 10.0,
                options = options.copy(minOutlierRmsImprovementPx = Double.NaN),
            )).reason,
        )
    }

    @Test
    fun excessiveResidualFailsWithoutPartialSpeed() {
        val outcome = VelocityMeasurementCalculator.measure(
            detections = listOf(
                Detection(0.0, 0.0, 0.0),
                Detection(1.0, 10.0, 0.0),
                Detection(2.0, 20.0, 50.0),
                Detection(3.0, 30.0, 0.0),
            ),
            pixelsPerFoot = 10.0,
            options = options.copy(maxRmsResidualPx = 0.1),
        )

        val failure = assertFailure(outcome)
        assertEquals(MeasurementFailure.EXCESSIVE_RESIDUAL, failure.reason)
        assertFalse(failure.javaClass.declaredFields.any { it.name.contains("mph", ignoreCase = true) })
        assertFalse(failure.javaClass.declaredFields.any { it.name.contains("angle", ignoreCase = true) })
    }

    @Test
    fun defensiveNonFiniteFitMappingDoesNotOverlapInputValidation() {
        val outcome = measurementFromFit(
            fit = VelocityFitResult(
                xInterceptPx = 0.0,
                yInterceptPx = 0.0,
                vxPxPerSecond = 10.0,
                vyPxPerSecond = 10.0,
                speedPxPerSecond = Double.POSITIVE_INFINITY,
                launchAngleDegrees = 45.0,
                rSquaredX = 1.0,
                rSquaredY = 1.0,
                rmsResidualPx = 0.0,
                usedOriginalIndices = listOf(0, 1, 2),
            ),
            pixelsPerFoot = 10.0,
            maxRmsResidualPx = 1.0,
        )

        assertEquals(MeasurementFailure.NON_FINITE_FIT, assertFailure(outcome).reason)
    }

    private fun validDetections(): List<Detection> =
        listOf(
            Detection(0.0, 0.0, 0.0),
            Detection(1.0, 10.0, 5.0),
            Detection(2.0, 20.0, 10.0),
        )

    private fun assertSuccess(outcome: MeasurementOutcome): VelocityMeasurement =
        assertInstanceOf(MeasurementOutcome.Success::class.java, outcome).measurement

    private fun assertFailure(outcome: MeasurementOutcome): MeasurementOutcome.Failure =
        assertInstanceOf(MeasurementOutcome.Failure::class.java, outcome)
}
