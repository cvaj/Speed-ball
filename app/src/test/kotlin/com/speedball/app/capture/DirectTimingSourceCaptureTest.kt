package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class DirectTimingSourceCaptureTest {
    @Test
    fun directProofStartValidationRejectsBusyAndUnsupportedModes() {
        val mode = mode()

        assertEquals(
            DirectTimingSourceFailure.CAPTURE_BUSY,
            validateDirectProofStart(mode, listOf(mode), BurstRecorderState.Recording)!!.reason,
        )
        assertEquals(
            DirectTimingSourceFailure.UNSUPPORTED_MODE,
            validateDirectProofStart(mode, emptyList(), BurstRecorderState.Idle)!!.reason,
        )
        assertEquals(
            DirectTimingSourceFailure.UNSUPPORTED_MODE,
            validateDirectProofStart(mode(fps = 120, lower = 30, upper = 120), listOf(mode(fps = 120, lower = 30, upper = 120)), BurstRecorderState.Idle)!!.reason,
        )
        assertEquals(null, validateDirectProofStart(mode, listOf(mode), BurstRecorderState.Idle))
    }

    @Test
    fun directFrameGuardRejectsIdleAndReleasingStates() {
        assertFalse(shouldHandleDirectFrame(BurstRecorderState.Idle))
        assertFalse(shouldHandleDirectFrame(BurstRecorderState.Releasing))
        assertTrue(shouldHandleDirectFrame(BurstRecorderState.Opening))
        assertTrue(shouldHandleDirectFrame(BurstRecorderState.Configuring))
        assertTrue(shouldHandleDirectFrame(BurstRecorderState.Recording))
    }

    @Test
    fun collectorAppendsOneAtomicRecordPerCallback() {
        val collector = DirectFrameProofCollector(
            maxFrames = 2,
            pixelConfig = DirectPixelProofConfig(maxTotalSamples = 8),
        )

        assertEquals(
            null,
            collector.appendAtomicFrame(
                state = BurstRecorderState.Recording,
                frameIndex = 0,
                timestampNanos = 1_000L,
                width = 2,
                height = 2,
                signature = signature(timestamp = 1_000L),
            ),
        )

        assertEquals(1, collector.snapshot().size)
        assertEquals(1_000L, collector.snapshot().single().frame.timestampNanos)
    }

    @Test
    fun collectorRejectsLateCallbackAndResourceCaps() {
        val collector = DirectFrameProofCollector(
            maxFrames = 1,
            pixelConfig = DirectPixelProofConfig(maxTotalSamples = 4),
        )

        assertEquals(
            DirectTimingSourceFailure.LATE_CALLBACK_AFTER_TEARDOWN,
            collector.appendAtomicFrame(
                state = BurstRecorderState.Releasing,
                frameIndex = 0,
                timestampNanos = 1_000L,
                width = 2,
                height = 2,
                signature = signature(timestamp = 1_000L),
            ),
        )
        assertEquals(
            null,
            collector.appendAtomicFrame(
                state = BurstRecorderState.Recording,
                frameIndex = 0,
                timestampNanos = 1_000L,
                width = 2,
                height = 2,
                signature = signature(timestamp = 1_000L),
            ),
        )
        assertEquals(
            DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED,
            collector.appendAtomicFrame(
                state = BurstRecorderState.Recording,
                frameIndex = 1,
                timestampNanos = 2_000L,
                width = 2,
                height = 2,
                signature = signature(timestamp = 2_000L),
            ),
        )
        collector.markReleased()
        assertEquals(
            DirectTimingSourceFailure.LATE_CALLBACK_AFTER_TEARDOWN,
            collector.appendAtomicFrame(
                state = BurstRecorderState.Recording,
                frameIndex = 1,
                timestampNanos = 2_000L,
                width = 2,
                height = 2,
                signature = signature(timestamp = 2_000L),
            ),
        )
    }

    @Test
    fun collectorRejectsSampleLimitBeforeAppending() {
        val collector = DirectFrameProofCollector(
            maxFrames = 2,
            pixelConfig = DirectPixelProofConfig(maxTotalSamples = 3),
        )

        assertEquals(
            DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED,
            collector.appendAtomicFrame(
                state = BurstRecorderState.Recording,
                frameIndex = 0,
                timestampNanos = 1_000L,
                width = 2,
                height = 2,
                signature = signature(timestamp = 1_000L),
            ),
        )
        assertTrue(collector.snapshot().isEmpty())
    }

    @Test
    fun directReleaseResourcesRunsEveryStepAndPrioritizesScratchFailure() {
        val calls = mutableListOf<String>()
        val failure = releaseDirectCaptureResources(
            DirectReleaseActions(
                stopRepeating = { calls += "stop" },
                closeSession = { calls += "session" },
                closeCamera = { calls += "camera" },
                releaseGl = {
                    calls += "gl"
                    error("gl release failed")
                },
                releaseCompanion = {
                    calls += "companion"
                    CompanionScratchCleanupResult(CompanionScratchCleanupStatus.FAILED)
                },
                quitThread = { calls += "thread" },
            ),
        )

        assertEquals(DirectTimingSourceFailure.SCRATCH_FILE_CLEANUP_FAILED, failure)
        assertEquals(listOf("stop", "session", "camera", "gl", "companion", "thread"), calls)
    }

    @Test
    fun directReleaseResourcesReportsResourceFailureWithoutScratchFailure() {
        val failure = releaseDirectCaptureResources(
            DirectReleaseActions(
                closeSession = { error("close failed") },
                releaseCompanion = { CompanionScratchCleanupResult(CompanionScratchCleanupStatus.DELETED) },
            ),
        )

        assertEquals(DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED, failure)
    }

    @Test
    fun directReleaseResourcesReturnsNullWhenAllReleaseStepsSucceed() {
        val calls = mutableListOf<String>()
        val failure = releaseDirectCaptureResources(
            DirectReleaseActions(
                stopRepeating = { calls += "stop" },
                closeSession = { calls += "session" },
                closeCamera = { calls += "camera" },
                releaseGl = { calls += "gl" },
                releaseCompanion = {
                    calls += "companion"
                    CompanionScratchCleanupResult(CompanionScratchCleanupStatus.ALREADY_ABSENT)
                },
                quitThread = { calls += "thread" },
            ),
        )

        assertEquals(null, failure)
        assertEquals(listOf("stop", "session", "camera", "gl", "companion", "thread"), calls)
    }

    @Test
    fun sourceKeepsDirectReadbackOnGlPathAndAwayFromDecoder() {
        val source = Files.readAllBytes(sourcePath("DirectTimingSourceCapture.kt")).toString(Charsets.UTF_8)

        assertTrue(source.contains("surfaceTexture.updateTexImage()"))
        assertTrue(source.contains("surfaceTexture.timestamp"))
        assertTrue(source.contains("GLES20.glReadPixels"))
        assertTrue(source.contains("buildDirectPixelProofSignature"))
        assertTrue(source.contains("DirectAtomicFrameProof(frame)"))
        assertTrue(source.contains("listOf(companionSurface, directSurface)"))
        assertTrue(source.contains("prepareCompanionEncoderScratch"))
        assertFalse(source.contains("MediaExtractor"))
        assertFalse(source.contains("MediaMetadataRetriever"))
    }

    private fun signature(timestamp: Long): DirectPixelProofSignature =
        buildDirectPixelProofSignature(
            timestampNanos = timestamp,
            frameWidth = 2,
            frameHeight = 2,
            tileLeft = 0,
            tileTop = 0,
            tileWidth = 2,
            tileHeight = 2,
            argbPixels = intArrayOf(0xff101010.toInt(), 0xff202020.toInt(), 0xff303030.toInt(), 0xff404040.toInt()),
        )

    private fun mode(fps: Int = 120, lower: Int = fps, upper: Int = fps): HighSpeedMode =
        HighSpeedMode(
            width = 1280,
            height = 720,
            fps = fps,
            aeTargetFpsLower = lower,
            aeTargetFpsUpper = upper,
            recordSupported = lower == fps && upper == fps,
        )

    private fun sourcePath(fileName: String): Path {
        val appPath = Path.of("app/src/main/java/com/speedball/app/capture/$fileName")
        return if (appPath.exists()) {
            appPath
        } else {
            Path.of("src/main/java/com/speedball/app/capture/$fileName")
        }
    }
}
