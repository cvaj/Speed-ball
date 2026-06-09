package com.speedball.app.measurement

import com.speedball.core.measurement.VelocityMeasurement
import com.speedball.core.physics.TrajectoryResult
import kotlin.math.max

/** Immutable ARGB frame plus the timestamp attached by its source. */
data class RgbFrame(
    val width: Int,
    val height: Int,
    val argbPixels: IntArray,
    val timestampSeconds: Double,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RgbFrame &&
            width == other.width &&
            height == other.height &&
            argbPixels.contentEquals(other.argbPixels) &&
            timestampSeconds == other.timestampSeconds

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + argbPixels.contentHashCode()
        result = 31 * result + timestampSeconds.hashCode()
        return result
    }
}

/** Ordered frame batch. Every consumer must validate timestamps before use. */
data class TimedFrameSequence(
    val frames: List<RgbFrame>,
)

/**
 * Evidence token for a frame source whose image/timestamp pairing has been proven.
 *
 * Integration fixtures define synthetic implementations in test source only.
 * Main source may expose only the vetted Phase 9 direct-source implementation,
 * and direct-source measurement must use the inseparable bound input emitted by
 * the vetted factory rather than caller-settable sequence fields.
 */
interface MeasurementTimingProof {
    val evidenceLabel: String
}

/** Fail-loud reason a measurement run cannot safely display a speed. */
enum class MeasurementRunFailure {
    UNPROVEN_TIMING,
    BAD_FRAME_SEQUENCE,
    DETECTION_FAILED,
    INSUFFICIENT_DETECTIONS,
    BAD_CALIBRATION,
    MEASUREMENT_REJECTED,
    RESOURCE_LIMIT_EXCEEDED,
}

/** End-to-end measurement outcome. Failures intentionally carry no partial mph. */
sealed interface MeasurementRunOutcome {
    data class Success(
        val measurement: VelocityMeasurement,
        val trajectory: TrajectoryResult,
        val detectionCount: Int,
        val timingProof: MeasurementTimingProof,
    ) : MeasurementRunOutcome

    data class NoRead(
        val reason: MeasurementRunFailure,
        val message: String,
    ) : MeasurementRunOutcome
}

/** Timing source used by the S10+ visual estimate path. */
enum class EstimateTimingBasis {
    REAL_PER_FRAME_TIMESTAMPS,
    VISUAL_FRAME_DELTA_INFERENCE,
    IMPORT_CONTAINER_PRESENTATION_TIMESTAMPS,
    IMPORT_PARTIAL_TIMESTAMP_VISUAL_GAP_RECONCILIATION,
    IMPORT_VISUAL_FRAME_DELTA_INFERENCE,
    RECORDED_CAPTURE_FRAME_INTERVAL,
    RECORDED_CONTAINER_PRESENTATION_TIMESTAMPS,
}

/** User-facing confidence bucket for an estimate-only result. */
enum class VisualEstimateConfidence {
    HIGH,
    MEDIUM,
    LOW,
}

/** Pixel-to-distance scale source used by an estimate-only result. */
enum class EstimateScaleBasis {
    DISTANCE_CALIBRATION,
    BALL_DIAMETER_SELF_CALIBRATION,
    DEPTH_CORRECTED_DISTANCE_CALIBRATION,
}

/** Fail-loud reason the estimate path cannot honestly display a speed. */
enum class VisualEstimateNoReadReason {
    BAD_CALIBRATION,
    BAD_TIMESTAMPS,
    DETECTION_FAILED,
    NO_FOREGROUND_MOTION,
    FOREGROUND_AMBIGUOUS,
    BALL_NOT_ISOLATED,
    GLOBAL_CAMERA_MOTION,
    GLOBAL_LIGHTING_CHANGE,
    INSUFFICIENT_DETECTIONS,
    AMBIGUOUS_TRACK,
    EXCESSIVE_RESIDUAL,
    PLANAR_ASSUMPTION_VIOLATED,
    NON_FINITE_RESULT,
    RESOURCE_LIMIT_EXCEEDED,
}

/** Summary of real per-frame timestamp gaps used by an estimate. */
data class TimestampGapSummary(
    val intervalCount: Int,
    val minGapSeconds: Double,
    val medianGapSeconds: Double,
    val maxGapSeconds: Double,
) {
    val maxToMedianRatio: Double
        get() = maxGapSeconds / medianGapSeconds

    fun hasOnlyFiniteValues(): Boolean =
        intervalCount > 0 &&
            minGapSeconds.isFinite() &&
            medianGapSeconds.isFinite() &&
            maxGapSeconds.isFinite() &&
            minGapSeconds > 0.0 &&
            medianGapSeconds > 0.0 &&
            maxGapSeconds > 0.0 &&
            maxToMedianRatio.isFinite()
}

/**
 * Diagnostics attached to estimate-only outcomes.
 *
 * Estimate mode assumes the ball travels approximately across the calibrated
 * image plane. Strong toward/away depth motion changes apparent scale and must
 * lower confidence or produce a no-read before speed is shown.
 */
