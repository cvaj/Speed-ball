package com.speedball.app.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong

class FrameTimestampReconcilerTest {
    @Test
    fun exactDecodedAndSensorCountsPairByIndex() {
        val outcome = reconcileFrameTimestamps(metadata(frameCount = 4), sensorTimestamps(count = 4), requestedFps = 120)
        val success = assertInstanceOf(DecodeOutcome.Success::class.java, outcome)

        assertEquals(4, success.pairs.size)
        assertEquals(0, success.pairs.first().frameIndex)
        assertEquals(0.0, success.pairs.first().relativeTimestampSeconds, 0.0)
        assertEquals(0.025, success.pairs.last().relativeTimestampSeconds, 0.001)
        assertTrue(success.pairs.zipWithNext().all { (a, b) -> b.relativeTimestampSeconds > a.relativeTimestampSeconds })
        assertTrue(success.diagnostics.ptsToSensorOffsetSummary!!.spreadMicros <= 1_000L)
    }

    @Test
    fun exactCountSuccessUsesSensorTimestampsNotPresentationTimestampsForPairs() {
        val pts = listOf(99_000L, 107_333L, 115_666L, 123_999L)
        val sensor = sensorTimestamps(count = 4)
        val outcome = reconcileFrameTimestamps(metadata(pts), sensor, requestedFps = 120)
        val success = assertInstanceOf(DecodeOutcome.Success::class.java, outcome)

        assertEquals(listOf(0, 1, 2, 3), success.pairs.map { it.frameIndex })
        assertEquals(sensor, success.pairs.map { it.sensorTimestampNanos })
        assertEquals(listOf(0.0, 0.008333, 0.016666, 0.024999), success.pairs.map { it.relativeTimestampSeconds })
        assertEquals(listOf("frameIndex", "sensorTimestampNanos", "relativeTimestampSeconds"), FrameTimestampPair::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") })
    }

    @Test
    fun decodedCountGreaterThanSensorCountFails() {
        val failure = reconcileFrameTimestamps(metadata(frameCount = 4), sensorTimestamps(count = 3), 120).asFailure()

        assertEquals(DecodeFailure.FRAME_SENSOR_COUNT_MISMATCH, failure.reason)
        assertEquals(ReconciliationDiagnostics::class.java, failure.diagnostics!!::class.java)
    }

    @Test
    fun sensorCountGreaterThanDecodedCountFails() {
        val failure = reconcileFrameTimestamps(metadata(frameCount = 3), sensorTimestamps(count = 4), 120).asFailure()

        assertEquals(DecodeFailure.FRAME_SENSOR_COUNT_MISMATCH, failure.reason)
        val diagnostics: Any? = failure.diagnostics
        assertFalse(diagnostics is TimestampAnchorDiagnostics)
    }

    @Test
    fun interiorSensorDropFailsEvenWhenMedianIsInBand() {
        val sensor = listOf(ts(0.0), ts(8.333), ts(16.666), ts(33.332), ts(41.665), ts(49.998))
        val failure = reconcileFrameTimestamps(metadata(frameCount = 6), sensor, 120).asFailure()

        assertEquals(DecodeFailure.SENSOR_DROPPED_FRAME_GAP, failure.reason)
    }

    @Test
    fun sensorGapJustOverOnePointFiveExpectedGapFails() {
        val sensor = listOf(ts(0.0), ts(8.333), ts(16.666), ts(29.167), ts(37.500), ts(45.833))
        val failure = reconcileFrameTimestamps(metadata(frameCount = 6), sensor, 120).asFailure()

        assertEquals(DecodeFailure.SENSOR_DROPPED_FRAME_GAP, failure.reason)
        assertEquals(12.5, failure.diagnostics!!.droppedFrameGapThresholdMillis, 0.001)
    }

    @Test
    fun interiorPresentationDropFailsEvenWhenMedianIsInBand() {
        val pts = listOf(0L, 8_333L, 16_666L, 33_332L, 41_665L, 49_998L)
        val failure = reconcileFrameTimestamps(metadata(pts), sensorTimestamps(count = 6), 120).asFailure()

        assertEquals(DecodeFailure.PRESENTATION_DROPPED_FRAME_GAP, failure.reason)
    }

    @Test
    fun presentationGapJustOverOnePointFiveExpectedGapFails() {
        val pts = listOf(0L, 8_333L, 16_666L, 29_167L, 37_500L, 45_833L)
        val failure = reconcileFrameTimestamps(metadata(pts), sensorTimestamps(count = 6), 120).asFailure()

        assertEquals(DecodeFailure.PRESENTATION_DROPPED_FRAME_GAP, failure.reason)
        assertEquals(12.5, failure.diagnostics!!.droppedFrameGapThresholdMillis, 0.001)
    }

    @Test
    fun firstPresentationGapDropFailsWhenFirstPtsIsZero() {
        val pts = listOf(0L, 16_666L, 24_999L, 33_332L, 41_665L, 49_998L)
        val failure = reconcileFrameTimestamps(metadata(pts), sensorTimestamps(count = 6), 120).asFailure()

        assertEquals(DecodeFailure.PRESENTATION_DROPPED_FRAME_GAP, failure.reason)
    }

    @Test
    fun presentationMedianOutsideBandFails() {
        val pts = listOf(0L, 10_400L, 20_800L, 31_200L)
        val failure = reconcileFrameTimestamps(metadata(pts), sensorTimestamps(count = 4), 120).asFailure()

        assertEquals(DecodeFailure.PRESENTATION_CADENCE_MISMATCH, failure.reason)
    }

    @Test
    fun sensorMedianOutsideBandFails() {
        val failure = reconcileFrameTimestamps(metadata(frameCount = 4), sensorTimestamps(count = 4, gapMillis = 10.4), 120).asFailure()

        assertEquals(DecodeFailure.SENSOR_CADENCE_MISMATCH, failure.reason)
    }

    @Test
    fun nearDuplicateSensorTimestampFailsBeforeCountMismatchRepair() {
        val sensor = listOf(ts(0.0), ts(0.5), ts(8.333), ts(16.666))
        val failure = reconcileFrameTimestamps(metadata(frameCount = 3), sensor, 120).asFailure()

        assertEquals(DecodeFailure.SENSOR_TIMESTAMP_NEAR_DUPLICATE, failure.reason)
    }

    @Test
    fun oneNanosecondNearDuplicateRemainsHardFailureBeforeCountMismatch() {
        val first = ts(0.0)
        val sensor = listOf(first, first + 1L, ts(8.333), ts(16.666))
        val failure = reconcileFrameTimestamps(metadata(frameCount = 3), sensor, 120).asFailure()

        assertEquals(DecodeFailure.SENSOR_TIMESTAMP_NEAR_DUPLICATE, failure.reason)
        assertEquals(0.000001, failure.diagnostics!!.nearDuplicateGapMillis!!, 0.0)
        assertEquals(3, failure.diagnostics.decodedFrameCount)
        assertEquals(4, failure.diagnostics.uniqueSensorTimestampCount)
    }

    @Test
    fun legitimate120FpsGapDoesNotTriggerNearDuplicateGate() {
        val outcome = reconcileFrameTimestamps(metadata(frameCount = 3), sensorTimestamps(count = 3), 120)

        assertInstanceOf(DecodeOutcome.Success::class.java, outcome)
    }

    @Test
    fun missingSensorTimestampsFailLoud() {
        val failure = reconcileFrameTimestamps(metadata(frameCount = 3), emptyList(), 120).asFailure()

        assertEquals(DecodeFailure.MISSING_SENSOR_TIMESTAMPS, failure.reason)
    }

    private fun metadata(frameCount: Int): DecodedVideoMetadata =
        metadata(List(frameCount) { index -> (index * 8_333.333).roundToLong() })

    private fun metadata(pts: List<Long>): DecodedVideoMetadata =
        DecodedVideoMetadata(
            frameCount = pts.size,
            width = 1280,
            height = 720,
            durationMicros = pts.lastOrNull(),
            presentationTimeMicros = pts,
            medianPresentationGapMillis = null,
            maximumPresentationGapMillis = null,
        )

    private fun sensorTimestamps(count: Int, gapMillis: Double = 8.333): List<Long> =
        List(count) { index -> ts(index * gapMillis) }

    private fun ts(offsetMillis: Double): Long =
        1_000_000_000L + (offsetMillis * 1_000_000.0).roundToLong()

    private fun DecodeOutcome.asFailure(): DecodeOutcome.Failure =
        assertInstanceOf(DecodeOutcome.Failure::class.java, this)
}
