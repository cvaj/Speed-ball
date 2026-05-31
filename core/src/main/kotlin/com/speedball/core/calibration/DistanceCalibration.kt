package com.speedball.core.calibration

import com.speedball.core.model.ImagePoint
import kotlin.math.hypot

/** Reason a distance calibration could not produce a valid pixel scale. */
enum class CalibrationFailure {
    INVALID_POINT,
    INVALID_PIXEL_DISTANCE,
    INVALID_REAL_DISTANCE,
}

/** Explicit result for distance calibration, avoiding default or guessed scales. */
sealed interface CalibrationResult {
    data class Success(val pixelsPerFoot: Double) : CalibrationResult
    data class Failure(val reason: CalibrationFailure, val message: String) : CalibrationResult
}

/** Distance-calibration helpers for converting image pixels into real distance. */
object DistanceCalibration {
    /**
     * Returns pixels per foot for a known pixel distance and known real distance.
     */
    fun pixelsPerFoot(pixelDistancePx: Double, realDistanceFeet: Double): CalibrationResult {
        if (!pixelDistancePx.isFinite()) {
            return CalibrationResult.Failure(
                CalibrationFailure.INVALID_PIXEL_DISTANCE,
                "Pixel distance must be finite.",
            )
        }
        if (pixelDistancePx <= 0.0) {
            return CalibrationResult.Failure(
                CalibrationFailure.INVALID_PIXEL_DISTANCE,
                "Pixel distance must be positive.",
            )
        }
        if (!realDistanceFeet.isFinite()) {
            return CalibrationResult.Failure(
                CalibrationFailure.INVALID_REAL_DISTANCE,
                "Real distance must be finite.",
            )
        }
        if (realDistanceFeet <= 0.0) {
            return CalibrationResult.Failure(
                CalibrationFailure.INVALID_REAL_DISTANCE,
                "Real distance must be positive.",
            )
        }
        return CalibrationResult.Success(pixelDistancePx / realDistanceFeet)
    }

    /**
     * Computes pixels per foot from two image points and a known real distance.
     */
    fun fromKnownDistance(
        pointA: ImagePoint,
        pointB: ImagePoint,
        realDistanceFeet: Double,
    ): CalibrationResult {
        if (!pointA.xPx.isFinite() || !pointA.yPx.isFinite() || !pointB.xPx.isFinite() || !pointB.yPx.isFinite()) {
            return CalibrationResult.Failure(
                CalibrationFailure.INVALID_POINT,
                "Calibration points must be finite.",
            )
        }
        return pixelsPerFoot(
            pixelDistancePx = hypot(pointB.xPx - pointA.xPx, pointB.yPx - pointA.yPx),
            realDistanceFeet = realDistanceFeet,
        )
    }
}
