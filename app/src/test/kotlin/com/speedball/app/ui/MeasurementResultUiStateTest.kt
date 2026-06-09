package com.speedball.app.ui

import com.speedball.app.measurement.MeasurementRunFailure
import com.speedball.app.measurement.MeasurementRunOutcome
import com.speedball.app.measurement.MeasurementTimingProof
import com.speedball.app.measurement.EstimateScaleBasis
import com.speedball.app.measurement.EstimateTimingBasis
import com.speedball.app.measurement.TimestampGapSummary
import com.speedball.app.measurement.VisualEstimateConfidence
import com.speedball.app.measurement.VisualEstimateCaptureProof
import com.speedball.app.measurement.VisualEstimateDiagnostics
import com.speedball.app.measurement.VisualEstimateDetectorSummary
import com.speedball.app.measurement.VisualEstimateNoReadReason
import com.speedball.app.measurement.VisualEstimateOutcome
import com.speedball.app.measurement.VisualEstimateResultFactory
import com.speedball.core.measurement.VelocityMeasurement
import com.speedball.core.physics.TrajectoryResult
import com.speedball.core.physics.TrajectorySample
import com.speedball.core.velocity.VelocityFitResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class MeasurementResultUiStateTest {
    @Test
    fun noReadResultContainsReasonAndActionWithoutValues() {
        val lines = measurementOutcomeUiLines(
            MeasurementRunOutcome.NoRead(
                reason = MeasurementRunFailure.BAD_CALIBRATION,
                message = "Known distance must be positive.",
            ),
        )
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("result=no-read"))
        assertTrue(joined.contains("reason=BAD_CALIBRATION"))
        assertTrue(joined.contains("action=recalibrate-distance"))
        assertNoResultValues(joined)
    }

    @Test
    fun successFixtureRendersOnlyOutcomeValues() {
        val lines = measurementOutcomeUiLines(successOutcome())
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("result=success"))
        assertTrue(joined.contains("evidence=result-ui-test"))
        assertTrue(joined.contains("detections=3"))
        assertTrue(joined.contains("mph=72.5"))
        assertTrue(joined.contains("angleDeg=12.0"))
        assertTrue(joined.contains("trajectory"))
        assertTrue(joined.contains("carryFt=328.1"))
        assertTrue(joined.contains("backspinCarryFt="))
        assertTrue(joined.contains("spinRpm=1800"))
        assertTrue(joined.contains("apexFt=39.4"))
        assertTrue(joined.contains("hangSec=3.00"))
    }

    @Test
    fun estimateSuccessIsLabeledWithConfidenceDiagnosticsAndPlaneAssumption() {
        val outcome = VisualEstimateResultFactory.successOrNoRead(
            milesPerHour = 68.18182,
            launchAngleDegrees = 0.0,
            diagnostics = VisualEstimateDiagnostics(
                frameCount = 4,
                detectionCount = 4,
                timingBasis = EstimateTimingBasis.REAL_PER_FRAME_TIMESTAMPS,
                timestampGapSummary = TimestampGapSummary(
                    intervalCount = 3,
                    minGapSeconds = 0.1,
                    medianGapSeconds = 0.1,
                    maxGapSeconds = 0.2,
                ),
                fitResidualPx = 1.25,
                confidence = VisualEstimateConfidence.LOW,
            ),
        )
        val joined = visualEstimateOutcomeUiLines(outcome).joinToString("\n")

        assertTrue(joined.contains("result=estimate"))
        assertTrue(joined.contains("confidence=LOW"))
        assertTrue(joined.contains("frames=4"))
        assertTrue(joined.contains("detections=4"))
        assertTrue(joined.contains("timing=REAL_PER_FRAME_TIMESTAMPS"))
        assertTrue(joined.contains("scale=DISTANCE_CALIBRATION"))
        assertTrue(joined.contains("speed-estimate mph=68.2"))
        assertTrue(joined.contains("angleScope=in-image-plane-estimate"))
        assertTrue(joined.contains("distance-estimate carryFt="))
        assertTrue(joined.contains("backspinCarryFt="))
        assertTrue(joined.contains("spinModel=assumed-level-swing-backspin"))
        assertFalse(joined.contains("distance-estimate carryFt=0.0"))
        assertTrue(joined.contains("timestampGapMaxToMedian=2.00"))
        assertTrue(joined.contains("residualPx=1.25"))
        assertTrue(joined.contains("calibrated image plane"))
        assertFalse(joined.contains("certified", ignoreCase = true))
    }

    @Test
    fun estimateSuccessSurfacesBallDiameterScaleBasisAndAssumption() {
        val outcome = VisualEstimateResultFactory.successOrNoRead(
            milesPerHour = 68.18182,
            launchAngleDegrees = 0.0,
            diagnostics = VisualEstimateDiagnostics(
                frameCount = 4,
                detectionCount = 4,
                timingBasis = EstimateTimingBasis.REAL_PER_FRAME_TIMESTAMPS,
                timestampGapSummary = TimestampGapSummary(
                    intervalCount = 3,
                    minGapSeconds = 0.1,
                    medianGapSeconds = 0.1,
                    maxGapSeconds = 0.2,
                ),
                fitResidualPx = 1.25,
                confidence = VisualEstimateConfidence.LOW,
                scaleBasis = EstimateScaleBasis.BALL_DIAMETER_SELF_CALIBRATION,
                assumptions = listOf(
                    VisualEstimateDiagnostics.PLANAR_MOTION_ASSUMPTION,
                    VisualEstimateDiagnostics.BALL_DIAMETER_SCALE_ASSUMPTION,
                ),
            ),
        )
        val joined = visualEstimateOutcomeUiLines(outcome).joinToString("\n")

        assertTrue(joined.contains("scale=BALL_DIAMETER_SELF_CALIBRATION"))
        assertTrue(joined.contains("entered ball diameter"))
        assertTrue(joined.contains("apparent short-axis diameter"))
        assertTrue(joined.contains("wrong ball type"))
        assertTrue(joined.contains("motion blur"))
    }

    @Test
    fun visualFrameDeltaEstimateSurfacesAbsoluteScaleAssumption() {
        val outcome = VisualEstimateResultFactory.successOrNoRead(
            milesPerHour = 68.18182,
            launchAngleDegrees = 0.0,
            diagnostics = VisualEstimateDiagnostics(
                frameCount = 4,
                detectionCount = 4,
                timingBasis = EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE,
                timestampGapSummary = TimestampGapSummary(
                    intervalCount = 3,
                    minGapSeconds = 0.1,
                    medianGapSeconds = 0.1,
                    maxGapSeconds = 0.2,
                ),
                fitResidualPx = 1.25,
                confidence = VisualEstimateConfidence.LOW,
                assumptions = listOf(
                    VisualEstimateDiagnostics.PLANAR_MOTION_ASSUMPTION,
                    VisualEstimateDiagnostics.VISUAL_FRAME_DELTA_ASSUMPTION,
                ),
            ),
        )
        val joined = visualEstimateOutcomeUiLines(outcome).joinToString("\n")

        assertTrue(joined.contains("timing=VISUAL_FRAME_DELTA_INFERENCE"))
        assertTrue(joined.contains("smallest observed frame gap"))
        assertTrue(joined.contains("bias speed high"))
    }

    @Test
    fun estimateNoReadNeverDisplaysSpeedOrAngleValues() {
        val joined = visualEstimateOutcomeUiLines(
            VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.PLANAR_ASSUMPTION_VIOLATED,
                message = "Depth motion violates the calibrated-plane estimate assumption.",
            ),
        ).joinToString("\n")

        assertTrue(joined.contains("result=estimate-no-read"))
        assertTrue(joined.contains("reason=PLANAR_ASSUMPTION_VIOLATED"))
        assertTrue(joined.contains("action=keep-ball-across-calibrated-plane"))
        assertNoResultValues(joined)
    }

    @Test
    fun visualEstimateNoReadReportShowsReasonActionMessageWithoutValues() {
        val report = visualEstimateReportFor(
            attemptId = 7L,
            outcome = VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.PLANAR_ASSUMPTION_VIOLATED,
                message = "Depth motion violates the calibrated-plane estimate assumption.",
            ),
        )
        val joined = requireNotNull(report).lines.joinToString("\n")

        assertTrue(report.kind == VisualEstimateReportKind.NoRead)
        assertTrue(report.attemptId == 7L)
        assertTrue(joined.contains("NO READ"))
        assertTrue(joined.contains("REASON PLANAR_ASSUMPTION_VIOLATED"))
        assertTrue(joined.contains("ACTION keep-ball-across-calibrated-plane"))
        assertTrue(joined.contains("MESSAGE Depth motion"))
        assertNoResultValues(joined)
    }

    @Test
    fun visualEstimateFailureReportUsesFailureKindWithoutValues() {
        val report = visualEstimateReportFor(
            attemptId = 8L,
            outcome = VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                message = "Direct visual estimate session configuration failed.",
            ),
            failure = true,
        )
        val joined = requireNotNull(report).lines.joinToString("\n")

        assertTrue(report.kind == VisualEstimateReportKind.Failure)
        assertTrue(report.attemptId == 8L)
        assertTrue(joined.contains("CAPTURE FAILED"))
        assertTrue(joined.contains("REASON RESOURCE_LIMIT_EXCEEDED"))
        assertTrue(joined.contains("ACTION reduce-frame-processing-load"))
        assertNoResultValues(joined)
    }

    @Test
    fun zeroCandidateProofExplainsBallLikelyWasOutsideCameraView() {
        val proof = VisualEstimateCaptureProof(
            attemptId = 12L,
            capturedFrameCount = 24,
            frameAvailableCallbackCount = 24,
            captureResultCallbackCount = 24,
            uniqueSensorTimestampCount = 24,
            readbackWidth = 640,
            readbackHeight = 360,
            detectorSummary = VisualEstimateDetectorSummary(
                processedFrameCount = 24,
                candidateFrameCount = 0,
                candidateBlobCount = 0,
                selectedSampleCount = 0,
                noReadReason = VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
                noReadMessage = "It appears there are no moving ball blobs in the camera frame view.",
            ),
            frames = emptyList(),
            sourceKind = "RECORDED_HFR",
            sourceWidth = 1280,
            sourceHeight = 720,
            workingWidth = 640,
            workingHeight = 360,
            decodedFrameCount = 24,
            requestedFps = 120,
            dropGateVerdict = "PASS",
            cadenceGateVerdict = "PASS",
        )
        val report = visualEstimateReportFor(
            attemptId = 12L,
            outcome = VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
                message = "It appears there are no moving ball blobs in the camera frame view.",
            ),
            captureProof = proof,
        )
        val joined = requireNotNull(report).lines.joinToString("\n")

        assertTrue(joined.contains("MESSAGE It appears there are no moving ball blobs in the camera frame view."))
        assertTrue(joined.contains("EVIDENCE it appears there are no moving ball blobs in the camera frame view"))
        assertFalse(joined.contains("selected color/ROI matched no blobs"))
        assertNoResultValues(joined)
    }

    @Test
    fun visualEstimateSuccessReportContainsOnlyTypedSuccessValues() {
        val outcome = VisualEstimateResultFactory.successOrNoRead(
            milesPerHour = 68.18182,
            launchAngleDegrees = 12.0,
            diagnostics = VisualEstimateDiagnostics(
                frameCount = 4,
                detectionCount = 4,
                timingBasis = EstimateTimingBasis.REAL_PER_FRAME_TIMESTAMPS,
                timestampGapSummary = TimestampGapSummary(
                    intervalCount = 3,
                    minGapSeconds = 0.1,
                    medianGapSeconds = 0.1,
                    maxGapSeconds = 0.1,
                ),
                fitResidualPx = 1.0,
                confidence = VisualEstimateConfidence.LOW,
            ),
        )

        val report = visualEstimateReportFor(9L, outcome)
        val joined = requireNotNull(report).lines.joinToString("\n")

        assertTrue(report.kind == VisualEstimateReportKind.Success)
        assertTrue(report.attemptId == 9L)
        assertTrue(joined.contains("VELOCITY 68.2 MPH"))
        assertTrue(joined.contains("ANGLE 12.0 DEG"))
        assertTrue(joined.contains("BACKSPIN DISTANCE"))
        assertTrue(report.dismissToken.startsWith("visual-estimate-report-attempt-"))
    }

    @Test
    fun visualEstimateReportIdentityChangesOnlyWithAttemptId() {
        val outcome = VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.DETECTION_FAILED,
            message = "No visible ball.",
        )

        val first = requireNotNull(visualEstimateReportFor(10L, outcome))
        val second = requireNotNull(visualEstimateReportFor(11L, outcome))

        assertNotEquals(first.attemptId, second.attemptId)
        assertNotEquals(first.dismissToken, second.dismissToken)
        assertTrue(first.lines == second.lines)
    }

    @Test
    fun directSessionFailureNoReadNeverDisplaysSpeedOrAngleValues() {
        val joined = visualEstimateOutcomeUiLines(
            VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                message = "Direct visual estimate session configuration failed.",
            ),
        ).joinToString("\n")

        assertTrue(joined.contains("result=estimate-no-read"))
        assertTrue(joined.contains("reason=RESOURCE_LIMIT_EXCEEDED"))
        assertTrue(joined.contains("action=reduce-frame-processing-load"))
        assertNoResultValues(joined)
    }

    @Test
    fun unprovenTimingNoReadContainsNoMeasurementValues() {
        val joined = measurementOutcomeUiLines(
            MeasurementRunOutcome.NoRead(
                reason = MeasurementRunFailure.UNPROVEN_TIMING,
                message = "No production frame source has proven image timestamp pairing.",
            ),
        ).joinToString("\n")

        assertTrue(joined.contains("reason=UNPROVEN_TIMING"))
        assertTrue(joined.contains("action=prove-frame-source"))
        assertNoResultValues(joined)
    }

    @Test
    fun longFailureMessagesAreCompacted() {
        val longMessage = "  Capture failed because the current input has repeated timestamp evidence.  ".repeat(8)
        val line = measurementOutcomeUiLines(
            MeasurementRunOutcome.NoRead(
                reason = MeasurementRunFailure.BAD_FRAME_SEQUENCE,
                message = longMessage,
            ),
        ).single()

        assertTrue(line.length <= 220)
        assertTrue(line.endsWith("..."))
        assertFalse(line.contains("  "))
    }

    @Test
    fun formatterDoesNotImportWorkflowReadinessInputs() {
        val source = Files.readAllBytes(sourcePath("ui/MeasurementResultUiState.kt"))
            .toString(Charsets.UTF_8)

        assertFalse(source.contains("CalibrationWorkflow"))
        assertFalse(source.contains("ColorWorkflow"))
        assertFalse(source.contains("Readiness"))
    }

    private fun assertNoResultValues(text: String) {
        assertFalse(text.contains("m" + "ph", ignoreCase = true))
        assertFalse(text.contains("ang" + "le", ignoreCase = true))
        assertFalse(text.contains("tra" + "jectory", ignoreCase = true))
        assertFalse(text.contains("car" + "ry", ignoreCase = true))
        assertFalse(text.contains("apex", ignoreCase = true))
        assertFalse(text.contains("hang", ignoreCase = true))
    }

    private fun successOutcome(): MeasurementRunOutcome.Success =
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
                override val evidenceLabel: String = "result-ui-test"
            },
        )

    private fun sourcePath(relativeFileName: String): Path {
        val appPath = Path.of("app/src/main/java/com/speedball/app/$relativeFileName")
        return if (Files.exists(appPath)) {
            appPath
        } else {
            Path.of("src/main/java/com/speedball/app/$relativeFileName")
        }
    }
}
