package com.speedball.app.importing

import com.speedball.app.capture.ContainerTimeWindow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    @Test
    fun androidWindowDecoderUsesExtractorCodecPathNotRetrieverFrameIndex() {
        val androidSource = readProjectFile("app/src/main/java/com/speedball/app/importing/AndroidRecordedHfrWindowFrameSource.kt")
        val mainActivity = readProjectFile("app/src/main/java/com/speedball/app/MainActivity.kt")
        val audioSource = readProjectFile("app/src/main/java/com/speedball/app/audio/AndroidImpactAudioTrigger.kt")

        assertTrue(androidSource.contains("MediaExtractor.SEEK_TO_PREVIOUS_SYNC"))
        assertFalse(androidSource.contains("MediaExtractor.SEEK_TO_CLOSEST_SYNC"))
        assertTrue(androidSource.contains("MediaCodec.createDecoderByType"))
        assertTrue(androidSource.contains("configure(format, null, null, 0)"))
        assertTrue(androidSource.contains("codec.getOutputBuffer(outputIndex)"))
        assertTrue(androidSource.contains("RecordedHfrByteBufferYuvConverter.convert"))
        assertTrue(androidSource.contains("RecordedHfrByteBufferYuvConverter.convertLuma"))
        assertTrue(androidSource.contains("sourceMotionScoutSelection"))
        assertTrue(androidSource.contains("windowFrameIndex < scout.denseStartIndex"))
        assertTrue(androidSource.contains("windowFrameIndex > scout.denseEndIndexInclusive"))
        assertTrue(androidSource.contains("window.windowStartUs"))
        assertTrue(androidSource.contains("window.windowEndUs"))
        assertTrue(androidSource.contains("maxDecodeWallClockMillis"))
        assertFalse(androidSource.contains("ImageReader"))
        assertFalse(androidSource.contains("getOutputImage"))
        assertFalse(androidSource.contains(".planes"))
        assertFalse(androidSource.contains("Bitmap"))
        assertFalse(androidSource.contains("mutableListOf<ImportVideoFrame>"))
        assertFalse(androidSource.contains("frames += imageToFrame"))
        assertTrue(audioSource.contains("AudioTimestamp.TIMEBASE_BOOTTIME"))
        assertTrue(audioSource.contains("onArmed("))
        assertTrue(audioSource.indexOf("recorder.startRecording()") < audioSource.indexOf("onArmed("))
        assertTrue(audioSource.contains("ImpactAudioStreamingDetector"))
        assertTrue(audioSource.contains("detector.feed(chunk, 0, read)"))
        assertFalse(audioSource.contains("copyOf(written)"))
        assertTrue(audioSource.contains("MediaRecorder.AudioSource.UNPROCESSED"))
        assertTrue(audioSource.contains("MediaRecorder.AudioSource.MIC"))
        assertFalse(androidSource.contains("import android.media.MediaMetadataRetriever"))
        assertFalse(androidSource.contains("getFrameAtIndex"))
        assertTrue(mainActivity.contains("AndroidRecordedHfrWindowFrameSource.create"))
        assertTrue(mainActivity.contains("sourceMotionScoutConfig = RecordedHfrMotionScoutConfig()"))
        assertTrue(mainActivity.contains("motionScoutConfig = RecordedHfrMotionScoutConfig(enabled = false)"))
        assertTrue(mainActivity.contains("autoProbeRecordedHfrByteBuffer"))
        assertTrue(mainActivity.contains("RecordedHfrByteBufferViabilityProbe.run(file)"))
        assertTrue(mainActivity.contains("RecordedHfrStreamingTimingMode.CONTAINER_PTS_DELTAS"))
        assertFalse(mainActivity.substringAfter("private fun runRecordedWindowEstimate").substringBefore("private fun reconcileImportTiming").contains("AndroidImportVideoFrameSource.create"))
    }

    @Test
    fun byteBufferViabilityProbeAvoidsPlaneApiFamily() {
        val probe = readProjectFile("app/src/main/java/com/speedball/app/importing/RecordedHfrByteBufferViabilityProbe.kt")

        assertTrue(probe.contains("configure(inputFormat, null, null, 0)"))
        assertTrue(probe.contains("codec.getOutputBuffer(outputIndex)"))
        assertTrue(probe.contains("MediaFormat.KEY_COLOR_FORMAT"))
        assertTrue(probe.contains("MediaFormat.KEY_STRIDE"))
        assertTrue(probe.contains("MediaFormat.KEY_SLICE_HEIGHT"))
        assertFalse(probe.contains("ImageReader"))
        assertFalse(probe.contains("getOutputImage"))
        assertFalse(probe.contains(".planes"))
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

    private fun readProjectFile(path: String): String =
        java.nio.file.Files.readAllLines(
            listOf(
                java.nio.file.Path.of(path),
                java.nio.file.Path.of(path.removePrefix("app/")),
            ).first { java.nio.file.Files.exists(it) },
        ).joinToString("\n")
}
