package com.speedball.app.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class DecodeCompletionGateTest {
    @Test
    fun cancellationClaimsTerminalDeliveryAndRejectsLateSuccess() {
        val gate = DecodeCompletionGate()
        val run = gate.startRun()
        val cancelled = gate.cancel()

        assertEquals(DecodeOutcome.Cancelled, cancelled)
        assertNull(gate.accept(run, success()))
    }

    @Test
    fun currentRunCanDeliverTypedFailure() {
        val gate = DecodeCompletionGate()
        val run = gate.startRun()
        val failure = DecodeOutcome.Failure(DecodeFailure.DECODE_WORK_LIMIT_EXCEEDED, "Work limit exceeded.")

        assertEquals(failure, gate.accept(run, failure))
    }

    @Test
    fun currentRunCanBeAcceptedOnlyOnce() {
        val gate = DecodeCompletionGate()
        val run = gate.startRun()

        assertEquals(success(), gate.accept(run, success()))
        assertNull(gate.accept(run, DecodeOutcome.Failure(DecodeFailure.FRAME_SENSOR_COUNT_MISMATCH, "Late duplicate.")))
    }

    private fun success(): DecodeOutcome.Success =
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
                ptsToSensorOffsetSummary = null,
                presentationClockAssessment = PresentationClockAssessment.UNKNOWN,
            ),
        )
}
