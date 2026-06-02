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
import com.speedball.app.measurement.VisualEstimateConfidence
import com.speedball.app.measurement.VisualEstimateFramePipelineConfig
import com.speedball.app.measurement.VisualEstimateNoReadReason
import com.speedball.app.measurement.VisualEstimateOutcome
import com.speedball.app.ui.MeasurementResultUiState
import com.speedball.app.ui.measurementResultUiLines
import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportEstimatePipelineTest {
    @Test
    fun importedFramesRunThroughExistingVisualEstimatePipeline() {
        val frames = ImportVideoFrameSequence(
            listOf(
                frame(0, x = 0, pts = 0L),
                frame(1, x = 3, pts = 10_000_000L),
                frame(2, x = 6, pts = 20_000_000L),
                frame(3, x = 21, pts = 70_000_000L),
            ),
        )
        val outcome = ImportEstimatePipeline.estimate(
            frames = frames,
            timing = containerTiming(frames),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = pipelineConfig(),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome, outcome.toString())
        assertEquals(68.18182, success.milesPerHour, 0.0001)
        assertEquals(EstimateTimingBasis.IMPORT_CONTAINER_PRESENTATION_TIMESTAMPS, success.diagnostics.timingBasis)
        assertEquals(VisualEstimateConfidence.LOW, success.diagnostics.confidence)
        assertTrue(success.diagnostics.assumptions.any { it.contains("re-encode") })
        assertTrue(success.diagnostics.assumptions.any { it.contains("calibrated image plane") })
    }

    @Test
    fun importedEstimateCanFollowOneDirectionalBallTrackThroughStaticDecoys() {
        val frames = ImportVideoFrameSequence(
            listOf(
                frameWithStaticDecoy(0, movingX = 0, pts = 0L),
                frameWithStaticDecoy(1, movingX = 3, pts = 10_000_000L),
                frameWithStaticDecoy(2, movingX = 6, pts = 20_000_000L),
                frameWithStaticDecoy(3, movingX = 21, pts = 70_000_000L),
            ),
        )
        val outcome = ImportEstimatePipeline.estimate(
            frames = frames,
            timing = containerTiming(frames),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = pipelineConfig(allowDirectionalCandidateSelection = true),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(68.18182, success.milesPerHour, 0.0001)
        assertEquals(4, success.diagnostics.detectionCount)
    }

    @Test
    fun importNoReadUsesExistingEstimateUiSuppressionPath() {
        val frames = ImportVideoFrameSequence(listOf(frame(0, x = 0)))
        val outcome = ImportEstimatePipeline.estimate(
            frames = frames,
            timing = containerTiming(
                ImportVideoFrameSequence(
                    listOf(frame(0, x = 0, pts = 0L), frame(1, x = 3, pts = 10_000_000L)),
                ),
            ),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = pipelineConfig(),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, outcome)
        assertEquals(VisualEstimateNoReadReason.BAD_TIMESTAMPS, noRead.reason)
        assertEquals(EstimateTimingBasis.IMPORT_CONTAINER_PRESENTATION_TIMESTAMPS, noRead.diagnostics?.timingBasis)
        assertEquals(VisualEstimateConfidence.LOW, noRead.diagnostics?.confidence)
        val joined = measurementResultUiLines(MeasurementResultUiState.EstimateOutcome(noRead)).joinToString("\n")
        assertTrue(joined.contains("result=estimate-no-read"))
        assertNoResultValues(joined)
    }

    @Test
    fun badCalibrationAndMissingDetectionRemainNoReadWithoutValues() {
        val frames = ImportVideoFrameSequence(
            listOf(
                frame(0, x = 0, pts = 0L),
                frame(1, x = 3, pts = 10_000_000L),
                frame(2, x = 6, pts = 20_000_000L),
                frame(3, x = 21, pts = 70_000_000L),
            ),
        )
        val timing = containerTiming(frames)
        val badCalibration = ImportEstimatePipeline.estimate(
            frames = frames,
            timing = timing,
            calibration = MeasurementCalibrationState(pointA = null, pointB = null, knownDistanceFeet = null),
            config = pipelineConfig(),
        )
        val blankFrames = ImportEstimatePipeline.estimate(
            frames = ImportVideoFrameSequence(
                listOf(blankFrame(0, 0L), blankFrame(1, 10_000_000L), blankFrame(2, 20_000_000L), blankFrame(3, 70_000_000L)),
            ),
            timing = timing,
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = pipelineConfig(),
        )

        assertEquals(VisualEstimateNoReadReason.BAD_CALIBRATION, assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, badCalibration).reason)
        assertEquals(VisualEstimateNoReadReason.DETECTION_FAILED, assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, blankFrames).reason)
        assertNoResultValues(badCalibration.toString())
        assertNoResultValues(blankFrames.toString())
    }

    @Test
    fun importedVisualDeltaTimingProvenanceReachesFinalOutcome() {
        val frames = ImportVideoFrameSequence(
            listOf(frame(0, x = 0), frame(1, x = 3), frame(2, x = 6), frame(3, x = 21)),
        )
        val outcome = ImportEstimatePipeline.estimate(
            frames = frames,
            timing = visualDeltaTiming(),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = pipelineConfig(),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(EstimateTimingBasis.IMPORT_VISUAL_FRAME_DELTA_INFERENCE, success.diagnostics.timingBasis)
        assertEquals(VisualEstimateConfidence.LOW, success.diagnostics.confidence)
        assertTrue(success.diagnostics.assumptions.any { it.contains("user-declared frame interval") })
        assertTrue(success.diagnostics.assumptions.any { it.contains("constant in-plane velocity") })
    }

    @Test
    fun recordedCaptureTimingProvenanceReachesFinalOutcome() {
        val frames = ImportVideoFrameSequence(
            listOf(frame(0, x = 0), frame(1, x = 3), frame(2, x = 6), frame(3, x = 9)),
        )
        val timing = assertInstanceOf(
            ImportValidationResult.Success::class.java,
            ImportTimingReconciler.reconcileRecordedCaptureFrameInterval(
                frameCount = frames.frames.size,
                frameIntervalSeconds = 0.01,
            ),
        ).value as ImportTimingReconciliation

        val outcome = ImportEstimatePipeline.estimate(
            frames = frames,
            timing = timing,
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = pipelineConfig(),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(EstimateTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL, success.diagnostics.timingBasis)
        assertEquals(VisualEstimateConfidence.LOW, success.diagnostics.confidence)
        assertTrue(success.diagnostics.assumptions.any { it.contains("MediaRecorder") })
        assertTrue(success.diagnostics.assumptions.any { it.contains("bias speed low") })
    }

    private fun containerTiming(frames: ImportVideoFrameSequence): ImportTimingReconciliation =
        assertInstanceOf(
            ImportValidationResult.Success::class.java,
            ImportTimingReconciler.reconcileContainerPresentationTimestamps(frames),
        ).value as ImportTimingReconciliation

    private fun visualDeltaTiming(): ImportTimingReconciliation =
        assertInstanceOf(
            ImportValidationResult.Success::class.java,
            ImportTimingReconciler.reconcile(
                samples = listOf(
                    ImportTrackSample(0, centroidX = 0.0, centroidY = 0.0, apparentDiameterPx = 10.0, presentationTimestampNanos = null),
                    ImportTrackSample(1, centroidX = 3.0, centroidY = 0.0, apparentDiameterPx = 10.0, presentationTimestampNanos = null),
                    ImportTrackSample(2, centroidX = 6.0, centroidY = 0.0, apparentDiameterPx = 10.0, presentationTimestampNanos = null),
                    ImportTrackSample(3, centroidX = 21.0, centroidY = 0.0, apparentDiameterPx = 10.0, presentationTimestampNanos = null),
                ),
                config = ImportTimingReconcilerConfig(
                    knownFrameIntervalSeconds = 0.01,
                    intervalIsUserDeclared = true,
                ),
            ),
        ).value as ImportTimingReconciliation

    private fun pipelineConfig(
        allowDirectionalCandidateSelection: Boolean = false,
    ): VisualEstimateFramePipelineConfig =
        VisualEstimateFramePipelineConfig(
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
                        maxFrameCount = 20,
                        maxThresholdPixels = FRAME_WIDTH * FRAME_HEIGHT,
                        maxComponentsPerFrame = FRAME_WIDTH * FRAME_HEIGHT,
                        maxOperationsPerFrame = FRAME_WIDTH * FRAME_HEIGHT * 20,
                    ),
                ),
                maxFrameToFrameJumpPx = 100.0,
                allowDirectionalCandidateSelection = allowDirectionalCandidateSelection,
            ),
        )

    private fun frame(index: Int, x: Int, pts: Long? = null): ImportVideoFrame {
        val pixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT) { 0xff000000.toInt() }
        pixels[BALL_Y * FRAME_WIDTH + x] = GREEN
        return ImportVideoFrame(index, pts, FRAME_WIDTH, FRAME_HEIGHT, pixels)
    }

    private fun blankFrame(index: Int, pts: Long?): ImportVideoFrame =
        ImportVideoFrame(index, pts, FRAME_WIDTH, FRAME_HEIGHT, IntArray(FRAME_WIDTH * FRAME_HEIGHT) { 0xff000000.toInt() })

    private fun frameWithStaticDecoy(index: Int, movingX: Int, pts: Long): ImportVideoFrame {
        val pixels = IntArray(FRAME_WIDTH * FRAME_HEIGHT) { 0xff000000.toInt() }
        pixels[BALL_Y * FRAME_WIDTH + movingX] = GREEN
        pixels[BALL_Y * FRAME_WIDTH + (FRAME_WIDTH - 1)] = GREEN
        return ImportVideoFrame(index, pts, FRAME_WIDTH, FRAME_HEIGHT, pixels)
    }

    private fun calibration(pixels: Double, feet: Double): MeasurementCalibrationState =
        MeasurementCalibrationState(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(pixels, 0.0),
            knownDistanceFeet = feet,
        )

    private fun assertNoResultValues(text: String) {
        assertFalse(text.contains("m" + "ph", ignoreCase = true))
        assertFalse(text.contains("ang" + "le", ignoreCase = true))
        assertFalse(text.contains("tra" + "jectory", ignoreCase = true))
        assertFalse(text.contains("car" + "ry", ignoreCase = true))
        assertFalse(text.contains("apex", ignoreCase = true))
        assertFalse(text.contains("hang", ignoreCase = true))
    }

    private companion object {
        const val FRAME_WIDTH = 30
        const val FRAME_HEIGHT = 5
        const val BALL_Y = 2
        const val GREEN = 0xff00ff00.toInt()
    }
}
