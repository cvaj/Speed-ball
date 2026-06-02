package com.speedball.app.importing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportFrameExtractorTest {
    @Test
    fun extractsBoundedFramesAndReleasesSourceOnSuccess() {
        val source = FakeImportFrameSource(
            listOf(frame(0, 1_000L), frame(1, 2_000L), frame(2, 3_000L)),
        )

        val sequence = assertSuccess(ImportFrameExtractor.extract(source, defaultConfig()))

        assertEquals(3, sequence.frames.size)
        assertTrue(source.closed)
    }

    @Test
    fun cancellationNoReadsAndReleasesSource() {
        val source = FakeImportFrameSource(listOf(frame(0, 1_000L)))

        val result = ImportFrameExtractor.extract(
            source = source,
            config = defaultConfig(),
            cancellationSignal = ImportCancellationSignal { true },
        )

        assertNoRead(result, ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED)
        assertTrue(source.closed)
    }

    @Test
    fun frameLimitNoReadsAndReleasesSource() {
        val source = FakeImportFrameSource(
            listOf(frame(0, 1_000L), frame(1, 2_000L), frame(2, 3_000L)),
        )

        val result = ImportFrameExtractor.extract(source, defaultConfig(maxFrames = 2))

        assertNoRead(result, ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED)
        assertTrue(source.closed)
    }

    @Test
    fun nonMonotonicPtsNoReadsAndReleasesSource() {
        val source = FakeImportFrameSource(
            listOf(frame(0, 1_000L), frame(1, 1_000L)),
        )

        val result = ImportFrameExtractor.extract(source, defaultConfig())

        assertNoRead(result, ImportNoReadReason.INVALID_METADATA)
        assertTrue(source.closed)
    }

    @Test
    fun badFrameDimensionsNoReadBeforeSequenceReuse() {
        val source = FakeImportFrameSource(
            listOf(frame(0, 1_000L), frame(1, 2_000L, width = 3, argb = IntArray(1))),
        )

        val result = ImportFrameExtractor.extract(source, defaultConfig())

        assertNoRead(result, ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED)
        assertTrue(source.closed)
    }

    @Test
    fun sourceExceptionMapsToTypedNoReadAndReleasesSource() {
        val source = FakeImportFrameSource(
            frames = listOf(frame(0, 1_000L)),
            throwOnIndex = 1,
        )

        val result = ImportFrameExtractor.extract(source, defaultConfig())

        assertNoRead(result, ImportNoReadReason.INVALID_METADATA)
        assertTrue(source.closed)
    }

    private fun assertSuccess(
        result: ImportValidationResult<ImportVideoFrameSequence>,
    ): ImportVideoFrameSequence =
        assertInstanceOf(ImportValidationResult.Success::class.java, result).let {
            assertInstanceOf(ImportVideoFrameSequence::class.java, it.value)
        }

    private fun assertNoRead(
        result: ImportValidationResult<ImportVideoFrameSequence>,
        reason: ImportNoReadReason,
    ) {
        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertEquals(reason, noRead.reason)
    }

    private fun defaultConfig(maxFrames: Int = 10): ImportFrameExtractionConfig =
        ImportFrameExtractionConfig(
            maxFrames = maxFrames,
            maxWidth = 4,
            maxHeight = 4,
            maxTotalPixels = 16,
        )

    private fun frame(
        index: Int,
        pts: Long?,
        width: Int = 2,
        height: Int = 2,
        argb: IntArray = IntArray(width * height) { 0xff00ff00.toInt() },
    ): ImportVideoFrame =
        ImportVideoFrame(
            frameIndex = index,
            presentationTimestampNanos = pts,
            width = width,
            height = height,
            argbPixels = argb,
        )

    private class FakeImportFrameSource(
        private val frames: List<ImportVideoFrame>,
        private val throwOnIndex: Int? = null,
    ) : ImportFrameSource {
        var closed: Boolean = false
        private var index: Int = 0

        override fun nextFrame(): ImportVideoFrame? {
            if (throwOnIndex == index) {
                throw IllegalStateException("decode failed")
            }
            return frames.getOrNull(index++)
        }

        override fun close() {
            closed = true
        }
    }
}
