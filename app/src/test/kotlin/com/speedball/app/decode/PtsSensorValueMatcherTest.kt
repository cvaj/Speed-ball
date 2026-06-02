package com.speedball.app.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PtsSensorValueMatcherTest {
    @Test
    fun bindsDecodedPtsToSubsetOfSensorTimestampsWithoutEqualCounts() {
        val pts = listOf(0L, 8_333L, 16_666L, 24_999L)
        val offsetMicros = 1_000_000L
        val sensors = listOf(
            sensor(offsetMicros),
            sensor(offsetMicros + 8_333L),
            sensor(offsetMicros + 16_666L),
            sensor(offsetMicros + 24_999L),
            sensor(offsetMicros + 33_332L),
        )

        val diagnostics = analyzePtsSensorValueMatch(pts, sensors, minimumCleanRun = 4)

        assertEquals(PtsSensorValueMatchVerdict.UNIQUE_MATCH, diagnostics.verdict)
        assertEquals(4, diagnostics.matchedFrameCount)
        assertEquals(4, diagnostics.unambiguousFrameCount)
        assertEquals(4, diagnostics.longestContiguousUnambiguousRun)
        assertEquals(0, diagnostics.ambiguousFrameCount)
        assertEquals(offsetMicros, diagnostics.bestOffsetMicros)
        assertEquals(0L, diagnostics.maximumResidualMicros)
    }

    @Test
    fun reportsNoMatchWhenNoSingleOffsetBindsEnoughFrames() {
        val pts = listOf(0L, 8_333L, 16_666L)
        val sensors = listOf(sensor(100_000L), sensor(200_000L), sensor(300_000L))

        val diagnostics = analyzePtsSensorValueMatch(pts, sensors, toleranceMicros = 10L)

        assertEquals(PtsSensorValueMatchVerdict.PARTIAL_MATCH, diagnostics.verdict)
        assertTrue(diagnostics.matchedFrameCount < pts.size)
        assertTrue(diagnostics.longestContiguousUnambiguousRun < 12)
    }

    @Test
    fun marksNearDuplicateSensorCandidatesAsAmbiguous() {
        val pts = listOf(0L, 8_333L, 16_666L)
        val offsetMicros = 1_000_000L
        val sensors = listOf(
            sensor(offsetMicros),
            sensor(offsetMicros) + 1L,
            sensor(offsetMicros + 8_333L),
            sensor(offsetMicros + 16_666L),
        )

        val diagnostics = analyzePtsSensorValueMatch(pts, sensors, minimumCleanRun = 3)

        assertEquals(PtsSensorValueMatchVerdict.AMBIGUOUS_MATCH, diagnostics.verdict)
        assertEquals(3, diagnostics.matchedFrameCount)
        assertEquals(1, diagnostics.ambiguousFrameCount)
        assertEquals(2, diagnostics.unambiguousFrameCount)
    }

    @Test
    fun countMismatchDiagnosticsCarryValueMatchResult() {
        val pts = listOf(0L, 8_333L, 16_666L, 24_999L)
        val offsetMicros = 1_000_000L
        val sensors = listOf(
            sensor(offsetMicros),
            sensor(offsetMicros + 8_333L),
            sensor(offsetMicros + 16_666L),
            sensor(offsetMicros + 24_999L),
            sensor(offsetMicros + 33_332L),
        )

        val failure = reconcileFrameTimestamps(
            metadata = DecodedVideoMetadata(
                frameCount = pts.size,
                width = 1280,
                height = 720,
                durationMicros = pts.last(),
                presentationTimeMicros = pts,
                medianPresentationGapMillis = null,
                maximumPresentationGapMillis = null,
            ),
            rawSensorTimestampsNanos = sensors,
            requestedFps = 120,
        ) as DecodeOutcome.Failure

        assertEquals(DecodeFailure.FRAME_SENSOR_COUNT_MISMATCH, failure.reason)
        val valueMatch = failure.diagnostics!!.ptsSensorValueMatch!!
        assertEquals(PtsSensorValueMatchVerdict.PARTIAL_MATCH, valueMatch.verdict)
        assertEquals(4, valueMatch.matchedFrameCount)
        assertEquals(4, valueMatch.longestContiguousUnambiguousRun)
    }

    private fun sensor(micros: Long): Long = micros * 1_000L
}
