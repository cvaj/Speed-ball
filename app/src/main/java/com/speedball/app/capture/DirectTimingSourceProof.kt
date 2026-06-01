package com.speedball.app.capture

import java.security.MessageDigest

/**
 * Direct proof session shape under evaluation.
 *
 * The companion encoder shape may drive the camera session, but its encoded
 * file is never a timing or image source for measurement.
 */
enum class DirectProofSessionShape {
    COMPANION_ENCODER,
    PREVIEW_ONLY_CONTROL,
}

/**
 * Fail-loud reasons for the Phase 9 direct timing-source proof.
 *
 * These reasons are source-proof diagnostics only. A failure must never be
 * converted into speed, trajectory, or measurement output.
 */
enum class DirectTimingSourceFailure {
    UNSUPPORTED_MODE,
    CAPTURE_BUSY,
    CAMERA_OPEN_FAILED,
    SESSION_CONFIGURATION_FAILED,
    COMPANION_RECORDER_SETUP_FAILED,
    SCRATCH_FILE_CLEANUP_FAILED,
    MISSING_DIRECT_TIMESTAMPS,
    INSUFFICIENT_DIRECT_FRAMES,
    DUPLICATE_DIRECT_TIMESTAMPS,
    DIRECT_TIMESTAMPS_NON_MONOTONIC,
    DIRECT_CADENCE_MISMATCH,
    DIRECT_DROPPED_FRAME_GAP,
    DIRECT_TIMESTAMP_NEAR_DUPLICATE,
    MISSING_SENSOR_TIMESTAMPS,
    SENSOR_MEMBERSHIP_UNAVAILABLE,
    NONZERO_OFFSET_OUT_OF_BOUND,
    AMBIGUOUS_WRONG_BY_K_OFFSET,
    PIXEL_READBACK_FAILED,
    BLANK_OR_STALE_PIXEL_PROOF,
    RESOURCE_LIMIT_EXCEEDED,
    LATE_CALLBACK_AFTER_TEARDOWN,
    PROOF_TOKEN_REJECTED,
}

/**
 * Minimum consumed same-update direct frames before Phase 9 may mint token eligibility.
 *
 * Two frames can produce a single plausible gap by accident; requiring a longer
 * bounded run keeps cadence proof from authorizing measurement on a fluke.
 */
const val MIN_DIRECT_PROOF_TOKEN_FRAMES: Int = 12

/** Timing for one bounded direct-capture release step. */
data class DirectReleaseStepTiming(
    val name: String,
    val elapsedMillis: Double,
    val failed: Boolean,
) {
    init {
        require(name.isNotBlank()) { "Release step name must not be blank." }
        require(!name.contains("/") && !name.contains("\\")) { "Release step name must not be path-like." }
        require(elapsedMillis >= 0.0) { "Release elapsed time must be non-negative." }
    }
}

/** Bounded root-cause diagnostics for direct readback capture. */
data class DirectCaptureDiagnostics(
    val frameAvailableCallbackCount: Int = 0,
    val captureResultCallbackCount: Int = 0,
    val appendedDirectFrameCount: Int = 0,
    val readbackCount: Int = 0,
    val medianReadbackMillis: Double? = null,
    val maximumReadbackMillis: Double? = null,
    val releaseStepTimings: List<DirectReleaseStepTiming> = emptyList(),
) {
    init {
        require(frameAvailableCallbackCount >= 0) { "Frame callback count must be non-negative." }
        require(captureResultCallbackCount >= 0) { "Capture callback count must be non-negative." }
        require(appendedDirectFrameCount >= 0) { "Appended direct frame count must be non-negative." }
        require(readbackCount >= 0) { "Readback count must be non-negative." }
        require(medianReadbackMillis == null || medianReadbackMillis >= 0.0) {
            "Median readback time must be non-negative."
        }
        require(maximumReadbackMillis == null || maximumReadbackMillis >= 0.0) {
            "Maximum readback time must be non-negative."
        }
    }
}

/** Opaque identity for a single direct-source proof run. */
@JvmInline
value class DirectProofRunId(val value: String) {
    init {
        require(value.isNotBlank()) { "Run id must not be blank." }
    }
}

