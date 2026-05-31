package com.speedball.core.velocity

import com.speedball.core.measurement.MeasurementFailure
import com.speedball.core.model.Detection
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Numeric options for fitting image-space velocity. */
data class VelocityFitOptions(
    val minTimeSpreadSecondsSquared: Double,
    val maxOutlierPasses: Int = 0,
    val minOutlierRmsImprovementPx: Double = 0.0,
)

/** Fitted image-space velocity and quality metrics. */
data class VelocityFitResult(
    val xInterceptPx: Double,
    val yInterceptPx: Double,
    val vxPxPerSecond: Double,
    val vyPxPerSecond: Double,
    val speedPxPerSecond: Double,
    val launchAngleDegrees: Double,
    val rSquaredX: Double,
    val rSquaredY: Double,
    val rmsResidualPx: Double,
    val usedOriginalIndices: List<Int>,
) {
    fun hasOnlyFiniteValues(): Boolean =
        xInterceptPx.isFinite() &&
            yInterceptPx.isFinite() &&
            vxPxPerSecond.isFinite() &&
            vyPxPerSecond.isFinite() &&
            speedPxPerSecond.isFinite() &&
            launchAngleDegrees.isFinite() &&
            rSquaredX.isFinite() &&
            rSquaredY.isFinite() &&
            rmsResidualPx.isFinite()
}

/** Explicit fit outcome so invalid evidence cannot produce a default speed. */
sealed interface VelocityFitOutcome {
    data class Success(val fit: VelocityFitResult) : VelocityFitOutcome
    data class Failure(val reason: MeasurementFailure, val message: String) : VelocityFitOutcome
}

/** Fits straight-line velocity in raw image coordinates. */
object VelocityFit {
    private const val TIE_TOLERANCE = 1e-12

    fun fit(
        detections: List<Detection>,
        options: VelocityFitOptions,
    ): VelocityFitOutcome {
        validateOptions(options)?.let { return it }
        validateDetections(detections)?.let { return it }

        var active = detections.mapIndexed { index, detection -> IndexedDetection(index, detection) }
        if (hasInsufficientTimeSpread(active, options.minTimeSpreadSecondsSquared)) {
            return VelocityFitOutcome.Failure(
                MeasurementFailure.BAD_TIMESTAMP,
                "Detection timestamps are too close together to fit velocity.",
            )
        }
        var current = fitIndexed(active, options.minTimeSpreadSecondsSquared)
            ?: return VelocityFitOutcome.Failure(
                MeasurementFailure.NON_FINITE_FIT,
                "Fit produced a non-finite derived value.",
            )

        repeat(options.maxOutlierPasses) {
            if (active.size <= MIN_DETECTIONS) {
                return@repeat
            }

            var best: RemovalCandidate? = null
            active.indices.forEach { activeIndex ->
                val candidateData = active.filterIndexed { index, _ -> index != activeIndex }
                if (candidateData.size < MIN_DETECTIONS) {
                    return@forEach
                }
                if (hasInsufficientTimeSpread(candidateData, options.minTimeSpreadSecondsSquared)) {
                    return@forEach
                }
                val candidateFit = fitIndexed(candidateData, options.minTimeSpreadSecondsSquared) ?: return@forEach
                val improvement = current.rmsResidualPx - candidateFit.rmsResidualPx
                if (improvement < options.minOutlierRmsImprovementPx) {
                    return@forEach
                }
                val candidate = RemovalCandidate(
                    activeIndex = activeIndex,
                    originalIndex = active[activeIndex].originalIndex,
                    improvement = improvement,
                    fit = candidateFit,
                )
                if (best == null || candidate.betterThan(requireNotNull(best))) {
                    best = candidate
                }
            }

            val removal = best ?: return@repeat
            active = active.filterIndexed { index, _ -> index != removal.activeIndex }
            current = removal.fit
        }

        return VelocityFitOutcome.Success(current)
    }

    private fun RemovalCandidate.betterThan(other: RemovalCandidate): Boolean {
        val delta = improvement - other.improvement
        return delta > TIE_TOLERANCE || (abs(delta) <= TIE_TOLERANCE && originalIndex < other.originalIndex)
    }

