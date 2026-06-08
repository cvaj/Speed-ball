package com.speedball.app.capture

import com.speedball.app.decode.buildTimestampDiagnostics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong

class BurstDiagnosticsTest {
    @Test
    fun duplicateCallbacksDoNotInflateUniqueTimestampProof() {
        val timestamps = listOf(timestampNanos(0.0), timestampNanos(8.33), timestampNanos(8.33), timestampNanos(16.66))

        val diagnostics = buildBurstDiagnostics(
            timestampsNanos = timestamps,
            callbackCount = 4,
            requestedDurationMillis = 2_500L,
            fps = 120,
            outputPath = "/private/path/burst.mp4",
            fileBytes = 123L,
        )

        assertEquals(3, diagnostics.uniqueTimestampCount)
        assertEquals(4, diagnostics.callbackCount)
        assertEquals("burst.mp4", diagnostics.displayOutputName)
        assertEquals(8.33, diagnostics.medianGapMillis!!, 0.01)
        assertEquals(8.33, diagnostics.maximumGapMillis!!, 0.01)
    }

    @Test
    fun emptySensorTimestampsFailLoud() {
        val outcome = buildBurstOutcome(
            timestampsNanos = emptyList(),
            callbackCount = 0,
            requestedDurationMillis = 2_500L,
            fps = 120,
            outputPath = "burst.mp4",
            fileBytes = 0L,
        )

        val failure = assertInstanceOf(BurstOutcome.Failure::class.java, outcome)
        assertEquals(BurstFailure.NO_SENSOR_TIMESTAMPS, failure.reason)
    }

    @Test
    fun expectedUniqueCountAndMinimumArePinnedFor120Fps() {
        val diagnostics = diagnosticsWithUniqueCount(count = 240, gapMillis = 8.33)

        assertEquals(300, diagnostics.expectedUniqueTimestampCount)
        assertEquals(240, diagnostics.minimumUniqueTimestampCount)
        assertTrue(diagnostics.uniqueCountPassesRequestedMinimum)
    }

    @Test
    fun uniqueCountBelowMinimumFails() {
        val diagnostics = diagnosticsWithUniqueCount(count = 239, gapMillis = 8.33)

        assertFalse(diagnostics.uniqueCountPassesRequestedMinimum)
        assertFalse(diagnostics.captureProofPasses)
    }

    @Test
    fun medianGapMustStayInsideRateBand() {
        val pass = diagnosticsWithUniqueCount(count = 300, gapMillis = 8.33)
        val fail = diagnosticsWithUniqueCount(count = 300, gapMillis = 10.4)

        assertTrue(pass.medianGapPassesRateBand)
        assertTrue(pass.captureProofPasses)
        assertFalse(fail.medianGapPassesRateBand)
        assertFalse(fail.captureProofPasses)
    }

    @Test
    fun captureProofRequiresBothUniqueFloorAndMedianGapBand() {
        val countPassesGapFails = diagnosticsWithUniqueCount(count = 240, gapMillis = 10.4)
        val gapPassesCountFails = diagnosticsWithUniqueCount(count = 239, gapMillis = 8.33)

        assertTrue(countPassesGapFails.uniqueCountPassesRequestedMinimum)
        assertFalse(countPassesGapFails.medianGapPassesRateBand)
        assertFalse(countPassesGapFails.captureProofPasses)
        assertFalse(gapPassesCountFails.uniqueCountPassesRequestedMinimum)
        assertTrue(gapPassesCountFails.medianGapPassesRateBand)
        assertFalse(gapPassesCountFails.captureProofPasses)
    }

    @Test
    fun phase4S10ProofGoldensRemainPinned() {
        val passing = diagnosticsWithUniqueCount(count = 325, gapMillis = 8.33)
        val lifecycleStop = diagnosticsWithUniqueCount(count = 45, gapMillis = 8.33)

        assertEquals(300, passing.expectedUniqueTimestampCount)
        assertEquals(240, passing.minimumUniqueTimestampCount)
        assertTrue(passing.medianGapPassesRateBand)
        assertTrue(passing.uniqueCountPassesRequestedMinimum)
        assertTrue(passing.captureProofPasses)
        assertEquals(300, lifecycleStop.expectedUniqueTimestampCount)
        assertEquals(240, lifecycleStop.minimumUniqueTimestampCount)
        assertTrue(lifecycleStop.medianGapPassesRateBand)
        assertFalse(lifecycleStop.uniqueCountPassesRequestedMinimum)
        assertFalse(lifecycleStop.captureProofPasses)
    }

    @Test
    fun sharedTimestampHelperAndBurstDiagnosticsUseSameUniqueCount() {
        val timestamps = listOf(timestampNanos(0.0), timestampNanos(8.33), timestampNanos(8.33), timestampNanos(24.99))
        val helper = buildTimestampDiagnostics(timestamps, fps = 120)
        val diagnostics = buildBurstDiagnostics(
            timestampsNanos = timestamps,
            callbackCount = timestamps.size,
            requestedDurationMillis = 2_500L,
            fps = 120,
            outputPath = "burst.mp4",
            fileBytes = 1L,
        )

        assertEquals(helper.uniqueCount, diagnostics.uniqueTimestampCount)
        assertEquals(helper.maximumGapMillis!!, diagnostics.maximumGapMillis!!, 0.01)
        assertEquals(helper.droppedFrameGapThresholdMillis, diagnostics.droppedFrameGapThresholdMillis, 0.01)
    }

