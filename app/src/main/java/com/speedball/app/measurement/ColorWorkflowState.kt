package com.speedball.app.measurement

/** Pure workflow state for sampled ball color, tolerance, and optional ROI. */
data class ColorWorkflowState(
    val sample: HsvColor? = null,
    val tolerance: HsvTolerance = HsvTolerance(
        hueDegrees = 8.0,
        saturation = 0.12,
        value = 0.12,
    ),
    val regionOfInterest: RegionOfInterest? = null,
    val revision: Long = 0L,
) {
    /** Stores a sampled HSV color and clamps tolerance before detector use. */
    fun selectSample(
        sample: HsvColor,
        tolerance: HsvTolerance = this.tolerance,
        regionOfInterest: RegionOfInterest? = this.regionOfInterest,
    ): ColorWorkflowState =
        copy(
            sample = sample,
            tolerance = tolerance.clamped(),
            regionOfInterest = regionOfInterest,
            revision = revision + 1L,
        )

    /** Replaces tolerance while preserving the current sample. */
    fun withTolerance(tolerance: HsvTolerance): ColorWorkflowState =
        copy(tolerance = tolerance.clamped(), revision = revision + 1L)

    /** Replaces ROI while preserving the current sample. */
    fun withRegionOfInterest(regionOfInterest: RegionOfInterest?): ColorWorkflowState =
        copy(regionOfInterest = regionOfInterest, revision = revision + 1L)

    /** Clears color readiness and prevents detector/result eligibility. */
    fun clear(): ColorWorkflowState =
        ColorWorkflowState(
            tolerance = tolerance,
            regionOfInterest = regionOfInterest,
            revision = revision + 1L,
        )

    /** Returns detector-ready color state only when sample and frame bounds validate. */
    fun readiness(frameWidth: Int, frameHeight: Int): ColorWorkflowReadiness {
        if (frameWidth <= 0 || frameHeight <= 0) {
            return ColorWorkflowReadiness.NotReady("Frame dimensions are unavailable for color setup.")
        }
        val selectedSample = sample
            ?: return ColorWorkflowReadiness.NotReady("Sample the ball color before measuring.")
        if (!selectedSample.hasValidWorkflowValues()) {
            return ColorWorkflowReadiness.NotReady("Sampled ball color is outside the valid HSV range.")
        }

        val clippedRoi = regionOfInterest?.clippedTo(frameWidth, frameHeight)
            ?: if (regionOfInterest == null) {
                RegionOfInterest(0, 0, frameWidth, frameHeight)
            } else {
                return ColorWorkflowReadiness.NotReady("Color region does not overlap the frame.")
            }

        return ColorWorkflowReadiness.Ready(
            threshold = HsvThreshold(
                center = selectedSample,
                tolerance = tolerance.clamped(),
            ),
            regionOfInterest = clippedRoi,
        )
    }

    /** Fail-loud detector no-read for missing or invalid color setup. */
    fun noReadOrNull(frameWidth: Int, frameHeight: Int): MeasurementRunOutcome.NoRead? =
        when (val readiness = readiness(frameWidth, frameHeight)) {
            is ColorWorkflowReadiness.Ready -> null
            is ColorWorkflowReadiness.NotReady -> MeasurementRunOutcome.NoRead(
                reason = MeasurementRunFailure.DETECTION_FAILED,
                message = readiness.message,
            )
        }
}

/** Detector-ready color setup or actionable not-ready text. */
sealed interface ColorWorkflowReadiness {
    data class Ready(
        val threshold: HsvThreshold,
        val regionOfInterest: RegionOfInterest,
    ) : ColorWorkflowReadiness

    data class NotReady(val message: String) : ColorWorkflowReadiness
}

private fun HsvColor.hasValidWorkflowValues(): Boolean =
    hasOnlyFiniteValues() &&
        saturation in 0.0..1.0 &&
        value in 0.0..1.0