/**
 * Content identity for the exact frame sequence proven by a direct source.
 *
 * A run id alone is not sufficient authorization for measurement. The
 * production proof token must bind to this identity and measurement
 * orchestration must recompute it for the candidate sequence.
 */
@JvmInline
value class DirectSequenceContentIdentity(val value: String) {
    init {
        require(value.isNotBlank()) { "Sequence content identity must not be blank." }
    }
}

/**
 * Non-reconstructable proof that bounded pixels were read for one frame.
 *
 * This type intentionally stores aggregate signatures only. It must not carry
 * raw tile pixels, bitmaps, file paths, or user-selected media identifiers.
 */
data class DirectPixelProofSignature(
    val timestampNanos: Long,
    val frameWidth: Int,
    val frameHeight: Int,
    val tileLeft: Int,
    val tileTop: Int,
    val tileWidth: Int,
    val tileHeight: Int,
    val sampleCount: Int,
    val aggregateHash: Long,
    val aggregateChecksum: Long,
    val variationScore: Long,
) {
    init {
        require(timestampNanos > 0L) { "Timestamp must be positive." }
        require(frameWidth > 0 && frameHeight > 0) { "Frame dimensions must be positive." }
        require(tileLeft >= 0 && tileTop >= 0) { "Tile origin must be non-negative." }
        require(tileWidth > 0 && tileHeight > 0) { "Tile dimensions must be positive." }
        require(tileLeft + tileWidth <= frameWidth) { "Tile must fit inside frame width." }
        require(tileTop + tileHeight <= frameHeight) { "Tile must fit inside frame height." }
        require(sampleCount > 0) { "Sample count must be positive." }
        require(variationScore >= 0L) { "Variation score must be non-negative." }
    }
}

/** One consumed direct frame and its same-consumption pixel proof. */
data class DirectFrameProof(
    val frameIndex: Int,
    val timestampNanos: Long,
    val width: Int,
    val height: Int,
    val pixelSignature: DirectPixelProofSignature,
) {
    init {
        require(frameIndex >= 0) { "Frame index must be non-negative." }
        require(timestampNanos > 0L) { "Timestamp must be positive." }
        require(width > 0 && height > 0) { "Frame dimensions must be positive." }
        require(pixelSignature.timestampNanos == timestampNanos) {
            "Pixel proof must be bound to the same timestamp as the frame."
        }
        require(pixelSignature.frameWidth == width && pixelSignature.frameHeight == height) {
            "Pixel proof dimensions must match the frame."
        }
    }
}

/**
 * Eligibility data that a later factory can use to mint a live proof token.
 *
 * This is not itself a `MeasurementTimingProof`; it is the data the vetted
 * factory must re-check before creating one.
 */
