package com.speedball.app.ui

import com.speedball.app.decode.DecodeOutcome
import com.speedball.app.capture.DirectSessionProbeOutcome
import com.speedball.app.capture.DirectTimingSourceProofOutcome
import com.speedball.app.capture.DirectTimingSourceProofRunResult
import com.speedball.app.capture.PreviewFrameOutcome
import com.speedball.app.importing.ImportWorkflowStage
import com.speedball.app.importing.ImportWorkflowState
import com.speedball.app.importing.SavedResultSummary
import com.speedball.app.measurement.CalibrationWorkflowReadiness
import com.speedball.app.measurement.CalibrationWorkflowState
import com.speedball.app.measurement.ColorWorkflowReadiness
import com.speedball.app.measurement.ColorWorkflowState
import com.speedball.app.measurement.MeasurementWorkflowState
import com.speedball.app.measurement.Phase14CaptureState
import com.speedball.app.measurement.Phase14SetupMode
import com.speedball.app.measurement.Phase14WorkflowState
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

/** Top-level app mode: setup edits are separate from run/listen/capture/report. */
enum class SpeedBallAppMode {
    Setup,
    Run,
}

/** Command readiness shown in Run Mode, independent from capture status text. */
sealed interface SpeedBallRunCommandState {
    data class SetupInvalid(val reason: String) : SpeedBallRunCommandState
    data object VoiceUnavailable : SpeedBallRunCommandState
    data class VoiceError(val code: Int, val message: String) : SpeedBallRunCommandState
    data class VoiceRetrying(val errorCount: Int, val nextDelayMillis: Long) : SpeedBallRunCommandState
    data object ManualReady : SpeedBallRunCommandState
    data object Listening : SpeedBallRunCommandState
    data object Capturing : SpeedBallRunCommandState
    data object Reporting : SpeedBallRunCommandState
}

