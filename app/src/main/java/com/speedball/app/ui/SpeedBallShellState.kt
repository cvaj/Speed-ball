package com.speedball.app.ui

import com.speedball.app.decode.DecodeOutcome
import com.speedball.app.capture.DirectSessionProbeOutcome
import com.speedball.app.capture.DirectTimingSourceProofOutcome
import com.speedball.app.capture.DirectTimingSourceProofRunResult
import com.speedball.app.capture.PreviewFrameOutcome
import com.speedball.app.measurement.CalibrationWorkflowReadiness
import com.speedball.app.measurement.CalibrationWorkflowState
import com.speedball.app.measurement.ColorWorkflowReadiness
import com.speedball.app.measurement.ColorWorkflowState
import com.speedball.app.measurement.MeasurementWorkflowState
import com.speedball.app.measurement.WorkflowCaptureState
import com.speedball.app.measurement.WorkflowSourceProofState

/** Availability marker for skeleton workflow sections before implementation phases land. */
enum class PlaceholderStatus {
    Pending,
    Unavailable,
}

/** One visible row in the Phase 1 shell workflow list. */
data class WorkflowSection(
    val title: String,
    val status: PlaceholderStatus,
    val detail: String,
)

/** Compose-free state used by JVM tests to guard the no-fake-result contract. */
data class SpeedBallShellState(
    val title: String,
    val sections: List<WorkflowSection>,
    val cameraPermission: String = "Not requested",
    val captureStatus: String = "Idle",
    val modeLines: List<String> = emptyList(),
    val selectedModeLine: String? = null,
    val resultLines: List<String> = emptyList(),
    val diagnosticLines: List<String> = emptyList(),
    val failureLine: String? = null,
) {
    /** User-visible strings exposed by the placeholder shell. */
    val visibleText: List<String>
        get() = buildList {
            add(title)
            add(cameraPermission)
            add(captureStatus)
            selectedModeLine?.let(::add)
            addAll(modeLines)
            addAll(resultLines)
            addAll(diagnosticLines)
            failureLine?.let(::add)
            sections.forEach { section ->
                add(section.title)
                add(section.status.name)
                add(section.detail)
            }
        }
}

/** Builds the only Phase 1 app state: a truthful no-read placeholder shell. */
fun speedBallPlaceholderState(): SpeedBallShellState =
    SpeedBallShellState(
        title = "Speed-ball",
        resultLines = productionNoReadResultLines(),
        sections = listOf(
            WorkflowSection("Mode", PlaceholderStatus.Pending, "Camera modes will come from device capability checks."),
            WorkflowSection("Calibrate", PlaceholderStatus.Pending, "Distance setup is not available in this skeleton."),
            WorkflowSection("Sample Color", PlaceholderStatus.Pending, "Color sampling arrives with detection."),
            WorkflowSection("Capture", PlaceholderStatus.Unavailable, "Camera capture is not implemented in this phase."),
            WorkflowSection("Import", PlaceholderStatus.Unavailable, "Video import is not implemented in this phase."),
            WorkflowSection("Results", PlaceholderStatus.Unavailable, "No read until a proven timing source exists."),
        ),
    )

fun speedBallCaptureState(
    permissionLabel: String,
    captureStatus: String,
    modeLines: List<String>,
    selectedModeLine: String?,
    diagnosticLines: List<String>,
    failureLine: String?,
    calibrationState: CalibrationWorkflowState = CalibrationWorkflowState(),
    colorState: ColorWorkflowState = ColorWorkflowState(),
    workflowState: MeasurementWorkflowState = MeasurementWorkflowState(),
    workflowFrameWidth: Int = 0,
    workflowFrameHeight: Int = 0,
): SpeedBallShellState =
    speedBallPlaceholderState().copy(
        cameraPermission = permissionLabel,
        captureStatus = captureStatus,
        modeLines = modeLines,
        selectedModeLine = selectedModeLine,
        resultLines = workflowGuidanceUiLines(
            calibrationState = calibrationState,
            colorState = colorState,
            workflowState = workflowState,
            frameWidth = workflowFrameWidth,
            frameHeight = workflowFrameHeight,
        ),
        diagnosticLines = diagnosticLines,
        failureLine = failureLine,
        sections = listOf(
            WorkflowSection("Mode", PlaceholderStatus.Pending, "Modes are loaded from the device HAL."),
            WorkflowSection("Calibrate", PlaceholderStatus.Pending, calibrationWorkflowUiLines(calibrationState).single()),
            WorkflowSection("Sample Color", PlaceholderStatus.Pending, colorWorkflowUiLines(colorState, workflowFrameWidth, workflowFrameHeight).first()),
            WorkflowSection("Capture", PlaceholderStatus.Pending, "Developer burst diagnostics are available."),
            WorkflowSection("Import", PlaceholderStatus.Unavailable, "Video import is not implemented in this phase."),
            WorkflowSection("Results", PlaceholderStatus.Unavailable, measurementOutcomeUiLines(workflowState.result).first()),
        ),
    )

