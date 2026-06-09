package com.speedball.app.measurement

import com.speedball.core.calibration.CalibrationFailure
import com.speedball.core.calibration.CalibrationResult
import com.speedball.core.calibration.DistanceCalibration
import com.speedball.core.measurement.MeasurementFailure
import com.speedball.core.measurement.MeasurementOptions
import com.speedball.core.measurement.MeasurementOutcome
import com.speedball.core.measurement.VelocityMeasurement
import com.speedball.core.measurement.VelocityMeasurementCalculator
import com.speedball.core.model.Detection
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** One ordered ball-center observation from app-owned visual estimate frames. */
data class VisualEstimateTrackSample(
    val timestampSeconds: Double,
    val xPx: Double,
    val yPx: Double,
    val apparentDiameterPx: Double? = null,
)

/**
 * Unit-bearing gates for S10+ visual estimate mode.
 *
 * [allowApparentScaleChangeEstimate] prevents noisy apparent-diameter
 * variation from becoming a standalone veto. Timing, path fit, direction,
 * residual, and calibration gates still fail loudly when the whole track cannot
 * support an honest estimate.
 */
data class VisualEstimatePipelineConfig(
    val maxEstimateRmsResidualPx: Double = 2.0,
    val maxEstimateRmsResidualDiameterFraction: Double? = null,
    val minTimeSpreadSecondsSquared: Double = 1.0e-6,
    val maxOutlierPasses: Int = 1,
    val minOutlierRmsImprovementPx: Double = 0.5,
    val maxTimestampGapSpreadRatio: Double = 2.5,
    val maxTimestampDisplacementRatioError: Double = 0.35,
    val maxRejectedOutlierCount: Int = 1,
    val maxApparentScaleChangeRatio: Double = 1.5,
    val allowApparentScaleChangeEstimate: Boolean = false,
    val knownBallDiameterFeet: Double? = null,
    val minBallDiameterSamples: Int = 3,
    val requireFittedPathProgression: Boolean = true,
    val minEstimateMilesPerHour: Double? = null,
    val minimumSpeedGatePolicy: MinimumSpeedGatePolicy = MinimumSpeedGatePolicy.ALL_TRUSTED_SCALE,
    val scaleMode: VisualEstimateScaleMode = VisualEstimateScaleMode.SamePlane,
    val levelReference: LevelReferenceSnapshot? = null,
    val launchHeightFeet: Double = 4.0,
)

/** Scale-plane model used before converting visual pixels per second to mph. */
sealed interface VisualEstimateScaleMode {
    /** Calibration points are on the ball travel plane. */
    data object SamePlane : VisualEstimateScaleMode

    /**
     * Estimate-only perspective correction for a fixed camera.
     *
     * Depths are measured from camera to the ball travel plane and calibration
     * plane. Monocular video cannot verify these entries or recover toward/away
     * speed, so this mode caps otherwise strong track confidence at MEDIUM.
     */
    data class DepthCorrected(
        val ballPlaneDepthFeet: Double,
        val calibrationPlaneDepthFeet: Double,
    ) : VisualEstimateScaleMode
}

/** Policy for applying the configured minimum display speed. */
enum class MinimumSpeedGatePolicy {
    SAME_PLANE_ONLY,
    ALL_TRUSTED_SCALE,
    DISABLED,
}

/**
 * Converts ordered visual detections plus calibration into an estimate-only
 * speed result.
 *
 * This pipeline uses real per-frame timestamps already attached to samples. It
 * never creates or accepts a proof token and cannot return [MeasurementRunOutcome].
 */
