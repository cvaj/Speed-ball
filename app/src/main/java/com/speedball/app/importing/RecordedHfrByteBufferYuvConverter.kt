package com.speedball.app.importing

import android.media.MediaCodecInfo
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

/**
 * Converts one MediaCodec byte-buffer YUV420 output into one working-resolution
 * ARGB frame.
 *
 * The caller must pass the codec-reported stride and slice height. This unit is
 * deliberately strict because an incorrect layout can produce a plausible but
 * wrong speed estimate.
 */
object RecordedHfrByteBufferYuvConverter {
    private const val QCOM_YUV420_SEMIPLANAR = 0x7fa30c00.toInt()

    fun convert(
        buffer: ByteBuffer,
        frameIndex: Int,
        ptsUs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
        targetWidth: Int,
        targetHeight: Int,
        colorFormat: Int,
        stride: Int,
        sliceHeight: Int,
    ): ImportValidationResult<ImportVideoFrame> {
        if (
            frameIndex < 0 ||
            ptsUs < 0L ||
            sourceWidth <= 0 ||
            sourceHeight <= 0 ||
            targetWidth <= 0 ||
            targetHeight <= 0 ||
            stride < sourceWidth ||
            sliceHeight < sourceHeight
        ) {
            return noRead("Recorded-HFR byte-buffer YUV layout metadata is invalid.")
        }
        val layout = when (colorFormat) {
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> YuvLayout.I420
            MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
            QCOM_YUV420_SEMIPLANAR,
            -> YuvLayout.NV12
            else -> return noRead("Recorded-HFR decoder output color format is unsupported: $colorFormat.")
        }
        val requiredCapacity = layout.requiredCapacity(stride, sliceHeight)
        if (requiredCapacity <= 0 || buffer.capacity() < requiredCapacity) {
            return noRead(
                "Recorded-HFR decoder output buffer is too small for reported layout: " +
                    "capacity=${buffer.capacity()} required=$requiredCapacity stride=$stride sliceHeight=$sliceHeight.",
            )
        }

        val pixels = IntArray(targetWidth * targetHeight)
        for (targetY in 0 until targetHeight) {
            val sourceY = min(sourceHeight - 1, (targetY.toLong() * sourceHeight / targetHeight).toInt())
            for (targetX in 0 until targetWidth) {
                val sourceX = min(sourceWidth - 1, (targetX.toLong() * sourceWidth / targetWidth).toInt())
                val (yValue, uValue, vValue) = layout.readYuv(
                    buffer = buffer,
                    x = sourceX,
                    y = sourceY,
                    stride = stride,
                    sliceHeight = sliceHeight,
                )
                pixels[targetY * targetWidth + targetX] = yuvToArgb(yValue, uValue, vValue)
            }
        }
        return ImportValidationResult.Success(
            ImportVideoFrame(
                frameIndex = frameIndex,
                presentationTimestampNanos = ptsUs * 1_000L,
                width = targetWidth,
                height = targetHeight,
                argbPixels = pixels,
            ),
        )
    }

    private fun yuvToArgb(yValue: Int, uValue: Int, vValue: Int): Int {
        val c = yValue - 16
        val d = uValue - 128
        val e = vValue - 128
        val r = clamp((298 * c + 409 * e + 128) shr 8)
        val g = clamp((298 * c - 100 * d - 208 * e + 128) shr 8)
        val b = clamp((298 * c + 516 * d + 128) shr 8)
        return -0x1000000 or (r shl 16) or (g shl 8) or b
    }

    private fun clamp(value: Int): Int = min(255, max(0, value))

    private fun ByteBuffer.unsigned(index: Int): Int = get(index).toInt() and 0xff

    private fun noRead(message: String): ImportValidationResult.NoRead =
        ImportValidationResult.NoRead(ImportNoReadReason.UNSUPPORTED_MEDIA, message)

    private enum class YuvLayout {
        I420 {
            override fun requiredCapacity(stride: Int, sliceHeight: Int): Int {
                val yBytes = stride.toLong() * sliceHeight.toLong()
                val chromaStride = (stride + 1L) / 2L
                val chromaSliceHeight = (sliceHeight + 1L) / 2L
                val total = yBytes + chromaStride * chromaSliceHeight * 2L
                return if (total in 1..Int.MAX_VALUE) total.toInt() else -1
            }

            override fun readYuv(buffer: ByteBuffer, x: Int, y: Int, stride: Int, sliceHeight: Int): YuvSample {
                val chromaStride = (stride + 1) / 2
                val chromaSliceHeight = (sliceHeight + 1) / 2
                val yBase = 0
                val uBase = stride * sliceHeight
                val vBase = uBase + chromaStride * chromaSliceHeight
                val chromaIndex = (y / 2) * chromaStride + x / 2
                return YuvSample(
                    y = buffer.unsigned(yBase + y * stride + x),
                    u = buffer.unsigned(uBase + chromaIndex),
                    v = buffer.unsigned(vBase + chromaIndex),
                )
            }
        },
        NV12 {
            override fun requiredCapacity(stride: Int, sliceHeight: Int): Int {
                val total = stride.toLong() * sliceHeight.toLong() + stride.toLong() * ((sliceHeight + 1L) / 2L)
                return if (total in 1..Int.MAX_VALUE) total.toInt() else -1
            }

            override fun readYuv(buffer: ByteBuffer, x: Int, y: Int, stride: Int, sliceHeight: Int): YuvSample {
                val yIndex = y * stride + x
                val chromaBase = stride * sliceHeight
                val chromaIndex = chromaBase + (y / 2) * stride + (x / 2) * 2
                return YuvSample(
                    y = buffer.unsigned(yIndex),
                    u = buffer.unsigned(chromaIndex),
                    v = buffer.unsigned(chromaIndex + 1),
                )
            }
        };

        abstract fun requiredCapacity(stride: Int, sliceHeight: Int): Int
        abstract fun readYuv(buffer: ByteBuffer, x: Int, y: Int, stride: Int, sliceHeight: Int): YuvSample
    }

    private data class YuvSample(val y: Int, val u: Int, val v: Int)
}
