package com.speedball.app.measurement

import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class VisualEstimateFramePipelineTest {
    @Test
    fun boundedRgbFramesProduceLabeledEstimateThroughRealDetector() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    ballFrame(x = 0, timestampSeconds = 0.00),
                    ballFrame(x = 3, timestampSeconds = 0.01),
                    ballFrame(x = 6, timestampSeconds = 0.02),
                    ballFrame(x = 21, timestampSeconds = 0.07),
                ),
            ),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = framePipelineConfig(),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(68.18182, success.milesPerHour, 0.0001)
        assertEquals(VisualEstimateConfidence.LOW, success.diagnostics.confidence)
        assertTrue(success.diagnostics.warnings.any { it.contains("skipped/coalesced") })
    }

    @Test
    fun framePipelineFallsBackToVisualFrameDeltasWhenTimestampsAreUnavailable() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    ballFrame(x = 0, timestampSeconds = 0.0),
                    ballFrame(x = 3, timestampSeconds = 0.0),
                    ballFrame(x = 6, timestampSeconds = 0.0),
                    ballFrame(x = 21, timestampSeconds = 0.0),
                ),
            ),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = framePipelineConfig(
                timing = VisualEstimateFrameTiming.PreferRealTimestamps(
                    visualFrameIntervalSeconds = 0.01,
                ),
            ),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(68.18182, success.milesPerHour, 0.0001)
        assertEquals(EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE, success.diagnostics.timingBasis)
        assertTrue(success.diagnostics.warnings.any { it.contains("Visual frame-delta inference") })
    }

    @Test
    fun framePipelineCanSelfCalibrateFromDetectedBallDiameter() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    ballFrame(x = 0, timestampSeconds = 0.00),
                    ballFrame(x = 3, timestampSeconds = 0.01),
                    ballFrame(x = 6, timestampSeconds = 0.02),
                    ballFrame(x = 21, timestampSeconds = 0.07),
                ),
            ),
            calibration = MeasurementCalibrationState(pointA = null, pointB = null, knownDistanceFeet = null),
            config = framePipelineConfig(
                estimateConfig = VisualEstimatePipelineConfig(knownBallDiameterFeet = 1.0 / 12.0),
            ),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(17.04545, success.milesPerHour, 0.0001)
        assertEquals(EstimateScaleBasis.BALL_DIAMETER_SELF_CALIBRATION, success.diagnostics.scaleBasis)
        assertTrue(success.diagnostics.assumptions.any { it.contains("entered ball diameter") })
        assertTrue(success.diagnostics.assumptions.any { it.contains("short-axis") })
    }

    @Test
    fun framePipelineSelfCalibrationUsesShortAxisForMotionBlurredBlob() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    elongatedBallFrame(left = 0, timestampSeconds = 0.00),
                    elongatedBallFrame(left = 3, timestampSeconds = 0.01),
                    elongatedBallFrame(left = 6, timestampSeconds = 0.02),
                    elongatedBallFrame(left = 21, timestampSeconds = 0.07),
                ),
            ),
            calibration = MeasurementCalibrationState(pointA = null, pointB = null, knownDistanceFeet = null),
            config = framePipelineConfig(
                estimateConfig = VisualEstimatePipelineConfig(knownBallDiameterFeet = 1.0),
                bounds = testBounds(width = BLUR_FRAME_WIDTH, height = BLUR_FRAME_HEIGHT),
                width = BLUR_FRAME_WIDTH,
                height = BLUR_FRAME_HEIGHT,
            ),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(68.18182, success.milesPerHour, 0.0001)
        assertEquals(EstimateScaleBasis.BALL_DIAMETER_SELF_CALIBRATION, success.diagnostics.scaleBasis)
    }

    @Test
    fun framePipelineNoReadsWithoutRealTimestampsOrVisualFrameInterval() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    ballFrame(x = 0, timestampSeconds = 0.0),
                    ballFrame(x = 3, timestampSeconds = 0.0),
                    ballFrame(x = 6, timestampSeconds = 0.0),
                    ballFrame(x = 21, timestampSeconds = 0.0),
                ),
            ),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = framePipelineConfig(),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, outcome)
        assertEquals(VisualEstimateNoReadReason.BAD_TIMESTAMPS, noRead.reason)
        assertEquals(EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE, noRead.diagnostics?.timingBasis)
        assertFalse(noRead.toString().contains("mph", ignoreCase = true))
    }

    @Test
    fun detectionNoReadReportsVisualFrameDeltaTimingBasisWhenThatRouteWasConfigured() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, emptySet(), 0.0),
                    frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, emptySet(), 0.0),
                    frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, emptySet(), 0.0),
                    frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, emptySet(), 0.0),
                ),
            ),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = framePipelineConfig(
                timing = VisualEstimateFrameTiming.RequireVisualFrameDeltas(
                    frameIntervalSeconds = 0.01,
                ),
            ),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, outcome)
        assertEquals(VisualEstimateNoReadReason.DETECTION_FAILED, noRead.reason)
        assertEquals(EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE, noRead.diagnostics?.timingBasis)
    }

    @Test
    fun ambiguousFrameNoReadsWithoutPartialValues() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    ballFrame(x = 0, timestampSeconds = 0.00),
                    frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, setOf(3 to BALL_Y, 24 to BALL_Y), 0.01),
                    ballFrame(x = 6, timestampSeconds = 0.02),
                    ballFrame(x = 21, timestampSeconds = 0.07),
                ),
            ),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = framePipelineConfig(),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, outcome)
        assertEquals(VisualEstimateNoReadReason.DETECTION_FAILED, noRead.reason)
        assertFalse(noRead.toString().contains("mph", ignoreCase = true))
    }

    @Test
    fun directionalCandidateSelectionNoReadsWhenTrackReverses() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    ballFrame(x = 0, timestampSeconds = 0.00),
                    ballFrame(x = 3, timestampSeconds = 0.01),
                    ballFrame(x = 2, timestampSeconds = 0.02),
                    ballFrame(x = 5, timestampSeconds = 0.03),
                ),
            ),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = framePipelineConfig(allowDirectionalCandidateSelection = true),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, outcome)
        assertEquals(VisualEstimateNoReadReason.AMBIGUOUS_TRACK, noRead.reason)
        assertTrue(noRead.message.contains("coherent horizontal ball track"))
        assertFalse(noRead.toString().contains("mph", ignoreCase = true))
        assertFalse(noRead.toString().contains("angle", ignoreCase = true))
        assertFalse(noRead.toString().contains("trajectory", ignoreCase = true))
    }

    @Test
    fun seededDirectionalSelectionUsesMotionWindowAndIgnoresOtherColorBlobs() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    seededMotionFrame(ballX = 6, timestampSeconds = 0.00),
                    seededMotionFrame(ballX = 6, timestampSeconds = 0.01),
                    seededMotionFrame(ballX = 6, timestampSeconds = 0.02),
                    seededMotionFrame(ballX = 9, timestampSeconds = 0.03),
                    seededMotionFrame(ballX = 12, timestampSeconds = 0.04),
                    seededMotionFrame(ballX = 15, timestampSeconds = 0.05),
                    seededMotionFrame(ballX = 18, timestampSeconds = 0.06),
                ),
            ),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = framePipelineConfig(
                allowDirectionalCandidateSelection = true,
                seedPoint = ImagePoint(6.0, BALL_Y.toDouble()),
                seedSearchRadiusPx = 3.0,
            ),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(68.18182, success.milesPerHour, 0.0001)
        assertEquals(5, success.diagnostics.frameCount)
        assertEquals(5, success.diagnostics.detectionCount)
    }

    @Test
    fun highVelocityStraightFlightSelectionChoosesHitPathOverSlowYellowMotion() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    hitAndSlowDecoyFrame(frameIndex = 0, hitX = 10, hitY = 18, slowX = 48),
                    hitAndSlowDecoyFrame(frameIndex = 1, hitX = 15, hitY = 17, slowX = 49),
                    hitAndSlowDecoyFrame(frameIndex = 2, hitX = 20, hitY = 16, slowX = 50),
                    hitAndSlowDecoyFrame(frameIndex = 3, hitX = 25, hitY = 15, slowX = 51),
                    hitAndSlowDecoyFrame(frameIndex = 4, hitX = 30, hitY = 14, slowX = 52),
                ),
            ),
            calibration = calibration(pixels = 8.133, feet = 1.0),
            config = framePipelineConfig(
                bounds = testBounds(width = HIT_FRAME_WIDTH, height = HIT_FRAME_HEIGHT),
                timing = VisualEstimateFrameTiming.RequireVisualFrameDeltas(1.0 / 120.0),
                width = HIT_FRAME_WIDTH,
                height = HIT_FRAME_HEIGHT,
                allowDirectionalCandidateSelection = true,
                estimateConfig = VisualEstimatePipelineConfig(minEstimateMilesPerHour = 25.0),
            ),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome, outcome.toString())
        assertEquals(51.3, success.milesPerHour, 0.6)
        assertEquals(5, success.diagnostics.detectionCount)
    }

    @Test
    fun directionalSelectionSkipsLeadingFramesBeforeBallEntersRoi() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    blankHitFrame(frameIndex = 0),
                    blankHitFrame(frameIndex = 1),
                    hitBlockFrame(frameIndex = 2, x = 10, y = 18),
                    hitBlockFrame(frameIndex = 3, x = 15, y = 17),
                    hitBlockFrame(frameIndex = 4, x = 20, y = 16),
                    hitBlockFrame(frameIndex = 5, x = 25, y = 15),
                    hitBlockFrame(frameIndex = 6, x = 30, y = 14),
                ),
            ),
            calibration = calibration(pixels = 8.133, feet = 1.0),
            config = framePipelineConfig(
                bounds = testBounds(width = HIT_FRAME_WIDTH, height = HIT_FRAME_HEIGHT),
                timing = VisualEstimateFrameTiming.RequireVisualFrameDeltas(1.0 / 120.0),
                width = HIT_FRAME_WIDTH,
                height = HIT_FRAME_HEIGHT,
                allowDirectionalCandidateSelection = true,
                estimateConfig = VisualEstimatePipelineConfig(minEstimateMilesPerHour = 25.0),
            ),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome, outcome.toString())
        assertEquals(5, success.diagnostics.detectionCount)
        assertEquals(51.3, success.milesPerHour, 0.6)
    }

    @Test
    fun fastCurvedPitchLikePathNoReadsWhenNoStraightHitWindowExists() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    hitBlockFrame(frameIndex = 0, x = 10, y = 7),
                    hitBlockFrame(frameIndex = 1, x = 15, y = 14),
                    hitBlockFrame(frameIndex = 2, x = 20, y = 19),
                    hitBlockFrame(frameIndex = 3, x = 25, y = 22),
                    hitBlockFrame(frameIndex = 4, x = 30, y = 23),
                ),
            ),
            calibration = calibration(pixels = 8.133, feet = 1.0),
            config = framePipelineConfig(
                bounds = testBounds(width = HIT_FRAME_WIDTH, height = HIT_FRAME_HEIGHT),
                timing = VisualEstimateFrameTiming.RequireVisualFrameDeltas(1.0 / 120.0),
                width = HIT_FRAME_WIDTH,
                height = HIT_FRAME_HEIGHT,
                allowDirectionalCandidateSelection = true,
                estimateConfig = VisualEstimatePipelineConfig(minEstimateMilesPerHour = 25.0),
            ),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, outcome, outcome.toString())
        assertEquals(VisualEstimateNoReadReason.AMBIGUOUS_TRACK, noRead.reason)
        assertFalse(noRead.toString().contains("mph", ignoreCase = true))
    }

    @Test
    fun belowHitSpeedThresholdNoReadsAfterCalibration() {
        val outcome = VisualEstimatePipeline.estimateWithVisualFrameDeltas(
            samples = listOf(
                VisualEstimateTrackSample(timestampSeconds = 0.0, xPx = 0.0, yPx = 0.0),
                VisualEstimateTrackSample(timestampSeconds = 0.0, xPx = 1.0, yPx = 0.0),
                VisualEstimateTrackSample(timestampSeconds = 0.0, xPx = 2.0, yPx = 0.0),
                VisualEstimateTrackSample(timestampSeconds = 0.0, xPx = 3.0, yPx = 0.0),
            ),
            calibration = calibration(pixels = 8.133, feet = 1.0),
            frameIntervalSeconds = 1.0 / 120.0,
            config = VisualEstimatePipelineConfig(minEstimateMilesPerHour = 25.0),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, outcome, outcome.toString())
        assertEquals(VisualEstimateNoReadReason.AMBIGUOUS_TRACK, noRead.reason)
        assertTrue(noRead.message.contains("speed threshold"))
    }

    @Test
    fun directionalSelectorUsesRansacConsensusForStraightHitTrack() {
        val source = Files.readAllLines(
            listOf(
                Path.of("app/src/main/java/com/speedball/app/measurement/VisualEstimateFramePipeline.kt"),
                Path.of("src/main/java/com/speedball/app/measurement/VisualEstimateFramePipeline.kt"),
            ).first { Files.exists(it) },
        ).joinToString("\n")

        assertTrue(source.contains("selectRansacStraightFlightTrack"))
        assertTrue(source.contains("RansacLineHypothesis"))
        assertTrue(source.contains("RANSAC_STRAIGHT_FLIGHT_INLIER_DISTANCE_PX"))
    }

    @Test
    fun frameBoundsNoReadBeforePixelsCanLeakIntoResult() {
        val outcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(
                listOf(
                    ballFrame(x = 0, timestampSeconds = 0.00),
                    ballFrame(x = 3, timestampSeconds = 0.01),
                    ballFrame(x = 6, timestampSeconds = 0.02),
                    ballFrame(x = 21, timestampSeconds = 0.07),
                    ballFrame(x = 24, timestampSeconds = 0.08),
                ),
            ),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            config = framePipelineConfig(
                bounds = testBounds(maxFrameCount = 4),
            ),
        )

        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, outcome)
        assertEquals(VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED, noRead.reason)
        assertFalse(noRead.toString().contains(RED_ARGB.toString()))
    }

    private fun framePipelineConfig(
        bounds: FrameProcessingBounds = testBounds(width = FRAME_WIDTH, height = FRAME_HEIGHT),
        timing: VisualEstimateFrameTiming = VisualEstimateFrameTiming.PreferRealTimestamps(),
        estimateConfig: VisualEstimatePipelineConfig = VisualEstimatePipelineConfig(),
        width: Int = FRAME_WIDTH,
        height: Int = FRAME_HEIGHT,
        allowDirectionalCandidateSelection: Boolean = false,
        seedPoint: ImagePoint? = null,
        seedSearchRadiusPx: Double? = null,
    ): VisualEstimateFramePipelineConfig =
        VisualEstimateFramePipelineConfig(
            trackConfig = TrackExtractionConfig(
                detectorConfig = defaultConfig(
                    width = width,
                    height = height,
                    bounds = bounds,
                ),
                maxFrameToFrameJumpPx = 100.0,
                allowDirectionalCandidateSelection = allowDirectionalCandidateSelection,
                seedPoint = seedPoint,
                seedSearchRadiusPx = seedSearchRadiusPx,
            ),
            estimateConfig = estimateConfig,
            timing = timing,
        )

    private fun testBounds(
        maxFrameCount: Int = 20,
        width: Int = FRAME_WIDTH,
        height: Int = FRAME_HEIGHT,
    ): FrameProcessingBounds =
        FrameProcessingBounds(
            maxWidth = width,
            maxHeight = height,
            maxPixels = width * height,
            maxFrameCount = maxFrameCount,
            maxThresholdPixels = width * height,
            maxComponentsPerFrame = width * height,
            maxOperationsPerFrame = width * height * 20,
        )

    private fun ballFrame(
        x: Int,
        timestampSeconds: Double,
    ): RgbFrame =
        frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, setOf(x to BALL_Y), timestampSeconds)

    private fun seededMotionFrame(
        ballX: Int,
        timestampSeconds: Double,
    ): RgbFrame =
        frameWithRedPixels(
            FRAME_WIDTH,
            FRAME_HEIGHT,
            buildSet {
                add(ballX to BALL_Y)
                add(0 to 0)
                add(1 to 0)
                add(2 to 0)
                add(24 to BALL_Y)
            },
            timestampSeconds,
        )

    private fun elongatedBallFrame(
        left: Int,
        timestampSeconds: Double,
    ): RgbFrame =
        frameWithRedPixels(
            width = BLUR_FRAME_WIDTH,
            height = BLUR_FRAME_HEIGHT,
            redPixels = buildSet {
                for (x in left until left + 5) {
                    for (y in 3..5) {
                        add(x to y)
                    }
                }
            },
            timestampSeconds = timestampSeconds,
        )

    private fun hitAndSlowDecoyFrame(
        frameIndex: Int,
        hitX: Int,
        hitY: Int,
        slowX: Int,
    ): RgbFrame =
        frameWithRedPixels(
            width = HIT_FRAME_WIDTH,
            height = HIT_FRAME_HEIGHT,
            redPixels = blockPixels(hitX, hitY) + blockPixels(slowX, 25),
            timestampSeconds = frameIndex / 120.0,
        )

    private fun hitBlockFrame(
        frameIndex: Int,
        x: Int,
        y: Int,
    ): RgbFrame =
        frameWithRedPixels(
            width = HIT_FRAME_WIDTH,
            height = HIT_FRAME_HEIGHT,
            redPixels = blockPixels(x, y),
            timestampSeconds = frameIndex / 120.0,
        )

    private fun blankHitFrame(frameIndex: Int): RgbFrame =
        frameWithRedPixels(
            width = HIT_FRAME_WIDTH,
            height = HIT_FRAME_HEIGHT,
            redPixels = emptySet(),
            timestampSeconds = frameIndex / 120.0,
        )

    private fun blockPixels(
        left: Int,
        top: Int,
    ): Set<Pair<Int, Int>> =
        buildSet {
            for (x in left until left + 2) {
                for (y in top until top + 2) {
                    add(x to y)
                }
            }
        }

    private fun calibration(
        pixels: Double,
        feet: Double,
    ): MeasurementCalibrationState =
        MeasurementCalibrationState(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(pixels, 0.0),
            knownDistanceFeet = feet,
        )

    private companion object {
        const val FRAME_WIDTH = 30
        const val FRAME_HEIGHT = 5
        const val BALL_Y = 2
        const val BLUR_FRAME_WIDTH = 40
        const val BLUR_FRAME_HEIGHT = 9
        const val HIT_FRAME_WIDTH = 80
        const val HIT_FRAME_HEIGHT = 32
    }
}
