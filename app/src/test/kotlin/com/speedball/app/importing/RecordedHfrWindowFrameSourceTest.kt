package com.speedball.app.importing

import com.speedball.app.capture.ContainerTimeWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecordedHfrWindowFrameSourceTest {
    @Test
    fun emitsOnlyFramesInsideRequestedContainerPtsWindow() {
        val source = FakeSource(
            listOf(
                frame(index = 0, ptsUs = 0),
                frame(index = 1, ptsUs = 50_000),
                frame(index = 2, ptsUs = 100_000),
                frame(index = 3, ptsUs = 150_000),
                frame(index = 4, ptsUs = 250_000),
            ),
        )
        val windowSource = assertSuccess(
            RecordedHfrWindowFrameSource.create(
                upstream = source,
                window = window(startUs = 80_000, endUs = 200_000, maxFrames = 24),
            ),
        )

        val emitted = generateSequence { windowSource.nextFrame() }.toList()
        windowSource.close()

        assertEquals(listOf(2, 3), emitted.map { it.frameIndex })
        assertTrue(source.closed)
    }

    @Test
    fun maxFramesCapsWindowWithoutCountingSensorIndex() {
        val windowSource = assertSuccess(
            RecordedHfrWindowFrameSource.create(
                upstream = FakeSource(List(10) { index -> frame(index = index * 10, ptsUs = 100_000 + index * 8_333L) }),
                window = window(startUs = 100_000, endUs = 200_000, maxFrames = 3),
            ),
        )

        val emitted = generateSequence { windowSource.nextFrame() }.toList()

        assertEquals(listOf(0, 10, 20), emitted.map { it.frameIndex })
    }

    @Test
    fun missingOrNonMonotonicPtsFailsThroughExtractorResult() {
        val missingPts = assertSuccess(
            RecordedHfrWindowFrameSource.create(
                upstream = FakeSource(listOf(frame(index = 0, ptsUs = null))),
                window = window(0, 10_000, maxFrames = 3),
            ),
        )
        val nonMonotonic = assertSuccess(
            RecordedHfrWindowFrameSource.create(
                upstream = FakeSource(listOf(frame(index = 0, ptsUs = 1_000), frame(index = 1, ptsUs = 1_000))),
                window = window(0, 10_000, maxFrames = 3),
            ),
        )

        assertThrowsIllegalState(missingPts)
        assertThrowsIllegalState(nonMonotonic)
    }

    @Test
    fun decodedWindowValidatorRejectsTimeoutShortAndOutsideWindow() {
        assertNoRead(
            RecordedHfrDecodedWindowValidator.validate(
                proof = proof(emittedFrameCount = 4, timedOut = true),
                requestedMaxFrames = 24,
                minUsableFrames = 4,
                maxDecodeWallClockMillis = 500,
            ),
            "budget",
        )
        assertNoRead(
            RecordedHfrDecodedWindowValidator.validate(
                proof = proof(emittedFrameCount = 3),
                requestedMaxFrames = 24,
                minUsableFrames = 4,
                maxDecodeWallClockMillis = 500,
            ),
            "frame count",
        )
        assertNoRead(
            RecordedHfrDecodedWindowValidator.validate(
                proof = proof(emittedLastPtsUs = 250_000),
                requestedMaxFrames = 24,
                minUsableFrames = 4,
                maxDecodeWallClockMillis = 500,
            ),
            "outside",
        )
    }

    @Test
    fun decodedWindowValidatorAcceptsBoundedWindowProof() {
        val result = RecordedHfrDecodedWindowValidator.validate(
            proof = proof(),
            requestedMaxFrames = 24,
            minUsableFrames = 4,
            maxDecodeWallClockMillis = 500,
        )

        assertInstanceOf(ImportValidationResult.Success::class.java, result)
    }

    private fun assertSuccess(
        result: ImportValidationResult<RecordedHfrWindowFrameSource>,
    ): RecordedHfrWindowFrameSource =
        assertInstanceOf(
            RecordedHfrWindowFrameSource::class.java,
            assertInstanceOf(ImportValidationResult.Success::class.java, result).value,
        )

    private fun assertNoRead(result: ImportValidationResult<RecordedHfrDecodedWindowProof>, expected: String) {
        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertTrue(noRead.message.contains(expected, ignoreCase = true), noRead.message)
    }

    private fun assertThrowsIllegalState(source: RecordedHfrWindowFrameSource) {
        try {
            while (true) {
                source.nextFrame() ?: break
            }
        } catch (_: IllegalStateException) {
            return
        }
        throw AssertionError("Expected IllegalStateException")
    }

    private class FakeSource(private val frames: List<ImportVideoFrame>) : ImportFrameSource {
        var closed = false
            private set
        private var cursor = 0

        override fun nextFrame(): ImportVideoFrame? = frames.getOrNull(cursor++)

        override fun close() {
            closed = true
        }
    }

    private fun window(startUs: Long, endUs: Long, maxFrames: Int): ContainerTimeWindow =
        ContainerTimeWindow(
            windowStartUs = startUs,
            windowEndUs = endUs,
            postImpactFrameCount = maxFrames,
            preImpactMarginFrames = 0,
            maxFrames = maxFrames,
        )

    private fun proof(
        emittedFrameCount: Int = 4,
        emittedFirstPtsUs: Long? = 100_000,
        emittedLastPtsUs: Long? = 150_000,
        timedOut: Boolean = false,
    ): RecordedHfrDecodedWindowProof =
        RecordedHfrDecodedWindowProof(
            requestedWindowStartUs = 100_000,
            requestedWindowEndUs = 200_000,
            emittedFrameCount = emittedFrameCount,
            emittedFirstPtsUs = emittedFirstPtsUs,
            emittedLastPtsUs = emittedLastPtsUs,
            syncPrefixFrameCount = 6,
            decodeWallClockMillis = 120,
            timedOut = timedOut,
        )

    private fun frame(index: Int, ptsUs: Long?): ImportVideoFrame =
        ImportVideoFrame(
            frameIndex = index,
            presentationTimestampNanos = ptsUs?.times(1_000L),
            width = 2,
            height = 2,
            argbPixels = IntArray(4) { 0xff000000.toInt() },
        )
}
