package com.speedball.app.importing

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import java.io.File

/** Android SAF-backed import frame source for user-selected videos. */
class AndroidImportVideoFrameSource private constructor(
    context: Context,
    private val source: AndroidImportVideoSource,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val sampleTimesUs: List<Long>,
    private val firstFrameIndex: Int,
) : ImportFrameSource {
    private val appContext = context.applicationContext
    private val retriever = MediaMetadataRetriever()
    private var cursor = 0

    init {
        source.setDataSource(appContext, retriever)
    }

    override fun nextFrame(): ImportVideoFrame? {
        if (cursor >= sampleTimesUs.size) return null
        val sampleTimeUs = sampleTimesUs[cursor]
        val frameIndex = firstFrameIndex + cursor
        val decoded = decodeFrame(frameIndex, sampleTimeUs)
            ?: throw IllegalStateException("No frame decoded for import sample.")
        val scaled = if (decoded.width == targetWidth && decoded.height == targetHeight) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true).also {
                decoded.recycle()
            }
        }
        val pixels = IntArray(targetWidth * targetHeight)
        scaled.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        scaled.recycle()
        return ImportVideoFrame(
            frameIndex = frameIndex.also { cursor++ },
            presentationTimestampNanos = sampleTimeUs * 1_000L,
            width = targetWidth,
            height = targetHeight,
            argbPixels = pixels,
        )
    }

    private fun decodeFrame(frameIndex: Int, sampleTimeUs: Long): Bitmap? {
        val indexedFrame = try {
            val method = retriever.javaClass.getMethod("getFrameAtIndex", Int::class.javaPrimitiveType)
            method.invoke(retriever, frameIndex) as? Bitmap
        } catch (_: ReflectiveOperationException) {
            null
        } catch (_: RuntimeException) {
            null
        }
        return indexedFrame ?: retriever.getFrameAtTime(sampleTimeUs, MediaMetadataRetriever.OPTION_CLOSEST)
    }

    override fun close() {
        try {
            retriever.release()
        } catch (_: RuntimeException) {
            // Resource release is best-effort after MediaMetadataRetriever failures.
        }
    }

    companion object {
        fun create(
            context: Context,
            uri: Uri,
            targetWidth: Int,
            targetHeight: Int,
            maxFrames: Int,
            startFrameIndex: Int = 0,
        ): ImportValidationResult<AndroidImportVideoFrameSource> {
            if (targetWidth <= 0 || targetHeight <= 0 || maxFrames <= 0 || startFrameIndex < 0) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                    message = "Import frame target size, start index, and frame cap must be valid.",
                )
            }
            val sampleTimes = readVideoSampleTimes(context, uri, maxFrames, startFrameIndex)
            if (sampleTimes is ImportValidationResult.NoRead) return sampleTimes
            sampleTimes as ImportValidationResult.Success
            return try {
                ImportValidationResult.Success(
                    AndroidImportVideoFrameSource(
                        context = context,
                        source = AndroidImportVideoSource.ContentUri(uri),
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        sampleTimesUs = sampleTimes.value,
                        firstFrameIndex = startFrameIndex,
                    ),
                )
            } catch (_: RuntimeException) {
                ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.INVALID_METADATA,
                    message = "Imported video could not be opened for frame extraction.",
                )
            }
        }

        fun create(
            context: Context,
            file: File,
            targetWidth: Int,
            targetHeight: Int,
            maxFrames: Int,
            startFrameIndex: Int = 0,
        ): ImportValidationResult<AndroidImportVideoFrameSource> {
            if (!file.isFile || file.length() <= 0L) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.INVALID_METADATA,
                    message = "Recorded video file is missing or empty.",
                )
            }
            if (targetWidth <= 0 || targetHeight <= 0 || maxFrames <= 0 || startFrameIndex < 0) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                    message = "Import frame target size, start index, and frame cap must be valid.",
                )
            }
            val sampleTimes = readVideoSampleTimes(file, maxFrames, startFrameIndex)
            if (sampleTimes is ImportValidationResult.NoRead) return sampleTimes
            sampleTimes as ImportValidationResult.Success
            return try {
                ImportValidationResult.Success(
                    AndroidImportVideoFrameSource(
                        context = context,
                        source = AndroidImportVideoSource.AppFile(file),
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        sampleTimesUs = sampleTimes.value,
                        firstFrameIndex = startFrameIndex,
                    ),
                )
            } catch (_: RuntimeException) {
                ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.INVALID_METADATA,
                    message = "Recorded video could not be opened for frame extraction.",
                )
            }
        }

        fun readMetadata(
            context: Context,
            uri: Uri,
            mimeType: String?,
            maxSamplesToProbe: Int,
        ): ImportValidationResult<ImportVideoMetadata> {
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(context.applicationContext, uri)
                val width = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val durationMillis = retriever.extractLong(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val rotation = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
                val sampleTimes = readVideoSampleTimes(context, uri, maxSamplesToProbe)
                if (sampleTimes is ImportValidationResult.NoRead) return sampleTimes
                sampleTimes as ImportValidationResult.Success
                ImportVideoMetadata.validate(
                    mimeType = mimeType ?: "video/unknown",
                    width = width ?: 0,
                    height = height ?: 0,
                    durationSeconds = (durationMillis ?: 0L) / 1_000.0,
                    rotationDegrees = rotation,
                    sampleCount = sampleTimes.value.size.takeIf { it > 0 },
                    hasMonotonicPresentationTimestamps = sampleTimes.value.isStrictlyIncreasing(),
                )
            } catch (_: RuntimeException) {
                ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.INVALID_METADATA,
                    message = "Imported video metadata could not be read safely.",
                )
            } finally {
                try {
                    retriever.release()
                } catch (_: RuntimeException) {
                    // Ignore retriever cleanup failures after a no-read path.
                }
            }
        }

        fun readMetadata(
            file: File,
            mimeType: String = "video/mp4",
            maxSamplesToProbe: Int,
        ): ImportValidationResult<ImportVideoMetadata> {
            if (!file.isFile || file.length() <= 0L) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.INVALID_METADATA,
                    message = "Recorded video file is missing or empty.",
                )
            }
            val retriever = MediaMetadataRetriever()
            return try {
                retriever.setDataSource(file.absolutePath)
                val width = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
                val height = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
                val durationMillis = retriever.extractLong(MediaMetadataRetriever.METADATA_KEY_DURATION)
                val rotation = retriever.extractInt(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) ?: 0
                val sampleTimes = readVideoSampleTimes(file, maxSamplesToProbe)
                if (sampleTimes is ImportValidationResult.NoRead) return sampleTimes
                sampleTimes as ImportValidationResult.Success
                ImportVideoMetadata.validate(
                    mimeType = mimeType,
                    width = width ?: 0,
                    height = height ?: 0,
                    durationSeconds = (durationMillis ?: 0L) / 1_000.0,
                    rotationDegrees = rotation,
                    sampleCount = sampleTimes.value.size.takeIf { it > 0 },
                    hasMonotonicPresentationTimestamps = sampleTimes.value.isStrictlyIncreasing(),
                )
            } catch (_: RuntimeException) {
                ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.INVALID_METADATA,
                    message = "Recorded video metadata could not be read safely.",
                )
            } finally {
                try {
                    retriever.release()
                } catch (_: RuntimeException) {
                    // Ignore retriever cleanup failures after a no-read path.
                }
            }
        }

        private fun readVideoSampleTimes(
            context: Context,
            uri: Uri,
            maxSamples: Int,
            startSampleIndex: Int = 0,
        ): ImportValidationResult<List<Long>> {
            if (maxSamples <= 0 || startSampleIndex < 0) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                    message = "Import sample probe limit and start index must be valid.",
                )
            }
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(context.applicationContext, uri, null)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.UNSUPPORTED_MEDIA,
                    message = "Imported media does not contain a video track.",
                )
                extractor.selectTrack(trackIndex)
                repeat(startSampleIndex) {
                    if (!extractor.advance()) {
                        return ImportValidationResult.NoRead(
                            reason = ImportNoReadReason.INVALID_METADATA,
                            message = "Imported video start frame is beyond the available samples.",
                        )
                    }
                }
                val times = mutableListOf<Long>()
                while (times.size < maxSamples) {
                    val timeUs = extractor.sampleTime
                    if (timeUs < 0L) break
                    times += timeUs
                    if (!extractor.advance()) break
                }
                if (times.size < 2) {
                    return ImportValidationResult.NoRead(
                        reason = ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
                        message = "Imported video needs at least two timestamped frames.",
                    )
                }
                if (!times.isStrictlyIncreasing()) {
                    return ImportValidationResult.NoRead(
                        reason = ImportNoReadReason.INVALID_METADATA,
                        message = "Imported video presentation timestamps are not strictly increasing.",
                    )
                }
                ImportValidationResult.Success(times.toList())
            } catch (_: RuntimeException) {
                ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.INVALID_METADATA,
                    message = "Imported video sample timestamps could not be probed safely.",
                )
            } finally {
                extractor.release()
            }
        }

        private fun readVideoSampleTimes(
            file: File,
            maxSamples: Int,
            startSampleIndex: Int = 0,
        ): ImportValidationResult<List<Long>> {
            if (maxSamples <= 0 || startSampleIndex < 0) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                    message = "Import sample probe limit and start index must be valid.",
                )
            }
            val extractor = MediaExtractor()
            return try {
                extractor.setDataSource(file.absolutePath)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.UNSUPPORTED_MEDIA,
                    message = "Recorded media does not contain a video track.",
                )
                extractor.selectTrack(trackIndex)
                repeat(startSampleIndex) {
                    if (!extractor.advance()) {
                        return ImportValidationResult.NoRead(
                            reason = ImportNoReadReason.INVALID_METADATA,
                            message = "Recorded video start frame is beyond the available samples.",
                        )
                    }
                }
                val times = mutableListOf<Long>()
                while (times.size < maxSamples) {
                    val timeUs = extractor.sampleTime
                    if (timeUs < 0L) break
                    times += timeUs
                    if (!extractor.advance()) break
                }
                if (times.size < 2) {
                    return ImportValidationResult.NoRead(
                        reason = ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
                        message = "Recorded video needs at least two timestamped frames.",
                    )
                }
                if (!times.isStrictlyIncreasing()) {
                    return ImportValidationResult.NoRead(
                        reason = ImportNoReadReason.INVALID_METADATA,
                        message = "Recorded video presentation timestamps are not strictly increasing.",
                    )
                }
                ImportValidationResult.Success(times.toList())
            } catch (_: RuntimeException) {
                ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.INVALID_METADATA,
                    message = "Recorded video sample timestamps could not be probed safely.",
                )
            } finally {
                extractor.release()
            }
        }

        private fun MediaMetadataRetriever.extractInt(key: Int): Int? =
            extractMetadata(key)?.toIntOrNull()

        private fun MediaMetadataRetriever.extractLong(key: Int): Long? =
            extractMetadata(key)?.toLongOrNull()

        private fun List<Long>.isStrictlyIncreasing(): Boolean =
            zipWithNext().all { (a, b) -> b > a }
    }
}

private sealed interface AndroidImportVideoSource {
    fun setDataSource(context: Context, retriever: MediaMetadataRetriever)

    data class ContentUri(val uri: Uri) : AndroidImportVideoSource {
        override fun setDataSource(context: Context, retriever: MediaMetadataRetriever) {
            retriever.setDataSource(context.applicationContext, uri)
        }
    }

    data class AppFile(val file: File) : AndroidImportVideoSource {
        override fun setDataSource(context: Context, retriever: MediaMetadataRetriever) {
            retriever.setDataSource(file.absolutePath)
        }
    }
}
