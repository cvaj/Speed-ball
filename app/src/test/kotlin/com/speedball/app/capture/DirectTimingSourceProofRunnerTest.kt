package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class DirectTimingSourceProofRunnerTest {
    @Test
    fun runnerAttemptsCompanionBeforePreviewControl() {
        val calls = mutableListOf<String>()
        val runner = DirectTimingSourceProofRunner(
            companionProbe = {
                calls += "companion"
                companionSuccess()
            },
            previewControl = {
                calls += "preview"
                previewFailure()
            },
        )

        val result = runner.run(
            runId = DirectProofRunId("run-a"),
            mode = mode(),
            pixelConfig = DirectPixelProofConfig(maxTotalSamples = 64, requireInterFrameVariation = false),
        )

        assertEquals(listOf("companion", "preview"), calls)
        assertTrue(result.previewControl.attempted)
        assertInstanceOf(DirectTimingSourceProofOutcome.Success::class.java, result.proofOutcome)
    }

    @Test
    fun companionTimingAndPixelGatesDetermineProofOutcome() {
        val badCadence = timestamps(count = MIN_DIRECT_PROOF_TOKEN_FRAMES, gapNanos = 33_333_333L)
        val runner = DirectTimingSourceProofRunner(
            companionProbe = { companionSuccess(timestamps = badCadence) },
            previewControl = { previewSuccess() },
        )

        val result = runner.run(
            runId = DirectProofRunId("run-b"),
            mode = mode(),
            pixelConfig = DirectPixelProofConfig(maxTotalSamples = 64, requireInterFrameVariation = false),
        )
        val failure = assertInstanceOf(DirectTimingSourceProofOutcome.Failure::class.java, result.proofOutcome)

        assertEquals(DirectTimingSourceFailure.DIRECT_CADENCE_MISMATCH, failure.reason)
        assertEquals(null, failure.diagnostics?.tokenEligibility)
        assertEquals(DirectTimingSourceFailure.DIRECT_CADENCE_MISMATCH, failure.diagnostics?.finalFailure)
        assertTrue(result.previewControl.attempted)
    }

    @Test
    fun insufficientDirectFramesFailBeforeTokenEligibility() {
        val shortRun = timestamps(count = MIN_DIRECT_PROOF_TOKEN_FRAMES - 1)
        val outcome = buildProofOutcomeFromCompanion(
            runId = DirectProofRunId("run-insufficient"),
            mode = mode(),
            pixelConfig = DirectPixelProofConfig(maxTotalSamples = 48, requireInterFrameVariation = false),
            companionOutcome = companionSuccess(timestamps = shortRun),
        )
        val failure = assertInstanceOf(DirectTimingSourceProofOutcome.Failure::class.java, outcome)

        assertEquals(DirectTimingSourceFailure.INSUFFICIENT_DIRECT_FRAMES, failure.reason)
        assertEquals(null, failure.diagnostics?.tokenEligibility)
    }

    @Test
    fun previewControlCannotMintTokenWhenCompanionFails() {
        val runner = DirectTimingSourceProofRunner(
            companionProbe = {
                DirectSessionProbeOutcome.Failure(
                    shape = DirectProofSessionShape.COMPANION_ENCODER,
                    reason = DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED,
                    message = "Companion session failed.",
                )
            },
            previewControl = { previewSuccess() },
        )

        val result = runner.run(
            runId = DirectProofRunId("run-c"),
            mode = mode(),
            pixelConfig = DirectPixelProofConfig(maxTotalSamples = 64, requireInterFrameVariation = false),
        )

        assertTrue(result.previewControl.attempted)
        assertInstanceOf(PreviewFrameOutcome.Success::class.java, result.previewControl.outcome)
        val failure = assertInstanceOf(DirectTimingSourceProofOutcome.Failure::class.java, result.proofOutcome)
        assertEquals(DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED, failure.reason)
    }

    @Test
    fun hardCameraFailureAndScratchCleanupFailurePreventPreviewControl() {
        assertFalse(
            shouldRunPreviewControlAfterCompanion(
                DirectSessionProbeOutcome.Failure(
                    shape = DirectProofSessionShape.COMPANION_ENCODER,
                    reason = DirectTimingSourceFailure.CAMERA_OPEN_FAILED,
                    message = "Camera unavailable.",
                ),
            ),
        )
        assertFalse(
            shouldRunPreviewControlAfterCompanion(
                DirectSessionProbeOutcome.Failure(
                    shape = DirectProofSessionShape.COMPANION_ENCODER,
                    reason = DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED,
                    message = "Session failed.",
                    scratchCleanupStatus = CompanionScratchCleanupStatus.FAILED,
                ),
            ),
        )
        assertFalse(
            shouldRunPreviewControlAfterCompanion(
                companionSuccess(scratchCleanupStatus = CompanionScratchCleanupStatus.FAILED),
            ),
        )
    }

    @Test
    fun scratchCleanupFailureReturnsTypedProofFailureBeforeTokenEligibility() {
        val outcome = buildProofOutcomeFromCompanion(
            runId = DirectProofRunId("run-d"),
            mode = mode(),
            pixelConfig = DirectPixelProofConfig(maxTotalSamples = 64, requireInterFrameVariation = false),
            companionOutcome = companionSuccess(scratchCleanupStatus = CompanionScratchCleanupStatus.FAILED),
        )
        val failure = assertInstanceOf(DirectTimingSourceProofOutcome.Failure::class.java, outcome)

        assertEquals(DirectTimingSourceFailure.SCRATCH_FILE_CLEANUP_FAILED, failure.reason)
        assertEquals(null, failure.diagnostics?.tokenEligibility)
    }

    @Test
    fun directProofSuccessBindsTokenToExactSequenceIdentity() {
        val outcome = buildProofOutcomeFromCompanion(
            runId = DirectProofRunId("run-e"),
            mode = mode(),
            pixelConfig = DirectPixelProofConfig(maxTotalSamples = 64, requireInterFrameVariation = false),
            companionOutcome = companionSuccess(),
        )
        val success = assertInstanceOf(DirectTimingSourceProofOutcome.Success::class.java, outcome)

        assertEquals(buildDirectSequenceContentIdentity(success.frames), success.tokenEligibility.sequenceIdentity)
        assertEquals(success.tokenEligibility, success.diagnostics.tokenEligibility)
        assertEquals(DirectProofSessionShape.COMPANION_ENCODER, success.diagnostics.sessionShape)
    }

    @Test
    fun runnerLogsBoundedLinesWithoutPathsOrPixelsOrDecoderTerms() {
        val logs = mutableListOf<String>()
        val runner = DirectTimingSourceProofRunner(
            companionProbe = {
                companionSuccess(
                    captureDiagnostics = DirectCaptureDiagnostics(
                        frameAvailableCallbackCount = 24,
                        captureResultCallbackCount = 96,
                        appendedDirectFrameCount = MIN_DIRECT_PROOF_TOKEN_FRAMES,
                        readbackCount = MIN_DIRECT_PROOF_TOKEN_FRAMES,
                        medianReadbackMillis = 1.25,
                        maximumReadbackMillis = 2.5,
                        releaseStepTimings = listOf(DirectReleaseStepTiming("releaseGl", 0.5, failed = false)),
                    ),
                )
            },
            previewControl = { previewFailure() },
            logger = { logs += it },
        )

        runner.run(
            runId = DirectProofRunId("run-f"),
            mode = mode(),
            pixelConfig = DirectPixelProofConfig(maxTotalSamples = 64, requireInterFrameVariation = false),
        )

        assertTrue(logs.any { it.startsWith("DIRECT_PROOF_COMPANION") })
        assertTrue(logs.any { it.startsWith("DIRECT_PROOF_PREVIEW") })
        assertTrue(logs.any { it.contains("frameCallbacks=24") })
        assertTrue(logs.any { it.contains("readbackMedianMs=1.250") })
        assertTrue(logs.any { it.contains("directMedianMs=8.333") })
        assertTrue(logs.any { it.contains("sensorMedianMs=8.333") })
        assertTrue(logs.any { it.contains("finalGate=none") })
        logs.forEach { line ->
            assertFalse(line.contains("/"))
            assertFalse(line.contains("mp4", ignoreCase = true))
            assertFalse(line.contains("pixel", ignoreCase = true) && line.contains("["))
            assertFalse(line.contains("MediaExtractor"))
        }
    }

    @Test
    fun sourceDoesNotReadCompanionEncodedFile() {
        val source = Files.readAllBytes(sourcePath("DirectTimingSourceProofRunner.kt")).toString(Charsets.UTF_8)

        assertFalse(source.contains("File("))
        assertFalse(source.contains("MediaExtractor"))
        assertFalse(source.contains("MediaMetadataRetriever"))
        assertFalse(source.contains("setDataSource"))
    }

    private fun companionSuccess(
        timestamps: List<Long> = timestamps(count = MIN_DIRECT_PROOF_TOKEN_FRAMES),
        scratchCleanupStatus: CompanionScratchCleanupStatus = CompanionScratchCleanupStatus.DELETED,
        captureDiagnostics: DirectCaptureDiagnostics = DirectCaptureDiagnostics(),
    ): DirectSessionProbeOutcome.Success =
        DirectSessionProbeOutcome.Success(
            shape = DirectProofSessionShape.COMPANION_ENCODER,
            requestListSize = 4,
            sensorTimestampsNanos = timestamps,
            frames = timestamps.mapIndexed { index, timestamp ->
                DirectFrameProof(
                    frameIndex = index,
                    timestampNanos = timestamp,
                    width = 2,
                    height = 2,
                    pixelSignature = signature(timestamp, seed = index),
                )
            },
            scratchCleanupStatus = scratchCleanupStatus,
            captureDiagnostics = captureDiagnostics,
        )

    private fun timestamps(count: Int, gapNanos: Long = 8_333_333L): List<Long> =
        List(count) { index -> 1_000_000_000L + index * gapNanos }

    private fun previewSuccess(): PreviewFrameOutcome.Success =
        PreviewFrameOutcome.Success(
            pairs = listOf(
                PreviewFrameTimestampPair(
                    frameIndex = 0,
                    previewTimestampNanos = 1_000L,
                    sensorTimestampNanos = 1_000L,
                    relativeTimestampSeconds = 0.0,
                ),
            ),
            diagnostics = buildPreviewFrameDiagnostics(
                rawPreviewTimestampsNanos = listOf(1_000L),
                rawSensorTimestampsNanos = listOf(1_000L),
                fps = 120,
                exactMatchCount = 1,
                sensorMembershipCount = 1,
                unmatchedLeadingPreviewCount = 0,
                unmatchedTrailingPreviewCount = 0,
                coalescingEvidence = false,
                offsetNanos = listOf(0L),
                verdict = PreviewFramePairingVerdict.EXACT_VALUE_MEMBERSHIP,
            ),
        )

    private fun previewFailure(): PreviewFrameOutcome.Failure =
        PreviewFrameOutcome.Failure(PreviewFrameFailure.PREVIEW_CADENCE_MISMATCH, "Preview control remains diagnostic.")

    private fun signature(timestamp: Long, seed: Int): DirectPixelProofSignature =
        buildDirectPixelProofSignature(
            timestampNanos = timestamp,
            frameWidth = 2,
            frameHeight = 2,
            tileLeft = 0,
            tileTop = 0,
            tileWidth = 2,
            tileHeight = 2,
            argbPixels = intArrayOf(
                0xff101010.toInt() + seed,
                0xff202020.toInt() + seed,
                0xff303030.toInt() + seed,
                0xff404040.toInt() + seed,
            ),
        )

    private fun mode(): HighSpeedMode =
        HighSpeedMode(
            width = 1280,
            height = 720,
            fps = 120,
            aeTargetFpsLower = 120,
            aeTargetFpsUpper = 120,
            recordSupported = true,
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
