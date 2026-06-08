package com.speedball.app.importing

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteBuffer

/**
 * Debug-only recorded-HFR decoder probe for measuring device byte-buffer output.
 *
 * This is the Gate 2 Subtask 0 viability check: it decodes a single video
 * output buffer without a render surface and reports the actual codec output
 * layout before the production recorded-HFR source is refactored around it.
 */
object RecordedHfrByteBufferViabilityProbe {
    private const val DEQUEUE_TIMEOUT_US = 5_000L
    private const val DEFAULT_TIMEOUT_MILLIS = 1_500L
    private const val UNKNOWN_INT = -1
    private const val QCOM_YUV420_SEMIPLANAR = 0x7fa30c00.toInt()
    private const val QCOM_YUV420_PACKED_SEMIPLANAR_32M = 0x7fa30c04.toInt()

    fun run(
        file: File,
        maxWallClockMillis: Long = DEFAULT_TIMEOUT_MILLIS,
    ): ImportValidationResult<RecordedHfrByteBufferViabilityResult> {
        if (!file.isFile || file.length() <= 0L) {
            return noRead("Recorded-HFR byte-buffer probe file is missing or empty.")
        }
        if (maxWallClockMillis <= 0L) {
            return noRead("Recorded-HFR byte-buffer probe timeout must be positive.")
        }

        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        val startedAt = System.nanoTime()
        val deadlineNanos = startedAt + maxWallClockMillis * 1_000_000L
        return try {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: return noRead("Recorded-HFR byte-buffer probe found no video track.")

            val inputFormat = extractor.getTrackFormat(trackIndex)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: return noRead("Recorded-HFR byte-buffer probe video MIME type is unavailable.")
            val sourceWidth = inputFormat.safeInt(MediaFormat.KEY_WIDTH)
            val sourceHeight = inputFormat.safeInt(MediaFormat.KEY_HEIGHT)
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                return noRead("Recorded-HFR byte-buffer probe video dimensions are invalid.")
            }

            extractor.selectTrack(trackIndex)
            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(inputFormat, null, null, 0)
                start()
            }

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var latestOutputFormat = codec.outputFormat
            while (System.nanoTime() <= deadlineNanos) {
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex)
                            ?: return noRead("Recorded-HFR byte-buffer probe input buffer was unavailable.")
                        val sampleTimeUs = extractor.sampleTime
                        val sampleSize = extractor.readSampleData(inputBuffer, 0)
                        if (sampleTimeUs < 0L || sampleSize < 0) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, extractor.sampleFlags)
                            extractor.advance()
                        }
                    }
                }

                when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> latestOutputFormat = codec.outputFormat
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        latestOutputFormat = codec.outputFormat
                        val outputBuffer = codec.getOutputBuffer(outputIndex)
                        val result = buildResult(
                            outputFormat = latestOutputFormat,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                            outputBuffer = outputBuffer,
                            outputSizeBytes = bufferInfo.size,
                            ptsUs = bufferInfo.presentationTimeUs,
                            wallClockMillis = (System.nanoTime() - startedAt) / 1_000_000L,
                        )
                        codec.releaseOutputBuffer(outputIndex, false)
                        return ImportValidationResult.Success(result)
                    }
                }
            }
            noRead("Recorded-HFR byte-buffer probe exceeded the wall-clock budget.")
        } catch (error: RuntimeException) {
            noRead("Recorded-HFR byte-buffer probe failed: ${error.javaClass.simpleName}.")
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
        }
    }

    private fun buildResult(
        outputFormat: MediaFormat,
        sourceWidth: Int,
        sourceHeight: Int,
        outputBuffer: ByteBuffer?,
        outputSizeBytes: Int,
        ptsUs: Long,
        wallClockMillis: Long,
    ): RecordedHfrByteBufferViabilityResult {
        val colorFormat = outputFormat.safeInt(MediaFormat.KEY_COLOR_FORMAT)
        val stride = outputFormat.safeInt(MediaFormat.KEY_STRIDE)
        val sliceHeight = outputFormat.safeInt(MediaFormat.KEY_SLICE_HEIGHT)
        val capacityBytes = outputBuffer?.capacity() ?: UNKNOWN_INT
        val minimumPaddedBytes = minimumYuv420Bytes(sourceWidth, sourceHeight, stride, sliceHeight)
        val layout = classifyColorFormat(colorFormat)
        val manuallyConvertible =
            layout == RecordedHfrByteBufferLayout.I420 ||
                layout == RecordedHfrByteBufferLayout.NV12 ||
                layout == RecordedHfrByteBufferLayout.QCOM_SEMIPLANAR
        val capacityLooksValid = minimumPaddedBytes > 0 && capacityBytes >= minimumPaddedBytes
        val viable = manuallyConvertible && capacityLooksValid
        val verdict = when {
            viable -> RecordedHfrByteBufferViabilityVerdict.CONVERTIBLE_RAW_YUV
            layout == RecordedHfrByteBufferLayout.FLEXIBLE_YUV -> RecordedHfrByteBufferViabilityVerdict.FLEXIBLE_ONLY_UNSUPPORTED
            layout == RecordedHfrByteBufferLayout.OPAQUE_OR_COMPRESSED -> RecordedHfrByteBufferViabilityVerdict.OPAQUE_OR_UBWC_UNSUPPORTED
            !manuallyConvertible -> RecordedHfrByteBufferViabilityVerdict.UNSUPPORTED_COLOR_FORMAT
            else -> RecordedHfrByteBufferViabilityVerdict.INVALID_BUFFER_LAYOUT
        }
        return RecordedHfrByteBufferViabilityResult(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            colorFormat = colorFormat,
            colorFormatName = colorFormatName(colorFormat),
            stride = stride,
            sliceHeight = sliceHeight,
            capacityBytes = capacityBytes,
            outputSizeBytes = outputSizeBytes,
            minimumExpectedBytes = minimumPaddedBytes,
            ptsUs = ptsUs,
            wallClockMillis = wallClockMillis,
            layout = layout,
            verdict = verdict,
        )
    }

    private fun minimumYuv420Bytes(sourceWidth: Int, sourceHeight: Int, stride: Int, sliceHeight: Int): Int {
        val safeStride = if (stride > 0) stride else sourceWidth
        val safeSliceHeight = if (sliceHeight > 0) sliceHeight else sourceHeight
        if (safeStride <= 0 || safeSliceHeight <= 0) return UNKNOWN_INT
        val yBytes = safeStride.toLong() * safeSliceHeight.toLong()
        val chromaBytes = safeStride.toLong() * ((safeSliceHeight + 1) / 2L)
        val total = yBytes + chromaBytes
        return if (total in 1..Int.MAX_VALUE) total.toInt() else UNKNOWN_INT
    }

    private fun classifyColorFormat(colorFormat: Int): RecordedHfrByteBufferLayout =
        when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> RecordedHfrByteBufferLayout.I420
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> RecordedHfrByteBufferLayout.NV12
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> RecordedHfrByteBufferLayout.FLEXIBLE_YUV
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface,
            QCOM_YUV420_PACKED_SEMIPLANAR_32M,
            -> RecordedHfrByteBufferLayout.OPAQUE_OR_COMPRESSED
            QCOM_YUV420_SEMIPLANAR -> RecordedHfrByteBufferLayout.QCOM_SEMIPLANAR
            else -> RecordedHfrByteBufferLayout.UNKNOWN
        }

    private fun colorFormatName(colorFormat: Int): String =
        when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> "COLOR_FormatYUV420Planar"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> "COLOR_FormatYUV420SemiPlanar"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible -> "COLOR_FormatYUV420Flexible"
            MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface -> "COLOR_FormatSurface"
            QCOM_YUV420_SEMIPLANAR -> "QCOM_YUV420_SEMIPLANAR"
            QCOM_YUV420_PACKED_SEMIPLANAR_32M -> "QCOM_YUV420_PACKED_SEMIPLANAR_32M"
            else -> "UNKNOWN_$colorFormat"
        }

    private fun MediaFormat.safeInt(key: String): Int =
        if (containsKey(key)) getInteger(key) else UNKNOWN_INT

    private fun noRead(message: String): ImportValidationResult.NoRead =
        ImportValidationResult.NoRead(ImportNoReadReason.UNSUPPORTED_MEDIA, message)
}

