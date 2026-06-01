package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong

class DirectFrameTimestampProofTest {
    @Test
    fun exactSensorMembershipSucceeds() {
        val timestamps = timestamps(count = 4)
        val success = assertInstanceOf(
            DirectFrameTimestampProofOutcome.Success::class.java,
            proveDirectFrameTimestamps(timestamps, timestamps, fps = 120),
        )

        assertEquals(DirectFrameTimestampVerdict.EXACT_SENSOR_MEMBERSHIP, success.diagnostics.verdict)
        assertEquals(timestamps, success.matches.map { it.directTimestampNanos })
        assertEquals(timestamps, success.matches.map { it.sensorTimestampNanos })
        assertEquals(List(4) { 0L }, success.matches.map { it.offsetNanos })
        assertEquals(4, success.diagnostics.exactMatchCount)
    }

    @Test
    fun subMicrosecondZeroOffsetEquivalentSucceedsOnlyWhenUnique() {
        val sensor = timestamps(count = 4)
        val direct = sensor.map { it + 999L }
        val success = assertInstanceOf(
            DirectFrameTimestampProofOutcome.Success::class.java,
            proveDirectFrameTimestamps(direct, sensor, fps = 120),
        )

        assertEquals(DirectFrameTimestampVerdict.ZERO_OFFSET_EQUIVALENT, success.diagnostics.verdict)
        assertEquals(List(4) { 999L }, success.matches.map { it.offsetNanos })
        assertEquals(0, success.diagnostics.exactMatchCount)
    }

    @Test
    fun offsetAtOneMicrosecondRejects() {
        val sensor = timestamps(count = 4)
        val direct = sensor.map { it + DIRECT_ZERO_OFFSET_EQUIVALENT_BOUND_NANOS }
        val failure = proveDirectFrameTimestamps(direct, sensor, fps = 120).asFailure()

        assertEquals(DirectTimingSourceFailure.NONZERO_OFFSET_OUT_OF_BOUND, failure.reason)
        assertEquals(listOf(DIRECT_ZERO_OFFSET_EQUIVALENT_BOUND_NANOS), failure.diagnostics.offsetNanos.distinct())
    }

    @Test
    fun wholeFrameOffsetRejectsAsWrongByKAmbiguous() {
        val sensor = timestamps(count = 4)
        val direct = sensor.map { it + ns(8.333333) }
        val failure = proveDirectFrameTimestamps(direct, sensor, fps = 120).asFailure()

        assertEquals(DirectTimingSourceFailure.AMBIGUOUS_WRONG_BY_K_OFFSET, failure.reason)
    }

    @Test
    fun previewOnlyS10CadenceShapeStillRejectsAt120() {
        val thirtyFps = timestamps(count = 4, gapMillis = 33.3775)
        val failure = proveDirectFrameTimestamps(thirtyFps, thirtyFps, fps = 120).asFailure()

        assertEquals(DirectTimingSourceFailure.DIRECT_CADENCE_MISMATCH, failure.reason)
        assertEquals(33.3775, failure.diagnostics.medianDirectGapMillis!!, 0.0001)
    }

    @Test
    fun degradedTwentyFourFrameStreamRejectsAsWholeInsteadOfFilteringToTwelve() {
        val degraded = timestamps(count = 24).toMutableList().apply {
            this[12] = this[11] + 1L
        }
        val failure = proveDirectFrameTimestamps(degraded, degraded, fps = 120).asFailure()

        assertEquals(DirectTimingSourceFailure.DIRECT_TIMESTAMP_NEAR_DUPLICATE, failure.reason)
        assertEquals(24, failure.diagnostics.rawDirectTimestampCount)
    }

    @Test
    fun duplicateDirectTimestampRejects() {
        val timestamps = listOf(ts(0.0), ts(8.333333), ts(8.333333), ts(16.666666))
        val failure = proveDirectFrameTimestamps(timestamps, timestamps, fps = 120).asFailure()

        assertEquals(DirectTimingSourceFailure.DUPLICATE_DIRECT_TIMESTAMPS, failure.reason)
    }

    @Test
    fun nonMonotonicDirectTimestampRejects() {
        val direct = listOf(ts(0.0), ts(16.666666), ts(8.333333))
        val failure = proveDirectFrameTimestamps(direct, direct.sorted(), fps = 120).asFailure()

        assertEquals(DirectTimingSourceFailure.DIRECT_TIMESTAMPS_NON_MONOTONIC, failure.reason)
    }

    @Test
    fun nearDuplicateDirectTimestampRejects() {
        val direct = listOf(ts(0.0), ts(0.5), ts(8.333333))
        val failure = proveDirectFrameTimestamps(direct, direct, fps = 120).asFailure()

        assertEquals(DirectTimingSourceFailure.DIRECT_TIMESTAMP_NEAR_DUPLICATE, failure.reason)
    }

    @Test
    fun droppedDirectGapRejects() {
        val direct = listOf(ts(0.0), ts(8.333333), ts(25.0), ts(33.333333))
        val failure = proveDirectFrameTimestamps(direct, direct, fps = 120).asFailure()

        assertEquals(DirectTimingSourceFailure.DIRECT_DROPPED_FRAME_GAP, failure.reason)
    }

    @Test
    fun interiorMissingSensorMembershipRejectsWithoutOrderFallback() {
        val direct = timestamps(count = 4)
        val sensor = listOf(direct[0], direct[1], direct[3])
        val failure = proveDirectFrameTimestamps(direct, sensor, fps = 120).asFailure()

        assertEquals(DirectTimingSourceFailure.SENSOR_MEMBERSHIP_UNAVAILABLE, failure.reason)
        assertTrue(failure.diagnostics.exactMatchCount < direct.size)
    }

    @Test
    fun missingDirectOrSensorTimestampsReject() {
        assertEquals(
            DirectTimingSourceFailure.MISSING_DIRECT_TIMESTAMPS,
            proveDirectFrameTimestamps(emptyList(), timestamps(count = 4), fps = 120).asFailure().reason,
        )
        assertEquals(
            DirectTimingSourceFailure.MISSING_SENSOR_TIMESTAMPS,
            proveDirectFrameTimestamps(timestamps(count = 4), emptyList(), fps = 120).asFailure().reason,
        )
    }

    private fun DirectFrameTimestampProofOutcome.asFailure(): DirectFrameTimestampProofOutcome.Failure =
        assertInstanceOf(DirectFrameTimestampProofOutcome.Failure::class.java, this)

    private fun timestamps(count: Int, startMillis: Double = 0.0, gapMillis: Double = 8.333333): List<Long> =
        List(count) { index -> ts(startMillis + index * gapMillis) }

    private fun ts(offsetMillis: Double): Long =
        1_000_000_000L + ns(offsetMillis)

    private fun ns(millis: Double): Long =
        (millis * 1_000_000.0).roundToLong()
}
