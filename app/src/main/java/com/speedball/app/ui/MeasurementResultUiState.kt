package com.speedball.app.ui

import com.speedball.app.measurement.MeasurementPipeline
import com.speedball.app.measurement.MeasurementRunFailure
import com.speedball.app.measurement.MeasurementRunOutcome
import com.speedball.app.measurement.VisualEstimateCaptureProof
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

/** Display contract for a visual-estimate report after a capture attempt. */
data class VisualEstimateReport(
    val attemptId: Long,
    val kind: VisualEstimateReportKind,
    val lines: List<String>,
    val dismissToken: String,
    val captureProof: VisualEstimateCaptureProof? = null,
)

/** Separates success overlays from non-destructive no-read/failure panels. */
enum class VisualEstimateReportKind {
    Success,
    NoRead,
    Failure,
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

/** Builds the capture report shown after a visual-estimate attempt. */
fun visualEstimateReportFor(
    attemptId: Long,
    outcome: VisualEstimateOutcome?,
    failure: Boolean = false,
    captureProof: VisualEstimateCaptureProof? = null,
): VisualEstimateReport? {
    val estimateOutcome = outcome ?: return null
    val kind = when (estimateOutcome) {
        is VisualEstimateOutcome.Success -> VisualEstimateReportKind.Success
        is VisualEstimateOutcome.NoRead ->
            if (failure) {
                VisualEstimateReportKind.Failure
            } else {
                VisualEstimateReportKind.NoRead
            }
    }
    val lines = when (estimateOutcome) {
        is VisualEstimateOutcome.Success -> estimateSuccessReportLines(estimateOutcome)
        is VisualEstimateOutcome.NoRead -> estimateNoReadReportLines(kind, estimateOutcome)
    }
    return VisualEstimateReport(
        attemptId = attemptId,
        kind = kind,
        lines = lines + captureProof.reportLinesFor(kind),
        dismissToken = visualEstimateDismissKey(attemptId),
        captureProof = captureProof,
    )
}

private fun visualEstimateDismissKey(attemptId: Long): String =
    "visual-estimate-report-attempt-$attemptId"

private fun noReadUiLines(
    reason: MeasurementRunFailure,
    message: String,
): List<String> =
    listOf(
        "result=no-read reason=$reason action=${reason.actionLabel()} message=${message.compactForResult()}",
    )

private fun estimateNoReadUiLines(outcome: VisualEstimateOutcome.NoRead): List<String> =
    buildList {
        val diagnostics = outcome.diagnostics
        add("result=estimate-no-read reason=${outcome.reason} action=${outcome.reason.actionLabel()} message=${outcome.message.compactForResult()}")
        if (diagnostics != null) {
            add(
                "estimate-no-read-diagnostics frames=${diagnostics.frameCount} detections=${diagnostics.detectionCount} " +
                    "candidateFrames=${diagnostics.candidateFrameCount ?: 0} candidateBlobs=${diagnostics.candidateBlobCount ?: 0} " +
                    "selectedSamples=${diagnostics.selectedSampleCount ?: 0} timing=${diagnostics.timingBasis}",
            )
        }
    }

private fun estimateNoReadReportLines(
    kind: VisualEstimateReportKind,
    outcome: VisualEstimateOutcome.NoRead,
): List<String> =
    listOf(
        if (kind == VisualEstimateReportKind.Failure) "CAPTURE FAILED" else "NO READ",
        "REASON ${outcome.reason}",
        "ACTION ${outcome.reason.actionLabel()}",
        "MESSAGE ${outcome.message.compactForResult()}",
    )

private fun VisualEstimateCaptureProof?.reportLinesFor(kind: VisualEstimateReportKind): List<String> {
    val proof = this ?: return emptyList()
    val summary = proof.detectorSummary
    val base = buildList {
        add("PROOF frames=${proof.capturedFrameCount} readback=${proof.readbackWidth}x${proof.readbackHeight}")
        add("DETECTOR candidateFrames=${summary.candidateFrameCount} candidateBlobs=${summary.candidateBlobCount} selectedSamples=${summary.selectedSampleCount}")
        if (proof.capturedFrameCount == 0) {
            add("EVIDENCE 0 frames captured")
        } else if (summary.candidateFrameCount == 0 && kind != VisualEstimateReportKind.Success) {
            add("EVIDENCE selected color/ROI matched no blobs")
        }
    }
    return if (kind == VisualEstimateReportKind.Success) {
        base
    } else {
        base.filterNot { it.contains("mph", ignoreCase = true) || it.contains("angle", ignoreCase = true) || it.contains("distance", ignoreCase = true) }
    }
}

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

private fun estimateSuccessReportLines(outcome: VisualEstimateOutcome.Success): List<String> =
    buildList {
        add("VELOCITY ${outcome.milesPerHour.formatResult(1)} MPH")
        outcome.launchAngleDegrees?.takeIf { it.isFinite() }?.let {
            add("ANGLE ${it.formatResult(1)} DEG")
        }
        estimateCarryFeet(outcome)?.let {
            add("DISTANCE ${it.formatResult(1)} FT")
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