/** Measured raw byte-buffer layout class from a recorded-HFR decoder probe. */
enum class RecordedHfrByteBufferLayout {
    I420,
    NV12,
    QCOM_SEMIPLANAR,
    FLEXIBLE_YUV,
    OPAQUE_OR_COMPRESSED,
    UNKNOWN,
}

/** Gate 2 Subtask 0 verdict for whether S10+ byte-buffer decode can continue. */
enum class RecordedHfrByteBufferViabilityVerdict {
    CONVERTIBLE_RAW_YUV,
    FLEXIBLE_ONLY_UNSUPPORTED,
    OPAQUE_OR_UBWC_UNSUPPORTED,
    UNSUPPORTED_COLOR_FORMAT,
    INVALID_BUFFER_LAYOUT,
}

/** Redacted S10+ decoder byte-buffer viability evidence. */
data class RecordedHfrByteBufferViabilityResult(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val colorFormat: Int,
    val colorFormatName: String,
    val stride: Int,
    val sliceHeight: Int,
    val capacityBytes: Int,
    val outputSizeBytes: Int,
    val minimumExpectedBytes: Int,
    val ptsUs: Long,
    val wallClockMillis: Long,
    val layout: RecordedHfrByteBufferLayout,
    val verdict: RecordedHfrByteBufferViabilityVerdict,
) {
    fun toLogFields(): String =
        "source=${sourceWidth}x$sourceHeight colorFormat=$colorFormat colorFormatName=$colorFormatName " +
            "stride=$stride sliceHeight=$sliceHeight capacityBytes=$capacityBytes outputSizeBytes=$outputSizeBytes " +
            "minimumExpectedBytes=$minimumExpectedBytes ptsUs=$ptsUs wallClockMs=$wallClockMillis " +
            "layout=$layout verdict=$verdict"
}
