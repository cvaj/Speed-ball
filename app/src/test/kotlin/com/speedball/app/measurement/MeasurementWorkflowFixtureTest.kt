package com.speedball.app.measurement

import com.speedball.app.ui.measurementOutcomeUiLines
import com.speedball.core.measurement.MeasurementOptions
import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeasurementWorkflowFixtureTest {
    @Test
    fun validWorkflowFixtureProducesDeterministicFormattedResult() {
        val outcome = MeasurementPipeline.measureWithProvenTiming(
            sequence = goodSequence(),
            calibration = validCalibrationState().toMeasurementCalibrationState(),
            timingProof = SyntheticWorkflowTimingProof(),
            config = pipelineConfig(validColorState()),
        )
        val success = assertInstanceOf(MeasurementRunOutcome.Success::class.java, outcome)
        val rendered = measurementOutcomeUiLines(success).joinToString("\n")

        assertTrue(rendered.contains("result=success"))
        assertTrue(rendered.contains("evidence=synthetic-workflow-test-only"))
        assertTrue(rendered.contains("detections=3"))
        assertTrue(rendered.contains("mph=9.6"))
        assertTrue(rendered.contains("angleDeg=45.0"))
        assertTrue(rendered.contains("trajectory"))
    }

    @Test
    fun badCalibrationReturnsNoReadWithoutValues() {
        val outcome = MeasurementPipeline.measureWithProvenTiming(
            sequence = goodSequence(),
            calibration = validCalibrationState().select(
                pointA = ImagePoint(0.0, 0.0),
                pointB = ImagePoint(10.0, 0.0),
                knownDistanceFeet = 0.0,
            ).toMeasurementCalibrationState(),
            timingProof = SyntheticWorkflowTimingProof(),
            config = pipelineConfig(validColorState()),
        )

        assertNoRead(outcome, MeasurementRunFailure.BAD_CALIBRATION)
    }

    @Test
    fun badColorOrRoiReturnsNoReadBeforeResultValues() {
        val badSample = ColorWorkflowState().selectSample(HsvColor(Double.NaN, 1.0, 1.0))
        val badRoi = ColorWorkflowState().selectSample(
            sample = HsvColor(0.0, 1.0, 1.0),
            regionOfInterest = RegionOfInterest(99, 99, 120, 120),
        )

        assertNoRead(requireNotNull(badSample.noReadOrNull(FRAME_WIDTH, FRAME_HEIGHT)), MeasurementRunFailure.DETECTION_FAILED)
        assertNoRead(requireNotNull(badRoi.noReadOrNull(FRAME_WIDTH, FRAME_HEIGHT)), MeasurementRunFailure.DETECTION_FAILED)
    }

    @Test
    fun insufficientDetectionsReturnsNoReadWithoutValues() {
        val outcome = MeasurementPipeline.measureWithProvenTiming(
            sequence = TimedFrameSequence(listOf(frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, setOf(1 to 5), 0.0))),
            calibration = validCalibrationState().toMeasurementCalibrationState(),
            timingProof = SyntheticWorkflowTimingProof(),
            config = pipelineConfig(validColorState()),
        )

        assertNoRead(outcome, MeasurementRunFailure.INSUFFICIENT_DETECTIONS)
    }

    @Test
    fun unprovenTimingWorkflowReturnsNoReadWithoutPipelineSuccess() {
        val state = MeasurementWorkflowState()
            .reduce(MeasurementWorkflowEvent.CalibrationSelected)
            .reduce(MeasurementWorkflowEvent.ColorSampleSelected)
            .reduce(MeasurementWorkflowEvent.StartCapture)

        assertNoRead(state.result, MeasurementRunFailure.UNPROVEN_TIMING)
    }

    @Test
    fun nonFiniteTimestampInputReturnsBadFrameSequenceNoRead() {
        val outcome = MeasurementPipeline.measureWithProvenTiming(
            sequence = TimedFrameSequence(
                listOf(
                    frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, setOf(1 to 5), 0.0),
                    frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, setOf(2 to 4), Double.NaN),
                    frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, setOf(3 to 3), 0.2),
                ),
            ),
            calibration = validCalibrationState().toMeasurementCalibrationState(),
            timingProof = SyntheticWorkflowTimingProof(),
            config = pipelineConfig(validColorState()),
        )

        assertNoRead(outcome, MeasurementRunFailure.BAD_FRAME_SEQUENCE)
    }

    @Test
    fun noSynthesizedSuccessGuardStillRequiresPipelineOutcomeEvent() {
        val ready = MeasurementWorkflowState()
            .reduce(MeasurementWorkflowEvent.CalibrationSelected)
            .reduce(MeasurementWorkflowEvent.ColorSampleSelected)
            .reduce(MeasurementWorkflowEvent.BoundSourceProofAvailable)
            .reduce(MeasurementWorkflowEvent.StartCapture)
        val pipelineOutcome = MeasurementPipeline.measureWithProvenTiming(
            sequence = goodSequence(),
            calibration = validCalibrationState().toMeasurementCalibrationState(),
            timingProof = SyntheticWorkflowTimingProof(),
            config = pipelineConfig(validColorState()),
        )
        val completed = ready.reduce(MeasurementWorkflowEvent.PipelineMeasurementCompleted(pipelineOutcome))

        assertInstanceOf(MeasurementRunOutcome.NoRead::class.java, ready.result)
        assertInstanceOf(MeasurementRunOutcome.Success::class.java, completed.result)
    }

    private fun pipelineConfig(colorState: ColorWorkflowState): MeasurementPipelineConfig {
        val colorReady = assertInstanceOf(
            ColorWorkflowReadiness.Ready::class.java,
            colorState.readiness(FRAME_WIDTH, FRAME_HEIGHT),
        )
        return MeasurementPipelineConfig(
            trackConfig = TrackExtractionConfig(
                detectorConfig = defaultConfig(
                    width = FRAME_WIDTH,
                    height = FRAME_HEIGHT,
                    roi = colorReady.regionOfInterest,
                ).copy(threshold = colorReady.threshold),
                maxFrameToFrameJumpPx = 10.0,
            ),
            measurementOptions = MeasurementOptions(
                maxRmsResidualPx = 2.0,
                minTimeSpreadSecondsSquared = 1.0e-6,
            ),
        )
    }

    private fun validColorState(): ColorWorkflowState =
        ColorWorkflowState().selectSample(
            sample = HsvColor(hueDegrees = 0.0, saturation = 1.0, value = 1.0),
            tolerance = HsvTolerance(hueDegrees = 8.0, saturation = 0.1, value = 0.1),
            regionOfInterest = RegionOfInterest(0, 0, FRAME_WIDTH, FRAME_HEIGHT),
        )

    private fun validCalibrationState(): CalibrationWorkflowState =
        CalibrationWorkflowState().select(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(10.0, 0.0),
            knownDistanceFeet = 10.0,
        )

    private fun goodSequence(): TimedFrameSequence =
        TimedFrameSequence(
            listOf(
                frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, setOf(1 to 5), 0.0),
                frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, setOf(2 to 4), 0.1),
                frameWithRedPixels(FRAME_WIDTH, FRAME_HEIGHT, setOf(3 to 3), 0.2),
            ),
        )

    private fun assertNoRead(outcome: MeasurementRunOutcome, reason: MeasurementRunFailure) {
        val noRead = assertInstanceOf(MeasurementRunOutcome.NoRead::class.java, outcome)
        val rendered = measurementOutcomeUiLines(noRead).joinToString("\n")

        assertTrue(rendered.contains("result=no-read"))
        assertTrue(rendered.contains("reason=$reason"))
        assertFalse(rendered.contains("m" + "ph", ignoreCase = true))
        assertFalse(rendered.contains("ang" + "le", ignoreCase = true))
        assertFalse(rendered.contains("tra" + "jectory", ignoreCase = true))
        assertFalse(rendered.contains("car" + "ry", ignoreCase = true))
        assertFalse(rendered.contains("apex", ignoreCase = true))
        assertFalse(rendered.contains("hang", ignoreCase = true))
    }

    private companion object {
        const val FRAME_WIDTH = 10
        const val FRAME_HEIGHT = 10
    }
}
