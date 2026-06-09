package com.speedball.app.measurement

import com.speedball.core.model.ImagePoint

/** Estimate setup mode for Phase 14's in-app workflow. */
enum class Phase14SetupMode {
    KnownDistance,
    BallDiameterFallback,
}

/** User-visible capture state for the Phase 14 estimate workflow. */
enum class Phase14CaptureState {
    Idle,
    NotReady,
    Armed,
    Running,
    Complete,
}

/** Stable geometry identity that binds setup selections to the active capture transform. */
data class Phase14Geometry(
    val modeId: String,
    val previewTransformId: String,
    val source: FrameDimensions,
    val readback: FrameDimensions,
)

/** Pure reducer state for Phase 14 setup, capture, retry, and result flow. */
data class Phase14WorkflowState(
    val permissionReady: Boolean = false,
    val geometry: Phase14Geometry? = null,
    val setupMode: Phase14SetupMode = Phase14SetupMode.KnownDistance,
    val calibrationPointA: NormalizedFramePoint? = null,
    val calibrationPointB: NormalizedFramePoint? = null,
    val knownDistanceFeet: Double? = null,
    val knownBallDiameterFeet: Double? = null,
    val colorSamplePoint: NormalizedFramePoint? = null,
    val colorSample: HsvColor? = null,
    val colorTolerance: HsvTolerance = HsvTolerance(8.0, 0.12, 0.12),
    val regionOfInterest: NormalizedFrameRect? = null,
    val impactZone: NormalizedFramePolygon? = null,
    val expectedBallBounds: NormalizedFramePolygon? = null,
    val levelReference: LevelReferenceSnapshot? = null,
    val capture: Phase14CaptureState = Phase14CaptureState.Idle,
    val result: VisualEstimateOutcome? = null,
) {
    fun reduce(event: Phase14WorkflowEvent): Phase14WorkflowState =
        when (event) {
            is Phase14WorkflowEvent.PermissionChanged ->
                copy(permissionReady = event.ready).withoutResultIf { !event.ready }

            is Phase14WorkflowEvent.GeometryChanged ->
                if (geometry == event.geometry) {
                    copy(geometry = event.geometry)
                } else {
                    copy(
                        geometry = event.geometry,
                        calibrationPointA = null,
                        calibrationPointB = null,
                        knownDistanceFeet = null,
                        colorSamplePoint = null,
                        colorSample = null,
                        regionOfInterest = null,
                        impactZone = null,
                        expectedBallBounds = null,
                        levelReference = null,
                        capture = Phase14CaptureState.Idle,
                        result = null,
                    )
                }

            Phase14WorkflowEvent.GeometryCleared ->
                copy(
                    geometry = null,
                    calibrationPointA = null,
                    calibrationPointB = null,
                    knownDistanceFeet = null,
                    colorSamplePoint = null,
                    colorSample = null,
                    regionOfInterest = null,
                    impactZone = null,
                    expectedBallBounds = null,
                    levelReference = null,
                    capture = Phase14CaptureState.Idle,
                    result = null,
                )

            is Phase14WorkflowEvent.SetupModeSelected ->
                copy(
                    setupMode = event.mode,
                    knownDistanceFeet = if (event.mode == Phase14SetupMode.BallDiameterFallback) null else knownDistanceFeet,
                    capture = Phase14CaptureState.Idle,
                    result = null,
                )

            is Phase14WorkflowEvent.KnownDistanceSelected ->
                copy(
                    setupMode = Phase14SetupMode.KnownDistance,
                    calibrationPointA = event.pointA.takeIf { it.isInFrame() },
                    calibrationPointB = event.pointB.takeIf { it.isInFrame() },
                    knownDistanceFeet = event.knownDistanceFeet,
                    capture = Phase14CaptureState.Idle,
                    result = null,
                )

            is Phase14WorkflowEvent.KnownBallDiameterChanged ->
                copy(
                    setupMode = Phase14SetupMode.BallDiameterFallback,
                    knownBallDiameterFeet = event.diameterFeet,
                    knownDistanceFeet = null,
                    capture = Phase14CaptureState.Idle,
                    result = null,
                )

            is Phase14WorkflowEvent.ColorSampleSelected ->
                copy(
                    colorSamplePoint = event.point.takeIf { it.isInFrame() },
                    colorSample = event.sample,
                    colorTolerance = event.tolerance.clamped(),
                    expectedBallBounds = event.expectedBallBounds?.takeIf { it.isInFrame() } ?: expectedBallBounds,
                    capture = Phase14CaptureState.Idle,
                    result = null,
                )

            is Phase14WorkflowEvent.ColorSampleCleared ->
                copy(
                    colorSamplePoint = event.point.takeIf { it.isInFrame() },
                    colorSample = null,
                    capture = Phase14CaptureState.Idle,
                    result = null,
                )

            is Phase14WorkflowEvent.RegionOfInterestSelected ->
                copy(
                    regionOfInterest = event.region.takeIf { it.isInFrame() },
                    capture = Phase14CaptureState.Idle,
                    result = null,
                )

            is Phase14WorkflowEvent.ImpactZoneSelected ->
                copy(
                    impactZone = event.polygon.takeIf { it.isInFrame() },
                    capture = Phase14CaptureState.Idle,
                    result = null,
                )

            is Phase14WorkflowEvent.ExpectedBallBoundsSelected ->
                copy(
                    expectedBallBounds = event.bounds.takeIf { it.isInFrame() },
                    capture = Phase14CaptureState.Idle,
                    result = null,
                )

            is Phase14WorkflowEvent.LevelReferenceCaptured ->
                copy(
                    levelReference = event.snapshot.takeIf { it.hasOnlyFiniteValues() },
                    capture = Phase14CaptureState.Idle,
                    result = null,
                )

            Phase14WorkflowEvent.LevelReferenceCleared ->
                copy(levelReference = null, capture = Phase14CaptureState.Idle, result = null)

            Phase14WorkflowEvent.ArmCapture ->
                if (canArm()) copy(capture = Phase14CaptureState.Armed) else copy(capture = Phase14CaptureState.NotReady, result = null)

            Phase14WorkflowEvent.StartCapture ->
                if (canArm()) copy(capture = Phase14CaptureState.Running, result = null) else copy(capture = Phase14CaptureState.NotReady, result = null)

            is Phase14WorkflowEvent.CaptureCompleted ->
                copy(capture = Phase14CaptureState.Complete, result = event.outcome)

            Phase14WorkflowEvent.Retry ->
                copy(capture = Phase14CaptureState.Idle, result = null)

            Phase14WorkflowEvent.Recalibrate ->
                copy(
                    calibrationPointA = null,
                    calibrationPointB = null,
                    knownDistanceFeet = null,
                    knownBallDiameterFeet = null,
                    levelReference = null,
                    capture = Phase14CaptureState.Idle,
                    result = null,
                )
        }

    fun canArm(): Boolean =
        permissionReady &&
            geometry != null &&
            hasReadyScaleSetup() &&
            hasReadyLevelReference()

    fun buildCalibrationForActiveReadback(): CalibrationWorkflowState {
        if (setupMode != Phase14SetupMode.KnownDistance) return CalibrationWorkflowState()
        val activeGeometry = geometry ?: return CalibrationWorkflowState()
        val pointA = calibrationPointA ?: return CalibrationWorkflowState()
        val pointB = calibrationPointB ?: return CalibrationWorkflowState()
        val distanceFeet = knownDistanceFeet ?: return CalibrationWorkflowState()
        val transform = DetectionReadbackTransform(activeGeometry.source, activeGeometry.readback)
        return CalibrationWorkflowState().select(
            pointA = transform.normalizedToReadbackPoint(pointA) ?: ImagePoint(Double.NaN, Double.NaN),
            pointB = transform.normalizedToReadbackPoint(pointB) ?: ImagePoint(Double.NaN, Double.NaN),
            knownDistanceFeet = distanceFeet,
        )
    }

    fun buildColorForActiveReadback(): ColorWorkflowState {
        val activeGeometry = geometry ?: return ColorWorkflowState(tolerance = colorTolerance)
        if (colorSamplePoint?.isInFrame() != true) return ColorWorkflowState(tolerance = colorTolerance)
        val sample = colorSample ?: return ColorWorkflowState(tolerance = colorTolerance)
        val roi = regionOfInterest ?: return ColorWorkflowState(tolerance = colorTolerance)
        val transform = DetectionReadbackTransform(activeGeometry.source, activeGeometry.readback)
        val readbackRoi = transform.normalizedToReadbackRoi(roi) ?: return ColorWorkflowState(tolerance = colorTolerance)
        return ColorWorkflowState(tolerance = colorTolerance).selectSample(
            sample = sample,
            tolerance = colorTolerance,
            regionOfInterest = readbackRoi,
        )
    }

    private fun hasReadyScaleSetup(): Boolean =
        when (setupMode) {
            Phase14SetupMode.KnownDistance ->
                calibrationPointA?.isInFrame() == true &&
                    calibrationPointB?.isInFrame() == true &&
                    knownDistanceFeet?.let { it.isFinite() && it > 0.0 } == true
            Phase14SetupMode.BallDiameterFallback ->
                knownBallDiameterFeet?.let { it.isFinite() && it > 0.0 } == true
        }

    private fun hasReadyColorSetup(): Boolean =
        colorSamplePoint?.isInFrame() == true &&
            colorSample?.hasValidPhase14Color() == true &&
            regionOfInterest?.isInFrame() == true

    private fun hasReadyLevelReference(): Boolean =
        levelReference?.hasOnlyFiniteValues() == true

    private fun withoutResultIf(predicate: () -> Boolean): Phase14WorkflowState =
        if (predicate()) copy(capture = Phase14CaptureState.Idle, result = null) else this
}

