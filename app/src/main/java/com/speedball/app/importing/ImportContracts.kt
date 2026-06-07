package com.speedball.app.importing

import com.speedball.app.measurement.VisualEstimateOutcome

/**
 * Phase 15 import results are estimate-only.
 *
 * This alias intentionally binds import success/no-read semantics to the
 * existing visual-estimate outcome. Import code must not create a separate
 * success/no-read hierarchy or strict measurement outcome.
 */
typealias ImportEstimateOutcome = VisualEstimateOutcome

/** Import source kind persisted in saved summaries or exports. */
enum class ImportResultSourceKind {
    IMPORT_ESTIMATE,
    RECORDED_ESTIMATE,
}

/** Estimate-only timing basis for user-selected imported clips. */
enum class ImportTimingBasis {
    CONTAINER_PRESENTATION_TIMESTAMPS,
    PARTIAL_TIMESTAMP_VISUAL_GAP_RECONCILIATION,
    VISUAL_FRAME_DELTA_INFERENCE,
    RECORDED_CAPTURE_FRAME_INTERVAL,
    RECORDED_CONTAINER_PRESENTATION_TIMESTAMPS,
    NO_TRUSTWORTHY_TIMING,
}

/** Fail-loud reason an import cannot produce an estimate. */
enum class ImportNoReadReason {
    UNSUPPORTED_MEDIA,
    INVALID_METADATA,
    PERMISSION_DENIED,
    NO_TRUSTWORTHY_TIMING,
    RESOURCE_LIMIT_EXCEEDED,
}

/** Result of validating import-domain inputs before frame processing. */
sealed interface ImportValidationResult<out T> {
    data class Success<T>(val value: T) : ImportValidationResult<T>
    data class NoRead(
        val reason: ImportNoReadReason,
        val message: String,
    ) : ImportValidationResult<Nothing>
}

/**
 * Redacted metadata for a user-selected video.
 *
 * It deliberately excludes content URIs, filesystem paths, and raw media
 * identifiers. Monotonic presentation timestamps are estimate evidence only,
 * not strict measurement proof.
 */
class ImportVideoMetadata private constructor(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val durationSeconds: Double,
    val rotationDegrees: Int,
    val sampleCount: Int?,
    val hasMonotonicPresentationTimestamps: Boolean,
) {
    companion object {
        fun validate(
            mimeType: String,
            width: Int,
            height: Int,
            durationSeconds: Double,
            rotationDegrees: Int,
            sampleCount: Int?,
            hasMonotonicPresentationTimestamps: Boolean,
        ): ImportValidationResult<ImportVideoMetadata> {
            if (!mimeType.startsWith("video/")) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.UNSUPPORTED_MEDIA,
                    message = "Imported media must be a video selected by the user.",
                )
            }
            if (width <= 0 || height <= 0 || !durationSeconds.isFinite() || durationSeconds <= 0.0) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.INVALID_METADATA,
                    message = "Imported video metadata must include finite positive dimensions and duration.",
                )
            }
            if (rotationDegrees !in setOf(0, 90, 180, 270)) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.INVALID_METADATA,
                    message = "Imported video rotation metadata is unsupported.",
                )
            }
            if (sampleCount != null && sampleCount <= 0) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.INVALID_METADATA,
                    message = "Imported video sample count must be positive when present.",
                )
            }
            return ImportValidationResult.Success(
                ImportVideoMetadata(
                    mimeType = mimeType,
                    width = width,
                    height = height,
                    durationSeconds = durationSeconds,
                    rotationDegrees = rotationDegrees,
                    sampleCount = sampleCount,
                    hasMonotonicPresentationTimestamps = hasMonotonicPresentationTimestamps,
                ),
            )
        }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ImportVideoMetadata &&
                mimeType == other.mimeType &&
                width == other.width &&
                height == other.height &&
                durationSeconds == other.durationSeconds &&
                rotationDegrees == other.rotationDegrees &&
                sampleCount == other.sampleCount &&
                hasMonotonicPresentationTimestamps == other.hasMonotonicPresentationTimestamps

    override fun hashCode(): Int {
        var result = mimeType.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + durationSeconds.hashCode()
        result = 31 * result + rotationDegrees
        result = 31 * result + (sampleCount ?: 0)
        result = 31 * result + hasMonotonicPresentationTimestamps.hashCode()
        return result
    }

    override fun toString(): String =
        "ImportVideoMetadata(mimeType=$mimeType, width=$width, height=$height, " +
            "durationSeconds=$durationSeconds, rotationDegrees=$rotationDegrees, " +
            "sampleCount=$sampleCount, hasMonotonicPresentationTimestamps=$hasMonotonicPresentationTimestamps)"
}

/** One decoded import frame in app memory. Raw pixels are not saved or exported. */
data class ImportVideoFrame(
    val frameIndex: Int,
    val presentationTimestampNanos: Long?,
    val width: Int,
    val height: Int,
    val argbPixels: IntArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ImportVideoFrame &&
                frameIndex == other.frameIndex &&
                presentationTimestampNanos == other.presentationTimestampNanos &&
                width == other.width &&
                height == other.height &&
                argbPixels.contentEquals(other.argbPixels)

    override fun hashCode(): Int {
        var result = frameIndex
        result = 31 * result + (presentationTimestampNanos?.hashCode() ?: 0)
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + argbPixels.contentHashCode()
        return result
    }
}

/** Ordered imported frame batch. Every consumer must validate timing before use. */
data class ImportVideoFrameSequence(
    val frames: List<ImportVideoFrame>,
)

/** Import estimate request after metadata/frame validation. */
data class ImportEstimateRequest(
    val metadata: ImportVideoMetadata,
    val frames: ImportVideoFrameSequence,
    val timingBasis: ImportTimingBasis,
)

/** Redacted summary safe for saved history and export surfaces. */
data class ImportEvidenceSummary(
    val sourceKind: ImportResultSourceKind,
    val timingBasis: ImportTimingBasis,
    val frameCount: Int,
    val detectionCount: Int,
    val assumptions: List<String>,
)