fun workflowGuidanceUiLines(
    calibrationState: CalibrationWorkflowState,
    colorState: ColorWorkflowState,
    workflowState: MeasurementWorkflowState,
    frameWidth: Int,
    frameHeight: Int,
): List<String> =
    calibrationWorkflowUiLines(calibrationState) +
        colorWorkflowUiLines(colorState, frameWidth, frameHeight) +
        sourceProofUiLine(workflowState.sourceProof) +
        captureWorkflowUiLine(workflowState.capture) +
        measurementOutcomeUiLines(workflowState.result)

private fun sourceProofUiLine(sourceProof: WorkflowSourceProofState): List<String> =
    when (sourceProof) {
        WorkflowSourceProofState.Unproven -> listOf("sourceProof=not-ready action=prove-direct-frame-source")
        WorkflowSourceProofState.BoundDirectInputAvailable -> listOf("sourceProof=ready source=phase9-bound-direct-input")
    }

private fun captureWorkflowUiLine(capture: WorkflowCaptureState): List<String> =
    listOf("captureWorkflow=${capture.name.lowercase()} route=phase9-bound-input-only")

fun calibrationWorkflowUiLines(state: CalibrationWorkflowState): List<String> =
    when (val readiness = state.readiness()) {
        is CalibrationWorkflowReadiness.Ready -> listOf(
            "calibration=ready pixelsPerFoot=${readiness.pixelsPerFoot.format(2)}",
        )

        is CalibrationWorkflowReadiness.NotReady -> listOf(
            "calibration=not-ready action=set-two-points-and-known-length message=${readiness.message}",
        )
    }

fun colorWorkflowUiLines(
    state: ColorWorkflowState,
    frameWidth: Int,
    frameHeight: Int,
): List<String> =
    when (val readiness = state.readiness(frameWidth, frameHeight)) {
        is ColorWorkflowReadiness.Ready -> {
            val threshold = readiness.threshold
            val sample = threshold.center
            val tolerance = threshold.tolerance.clamped()
            val roi = readiness.regionOfInterest
            listOf(
                "colorSample=ready preview=detector-setup hueDeg=${sample.hueDegrees.format(1)} sat=${sample.saturation.format(2)} value=${sample.value.format(2)}",
                "colorTolerance hueDeg=${tolerance.hueDegrees.format(1)} sat=${tolerance.saturation.format(2)} value=${tolerance.value.format(2)} roi=${roi.left},${roi.top},${roi.rightExclusive}x${roi.bottomExclusive}",
            )
        }

        is ColorWorkflowReadiness.NotReady -> listOf(
            "colorSample=not-ready preview=setup action=sample-ball-color message=${readiness.message}",
        )
    }

