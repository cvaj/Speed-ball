package com.speedball.app.ui

import com.speedball.app.decode.DecodedFrameSample
import com.speedball.app.decode.DecodedVideoMetadata
import com.speedball.app.decode.DecodeFailure
import com.speedball.app.decode.DecodeOutcome
import com.speedball.app.decode.OffsetSummary
import com.speedball.app.decode.PresentationClockAssessment
import com.speedball.app.decode.ReconciliationDiagnostics
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeedBallShellStateTest {
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

    private fun assertNoMeasurementWords(text: String) {
        assertFalse(text.contains("m" + "ph", ignoreCase = true))
        assertFalse(text.contains("ang" + "le", ignoreCase = true))
        assertFalse(text.contains("tra" + "jectory", ignoreCase = true))
        assertFalse(text.contains("velo" + "city", ignoreCase = true))
        assertFalse(text.contains("car" + "ry", ignoreCase = true))
        assertFalse(text.contains("dist" + "ance", ignoreCase = true))
    }
}
