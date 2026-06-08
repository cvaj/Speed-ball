package com.speedball.app.capture

import java.io.File
import kotlin.math.abs

/** A device-reported high-speed mode reduced to app-owned primitive values. */
data class HighSpeedMode(
    val width: Int,
    val height: Int,
    val fps: Int,
    val aeTargetFpsLower: Int,
    val aeTargetFpsUpper: Int,
    val recordSupported: Boolean,
) {
    val label: String = "${width}x$height @ ${fps} fps"
}

/** Raw high-speed FPS range data copied out of Camera2 framework objects. */
data class RawHighSpeedRange(
    val width: Int,
    val height: Int,
    val lowerFps: Int,
    val upperFps: Int,
)

/** User/request options for a bounded high-speed burst. */
data class BurstOptions(
    val mode: HighSpeedMode,
    val durationMillis: Long = DEFAULT_BURST_DURATION_MILLIS,
    val preferredExposureTimeNanos: Long? = null,
    val companionSurfaceMode: BurstCompanionSurfaceMode = BurstCompanionSurfaceMode.VISIBLE_PREVIEW,
    val stopMode: BurstStopMode = BurstStopMode.FixedDuration,
    val onFirstFrameAnchor: ((BurstFrameAnchor) -> Unit)? = null,
)

/** Preview-class companion target used beside the recorder surface in constrained HFR sessions. */
enum class BurstCompanionSurfaceMode {
    VISIBLE_PREVIEW,
    OFFSCREEN_PREVIEW,
}

/** Controls whether the recorder stops itself or waits for an external marker. */
enum class BurstStopMode {
    FixedDuration,
    ExternalStop,
}

/** First positive Camera2 timestamp observed after recording starts. */
data class BurstFrameAnchor(
    val sensorTimestampNanos: Long,
    val elapsedRealtimeNanos: Long,
    val recorderStartCommandElapsedNanos: Long,
    val timestampSource: CameraTimestampSourceLabel,
)

/** Fail-loud reasons for every Phase 4 capture terminal path. */
enum class BurstFailure {
    CAMERA_PERMISSION_DENIED,
    NO_BACK_CAMERA,
    NO_HIGH_SPEED_MODES,
    UNSUPPORTED_MODE,
    CAPTURE_BUSY,
    CAMERA_OPEN_FAILED,
    CAMERA_DEVICE_DISCONNECTED,
    CAMERA_DEVICE_ERROR,
    SESSION_CONFIGURATION_FAILED,
    RECORDER_PREPARE_FAILED,
    RECORDING_FAILED,
    NO_SENSOR_TIMESTAMPS,
    RESOURCE_RELEASE_FAILED,
}

/** Terminal result from a burst attempt. Failure never carries partial measurement values. */
sealed interface BurstOutcome {
    data class Success(
        val diagnostics: BurstDiagnostics,
        val outputFile: File? = null,
        val sensorTimestampsNanos: List<Long> = emptyList(),
        val requestedFps: Int? = null,
        val requestedDurationMillis: Long? = null,
        val width: Int? = null,
        val height: Int? = null,
        val companionSurfaceMode: BurstCompanionSurfaceMode? = null,
    ) : BurstOutcome

    data class Failure(val reason: BurstFailure, val message: String) : BurstOutcome
}

/** Terminal result from high-speed mode enumeration. */
sealed interface HighSpeedModesResult {
    data class Success(val modes: List<HighSpeedMode>) : HighSpeedModesResult
    data class Failure(val reason: BurstFailure, val message: String) : HighSpeedModesResult
}

enum class BurstRecorderState {
    Idle,
    Opening,
    Configuring,
    Recording,
    Releasing,
}

/** Small synchronization primitive for exactly-once terminal capture completion. */
class TerminalCompletionGate {
    private var completed = false

    fun claim(): Boolean = synchronized(this) {
        if (completed) {
            false
        } else {
            completed = true
            true
        }
    }

    fun reset() = synchronized(this) {
        completed = false
    }
}

