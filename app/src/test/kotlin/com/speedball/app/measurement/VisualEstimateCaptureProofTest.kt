package com.speedball.app.measurement

import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class VisualEstimateCaptureProofTest {
    @Test
    fun proofFrameSelectionIsBoundedAndDeterministic() {
        assertEquals(emptyList<Int>(), VisualEstimateCaptureProofBuilder.selectProofFrameIndices(0, 8))
        assertEquals((0 until 4).toList(), VisualEstimateCaptureProofBuilder.selectProofFrameIndices(4, 8))
        assertEquals((0 until 8).toList(), VisualEstimateCaptureProofBuilder.selectProofFrameIndices(8, 8))
        assertEquals(listOf(0, 7, 13, 20, 27, 34, 40, 47), VisualEstimateCaptureProofBuilder.selectProofFrameIndices(48, 8))
    }

    @Test
    fun proofContractsDoNotCarryEstimateValuesOrFullFrameBuffers() {
        val source = Files.readAllBytes(sourcePath("VisualEstimateCaptureProof.kt")).toString(Charsets.UTF_8)

        assertFalse(Regex("val\\s+\\w*(mph|speed|velocity|angle|distance)\\w*", RegexOption.IGNORE_CASE).containsMatchIn(source))
        assertFalse(source.contains("DirectArgbFrame"))
        assertTrue(source.contains("thumbnailArgbPixels"))
    }

    @Test
    fun traceCandidatesComeFromSameDetectorConfig() {
        val config = framePipelineConfig(allowDirectionalCandidateSelection = true)
        val frame = ballFrame(x = 2, timestampSeconds = 0.0)
        val result = VisualEstimateFramePipeline.estimateFromFramesWithTrace(
            sequence = TimedFrameSequence(
                listOf(
                    frame,
                    ballFrame(x = 6, timestampSeconds = 1.0 / 120.0),
                    ballFrame(x = 12, timestampSeconds = 2.0 / 120.0),
                    ballFrame(x = 20, timestampSeconds = 3.0 / 120.0),
                ),
            ),
            calibration = MeasurementCalibrationState(
                pointA = ImagePoint(0.0, 0.0),
                pointB = ImagePoint(4.0, 0.0),
                knownDistanceFeet = 1.0,
            ),
            config = config,
        )
        val detectorOutcome = BlobDetector.detectCandidates(frame, config.trackConfig.detectorConfig)
        val detectorCandidates = (detectorOutcome as BlobCandidateDetectionOutcome.Success).blobs
        val traceFrame = result.detectorTrace.frames.first()

        assertEquals(detectorCandidates, traceFrame.candidates)
        assertEquals(detectorCandidates.size, traceFrame.candidateCount)
        assertTrue(result.detectorTrace.detectorSummary.selectedSampleCount >= 4)
    }

    @Test
    fun zeroCandidateNoReadBuildsEmptyDetectorSummaryAndProofMessage() {
        val config = framePipelineConfig(allowDirectionalCandidateSelection = true)
        val frames = List(4) { index -> blankFrame(timestampSeconds = index / 120.0) }
        val result = VisualEstimateFramePipeline.estimateFromFramesWithTrace(
            sequence = TimedFrameSequence(frames),
            calibration = MeasurementCalibrationState(
                pointA = ImagePoint(0.0, 0.0),
                pointB = ImagePoint(4.0, 0.0),
                knownDistanceFeet = 1.0,
            ),
            config = config,
        )
        val proof = VisualEstimateCaptureProofBuilder.build(
            attemptId = 42L,
            frames = frames,
            frameAvailableCallbackCount = 4,
            captureResultCallbackCount = 4,
            uniqueSensorTimestampCount = 4,
            readbackWidth = FRAME_WIDTH,
            readbackHeight = FRAME_HEIGHT,
            trace = result.detectorTrace.withOutcome(result.outcome),
        )

        assertEquals(4, proof.capturedFrameCount)
        assertEquals(0, proof.detectorSummary.candidateFrameCount)
        assertEquals(0, proof.detectorSummary.candidateBlobCount)
        assertEquals(0, proof.detectorSummary.selectedSampleCount)
        assertEquals(4, proof.frames.size)
        assertTrue(result.outcome is VisualEstimateOutcome.NoRead)
    }

    @Test
    fun proofUsesTraceOverlaysForRetainedThumbnails() {
        val config = framePipelineConfig(allowDirectionalCandidateSelection = true)
        val frames = listOf(
            ballFrame(x = 2, timestampSeconds = 0.0),
            ballFrame(x = 6, timestampSeconds = 1.0 / 120.0),
            ballFrame(x = 12, timestampSeconds = 2.0 / 120.0),
            ballFrame(x = 20, timestampSeconds = 3.0 / 120.0),
        )
        val result = VisualEstimateFramePipeline.estimateFromFramesWithTrace(
            sequence = TimedFrameSequence(frames),
            calibration = MeasurementCalibrationState(
                pointA = ImagePoint(0.0, 0.0),
                pointB = ImagePoint(4.0, 0.0),
                knownDistanceFeet = 1.0,
            ),
            config = config,
        )
        val proof = VisualEstimateCaptureProofBuilder.build(
            attemptId = 43L,
            frames = frames,
            frameAvailableCallbackCount = 4,
            captureResultCallbackCount = 4,
            uniqueSensorTimestampCount = 4,
            readbackWidth = FRAME_WIDTH,
            readbackHeight = FRAME_HEIGHT,
            trace = result.detectorTrace.withOutcome(result.outcome),
        )

        assertTrue(proof.frames.all { it.candidates.isNotEmpty() })
        assertTrue(proof.frames.all { it.selected != null })
        assertTrue(proof.frames.all { it.thumbnailArgbPixels.isNotEmpty() })
    }

    private fun framePipelineConfig(
        allowDirectionalCandidateSelection: Boolean,
    ): VisualEstimateFramePipelineConfig =
        VisualEstimateFramePipelineConfig(
            trackConfig = TrackExtractionConfig(
                detectorConfig = BlobDetectionConfig(
                    threshold = HsvThreshold(
                        center = HsvColor(0.0, 1.0, 1.0),
                        tolerance = HsvTolerance(12.0, 0.2, 0.2),
                    ),
                    roi = RegionOfInterest(0, 0, FRAME_WIDTH, FRAME_HEIGHT),
                    minAreaPx = 1,
                    maxAreaPx = 20,
                    bounds = FrameProcessingBounds(
                        maxWidth = FRAME_WIDTH,
                        maxHeight = FRAME_HEIGHT,
                        maxPixels = FRAME_WIDTH * FRAME_HEIGHT,
                        maxFrameCount = 48,
                        maxThresholdPixels = FRAME_WIDTH * FRAME_HEIGHT,
                        maxComponentsPerFrame = FRAME_WIDTH * FRAME_HEIGHT,
                        maxOperationsPerFrame = FRAME_WIDTH * FRAME_HEIGHT * 20,
                    ),
                ),
                maxFrameToFrameJumpPx = 40.0,
                allowDirectionalCandidateSelection = allowDirectionalCandidateSelection,
            ),
            timing = VisualEstimateFrameTiming.RequireVisualFrameDeltas(1.0 / 120.0),
        )

    private fun ballFrame(x: Int, timestampSeconds: Double): RgbFrame =
        RgbFrame(
            width = FRAME_WIDTH,
            height = FRAME_HEIGHT,
            argbPixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT).also { pixels ->
                pixels[BALL_Y * FRAME_WIDTH + x] = RED_ARGB
            },
            timestampSeconds = timestampSeconds,
        )

    private fun blankFrame(timestampSeconds: Double): RgbFrame =
        RgbFrame(
            width = FRAME_WIDTH,
            height = FRAME_HEIGHT,
            argbPixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT) { BLACK_ARGB },
            timestampSeconds = timestampSeconds,
        )

    private fun sourcePath(fileName: String): Path =
        listOf(
            Path.of("app/src/main/java/com/speedball/app/measurement/$fileName"),
            Path.of("src/main/java/com/speedball/app/measurement/$fileName"),
        ).first { Files.exists(it) }

    private companion object {
        const val FRAME_WIDTH = 32
        const val FRAME_HEIGHT = 18
        const val BALL_Y = 8
        const val RED_ARGB = -0x10000
        const val BLACK_ARGB = -0x1000000
    }
}
