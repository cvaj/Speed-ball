package com.speedball.core.calibration

import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DistanceCalibrationTest {
    @Test
    fun computesPixelsPerFootFromKnownPixelAndRealDistance() {
        val result = DistanceCalibration.pixelsPerFoot(pixelDistancePx = 120.0, realDistanceFeet = 3.0)

        val success = assertInstanceOf(CalibrationResult.Success::class.java, result)
        assertEquals(40.0, success.pixelsPerFoot, 1e-12)
    }

    @Test
    fun computesPixelsPerFootFromTwoImagePoints() {
        val result = DistanceCalibration.fromKnownDistance(
            pointA = ImagePoint(10.0, 20.0),
            pointB = ImagePoint(13.0, 24.0),
            realDistanceFeet = 2.0,
        )

        val success = assertInstanceOf(CalibrationResult.Success::class.java, result)
        assertEquals(2.5, success.pixelsPerFoot, 1e-12)
    }

    @Test
    fun invalidDistancesFailWithoutDefaultScale() {
        val zeroPixel = DistanceCalibration.pixelsPerFoot(pixelDistancePx = 0.0, realDistanceFeet = 3.0)
        val badReal = DistanceCalibration.pixelsPerFoot(pixelDistancePx = 10.0, realDistanceFeet = Double.NaN)
        val badPoint = DistanceCalibration.fromKnownDistance(
            pointA = ImagePoint(Double.POSITIVE_INFINITY, 0.0),
            pointB = ImagePoint(1.0, 1.0),
            realDistanceFeet = 1.0,
        )

        assertEquals(CalibrationFailure.INVALID_PIXEL_DISTANCE, assertFailure(zeroPixel).reason)
        assertEquals(CalibrationFailure.INVALID_REAL_DISTANCE, assertFailure(badReal).reason)
        assertEquals(CalibrationFailure.INVALID_POINT, assertFailure(badPoint).reason)
    }

    private fun assertFailure(result: CalibrationResult): CalibrationResult.Failure =
        assertInstanceOf(CalibrationResult.Failure::class.java, result)
}
