package com.speedball.app.ui

import com.speedball.app.capture.DirectPreviewControlReport
import com.speedball.app.capture.DirectProofSessionShape
import com.speedball.app.capture.DirectSessionProbeOutcome
import com.speedball.app.capture.DirectTimingSourceFailure
import com.speedball.app.capture.DirectTimingSourceProofOutcome
import com.speedball.app.capture.DirectTimingSourceProofRunResult
import com.speedball.app.capture.PreviewFrameDiagnostics
import com.speedball.app.capture.PreviewFrameFailure
import com.speedball.app.capture.PreviewFrameOutcome
import com.speedball.app.capture.PreviewFramePairingVerdict
import com.speedball.app.decode.DecodedFrameSample
import com.speedball.app.decode.DecodedVideoMetadata
import com.speedball.app.decode.DecodeFailure
import com.speedball.app.decode.DecodeOutcome
import com.speedball.app.decode.OffsetSummary
import com.speedball.app.decode.PresentationClockAssessment
import com.speedball.app.decode.ReconciliationDiagnostics
import com.speedball.app.importing.ImportEvidenceSummary
import com.speedball.app.importing.ImportNoReadReason
import com.speedball.app.importing.ImportResultSourceKind
import com.speedball.app.importing.ImportTimingBasis
import com.speedball.app.importing.ImportWorkflowEvent
import com.speedball.app.importing.ImportWorkflowState
import com.speedball.app.importing.SavedResultSummary
import com.speedball.app.measurement.CalibrationWorkflowState
import com.speedball.app.measurement.ColorWorkflowState
import com.speedball.app.measurement.HsvColor
import com.speedball.app.measurement.HsvTolerance
import com.speedball.app.measurement.LevelReferenceCalculator
import com.speedball.app.measurement.LevelReferenceDisplayRotation
import com.speedball.app.measurement.LevelReferenceSnapshot
import com.speedball.app.measurement.LevelReferenceSource
import com.speedball.app.measurement.MeasurementRunFailure
import com.speedball.app.measurement.MeasurementRunOutcome
import com.speedball.app.measurement.MeasurementTimingProof
import com.speedball.app.measurement.MeasurementWorkflowEvent
import com.speedball.app.measurement.MeasurementWorkflowState
import com.speedball.app.measurement.NormalizedFramePoint
import com.speedball.app.measurement.NormalizedFrameRect
import com.speedball.app.measurement.Phase14CaptureState
import com.speedball.app.measurement.Phase14Geometry
import com.speedball.app.measurement.Phase14SetupMode
import com.speedball.app.measurement.Phase14WorkflowEvent
import com.speedball.app.measurement.Phase14WorkflowState
import com.speedball.app.measurement.RegionOfInterest
import com.speedball.core.model.ImagePoint
import com.speedball.core.measurement.VelocityMeasurement
import com.speedball.core.physics.TrajectoryResult
import com.speedball.core.physics.TrajectorySample
import com.speedball.core.velocity.VelocityFitResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedBallShellStateTest {
    @Test
    fun previewFailureDiagnosticsDoNotExposeMeasurementWords() {
        val lines = previewOutcomeUiLines(
            PreviewFrameOutcome.Failure(
                reason = PreviewFrameFailure.SURFACE_CONFIGURATION_REJECTED,
                message = "Preview surface rejected.",
                diagnostics = previewDiagnostics(),
            ),
        )
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("previewFailure=SURFACE_CONFIGURATION_REJECTED"))
        assertTrue(joined.contains("previewFrames=3"))
        assertTrue(joined.contains("sensorTs=5"))
        assertTrue(joined.contains("coalescing=false"))
        assertNoMeasurementWords(joined)
    }

    @Test
    fun previewCancelledIsDistinct() {
        assertTrue(previewOutcomeUiLines(PreviewFrameOutcome.Cancelled).single().contains("cancelled"))
    }

    @Test
    fun decodeSuccessDiagnosticsExposeOnlyRawProofValues() {
        val lines = decodeOutcomeUiLines(successOutcome())
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("decodeFrames=3"))
        assertTrue(joined.contains("uniqueSensorTs=3"))
        assertTrue(joined.contains("sensorGapMs"))
        assertTrue(joined.contains("ptsGapMs"))
        assertTrue(joined.contains("ptsSensorOffsetMicros"))
        assertTrue(joined.contains("sampledFrames=0:1280x720@0us"))
        assertNoMeasurementWords(joined)
    }

    @Test
    fun decodeFailureIsTypedNoReadDiagnostic() {
        val lines = decodeOutcomeUiLines(
            DecodeOutcome.Failure(DecodeFailure.FRAME_SENSOR_COUNT_MISMATCH, "Decoded frame count did not exactly match unique SENSOR_TIMESTAMP count."),
        )
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("decodeFailure=FRAME_SENSOR_COUNT_MISMATCH"))
        assertNoMeasurementWords(joined)
    }

    @Test
    fun decodeFailureUiDoesNotExposeAnchorDiagnosticsOrRawArrays() {
        val lines = decodeOutcomeUiLines(
            DecodeOutcome.Failure(
                reason = DecodeFailure.FRAME_SENSOR_COUNT_MISMATCH,
                message = "Decoded frame count did not exactly match unique SENSOR_TIMESTAMP count.",
                diagnostics = successOutcome().diagnostics.copy(
                    exactCountPasses = false,
                    sensorGapNanos = listOf(1_000_000L, 2_000_000L),
                    presentationGapMicros = listOf(1_000L, 2_000L),
                ),
            ),
        )
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("decodeFailure=FRAME_SENSOR_COUNT_MISMATCH"))
        assertFalse(joined.contains("anchor", ignoreCase = true))
        assertFalse(joined.contains("["))
        assertFalse(joined.contains("1000000"))
        assertNoMeasurementWords(joined)
    }

    @Test
    fun decodeCancelledIsDistinct() {
        assertTrue(decodeOutcomeUiLines(DecodeOutcome.Cancelled).single().contains("cancelled"))
    }

    @Test
    fun measurementNoReadUiDoesNotExposeMeasurementValues() {
        val lines = measurementOutcomeUiLines(
            MeasurementRunOutcome.NoRead(
                reason = MeasurementRunFailure.UNPROVEN_TIMING,
                message = "Timing source is not proven.",
            ),
        )
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("result=no-read"))
        assertTrue(joined.contains("UNPROVEN_TIMING"))
        assertNoMeasurementWords(joined)
    }

    @Test
    fun defaultStateSurfacesProductionNoReadReason() {
        val joined = speedBallPlaceholderState().resultLines.joinToString("\n")

        assertTrue(joined.contains("result=no-read"))
        assertTrue(joined.contains("UNPROVEN_TIMING"))
        assertTrue(joined.contains("No production frame source has proven"))
        assertNoMeasurementWords(joined)
    }

    @Test
    fun measurementSuccessUiDisplaysOnlySuccessValues() {
        val lines = measurementOutcomeUiLines(successMeasurementOutcome())
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("result=success"))
        assertTrue(joined.contains("mph=72.5"))
        assertTrue(joined.contains("angleDeg=12.0"))
        assertTrue(joined.contains("carryFt=328.1"))
    }

    @Test
    fun directProofFailureDiagnosticsRemainNoReadAndPathFree() {
        val lines = directProofRunUiLines(
            DirectTimingSourceProofRunResult(
                companionOutcome = DirectSessionProbeOutcome.Failure(
                    shape = DirectProofSessionShape.COMPANION_ENCODER,
                    reason = DirectTimingSourceFailure.DIRECT_CADENCE_MISMATCH,
                    message = "Consumed direct cadence did not match requested rate.",
                    requestListSize = 4,
                    directTimestampCount = 66,
                    sensorTimestampCount = 320,
                    pixelProofCount = 66,
                ),
                previewControl = DirectPreviewControlReport(attempted = true, outcome = previewControlFailure()),
                proofOutcome = DirectTimingSourceProofOutcome.Failure(
                    reason = DirectTimingSourceFailure.DIRECT_CADENCE_MISMATCH,
                    message = "Consumed direct cadence did not match requested rate.",
                ),
            ),
        )
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("directProofFailure=DIRECT_CADENCE_MISMATCH"))
        assertTrue(joined.contains("directCompanionFailure=DIRECT_CADENCE_MISMATCH"))
        assertTrue(joined.contains("directPreviewControl attempted=true"))
        assertFalse(joined.contains("/storage/"))
        assertFalse(joined.contains(".mp4"))
        assertNoMeasurementWords(joined)
    }

    @Test
    fun calibrationWorkflowUiShowsReadinessWithoutResultValues() {
        val lines = calibrationWorkflowUiLines(
            CalibrationWorkflowState().select(
                pointA = ImagePoint(0.0, 0.0),
                pointB = ImagePoint(12.0, 0.0),
                knownDistanceFeet = 6.0,
            ),
        )
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("calibration=ready"))
        assertTrue(joined.contains("pixelsPerFoot=2.00"))
        assertFalse(joined.contains("m" + "ph", ignoreCase = true))
        assertFalse(joined.contains("ang" + "le", ignoreCase = true))
        assertFalse(joined.contains("tra" + "jectory", ignoreCase = true))
        assertFalse(joined.contains("car" + "ry", ignoreCase = true))
        assertFalse(joined.contains("apex", ignoreCase = true))
        assertFalse(joined.contains("hang", ignoreCase = true))
    }

    @Test
    fun calibrationWorkflowUiShowsActionableNoReadWithoutResultValues() {
        val lines = calibrationWorkflowUiLines(CalibrationWorkflowState())
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("calibration=not-ready"))
        assertTrue(joined.contains("action=set-two-points-and-known-length"))
        assertFalse(joined.contains("m" + "ph", ignoreCase = true))
        assertFalse(joined.contains("ang" + "le", ignoreCase = true))
        assertFalse(joined.contains("tra" + "jectory", ignoreCase = true))
        assertFalse(joined.contains("car" + "ry", ignoreCase = true))
        assertFalse(joined.contains("apex", ignoreCase = true))
        assertFalse(joined.contains("hang", ignoreCase = true))
    }

    @Test
    fun colorWorkflowUiShowsSamplePreviewWithoutResultValues() {
        val lines = colorWorkflowUiLines(
            ColorWorkflowState().selectSample(
                sample = HsvColor(hueDegrees = 359.0, saturation = 1.0, value = 0.8),
                tolerance = HsvTolerance(hueDegrees = 400.0, saturation = 0.2, value = 0.2),
                regionOfInterest = RegionOfInterest(left = -5, top = 4, rightExclusive = 20, bottomExclusive = 12),
            ),
            frameWidth = 16,
            frameHeight = 10,
        )
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("colorSample=ready"))
        assertTrue(joined.contains("preview=detector-setup"))
        assertTrue(joined.contains("hueDeg=359.0"))
        assertTrue(joined.contains("roi=0,4,16x10"))
        assertFalse(joined.contains("result=", ignoreCase = true))
        assertFalse(joined.contains("conf" + "idence", ignoreCase = true))
        assertNoMeasurementWords(joined)
    }

    @Test
    fun colorWorkflowUiShowsActionableSetupWithoutResultValues() {
        val lines = colorWorkflowUiLines(ColorWorkflowState(), frameWidth = 16, frameHeight = 10)
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("colorSample=not-ready"))
        assertTrue(joined.contains("action=sample-ball-color"))
        assertFalse(joined.contains("result=", ignoreCase = true))
        assertFalse(joined.contains("conf" + "idence", ignoreCase = true))
        assertNoMeasurementWords(joined)
    }

    @Test
    fun appShellDefaultIsNoReadAndNotReady() {
        val state = speedBallCaptureState(
            permissionLabel = "Granted",
            captureStatus = "Idle",
            modeLines = emptyList(),
            selectedModeLine = null,
            diagnosticLines = emptyList(),
            failureLine = null,
        )
        val joined = state.visibleText.joinToString("\n")

        assertTrue(joined.contains("calibration=not-ready"))
        assertTrue(joined.contains("colorSample=not-ready"))
        assertTrue(joined.contains("sourceProof=not-ready"))
        assertTrue(joined.contains("captureWorkflow=idle"))
        assertTrue(joined.contains("result=no-read"))
        assertNoResultValuesExceptCalibrationText(joined)
    }

    @Test
    fun appModeAndRunCommandStateAreVisibleAndIndependentFromCaptureStatus() {
        val runState = speedBallCaptureState(
            permissionLabel = "Granted",
            captureStatus = "Idle",
            appMode = SpeedBallAppMode.Run,
            runCommandState = SpeedBallRunCommandState.VoiceUnavailable,
            modeLines = emptyList(),
            selectedModeLine = null,
            diagnosticLines = emptyList(),
            failureLine = null,
        )
        val setupState = runState.copy(
            appMode = SpeedBallAppMode.Setup,
            runCommandState = SpeedBallRunCommandState.SetupInvalid("Sample the ball color before measuring."),
        )

        val runText = runState.visibleText.joinToString("\n")
        val setupText = setupState.visibleText.joinToString("\n")

        assertTrue(runText.contains("appMode=Run"))
        assertTrue(runText.contains("runCommand=voice-unavailable manual-shoot=true"))
        assertTrue(runText.contains("Idle"))
        assertTrue(setupText.contains("appMode=Setup"))
        assertTrue(setupText.contains("runCommand=setup-invalid reason=Sample the ball color before measuring."))
        assertTrue(setupText.contains("Idle"))
    }

    @Test
    fun developerProofDiagnosticsDoNotCreateMeasurementResultValues() {
        val state = speedBallCaptureState(
            permissionLabel = "Granted",
            captureStatus = "Direct proof complete",
            modeLines = listOf("1280x720 @ 120 fps (recordable)"),
            selectedModeLine = "1280x720 @ 120 fps",
            diagnosticLines = listOf("directProof=success frames=12 tokenEligible=false"),
            failureLine = null,
        )
        val joined = state.visibleText.joinToString("\n")

        assertTrue(joined.contains("directProof=success"))
        assertTrue(joined.contains("result=no-read"))
        assertNoResultValuesExceptCalibrationText(joined)
    }

    @Test
    fun calibrationAndColorReadinessUpdateShellText() {
        val state = speedBallCaptureState(
            permissionLabel = "Granted",
            captureStatus = "Idle",
            modeLines = emptyList(),
            selectedModeLine = null,
            diagnosticLines = emptyList(),
            failureLine = null,
            calibrationState = CalibrationWorkflowState().select(
                pointA = ImagePoint(0.0, 0.0),
                pointB = ImagePoint(12.0, 0.0),
                knownDistanceFeet = 6.0,
            ),
            colorState = ColorWorkflowState().selectSample(
                sample = HsvColor(0.0, 1.0, 1.0),
                regionOfInterest = RegionOfInterest(0, 0, 16, 10),
            ),
            workflowFrameWidth = 16,
            workflowFrameHeight = 10,
        )
        val joined = state.visibleText.joinToString("\n")

        assertTrue(joined.contains("calibration=ready pixelsPerFoot=2.00"))
        assertTrue(joined.contains("colorSample=ready"))
        assertTrue(joined.contains("roi=0,0,16x10"))
        assertTrue(joined.contains("result=no-read"))
        assertNoResultValuesExceptCalibrationText(joined)
    }

    @Test
    fun phase14KnownDistanceReadinessTextDoesNotDisplayEstimateValues() {
        val state = speedBallCaptureState(
            permissionLabel = "Granted",
            captureStatus = "Visual estimate armed",
            modeLines = emptyList(),
            selectedModeLine = "1280x720 @ 120 fps",
            diagnosticLines = emptyList(),
            failureLine = null,
            phase14State = phase14ReadyState(),
        )
        val joined = state.visibleText.joinToString("\n")

        assertTrue(joined.contains("setup=known-distance status=ready"))
        assertTrue(joined.contains("phase14Color=ready"))
        assertTrue(joined.contains("phase14Source=ready readback=160x90"))
        assertTrue(joined.contains("phase14Level=ready"))
        assertTrue(joined.contains("phase14Capture=idle canArm=true"))
        assertNoResultValuesExceptCalibrationText(joined)
    }

    @Test
    fun phase14SetupAdjustmentTargetIsVisibleWithoutEstimateValues() {
        val state = speedBallCaptureState(
            permissionLabel = "Granted",
            captureStatus = "Setup target ROI",
            modeLines = emptyList(),
            selectedModeLine = "1280x720 @ 120 fps",
            diagnosticLines = emptyList(),
            failureLine = null,
            phase14State = phase14ReadyState(),
            setupAdjustmentTargetLabel = "ROI",
        )
        val joined = state.visibleText.joinToString("\n")

        assertTrue(joined.contains("setupTarget=ROI"))
        assertTrue(joined.contains("phase14Color=ready"))
        assertNoResultValuesExceptCalibrationText(joined)
    }

    @Test
    fun phase14FallbackShowsSetupAssumptionBeforeCapture() {
        val state = phase14ReadyState()
            .reduce(Phase14WorkflowEvent.SetupModeSelected(Phase14SetupMode.BallDiameterFallback))
            .reduce(Phase14WorkflowEvent.KnownBallDiameterChanged(0.25))
        val joined = phase14WorkflowUiLines(state).joinToString("\n")

        assertTrue(joined.contains("setup=ball-diameter-fallback status=ready"))
        assertTrue(joined.contains("assumption=entered-ball-type-and-motion-blur-bias"))
        assertFalse(joined.contains("certified", ignoreCase = true))
        assertFalse(joined.contains("m" + "ph", ignoreCase = true))
    }

    @Test
    fun phase14NoReadResultSuppressesSpeedValues() {
        val state = phase14ReadyState().copy(capture = Phase14CaptureState.Complete).reduce(
            Phase14WorkflowEvent.CaptureCompleted(
                com.speedball.app.measurement.VisualEstimateOutcome.NoRead(
                    reason = com.speedball.app.measurement.VisualEstimateNoReadReason.DETECTION_FAILED,
                    message = "No single ball blob was detected.",
                ),
            ),
        )
        val joined = phase14WorkflowUiLines(state).joinToString("\n")

        assertTrue(joined.contains("result=estimate-no-read"))
        assertTrue(joined.contains("DETECTION_FAILED"))
        assertFalse(joined.contains("m" + "ph", ignoreCase = true))
        assertFalse(joined.contains("ang" + "le", ignoreCase = true))
        assertFalse(joined.contains("tra" + "jectory", ignoreCase = true))
    }

    @Test
    fun importWorkflowNoReadUsesEstimateLabelAndSuppressesValues() {
        val importState = ImportWorkflowState().reduce(
            ImportWorkflowEvent.ImportFailed(
                reason = ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
                message = "Imported video did not expose trusted timing.",
            ),
        )
        val joined = importWorkflowUiLines(importState).joinToString("\n")

        assertTrue(joined.contains("import=estimate-only"))
        assertTrue(joined.contains("strict=false"))
        assertTrue(joined.contains("NO_TRUSTWORTHY_TIMING"))
        assertFalse(joined.contains("m" + "ph", ignoreCase = true))
        assertFalse(joined.contains("ang" + "le", ignoreCase = true))
        assertFalse(joined.contains("content://"))
    }

    @Test
    fun savedAndExportLinesAreRedactedImportEvidence() {
        val summary = savedImportSummary(speed = 70.4)
        val joined = (
            savedResultHistoryUiLines(listOf(summary)) +
                exportEvidenceUiLines("source=IMPORT_ESTIMATE\nspeedMph=70.4\nassumption=Estimate only.")
            ).joinToString("\n")

        assertTrue(joined.contains("savedResults=1"))
        assertTrue(joined.contains("source=IMPORT_ESTIMATE"))
        assertTrue(joined.contains("speedMph=70.4"))
        assertFalse(joined.contains("content://"))
        assertFalse(joined.contains("/storage"))
        assertFalse(joined.contains("pixel", ignoreCase = true))
    }

    @Test
    fun sourceUnprovenShellContainsNoResultValues() {
        val workflowState = MeasurementWorkflowState()
            .reduce(MeasurementWorkflowEvent.CalibrationSelected)
            .reduce(MeasurementWorkflowEvent.ColorSampleSelected)
            .reduce(MeasurementWorkflowEvent.StartCapture)
        val joined = speedBallCaptureState(
            permissionLabel = "Granted",
            captureStatus = "Idle",
            modeLines = emptyList(),
            selectedModeLine = null,
            diagnosticLines = emptyList(),
            failureLine = null,
            workflowState = workflowState,
        ).visibleText.joinToString("\n")

        assertTrue(joined.contains("sourceProof=not-ready"))
        assertTrue(joined.contains("reason=UNPROVEN_TIMING"))
        assertNoResultValuesExceptCalibrationText(joined)
    }

    @Test
    fun fullPrivatePathsAreNotIntroducedByDecodeLines() {
        val joined = decodeOutcomeUiLines(successOutcome()).joinToString("\n")

        assertFalse(joined.contains("/home/"))
        assertFalse(joined.contains("/storage/"))
    }

    private fun successOutcome(): DecodeOutcome.Success =
        DecodeOutcome.Success(
            metadata = DecodedVideoMetadata(
                frameCount = 3,
                width = 1280,
                height = 720,
                durationMicros = 16_666L,
                presentationTimeMicros = listOf(0L, 8_333L, 16_666L),
                medianPresentationGapMillis = 8.33,
                maximumPresentationGapMillis = 8.33,
            ),
            pairs = emptyList(),
            diagnostics = ReconciliationDiagnostics(
                decodedFrameCount = 3,
                uniqueSensorTimestampCount = 3,
                medianSensorGapMillis = 8.33,
                maximumSensorGapMillis = 8.33,
                medianPresentationGapMillis = 8.33,
                maximumPresentationGapMillis = 8.33,
                expectedGapMillis = 8.33,
                gapLowerBoundMillis = 7.08,
                gapUpperBoundMillis = 9.58,
                droppedFrameGapThresholdMillis = 12.50,
                exactCountPasses = true,
                nearDuplicateGapMillis = null,
                ptsToSensorOffsetSummary = OffsetSummary(0L, 10L, 10L),
                presentationClockAssessment = PresentationClockAssessment.UNKNOWN,
                sampledFrames = listOf(
                    DecodedFrameSample(0, 1280, 720, 0L),
                    DecodedFrameSample(2, 1280, 720, 16_666L),
                ),
            ),
        )

    private fun previewDiagnostics(): PreviewFrameDiagnostics =
        PreviewFrameDiagnostics(
            rawPreviewTimestampCount = 3,
            positivePreviewTimestampCount = 3,
            uniquePreviewTimestampCount = 3,
            rawSensorTimestampCount = 6,
            positiveSensorTimestampCount = 5,
            uniqueSensorTimestampCount = 5,
            medianPreviewGapMillis = 8.33,
            maximumPreviewGapMillis = 8.33,
            medianSensorGapMillis = 8.33,
            maximumSensorGapMillis = 8.33,
            expectedGapMillis = 8.33,
            droppedFrameGapThresholdMillis = 12.5,
            exactMatchCount = 3,
            sensorMembershipCount = 3,
            unmatchedLeadingPreviewCount = 0,
            unmatchedTrailingPreviewCount = 0,
            coalescingEvidence = false,
            verdict = PreviewFramePairingVerdict.NOT_EVALUATED,
        )

    private fun previewControlFailure(): PreviewFrameOutcome.Failure =
        PreviewFrameOutcome.Failure(PreviewFrameFailure.PREVIEW_CADENCE_MISMATCH, "Preview remains diagnostic.")

    private fun assertNoMeasurementWords(text: String) {
        assertFalse(text.contains("m" + "ph", ignoreCase = true))
        assertFalse(text.contains("ang" + "le", ignoreCase = true))
        assertFalse(text.contains("tra" + "jectory", ignoreCase = true))
        assertFalse(text.contains("velo" + "city", ignoreCase = true))
        assertFalse(text.contains("car" + "ry", ignoreCase = true))
        assertFalse(text.contains("dist" + "ance", ignoreCase = true))
    }

    private fun assertNoResultValuesExceptCalibrationText(text: String) {
        assertFalse(text.contains("m" + "ph", ignoreCase = true))
        assertFalse(text.contains("ang" + "le", ignoreCase = true))
        assertFalse(text.contains("tra" + "jectory", ignoreCase = true))
        assertFalse(text.contains("velo" + "city", ignoreCase = true))
        assertFalse(text.contains("car" + "ry", ignoreCase = true))
        assertFalse(text.contains("apex", ignoreCase = true))
        assertFalse(text.contains("hang", ignoreCase = true))
    }

    private fun phase14ReadyState(): Phase14WorkflowState =
        Phase14WorkflowState()
            .reduce(Phase14WorkflowEvent.PermissionChanged(true))
            .reduce(
                Phase14WorkflowEvent.GeometryChanged(
                    Phase14Geometry(
                        modeId = "1280x720@120",
                        previewTransformId = "fit",
                        source = com.speedball.app.measurement.FrameDimensions(1280, 720),
                        readback = com.speedball.app.measurement.FrameDimensions(160, 90),
                    ),
                ),
            )
            .reduce(
                Phase14WorkflowEvent.KnownDistanceSelected(
                    pointA = NormalizedFramePoint(0.2, 0.5),
                    pointB = NormalizedFramePoint(0.8, 0.5),
                    knownDistanceFeet = 8.0,
                ),
            )
            .reduce(
                Phase14WorkflowEvent.ColorSampleSelected(
                    point = NormalizedFramePoint(0.5, 0.5),
                    sample = HsvColor(42.0, 0.8, 0.8),
                    tolerance = HsvTolerance(12.0, 0.2, 0.2),
                ),
            )
            .reduce(Phase14WorkflowEvent.RegionOfInterestSelected(NormalizedFrameRect(0.1, 0.1, 0.9, 0.9)))
            .reduce(Phase14WorkflowEvent.LevelReferenceCaptured(levelSnapshot()))

    private fun levelSnapshot(): LevelReferenceSnapshot =
        LevelReferenceSnapshot(
            rollDegrees = 0.0,
            pitchDegrees = 0.0,
            sampleCount = LevelReferenceCalculator.MIN_SAMPLE_COUNT,
            source = LevelReferenceSource.GRAVITY_SENSOR,
            displayRotation = LevelReferenceDisplayRotation.ROTATION_0,
            maxGyroMagnitudeRadPerSecond = 0.0,
            capturedAtEpochMillis = 1L,
        )

    private fun successMeasurementOutcome(): MeasurementRunOutcome.Success =
        MeasurementRunOutcome.Success(
            measurement = VelocityMeasurement(
                fit = VelocityFitResult(
                    xInterceptPx = 0.0,
                    yInterceptPx = 0.0,
                    vxPxPerSecond = 10.0,
                    vyPxPerSecond = -2.0,
                    speedPxPerSecond = 10.2,
                    launchAngleDegrees = 12.0,
                    rSquaredX = 1.0,
                    rSquaredY = 1.0,
                    rmsResidualPx = 0.1,
                    usedOriginalIndices = listOf(0, 1, 2),
                ),
                pixelsPerFoot = 2.0,
                feetPerSecond = 100.0,
                milesPerHour = 72.5,
                launchAngleDegrees = 12.0,
            ),
            trajectory = TrajectoryResult(
                samples = listOf(TrajectorySample(0.0, 0.0, 0.0, 0.0, 0.0)),
                apexMeters = 12.0,
                carryMeters = 100.0,
                hangTimeSeconds = 3.0,
            ),
            detectionCount = 3,
            timingProof = object : MeasurementTimingProof {
                override val evidenceLabel: String = "ui-test"
            },
        )

    private fun savedImportSummary(speed: Double?): SavedResultSummary =
        SavedResultSummary(
            id = "result-1",
            createdAtEpochMillis = 1_000L,
            sourceKind = ImportResultSourceKind.IMPORT_ESTIMATE,
            evidence = ImportEvidenceSummary(
                sourceKind = ImportResultSourceKind.IMPORT_ESTIMATE,
                timingBasis = ImportTimingBasis.CONTAINER_PRESENTATION_TIMESTAMPS,
                frameCount = 4,
                detectionCount = 4,
                assumptions = listOf("Estimate only."),
            ),
            speedMilesPerHour = speed,
            launchAngleDegrees = null,
            noReadReason = null,
        )
}
