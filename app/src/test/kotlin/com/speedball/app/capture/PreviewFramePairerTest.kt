package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong

class PreviewFramePairerTest {
    @Test
    fun exactIdentitySuccessPairsPreviewTimestamps() {
        val timestamps = timestamps(count = 4)
        val success = assertInstanceOf(
            PreviewFrameOutcome.Success::class.java,
            pairPreviewFrameTimestamps(timestamps, timestamps, fps = 120),
        )

        assertEquals(timestamps, success.pairs.map { it.previewTimestampNanos })
        assertEquals(timestamps, success.pairs.map { it.sensorTimestampNanos })
        assertEquals(PreviewFramePairingVerdict.EXACT_VALUE_MEMBERSHIP, success.diagnostics.verdict)
        assertEquals(4, success.diagnostics.exactMatchCount)
    }

    @Test
    fun sensorOvercountDoesNotAutoAbortWhenPreviewMembershipAndCadenceAreClean() {
        val preview = timestamps(count = 4)
        val sensor = listOf(preview.first() - ns(8.333)) + preview + listOf(preview.last() + ns(8.333))
        val success = assertInstanceOf(
            PreviewFrameOutcome.Success::class.java,
            pairPreviewFrameTimestamps(preview, sensor, fps = 120),
        )

        assertEquals(4, success.pairs.size)
        assertTrue(success.diagnostics.coalescingEvidence)
    }

    @Test
    fun previewCadenceMismatchRejectsEvenWithExactSensorMembership() {
        val thirtyFpsPreview = timestamps(count = 4, gapMillis = 33.3775)
        val failure = pairPreviewFrameTimestamps(thirtyFpsPreview, thirtyFpsPreview, fps = 120).asFailure()

        assertEquals(PreviewFrameFailure.PREVIEW_CADENCE_MISMATCH, failure.reason)
        assertEquals(4, failure.diagnostics!!.exactMatchCount)
        assertEquals(33.3775, failure.diagnostics.medianPreviewGapMillis!!, 0.0001)
    }

    @Test
    fun previewUndercountCoalescingRejectsWithSpecificReason() {
        val preview = listOf(ts(0.0), ts(8.333333), ts(25.0), ts(33.333333))
        val sensor = timestamps(count = 5)
        val failure = pairPreviewFrameTimestamps(preview, sensor, fps = 120).asFailure()

        assertEquals(PreviewFrameFailure.PREVIEW_UNDERCOUNT_COALESCING, failure.reason)
        assertEquals(true, failure.diagnostics!!.coalescingEvidence)
    }

    @Test
    fun previewSideNearDuplicateRejects() {
        val preview = listOf(ts(0.0), ts(0.5), ts(8.333))
        val failure = pairPreviewFrameTimestamps(preview, preview, fps = 120).asFailure()

        assertEquals(PreviewFrameFailure.PREVIEW_TIMESTAMP_NEAR_DUPLICATE, failure.reason)
    }

    @Test
    fun oneLeadingAndOneTrailingMissingPreviewTimestampCanTrim() {
        val core = timestamps(count = 4)
        val preview = listOf(core.first() - ns(8.333)) + core + listOf(core.last() + ns(8.333))
        val success = assertInstanceOf(
            PreviewFrameOutcome.Success::class.java,
            pairPreviewFrameTimestamps(preview, core, fps = 120),
        )

        assertEquals(core, success.pairs.map { it.previewTimestampNanos })
        assertEquals(1, success.diagnostics.unmatchedLeadingPreviewCount)
        assertEquals(1, success.diagnostics.unmatchedTrailingPreviewCount)
    }

    @Test
    fun interiorMissingPreviewTimestampRejects() {
        val preview = timestamps(count = 5)
        val sensor = listOf(preview[0], preview[1], preview[3], preview[4])
        val failure = pairPreviewFrameTimestamps(preview, sensor, fps = 120).asFailure()

        assertEquals(PreviewFrameFailure.SENSOR_MEMBERSHIP_UNAVAILABLE, failure.reason)
    }

    @Test
    fun moreThanOneLeadingTrimRejects() {
        val core = timestamps(count = 4, startMillis = 16.666)
        val preview = listOf(ts(0.0), ts(8.333)) + core
        val failure = pairPreviewFrameTimestamps(preview, core, fps = 120).asFailure()

        assertEquals(PreviewFrameFailure.FRAME_SENSOR_COUNT_MISMATCH, failure.reason)
    }

    @Test
    fun nonzeroConstantOffsetRequiresReview() {
        val sensor = timestamps(count = 4)
        val preview = sensor.map { it + ns(2.0) }
        val failure = pairPreviewFrameTimestamps(preview, sensor, fps = 120).asFailure()

        assertEquals(PreviewFrameFailure.NONZERO_OFFSET_REQUIRES_REVIEW, failure.reason)
        assertEquals(listOf(ns(2.0)), failure.diagnostics!!.offsetNanos.distinct())
    }

    @Test
    fun wholeFrameOffsetIsAmbiguous() {
        val sensor = timestamps(count = 4)
        val preview = sensor.map { it + ns(8.333333) }
        val failure = pairPreviewFrameTimestamps(preview, sensor, fps = 120).asFailure()

        assertEquals(PreviewFrameFailure.AMBIGUOUS_OFFSET, failure.reason)
    }

    @Test
    fun equalCountOneEdgeUnmatchedWithRemainingMembershipIsAmbiguous() {
        val sensor = timestamps(count = 4)
        val preview = listOf(sensor[1], sensor[2], sensor[3], sensor[3] + ns(8.333333))
        val failure = pairPreviewFrameTimestamps(preview, sensor, fps = 120).asFailure()

        assertEquals(PreviewFrameFailure.AMBIGUOUS_OFFSET, failure.reason)
        assertEquals(1, failure.diagnostics!!.unmatchedTrailingPreviewCount)
    }

    private fun PreviewFrameOutcome.asFailure(): PreviewFrameOutcome.Failure =
        assertInstanceOf(PreviewFrameOutcome.Failure::class.java, this)

    private fun timestamps(count: Int, startMillis: Double = 0.0, gapMillis: Double = 8.333333): List<Long> =
        List(count) { index -> ts(startMillis + index * gapMillis) }

    private fun ts(offsetMillis: Double): Long =
        1_000_000_000L + ns(offsetMillis)

    private fun ns(millis: Double): Long =
        (millis * 1_000_000.0).roundToLong()
}