object VisualEstimatePipeline {
    fun estimate(
        samples: List<VisualEstimateTrackSample>,
        calibration: MeasurementCalibrationState,
        config: VisualEstimatePipelineConfig = VisualEstimatePipelineConfig(),
    ): VisualEstimateOutcome {
        val movingSamples = samples.withoutStationaryPrefix()
        val gapSummary = timestampGapSummary(movingSamples.map { it.timestampSeconds })
            ?: return VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                "Estimate samples require strictly increasing real timestamps.",
                diagnostics = noReadDiagnostics(movingSamples.size, movingSamples.size, timingBasis = EstimateTimingBasis.REAL_PER_FRAME_TIMESTAMPS),
            )
        return estimateTimestampedSamples(
            samples = movingSamples,
            calibration = calibration,
            config = config,
            timingBasis = EstimateTimingBasis.REAL_PER_FRAME_TIMESTAMPS,
            gapSummary = gapSummary,
            inferredFrameDeltas = false,
        )
    }

    fun estimateWithVisualFrameDeltas(
        samples: List<VisualEstimateTrackSample>,
        calibration: MeasurementCalibrationState,
        frameIntervalSeconds: Double,
        config: VisualEstimatePipelineConfig = VisualEstimatePipelineConfig(),
    ): VisualEstimateOutcome {
        validateVisualFrameInterval(frameIntervalSeconds)?.let { return it }
        val movingSamples = samples.withoutStationaryPrefix()
        val inference = inferVisualFrameDeltaTimestamps(movingSamples, frameIntervalSeconds)
        if (inference is VisualFrameDeltaInference.Failure) return inference.noRead
        val success = inference as VisualFrameDeltaInference.Success
        return estimateTimestampedSamples(
            samples = success.samples,
            calibration = calibration,
            config = config,
            timingBasis = EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE,
            gapSummary = success.gapSummary,
            inferredFrameDeltas = true,
        )
    }

    private fun estimateTimestampedSamples(
        samples: List<VisualEstimateTrackSample>,
        calibration: MeasurementCalibrationState,
        config: VisualEstimatePipelineConfig,
        timingBasis: EstimateTimingBasis,
        gapSummary: TimestampGapSummary,
        inferredFrameDeltas: Boolean,
    ): VisualEstimateOutcome {
        validateConfig(config)?.let { return it }
        if (samples.size < ESTIMATE_MIN_USABLE_DETECTIONS) {
            return VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
                "At least four usable detections are required for an estimate.",
                diagnostics = noReadDiagnostics(samples.size, samples.size, gapSummary, timingBasis = timingBasis),
            )
        }
        val scale = when (val resolution = resolveEstimateScale(calibration, samples, gapSummary, timingBasis, config)) {
            is EstimateScaleResolution.Success -> resolution
            is EstimateScaleResolution.Failure -> return resolution.noRead
        }
        val intervalCoherence = if (inferredFrameDeltas) {
            IntervalCoherenceOutcome.Coalesced
        } else {
            intervalCoherence(samples, gapSummary, config, timingBasis)
        }
        if (intervalCoherence is IntervalCoherenceOutcome.Failure) return intervalCoherence.noRead
        val scaleChangeWarning = when (val scaleChange = scaleChangeCheck(samples, gapSummary, config, timingBasis, scale.basis)) {
            is ScaleChangeCheck.Failure -> return scaleChange.noRead
            is ScaleChangeCheck.Warning -> scaleChange.message
            ScaleChangeCheck.None -> null
        }

        val detections = samples.map {
            Detection(
                timestampSeconds = it.timestampSeconds,
                xPx = it.xPx,
                yPx = it.yPx,
            )
        }
        val maxRmsResidualPx = effectiveMaxRmsResidualPx(samples, config)
        val measurement = when (
            val outcome = VelocityMeasurementCalculator.measure(
                detections = detections,
                pixelsPerFoot = scale.pixelsPerFoot,
                options = MeasurementOptions(
                    maxRmsResidualPx = maxRmsResidualPx,
                    minTimeSpreadSecondsSquared = config.minTimeSpreadSecondsSquared,
                    maxOutlierPasses = config.maxOutlierPasses,
                    minOutlierRmsImprovementPx = config.minOutlierRmsImprovementPx,
                ),
            )
        ) {
            is MeasurementOutcome.Success -> outcome.measurement
            is MeasurementOutcome.Failure -> return outcome.toEstimateNoRead(samples, gapSummary, timingBasis)
        }

        val usedCount = measurement.fit.usedOriginalIndices.size
        val rejectedCount = samples.size - usedCount
        if (usedCount < ESTIMATE_MIN_USABLE_DETECTIONS) {
            return VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
                "At least four usable detections must remain after outlier rejection.",
                diagnostics = noReadDiagnostics(samples.size, usedCount, gapSummary, measurement.fit.rmsResidualPx, timingBasis = timingBasis),
            )
        }
        if (rejectedCount > config.maxRejectedOutlierCount) {
            return VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
                "Too many detections were rejected as outliers for an honest estimate.",
                diagnostics = noReadDiagnostics(samples.size, usedCount, gapSummary, measurement.fit.rmsResidualPx, timingBasis = timingBasis),
            )
        }
        if (config.requireFittedPathProgression && !progressesAlongFittedPath(detections, measurement)) {
            return VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
                "Detections do not progress consistently along the fitted 2D path.",
                diagnostics = noReadDiagnostics(samples.size, usedCount, gapSummary, measurement.fit.rmsResidualPx, timingBasis = timingBasis),
            )
        }
        val speedFloorSkippedWarning = config.minEstimateMilesPerHour
            ?.takeUnless { shouldApplyMinimumSpeedGate(config.minimumSpeedGatePolicy, scale.basis) }
            ?.let { "Minimum hit-speed gate was skipped because the scale basis is ${scale.basis}." }
        config.minEstimateMilesPerHour
            ?.takeIf { shouldApplyMinimumSpeedGate(config.minimumSpeedGatePolicy, scale.basis) }
            ?.let { minimum ->
                if (measurement.milesPerHour < minimum) {
                    return VisualEstimateOutcome.NoRead(
                        VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
                        "Selected motion is below the configured hit-ball speed threshold.",
                        diagnostics = noReadDiagnostics(samples.size, usedCount, gapSummary, measurement.fit.rmsResidualPx, timingBasis = timingBasis),
                    )
                }
            }

        val diagnostics = VisualEstimateDiagnostics(
            frameCount = samples.size,
            detectionCount = usedCount,
            timingBasis = timingBasis,
            timestampGapSummary = gapSummary,
            fitResidualPx = measurement.fit.rmsResidualPx,
            confidence = confidenceFor(samples.size, gapSummary, measurement.fit.rmsResidualPx, maxRmsResidualPx, intervalCoherence, scale.basis),
            scaleBasis = scale.basis,
            levelReference = config.levelReference,
            assumptions = assumptionsFor(timingBasis, scale.basis, config.levelReference),
            warnings = buildList {
                if (rejectedCount == 1) add("One visual outlier was rejected.")
                if (inferredFrameDeltas) {
                    add("Visual frame-delta inference used known per-frame interval.")
                }
                if (intervalCoherence is IntervalCoherenceOutcome.Coalesced) {
                    add("Centroid displacement supports skipped/coalesced interval inference.")
                }
                scaleChangeWarning?.let(::add)
                scale.warnings.forEach(::add)
                speedFloorSkippedWarning?.let(::add)
            },
        )
        return VisualEstimateResultFactory.successOrNoRead(
            milesPerHour = measurement.milesPerHour,
            launchAngleDegrees = LevelReferenceCalculator.correctLaunchAngleDegrees(
                imageLaunchAngleDegrees = measurement.launchAngleDegrees,
                levelReference = config.levelReference,
            ),
            diagnostics = diagnostics,
            launchHeightFeet = config.launchHeightFeet,
        )
    }
}