    private fun fitIndexed(
        indexedDetections: List<IndexedDetection>,
        minTimeSpreadSecondsSquared: Double,
    ): VelocityFitResult? {
        val n = indexedDetections.size
        val meanT = indexedDetections.sumOf { it.detection.timestampSeconds } / n
        val centeredTime = indexedDetections.map { it.detection.timestampSeconds - meanT }
        val denominator = centeredTime.sumOf { it * it }
        if (!denominator.isFinite() || denominator <= minTimeSpreadSecondsSquared) {
            return null
        }

        val meanX = indexedDetections.sumOf { it.detection.xPx } / n
        val meanY = indexedDetections.sumOf { it.detection.yPx } / n
        val vx = indexedDetections.indices.sumOf { index -> centeredTime[index] * (indexedDetections[index].detection.xPx - meanX) } / denominator
        val vy = indexedDetections.indices.sumOf { index -> centeredTime[index] * (indexedDetections[index].detection.yPx - meanY) } / denominator
        val xIntercept = meanX - vx * meanT
        val yIntercept = meanY - vy * meanT
        val speed = hypot(vx, vy)
        val angleDegrees = atan2(-vy, abs(vx)) * 180.0 / PI

        val residualSquaredSum = indexedDetections.sumOf {
            val t = it.detection.timestampSeconds
            val xResidual = it.detection.xPx - (xIntercept + vx * t)
            val yResidual = it.detection.yPx - (yIntercept + vy * t)
            xResidual * xResidual + yResidual * yResidual
        }
        val rmsResidual = sqrt(residualSquaredSum / n)
        val rSquaredX = rSquared(
            values = indexedDetections.map { it.detection.xPx },
            predictions = indexedDetections.map { xIntercept + vx * it.detection.timestampSeconds },
        )
        val rSquaredY = rSquared(
            values = indexedDetections.map { it.detection.yPx },
            predictions = indexedDetections.map { yIntercept + vy * it.detection.timestampSeconds },
        )

        val result = VelocityFitResult(
            xInterceptPx = xIntercept,
            yInterceptPx = yIntercept,
            vxPxPerSecond = vx,
            vyPxPerSecond = vy,
            speedPxPerSecond = speed,
            launchAngleDegrees = angleDegrees,
            rSquaredX = rSquaredX,
            rSquaredY = rSquaredY,
            rmsResidualPx = rmsResidual,
            usedOriginalIndices = indexedDetections.map { it.originalIndex },
        )
        return result.takeIf { it.hasOnlyFiniteValues() }
    }

    private fun hasInsufficientTimeSpread(
        indexedDetections: List<IndexedDetection>,
        minTimeSpreadSecondsSquared: Double,
    ): Boolean {
        val meanT = indexedDetections.sumOf { it.detection.timestampSeconds } / indexedDetections.size
        val denominator = indexedDetections.sumOf {
            val centered = it.detection.timestampSeconds - meanT
            centered * centered
        }
        return !denominator.isFinite() || denominator <= minTimeSpreadSecondsSquared
    }

    private fun rSquared(values: List<Double>, predictions: List<Double>): Double {
        val mean = values.sum() / values.size
        val total = values.sumOf { value -> (value - mean) * (value - mean) }
        if (total == 0.0) {
            val residual = values.zip(predictions).sumOf { (value, prediction) -> (value - prediction) * (value - prediction) }
            return if (residual == 0.0) 1.0 else 0.0
        }
        val residual = values.zip(predictions).sumOf { (value, prediction) -> (value - prediction) * (value - prediction) }
        return 1.0 - residual / total
    }

    private fun validateDetections(detections: List<Detection>): VelocityFitOutcome.Failure? {
        if (detections.size < MIN_DETECTIONS) {
            return VelocityFitOutcome.Failure(
                MeasurementFailure.INSUFFICIENT_DETECTIONS,
                "At least three detections are required.",
            )
        }
        detections.forEach { detection ->
            if (!detection.timestampSeconds.isFinite() || !detection.xPx.isFinite() || !detection.yPx.isFinite()) {
                return VelocityFitOutcome.Failure(
                    MeasurementFailure.INVALID_DETECTION,
                    "Detection timestamp and coordinates must be finite.",
                )
            }
        }
        detections.zipWithNext().forEach { (previous, current) ->
            if (current.timestampSeconds <= previous.timestampSeconds) {
                return VelocityFitOutcome.Failure(
                    MeasurementFailure.BAD_TIMESTAMP,
                    "Detection timestamps must be strictly increasing.",
                )
            }
        }
        return null
    }

    private fun validateOptions(options: VelocityFitOptions): VelocityFitOutcome.Failure? {
        if (!options.minTimeSpreadSecondsSquared.isFinite() || options.minTimeSpreadSecondsSquared <= 0.0) {
            return VelocityFitOutcome.Failure(
                MeasurementFailure.INVALID_OPTIONS,
                "Minimum time spread must be finite and positive.",
            )
        }
        if (options.maxOutlierPasses < 0) {
            return VelocityFitOutcome.Failure(
                MeasurementFailure.INVALID_OPTIONS,
                "Maximum outlier passes must be non-negative.",
            )
        }
        if (!options.minOutlierRmsImprovementPx.isFinite() || options.minOutlierRmsImprovementPx < 0.0) {
            return VelocityFitOutcome.Failure(
                MeasurementFailure.INVALID_OPTIONS,
                "Minimum outlier improvement must be finite and non-negative.",
            )
        }
        return null
    }
}

private const val MIN_DETECTIONS = 3

private data class IndexedDetection(
    val originalIndex: Int,
    val detection: Detection,
)

private data class RemovalCandidate(
    val activeIndex: Int,
    val originalIndex: Int,
    val improvement: Double,
    val fit: VelocityFitResult,
)
