package com.speedball.app.decode

import android.annotation.SuppressLint
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.os.Build
import java.io.File
import kotlin.math.ceil
import kotlin.math.max

data class DecodeWorkBounds(
    val maximumSamples: Int,
    val timeoutMillis: Long,
)

data class FrameSamplingPlan(
    val indices: List<Int>,
    val presentationTimeMicros: List<Long>,
)

enum class FrameExtractionMethod {
    INDEX,
    PRESENTATION_TIME,
}

sealed interface FrameProofResult {
    data class Success(val sampledFrames: List<DecodedFrameSample>) : FrameProofResult
    data class Failure(val reason: DecodeFailure, val message: String) : FrameProofResult
}

/** Reads app-created burst video metadata and reconciles frames to SENSOR_TIMESTAMP timing. */
class BurstVideoDecoder {
    fun decode(
        outputFile: File,
        rawSensorTimestampsNanos: List<Long>,
        requestedFps: Int,
        requestedDurationMillis: Long,
    ): DecodeOutcome {
        val bounds = buildDecodeWorkBounds(requestedDurationMillis, requestedFps)
        val metadata = when (val read = readMetadata(outputFile, requestedFps, bounds)) {
            is MetadataValidation.Success -> read.metadata
            is MetadataValidation.Failure -> return DecodeOutcome.Failure(read.reason, read.message)
        }
        val frameProof = when (val proof = proveSampleFrames(outputFile, metadata, Build.VERSION.SDK_INT)) {
            is FrameProofResult.Success -> proof.sampledFrames
            is FrameProofResult.Failure -> return DecodeOutcome.Failure(proof.reason, proof.message)
        }
        return when (val reconciled = reconcileFrameTimestamps(metadata, rawSensorTimestampsNanos, requestedFps)) {
            is DecodeOutcome.Success -> reconciled.copy(
                diagnostics = reconciled.diagnostics.copy(sampledFrames = frameProof),
            )
            is DecodeOutcome.Failure -> reconciled
            DecodeOutcome.Cancelled -> DecodeOutcome.Cancelled
        }
    }

    private fun readMetadata(
        outputFile: File,
        requestedFps: Int,
        bounds: DecodeWorkBounds,
    ): MetadataValidation {
        if (!outputFile.exists()) {
            return MetadataValidation.Failure(DecodeFailure.OUTPUT_FILE_MISSING, "Recorder output file is missing.")
        }
        if (outputFile.length() <= 0L) {
            return MetadataValidation.Failure(DecodeFailure.OUTPUT_FILE_EMPTY, "Recorder output file is empty.")
        }

        var primaryFailure: MetadataValidation.Failure? = null
        var releaseFailure: MetadataValidation.Failure? = null
        var raw: RawDecodedVideoMetadata? = null
        var extractor: MediaExtractor? = null
        try {
            extractor = MediaExtractor()
            extractor.setDataSource(outputFile.absolutePath)
            val videoTrack = extractor.findVideoTrack()
            if (videoTrack == null) {
                primaryFailure = MetadataValidation.Failure(DecodeFailure.NO_VIDEO_TRACK, "No video track was found in the recorder output.")
            } else {
                val format = extractor.getTrackFormat(videoTrack)
                extractor.selectTrack(videoTrack)
                val pts = mutableListOf<Long>()
                while (true) {
                    val sampleTime = extractor.sampleTime
                    if (sampleTime < 0L) break
                    pts += sampleTime
                    if (pts.size > bounds.maximumSamples) {
                        primaryFailure = MetadataValidation.Failure(
                            DecodeFailure.DECODE_WORK_LIMIT_EXCEEDED,
                            "Decoded sample count exceeded the bounded work limit.",
                        )
                        break
                    }
                    extractor.advance()
                }
                if (primaryFailure == null) {
                    raw = RawDecodedVideoMetadata(
                        mimeType = format.stringOrNull(MediaFormat.KEY_MIME),
                        width = format.intOrNull(MediaFormat.KEY_WIDTH),
                        height = format.intOrNull(MediaFormat.KEY_HEIGHT),
                        durationMicros = format.longOrNull(MediaFormat.KEY_DURATION),
                        presentationTimeMicros = pts,
                    )
                }
            }
        } catch (exception: RuntimeException) {
            primaryFailure = MetadataValidation.Failure(
                DecodeFailure.INVALID_VIDEO_METADATA,
                "Video metadata could not be read: ${exception.message ?: exception.javaClass.simpleName}.",
            )
        } finally {
            releaseFailure = runCatching { extractor?.release() }
                .exceptionOrNull()
                ?.let { MetadataValidation.Failure(DecodeFailure.RESOURCE_RELEASE_FAILED, "MediaExtractor release failed.") }
        }
        val resolved = resolveDecodeTerminalFailure(primaryFailure?.reason, releaseFailure?.reason)
        if (resolved != null) {
            return when (resolved) {
                primaryFailure?.reason -> primaryFailure
                else -> releaseFailure!!
            }
        }

        return validateDecodedVideoMetadata(raw, requestedFps)
    }