private fun assumptionsFor(
    timingBasis: EstimateTimingBasis,
    scaleBasis: EstimateScaleBasis,
    levelReference: LevelReferenceSnapshot?,
): List<String> =
    buildList {
        add(VisualEstimateDiagnostics.PLANAR_MOTION_ASSUMPTION)
        if (levelReference != null) {
            add(VisualEstimateDiagnostics.STATIC_IMU_LEVEL_ASSUMPTION)
        } else {
            add(VisualEstimateDiagnostics.NO_LEVEL_REFERENCE_ASSUMPTION)
        }
        if (timingBasis == EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE) {
            add(VisualEstimateDiagnostics.VISUAL_FRAME_DELTA_ASSUMPTION)
        }
        if (scaleBasis == EstimateScaleBasis.BALL_DIAMETER_SELF_CALIBRATION) {
            add(VisualEstimateDiagnostics.BALL_DIAMETER_SCALE_ASSUMPTION)
        }
        if (scaleBasis == EstimateScaleBasis.DEPTH_CORRECTED_DISTANCE_CALIBRATION) {
            add(VisualEstimateDiagnostics.DEPTH_CORRECTED_SCALE_ASSUMPTION)
            add(VisualEstimateDiagnostics.UNMEASURED_DEPTH_VELOCITY_ASSUMPTION)
        }
    }

private fun List<VisualEstimateTrackSample>.withoutStationaryPrefix(): List<VisualEstimateTrackSample> {
    if (size < ESTIMATE_MIN_USABLE_DETECTIONS) return this
    val first = first()
    val second = getOrNull(1) ?: return this
    if (hypot(second.xPx - first.xPx, second.yPx - first.yPx) >= STATIONARY_PREFIX_MOTION_THRESHOLD_PX) {
        return this
    }
    val firstMotionIndex = indexOfFirst { sample ->
        hypot(sample.xPx - first.xPx, sample.yPx - first.yPx) >= STATIONARY_PREFIX_MOTION_THRESHOLD_PX
    }
    if (firstMotionIndex <= 0) return this
    val trimmed = drop(firstMotionIndex)
    return trimmed.takeIf { it.size >= ESTIMATE_MIN_USABLE_DETECTIONS } ?: this
}

private sealed interface EstimateScaleResolution {
    data class Success(
        val pixelsPerFoot: Double,
        val basis: EstimateScaleBasis,
        val warnings: List<String> = emptyList(),
    ) : EstimateScaleResolution

    data class Failure(val noRead: VisualEstimateOutcome.NoRead) : EstimateScaleResolution
}

private fun resolveEstimateScale(
    calibration: MeasurementCalibrationState,
    samples: List<VisualEstimateTrackSample>,
    gapSummary: TimestampGapSummary,
    timingBasis: EstimateTimingBasis,
    config: VisualEstimatePipelineConfig,
): EstimateScaleResolution =
    when (val calibrationResult = calibration.pixelsPerFoot()) {
        is CalibrationResult.Success -> applyScaleMode(calibrationResult.pixelsPerFoot, samples, gapSummary, timingBasis, config)
        is CalibrationResult.Failure -> {
            if (
                config.scaleMode is VisualEstimateScaleMode.DepthCorrected ||
                calibration.hasCompleteDistanceCalibrationInput() ||
                config.knownBallDiameterFeet == null
            ) {
                EstimateScaleResolution.Failure(calibrationResult.toEstimateNoRead(samples, gapSummary, timingBasis))
            } else {
                resolveBallDiameterScale(samples, gapSummary, timingBasis, config)
            }
        }
    }

