package com.speedball.app.measurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Phase14WorkflowStateTest {
    @Test
    fun captureCannotArmUntilPermissionGeometryScaleColorAndLevelAreReady() {
        val notReady = Phase14WorkflowState().reduce(Phase14WorkflowEvent.ArmCapture)

        assertEquals(Phase14CaptureState.NotReady, notReady.capture)
        assertFalse(notReady.canArm())

        val missingLevel = baseReadyStateWithoutLevel()
        val ready = baseReadyState()

        assertFalse(missingLevel.canArm())
        assertTrue(ready.canArm())
        assertEquals(Phase14CaptureState.Armed, ready.reduce(Phase14WorkflowEvent.ArmCapture).capture)
    }

    @Test
    fun geometryChangeClearsDistanceCalibrationColorAndRoi() {
        val ready = baseReadyState()
        val changed = ready.reduce(Phase14WorkflowEvent.GeometryChanged(geometry(readbackWidth = 200, readbackHeight = 90)))

        assertNull(changed.calibrationPointA)
        assertNull(changed.calibrationPointB)
        assertNull(changed.knownDistanceFeet)
        assertNull(changed.colorSamplePoint)
        assertNull(changed.colorSample)
        assertNull(changed.regionOfInterest)
        assertNull(changed.levelReference)
        assertFalse(changed.canArm())
    }

    @Test
    fun geometryClearedPreventsCaptureArming() {
        val cleared = baseReadyState().reduce(Phase14WorkflowEvent.GeometryCleared)

        assertNull(cleared.geometry)
        assertNull(cleared.levelReference)
        assertFalse(cleared.canArm())
        assertEquals(Phase14CaptureState.NotReady, cleared.reduce(Phase14WorkflowEvent.ArmCapture).capture)
    }

    @Test
    fun retryClearsResultWithoutClearingValidSetup() {
        val completed = baseReadyState().reduce(
            Phase14WorkflowEvent.CaptureCompleted(
                VisualEstimateOutcome.NoRead(VisualEstimateNoReadReason.DETECTION_FAILED, "No ball."),
            ),
        )

        val retried = completed.reduce(Phase14WorkflowEvent.Retry)

        assertNull(retried.result)
        assertTrue(retried.canArm())
        assertEquals(Phase14CaptureState.Idle, retried.capture)
    }

    @Test
    fun recalibrateClearsScaleButKeepsColorSetup() {
        val recalibrated = baseReadyState().reduce(Phase14WorkflowEvent.Recalibrate)

        assertNull(recalibrated.calibrationPointA)
        assertNull(recalibrated.knownDistanceFeet)
        assertNull(recalibrated.knownBallDiameterFeet)
        assertNull(recalibrated.levelReference)
        assertTrue(recalibrated.colorSamplePoint?.isInFrame() == true)
        assertFalse(recalibrated.canArm())
    }

    @Test
    fun switchingToBallDiameterFallbackRemovesDistanceFromReadyPath() {
        val fallback = baseReadyState()
            .reduce(Phase14WorkflowEvent.KnownBallDiameterChanged(0.25))

        assertEquals(Phase14SetupMode.BallDiameterFallback, fallback.setupMode)
        assertNull(fallback.knownDistanceFeet)
        assertTrue(fallback.canArm())
    }

    @Test
    fun levelReferenceCaptureIsRequiredAndCanBeCleared() {
        val withLevel = baseReadyStateWithoutLevel().reduce(Phase14WorkflowEvent.LevelReferenceCaptured(levelSnapshot()))

        assertTrue(withLevel.canArm())
        assertEquals(levelSnapshot(), withLevel.levelReference)
        assertFalse(withLevel.reduce(Phase14WorkflowEvent.LevelReferenceCleared).canArm())
    }

    @Test
    fun activeReadbackBuildersTransformNormalizedSetup() {
        val ready = baseReadyState()
        val calibration = ready.buildCalibrationForActiveReadback()
        val color = ready.buildColorForActiveReadback()

        val calibrationReady = assertInstanceOf(CalibrationWorkflowReadiness.Ready::class.java, calibration.readiness())
        val colorReady = assertInstanceOf(ColorWorkflowReadiness.Ready::class.java, color.readiness(160, 90))

        assertEquals(16.0, calibrationReady.pixelsPerFoot, 1.0e-9)
        assertEquals(RegionOfInterest(16, 9, 144, 81), colorReady.regionOfInterest)
    }

    @Test
    fun colorReadbackBuilderDoesNotFallBackToFullFrameWithoutRoi() {
        val missingRoi = baseReadyState().copy(regionOfInterest = null)
        val missingSamplePoint = baseReadyState().copy(colorSamplePoint = null)

        assertInstanceOf(
            ColorWorkflowReadiness.NotReady::class.java,
            missingRoi.buildColorForActiveReadback().readiness(160, 90),
        )
        assertInstanceOf(
            ColorWorkflowReadiness.NotReady::class.java,
            missingSamplePoint.buildColorForActiveReadback().readiness(160, 90),
        )
    }

    @Test
    fun clearedColorSampleKeepsTargetButPreventsReadiness() {
        val cleared = baseReadyState().reduce(
            Phase14WorkflowEvent.ColorSampleCleared(NormalizedFramePoint(0.25, 0.75)),
        )

        assertEquals(NormalizedFramePoint(0.25, 0.75), cleared.colorSamplePoint)
        assertNull(cleared.colorSample)
        assertFalse(cleared.canArm())
        assertInstanceOf(
            ColorWorkflowReadiness.NotReady::class.java,
            cleared.buildColorForActiveReadback().readiness(160, 90),
        )
    }

    @Test
    fun endToEndSpeedGoldenUsesComposedReadbackCalibration() {
        val workflow = Phase14WorkflowState()
            .reduce(Phase14WorkflowEvent.PermissionChanged(true))
            .reduce(Phase14WorkflowEvent.GeometryChanged(geometry(readbackWidth = 200, readbackHeight = 90)))
            .reduce(
                Phase14WorkflowEvent.KnownDistanceSelected(
                    pointA = NormalizedFramePoint(0.10, 0.50),
                    pointB = NormalizedFramePoint(0.90, 0.50),
                    knownDistanceFeet = 8.0,
                ),
            )
            .reduce(
                Phase14WorkflowEvent.ColorSampleSelected(
                    point = NormalizedFramePoint(0.50, 0.50),
                    sample = HsvColor(120.0, 0.8, 0.7),
                    tolerance = HsvTolerance(10.0, 0.2, 0.2),
                ),
            )
            .reduce(
                Phase14WorkflowEvent.RegionOfInterestSelected(
                    NormalizedFrameRect(0.10, 0.10, 0.90, 0.90),
                ),
            )

        val outcome = VisualEstimatePipeline.estimate(
            samples = listOf(
                VisualEstimateTrackSample(timestampSeconds = 0.0, xPx = 20.0, yPx = 45.0),
                VisualEstimateTrackSample(timestampSeconds = 0.1, xPx = 40.0, yPx = 45.0),
                VisualEstimateTrackSample(timestampSeconds = 0.2, xPx = 60.0, yPx = 45.0),
                VisualEstimateTrackSample(timestampSeconds = 0.3, xPx = 80.0, yPx = 45.0),
            ),
            calibration = workflow.buildCalibrationForActiveReadback().toMeasurementCalibrationState(),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(6.81818, success.milesPerHour, 0.0001)
        assertEquals(EstimateScaleBasis.DISTANCE_CALIBRATION, success.diagnostics.scaleBasis)
    }

    @Test
    fun endToEndSpeedGoldenCanStartFromLetterboxedPreviewTaps() {
        val previewTransform = PreviewFrameTransform(
            view = FrameDimensions(400, 400),
            source = FrameDimensions(1280, 720),
            scaleMode = PreviewScaleMode.FitCenter,
        )
        val pointA = requireNotNull(previewTransform.viewPointToNormalized(40.0, 200.0))
        val pointB = requireNotNull(previewTransform.viewPointToNormalized(360.0, 200.0))
        val workflow = Phase14WorkflowState()
            .reduce(Phase14WorkflowEvent.PermissionChanged(true))
            .reduce(Phase14WorkflowEvent.GeometryChanged(geometry(readbackWidth = 200, readbackHeight = 90)))
            .reduce(
                Phase14WorkflowEvent.KnownDistanceSelected(
                    pointA = pointA,
                    pointB = pointB,
                    knownDistanceFeet = 8.0,
                ),
            )
            .reduce(
                Phase14WorkflowEvent.ColorSampleSelected(
                    point = NormalizedFramePoint(0.50, 0.50),
                    sample = HsvColor(120.0, 0.8, 0.7),
                    tolerance = HsvTolerance(10.0, 0.2, 0.2),
                ),
            )
            .reduce(
                Phase14WorkflowEvent.RegionOfInterestSelected(
                    NormalizedFrameRect(0.10, 0.10, 0.90, 0.90),
                ),
            )

        val outcome = VisualEstimatePipeline.estimate(
            samples = listOf(
                VisualEstimateTrackSample(timestampSeconds = 0.0, xPx = 20.0, yPx = 45.0),
                VisualEstimateTrackSample(timestampSeconds = 0.1, xPx = 40.0, yPx = 45.0),
                VisualEstimateTrackSample(timestampSeconds = 0.2, xPx = 60.0, yPx = 45.0),
                VisualEstimateTrackSample(timestampSeconds = 0.3, xPx = 80.0, yPx = 45.0),
            ),
            calibration = workflow.buildCalibrationForActiveReadback().toMeasurementCalibrationState(),
        )

        assertEquals(0.10, pointA.x, 1.0e-9)
        assertEquals(0.50, pointA.y, 1.0e-9)
        assertEquals(0.90, pointB.x, 1.0e-9)
        assertEquals(0.50, pointB.y, 1.0e-9)
        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        assertEquals(6.81818, success.milesPerHour, 0.0001)
        assertEquals(EstimateScaleBasis.DISTANCE_CALIBRATION, success.diagnostics.scaleBasis)
    }

    @Test
    fun caliperCalibrationUsesImageNormalizedCoordinatesFromPillarboxedPreview() {
        val previewTransform = PreviewFrameTransform(
            view = FrameDimensions(2280, 1080),
            source = FrameDimensions(1280, 720),
            scaleMode = PreviewScaleMode.FitCenter,
        )
        val pointA = requireNotNull(previewTransform.viewPointToNormalized(660.0, 540.0))
        val pointB = requireNotNull(previewTransform.viewPointToNormalized(1620.0, 540.0))
        val workflow = Phase14WorkflowState()
            .reduce(Phase14WorkflowEvent.PermissionChanged(true))
            .reduce(Phase14WorkflowEvent.GeometryChanged(geometry(readbackWidth = 1280, readbackHeight = 720)))
            .reduce(
                Phase14WorkflowEvent.KnownDistanceSelected(
                    pointA = pointA,
                    pointB = pointB,
                    knownDistanceFeet = 11.0,
                ),
            )

        val readiness = assertInstanceOf(
            CalibrationWorkflowReadiness.Ready::class.java,
            workflow.buildCalibrationForActiveReadback().readiness(),
        )

        assertEquals(0.25, pointA.x, 1.0e-9)
        assertEquals(0.75, pointB.x, 1.0e-9)
        assertEquals(640.0 / 11.0, readiness.pixelsPerFoot, 1.0e-9)
    }

    @Test
    fun distanceEditOverwritesFeetAndPreservesCalipers() {
        val ready = baseReadyState()
        val edited = ready.reduce(
            Phase14WorkflowEvent.KnownDistanceSelected(
                pointA = requireNotNull(ready.calibrationPointA),
                pointB = requireNotNull(ready.calibrationPointB),
                knownDistanceFeet = 10.0,
            ),
        )

        val readiness = assertInstanceOf(
            CalibrationWorkflowReadiness.Ready::class.java,
            edited.buildCalibrationForActiveReadback().readiness(),
        )

        assertEquals(ready.calibrationPointA, edited.calibrationPointA)
        assertEquals(ready.calibrationPointB, edited.calibrationPointB)
        assertEquals(10.0, edited.knownDistanceFeet)
        assertEquals(12.8, readiness.pixelsPerFoot, 1.0e-9)
        assertTrue(edited.canArm())
    }

    @Test
    fun invalidDistanceEditBlocksLiveAndCurrentCalibrationConsumers() {
        val ready = baseReadyState()
        val invalid = ready.reduce(
            Phase14WorkflowEvent.KnownDistanceSelected(
                pointA = requireNotNull(ready.calibrationPointA),
                pointB = requireNotNull(ready.calibrationPointB),
                knownDistanceFeet = Double.NaN,
            ),
        )

        val readiness = assertInstanceOf(
            CalibrationWorkflowReadiness.NotReady::class.java,
            invalid.buildCalibrationForActiveReadback().readiness(),
        )

        assertFalse(invalid.canArm())
        assertEquals(ready.calibrationPointA, invalid.calibrationPointA)
        assertEquals(ready.calibrationPointB, invalid.calibrationPointB)
        assertTrue(readiness.message.contains("positive", ignoreCase = true) || readiness.message.contains("finite", ignoreCase = true))
    }

    private fun baseReadyState(): Phase14WorkflowState =
        baseReadyStateWithoutLevel().reduce(Phase14WorkflowEvent.LevelReferenceCaptured(levelSnapshot()))

    private fun baseReadyStateWithoutLevel(): Phase14WorkflowState =
        Phase14WorkflowState()
            .reduce(Phase14WorkflowEvent.PermissionChanged(true))
            .reduce(Phase14WorkflowEvent.GeometryChanged(geometry()))
            .reduce(
                Phase14WorkflowEvent.KnownDistanceSelected(
                    pointA = NormalizedFramePoint(0.10, 0.50),
                    pointB = NormalizedFramePoint(0.90, 0.50),
                    knownDistanceFeet = 8.0,
                ),
            )
            .reduce(
                Phase14WorkflowEvent.ColorSampleSelected(
                    point = NormalizedFramePoint(0.50, 0.50),
                    sample = HsvColor(120.0, 0.8, 0.7),
                    tolerance = HsvTolerance(10.0, 0.2, 0.2),
                ),
            )
            .reduce(
                Phase14WorkflowEvent.RegionOfInterestSelected(
                    NormalizedFrameRect(0.10, 0.10, 0.90, 0.90),
                ),
            )

    private fun levelSnapshot(): LevelReferenceSnapshot =
        LevelReferenceSnapshot(
            rollDegrees = 0.0,
            pitchDegrees = 0.0,
            sampleCount = LevelReferenceCalculator.MIN_SAMPLE_COUNT,
            source = LevelReferenceSource.GRAVITY_SENSOR,
            displayRotation = LevelReferenceDisplayRotation.ROTATION_0,
            maxGyroMagnitudeRadPerSecond = 0.0,
            capturedAtEpochMillis = 1L,
        )

    private fun geometry(
        readbackWidth: Int = 160,
        readbackHeight: Int = 90,
    ): Phase14Geometry =
        Phase14Geometry(
            modeId = "1280x720@120",
            previewTransformId = "fit-400x225",
            source = FrameDimensions(1280, 720),
            readback = FrameDimensions(readbackWidth, readbackHeight),
        )
}
