package com.speedball.app.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong

class TimestampDiagnosticsTest {
    @Test
    fun sharedNormalizerFiltersNonPositiveValues() {
        val normalized = normalizeSensorTimestamps(listOf(-5L, 0L, 10L, 20L))

        assertEquals(2, normalized.positiveCount)
        assertEquals(listOf(10L, 20L), normalized.uniqueTimestampsNanos)
    }

    @Test
    fun sharedNormalizerKeepsPositiveCountBeforeDistinctSorting() {
        val normalized = normalizeSensorTimestamps(listOf(30L, 10L, 30L, 20L, 10L))

        assertEquals(5, normalized.positiveCount)
        assertEquals(listOf(10L, 20L, 30L), normalized.uniqueTimestampsNanos)
    }

    @Test
    fun sharedNormalizerHandlesEmptyInput() {
        val normalized = normalizeSensorTimestamps(emptyList())

        assertEquals(0, normalized.positiveCount)
        assertTrue(normalized.uniqueTimestampsNanos.isEmpty())
    }

    @Test
    fun exactDuplicatesCollapseButNearDuplicatesRemain() {
        val diagnostics = buildTimestampDiagnostics(
            listOf(ts(0.0), ts(8.33), ts(8.33), ts(8.34), ts(16.66)),
            fps = 120,
        )

        assertEquals(4, diagnostics.uniqueCount)
        assertEquals(3, diagnostics.gapNanos.size)
        assertEquals(0.01, diagnostics.gapNanos[1] / 1_000_000.0, 0.01)
    }

    @Test
    fun medianAndMaximumGapsAreReportedSeparately() {
        val diagnostics = buildTimestampDiagnostics(
            listOf(ts(0.0), ts(8.33), ts(16.66), ts(33.32), ts(41.65)),
            fps = 120,
        )

        assertEquals(8.33, diagnostics.medianGapMillis!!, 0.01)
        assertEquals(16.66, diagnostics.maximumGapMillis!!, 0.01)
        assertTrue(diagnostics.medianGapPassesRateBand)
        assertFalse(diagnostics.maximumGapPassesDropThreshold)
    }

    @Test
    fun fpsBandAndDropThresholdArePinnedFor120Fps() {
        val diagnostics = buildTimestampDiagnostics(listOf(ts(0.0), ts(8.33), ts(16.66)), fps = 120)

        assertEquals(8.333, diagnostics.expectedGapMillis, 0.001)
        assertEquals(7.083, diagnostics.gapLowerBoundMillis, 0.001)
        assertEquals(9.583, diagnostics.gapUpperBoundMillis, 0.001)
        assertEquals(12.5, diagnostics.droppedFrameGapThresholdMillis, 0.001)
    }

    @Test
    fun orderedDiagnosticsKeepZeroPresentationTimestamp() {
        val diagnostics = buildOrderedTimestampDiagnostics(
            listOf(0L, 16_666_000L, 24_999_000L, 33_332_000L),
            fps = 120,
        )

        assertEquals(4, diagnostics.uniqueCount)
        assertEquals(3, diagnostics.gapNanos.size)
        assertEquals(16.666, diagnostics.maximumGapMillis!!, 0.001)
        assertFalse(diagnostics.maximumGapPassesDropThreshold)
    }

    @Test
    fun nearDuplicateEvidenceCountsOneNanosecondGroups() {
        val first = ts(0.0)
        val evidence = buildNearDuplicateEvidence(
            timestampsNanos = listOf(first, first + 1L, ts(8.333), ts(16.666)),
            decodedFrameCount = 3,
        )

        assertEquals(4, evidence.rawPositiveSensorTimestampCount)
        assertEquals(4, evidence.exactDistinctSensorTimestampCount)
        assertEquals(1, evidence.nearDuplicateGroupCount)
        assertEquals(listOf(1L), evidence.representativeNearDuplicateGapsNanos)
    }

    @Test
    fun nearDuplicateEvidenceSharesExactDistinctNormalizationWithTimestampDiagnostics() {
        val first = ts(0.0)
        val raw = listOf(0L, -1L, first, first, first + 1L, ts(16.666))
        val normalized = normalizeSensorTimestamps(raw)
        val diagnostics = buildTimestampDiagnostics(raw, fps = 120)
        val evidence = buildNearDuplicateEvidence(raw, decodedFrameCount = 3)

        assertEquals(normalized.positiveCount, diagnostics.positiveCount)
        assertEquals(normalized.uniqueTimestampsNanos, diagnostics.uniqueTimestampsNanos)
        assertEquals(diagnostics.positiveCount, evidence.rawPositiveSensorTimestampCount)
        assertEquals(diagnostics.uniqueCount, evidence.exactDistinctSensorTimestampCount)
        assertEquals(diagnostics.uniqueTimestampsNanos.size, evidence.exactDistinctSensorTimestampCount)
    }

    @Test
    fun postCollapseCountCanMatchDecodedCountAsEvidenceOnly() {
        val first = ts(0.0)
        val evidence = buildNearDuplicateEvidence(
            timestampsNanos = listOf(first, first + 1L, ts(8.333), ts(16.666)),
            decodedFrameCount = 3,
        )

        assertEquals(3, evidence.hypotheticalPostCollapseSensorCount)
        assertEquals(PostCollapseSensorCountComparison.MATCHES_DECODED_COUNT, evidence.postCollapseComparison)
        assertEquals("postCollapseMatchesDecodedCount", evidence.postCollapseComparison.diagnosticName)
        assertTrue(evidence.interpretation.contains("Evidence only."))
    }

    @Test
    fun postCollapseCountCanRemainMismatched() {
        val first = ts(0.0)
        val evidence = buildNearDuplicateEvidence(
            timestampsNanos = listOf(first, first + 1L, ts(8.333), ts(16.666), ts(24.999)),
            decodedFrameCount = 3,
        )

        assertEquals(4, evidence.hypotheticalPostCollapseSensorCount)
        assertEquals(PostCollapseSensorCountComparison.STILL_MISMATCHED, evidence.postCollapseComparison)
        assertEquals("postCollapseStillMismatched", evidence.postCollapseComparison.diagnosticName)
    }

    @Test
    fun postCollapseComparisonCanBeUnavailable() {
        val evidence = buildNearDuplicateEvidence(
            timestampsNanos = emptyList(),
            decodedFrameCount = 3,
        )

        assertEquals(null, evidence.hypotheticalPostCollapseSensorCount)
        assertEquals(PostCollapseSensorCountComparison.UNAVAILABLE, evidence.postCollapseComparison)
        assertEquals("postCollapseUnavailable", evidence.postCollapseComparison.diagnosticName)
    }

    private fun ts(offsetMillis: Double): Long =
        1_000_000_000L + (offsetMillis * 1_000_000.0).roundToLong()
}