data class VisualEstimateDiagnostics(
    val frameCount: Int,
    val detectionCount: Int,
    val timingBasis: EstimateTimingBasis,
    val timestampGapSummary: TimestampGapSummary?,
    val fitResidualPx: Double?,
    val confidence: VisualEstimateConfidence?,
    val candidateFrameCount: Int? = null,
    val candidateBlobCount: Int? = null,
    val selectedSampleCount: Int? = null,
    val scaleBasis: EstimateScaleBasis = EstimateScaleBasis.DISTANCE_CALIBRATION,
    val levelReference: LevelReferenceSnapshot? = null,
    val assumptions: List<String> = listOf(PLANAR_MOTION_ASSUMPTION),
    val warnings: List<String> = emptyList(),
) {
    companion object {
        const val PLANAR_MOTION_ASSUMPTION: String =
            "Estimate assumes the ball travels across the calibrated image plane."
        const val STATIC_IMU_LEVEL_ASSUMPTION: String =
            "Provisional launch-angle correction uses one still-phone IMU gravity snapshot and assumes the tripod does not move before capture; physical sign validation remains open."
        const val NO_LEVEL_REFERENCE_ASSUMPTION: String =
            "No level reference was captured; launch angle is not corrected for camera tilt."
        const val VISUAL_FRAME_DELTA_ASSUMPTION: String =
            "Estimate assumes the smallest observed frame gap is one native interval; uniformly dropped frames would bias speed high."
        const val BALL_DIAMETER_SCALE_ASSUMPTION: String =
            "Scale uses entered ball diameter and apparent short-axis diameter; wrong ball type or motion blur biases speed."
        const val DEPTH_CORRECTED_SCALE_ASSUMPTION: String =
            "Depth-corrected scale assumes user-entered camera-to-plane depths and fronto-parallel ball travel."
        const val UNMEASURED_DEPTH_VELOCITY_ASSUMPTION: String =
            "Monocular estimate does not measure toward-or-away velocity; depth-corrected speed is lower confidence."
    }
}

/**
 * Estimate-only outcome for personal visual speed estimates.
 *
 * This type deliberately does not implement [MeasurementRunOutcome] and carries
 * no [MeasurementTimingProof]. It cannot satisfy a strict proof-token-backed
 * measurement requirement.
 */
sealed interface VisualEstimateOutcome {
    class Success internal constructor(
        val milesPerHour: Double,
        val launchAngleDegrees: Double?,
        val diagnostics: VisualEstimateDiagnostics,
        val launchHeightFeet: Double,
    ) : VisualEstimateOutcome {
        override fun equals(other: Any?): Boolean =
            this === other ||
                other is Success &&
                milesPerHour == other.milesPerHour &&
                launchAngleDegrees == other.launchAngleDegrees &&
                diagnostics == other.diagnostics &&
                launchHeightFeet == other.launchHeightFeet

        override fun hashCode(): Int {
            var result = milesPerHour.hashCode()
            result = 31 * result + (launchAngleDegrees?.hashCode() ?: 0)
            result = 31 * result + diagnostics.hashCode()
            result = 31 * result + launchHeightFeet.hashCode()
            return result
        }

        override fun toString(): String =
            "Success(milesPerHour=$milesPerHour, launchAngleDegrees=$launchAngleDegrees, diagnostics=$diagnostics, launchHeightFeet=$launchHeightFeet)"
    }

    data class NoRead(
        val reason: VisualEstimateNoReadReason,
        val message: String,
        val diagnostics: VisualEstimateDiagnostics? = null,
    ) : VisualEstimateOutcome
}

/** Constructs estimate outcomes while enforcing the estimate honesty contract. */
object VisualEstimateResultFactory {
    fun successOrNoRead(
        milesPerHour: Double,
        launchAngleDegrees: Double?,
        diagnostics: VisualEstimateDiagnostics,
        launchHeightFeet: Double = 4.0,
    ): VisualEstimateOutcome {
        validateSuccessFields(milesPerHour, launchAngleDegrees, diagnostics, launchHeightFeet)?.let { return it }
        return VisualEstimateOutcome.Success(
            milesPerHour = milesPerHour,
            launchAngleDegrees = launchAngleDegrees,
            diagnostics = diagnostics,
            launchHeightFeet = launchHeightFeet,
        )
    }
}

