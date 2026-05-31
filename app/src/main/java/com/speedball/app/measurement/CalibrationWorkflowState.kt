package com.speedball.app.measurement

import com.speedball.core.calibration.CalibrationResult
import com.speedball.core.model.ImagePoint

/** User-facing calibration workflow state backed by measurement-time validation. */
data class CalibrationWorkflowState(
    val pointA: ImagePoint? = null,
    val pointB: ImagePoint? = null,
    val knownDistanceFeet: Double? = null,
    val revision: Long = 0L,
) {
    fun select(
        pointA: ImagePoint,
        pointB: ImagePoint,
        knownDistanceFeet: Double,
    ): CalibrationWorkflowState =
        CalibrationWorkflowState(
            pointA = pointA,
            pointB = pointB,
            knownDistanceFeet = knownDistanceFeet,
            revision = revision + 1L,
        )

    fun clear(): CalibrationWorkflowState =
        CalibrationWorkflowState(revision = revision + 1L)

    fun toMeasurementCalibrationState(): MeasurementCalibrationState =
        MeasurementCalibrationState(
            pointA = pointA,
            pointB = pointB,
            knownDistanceFeet = knownDistanceFeet,
            revision = revision,
        )

    fun validation(): CalibrationResult =
        toMeasurementCalibrationState().pixelsPerFoot()

    fun noReadOrNull(): MeasurementRunOutcome.NoRead? =
        when (val result = validation()) {
            is CalibrationResult.Success -> null
            is CalibrationResult.Failure -> MeasurementRunOutcome.NoRead(
                reason = MeasurementRunFailure.BAD_CALIBRATION,
                message = result.message,
            )
        }

    fun readiness(): CalibrationWorkflowReadiness =
        when (val result = validation()) {
            is CalibrationResult.Success -> CalibrationWorkflowReadiness.Ready(result.pixelsPerFoot)
            is CalibrationResult.Failure -> CalibrationWorkflowReadiness.NotReady(result.message)
        }
}

sealed interface CalibrationWorkflowReadiness {
    data class Ready(val pixelsPerFoot: Double) : CalibrationWorkflowReadiness
    data class NotReady(val message: String) : CalibrationWorkflowReadiness
}
