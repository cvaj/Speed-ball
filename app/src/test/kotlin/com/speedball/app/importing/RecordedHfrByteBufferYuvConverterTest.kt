package com.speedball.app.importing

import android.media.MediaCodecInfo
import java.nio.ByteBuffer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecordedHfrByteBufferYuvConverterTest {
    @Test
    fun convertsTightNv12ToArgbFrame() {
        val buffer = nv12(width = 4, height = 2, stride = 4, sliceHeight = 2) { _, _ -> 235 }

        val frame = assertSuccess(
            RecordedHfrByteBufferYuvConverter.convert(
                buffer = buffer,
                frameIndex = 7,
                ptsUs = 12_345,
                sourceWidth = 4,
                sourceHeight = 2,
                targetWidth = 4,
                targetHeight = 2,
                colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                stride = 4,
                sliceHeight = 2,
            ),
        )

        assertEquals(7, frame.frameIndex)
        assertEquals(12_345_000L, frame.presentationTimestampNanos)
        assertTrue(frame.argbPixels.all { it.red() >= 250 && it.green() >= 250 && it.blue() >= 250 })
    }

    @Test
    fun convertsPaddedNv12WithoutReadingPaddingAsPixels() {
        val buffer = nv12(width = 4, height = 2, stride = 8, sliceHeight = 4) { x, _ ->
            if (x < 2) 235 else 16
        }

        val frame = assertSuccess(
            RecordedHfrByteBufferYuvConverter.convert(
                buffer = buffer,
                frameIndex = 0,
                ptsUs = 1,
                sourceWidth = 4,
                sourceHeight = 2,
                targetWidth = 4,
                targetHeight = 2,
                colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                stride = 8,
                sliceHeight = 4,
            ),
        )

        assertTrue(frame.argbPixels[0].red() >= 250)
        assertTrue(frame.argbPixels[1].red() >= 250)
        assertTrue(frame.argbPixels[2].red() <= 3)
        assertTrue(frame.argbPixels[3].red() <= 3)
    }

    @Test
    fun convertsPaddedI420WithoutAssumingTightPlanes() {
        val buffer = i420(width = 4, height = 2, stride = 8, sliceHeight = 4) { x, _ ->
            if (x >= 2) 235 else 16
        }

        val frame = assertSuccess(
            RecordedHfrByteBufferYuvConverter.convert(
                buffer = buffer,
                frameIndex = 1,
                ptsUs = 2,
                sourceWidth = 4,
                sourceHeight = 2,
                targetWidth = 4,
                targetHeight = 2,
                colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar,
                stride = 8,
                sliceHeight = 4,
            ),
        )

        assertTrue(frame.argbPixels[0].red() <= 3)
        assertTrue(frame.argbPixels[1].red() <= 3)
        assertTrue(frame.argbPixels[2].red() >= 250)
        assertTrue(frame.argbPixels[3].red() >= 250)
    }

    @Test
    fun mapsKnownSourceBlockDuringNonOneToOneDownscale() {
        val sourceWidth = 1920
        val sourceHeight = 1080
        val targetWidth = 1280
        val targetHeight = 720
        val buffer = nv12(width = sourceWidth, height = sourceHeight, stride = sourceWidth, sliceHeight = sourceHeight) { x, _ ->
            if (x >= 960) 235 else 16
        }

        val frame = assertSuccess(
            RecordedHfrByteBufferYuvConverter.convert(
                buffer = buffer,
                frameIndex = 2,
                ptsUs = 3,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                stride = sourceWidth,
                sliceHeight = sourceHeight,
            ),
        )

        assertTrue(frame.argbPixels[360 * targetWidth + 639].red() <= 3)
        assertTrue(frame.argbPixels[360 * targetWidth + 640].red() >= 250)
    }

    @Test
    fun rejectsUnsupportedNv21LikeFormatAndInvalidCapacity() {
        val buffer = nv12(width = 4, height = 2, stride = 4, sliceHeight = 2) { _, _ -> 235 }

        assertNoRead(
            RecordedHfrByteBufferYuvConverter.convert(
                buffer = buffer,
                frameIndex = 0,
                ptsUs = 0,
                sourceWidth = 4,
                sourceHeight = 2,
                targetWidth = 4,
                targetHeight = 2,
                colorFormat = 17,
                stride = 4,
                sliceHeight = 2,
            ),
            "unsupported",
        )
        assertNoRead(
            RecordedHfrByteBufferYuvConverter.convert(
                buffer = ByteBuffer.allocate(4),
                frameIndex = 0,
                ptsUs = 0,
                sourceWidth = 4,
                sourceHeight = 2,
                targetWidth = 4,
                targetHeight = 2,
                colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                stride = 4,
                sliceHeight = 2,
            ),
            "too small",
        )
    }

    @Test
    fun rejectsMissingOrInvalidStrideAndSliceHeight() {
        val buffer = ByteBuffer.allocate(32)

        assertNoRead(
            RecordedHfrByteBufferYuvConverter.convert(
                buffer = buffer,
                frameIndex = 0,
                ptsUs = 0,
                sourceWidth = 4,
                sourceHeight = 2,
                targetWidth = 4,
                targetHeight = 2,
                colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                stride = -1,
                sliceHeight = 2,
            ),
            "invalid",
        )
        assertNoRead(
            RecordedHfrByteBufferYuvConverter.convert(
                buffer = buffer,
                frameIndex = 0,
                ptsUs = 0,
                sourceWidth = 4,
                sourceHeight = 2,
                targetWidth = 4,
                targetHeight = 2,
                colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar,
                stride = 4,
                sliceHeight = -1,
            ),
            "invalid",
        )
    }

    private fun nv12(
        width: Int,
        height: Int,
        stride: Int,
        sliceHeight: Int,
        yValue: (x: Int, y: Int) -> Int,
    ): ByteBuffer {
        val buffer = ByteBuffer.allocate(stride * sliceHeight + stride * ((sliceHeight + 1) / 2))
        for (y in 0 until height) {
            for (x in 0 until width) {
                buffer.put(y * stride + x, yValue(x, y).toByte())
            }
        }
        val chromaBase = stride * sliceHeight
        for (y in 0 until (height + 1) / 2) {
            for (x in 0 until width step 2) {
                val index = chromaBase + y * stride + x
                buffer.put(index, 128.toByte())
                buffer.put(index + 1, 128.toByte())
            }
        }
        return buffer
    }

    private fun i420(
        width: Int,
        height: Int,
        stride: Int,
        sliceHeight: Int,
        yValue: (x: Int, y: Int) -> Int,
    ): ByteBuffer {
        val chromaStride = (stride + 1) / 2
        val chromaSliceHeight = (sliceHeight + 1) / 2
        val yBytes = stride * sliceHeight
        val buffer = ByteBuffer.allocate(yBytes + chromaStride * chromaSliceHeight * 2)
        for (y in 0 until height) {
            for (x in 0 until width) {
                buffer.put(y * stride + x, yValue(x, y).toByte())
            }
        }
        val uBase = yBytes
        val vBase = uBase + chromaStride * chromaSliceHeight
        for (index in 0 until chromaStride * chromaSliceHeight) {
            buffer.put(uBase + index, 128.toByte())
            buffer.put(vBase + index, 128.toByte())
        }
        return buffer
    }

    private fun assertSuccess(result: ImportValidationResult<ImportVideoFrame>): ImportVideoFrame =
        assertInstanceOf(ImportValidationResult.Success::class.java, result).value as ImportVideoFrame

    private fun assertNoRead(result: ImportValidationResult<ImportVideoFrame>, expected: String) {
        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertTrue(noRead.message.contains(expected, ignoreCase = true), noRead.message)
    }

    private fun Int.red(): Int = (this shr 16) and 0xff
    private fun Int.green(): Int = (this shr 8) and 0xff
    private fun Int.blue(): Int = this and 0xff
}
