package com.speedball.app.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong

class TimestampDiagnosticsTest {
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

    private fun ts(offsetMillis: Double): Long =
        1_000_000_000L + (offsetMillis * 1_000_000.0).roundToLong()
}