private fun applyScaleMode(
    calibratedPixelsPerFoot: Double,
    samples: List<VisualEstimateTrackSample>,
    gapSummary: TimestampGapSummary,
    timingBasis: EstimateTimingBasis,
    config: VisualEstimatePipelineConfig,
): EstimateScaleResolution {
    return when (val mode = config.scaleMode) {
        VisualEstimateScaleMode.SamePlane -> EstimateScaleResolution.Success(
            pixelsPerFoot = calibratedPixelsPerFoot,
            basis = EstimateScaleBasis.DISTANCE_CALIBRATION,
        )
        is VisualEstimateScaleMode.DepthCorrected -> {
            val ballDepth = mode.ballPlaneDepthFeet
            val calibrationDepth = mode.calibrationPlaneDepthFeet
            if (
                !calibratedPixelsPerFoot.isFinite() ||
                !ballDepth.isFinite() ||
                !calibrationDepth.isFinite() ||
                calibratedPixelsPerFoot <= 0.0 ||
                ballDepth <= 0.0 ||
                calibrationDepth <= 0.0
            ) {
                return EstimateScaleResolution.Failure(
                    VisualEstimateOutcome.NoRead(
                        VisualEstimateNoReadReason.BAD_CALIBRATION,
                        "Depth-corrected scale requires finite positive calibration and camera-to-plane depths.",
                        diagnostics = noReadDiagnostics(samples.size, samples.size, gapSummary, timingBasis = timingBasis),
                    ),
                )
            }
            val correctionFactor = calibrationDepth / ballDepth
            val correctedPixelsPerFoot = calibratedPixelsPerFoot * correctionFactor
            if (
                !correctionFactor.isFinite() ||
                !correctedPixelsPerFoot.isFinite() ||
                correctionFactor !in MIN_DEPTH_SCALE_CORRECTION_FACTOR..MAX_DEPTH_SCALE_CORRECTION_FACTOR
            ) {
                return EstimateScaleResolution.Failure(
                    VisualEstimateOutcome.NoRead(
                        VisualEstimateNoReadReason.BAD_CALIBRATION,
                        "Depth-corrected scale factor is outside the reviewed safe range.",
                        diagnostics = noReadDiagnostics(samples.size, samples.size, gapSummary, timingBasis = timingBasis),
                    ),
                )
            }
            EstimateScaleResolution.Success(
                pixelsPerFoot = correctedPixelsPerFoot,
                basis = EstimateScaleBasis.DEPTH_CORRECTED_DISTANCE_CALIBRATION,
                warnings = buildList {
                    add("Depth-corrected scale uses user-entered camera-to-plane depths.")
                    add("Depth correction factor=${correctionFactor.formatScaleWarning()}.")
                    if (correctionFactor < LOW_CONFIDENCE_DEPTH_SCALE_FACTOR_FLOOR || correctionFactor > LOW_CONFIDENCE_DEPTH_SCALE_FACTOR_CEILING) {
                        add("Depth correction factor is far from same-plane calibration.")
                    }
                },
            )
        }
    }
}

private fun MeasurementCalibrationState.hasCompleteDistanceCalibrationInput(): Boolean =
    pointA != null && pointB != null && knownDistanceFeet != null

private fun resolveBallDiameterScale(
    samples: List<VisualEstimateTrackSample>,
    gapSummary: TimestampGapSummary,
    timingBasis: EstimateTimingBasis,
    config: VisualEstimatePipelineConfig,
): EstimateScaleResolution {
    val knownBallDiameterFeet = config.knownBallDiameterFeet
    if (knownBallDiameterFeet == null || !knownBallDiameterFeet.isFinite() || knownBallDiameterFeet <= 0.0) {
        return EstimateScaleResolution.Failure(
            VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.BAD_CALIBRATION,
                "Known ball diameter must be finite and positive for self-calibration.",
                diagnostics = noReadDiagnostics(samples.size, samples.size, gapSummary, timingBasis = timingBasis),
            ),
        )
    }
    val diameters = samples.mapNotNull { it.apparentDiameterPx }
    if (diameters.size < config.minBallDiameterSamples) {
        return EstimateScaleResolution.Failure(
            VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.BAD_CALIBRATION,
                "Ball-diameter self-calibration requires apparent ball size in at least ${config.minBallDiameterSamples} detections.",
                diagnostics = noReadDiagnostics(samples.size, diameters.size, gapSummary, timingBasis = timingBasis),
            ),
        )
    }
    if (diameters.any { !it.isFinite() || it <= 0.0 }) {
        return EstimateScaleResolution.Failure(
            VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.BAD_CALIBRATION,
                "Apparent ball diameter must be finite and positive for self-calibration.",
                diagnostics = noReadDiagnostics(samples.size, diameters.size, gapSummary, timingBasis = timingBasis),
            ),
        )
    }
    return when (val calibrationResult = DistanceCalibration.pixelsPerFoot(diameters.median(), knownBallDiameterFeet)) {
        is CalibrationResult.Success -> EstimateScaleResolution.Success(
            pixelsPerFoot = calibrationResult.pixelsPerFoot,
            basis = EstimateScaleBasis.BALL_DIAMETER_SELF_CALIBRATION,
        )
        is CalibrationResult.Failure -> EstimateScaleResolution.Failure(calibrationResult.toEstimateNoRead(samples, gapSummary, timingBasis))
    }
}