class DirectProofTokenEligibility private constructor(
    val runId: DirectProofRunId,
    val sequenceIdentity: DirectSequenceContentIdentity,
    val evidenceLabel: String,
) {
    init {
        require(evidenceLabel.isNotBlank()) { "Evidence label must not be blank." }
    }

    companion object {
        internal fun fromProvenSequence(
            runId: DirectProofRunId,
            sequenceIdentity: DirectSequenceContentIdentity,
            evidenceLabel: String,
        ): DirectProofTokenEligibility =
            DirectProofTokenEligibility(
                runId = runId,
                sequenceIdentity = sequenceIdentity,
                evidenceLabel = evidenceLabel,
            )
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is DirectProofTokenEligibility &&
            runId == other.runId &&
            sequenceIdentity == other.sequenceIdentity &&
            evidenceLabel == other.evidenceLabel

    override fun hashCode(): Int {
        var result = runId.hashCode()
        result = 31 * result + sequenceIdentity.hashCode()
        result = 31 * result + evidenceLabel.hashCode()
        return result
    }

    override fun toString(): String =
        "DirectProofTokenEligibility(runId=$runId, sequenceIdentity=$sequenceIdentity, evidenceLabel=$evidenceLabel)"
}

/** Path-free developer diagnostics for a direct timing-source proof run. */
data class DirectTimingSourceDiagnostics(
    val runId: DirectProofRunId,
    val sessionShape: DirectProofSessionShape,
    val requestedFps: Int,
    val directTimestampCount: Int,
    val sensorTimestampCount: Int,
    val pixelProofCount: Int,
    val sequenceIdentity: DirectSequenceContentIdentity?,
    val tokenEligibility: DirectProofTokenEligibility?,
    val directMedianGapMillis: Double? = null,
    val directMaximumGapMillis: Double? = null,
    val sensorMedianGapMillis: Double? = null,
    val sensorMaximumGapMillis: Double? = null,
    val finalFailure: DirectTimingSourceFailure? = null,
    val captureDiagnostics: DirectCaptureDiagnostics = DirectCaptureDiagnostics(),
) {
    init {
        require(requestedFps > 0) { "Requested fps must be positive." }
        require(directTimestampCount >= 0) { "Direct timestamp count must be non-negative." }
        require(sensorTimestampCount >= 0) { "Sensor timestamp count must be non-negative." }
        require(pixelProofCount >= 0) { "Pixel proof count must be non-negative." }
    }
}

/** Terminal outcome from Phase 9 direct timing-source proof. */
sealed interface DirectTimingSourceProofOutcome {
    data class Success(
        val frames: List<DirectFrameProof>,
        val diagnostics: DirectTimingSourceDiagnostics,
        val tokenEligibility: DirectProofTokenEligibility,
    ) : DirectTimingSourceProofOutcome {
        init {
            require(frames.size >= MIN_DIRECT_PROOF_TOKEN_FRAMES) {
                "Successful proof requires at least $MIN_DIRECT_PROOF_TOKEN_FRAMES consumed direct frames."
            }
            require(diagnostics.sequenceIdentity == tokenEligibility.sequenceIdentity) {
                "Diagnostics and token eligibility must reference the same sequence."
            }
            require(diagnostics.tokenEligibility == tokenEligibility) {
                "Diagnostics must carry the token eligibility data."
            }
        }
    }

    data class Failure(
        val reason: DirectTimingSourceFailure,
        val message: String,
        val diagnostics: DirectTimingSourceDiagnostics? = null,
    ) : DirectTimingSourceProofOutcome {
        init {
            require(message.isNotBlank()) { "Failure message must not be blank." }
        }
    }

    data object Cancelled : DirectTimingSourceProofOutcome
}

/**
 * Builds the content identity for the exact sequence proven by the source.
 *
 * The identity includes frame order, dimensions, timestamps, and aggregate pixel
 * signatures. If detections are later proven as part of the same source proof,
 * their content hash can be added as another binding without weakening the
 * frame-sequence binding.
 */
fun buildDirectSequenceContentIdentity(
    frames: List<DirectFrameProof>,
    detectionContentHash: String? = null,
): DirectSequenceContentIdentity {
    require(frames.isNotEmpty()) { "Sequence identity requires at least one frame." }
    require(detectionContentHash == null || detectionContentHash.isNotBlank()) {
        "Detection content hash must be null or non-blank."
    }
    val digest = MessageDigest.getInstance("SHA-256")
    digest.updateAscii("speed-ball-direct-sequence-v1\n")
    frames.forEach { frame ->
        digest.updateAscii(
            listOf(
                frame.frameIndex,
                frame.timestampNanos,
                frame.width,
                frame.height,
                frame.pixelSignature.tileLeft,
                frame.pixelSignature.tileTop,
                frame.pixelSignature.tileWidth,
                frame.pixelSignature.tileHeight,
                frame.pixelSignature.sampleCount,
                frame.pixelSignature.aggregateHash,
                frame.pixelSignature.aggregateChecksum,
                frame.pixelSignature.variationScore,
            ).joinToString(separator = "|", postfix = "\n"),
        )
    }
    if (detectionContentHash != null) {
        digest.updateAscii("detections|$detectionContentHash\n")
    }
    return DirectSequenceContentIdentity(digest.digest().toHex())
}

private fun MessageDigest.updateAscii(value: String) {
    update(value.toByteArray(Charsets.US_ASCII))
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }
