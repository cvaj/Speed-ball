package com.speedball.app.measurement

import com.speedball.core.calibration.CalibrationFailure
import com.speedball.core.calibration.CalibrationResult
import com.speedball.core.measurement.MeasurementFailure
import com.speedball.core.measurement.MeasurementOptions
import com.speedball.core.measurement.MeasurementOutcome
import com.speedball.core.measurement.VelocityMeasurementCalculator
import com.speedball.core.physics.LaunchState
import com.speedball.core.physics.TrajectoryOptions
import com.speedball.core.physics.TrajectoryOutcome
import com.speedball.core.physics.TrajectoryPhysics

/** End-to-end configuration for the Phase 8 pure measurement pipeline. */
data class MeasurementPipelineConfig(
    val trackConfig: TrackExtractionConfig,
    val measurementOptions: MeasurementOptions = MeasurementOptions(
        maxRmsResidualPx = 2.0,
        minTimeSpreadSecondsSquared = 1.0e-6,
        maxOutlierPasses = 0,
        minOutlierRmsImprovementPx = 0.0,
    ),
    val trajectoryOptions: TrajectoryOptions = TrajectoryOptions.default(),
)

/** Orchestrates detection, calibration, measurement, and trajectory projection. */
object MeasurementPipeline {
    fun currentProductionNoRead(): MeasurementRunOutcome.NoRead =
        MeasurementRunOutcome.NoRead(
            reason = MeasurementRunFailure.UNPROVEN_TIMING,
            message = "No production frame source has proven image/timestamp pairing in Phase 8.",
        )

    fun measureWithProvenTiming(
        sequence: TimedFrameSequence,
        calibration: MeasurementCalibrationState,
        timingProof: MeasurementTimingProof,
        config: MeasurementPipelineConfig,
    ): MeasurementRunOutcome {
        if (timingProof is DirectSourceMeasurementTimingProof) {
            return MeasurementRunOutcome.NoRead(
                reason = MeasurementRunFailure.UNPROVEN_TIMING,
                message = "Direct timing proof must be used through its bound direct-source input.",
            )
        }
        return measureValidated(sequence, calibration, timingProof, config)
    }

    fun measureWithDirectProof(
        input: DirectSourceMeasurementInput,
        calibration: MeasurementCalibrationState,
        config: MeasurementPipelineConfig,
    ): MeasurementRunOutcome =
        measureValidated(input.sequence, calibration, input.timingProof, config)

    private fun measureValidated(
        sequence: TimedFrameSequence,
        calibration: MeasurementCalibrationState,
        timingProof: MeasurementTimingProof,
        config: MeasurementPipelineConfig,
    ): MeasurementRunOutcome {
        val pixelsPerFoot = when (val calibrationResult = calibration.pixelsPerFoot()) {
            is CalibrationResult.Failure -> return calibrationResult.toNoRead()
            is CalibrationResult.Success -> calibrationResult.pixelsPerFoot
        }
        val detections = when (val trackOutcome = DetectionTrackExtractor.extract(sequence, config.trackConfig)) {
            is TrackExtractionOutcome.Failure -> return MeasurementRunOutcome.NoRead(trackOutcome.reason, trackOutcome.message)
            is TrackExtractionOutcome.Success -> trackOutcome.detections
        }
        val measurement = when (
            val outcome = VelocityMeasurementCalculator.measure(
                detections = detections,
                pixelsPerFoot = pixelsPerFoot,
                options = config.measurementOptions,
            )
        ) {
            is MeasurementOutcome.Failure -> return outcome.toNoRead()
            is MeasurementOutcome.Success -> outcome.measurement
        }
        val trajectory = when (
            val trajectoryOutcome = TrajectoryPhysics.simulate(
                launch = LaunchState.fromMilesPerHour(
                    milesPerHour = measurement.milesPerHour,
                    angleDegrees = measurement.launchAngleDegrees,
                ),
                options = config.trajectoryOptions,
            )
        ) {
            is TrajectoryOutcome.Failure -> return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.MEASUREMENT_REJECTED,
                trajectoryOutcome.message,
            )
            is TrajectoryOutcome.Success -> trajectoryOutcome.trajectory
        }
        return MeasurementRunOutcome.Success(
            measurement = measurement,
            trajectory = trajectory,
            detectionCount = detections.size,
            timingProof = timingProof,
        )
    }
}

private fun CalibrationResult.Failure.toNoRead(): MeasurementRunOutcome.NoRead =
    MeasurementRunOutcome.NoRead(
        reason = when (reason) {
            CalibrationFailure.INVALID_POINT,
            CalibrationFailure.INVALID_PIXEL_DISTANCE,
            CalibrationFailure.INVALID_REAL_DISTANCE -> MeasurementRunFailure.BAD_CALIBRATION
        },
        message = message,
    )

private fun MeasurementOutcome.Failure.toNoRead(): MeasurementRunOutcome.NoRead =
    MeasurementRunOutcome.NoRead(
        reason = when (reason) {
            MeasurementFailure.INSUFFICIENT_DETECTIONS -> MeasurementRunFailure.INSUFFICIENT_DETECTIONS
            MeasurementFailure.BAD_TIMESTAMP,
            MeasurementFailure.INVALID_DETECTION,
            MeasurementFailure.INVALID_OPTIONS -> MeasurementRunFailure.BAD_FRAME_SEQUENCE
            MeasurementFailure.INVALID_CALIBRATION -> MeasurementRunFailure.BAD_CALIBRATION
            MeasurementFailure.NON_FINITE_FIT,
            MeasurementFailure.EXCESSIVE_RESIDUAL -> MeasurementRunFailure.MEASUREMENT_REJECTED
        },
        message = message,
    )
