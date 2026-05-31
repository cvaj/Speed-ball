package com.speedball.app.measurement

import com.speedball.core.calibration.CalibrationResult
import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class CalibrationWorkflowStateTest {
    @Test
    fun finitePositiveCalibrationIsReady() {
        val state = CalibrationWorkflowState().select(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(12.0, 0.0),
            knownDistanceFeet = 6.0,
        )

        val validation = assertInstanceOf(CalibrationResult.Success::class.java, state.validation())
        val readiness = assertInstanceOf(CalibrationWorkflowReadiness.Ready::class.java, state.readiness())

        assertEquals(2.0, validation.pixelsPerFoot, 1.0e-9)
        assertEquals(2.0, readiness.pixelsPerFoot, 1.0e-9)
        assertNull(state.noReadOrNull())
    }

    @Test
    fun zeroNegativeAndNonFiniteKnownDistanceReject() {
        listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY).forEach { knownDistance ->
            val state = CalibrationWorkflowState().select(
                pointA = ImagePoint(0.0, 0.0),
                pointB = ImagePoint(12.0, 0.0),
                knownDistanceFeet = knownDistance,
            )

            assertInstanceOf(CalibrationResult.Failure::class.java, state.validation())
            assertNoRead(state)
        }
    }

    @Test
    fun nonFiniteOrIdenticalPointsReject() {
        listOf(
            ImagePoint(Double.NaN, 0.0) to ImagePoint(12.0, 0.0),
            ImagePoint(0.0, 0.0) to ImagePoint(Double.POSITIVE_INFINITY, 0.0),
            ImagePoint(4.0, 4.0) to ImagePoint(4.0, 4.0),
        ).forEach { (pointA, pointB) ->
            val state = CalibrationWorkflowState().select(
                pointA = pointA,
                pointB = pointB,
                knownDistanceFeet = 6.0,
            )

            assertInstanceOf(CalibrationResult.Failure::class.java, state.validation())
            assertNoRead(state)
        }
    }

    @Test
    fun clearedCalibrationAfterReadinessReturnsNoRead() {
        val ready = CalibrationWorkflowState().select(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(12.0, 0.0),
            knownDistanceFeet = 6.0,
        )
        val cleared = ready.clear()

        assertInstanceOf(CalibrationWorkflowReadiness.Ready::class.java, ready.readiness())
        assertInstanceOf(CalibrationWorkflowReadiness.NotReady::class.java, cleared.readiness())
        assertNoRead(cleared)
        assertEquals(ready.revision + 1L, cleared.revision)
    }

    @Test
    fun measurementCalibrationStateCarriesCurrentRevision() {
        val state = CalibrationWorkflowState(revision = 41L).select(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(12.0, 0.0),
            knownDistanceFeet = 6.0,
        )

        assertEquals(42L, state.revision)
        assertEquals(42L, state.toMeasurementCalibrationState().revision)
    }

    private fun assertNoRead(state: CalibrationWorkflowState) {
        val noRead = assertInstanceOf(MeasurementRunOutcome.NoRead::class.java, state.noReadOrNull())
        assertEquals(MeasurementRunFailure.BAD_CALIBRATION, noRead.reason)
    }
}
