package com.speedball.app.importing

import com.speedball.app.measurement.BlobDetectionConfig
import com.speedball.app.measurement.EstimateTimingBasis
import com.speedball.app.measurement.FrameProcessingBounds
import com.speedball.app.measurement.HsvColor
import com.speedball.app.measurement.HsvThreshold
import com.speedball.app.measurement.HsvTolerance
import com.speedball.app.measurement.MeasurementCalibrationState
import com.speedball.app.measurement.RegionOfInterest
import com.speedball.app.measurement.TrackExtractionConfig
import com.speedball.app.measurement.VisualEstimateCandidateFrame
import com.speedball.app.measurement.VisualEstimateConfidence
import com.speedball.app.measurement.VisualEstimateFramePipelineConfig
import com.speedball.app.measurement.VisualEstimateNoReadReason
import com.speedball.app.measurement.VisualEstimateOutcome
import com.speedball.app.measurement.VisualEstimatePipelineConfig
import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class RecordedHfrStreamingEstimateTest {
    @Test
    fun streamingScansAllFramesButRetainsOnlyCandidateProofAndBlobRecords() {
        val frames = listOf(
            blankFrame(0),
            blankFrame(1),
            blankFrame(2),
            ballFrame(index = 40, x = 2),
            ballFrame(index = 43, x = 11),
            ballFrame(index = 46, x = 20),
            ballFrame(index = 49, x = 29),
        )

        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(frames),
            config = streamingConfig(maxProofFrames = 3),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, result.outcome, result.outcome.toString())
        assertEquals(7, result.scannedFrameCount)
        assertEquals(4, result.retainedCandidateFrameCount)
        assertEquals(4, result.candidateBlobCount)
        assertEquals(4, result.selectedSampleCount)
        assertEquals(3, result.proofThumbnails.size)
        assertTrue(result.proofThumbnails.all { it.thumbnailWidth <= 16 && it.thumbnailHeight <= 9 })
        assertEquals(listOf(3, 3, 3), result.timing?.frameStepMultipliers)
        assertEquals(EstimateTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL, success.diagnostics.timingBasis)
        assertEquals(VisualEstimateConfidence.LOW, success.diagnostics.confidence)
        assertFalse(
            VisualEstimateCandidateFrame::class.java.declaredFields.any { it.type == IntArray::class.java },
            "Retained candidate frame records must not expose full-frame ARGB pixels.",
        )
    }

    @Test
    fun retainedOriginalIndexesChangeSpeedInsteadOfCompactRenumbering() {
        val nonConsecutive = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                listOf(
                    ballFrame(index = 40, x = 2),
                    ballFrame(index = 43, x = 11),
                    ballFrame(index = 46, x = 20),
                    ballFrame(index = 49, x = 29),
                ),
            ),
            config = streamingConfig(),
        )
        val consecutive = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                listOf(
                    ballFrame(index = 40, x = 2),
                    ballFrame(index = 41, x = 11),
                    ballFrame(index = 42, x = 20),
                    ballFrame(index = 43, x = 29),
                ),
            ),
            config = streamingConfig(),
        )

        val nonConsecutiveSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, nonConsecutive.outcome)
        val consecutiveSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, consecutive.outcome)
        assertEquals(listOf(3, 3, 3), nonConsecutive.timing?.frameStepMultipliers)
        assertEquals(listOf(1, 1, 1), consecutive.timing?.frameStepMultipliers)
        assertEquals(27.2727, nonConsecutiveSuccess.milesPerHour, 0.01)
        assertEquals(81.8181, consecutiveSuccess.milesPerHour, 0.01)
    }

    @Test
    fun containerPtsDeltaModeUsesSparseWindowTimingInsteadOfConsecutiveIndexes() {
        val indexMode = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                listOf(
                    ballFrame(index = 0, x = 2, ptsNanos = 1_000_000_000L),
                    ballFrame(index = 1, x = 11, ptsNanos = 1_025_000_000L),
                    ballFrame(index = 2, x = 20, ptsNanos = 1_050_000_000L),
                    ballFrame(index = 3, x = 29, ptsNanos = 1_075_000_000L),
                ),
            ),
            config = streamingConfig(timingMode = RecordedHfrStreamingTimingMode.FRAME_INDEX_INTERVAL),
        )
        val ptsMode = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                listOf(
                    ballFrame(index = 0, x = 2, ptsNanos = 1_000_000_000L),
                    ballFrame(index = 1, x = 11, ptsNanos = 1_025_000_000L),
                    ballFrame(index = 2, x = 20, ptsNanos = 1_050_000_000L),
                    ballFrame(index = 3, x = 29, ptsNanos = 1_075_000_000L),
                ),
            ),
            config = streamingConfig(timingMode = RecordedHfrStreamingTimingMode.CONTAINER_PTS_DELTAS),
        )

        val indexSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, indexMode.outcome)
        val ptsSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, ptsMode.outcome)
        assertEquals(ImportTimingBasis.RECORDED_CONTAINER_PRESENTATION_TIMESTAMPS, ptsMode.timing?.basis)
        assertEquals(0.025, ptsMode.timing?.timestampGapSummary?.medianGapSeconds ?: 0.0, 1.0e-12)
        assertEquals(81.8181, indexSuccess.milesPerHour, 0.01)
        assertEquals(27.2727, ptsSuccess.milesPerHour, 0.01)
        assertEquals(EstimateTimingBasis.RECORDED_CONTAINER_PRESENTATION_TIMESTAMPS, ptsSuccess.diagnostics.timingBasis)
    }

    @Test
    fun containerPtsDeltaModeFailsLoudWhenWindowFrameLacksPts() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                listOf(
                    ballFrame(index = 0, x = 2, ptsNanos = 1_000_000_000L),
                    ballFrame(index = 1, x = 11, ptsNanos = null),
                    ballFrame(index = 2, x = 20, ptsNanos = 1_050_000_000L),
                    ballFrame(index = 3, x = 29, ptsNanos = 1_075_000_000L),
                ),
            ),
            config = streamingConfig(timingMode = RecordedHfrStreamingTimingMode.CONTAINER_PTS_DELTAS),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, result.outcome)
        assertEquals(VisualEstimateNoReadReason.BAD_TIMESTAMPS, noRead.reason)
        assertTrue(noRead.message.contains("container PTS"))
    }


    @Test
    fun oneToThreeCandidateFramesNoReadWithCountsAndNoSpeed() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(
                listOf(
                    blankFrame(0),
                    ballFrame(index = 1, x = 2),
                    ballFrame(index = 2, x = 11),
                    ballFrame(index = 3, x = 20),
                ),
            ),
            config = streamingConfig(),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, result.outcome)
        assertEquals(VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS, noRead.reason)
        assertEquals(4, result.scannedFrameCount)
        assertEquals(3, result.retainedCandidateFrameCount)
        assertEquals(3, noRead.diagnostics?.candidateFrameCount)
        assertEquals(0, noRead.diagnostics?.selectedSampleCount)
        assertFalse(noRead.toString().contains("mph", ignoreCase = true))
    }

    @Test
    fun scanBoundAndCancellationFailLoudAndCloseSource() {
        val boundedSource = FakeFrameSource(List(10) { blankFrame(it) })
        val bounded = RecordedHfrStreamingEstimate.estimate(
            source = boundedSource,
            config = streamingConfig(maxScannedFrames = 3),
        )
        val cancelledSource = FakeFrameSource(List(10) { blankFrame(it) })
        val cancelled = RecordedHfrStreamingEstimate.estimate(
            source = cancelledSource,
            config = streamingConfig(),
            cancellationSignal = ImportCancellationSignal { true },
        )

        assertEquals(VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED, assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, bounded.outcome).reason)
        assertEquals(3, bounded.scannedFrameCount)
        assertTrue(boundedSource.closed)
        assertEquals(VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED, assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, cancelled.outcome).reason)
        assertEquals(0, cancelled.scannedFrameCount)
        assertTrue(cancelledSource.closed)
    }

    @Test
    fun retainedCandidateCapReturnsResourceNoReadWithoutCatchingOom() {
        val result = RecordedHfrStreamingEstimate.estimate(
            source = FakeFrameSource(List(8) { index -> ballFrame(index = index, x = 2 + index) }),
            config = streamingConfig(maxRetainedCandidateFrames = 4),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, result.outcome)
        assertEquals(VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED, noRead.reason)
        assertEquals(4, result.retainedCandidateFrameCount)
        assertTrue(result.proofThumbnails.size <= 4)
    }

    private class FakeFrameSource(
        private val frames: List<ImportVideoFrame>,
    ) : ImportFrameSource {
        var closed: Boolean = false
            private set
        private var index: Int = 0

        override fun nextFrame(): ImportVideoFrame? =
            frames.getOrNull(index++)

        override fun close() {
            closed = true
        }
    }

    private fun streamingConfig(
        maxScannedFrames: Int = 120,
        maxRetainedCandidateFrames: Int = 40,
        maxProofFrames: Int = 8,
        timingMode: RecordedHfrStreamingTimingMode = RecordedHfrStreamingTimingMode.FRAME_INDEX_INTERVAL,
    ): RecordedHfrStreamingEstimateConfig =
        RecordedHfrStreamingEstimateConfig(
            frameIntervalSeconds = 1.0 / 120.0,
            calibration = calibration(pixels = 9.0, feet = 1.0),
            framePipelineConfig = VisualEstimateFramePipelineConfig(
                trackConfig = TrackExtractionConfig(
                    detectorConfig = BlobDetectionConfig(
                        threshold = HsvThreshold(
                            center = HsvColor(120.0, 1.0, 1.0),
                            tolerance = HsvTolerance(10.0, 0.1, 0.1),
                        ),
                        roi = RegionOfInterest(0, 0, FRAME_WIDTH, FRAME_HEIGHT),
                        minAreaPx = 1,
                        maxAreaPx = 10,
                        bounds = FrameProcessingBounds(
                            maxWidth = FRAME_WIDTH,
                            maxHeight = FRAME_HEIGHT,
                            maxPixels = FRAME_WIDTH * FRAME_HEIGHT,
                            maxFrameCount = 120,
                            maxThresholdPixels = FRAME_WIDTH * FRAME_HEIGHT,
                            maxComponentsPerFrame = FRAME_WIDTH * FRAME_HEIGHT,
                            maxOperationsPerFrame = FRAME_WIDTH * FRAME_HEIGHT * 20,
                        ),
                    ),
                    maxFrameToFrameJumpPx = 100.0,
                    allowDirectionalCandidateSelection = true,
                ),
                estimateConfig = VisualEstimatePipelineConfig(minEstimateMilesPerHour = 1.0),
            ),
            maxScannedFrames = maxScannedFrames,
            maxRetainedCandidateFrames = maxRetainedCandidateFrames,
            maxProofFrames = maxProofFrames,
            proofThumbnailMaxWidth = 16,
            proofThumbnailMaxHeight = 9,
            timingMode = timingMode,
        )

    private fun calibration(pixels: Double, feet: Double): MeasurementCalibrationState =
        MeasurementCalibrationState(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(pixels, 0.0),
            knownDistanceFeet = feet,
        )

    private fun ballFrame(index: Int, x: Int, ptsNanos: Long? = null): ImportVideoFrame {
        val pixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT) { BLACK }
        pixels[BALL_Y * FRAME_WIDTH + x] = GREEN
        return ImportVideoFrame(
            frameIndex = index,
            presentationTimestampNanos = ptsNanos,
            width = FRAME_WIDTH,
            height = FRAME_HEIGHT,
            argbPixels = pixels,
        )
    }

    private fun blankFrame(index: Int): ImportVideoFrame =
        ImportVideoFrame(
            frameIndex = index,
            presentationTimestampNanos = null,
            width = FRAME_WIDTH,
            height = FRAME_HEIGHT,
            argbPixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT) { BLACK },
        )

    private companion object {
        const val FRAME_WIDTH = 40
        const val FRAME_HEIGHT = 9
        const val BALL_Y = 4
        const val BLACK = 0xff000000.toInt()
        const val GREEN = 0xff00ff00.toInt()
    }
}
