package com.speedball.app.decode

/** Fail-loud reasons for Phase 5 decode and frame/timestamp reconciliation. */
enum class DecodeFailure {
    OUTPUT_FILE_MISSING,
    OUTPUT_FILE_EMPTY,
    UNSUPPORTED_DECODER_API,
    NO_VIDEO_TRACK,
    INVALID_VIDEO_METADATA,
    FRAME_COUNT_UNAVAILABLE,
    FRAME_COUNT_TOO_LOW,
    FRAME_EXTRACTION_FAILED,
    MISSING_SENSOR_TIMESTAMPS,
    SENSOR_TIMESTAMP_NEAR_DUPLICATE,
    FRAME_SENSOR_COUNT_MISMATCH,
    PRESENTATION_TIMESTAMPS_NON_MONOTONIC,
    PRESENTATION_CADENCE_MISMATCH,
    PRESENTATION_DROPPED_FRAME_GAP,
    SENSOR_CADENCE_MISMATCH,
    SENSOR_DROPPED_FRAME_GAP,
    DECODE_WORK_LIMIT_EXCEEDED,
    RESOURCE_RELEASE_FAILED,
}

/** Summary of the relative PTS-vs-SENSOR_TIMESTAMP offset series. */
data class OffsetSummary(
    val minimumOffsetMicros: Long,
    val maximumOffsetMicros: Long,
    val spreadMicros: Long,
)

enum class PresentationClockAssessment {
    UNKNOWN,
    CAPTURE_BASED_CANDIDATE,
    SYNTHETIC_UNIFORM_CANDIDATE,
}

data class DecodedFrameSample(
    val frameIndex: Int,
    val width: Int,
    val height: Int,
    val presentationTimeMicros: Long,
)

/** Raw count/gap diagnostics used by developer proof UI only, not measurement results. */
data class ReconciliationDiagnostics(
    val decodedFrameCount: Int,
    val uniqueSensorTimestampCount: Int,
    val sensorGapNanos: List<Long> = emptyList(),
    val presentationGapMicros: List<Long> = emptyList(),
    val medianSensorGapMillis: Double?,
    val maximumSensorGapMillis: Double?,
    val medianPresentationGapMillis: Double?,
    val maximumPresentationGapMillis: Double?,
    val expectedGapMillis: Double,
    val gapLowerBoundMillis: Double,
    val gapUpperBoundMillis: Double,
    val droppedFrameGapThresholdMillis: Double,
    val exactCountPasses: Boolean,
    val nearDuplicateGapMillis: Double?,
    val ptsToSensorOffsetSummary: OffsetSummary?,
    val ptsToSensorOffsetMicros: List<Long> = emptyList(),
    val presentationClockAssessment: PresentationClockAssessment,
    val sampledFrames: List<DecodedFrameSample> = emptyList(),
)

/** Terminal Phase 5 decode result. Failure and cancellation never carry decoded bitmaps or partial measurement payloads. */
sealed interface DecodeOutcome {
    data class Success(
        val metadata: DecodedVideoMetadata,
        val pairs: List<FrameTimestampPair>,
        val diagnostics: ReconciliationDiagnostics,
    ) : DecodeOutcome

    data class Failure(
        val reason: DecodeFailure,
        val message: String,
        val diagnostics: ReconciliationDiagnostics? = null,
    ) : DecodeOutcome

    data object Cancelled : DecodeOutcome
}

fun resolveDecodeTerminalFailure(
    primaryFailure: DecodeFailure?,
    releaseFailure: DecodeFailure?,
): DecodeFailure? = primaryFailure ?: releaseFailure
