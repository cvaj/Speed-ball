package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirectTimingSourceProofTest {
    @Test
    fun failureTaxonomyIsPinned() {
        assertEquals(
            listOf(
                DirectTimingSourceFailure.UNSUPPORTED_MODE,
                DirectTimingSourceFailure.CAPTURE_BUSY,
                DirectTimingSourceFailure.CAMERA_OPEN_FAILED,
                DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED,
                DirectTimingSourceFailure.COMPANION_RECORDER_SETUP_FAILED,
                DirectTimingSourceFailure.SCRATCH_FILE_CLEANUP_FAILED,
                DirectTimingSourceFailure.MISSING_DIRECT_TIMESTAMPS,
                DirectTimingSourceFailure.INSUFFICIENT_DIRECT_FRAMES,
                DirectTimingSourceFailure.DUPLICATE_DIRECT_TIMESTAMPS,
                DirectTimingSourceFailure.DIRECT_TIMESTAMPS_NON_MONOTONIC,
                DirectTimingSourceFailure.DIRECT_CADENCE_MISMATCH,
                DirectTimingSourceFailure.DIRECT_DROPPED_FRAME_GAP,
                DirectTimingSourceFailure.DIRECT_TIMESTAMP_NEAR_DUPLICATE,
                DirectTimingSourceFailure.MISSING_SENSOR_TIMESTAMPS,
                DirectTimingSourceFailure.SENSOR_MEMBERSHIP_UNAVAILABLE,
                DirectTimingSourceFailure.NONZERO_OFFSET_OUT_OF_BOUND,
                DirectTimingSourceFailure.AMBIGUOUS_WRONG_BY_K_OFFSET,
                DirectTimingSourceFailure.PIXEL_READBACK_FAILED,
                DirectTimingSourceFailure.BLANK_OR_STALE_PIXEL_PROOF,
                DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED,
                DirectTimingSourceFailure.LATE_CALLBACK_AFTER_TEARDOWN,
                DirectTimingSourceFailure.PROOF_TOKEN_REJECTED,
            ),
            DirectTimingSourceFailure.entries,
        )
    }

    @Test
    fun sequenceIdentityIncludesFrameOrderTimestampDimensionsAndPixelSignature() {
        val runId = DirectProofRunId("run-1")
        val first = frame(index = 0, timestamp = 1_000L, hash = 10L)
        val second = frame(index = 1, timestamp = 2_000L, hash = 20L)

        val identity = buildDirectSequenceContentIdentity(listOf(first, second))
        assertEquals(identity, buildDirectSequenceContentIdentity(listOf(first, second)))
        assertNotEquals(identity, buildDirectSequenceContentIdentity(listOf(second.copy(frameIndex = 0), first.copy(frameIndex = 1))))
        assertNotEquals(identity, buildDirectSequenceContentIdentity(listOf(frame(index = 0, timestamp = 1_000L, hash = 10L, width = 1281), second)))
        assertNotEquals(identity, buildDirectSequenceContentIdentity(listOf(frame(index = 0, timestamp = 1_001L, hash = 10L), second)))
        assertNotEquals(identity, buildDirectSequenceContentIdentity(listOf(frame(index = 0, timestamp = 1_000L, hash = 99L), second)))

        val sameRunDifferentSequence = buildDirectSequenceContentIdentity(listOf(first, frame(index = 1, timestamp = 2_001L, hash = 20L)))
        assertNotEquals(identity, sameRunDifferentSequence, "Same run $runId must not authorize a different sequence.")
    }

    @Test
    fun detectionContentHashAddsOnlyTighteningBinding() {
        val frames = listOf(frame(index = 0, timestamp = 1_000L, hash = 10L))
        val frameOnly = buildDirectSequenceContentIdentity(frames)
        val withDetections = buildDirectSequenceContentIdentity(frames, detectionContentHash = "abc123")

        assertNotEquals(frameOnly, withDetections)
        assertEquals(withDetections, buildDirectSequenceContentIdentity(frames, detectionContentHash = "abc123"))
        assertNotEquals(withDetections, buildDirectSequenceContentIdentity(frames, detectionContentHash = "def456"))
        assertThrows(IllegalArgumentException::class.java) {
            buildDirectSequenceContentIdentity(frames, detectionContentHash = "")
        }
    }

    @Test
    fun successRequiresEligibilityForSameSequence() {
        val runId = DirectProofRunId("run-1")
        val frames = frames(count = MIN_DIRECT_PROOF_TOKEN_FRAMES)
        val identity = buildDirectSequenceContentIdentity(frames)
        val eligibility = DirectProofTokenEligibility.fromProvenSequence(
            runId = runId,
            sequenceIdentity = identity,
            evidenceLabel = "direct:companion:120",
        )
        val diagnostics = diagnostics(runId, identity, eligibility, frameCount = frames.size)

        val success = DirectTimingSourceProofOutcome.Success(
            frames = frames,
            diagnostics = diagnostics,
            tokenEligibility = eligibility,
        )

        assertEquals(identity, success.tokenEligibility.sequenceIdentity)
        assertThrows(IllegalArgumentException::class.java) {
            DirectTimingSourceProofOutcome.Success(
                frames = frames,
                diagnostics = diagnostics(runId, DirectSequenceContentIdentity("other"), eligibility, frameCount = frames.size),
                tokenEligibility = eligibility,
            )
        }
    }

    @Test
    fun successRequiresMinimumDirectFrameCount() {
        val runId = DirectProofRunId("run-min")
        val frames = frames(count = MIN_DIRECT_PROOF_TOKEN_FRAMES - 1)
        val identity = buildDirectSequenceContentIdentity(frames)
        val eligibility = DirectProofTokenEligibility.fromProvenSequence(
            runId = runId,
            sequenceIdentity = identity,
            evidenceLabel = "direct:companion:120",
        )

        assertThrows(IllegalArgumentException::class.java) {
            DirectTimingSourceProofOutcome.Success(
                frames = frames,
                diagnostics = diagnostics(runId, identity, eligibility, frameCount = frames.size),
                tokenEligibility = eligibility,
            )
        }
    }

    @Test
    fun diagnosticsAndFailuresDoNotCarryPrivatePayloads() {
        val failure = DirectTimingSourceProofOutcome.Failure(
            reason = DirectTimingSourceFailure.SCRATCH_FILE_CLEANUP_FAILED,
            message = "Scratch cleanup failed.",
            diagnostics = diagnostics(
                runId = DirectProofRunId("run-1"),
                identity = null,
                eligibility = null,
            ),
        )
        val values = failure::class.java.declaredFields.map { it.name } +
            failure.message +
            failure.reason.name +
            failure.diagnostics!!::class.java.declaredFields.map { it.name }

        assertNoForbiddenPayloadNames(values)
        assertFalse(values.joinToString(" ").contains("/storage/"))
        assertFalse(values.joinToString(" ").contains("content://"))
    }

    @Test
    fun pixelSignatureRejectsRawGeometryAndTimestampMismatchProblems() {
        assertThrows(IllegalArgumentException::class.java) {
            pixelSignature(timestamp = 1_000L, tileWidth = 0)
        }
        assertThrows(IllegalArgumentException::class.java) {
            frame(index = 0, timestamp = 1_000L, hash = 10L)
                .copy(pixelSignature = pixelSignature(timestamp = 2_000L))
        }
    }

    private fun frame(
        index: Int,
        timestamp: Long,
        hash: Long,
        width: Int = 1280,
        height: Int = 720,
    ): DirectFrameProof =
        DirectFrameProof(
            frameIndex = index,
            timestampNanos = timestamp,
            width = width,
            height = height,
            pixelSignature = pixelSignature(timestamp = timestamp, hash = hash, frameWidth = width, frameHeight = height),
        )

    private fun frames(count: Int): List<DirectFrameProof> =
        List(count) { index ->
            frame(
                index = index,
                timestamp = 1_000_000_000L + index * 8_333_333L,
                hash = 10L + index,
            )
        }

    private fun pixelSignature(
        timestamp: Long,
        hash: Long = 10L,
        frameWidth: Int = 1280,
        frameHeight: Int = 720,
        tileWidth: Int = 8,
    ): DirectPixelProofSignature =
        DirectPixelProofSignature(
            timestampNanos = timestamp,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            tileLeft = 4,
            tileTop = 6,
            tileWidth = tileWidth,
            tileHeight = 8,
            sampleCount = 64,
            aggregateHash = hash,
            aggregateChecksum = 100L + hash,
            variationScore = 3L,
        )

    private fun diagnostics(
        runId: DirectProofRunId,
        identity: DirectSequenceContentIdentity?,
        eligibility: DirectProofTokenEligibility?,
        frameCount: Int = 1,
    ): DirectTimingSourceDiagnostics =
        DirectTimingSourceDiagnostics(
            runId = runId,
            sessionShape = DirectProofSessionShape.COMPANION_ENCODER,
            requestedFps = 120,
            directTimestampCount = frameCount,
            sensorTimestampCount = frameCount,
            pixelProofCount = frameCount,
            sequenceIdentity = identity,
            tokenEligibility = eligibility,
        )

    private fun assertNoForbiddenPayloadNames(values: List<String>) {
        val tokens = values
            .flatMap { value -> value.lowercase().split(Regex("[^a-z0-9]+")) }
            .filter { it.isNotBlank() }
        listOf("mph", "angle", "trajectory", "bitmap", "media", "uri", "path").forEach { forbidden ->
            assertFalse(tokens.contains(forbidden), "Direct proof payload leaked '$forbidden': $tokens")
        }
        assertTrue(tokens.contains("diagnostics"))
    }
}
