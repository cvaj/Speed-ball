package com.speedball.app.ui

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
import com.speedball.app.measurement.MeasurementRunFailure
import com.speedball.app.measurement.MeasurementRunOutcome
import com.speedball.app.measurement.MeasurementTimingProof
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

    private fun assertNoMeasurementWords(text: String) {
        assertFalse(text.contains("m" + "ph", ignoreCase = true))
        assertFalse(text.contains("ang" + "le", ignoreCase = true))
        assertFalse(text.contains("tra" + "jectory", ignoreCase = true))
        assertFalse(text.contains("velo" + "city", ignoreCase = true))
        assertFalse(text.contains("car" + "ry", ignoreCase = true))
        assertFalse(text.contains("dist" + "ance", ignoreCase = true))
    }

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
}
