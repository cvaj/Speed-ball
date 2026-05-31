package com.speedball.app.decode

/** Raw video-track values copied from Android media APIs before pure validation. */
data class RawDecodedVideoMetadata(
    val mimeType: String?,
    val width: Int?,
    val height: Int?,
    val durationMicros: Long?,
    val presentationTimeMicros: List<Long>,
)

/**
 * Validated decoded video metadata. The sample count and PTS list come from
 * MediaExtractor; SENSOR_TIMESTAMP remains the measurement timing authority.
 */
data class DecodedVideoMetadata(
    val frameCount: Int,
    val width: Int,
    val height: Int,
    val durationMicros: Long?,
    val presentationTimeMicros: List<Long>,
    val medianPresentationGapMillis: Double?,
    val maximumPresentationGapMillis: Double?,
)

sealed interface MetadataValidation {
    data class Success(val metadata: DecodedVideoMetadata) : MetadataValidation
    data class Failure(val reason: DecodeFailure, val message: String) : MetadataValidation
}

fun validateDecodedVideoMetadata(
    raw: RawDecodedVideoMetadata?,
    requestedFps: Int,
): MetadataValidation {
    if (raw == null || raw.mimeType?.startsWith("video/") != true) {
        return MetadataValidation.Failure(DecodeFailure.NO_VIDEO_TRACK, "No video track was found in the recorder output.")
    }
    val width = raw.width
    val height = raw.height
    if (width == null || height == null || width <= 0 || height <= 0) {
        return MetadataValidation.Failure(DecodeFailure.INVALID_VIDEO_METADATA, "Video dimensions are missing or invalid.")
    }
    if (raw.durationMicros != null && raw.durationMicros <= 0L) {
        return MetadataValidation.Failure(DecodeFailure.INVALID_VIDEO_METADATA, "Video duration is invalid.")
    }
    val pts = raw.presentationTimeMicros
    if (pts.isEmpty()) {
        return MetadataValidation.Failure(DecodeFailure.FRAME_COUNT_UNAVAILABLE, "No decoded sample timestamps were available.")
    }
    if (pts.size < MINIMUM_RECONCILABLE_FRAME_COUNT) {
        return MetadataValidation.Failure(DecodeFailure.FRAME_COUNT_TOO_LOW, "At least three decoded frames are required.")
    }
    if (!pts.isStrictlyIncreasing()) {
        return MetadataValidation.Failure(DecodeFailure.PRESENTATION_TIMESTAMPS_NON_MONOTONIC, "Presentation timestamps were not strictly increasing.")
    }
    val ptsNanos = pts.map { it * 1_000L }
    val diagnostics = buildOrderedTimestampDiagnostics(ptsNanos, requestedFps)
    return MetadataValidation.Success(
        DecodedVideoMetadata(
            frameCount = pts.size,
            width = width,
            height = height,
            durationMicros = raw.durationMicros,
            presentationTimeMicros = pts,
            medianPresentationGapMillis = diagnostics.medianGapMillis,
            maximumPresentationGapMillis = diagnostics.maximumGapMillis,
        ),
    )
}

const val MINIMUM_RECONCILABLE_FRAME_COUNT: Int = 3

internal fun List<Long>.isStrictlyIncreasing(): Boolean =
    zipWithNext().all { (a, b) -> b > a }