private sealed interface VisualFrameDeltaInference {
    data class Success(
        val samples: List<VisualEstimateTrackSample>,
        val gapSummary: TimestampGapSummary,
    ) : VisualFrameDeltaInference

    data class Failure(val noRead: VisualEstimateOutcome.NoRead) : VisualFrameDeltaInference
}

private fun validateVisualFrameInterval(frameIntervalSeconds: Double): VisualEstimateOutcome.NoRead? =
    if (!frameIntervalSeconds.isFinite() || frameIntervalSeconds <= 0.0) {
        VisualEstimateOutcome.NoRead(
            VisualEstimateNoReadReason.BAD_TIMESTAMPS,
            "Visual frame-delta estimate requires a finite positive per-frame interval.",
            diagnostics = noReadDiagnostics(
                frameCount = 0,
                detectionCount = 0,
                timingBasis = EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE,
            ),
        )
    } else {
        null
    }

private fun inferVisualFrameDeltaTimestamps(
    samples: List<VisualEstimateTrackSample>,
    frameIntervalSeconds: Double,
): VisualFrameDeltaInference {
    if (samples.size < ESTIMATE_MIN_USABLE_DETECTIONS) {
        return visualDeltaNoRead(
            samples,
            VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
            "At least four usable detections are required for visual frame-delta timing.",
        )
    }
    val displacements = samples.zipWithNext().map { (previous, current) ->
        hypot(current.xPx - previous.xPx, current.yPx - previous.yPx)
    }
    if (displacements.any { !it.isFinite() || it < 0.0 }) {
        return visualDeltaNoRead(samples, VisualEstimateNoReadReason.AMBIGUOUS_TRACK, "Visual displacements must be finite.")
    }
    val positiveDisplacements = displacements.filter { it > VISUAL_DELTA_MIN_DISPLACEMENT_PX }
    if (positiveDisplacements.isEmpty()) {
        return visualDeltaNoRead(samples, VisualEstimateNoReadReason.AMBIGUOUS_TRACK, "Visual frame-delta timing needs positive centroid motion.")
    }
    val unitDisplacement = positiveDisplacements.minOrNull() ?: return visualDeltaNoRead(
        samples,
        VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
        "Visual frame-delta timing could not identify a base displacement.",
    )
    var currentTimeSeconds = 0.0
    val inferredTimes = mutableListOf(0.0)
    displacements.forEach { displacement ->
        val intervals = max(1, (displacement / unitDisplacement).roundToInt())
        currentTimeSeconds += intervals * frameIntervalSeconds
        inferredTimes += currentTimeSeconds
    }
    val inferredSamples = samples.zip(inferredTimes).map { (sample, timestampSeconds) ->
        sample.copy(timestampSeconds = timestampSeconds)
    }
    val gaps = inferredTimes.zipWithNext().map { (previous, current) -> current - previous }
    val gapSummary = TimestampGapSummary(
        intervalCount = gaps.size,
        minGapSeconds = gaps.minOrNull() ?: frameIntervalSeconds,
        medianGapSeconds = gaps.median(),
        maxGapSeconds = gaps.maxOrNull() ?: frameIntervalSeconds,
    )
    if (!gapSummary.hasOnlyFiniteValues()) {
        return visualDeltaNoRead(samples, VisualEstimateNoReadReason.BAD_TIMESTAMPS, "Inferred visual frame-delta gaps were invalid.")
    }
    return VisualFrameDeltaInference.Success(inferredSamples, gapSummary)
}

private fun visualDeltaNoRead(
    samples: List<VisualEstimateTrackSample>,
    reason: VisualEstimateNoReadReason,
    message: String,
): VisualFrameDeltaInference.Failure =
    VisualFrameDeltaInference.Failure(
        VisualEstimateOutcome.NoRead(
            reason = reason,
            message = message,
            diagnostics = noReadDiagnostics(
                frameCount = samples.size,
                detectionCount = samples.size,
                timingBasis = EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE,
            ),
        ),
    )

