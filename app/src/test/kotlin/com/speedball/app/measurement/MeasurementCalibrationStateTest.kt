package com.speedball.app.measurement

import com.speedball.core.calibration.CalibrationResult
import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeasurementCalibrationStateTest {
    @Test
    fun validCalibrationProducesPixelsPerFoot() {
        val result = MeasurementCalibrationState(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(12.0, 0.0),
            knownDistanceFeet = 6.0,
        ).pixelsPerFoot()

        assertTrue(result is CalibrationResult.Success)
        assertEquals(2.0, (result as CalibrationResult.Success).pixelsPerFoot, 1.0e-9)
    }

    @Test
    fun missingNonFiniteZeroAndClearedCalibrationFail() {
        listOf(
            MeasurementCalibrationState(null, ImagePoint(1.0, 1.0), 3.0),
            MeasurementCalibrationState(ImagePoint(Double.NaN, 0.0), ImagePoint(1.0, 1.0), 3.0),
            MeasurementCalibrationState(ImagePoint(0.0, 0.0), ImagePoint(0.0, 0.0), 3.0),
            MeasurementCalibrationState(ImagePoint(0.0, 0.0), ImagePoint(1.0, 0.0), 0.0),
        ).forEach { state ->
            assertTrue(state.pixelsPerFoot() is CalibrationResult.Failure)
        }
    }

    @Test
    fun revalidationUsesCurrentStateAfterChange() {
        val valid = MeasurementCalibrationState(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(10.0, 0.0),
            knownDistanceFeet = 5.0,
            revision = 1L,
        )
        val staleOrCleared = valid.copy(pointB = null, revision = 2L)

        assertTrue(valid.pixelsPerFoot() is CalibrationResult.Success)
        assertTrue(staleOrCleared.pixelsPerFoot() is CalibrationResult.Failure)
    }
}
