package com.speedball.app.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong

class TimestampAnchorAnalyzerTest {
    @Test
    fun nearDuplicatePostCollapseMatchStillRejectsAsEvidenceOnly() {
        val first = ts(0.0)
        val outcome = analyzeTimestampAnchorEvidence(
            metadata = metadata(frameCount = 3),
            rawSensorTimestampsNanos = listOf(first, first + 1L, ts(8.333), ts(16.666)),
        )
        val rejected = assertInstanceOf(TimestampAnchorOutcome.Rejected::class.java, outcome)
        val evidence = rejected.diagnostics.nearDuplicateEvidence!!

        assertEquals(TimestampAnchorFailure.SENSOR_NEAR_DUPLICATE, rejected.reason)
        assertEquals(4, rejected.diagnostics.rawSensorTimestampCount)
        assertEquals(3, evidence.hypotheticalPostCollapseSensorCount)
        assertEquals(PostCollapseSensorCountComparison.MATCHES_DECODED_COUNT, evidence.postCollapseComparison)
        assertTrue(outcome !is TimestampAnchorOutcome.Proven)
    }

    @Test
    fun nearDuplicatePostCollapseMismatchRejectsWithEvidence() {
        val first = ts(0.0)
        val outcome = analyzeTimestampAnchorEvidence(
            metadata = metadata(frameCount = 3),
            rawSensorTimestampsNanos = listOf(first, first + 1L, ts(8.333), ts(16.666), ts(24.999)),
        )
        val rejected = assertInstanceOf(TimestampAnchorOutcome.Rejected::class.java, outcome)
        val evidence = rejected.diagnostics.nearDuplicateEvidence!!

        assertEquals(TimestampAnchorFailure.SENSOR_NEAR_DUPLICATE, rejected.reason)
        assertEquals(4, evidence.hypotheticalPostCollapseSensorCount)
        assertEquals(PostCollapseSensorCountComparison.STILL_MISMATCHED, evidence.postCollapseComparison)
    }

    private fun metadata(frameCount: Int): DecodedVideoMetadata =
        DecodedVideoMetadata(
            frameCount = frameCount,
            width = 1280,
            height = 720,
            durationMicros = null,
            presentationTimeMicros = List(frameCount) { index -> (index * 8_333.333).roundToLong() },
            medianPresentationGapMillis = null,
            maximumPresentationGapMillis = null,
        )

    private fun ts(offsetMillis: Double): Long =
        1_000_000_000L + (offsetMillis * 1_000_000.0).roundToLong()
}