private fun validateConfig(config: VisualEstimatePipelineConfig): VisualEstimateOutcome.NoRead? {
    if (!config.maxEstimateRmsResidualPx.isFinite() || config.maxEstimateRmsResidualPx <= 0.0) {
        return configNoRead("Estimate residual threshold must be finite and positive.")
    }
    config.maxEstimateRmsResidualDiameterFraction?.let {
        if (!it.isFinite() || it <= 0.0) {
            return configNoRead("Estimate diameter-scaled residual threshold must be finite and positive.")
        }
    }
    if (!config.minTimeSpreadSecondsSquared.isFinite() || config.minTimeSpreadSecondsSquared <= 0.0) {
        return configNoRead("Estimate minimum time spread must be finite and positive.")
    }
    if (config.maxOutlierPasses < 0 || config.maxRejectedOutlierCount < 0) {
        return configNoRead("Estimate outlier gates must be non-negative.")
    }
    if (!config.minOutlierRmsImprovementPx.isFinite() || config.minOutlierRmsImprovementPx < 0.0) {
        return configNoRead("Estimate outlier improvement must be finite and non-negative.")
    }
    if (!config.maxTimestampGapSpreadRatio.isFinite() || config.maxTimestampGapSpreadRatio <= 1.0) {
        return configNoRead("Estimate timestamp gap ratio must be finite and greater than one.")
    }
    if (!config.maxTimestampDisplacementRatioError.isFinite() || config.maxTimestampDisplacementRatioError < 0.0) {
        return configNoRead("Estimate timestamp/displacement ratio error must be finite and non-negative.")
    }
    if (!config.maxApparentScaleChangeRatio.isFinite() || config.maxApparentScaleChangeRatio <= 1.0) {
        return configNoRead("Estimate apparent scale-change ratio must be finite and greater than one.")
    }
    if (config.knownBallDiameterFeet != null && (!config.knownBallDiameterFeet.isFinite() || config.knownBallDiameterFeet <= 0.0)) {
        return configNoRead("Known ball diameter must be finite and positive when supplied.")
    }
    if (config.minBallDiameterSamples < 1) {
        return configNoRead("Ball-diameter self-calibration sample count must be positive.")
    }
    config.minEstimateMilesPerHour?.let {
        if (!it.isFinite() || it < 0.0) {
            return configNoRead("Minimum estimate speed must be finite and non-negative when supplied.")
        }
    }
    when (val scaleMode = config.scaleMode) {
        VisualEstimateScaleMode.SamePlane -> Unit
        is VisualEstimateScaleMode.DepthCorrected -> {
            if (
                !scaleMode.ballPlaneDepthFeet.isFinite() ||
                !scaleMode.calibrationPlaneDepthFeet.isFinite() ||
                scaleMode.ballPlaneDepthFeet <= 0.0 ||
                scaleMode.calibrationPlaneDepthFeet <= 0.0
            ) {
                return VisualEstimateOutcome.NoRead(
                    VisualEstimateNoReadReason.BAD_CALIBRATION,
                    "Depth-corrected scale depths must be finite and positive.",
                )
            }
        }
    }
    return null
}

private fun configNoRead(message: String): VisualEstimateOutcome.NoRead =
    VisualEstimateOutcome.NoRead(VisualEstimateNoReadReason.NON_FINITE_RESULT, message)

private fun timestampGapSummary(timestampsSeconds: List<Double>): TimestampGapSummary? {
    if (timestampsSeconds.size < 2) return null
    if (timestampsSeconds.any { !it.isFinite() }) return null
    if (timestampsSeconds.zipWithNext().any { (previous, current) -> current <= previous }) return null
    val gaps = timestampsSeconds.zipWithNext().map { (previous, current) -> current - previous }
    return TimestampGapSummary(
        intervalCount = gaps.size,
        minGapSeconds = gaps.minOrNull() ?: return null,
        medianGapSeconds = gaps.median(),
        maxGapSeconds = gaps.maxOrNull() ?: return null,
    ).takeIf { it.hasOnlyFiniteValues() }
}

private sealed interface ScaleChangeCheck {
    data object None : ScaleChangeCheck
    data class Warning(val message: String) : ScaleChangeCheck
    data class Failure(val noRead: VisualEstimateOutcome.NoRead) : ScaleChangeCheck
}

private fun scaleChangeCheck(
    samples: List<VisualEstimateTrackSample>,
    gapSummary: TimestampGapSummary,
    config: VisualEstimatePipelineConfig,
    timingBasis: EstimateTimingBasis,
    scaleBasis: EstimateScaleBasis,
): ScaleChangeCheck {
    val diameters = samples.mapNotNull { it.apparentDiameterPx }
    if (diameters.isEmpty()) return ScaleChangeCheck.None
    if (diameters.any { !it.isFinite() || it <= 0.0 }) {
        return ScaleChangeCheck.Failure(
            VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.DETECTION_FAILED,
                "Apparent ball size must be finite and positive when supplied.",
                diagnostics = noReadDiagnostics(samples.size, samples.size, gapSummary, timingBasis = timingBasis),
            ),
        )
    }
    if (diameters.size < 2) return ScaleChangeCheck.None
    val ratio = (diameters.maxOrNull() ?: return ScaleChangeCheck.None) /
        (diameters.minOrNull() ?: return ScaleChangeCheck.None)
    if (ratio > config.maxApparentScaleChangeRatio) {
        if (config.allowApparentScaleChangeEstimate) {
            return ScaleChangeCheck.Warning(
                "Apparent blob size varied across the track; accepted because the full trajectory/timing fit remained coherent.",
            )
        }
        return ScaleChangeCheck.Failure(
            VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.PLANAR_ASSUMPTION_VIOLATED,
                "Apparent ball size changed too much; depth motion violates the calibrated-plane estimate assumption.",
                diagnostics = noReadDiagnostics(
                    frameCount = samples.size,
                    detectionCount = samples.size,
                    gapSummary = gapSummary,
                    warnings = listOf("Strong apparent scale change across the track."),
                    timingBasis = timingBasis,
                ),
            ),
        )
    }
    return ScaleChangeCheck.None
}