    @Test
    fun maximumGapDoesNotFeedCaptureProof() {
        val timestamps = List(250) { index ->
            val adjustedIndex = if (index >= 120) index + 1 else index
            timestampNanos(adjustedIndex * 8.33)
        }
        val diagnostics = buildBurstDiagnostics(
            timestampsNanos = timestamps,
            callbackCount = timestamps.size,
            requestedDurationMillis = 2_500L,
            fps = 120,
            outputPath = "burst.mp4",
            fileBytes = 1L,
        )

        assertTrue(diagnostics.uniqueCountPassesRequestedMinimum)
        assertTrue(diagnostics.medianGapPassesRateBand)
        assertTrue(diagnostics.maximumGapMillis!! > diagnostics.droppedFrameGapThresholdMillis)
        assertTrue(diagnostics.captureProofPasses)
    }

    @Test
    fun terminalResolutionPreservesPrimaryFailureOverReleaseFailure() {
        assertEquals(
            BurstFailure.CAMERA_DEVICE_ERROR,
            resolveTerminalFailure(BurstFailure.CAMERA_DEVICE_ERROR, BurstFailure.RESOURCE_RELEASE_FAILED),
        )
        assertEquals(BurstFailure.RESOURCE_RELEASE_FAILED, resolveTerminalFailure(null, BurstFailure.RESOURCE_RELEASE_FAILED))
    }

    @Test
    fun terminalCompletionGateCanBeClaimedOnlyOnceUntilReset() {
        val gate = TerminalCompletionGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())
        gate.reset()
        assertTrue(gate.claim())
    }

    @Test
    fun synchronousFailureReasonsArePinned() {
        val synchronousReasons = BurstFailure.entries.filter(::shouldDeliverSynchronously)

        assertEquals(
            listOf(
                BurstFailure.CAMERA_PERMISSION_DENIED,
                BurstFailure.NO_BACK_CAMERA,
                BurstFailure.CAMERA_OPEN_FAILED,
                BurstFailure.RECORDER_PREPARE_FAILED,
            ),
            synchronousReasons,
        )
        assertFalse(shouldDeliverSynchronously(BurstFailure.CAPTURE_BUSY))
        assertFalse(shouldDeliverSynchronously(BurstFailure.RECORDING_FAILED))
    }

    @Test
    fun durationClampUsesSafeBounds() {
        assertEquals(DEFAULT_BURST_DURATION_MILLIS, clampBurstDurationMillis(-1L))
        assertEquals(1_000L, clampBurstDurationMillis(1_000L))
        assertEquals(7_700L, clampBurstDurationMillis(7_700L))
        assertEquals(MAX_BURST_DURATION_MILLIS, clampBurstDurationMillis(10_000L))
    }

    @Test
    fun burstOptionsDefaultToAutoExposure() {
        val mode = HighSpeedMode(1280, 720, 120, 120, 120, recordSupported = true)

        assertNull(BurstOptions(mode).preferredExposureTimeNanos)
    }

    @Test
    fun exposureDiagnosticsSummarizeRequestedModeAndActualSamples() {
        val diagnostics = buildBurstDiagnostics(
            timestampsNanos = List(5) { index -> timestampNanos(index * 8.33) },
            callbackCount = 5,
            requestedDurationMillis = 40L,
            fps = 120,
            outputPath = "/private/path/burst.mp4",
            fileBytes = 123L,
            requestedExposureTimeNanos = 1_000_000L,
            actualExposureTimeNanos = listOf(4_000_000L, 2_000_000L, 6_000_000L, 0L),
        )

        assertEquals("MANUAL", diagnostics.requestedExposureMode)
        assertEquals(1_000_000L, diagnostics.requestedExposureTimeNanos)
        assertEquals(3, diagnostics.actualExposureSampleCount)
        assertEquals(2_000_000L, diagnostics.actualExposureMinNanos)
        assertEquals(4_000_000L, diagnostics.actualExposureMedianNanos)
        assertEquals(6_000_000L, diagnostics.actualExposureMaxNanos)
        assertTrue(diagnostics.exposureSummary().contains("actualExposureNs=2000000/4000000/6000000"))
    }

    @Test
    fun externalStopStillResolvesDurationFailsafe() {
        assertEquals(7_700L, resolveBurstDurationFailsafeMillis(BurstStopMode.ExternalStop, 7_700L))
        assertEquals(7_700L, resolveBurstDurationFailsafeMillis(BurstStopMode.FixedDuration, 7_700L))
        assertEquals(MAX_BURST_DURATION_MILLIS, resolveBurstDurationFailsafeMillis(BurstStopMode.ExternalStop, 10_000L))
    }

    private fun diagnosticsWithUniqueCount(count: Int, gapMillis: Double): BurstDiagnostics =
        buildBurstDiagnostics(
            timestampsNanos = List(count) { index -> timestampNanos(index * gapMillis) },
            callbackCount = count,
            requestedDurationMillis = 2_500L,
            fps = 120,
            outputPath = "burst.mp4",
            fileBytes = 1L,
        )

    private fun timestampNanos(offsetMillis: Double): Long =
        1_000_000_000L + (offsetMillis * 1_000_000.0).roundToLong()
}
