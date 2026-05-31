package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreviewTimestampSpikeCaptureTest {
    @Test
    fun overlappingPreviewSpikeFailsAsCaptureBusy() {
        val mode = mode()
        val failure = validatePreviewSpikeStart(mode, listOf(mode), BurstRecorderState.Recording)

        assertEquals(PreviewFrameFailure.CAPTURE_BUSY, failure!!.reason)
    }

    @Test
    fun unknownModeFailsBeforeCameraOpen() {
        val failure = validatePreviewSpikeStart(mode(), emptyList(), BurstRecorderState.Idle)

        assertEquals(PreviewFrameFailure.UNSUPPORTED_MODE, failure!!.reason)
    }

    @Test
    fun blendedRangeWithoutFixedFpsFailsBeforeCameraOpen() {
        val blended = mode(fps = 120, lower = 30, upper = 120)
        val failure = validatePreviewSpikeStart(blended, listOf(blended), BurstRecorderState.Idle)

        assertEquals(PreviewFrameFailure.UNSUPPORTED_MODE, failure!!.reason)
    }

    @Test
    fun fixedPreviewModeCanStartValidation() {
        val mode = mode()

        assertNull(validatePreviewSpikeStart(mode, listOf(mode), BurstRecorderState.Idle))
    }

    @Test
    fun lateFrameGuardRejectsIdleAndReleasingStates() {
        assertFalse(shouldHandlePreviewFrame(BurstRecorderState.Idle))
        assertFalse(shouldHandlePreviewFrame(BurstRecorderState.Releasing))
        assertTrue(shouldHandlePreviewFrame(BurstRecorderState.Opening))
        assertTrue(shouldHandlePreviewFrame(BurstRecorderState.Configuring))
        assertTrue(shouldHandlePreviewFrame(BurstRecorderState.Recording))
    }

    @Test
    fun releaseFailureMapsCancelledOutcomeToResourceFailure() {
        val failure = assertInstanceOf(
            PreviewFrameOutcome.Failure::class.java,
            resolvePreviewReleaseOutcome(PreviewFrameOutcome.Cancelled, releaseFailed = true),
        )

        assertEquals(PreviewFrameFailure.RESOURCE_RELEASE_FAILED, failure.reason)
    }

    @Test
    fun releaseFailurePreservesPrimaryFailureReason() {
        val primary = PreviewFrameOutcome.Failure(PreviewFrameFailure.CAMERA_DEVICE_ERROR, "Camera failed.")

        assertEquals(primary, resolvePreviewReleaseOutcome(primary, releaseFailed = true))
    }

    @Test
    fun previewReleaseResourcesRunsEveryStepAndReportsFailure() {
        val calls = mutableListOf<String>()
        val releaseFailed = releasePreviewResources(
            PreviewReleaseActions(
                stopRepeating = { calls += "stop" },
                closeSession = {
                    calls += "session"
                    error("session close failed")
                },
                closeCamera = { calls += "camera" },
                releaseGl = { calls += "gl" },
                quitThread = { calls += "thread" },
            ),
        )

        assertTrue(releaseFailed)
        assertEquals(listOf("stop", "session", "camera", "gl", "thread"), calls)
    }

    @Test
    fun terminalGatePreventsDoubleCompletion() {
        val gate = TerminalCompletionGate()

        assertTrue(gate.claim())
        assertFalse(gate.claim())
    }

    private fun mode(fps: Int = 120, lower: Int = fps, upper: Int = fps): HighSpeedMode =
        HighSpeedMode(
            width = 1280,
            height = 720,
            fps = fps,
            aeTargetFpsLower = lower,
            aeTargetFpsUpper = upper,
            recordSupported = lower == fps && upper == fps,
        )
}