private sealed interface IntervalCoherenceOutcome {
    data object Plain : IntervalCoherenceOutcome
    data object Coalesced : IntervalCoherenceOutcome
    data class Failure(val noRead: VisualEstimateOutcome.NoRead) : IntervalCoherenceOutcome
}

private fun intervalCoherence(
    samples: List<VisualEstimateTrackSample>,
    gapSummary: TimestampGapSummary,
    config: VisualEstimatePipelineConfig,
    timingBasis: EstimateTimingBasis,
): IntervalCoherenceOutcome {
    if (gapSummary.maxToMedianRatio <= config.maxTimestampGapSpreadRatio) {
        return IntervalCoherenceOutcome.Plain
    }
    val timestampGaps = samples.zipWithNext().map { (previous, current) -> current.timestampSeconds - previous.timestampSeconds }
    val displacements = samples.zipWithNext().map { (previous, current) ->
        kotlin.math.hypot(current.xPx - previous.xPx, current.yPx - previous.yPx)
    }
    val medianDisplacement = displacements.median()
    if (!medianDisplacement.isFinite() || medianDisplacement <= 0.0) {
        return IntervalCoherenceOutcome.Failure(
            VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
                "Timestamp gaps are uneven but visual displacement cannot support skipped-frame inference.",
                diagnostics = noReadDiagnostics(samples.size, samples.size, gapSummary, timingBasis = timingBasis),
            ),
        )
    }
    val ratiosAgree = timestampGaps.zip(displacements).all { (gap, displacement) ->
        val timestampRatio = gap / gapSummary.medianGapSeconds
        val displacementRatio = displacement / medianDisplacement
        val tolerance = max(ABSOLUTE_INTERVAL_RATIO_TOLERANCE, timestampRatio * config.maxTimestampDisplacementRatioError)
        abs(timestampRatio - displacementRatio) <= tolerance
    }
    if (!ratiosAgree) {
        return IntervalCoherenceOutcome.Failure(
            VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
                "Timestamp gaps and centroid displacement disagree; skipped/coalesced interval inference is not coherent.",
                diagnostics = noReadDiagnostics(samples.size, samples.size, gapSummary, timingBasis = timingBasis),
            ),
        )
    }
    return IntervalCoherenceOutcome.Coalesced
}

private fun progressesAlongFittedPath(
    detections: List<Detection>,
    measurement: VelocityMeasurement,
): Boolean {
    val vx = measurement.fit.vxPxPerSecond
    val vy = measurement.fit.vyPxPerSecond
    val speed = measurement.fit.speedPxPerSecond
    if (!speed.isFinite() || speed <= 0.0) return false
    val unitX = vx / speed
    val unitY = vy / speed
    val used = measurement.fit.usedOriginalIndices.map { detections[it] }
    if (used.size < ESTIMATE_MIN_USABLE_DETECTIONS) return false
    val projections = used.map { detection ->
        detection.xPx * unitX + detection.yPx * unitY
    }
    return projections.zipWithNext().all { (previous, current) ->
        current + PROGRESSION_TOLERANCE_PX >= previous
    } && projections.last() > projections.first()
}

private fun confidenceFor(
    frameCount: Int,
    gapSummary: TimestampGapSummary,
    residualPx: Double,
    maxRmsResidualPx: Double,
    intervalCoherence: IntervalCoherenceOutcome,
    scaleBasis: EstimateScaleBasis,
): VisualEstimateConfidence =
    when {
        intervalCoherence is IntervalCoherenceOutcome.Coalesced -> VisualEstimateConfidence.LOW
        frameCount >= 6 &&
            gapSummary.maxToMedianRatio <= 1.25 &&
            residualPx <= maxRmsResidualPx * 0.5 -> {
                if (scaleBasis == EstimateScaleBasis.DEPTH_CORRECTED_DISTANCE_CALIBRATION) {
                    VisualEstimateConfidence.MEDIUM
                } else {
                    VisualEstimateConfidence.HIGH
                }
            }
        frameCount >= 5 &&
            gapSummary.maxToMedianRatio <= 1.75 &&
            residualPx <= maxRmsResidualPx * 0.75 -> VisualEstimateConfidence.MEDIUM
        else -> VisualEstimateConfidence.LOW
    }