    @SuppressLint("NewApi")
    private fun proveSampleFrames(
        outputFile: File,
        metadata: DecodedVideoMetadata,
        sdkInt: Int,
    ): FrameProofResult {
        val plan = buildFrameSamplingPlan(metadata.frameCount, metadata.presentationTimeMicros)
            ?: return FrameProofResult.Failure(DecodeFailure.FRAME_COUNT_TOO_LOW, "At least three frames are required for non-adjacent frame proof.")
        val method = frameExtractionMethodForSdk(sdkInt)
            ?: return FrameProofResult.Failure(DecodeFailure.UNSUPPORTED_DECODER_API, "The Android decoder API level cannot safely prove frame extraction.")

        var primaryFailure: FrameProofResult.Failure? = null
        var releaseFailure: FrameProofResult.Failure? = null
        val samples = mutableListOf<DecodedFrameSample>()
        var retriever: MediaMetadataRetriever? = null
        try {
            retriever = MediaMetadataRetriever()
            retriever.setDataSource(outputFile.absolutePath)
            plan.indices.forEachIndexed { position, index ->
                val bitmap = when (method) {
                    FrameExtractionMethod.INDEX -> retriever.getFrameAtIndex(index)
                    FrameExtractionMethod.PRESENTATION_TIME -> retriever.getFrameAtTime(
                        plan.presentationTimeMicros[position],
                        MediaMetadataRetriever.OPTION_CLOSEST,
                    )
                }
                if (bitmap == null || bitmap.width != metadata.width || bitmap.height != metadata.height) {
                    primaryFailure = FrameProofResult.Failure(DecodeFailure.FRAME_EXTRACTION_FAILED, "A sampled frame could not be decoded at the expected dimensions.")
                    bitmap?.recycle()
                    return@forEachIndexed
                }
                samples += DecodedFrameSample(
                    frameIndex = index,
                    width = bitmap.width,
                    height = bitmap.height,
                    presentationTimeMicros = plan.presentationTimeMicros[position],
                )
                bitmap.recycle()
            }
        } catch (exception: RuntimeException) {
            primaryFailure = FrameProofResult.Failure(
                DecodeFailure.FRAME_EXTRACTION_FAILED,
                "Sample frame extraction failed: ${exception.message ?: exception.javaClass.simpleName}.",
            )
        } finally {
            releaseFailure = runCatching { retriever?.release() }
                .exceptionOrNull()
                ?.let { FrameProofResult.Failure(DecodeFailure.RESOURCE_RELEASE_FAILED, "MediaMetadataRetriever release failed.") }
        }
        val resolved = resolveDecodeTerminalFailure(primaryFailure?.reason, releaseFailure?.reason)
        if (resolved != null) {
            return when (resolved) {
                primaryFailure?.reason -> primaryFailure
                else -> releaseFailure!!
            }
        }

        return if (samples.size == plan.indices.size) {
            FrameProofResult.Success(samples)
        } else {
            FrameProofResult.Failure(DecodeFailure.FRAME_EXTRACTION_FAILED, "Not all sampled frames could be decoded.")
        }
    }
}

fun buildDecodeWorkBounds(
    requestedDurationMillis: Long,
    requestedFps: Int,
): DecodeWorkBounds {
    require(requestedFps > 0) { "FPS must be positive." }
    val requestedMillis = max(1L, requestedDurationMillis)
    val expectedFrames = ceil((requestedMillis / 1_000.0) * requestedFps).toInt()
    val margin = max(requestedFps, 30)
    return DecodeWorkBounds(
        maximumSamples = expectedFrames + margin,
        timeoutMillis = max(10_000L, requestedMillis * 4L),
    )
}

fun buildFrameSamplingPlan(
    frameCount: Int,
    presentationTimeMicros: List<Long>,
): FrameSamplingPlan? {
    if (frameCount < MINIMUM_RECONCILABLE_FRAME_COUNT || presentationTimeMicros.size < frameCount) return null
    return FrameSamplingPlan(
        indices = listOf(0, frameCount - 1),
        presentationTimeMicros = listOf(presentationTimeMicros.first(), presentationTimeMicros[frameCount - 1]),
    )
}

fun frameExtractionMethodForSdk(sdkInt: Int): FrameExtractionMethod? =
    when {
        sdkInt >= Build.VERSION_CODES.P -> FrameExtractionMethod.INDEX
        sdkInt >= Build.VERSION_CODES.O -> FrameExtractionMethod.PRESENTATION_TIME
        else -> null
    }

private fun MediaExtractor.findVideoTrack(): Int? {
    for (index in 0 until trackCount) {
        val format = getTrackFormat(index)
        val mime = format.stringOrNull(MediaFormat.KEY_MIME)
        if (mime?.startsWith("video/") == true) return index
    }
    return null
}

private fun MediaFormat.stringOrNull(key: String): String? =
    if (containsKey(key)) getString(key) else null

private fun MediaFormat.intOrNull(key: String): Int? =
    if (containsKey(key)) getInteger(key) else null

private fun MediaFormat.longOrNull(key: String): Long? =
    if (containsKey(key)) getLong(key) else null
