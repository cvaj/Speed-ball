package com.speedball.app.measurement

import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VisualEstimatePipelineTest {
    @Test
    fun goldenEstimateUsesRealNonUniformTimestamps() {
        val outcome = VisualEstimatePipeline.estimate(
            samples = listOf(
                sample(0.0, 0.0),
                sample(0.1, 10.0),
                sample(0.3, 30.0),
                sample(0.6, 60.0),
            ),
            calibration = calibration(pixels = 10.0, feet = 10.0),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(68.18182, success.milesPerHour, 0.0001)
        assertEquals(0.0, success.launchAngleDegrees ?: Double.NaN, 1.0e-9)
        assertEquals(4, success.diagnostics.detectionCount)
        assertEquals(EstimateTimingBasis.REAL_PER_FRAME_TIMESTAMPS, success.diagnostics.timingBasis)
        assertTrue(success.diagnostics.assumptions.any { it.contains("calibrated image plane") })
        assertTrue(success.diagnostics.assumptions.any { it.contains("not corrected for camera tilt") })
    }

    @Test
    fun confidenceFloorNoReadsSparseUnevenAndOutlierHeavyTracks() {
        assertNoRead(
            VisualEstimatePipeline.estimate(
                samples = listOf(sample(0.0, 0.0), sample(0.1, 10.0), sample(0.2, 20.0)),
                calibration = validCalibration(),
            ),
            VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
        )
        assertNoRead(
            VisualEstimatePipeline.estimate(
                samples = listOf(sample(0.0, 0.0), sample(0.01, 1.0), sample(0.02, 2.0), sample(0.10, 3.0)),
                calibration = validCalibration(),
            ),
            VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
        )
        assertNoRead(
            VisualEstimatePipeline.estimate(
                samples = listOf(
                    sample(0.0, 0.0),
                    sample(0.1, 10.0),
                    sample(0.2, 80.0),
                    sample(0.3, -80.0),
                    sample(0.4, 40.0),
                    sample(0.5, 50.0),
                ),
                calibration = validCalibration(),
                config = VisualEstimatePipelineConfig(
                    maxEstimateRmsResidualPx = 2.0,
                    minOutlierRmsImprovementPx = 0.0,
                    maxOutlierPasses = 2,
                    maxRejectedOutlierCount = 1,
                ),
            ),
            VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
        )
    }

    @Test
    fun visualDisplacementCanExplainSkippedOrCoalescedIntervalsWhenAnchoredByTimestamps() {
        val outcome = VisualEstimatePipeline.estimate(
            samples = listOf(
                sample(0.00, 0.0),
                sample(0.01, 3.0),
                sample(0.02, 6.0),
                sample(0.07, 21.0),
            ),
            calibration = calibration(pixels = 3.0, feet = 1.0),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(68.18182, success.milesPerHour, 0.0001)
        assertEquals(VisualEstimateConfidence.LOW, success.diagnostics.confidence)
        assertTrue(success.diagnostics.warnings.any { it.contains("skipped/coalesced") })
    }

    @Test
    fun blurredBlobResidualUsesDiameterScaledCentroidTolerance() {
        val blurredTrack = listOf(
            sample(0.00, 0.0, yPx = 0.0, diameter = 20.0),
            sample(0.01, 20.0, yPx = 4.0, diameter = 20.0),
            sample(0.02, 40.0, yPx = -4.0, diameter = 20.0),
            sample(0.03, 60.0, yPx = 4.0, diameter = 20.0),
            sample(0.04, 80.0, yPx = 0.0, diameter = 20.0),
        )

        assertNoRead(
            VisualEstimatePipeline.estimate(
                samples = blurredTrack,
                calibration = validCalibration(),
                config = VisualEstimatePipelineConfig(
                    maxEstimateRmsResidualPx = 2.0,
                    maxOutlierPasses = 0,
                ),
            ),
            VisualEstimateNoReadReason.EXCESSIVE_RESIDUAL,
        )

        val scaled = VisualEstimatePipeline.estimate(
            samples = blurredTrack,
            calibration = validCalibration(),
            config = VisualEstimatePipelineConfig(
                maxEstimateRmsResidualPx = 2.0,
                maxEstimateRmsResidualDiameterFraction = 0.35,
                maxOutlierPasses = 0,
            ),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, scaled)
        assertTrue(success.diagnostics.fitResidualPx ?: 0.0 > 2.0)
        assertTrue(success.diagnostics.confidence != null)
    }

    @Test
    fun stationaryLaunchReferenceIsTrimmedBeforeVelocityFit() {
        val outcome = VisualEstimatePipeline.estimate(
            samples = listOf(
                sample(0.00, 0.0, diameter = 9.0),
                sample(0.01, 0.0, diameter = 9.0),
                sample(0.02, 3.0, diameter = 5.0),
                sample(0.03, 6.0, diameter = 5.0),
                sample(0.08, 21.0, diameter = 5.0),
                sample(0.09, 24.0, diameter = 5.0),
            ),
            calibration = calibration(pixels = 3.0, feet = 1.0),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(68.18182, success.milesPerHour, 0.0001)
        assertEquals(4, success.diagnostics.detectionCount)
    }

    @Test
    fun levelReferenceCorrectsLaunchAngleAndIsReportedInDiagnostics() {
        val level = levelSnapshot(rollDegrees = 10.0)
        val outcome = VisualEstimatePipeline.estimate(
            samples = listOf(
                sample(0.0, 0.0, yPx = 0.0),
                sample(0.1, 10.0, yPx = -10.0),
                sample(0.2, 20.0, yPx = -20.0),
                sample(0.3, 30.0, yPx = -30.0),
            ),
            calibration = validCalibration(),
            config = VisualEstimatePipelineConfig(levelReference = level),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(35.0, success.launchAngleDegrees ?: Double.NaN, 0.001)
        assertEquals(level, success.diagnostics.levelReference)
        assertTrue(success.diagnostics.assumptions.any { it.contains("Provisional launch-angle correction") })
        assertTrue(success.diagnostics.assumptions.any { it.contains("physical sign validation remains open") })
    }

    @Test
    fun visualFrameDeltasCanEstimateWhenTimestampsAreUnavailable() {
        val outcome = VisualEstimatePipeline.estimateWithVisualFrameDeltas(
            samples = listOf(
                sample(0.0, 0.0),
                sample(0.0, 3.0),
                sample(0.0, 6.0),
                sample(0.0, 21.0),
            ),
            calibration = calibration(pixels = 3.0, feet = 1.0),
            frameIntervalSeconds = 0.01,
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(68.18182, success.milesPerHour, 0.0001)
        assertEquals(EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE, success.diagnostics.timingBasis)
        assertTrue(success.diagnostics.assumptions.any { it.contains("smallest observed frame gap") })
        assertTrue(success.diagnostics.assumptions.any { it.contains("bias speed high") })
        assertTrue(success.diagnostics.warnings.any { it.contains("Visual frame-delta inference") })
        assertTrue(success.diagnostics.warnings.any { it.contains("skipped/coalesced") })
    }

    @Test
    fun visualFrameDeltaTimingNoReadsWithoutKnownFrameIntervalOrMotion() {
        assertNoRead(
            VisualEstimatePipeline.estimateWithVisualFrameDeltas(
                samples = cleanSamples(),
                calibration = validCalibration(),
                frameIntervalSeconds = Double.NaN,
            ),
            VisualEstimateNoReadReason.BAD_TIMESTAMPS,
        )
        assertNoRead(
            VisualEstimatePipeline.estimateWithVisualFrameDeltas(
                samples = listOf(
                    sample(0.0, 0.0),
                    sample(0.0, 0.0),
                    sample(0.0, 0.0),
                    sample(0.0, 0.0),
                ),
                calibration = validCalibration(),
                frameIntervalSeconds = 0.01,
            ),
            VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
        )
    }

    @Test
    fun knownBallDiameterSelfCalibrationEstimatesScaleWhenDistanceCalibrationMissing() {
        val outcome = VisualEstimatePipeline.estimate(
            samples = listOf(
                sample(0.0, 0.0, diameter = 5.0),
                sample(0.1, 10.0, diameter = 5.0),
                sample(0.2, 20.0, diameter = 5.0),
                sample(0.3, 30.0, diameter = 5.0),
            ),
            calibration = MeasurementCalibrationState(pointA = null, pointB = null, knownDistanceFeet = null),
            config = VisualEstimatePipelineConfig(knownBallDiameterFeet = 1.0),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(13.63636, success.milesPerHour, 0.0001)
        assertEquals(EstimateScaleBasis.BALL_DIAMETER_SELF_CALIBRATION, success.diagnostics.scaleBasis)
        assertTrue(success.diagnostics.assumptions.any { it.contains("entered ball diameter") })
        assertTrue(success.diagnostics.assumptions.any { it.contains("short-axis") })
    }

    @Test
    fun knownBallDiameterSelfCalibrationNoReadsWithoutApparentDiameters() {
        val noRead = assertNoRead(
            VisualEstimatePipeline.estimate(
                samples = cleanSamples(),
                calibration = MeasurementCalibrationState(pointA = null, pointB = null, knownDistanceFeet = null),
                config = VisualEstimatePipelineConfig(knownBallDiameterFeet = 1.0),
            ),
            VisualEstimateNoReadReason.BAD_CALIBRATION,
        )

        assertTrue(noRead.message.contains("apparent ball size"))
    }

    @Test
    fun badInputsNoReadWithoutPartialSpeed() {
        assertNoRead(
            VisualEstimatePipeline.estimate(
                samples = listOf(sample(0.0, 0.0), sample(0.1, 10.0), sample(0.1, 20.0), sample(0.3, 30.0)),
                calibration = validCalibration(),
            ),
            VisualEstimateNoReadReason.BAD_TIMESTAMPS,
        )
        assertNoRead(
            VisualEstimatePipeline.estimate(
                samples = cleanSamples(),
                calibration = validCalibration().copy(knownDistanceFeet = 0.0),
            ),
            VisualEstimateNoReadReason.BAD_CALIBRATION,
        )
        assertNoRead(
            VisualEstimatePipeline.estimate(
                samples = cleanSamples().mapIndexed { index, sample ->
                    if (index == 2) sample.copy(yPx = 25.0) else sample
                },
                calibration = validCalibration(),
                config = VisualEstimatePipelineConfig(maxEstimateRmsResidualPx = 0.1, maxOutlierPasses = 0),
            ),
            VisualEstimateNoReadReason.EXCESSIVE_RESIDUAL,
        )
    }

    @Test
    fun planarDepthWarningNoReadsStrongApparentScaleChange() {
        val outcome = VisualEstimatePipeline.estimate(
            samples = listOf(
                sample(0.0, 0.0, diameter = 8.0),
                sample(0.1, 10.0, diameter = 9.0),
                sample(0.2, 20.0, diameter = 14.0),
                sample(0.3, 30.0, diameter = 16.0),
            ),
            calibration = validCalibration(),
        )

        val noRead = assertNoRead(outcome, VisualEstimateNoReadReason.PLANAR_ASSUMPTION_VIOLATED)
        assertTrue(noRead.message.contains("depth motion"))
        assertTrue(noRead.diagnostics?.warnings.orEmpty().any { it.contains("scale change") })
    }

    @Test
    fun apparentScaleChangeDoesNotStandaloneFailWhenTrackIsOtherwiseCoherent() {
        val outcome = VisualEstimatePipeline.estimate(
            samples = listOf(
                sample(0.0, 0.0, diameter = 8.0),
                sample(0.1, 10.0, diameter = 9.0),
                sample(0.2, 20.0, diameter = 14.0),
                sample(0.3, 30.0, diameter = 16.0),
            ),
            calibration = validCalibration(),
            config = VisualEstimatePipelineConfig(
                allowApparentScaleChangeEstimate = true,
            ),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertTrue(success.diagnostics.warnings.any { it.contains("Apparent blob size varied") })
        assertTrue(success.milesPerHour > 0.0)
    }

    @Test
    fun depthCorrectedScaleAppliesPerspectiveRatioAndCapsStrongTrackConfidence() {
        val strongTrack = List(6) { index ->
            sample(timestampSeconds = index * 0.1, xPx = index * 10.0)
        }
        val samePlane = VisualEstimatePipeline.estimate(
            samples = strongTrack,
            calibration = calibration(pixels = 10.0, feet = 10.0),
        )
        val depthCorrected = VisualEstimatePipeline.estimate(
            samples = strongTrack,
            calibration = calibration(pixels = 10.0, feet = 10.0),
            config = VisualEstimatePipelineConfig(
                scaleMode = VisualEstimateScaleMode.DepthCorrected(
                    ballPlaneDepthFeet = 1.0,
                    calibrationPlaneDepthFeet = 7.0,
                ),
            ),
        )

        val samePlaneSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, samePlane)
        val correctedSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, depthCorrected)
        assertEquals(samePlaneSuccess.milesPerHour / 7.0, correctedSuccess.milesPerHour, 0.0001)
        assertEquals(EstimateScaleBasis.DEPTH_CORRECTED_DISTANCE_CALIBRATION, correctedSuccess.diagnostics.scaleBasis)
        assertEquals(VisualEstimateConfidence.MEDIUM, correctedSuccess.diagnostics.confidence)
        assertTrue(correctedSuccess.diagnostics.assumptions.any { it.contains("Depth-corrected scale") })
        assertTrue(correctedSuccess.diagnostics.assumptions.any { it.contains("toward-or-away") })
        assertTrue(correctedSuccess.diagnostics.warnings.any { it.contains("factor=7.000") })
    }

    @Test
    fun depthCorrectedScaleFailsLoudOnInvalidOrExtremeDepths() {
        assertNoRead(
            VisualEstimatePipeline.estimate(
                samples = cleanSamples(),
                calibration = validCalibration(),
                config = VisualEstimatePipelineConfig(
                    scaleMode = VisualEstimateScaleMode.DepthCorrected(
                        ballPlaneDepthFeet = 0.0,
                        calibrationPlaneDepthFeet = 7.0,
                    ),
                ),
            ),
            VisualEstimateNoReadReason.BAD_CALIBRATION,
        )
        assertNoRead(
            VisualEstimatePipeline.estimate(
                samples = cleanSamples(),
                calibration = validCalibration(),
                config = VisualEstimatePipelineConfig(
                    scaleMode = VisualEstimateScaleMode.DepthCorrected(
                        ballPlaneDepthFeet = 1.0,
                        calibrationPlaneDepthFeet = 20.0,
                    ),
                ),
            ),
            VisualEstimateNoReadReason.BAD_CALIBRATION,
        )
    }

    @Test
    fun speedFloorHardRejectsOnlyWhenPolicyAppliesToScaleBasis() {
        val samePlane = VisualEstimatePipeline.estimate(
            samples = cleanSamples(),
            calibration = calibration(pixels = 10.0, feet = 10.0),
            config = VisualEstimatePipelineConfig(
                minEstimateMilesPerHour = 80.0,
                minimumSpeedGatePolicy = MinimumSpeedGatePolicy.SAME_PLANE_ONLY,
            ),
        )
        val depthCorrected = VisualEstimatePipeline.estimate(
            samples = cleanSamples(),
            calibration = calibration(pixels = 10.0, feet = 10.0),
            config = VisualEstimatePipelineConfig(
                minEstimateMilesPerHour = 80.0,
                minimumSpeedGatePolicy = MinimumSpeedGatePolicy.SAME_PLANE_ONLY,
                scaleMode = VisualEstimateScaleMode.DepthCorrected(
                    ballPlaneDepthFeet = 10.0,
                    calibrationPlaneDepthFeet = 10.0,
                ),
            ),
        )

        assertNoRead(samePlane, VisualEstimateNoReadReason.AMBIGUOUS_TRACK)
        val correctedSuccess = assertInstanceOf(VisualEstimateOutcome.Success::class.java, depthCorrected)
        assertTrue(correctedSuccess.diagnostics.warnings.any { it.contains("Minimum hit-speed gate was skipped") })
    }

    @Test
    fun nonProgressingTrackNoReads() {
        assertNoRead(
            VisualEstimatePipeline.estimate(
                samples = listOf(
                    sample(0.0, 0.0),
                    sample(0.1, 0.0),
                    sample(0.2, 0.0),
                    sample(0.3, 0.0),
                ),
                calibration = validCalibration(),
            ),
            VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
        )
    }

    private fun assertNoRead(
        outcome: VisualEstimateOutcome,
        reason: VisualEstimateNoReadReason,
    ): VisualEstimateOutcome.NoRead {
        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, outcome)
        assertEquals(reason, noRead.reason)
        assertFalse(noRead.toString().contains("mph", ignoreCase = true))
        return noRead
    }

    private fun cleanSamples(): List<VisualEstimateTrackSample> =
        listOf(
            sample(0.0, 0.0),
            sample(0.1, 10.0),
            sample(0.2, 20.0),
            sample(0.3, 30.0),
        )

    private fun sample(
        timestampSeconds: Double,
        xPx: Double,
        yPx: Double = 0.0,
        diameter: Double? = null,
    ): VisualEstimateTrackSample =
        VisualEstimateTrackSample(
            timestampSeconds = timestampSeconds,
            xPx = xPx,
            yPx = yPx,
            apparentDiameterPx = diameter,
        )

    private fun validCalibration(): MeasurementCalibrationState =
        calibration(pixels = 10.0, feet = 10.0)

    private fun calibration(
        pixels: Double,
        feet: Double,
    ): MeasurementCalibrationState =
        MeasurementCalibrationState(
            pointA = ImagePoint(0.0, 0.0),
            pointB = ImagePoint(pixels, 0.0),
            knownDistanceFeet = feet,
        )

    private fun levelSnapshot(rollDegrees: Double): LevelReferenceSnapshot =
        LevelReferenceSnapshot(
            rollDegrees = rollDegrees,
            pitchDegrees = 0.0,
            sampleCount = LevelReferenceCalculator.MIN_SAMPLE_COUNT,
            source = LevelReferenceSource.GRAVITY_SENSOR,
            displayRotation = LevelReferenceDisplayRotation.ROTATION_0,
            maxGyroMagnitudeRadPerSecond = 0.0,
            capturedAtEpochMillis = 1L,
        )
}
