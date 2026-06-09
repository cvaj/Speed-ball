package com.speedball.app.capture

import com.speedball.app.importing.ImportNoReadReason
import com.speedball.app.importing.ImportValidationResult
import kotlin.math.ceil
import kotlin.math.floor

private const val MICROS_PER_SECOND = 1_000_000.0

/** Status for mapping an impact marker onto the recorded container timeline. */
enum class AudioVideoClockAnchorStatus {
    OK,
    MISSING_VIDEO_ANCHOR,
    UNMAPPABLE_TIMESTAMP_SOURCE,
    MISSING_AUDIO_ANCHOR,
    BAD_IMPACT_TIME,
    ANCHOR_ERROR_EXCEEDS_CEILING,
}

/**
 * End-to-end audio/video anchor for sound-triggered recorded-HFR.
 *
 * [sensorDiagnosticFrameIndex] is diagnostic only. MediaRecorder may drop encoded
 * frames, so decoder localization must use [ContainerTimeWindow] presentation
 * times instead of this sensor-clock index.
 */
data class AudioVideoClockAnchor(
    val firstFrameSensorTimestampNanos: Long,
    val firstFrameElapsedRealtimeNanos: Long,
    val recorderStartCommandElapsedNanos: Long,
    val timestampSource: CameraTimestampSourceLabel,
    val endToEndAnchorErrorNanos: Long,
) {
    val status: AudioVideoClockAnchorStatus =
        when {
            firstFrameSensorTimestampNanos <= 0L || firstFrameElapsedRealtimeNanos <= 0L -> AudioVideoClockAnchorStatus.MISSING_VIDEO_ANCHOR
            timestampSource != CameraTimestampSourceLabel.REALTIME -> AudioVideoClockAnchorStatus.UNMAPPABLE_TIMESTAMP_SOURCE
            endToEndAnchorErrorNanos < 0L -> AudioVideoClockAnchorStatus.BAD_IMPACT_TIME
            else -> AudioVideoClockAnchorStatus.OK
        }
}

/** Request to map a detected impact marker into a bounded container-time window. */
data class ImpactWindowRequest(
    val impactElapsedRealtimeNanos: Long,
    val requestedFps: Int,
    val preImpactMillis: Long = 1_000L,
    val postImpactMillis: Long = 1_000L,
    val maxPreImpactMarginFrames: Int = 12,
)

/** Container presentation-time interval emitted by the recorded-HFR window decoder. */
data class ContainerTimeWindow(
    val windowStartUs: Long,
    val windowEndUs: Long,
    val postImpactFrameCount: Int,
    val preImpactMarginFrames: Int,
    val maxFrames: Int,
)

/** Mapping result for one impact marker. */
data class ImpactWindowMapping(
    val impactOffsetUs: Long,
    val sensorDiagnosticFrameIndex: Int,
    val endToEndAnchorErrorNanos: Long,
    val window: ContainerTimeWindow,
)

/** Maps impact audio timestamps to bounded MediaRecorder container-time windows. */
object ImpactWindowMapper {
    fun map(
        anchor: AudioVideoClockAnchor,
        request: ImpactWindowRequest,
    ): ImportValidationResult<ImpactWindowMapping> {
        if (anchor.status != AudioVideoClockAnchorStatus.OK) {
            return noRead("Impact window anchor is not trustworthy: ${anchor.status}.")
        }
        if (request.requestedFps <= 0 || request.preImpactMillis < 0L || request.postImpactMillis <= 0L || request.maxPreImpactMarginFrames < 0) {
            return noRead("Impact window request must use positive fps and duration limits.")
        }
        if (request.impactElapsedRealtimeNanos <= anchor.firstFrameElapsedRealtimeNanos) {
            return noRead("Impact happened before the recorded container timeline was anchored.")
        }
        val frameIntervalNanos = 1_000_000_000.0 / request.requestedFps
        val preImpactMarginFrames = ceil(anchor.endToEndAnchorErrorNanos / frameIntervalNanos).toInt()
        if (preImpactMarginFrames > request.maxPreImpactMarginFrames) {
            return noRead("Impact audio/video anchor error exceeds the configured pre-impact margin ceiling.")
        }
        val impactOffsetNanos = request.impactElapsedRealtimeNanos - anchor.firstFrameElapsedRealtimeNanos
        val impactOffsetUs = (impactOffsetNanos / 1_000L).coerceAtLeast(0L)
        val preImpactMarginUs = (preImpactMarginFrames * frameIntervalNanos / 1_000.0).toLong()
        val requestedPreImpactFrameCount = ceil(request.requestedFps * (request.preImpactMillis / 1_000.0)).toInt()
        val requestedPreImpactUs = request.preImpactMillis * 1_000L
        val postImpactFrameCount = ceil(request.requestedFps * (request.postImpactMillis / 1_000.0)).toInt()
        val windowStartUs = (impactOffsetUs - requestedPreImpactUs - preImpactMarginUs).coerceAtLeast(0L)
        val windowEndUs = impactOffsetUs + request.postImpactMillis * 1_000L
        if (windowEndUs <= windowStartUs) return noRead("Impact container-time window must be positive.")
        return ImportValidationResult.Success(
            ImpactWindowMapping(
                impactOffsetUs = impactOffsetUs,
                sensorDiagnosticFrameIndex = floor((impactOffsetUs / MICROS_PER_SECOND) * request.requestedFps).toInt(),
                endToEndAnchorErrorNanos = anchor.endToEndAnchorErrorNanos,
                window = ContainerTimeWindow(
                    windowStartUs = windowStartUs,
                    windowEndUs = windowEndUs,
                    postImpactFrameCount = postImpactFrameCount,
                    preImpactMarginFrames = preImpactMarginFrames,
                    maxFrames = requestedPreImpactFrameCount + postImpactFrameCount + preImpactMarginFrames,
                ),
            ),
        )
    }

    private fun noRead(message: String): ImportValidationResult.NoRead =
        ImportValidationResult.NoRead(ImportNoReadReason.NO_TRUSTWORTHY_TIMING, message)
}
