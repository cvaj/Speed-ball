package com.speedball.app.ui

import com.speedball.app.measurement.MeasurementPipeline
import com.speedball.app.measurement.MeasurementRunFailure
import com.speedball.app.measurement.MeasurementRunOutcome

/** Compose-free result presentation state. Success can only carry a pipeline outcome. */
sealed interface MeasurementResultUiState {
    data object Ready : MeasurementResultUiState
    data object Running : MeasurementResultUiState
    data class Outcome(val outcome: MeasurementRunOutcome) : MeasurementResultUiState
}

fun measurementResultUiLines(state: MeasurementResultUiState): List<String> =
    when (state) {
        MeasurementResultUiState.Ready -> listOf("result=ready")
        MeasurementResultUiState.Running -> listOf("result=running")
        is MeasurementResultUiState.Outcome -> measurementOutcomeUiLines(state.outcome)
    }

fun measurementOutcomeUiLines(outcome: MeasurementRunOutcome?): List<String> =
    when (outcome) {
        null -> noReadUiLines(
            reason = MeasurementRunFailure.UNPROVEN_TIMING,
            message = "Timing source is not proven.",
        )
        is MeasurementRunOutcome.NoRead -> noReadUiLines(outcome.reason, outcome.message)
        is MeasurementRunOutcome.Success -> successUiLines(outcome)
    }

fun productionNoReadResultLines(): List<String> =
    measurementOutcomeUiLines(MeasurementPipeline.currentProductionNoRead())

private fun noReadUiLines(
    reason: MeasurementRunFailure,
    message: String,
): List<String> =
    listOf(
        "result=no-read reason=$reason action=${reason.actionLabel()} message=${message.compactForResult()}",
    )

private fun successUiLines(outcome: MeasurementRunOutcome.Success): List<String> =
    listOf(
        "result=success evidence=${outcome.timingProof.evidenceLabel.compactForResult()} detections=${outcome.detectionCount}",
        "speed mph=${outcome.measurement.milesPerHour.formatResult(1)} angleDeg=${outcome.measurement.launchAngleDegrees.formatResult(1)}",
        "trajectory carryFt=${(outcome.trajectory.carryMeters * FEET_PER_METER).formatResult(1)} apexFt=${(outcome.trajectory.apexMeters * FEET_PER_METER).formatResult(1)} hangSec=${outcome.trajectory.hangTimeSeconds.formatResult(2)}",
    )

private fun MeasurementRunFailure.actionLabel(): String =
    when (this) {
        MeasurementRunFailure.UNPROVEN_TIMING -> "prove-frame-source"
        MeasurementRunFailure.BAD_FRAME_SEQUENCE -> "retry-capture"
        MeasurementRunFailure.DETECTION_FAILED -> "resample-color-or-roi"
        MeasurementRunFailure.INSUFFICIENT_DETECTIONS -> "retry-capture-with-visible-ball"
        MeasurementRunFailure.BAD_CALIBRATION -> "recalibrate-distance"
        MeasurementRunFailure.MEASUREMENT_REJECTED -> "retry-with-cleaner-flight"
        MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED -> "reduce-frame-processing-load"
    }

private fun String.compactForResult(): String {
    val compact = trim().replace(Regex("\\s+"), " ")
    return if (compact.length <= MAX_RESULT_MESSAGE_CHARS) {
        compact
    } else {
        compact.take(MAX_RESULT_MESSAGE_CHARS - 3) + "..."
    }
}

private fun Double.formatResult(decimals: Int): String =
    "%.${decimals}f".format(this)

private const val MAX_RESULT_MESSAGE_CHARS = 120
private const val FEET_PER_METER = 3.280839895013123