private fun validateSuccessFields(
    milesPerHour: Double,
    launchAngleDegrees: Double?,
    diagnostics: VisualEstimateDiagnostics,
    launchHeightFeet: Double,
): VisualEstimateOutcome.NoRead? {
    if (!milesPerHour.isFinite() || milesPerHour < 0.0 || launchAngleDegrees?.isFinite() == false || !launchHeightFeet.isFinite() || launchHeightFeet < 0.0) {
        return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.NON_FINITE_RESULT,
            message = "Estimate produced a non-finite speed, angle, or launch height.",
            diagnostics = diagnostics,
        )
    }
    if (diagnostics.frameCount < 1 || diagnostics.detectionCount < 1) {
        return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
            message = "Estimate diagnostics must include positive frame and detection counts.",
            diagnostics = diagnostics,
        )
    }
    val summary = diagnostics.timestampGapSummary
        ?: return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
            message = "Estimate requires real per-frame timestamp gaps.",
            diagnostics = diagnostics,
        )
    if (!summary.hasOnlyFiniteValues()) {
        return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
            message = "Estimate timestamp gaps must be finite and positive.",
            diagnostics = diagnostics,
        )
    }
    val residual = diagnostics.fitResidualPx
    if (residual == null || !residual.isFinite() || residual < 0.0) {
        return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.EXCESSIVE_RESIDUAL,
            message = "Estimate residual must be finite and non-negative.",
            diagnostics = diagnostics,
        )
    }
    if (diagnostics.confidence == null) {
        return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
            message = "Estimate confidence is required before displaying speed.",
            diagnostics = diagnostics,
        )
    }
    if (diagnostics.assumptions.none { it.contains("calibrated image plane", ignoreCase = true) }) {
        return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.PLANAR_ASSUMPTION_VIOLATED,
            message = "Estimate must disclose the calibrated-plane motion assumption.",
            diagnostics = diagnostics,
        )
    }
    if (
        diagnostics.timingBasis == EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE &&
        diagnostics.assumptions.none { it.contains("smallest observed frame gap", ignoreCase = true) }
    ) {
        return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
            message = "Visual frame-delta estimates must disclose their absolute-scale timing assumption.",
            diagnostics = diagnostics,
        )
    }
    if (
        diagnostics.scaleBasis == EstimateScaleBasis.BALL_DIAMETER_SELF_CALIBRATION &&
        diagnostics.assumptions.none { it.contains("entered ball diameter", ignoreCase = true) }
    ) {
        return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.BAD_CALIBRATION,
            message = "Ball-diameter self-calibrated estimates must disclose the apparent-diameter scale assumption.",
            diagnostics = diagnostics,
        )
    }
    if (
        diagnostics.scaleBasis == EstimateScaleBasis.DEPTH_CORRECTED_DISTANCE_CALIBRATION &&
        diagnostics.assumptions.none { it.contains("Depth-corrected scale", ignoreCase = true) }
    ) {
        return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.BAD_CALIBRATION,
            message = "Depth-corrected estimates must disclose the camera-to-plane depth assumption.",
            diagnostics = diagnostics,
        )
    }
    if (diagnostics.detectionCount > diagnostics.frameCount) {
        return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
            message = "Estimate diagnostics cannot contain more detections than frames.",
            diagnostics = diagnostics,
        )
    }
    return null
}

/** Converts real frame timestamps to seconds and gap diagnostics for estimate mode. */
object EstimateTimestampModel {
    fun fromRealTimestampNanos(timestampsNanos: List<Long>): EstimateTimestampOutcome {
        if (timestampsNanos.size < 2) {
            return EstimateTimestampOutcome.Failure(
                VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                "At least two real frame timestamps are required.",
            )
        }
        val timestampsSeconds = timestampsNanos.map { timestamp ->
            if (timestamp < 0L) {
                return EstimateTimestampOutcome.Failure(
                    VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                    "Frame timestamps must be non-negative.",
                )
            }
            timestamp / NANOS_PER_SECOND
        }
        timestampsSeconds.zipWithNext().forEach { (previous, current) ->
            if (current <= previous) {
                return EstimateTimestampOutcome.Failure(
                    VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                    "Frame timestamps must be strictly increasing.",
                )
            }
        }
        val gaps = timestampsSeconds.zipWithNext().map { (previous, current) -> current - previous }
        val summary = TimestampGapSummary(
            intervalCount = gaps.size,
            minGapSeconds = gaps.minOrNull() ?: 0.0,
            medianGapSeconds = gaps.median(),
            maxGapSeconds = gaps.maxOrNull() ?: 0.0,
        )
        if (!summary.hasOnlyFiniteValues()) {
            return EstimateTimestampOutcome.Failure(
                VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                "Frame timestamp gaps must be finite and positive.",
            )
        }
        return EstimateTimestampOutcome.Success(timestampsSeconds, summary)
    }

    fun fromNominalCadence(frameCount: Int, framesPerSecond: Double): EstimateTimestampOutcome.Failure {
        val sanitizedFrameCount = max(frameCount, 0)
        val sanitizedRate = if (framesPerSecond.isFinite() && framesPerSecond > 0.0) framesPerSecond else 0.0
        return EstimateTimestampOutcome.Failure(
            VisualEstimateNoReadReason.BAD_TIMESTAMPS,
            "S10+ direct estimate mode requires real per-frame timestamps, not nominal cadence ($sanitizedFrameCount frames at $sanitizedRate fps).",
        )
    }
}

sealed interface EstimateTimestampOutcome {
    data class Success(
        val timestampsSeconds: List<Double>,
        val gapSummary: TimestampGapSummary,
    ) : EstimateTimestampOutcome

    data class Failure(
        val reason: VisualEstimateNoReadReason,
        val message: String,
    ) : EstimateTimestampOutcome
}

private const val NANOS_PER_SECOND = 1_000_000_000.0

private fun List<Double>.median(): Double {
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}