/** Compose-free state used by JVM tests to guard the no-fake-result contract. */
data class SpeedBallShellState(
    val title: String,
    val sections: List<WorkflowSection>,
    val appMode: SpeedBallAppMode = SpeedBallAppMode.Setup,
    val runCommandState: SpeedBallRunCommandState = SpeedBallRunCommandState.SetupInvalid("Setup not ready"),
    val cameraPermission: String = "Not requested",
    val captureStatus: String = "Idle",
    val modeLines: List<String> = emptyList(),
    val selectedModeLine: String? = null,
    val resultLines: List<String> = emptyList(),
    val diagnosticLines: List<String> = emptyList(),
    val importLines: List<String> = emptyList(),
    val savedResultLines: List<String> = emptyList(),
    val exportLines: List<String> = emptyList(),
    val failureLine: String? = null,
    val visualEstimateReport: VisualEstimateReport? = null,
    val phase14State: Phase14WorkflowState = Phase14WorkflowState(),
    val setupAdjustmentTargetLabel: String = "A",
    val knownDistanceFeetText: String = "",
    val calibrationPlaneDepthFeetText: String = "",
    val ballPlaneDepthFeetText: String = "",
    val motionBlobSideRatioText: String = "2.00",
    val launchHeightFeetText: String = "4.0",
    val previewRotationDegrees: Int = 0,
) {
    /** User-visible strings exposed by the placeholder shell. */
    val visibleText: List<String>
        get() = buildList {
            add(title)
            add("appMode=${appMode.name}")
            add("runCommand=${runCommandState.visibleText()}")
            add(cameraPermission)
            add(captureStatus)
            selectedModeLine?.let(::add)
            addAll(modeLines)
            addAll(resultLines)
            addAll(diagnosticLines)
            addAll(importLines)
            addAll(savedResultLines)
            addAll(exportLines)
            visualEstimateReport?.lines?.forEach { add("visualEstimateReport=$it") }
            failureLine?.let(::add)
            add("setupTarget=$setupAdjustmentTargetLabel")
            add("knownDistanceFeetInput=$knownDistanceFeetText")
            add("calibrationPlaneDepthFeetInput=$calibrationPlaneDepthFeetText")
            add("ballPlaneDepthFeetInput=$ballPlaneDepthFeetText")
            add("motionBlobSideRatioInput=$motionBlobSideRatioText")
            add("launchHeightFeetInput=$launchHeightFeetText")
            add("previewRotationDegrees=$previewRotationDegrees")
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
    appMode: SpeedBallAppMode = SpeedBallAppMode.Setup,
    runCommandState: SpeedBallRunCommandState = SpeedBallRunCommandState.SetupInvalid("Setup not ready"),
    modeLines: List<String>,
    selectedModeLine: String?,
    diagnosticLines: List<String>,
    failureLine: String?,
    calibrationState: CalibrationWorkflowState = CalibrationWorkflowState(),
    colorState: ColorWorkflowState = ColorWorkflowState(),
    workflowState: MeasurementWorkflowState = MeasurementWorkflowState(),
    phase14State: Phase14WorkflowState = Phase14WorkflowState(),
    importWorkflowState: ImportWorkflowState = ImportWorkflowState(),
    savedResultSummaries: List<SavedResultSummary> = emptyList(),
    exportEvidenceText: String? = null,
    setupAdjustmentTargetLabel: String = "A",
    knownDistanceFeetText: String = "",
    calibrationPlaneDepthFeetText: String = "",
    ballPlaneDepthFeetText: String = "",
    motionBlobSideRatioText: String = "2.00",
    launchHeightFeetText: String = "4.0",
    previewRotationDegrees: Int = 0,
    visualEstimateReport: VisualEstimateReport? = null,
    workflowFrameWidth: Int = 0,
    workflowFrameHeight: Int = 0,
): SpeedBallShellState =
    speedBallPlaceholderState().copy(
        cameraPermission = permissionLabel,
        captureStatus = captureStatus,
        appMode = appMode,
        runCommandState = runCommandState,
        modeLines = modeLines,
        selectedModeLine = selectedModeLine,
        resultLines = workflowGuidanceUiLines(
            calibrationState = calibrationState,
            colorState = colorState,
            workflowState = workflowState,
            phase14State = phase14State,
            frameWidth = workflowFrameWidth,
            frameHeight = workflowFrameHeight,
        ),
        diagnosticLines = diagnosticLines,
        importLines = importWorkflowUiLines(importWorkflowState),
        savedResultLines = savedResultHistoryUiLines(savedResultSummaries),
        exportLines = exportEvidenceUiLines(exportEvidenceText),
        failureLine = failureLine,
        visualEstimateReport = visualEstimateReport,
        phase14State = phase14State,
        setupAdjustmentTargetLabel = setupAdjustmentTargetLabel,
        knownDistanceFeetText = knownDistanceFeetText,
        calibrationPlaneDepthFeetText = calibrationPlaneDepthFeetText,
        ballPlaneDepthFeetText = ballPlaneDepthFeetText,
        motionBlobSideRatioText = motionBlobSideRatioText,
        launchHeightFeetText = launchHeightFeetText,
        previewRotationDegrees = previewRotationDegrees,
        sections = listOf(
            WorkflowSection("Mode", PlaceholderStatus.Pending, "Modes are loaded from the device HAL."),
            WorkflowSection("Calibrate", PlaceholderStatus.Pending, calibrationWorkflowUiLines(calibrationState).single()),
            WorkflowSection("Sample Color", PlaceholderStatus.Pending, colorWorkflowUiLines(colorState, workflowFrameWidth, workflowFrameHeight).first()),
            WorkflowSection("Capture", PlaceholderStatus.Pending, "Developer burst diagnostics are available."),
            WorkflowSection("Import", PlaceholderStatus.Pending, importWorkflowUiLines(importWorkflowState).first()),
            WorkflowSection("Results", PlaceholderStatus.Pending, measurementOutcomeUiLines(workflowState.result).first()),
        ),
    )

private fun SpeedBallRunCommandState.visibleText(): String =
    when (this) {
        is SpeedBallRunCommandState.SetupInvalid -> "setup-invalid reason=${reason.compactForImportLine()}"
        SpeedBallRunCommandState.VoiceUnavailable -> "voice-unavailable manual-shoot=true"
        is SpeedBallRunCommandState.VoiceError -> "voice-error code=$code message=${message.compactForImportLine()} manual-shoot=true"
        is SpeedBallRunCommandState.VoiceRetrying -> "voice-retrying count=$errorCount nextDelayMs=$nextDelayMillis"
        SpeedBallRunCommandState.ManualReady -> "manual-ready"
        SpeedBallRunCommandState.Listening -> "listening"
        SpeedBallRunCommandState.Capturing -> "capturing"
        SpeedBallRunCommandState.Reporting -> "reporting"
    }

fun workflowGuidanceUiLines(
    calibrationState: CalibrationWorkflowState,
    colorState: ColorWorkflowState,
    workflowState: MeasurementWorkflowState,
    phase14State: Phase14WorkflowState = Phase14WorkflowState(),
    frameWidth: Int,
    frameHeight: Int,
): List<String> =
    calibrationWorkflowUiLines(calibrationState) +
        colorWorkflowUiLines(colorState, frameWidth, frameHeight) +
        phase14WorkflowUiLines(phase14State) +
        sourceProofUiLine(workflowState.sourceProof) +
        captureWorkflowUiLine(workflowState.capture) +
        measurementOutcomeUiLines(workflowState.result)

fun importWorkflowUiLines(state: ImportWorkflowState): List<String> =
    buildList {
        add("import=estimate-only stage=${state.stage.name.lowercase()} strict=false")
        state.metadata?.let { metadata ->
            add(
                "importMetadata=${metadata.width}x${metadata.height} durationSec=${metadata.durationSeconds.format(2)} " +
                    "ptsMonotonic=${metadata.hasMonotonicPresentationTimestamps}",
            )
        }
        state.frames?.let { frames ->
            add("importFrames=${frames.frames.size} timing=${state.timing?.basis ?: "pending"}")
        }
        state.timing?.let { timing ->
            add("importTiming=${timing.basis} confidence=${timing.confidence} assumptions=${timing.assumptions.size}")
        }
        state.outcome?.let { outcome ->
            add("importResult=estimate-only")
            addAll(measurementResultUiLines(MeasurementResultUiState.EstimateOutcome(outcome)))
        }
        if (state.stage == ImportWorkflowStage.NO_READ && state.outcome == null) {
            add("importNoRead=${state.noReadReason ?: "UNKNOWN"} message=${state.message?.compactForImportLine() ?: "No imported estimate."}")
        }
    }

fun savedResultHistoryUiLines(summaries: List<SavedResultSummary>): List<String> =
    buildList {
        add("savedResults=${summaries.size}")
        summaries.take(MAX_VISIBLE_SAVED_RESULTS).forEach { summary ->
            val outcome = if (summary.noReadReason == null) "success" else "no-read"
            val value = summary.speedMilesPerHour?.let { " speedMph=${it.format(1)}" }.orEmpty()
            add(
                "savedResult id=${summary.id.compactForImportLine()} source=${summary.sourceKind} outcome=$outcome" +
                    " timing=${summary.evidence.timingBasis} frames=${summary.evidence.frameCount}" +
                    " detections=${summary.evidence.detectionCount}$value",
            )
        }
    }

fun exportEvidenceUiLines(exportEvidenceText: String?): List<String> =
    exportEvidenceText
        ?.lines()
        ?.filter { it.isNotBlank() }
        ?.take(MAX_VISIBLE_EXPORT_LINES)
        ?.map { "exportEvidence ${it.compactForImportLine()}" }
        ?: emptyList()

fun phase14WorkflowUiLines(state: Phase14WorkflowState): List<String> {
    val setup = when (state.setupMode) {
        Phase14SetupMode.KnownDistance -> {
            val ready = state.calibrationPointA != null &&
                state.calibrationPointB != null &&
                state.knownDistanceFeet?.let { it.isFinite() && it > 0.0 } == true
            if (ready) {
                "setup=known-distance status=ready"
            } else {
                "setup=known-distance status=not-ready action=set-two-points-and-known-length"
            }
        }
        Phase14SetupMode.BallDiameterFallback -> {
            val ready = state.knownBallDiameterFeet?.let { it.isFinite() && it > 0.0 } == true
            if (ready) {
                "setup=ball-diameter-fallback status=ready assumption=entered-ball-type-and-motion-blur-bias"
            } else {
                "setup=ball-diameter-fallback status=not-ready action=enter-ball-diameter assumption=secondary-fallback"
            }
        }
    }
    val color = if (state.colorSample != null && state.colorSamplePoint != null) {
        "phase14Color=ready discriminator=optional"
    } else {
        "phase14Color=not-ready action=sample-color"
    }
    val impactZone = state.impactZone?.takeIf { it.isInFrame() }?.let {
        "phase14ImpactZone=ready points=${it.points.size}"
    } ?: "phase14ImpactZone=full-frame action=draw-impact-zone-to-limit-background-noise"
    val ballBox = state.expectedBallBounds?.takeIf { it.isInFrame() }?.let {
        val width = it.points.maxOf { point -> point.x } - it.points.minOf { point -> point.x }
        val height = it.points.maxOf { point -> point.y } - it.points.minOf { point -> point.y }
        "phase14BallBox=ready widthNorm=${width.format(3)} heightNorm=${height.format(3)}"
    } ?: "phase14BallBox=not-ready action=tap-ball-and-resize-box"
    val source = if (state.permissionReady && state.geometry != null) {
        "phase14Source=ready readback=${state.geometry.readback.width}x${state.geometry.readback.height}"
    } else {
        "phase14Source=not-ready action=grant-permission-and-load-mode"
    }
    val level = state.levelReference?.let {
        "phase14Level=ready rollDeg=${it.rollDegrees.format(1)} source=${it.source} samples=${it.sampleCount}"
    } ?: "phase14Level=not-ready action=capture-still-phone-level"
    val capture = when (state.capture) {
        Phase14CaptureState.Idle -> "phase14Capture=idle canArm=${state.canArm()}"
        Phase14CaptureState.NotReady -> "phase14Capture=not-ready canArm=false"
        Phase14CaptureState.Armed -> "phase14Capture=armed"
        Phase14CaptureState.Running -> "phase14Capture=running"
        Phase14CaptureState.Complete -> "phase14Capture=complete"
    }
    val result = state.result?.let(::visualEstimateOutcomeUiLines).orEmpty()
    return listOf(setup, color, impactZone, ballBox, source, level, capture) + result
}

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

private fun String.compactForImportLine(): String =
    trim().replace(Regex("\\s+"), " ").take(MAX_IMPORT_LINE_CHARS)

private const val MAX_VISIBLE_SAVED_RESULTS = 5
private const val MAX_VISIBLE_EXPORT_LINES = 12
private const val MAX_IMPORT_LINE_CHARS = 160
