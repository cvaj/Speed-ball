package com.speedball.app.ui

import com.speedball.app.measurement.MeasurementPipeline
import com.speedball.app.measurement.MeasurementRunFailure
import com.speedball.app.measurement.MeasurementRunOutcome
import com.speedball.app.measurement.VisualEstimateNoReadReason
import com.speedball.app.measurement.VisualEstimateOutcome
import com.speedball.core.physics.LaunchState
import com.speedball.core.physics.TrajectoryOutcome
import com.speedball.core.physics.TrajectoryPhysics

/** Compose-free result presentation state. Success can only carry a pipeline outcome. */
sealed interface MeasurementResultUiState {
    data object Ready : MeasurementResultUiState
    data object Running : MeasurementResultUiState
    data class Outcome(val outcome: MeasurementRunOutcome) : MeasurementResultUiState
    data class EstimateOutcome(val outcome: VisualEstimateOutcome) : MeasurementResultUiState
}

fun measurementResultUiLines(state: MeasurementResultUiState): List<String> =
    when (state) {
        MeasurementResultUiState.Ready -> listOf("result=ready")
        MeasurementResultUiState.Running -> listOf("result=running")
        is MeasurementResultUiState.Outcome -> measurementOutcomeUiLines(state.outcome)
        is MeasurementResultUiState.EstimateOutcome -> visualEstimateOutcomeUiLines(state.outcome)
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

fun visualEstimateOutcomeUiLines(outcome: VisualEstimateOutcome): List<String> =
    when (outcome) {
        is VisualEstimateOutcome.NoRead -> estimateNoReadUiLines(outcome)
        is VisualEstimateOutcome.Success -> estimateSuccessUiLines(outcome)
    }

private fun noReadUiLines(
    reason: MeasurementRunFailure,
    message: String,
): List<String> =
    listOf(
        "result=no-read reason=$reason action=${reason.actionLabel()} message=${message.compactForResult()}",
    )

private fun estimateNoReadUiLines(outcome: VisualEstimateOutcome.NoRead): List<String> =
    listOf(
        "result=estimate-no-read reason=${outcome.reason} action=${outcome.reason.actionLabel()} message=${outcome.message.compactForResult()}",
    )

private fun successUiLines(outcome: MeasurementRunOutcome.Success): List<String> =
    listOf(
        "result=success evidence=${outcome.timingProof.evidenceLabel.compactForResult()} detections=${outcome.detectionCount}",
        "speed mph=${outcome.measurement.milesPerHour.formatResult(1)} angleDeg=${outcome.measurement.launchAngleDegrees.formatResult(1)}",
        "trajectory carryFt=${(outcome.trajectory.carryMeters * FEET_PER_METER).formatResult(1)} apexFt=${(outcome.trajectory.apexMeters * FEET_PER_METER).formatResult(1)} hangSec=${outcome.trajectory.hangTimeSeconds.formatResult(2)}",
    )

private fun estimateSuccessUiLines(outcome: VisualEstimateOutcome.Success): List<String> {
    val diagnostics = outcome.diagnostics
    val gapRatio = diagnostics.timestampGapSummary?.maxToMedianRatio?.formatResult(2) ?: "na"
    val residual = diagnostics.fitResidualPx?.formatResult(2) ?: "na"
    val assumption = diagnostics.assumptions.joinToString(separator = " ").compactForResult()
    val angle = outcome.launchAngleDegrees?.formatResult(1) ?: "na"
    val carryFeet = estimateCarryFeet(outcome)?.formatResult(1) ?: "na"
    return buildList {
        add("result=estimate confidence=${diagnostics.confidence} frames=${diagnostics.frameCount} detections=${diagnostics.detectionCount} timing=${diagnostics.timingBasis} scale=${diagnostics.scaleBasis}")
        add("speed-estimate mph=${outcome.milesPerHour.formatResult(1)} angleDeg=$angle angleScope=in-image-plane-estimate")
        add("distance-estimate carryFt=$carryFeet")
        add("estimate-diagnostics timestampGapMaxToMedian=$gapRatio residualPx=$residual assumption=$assumption")
        diagnostics.assumptions.forEach { assumptionLine ->
            add("estimate-assumption ${assumptionLine.compactForResult()}")
        }
    }
}

private fun estimateCarryFeet(outcome: VisualEstimateOutcome.Success): Double? {
    val angle = outcome.launchAngleDegrees?.takeIf { it.isFinite() } ?: return null
    val trajectory = TrajectoryPhysics.simulate(
        LaunchState.fromMilesPerHour(
            milesPerHour = outcome.milesPerHour,
            angleDegrees = angle,
        ),
    )
    return when (trajectory) {
        is TrajectoryOutcome.Success -> trajectory.trajectory.carryMeters * FEET_PER_METER
        is TrajectoryOutcome.Failure -> null
    }
}

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

private fun VisualEstimateNoReadReason.actionLabel(): String =
    when (this) {
        VisualEstimateNoReadReason.BAD_CALIBRATION -> "recalibrate-distance"
        VisualEstimateNoReadReason.BAD_TIMESTAMPS -> "retry-capture"
        VisualEstimateNoReadReason.DETECTION_FAILED -> "resample-color-or-roi"
        VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS -> "retry-capture-with-visible-ball"
        VisualEstimateNoReadReason.AMBIGUOUS_TRACK -> "retry-with-cleaner-flight"
        VisualEstimateNoReadReason.EXCESSIVE_RESIDUAL -> "retry-with-cleaner-flight"
        VisualEstimateNoReadReason.PLANAR_ASSUMPTION_VIOLATED -> "keep-ball-across-calibrated-plane"
        VisualEstimateNoReadReason.NON_FINITE_RESULT -> "retry-capture"
        VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED -> "reduce-frame-processing-load"
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
