package com.speedball.app.measurement

import com.speedball.core.measurement.MeasurementOptions
import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MeasurementPipelineTest {
    @Test
    fun syntheticProvenTimingPipelineProducesDeterministicResult() {
        val outcome = MeasurementPipeline.measureWithProvenTiming(
            sequence = TimedFrameSequence(
                listOf(
                    frameWithRedPixels(10, 10, setOf(1 to 5), 0.0),
                    frameWithRedPixels(10, 10, setOf(2 to 4), 0.1),
                    frameWithRedPixels(10, 10, setOf(3 to 3), 0.2),
                ),
            ),
            calibration = MeasurementCalibrationState(
                pointA = ImagePoint(0.0, 0.0),
                pointB = ImagePoint(10.0, 0.0),
                knownDistanceFeet = 10.0,
            ),
            timingProof = SyntheticMeasurementTimingProof(),
            config = pipelineConfig(),
        )

        assertTrue(outcome is MeasurementRunOutcome.Success)
        val success = outcome as MeasurementRunOutcome.Success
        assertEquals(3, success.detectionCount)
        assertEquals(9.64, success.measurement.milesPerHour, 0.02)
        assertEquals(45.0, success.measurement.launchAngleDegrees, 1.0e-9)
        assertTrue(success.trajectory.carryMeters > 0.0)
    }

    @Test
    fun productionPathIsNoReadAndCannotDisplaySpeed() {
        val outcome = MeasurementPipeline.currentProductionNoRead()
        val rendered = outcome.toString()

        assertEquals(MeasurementRunFailure.UNPROVEN_TIMING, outcome.reason)
        assertFalse(rendered.contains("mph", ignoreCase = true))
    }

    @Test
    fun errorPathsFailLoudWithoutPartialSpeed() {
        assertNoRead(
            MeasurementPipeline.measureWithProvenTiming(
                sequence = TimedFrameSequence(listOf(frameWithRedPixels(10, 10, setOf(1 to 1), 0.0))),
                calibration = validCalibration(),
                timingProof = SyntheticMeasurementTimingProof(),
                config = pipelineConfig(),
            ),
            MeasurementRunFailure.INSUFFICIENT_DETECTIONS,
        )
        assertNoRead(
            MeasurementPipeline.measureWithProvenTiming(
                sequence = goodSequence(),
                calibration = validCalibration().copy(knownDistanceFeet = 0.0),
                timingProof = SyntheticMeasurementTimingProof(),
                config = pipelineConfig(),
            ),
            MeasurementRunFailure.BAD_CALIBRATION,
        )
        assertNoRead(
            MeasurementPipeline.measureWithProvenTiming(
                sequence = TimedFrameSequence(
                    listOf(
                        frameWithRedPixels(10, 10, setOf(1 to 5), 0.0),
                        frameWithRedPixels(10, 10, setOf(2 to 4), 0.1),
                        frameWithRedPixels(10, 10, setOf(3 to 3), 0.1),
                    ),
                ),
                calibration = validCalibration(),
                timingProof = SyntheticMeasurementTimingProof(),
                config = pipelineConfig(),
            ),
            MeasurementRunFailure.BAD_FRAME_SEQUENCE,
        )
        assertNoRead(
            MeasurementPipeline.measureWithProvenTiming(
                sequence = goodSequence(),
                calibration = validCalibration(),
                timingProof = SyntheticMeasurementTimingProof(),
                config = pipelineConfig().copy(
                    measurementOptions = MeasurementOptions(
                        maxRmsResidualPx = 0.0001,
                        minTimeSpreadSecondsSquared = 1.0e-6,
                    ),
                ),
            ),
            MeasurementRunFailure.MEASUREMENT_REJECTED,
        )
    }

    private fun assertNoRead(outcome: MeasurementRunOutcome, reason: MeasurementRunFailure) {
        assertTrue(outcome is MeasurementRunOutcome.NoRead)
        val noRead = outcome as MeasurementRunOutcome.NoRead
        assertEquals(reason, noRead.reason)
        assertFalse(noRead.toString().contains("mph", ignoreCase = true))
    }

    private fun goodSequence(): TimedFrameSequence =
        TimedFrameSequence(
            listOf(
                frameWithRedPixels(10, 10, setOf(1 to 5), 0.0),
                frameWithRedPixels(10, 10, setOf(2 to 4), 0.1),
                frameWithRedPixels(10, 10, setOf(4 to 2), 0.2),
            ),
        )

    private fun validCalibration(): MeasurementCalibrationState =
        MeasurementCalibrationState(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(10.0, 0.0),
            knownDistanceFeet = 10.0,
        )

    private fun pipelineConfig(): MeasurementPipelineConfig =
        MeasurementPipelineConfig(
            trackConfig = TrackExtractionConfig(
                detectorConfig = defaultConfig(10, 10),
                maxFrameToFrameJumpPx = 10.0,
            ),
            measurementOptions = MeasurementOptions(
                maxRmsResidualPx = 2.0,
                minTimeSpreadSecondsSquared = 1.0e-6,
            ),
        )
}