fun decodeOutcomeUiLines(outcome: DecodeOutcome?): List<String> =
    when (outcome) {
        null -> emptyList()
        DecodeOutcome.Cancelled -> listOf("decode=cancelled")
        is DecodeOutcome.Failure -> listOf("decodeFailure=${outcome.reason} message=${outcome.message}")
        is DecodeOutcome.Success -> buildList {
            val diagnostics = outcome.diagnostics
            add("decodeFrames=${diagnostics.decodedFrameCount} uniqueSensorTs=${diagnostics.uniqueSensorTimestampCount} exactCountPass=${diagnostics.exactCountPasses}")
            add(
                "sensorGapMs median=${diagnostics.medianSensorGapMillis.formatOrNa()} max=${diagnostics.maximumSensorGapMillis.formatOrNa()} " +
                    "dropThreshold=${diagnostics.droppedFrameGapThresholdMillis.format(2)}",
            )
            add(
                "ptsGapMs median=${diagnostics.medianPresentationGapMillis.formatOrNa()} max=${diagnostics.maximumPresentationGapMillis.formatOrNa()} " +
                    "clock=${diagnostics.presentationClockAssessment}",
            )
            diagnostics.ptsToSensorOffsetSummary?.let {
                add("ptsSensorOffsetMicros min=${it.minimumOffsetMicros} max=${it.maximumOffsetMicros} spread=${it.spreadMicros}")
            }
            if (diagnostics.sampledFrames.isNotEmpty()) {
                add(
                    "sampledFrames=" + diagnostics.sampledFrames.joinToString { sample ->
                        "${sample.frameIndex}:${sample.width}x${sample.height}@${sample.presentationTimeMicros}us"
                    },
                )
            }
        }
    }

fun previewOutcomeUiLines(outcome: PreviewFrameOutcome?): List<String> =
    when (outcome) {
        null -> emptyList()
        PreviewFrameOutcome.Cancelled -> listOf("previewProof=cancelled")
        is PreviewFrameOutcome.Failure -> buildList {
            add("previewFailure=${outcome.reason} message=${outcome.message}")
            outcome.diagnostics?.let { diagnostics ->
                add("previewFrames=${diagnostics.uniquePreviewTimestampCount} sensorTs=${diagnostics.uniqueSensorTimestampCount} exactMatches=${diagnostics.exactMatchCount}")
                add(
                    "previewGapMs median=${diagnostics.medianPreviewGapMillis.formatOrNa()} max=${diagnostics.maximumPreviewGapMillis.formatOrNa()} " +
                        "sensorMedian=${diagnostics.medianSensorGapMillis.formatOrNa()} coalescing=${diagnostics.coalescingEvidence}",
                )
            }
        }
        is PreviewFrameOutcome.Success -> buildList {
            val diagnostics = outcome.diagnostics
            add("previewPairs=${outcome.pairs.size} exactMatches=${diagnostics.exactMatchCount}")
            add(
                "previewGapMs median=${diagnostics.medianPreviewGapMillis.formatOrNa()} max=${diagnostics.maximumPreviewGapMillis.formatOrNa()} " +
                    "sensorMedian=${diagnostics.medianSensorGapMillis.formatOrNa()}",
            )
        }
    }

fun directProofRunUiLines(result: DirectTimingSourceProofRunResult?): List<String> =
    when (result) {
        null -> emptyList()
        else -> buildList {
            add(
                when (val proof = result.proofOutcome) {
                    is DirectTimingSourceProofOutcome.Success ->
                        "directProof=success frames=${proof.frames.size} tokenEligible=true"
                    is DirectTimingSourceProofOutcome.Failure ->
                        "directProofFailure=${proof.reason} message=${proof.message}"
                    DirectTimingSourceProofOutcome.Cancelled ->
                        "directProof=cancelled"
                },
            )
            when (val companion = result.companionOutcome) {
                is DirectSessionProbeOutcome.Success -> add(
                    "directCompanion shape=${companion.shape} requestList=${companion.requestListSize} directFrames=${companion.frames.size} sensorTs=${companion.sensorTimestampsNanos.size}",
                )
                is DirectSessionProbeOutcome.Failure -> add(
                    "directCompanionFailure=${companion.reason} shape=${companion.shape} directFrames=${companion.directTimestampCount} sensorTs=${companion.sensorTimestampCount}",
                )
            }
            add("directPreviewControl attempted=${result.previewControl.attempted}")
        }
    }

private fun Double?.formatOrNa(): String =
    this?.format(2) ?: "n/a"

private fun Double.format(decimals: Int): String =
    "%.${decimals}f".format(this)
