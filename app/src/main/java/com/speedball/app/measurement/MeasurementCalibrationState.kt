package com.speedball.app.measurement

import com.speedball.core.calibration.CalibrationResult
import com.speedball.core.calibration.DistanceCalibration
import com.speedball.core.model.ImagePoint

/** User-entered distance calibration state, revalidated for every run. */
data class MeasurementCalibrationState(
    val pointA: ImagePoint?,
    val pointB: ImagePoint?,
    val knownDistanceFeet: Double?,
    val revision: Long = 0L,
) {
    fun pixelsPerFoot(): CalibrationResult {
        val a = pointA
        val b = pointB
        val distance = knownDistanceFeet
        if (a == null || b == null || distance == null) {
            return CalibrationResult.Failure(
                com.speedball.core.calibration.CalibrationFailure.INVALID_POINT,
                "Calibration requires two points and a known distance.",
            )
        }
        return DistanceCalibration.fromKnownDistance(a, b, distance)
    }
}