private fun effectiveMaxRmsResidualPx(
    samples: List<VisualEstimateTrackSample>,
    config: VisualEstimatePipelineConfig,
): Double {
    val fraction = config.maxEstimateRmsResidualDiameterFraction ?: return config.maxEstimateRmsResidualPx
    val medianDiameter = samples.mapNotNull { it.apparentDiameterPx }
        .filter { it.isFinite() && it > 0.0 }
        .median()
    val diameterScaled = medianDiameter * fraction
    return max(config.maxEstimateRmsResidualPx, diameterScaled)
}

private fun shouldApplyMinimumSpeedGate(
    policy: MinimumSpeedGatePolicy,
    scaleBasis: EstimateScaleBasis,
): Boolean =
    when (policy) {
        MinimumSpeedGatePolicy.DISABLED -> false
        MinimumSpeedGatePolicy.SAME_PLANE_ONLY -> scaleBasis == EstimateScaleBasis.DISTANCE_CALIBRATION
        MinimumSpeedGatePolicy.ALL_TRUSTED_SCALE -> scaleBasis != EstimateScaleBasis.DEPTH_CORRECTED_DISTANCE_CALIBRATION
    }

private fun CalibrationResult.Failure.toEstimateNoRead(
    samples: List<VisualEstimateTrackSample>,
    gapSummary: TimestampGapSummary,
    timingBasis: EstimateTimingBasis,
): VisualEstimateOutcome.NoRead =
    VisualEstimateOutcome.NoRead(
        reason = when (reason) {
            CalibrationFailure.INVALID_POINT,
            CalibrationFailure.INVALID_PIXEL_DISTANCE,
            CalibrationFailure.INVALID_REAL_DISTANCE -> VisualEstimateNoReadReason.BAD_CALIBRATION
        },
        message = message,
        diagnostics = noReadDiagnostics(samples.size, samples.size, gapSummary, timingBasis = timingBasis),
    )

private fun MeasurementOutcome.Failure.toEstimateNoRead(
    samples: List<VisualEstimateTrackSample>,
    gapSummary: TimestampGapSummary,
    timingBasis: EstimateTimingBasis,
): VisualEstimateOutcome.NoRead =
    VisualEstimateOutcome.NoRead(
        reason = when (reason) {
            MeasurementFailure.INSUFFICIENT_DETECTIONS -> VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS
            MeasurementFailure.BAD_TIMESTAMP,
            MeasurementFailure.INVALID_DETECTION,
            MeasurementFailure.INVALID_OPTIONS -> VisualEstimateNoReadReason.BAD_TIMESTAMPS
            MeasurementFailure.INVALID_CALIBRATION -> VisualEstimateNoReadReason.BAD_CALIBRATION
            MeasurementFailure.NON_FINITE_FIT -> VisualEstimateNoReadReason.NON_FINITE_RESULT
            MeasurementFailure.EXCESSIVE_RESIDUAL -> VisualEstimateNoReadReason.EXCESSIVE_RESIDUAL
        },
        message = message,
        diagnostics = noReadDiagnostics(samples.size, samples.size, gapSummary, timingBasis = timingBasis),
    )

private fun noReadDiagnostics(
    frameCount: Int,
    detectionCount: Int,
    gapSummary: TimestampGapSummary? = null,
    residualPx: Double? = null,
    warnings: List<String> = emptyList(),
    timingBasis: EstimateTimingBasis = EstimateTimingBasis.REAL_PER_FRAME_TIMESTAMPS,
): VisualEstimateDiagnostics =
    VisualEstimateDiagnostics(
        frameCount = max(frameCount, 0),
        detectionCount = max(detectionCount, 0),
        timingBasis = timingBasis,
        timestampGapSummary = gapSummary,
        fitResidualPx = residualPx,
        confidence = null,
        warnings = warnings,
    )

private fun List<Double>.median(): Double {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}

private fun Double.formatScaleWarning(): String =
    "%.3f".format(this)

private const val ESTIMATE_MIN_USABLE_DETECTIONS = 4
private const val MIN_DEPTH_SCALE_CORRECTION_FACTOR = 0.10
private const val MAX_DEPTH_SCALE_CORRECTION_FACTOR = 10.0
private const val LOW_CONFIDENCE_DEPTH_SCALE_FACTOR_FLOOR = 0.50
private const val LOW_CONFIDENCE_DEPTH_SCALE_FACTOR_CEILING = 2.0
private const val PROGRESSION_TOLERANCE_PX = 1.0e-6
private const val ABSOLUTE_INTERVAL_RATIO_TOLERANCE = 0.5
private const val VISUAL_DELTA_MIN_DISPLACEMENT_PX = 1.0e-6
private const val STATIONARY_PREFIX_MOTION_THRESHOLD_PX = 1.0
