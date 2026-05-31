package com.speedball.app.capture

import android.content.Context
import android.media.MediaRecorder
import android.view.Surface
import java.io.File

private const val COMPANION_SCRATCH_DIR = "speed-ball-companion"
private const val MIN_COMPANION_FILE_LIMIT_BYTES = 1_000_000L
private const val BYTES_PER_SECOND_AT_720P_120 = 3_000_000L
private const val BYTES_PER_SECOND_AT_1080P_120 = 5_000_000L

/** Path-free status for companion encoder scratch cleanup. */
enum class CompanionScratchCleanupStatus {
    DELETED,
    ALREADY_ABSENT,
    FAILED,
}

/** Path-free cleanup result. */
data class CompanionScratchCleanupResult(
    val status: CompanionScratchCleanupStatus,
)

/**
 * Companion encoder resources for the Phase 9 direct source session shape.
 *
 * The scratch MP4 is only a Camera2 session driver. It must not be surfaced in
 * diagnostics, logged by path, imported, or used as a measurement source.
 */
class CompanionEncoderScratch internal constructor(
    private val recorder: MediaRecorder,
    private val file: File,
    val surface: Surface,
    val durationLimitMillis: Long,
    val fileSizeLimitBytes: Long,
) {
    fun start() {
        recorder.start()
    }

    fun stopIfStarted(started: Boolean) {
        if (started) {
            recorder.stop()
        }
    }

    fun releaseAndDelete(): CompanionScratchCleanupResult {
        runCatching { recorder.release() }
        return deleteCompanionScratchFile(file)
    }
}

fun prepareCompanionEncoderScratch(
    context: Context,
    mode: HighSpeedMode,
    durationMillis: Long,
): CompanionEncoderScratch? {
    val boundedDuration = clampBurstDurationMillis(durationMillis)
    val file = createCompanionScratchFile(
        cacheDir = context.cacheDir,
        mode = mode,
        timestampMillis = System.currentTimeMillis(),
    )
    @Suppress("DEPRECATION")
    val recorder = MediaRecorder()
    return try {
        val fileSizeLimit = companionScratchFileSizeLimitBytes(mode, boundedDuration)
        recorder.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setOutputFile(file.absolutePath)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(mode.width, mode.height)
            setVideoFrameRate(mode.fps)
            setCaptureRate(mode.fps.toDouble())
            setVideoEncodingBitRate(if (mode.width >= 1920) 40_000_000 else 24_000_000)
            setMaxDuration(boundedDuration.toInt())
            setMaxFileSize(fileSizeLimit)
            prepare()
        }
        CompanionEncoderScratch(
            recorder = recorder,
            file = file,
            surface = recorder.surface,
            durationLimitMillis = boundedDuration,
            fileSizeLimitBytes = fileSizeLimit,
        )
    } catch (_: Exception) {
        runCatching { recorder.release() }
        deleteCompanionScratchFile(file)
        null
    }
}

fun createCompanionScratchFile(
    cacheDir: File,
    mode: HighSpeedMode,
    timestampMillis: Long,
): File {
    require(timestampMillis >= 0L) { "Timestamp must be non-negative." }
    val directory = File(cacheDir, COMPANION_SCRATCH_DIR)
    if (!directory.exists()) {
        directory.mkdirs()
    }
    return File(directory, "companion_${mode.width}x${mode.height}_${mode.fps}_$timestampMillis.mp4")
}

fun companionScratchFileSizeLimitBytes(mode: HighSpeedMode, durationMillis: Long): Long {
    val seconds = (clampBurstDurationMillis(durationMillis) + 999L) / 1_000L
    val bytesPerSecond = if (mode.width >= 1920) BYTES_PER_SECOND_AT_1080P_120 else BYTES_PER_SECOND_AT_720P_120
    return maxOf(MIN_COMPANION_FILE_LIMIT_BYTES, seconds * bytesPerSecond)
}

fun deleteCompanionScratchFile(file: File): CompanionScratchCleanupResult {
    if (!file.exists()) {
        return CompanionScratchCleanupResult(CompanionScratchCleanupStatus.ALREADY_ABSENT)
    }
    val deleted = file.delete()
    return CompanionScratchCleanupResult(
        status = if (deleted || !file.exists()) {
            CompanionScratchCleanupStatus.DELETED
        } else {
            CompanionScratchCleanupStatus.FAILED
        },
    )
}