/** Reducer events for Phase 14 setup/capture workflow. */
sealed interface Phase14WorkflowEvent {
    data class PermissionChanged(val ready: Boolean) : Phase14WorkflowEvent
    data class GeometryChanged(val geometry: Phase14Geometry) : Phase14WorkflowEvent
    data object GeometryCleared : Phase14WorkflowEvent
    data class SetupModeSelected(val mode: Phase14SetupMode) : Phase14WorkflowEvent
    data class KnownDistanceSelected(
        val pointA: NormalizedFramePoint,
        val pointB: NormalizedFramePoint,
        val knownDistanceFeet: Double,
    ) : Phase14WorkflowEvent
    data class KnownBallDiameterChanged(val diameterFeet: Double) : Phase14WorkflowEvent
    data class ColorSampleSelected(
        val point: NormalizedFramePoint,
        val sample: HsvColor,
        val tolerance: HsvTolerance,
        val expectedBallBounds: NormalizedFramePolygon? = null,
    ) : Phase14WorkflowEvent
    data class ColorSampleCleared(val point: NormalizedFramePoint) : Phase14WorkflowEvent
    data class RegionOfInterestSelected(val region: NormalizedFrameRect) : Phase14WorkflowEvent
    data class ImpactZoneSelected(val polygon: NormalizedFramePolygon) : Phase14WorkflowEvent
    data class ExpectedBallBoundsSelected(val bounds: NormalizedFramePolygon) : Phase14WorkflowEvent
    data class LevelReferenceCaptured(val snapshot: LevelReferenceSnapshot) : Phase14WorkflowEvent
    data object LevelReferenceCleared : Phase14WorkflowEvent
    data object ArmCapture : Phase14WorkflowEvent
    data object StartCapture : Phase14WorkflowEvent
    data class CaptureCompleted(val outcome: VisualEstimateOutcome) : Phase14WorkflowEvent
    data object Retry : Phase14WorkflowEvent
    data object Recalibrate : Phase14WorkflowEvent
}

private fun HsvColor.hasValidPhase14Color(): Boolean =
    hueDegrees.isFinite() && saturation.isFinite() && value.isFinite() && saturation in 0.0..1.0 && value in 0.0..1.0