const val DEFAULT_BURST_DURATION_MILLIS: Long = 2_500L
const val MAX_BURST_DURATION_MILLIS: Long = 8_000L
const val DEFAULT_FAST_SHUTTER_EXPOSURE_NANOS: Long = 1_000_000L

fun clampBurstDurationMillis(durationMillis: Long): Long =
    when {
        durationMillis <= 0L -> DEFAULT_BURST_DURATION_MILLIS
        durationMillis > MAX_BURST_DURATION_MILLIS -> MAX_BURST_DURATION_MILLIS
        else -> durationMillis
    }

/**
 * Duration failsafe for burst recording.
 *
 * [BurstStopMode.ExternalStop] may stop earlier from an external marker, but it
 * still receives this hard cap so a stalled marker path cannot run HFR forever.
 */
fun resolveBurstDurationFailsafeMillis(
    stopMode: BurstStopMode,
    durationMillis: Long,
): Long? =
    when (stopMode) {
        BurstStopMode.FixedDuration,
        BurstStopMode.ExternalStop -> clampBurstDurationMillis(durationMillis)
    }

fun mapHighSpeedModes(rawRanges: List<RawHighSpeedRange>): List<HighSpeedMode> {
    val supportedSizes = setOf(1280 to 720, 1920 to 1080)
    val targetFps = setOf(120, 240)
    val modes = mutableListOf<HighSpeedMode>()

    rawRanges
        .filter { (it.width to it.height) in supportedSizes }
        .groupBy { it.width to it.height }
        .forEach { (size, rangesForSize) ->
            targetFps.forEach { fps ->
                val matching = rangesForSize.filter { it.upperFps == fps }
                if (matching.isNotEmpty()) {
                    val fixed = matching.firstOrNull { it.lowerFps == fps }
                    val representative = fixed ?: matching.minBy { abs(it.lowerFps - fps) }
                    modes += HighSpeedMode(
                        width = size.first,
                        height = size.second,
                        fps = fps,
                        aeTargetFpsLower = representative.lowerFps,
                        aeTargetFpsUpper = representative.upperFps,
                        recordSupported = fixed != null,
                    )
                }
            }
        }

    return modes.distinct().sortedWith(compareBy(::modeSortRank, { it.width * it.height }))
}

fun selectDefaultMode(modes: List<HighSpeedMode>): HighSpeedMode? =
    modes.firstOrNull { it.recordSupported && it.fps == 120 }

fun validateBurstStart(
    mode: HighSpeedMode,
    availableModes: List<HighSpeedMode>,
    recorderState: BurstRecorderState,
): BurstOutcome.Failure? {
    if (recorderState != BurstRecorderState.Idle) {
        return BurstOutcome.Failure(BurstFailure.CAPTURE_BUSY, "A capture burst is already active.")
    }
    val knownMode = availableModes.any {
        it.width == mode.width &&
            it.height == mode.height &&
            it.fps == mode.fps &&
            it.aeTargetFpsLower == mode.aeTargetFpsLower &&
            it.aeTargetFpsUpper == mode.aeTargetFpsUpper
    }
    if (!knownMode || mode.fps != 120 || !mode.recordSupported || mode.aeTargetFpsLower != mode.fps || mode.aeTargetFpsUpper != mode.fps) {
        return BurstOutcome.Failure(BurstFailure.UNSUPPORTED_MODE, "This high-speed mode is not supported for Phase 4 recording.")
    }
    return null
}

private fun modeSortRank(mode: HighSpeedMode): Int =
    when {
        mode.width == 1280 && mode.height == 720 && mode.fps == 120 && mode.recordSupported -> 0
        mode.width == 1920 && mode.height == 1080 && mode.fps == 120 && mode.recordSupported -> 1
        mode.width == 1280 && mode.height == 720 && mode.fps == 240 && mode.recordSupported -> 2
        mode.width == 1920 && mode.height == 1080 && mode.fps == 240 && mode.recordSupported -> 3
        mode.recordSupported -> 4
        else -> 5
    }
