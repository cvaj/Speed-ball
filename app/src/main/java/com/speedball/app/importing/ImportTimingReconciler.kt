package com.speedball.app.importing

import com.speedball.app.measurement.TimestampGapSummary
import com.speedball.app.measurement.VisualEstimateConfidence
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Centroid sample used by imported timing reconciliation after detection. */
data class ImportTrackSample(
    val frameIndex: Int,
    val centroidX: Double,
    val centroidY: Double,
    val apparentDiameterPx: Double,
    val presentationTimestampNanos: Long?,
)

/** Configuration for estimate-only imported timing reconciliation. */
data class ImportTimingReconcilerConfig(
    val knownFrameIntervalSeconds: Double? = null,
    val intervalIsUserDeclared: Boolean = false,
    val minMotionPx: Double = 1.0,
    val maxResidualRatio: Double = 0.35,
    val maxDiameterRatio: Double = 1.35,
)

/**
 * Estimate-only timing result for imported clips.
 *
 * This is intentionally not a data class: timing provenance must be created by
 * the reviewed reconciler and must not be weakened later through generated
 * `copy()` calls.
 */
class ImportTimingReconciliation(
    val basis: ImportTimingBasis,
    val timestampsSeconds: List<Double>,
    val timestampGapSummary: TimestampGapSummary,
    val frameStepMultipliers: List<Int>,
    val confidence: VisualEstimateConfidence,
    val assumptions: List<String>,
)

/** Pure reconciler for imported clip timing bases. */
object ImportTimingReconciler {
    const val CONTAINER_PTS_PROVENANCE_ASSUMPTION: String =
        "Imported container presentation timestamps are estimate-only; re-encode, VFR remux, editor rewrite, or transcode can alter true capture cadence."
    const val USER_DECLARED_INTERVAL_ASSUMPTION: String =
        "Imported estimate uses a user-declared frame interval; wrong interval or uniform frame loss is not internally detectable."
    const val CONSTANT_VELOCITY_WINDOW_ASSUMPTION: String =
        "Imported visual gap inference assumes roughly constant in-plane velocity within the timed window."
    const val RECORDED_CAPTURE_FRAME_DROP_ASSUMPTION: String =
        "Recorded estimate uses app-owned MediaRecorder frames; device or encoder frame drop/coalescing can under-sample fast motion and bias speed low."

    fun reconcile(
        samples: List<ImportTrackSample>,
        config: ImportTimingReconcilerConfig = ImportTimingReconcilerConfig(),
    ): ImportValidationResult<ImportTimingReconciliation> {
        if (samples.size < 2 || samples.any { !it.hasFiniteGeometry() }) {
            return noRead("Imported timing needs at least two finite detected samples.")
        }
        if (hasDirectionReversal(samples, config.minMotionPx)) {
            return noRead("Imported track direction is ambiguous.")
        }
        if (violatesDiameterGate(samples, config.maxDiameterRatio)) {
            return noRead("Imported track changes apparent ball size too much for an in-plane estimate.")
        }

        val timestamps = samples.map { it.presentationTimestampNanos }
        if (timestamps.all { it != null }) {
            return reconcileContainerTimestamps(samples.size, timestamps.filterNotNull())
        }
        val intervalSeconds = config.knownFrameIntervalSeconds ?: inferIntervalFromTimedPair(samples)
            ?: return noRead("Imported clip has no trustworthy absolute timing interval.")
        if (!intervalSeconds.isFinite() || intervalSeconds <= 0.0) {
            return noRead("Imported timing interval must be finite and positive.")
        }
        val baseDistance = inferBaseDistance(samples, intervalSeconds, config)
            ?: return noRead("Imported track has too little motion for visual timing.")
        val stepMultipliers = adjacentDistances(samples).map { distance ->
            max(1, (distance / baseDistance).roundToInt())
        }
        val maxResidual = adjacentDistances(samples).zip(stepMultipliers).maxOf { (distance, multiplier) ->
            abs(distance - multiplier * baseDistance) / baseDistance
        }
        if (!maxResidual.isFinite() || maxResidual > config.maxResidualRatio) {
            return noRead("Imported visual frame-gap inference residual is too high.")
        }
        val basis = if (timestamps.any { it != null }) {
            ImportTimingBasis.PARTIAL_TIMESTAMP_VISUAL_GAP_RECONCILIATION
        } else {
            ImportTimingBasis.VISUAL_FRAME_DELTA_INFERENCE
        }
        val assumptions = buildList {
            add(CONSTANT_VELOCITY_WINDOW_ASSUMPTION)
            if (timestamps.any { it != null }) add(CONTAINER_PTS_PROVENANCE_ASSUMPTION)
            if (config.intervalIsUserDeclared) add(USER_DECLARED_INTERVAL_ASSUMPTION)
        }
        val confidence = if (config.intervalIsUserDeclared) {
            VisualEstimateConfidence.LOW
        } else {
            VisualEstimateConfidence.MEDIUM
        }
        return ImportValidationResult.Success(
            ImportTimingReconciliation(
                basis = basis,
                timestampsSeconds = timestampsFromSteps(stepMultipliers, intervalSeconds),
                timestampGapSummary = gapSummary(stepMultipliers.map { it * intervalSeconds }),
                frameStepMultipliers = stepMultipliers,
                confidence = confidence,
                assumptions = assumptions,
            ),
        )
    }

    /**
     * Reconciles decode-order imported frames that already carry monotonic
     * container presentation timestamps.
     *
     * This is still estimate-only provenance. It deliberately returns LOW
     * confidence and the same container-PTS provenance warning used by the
     * centroid-aware reconciler path.
     */
    fun reconcileContainerPresentationTimestamps(
        frames: ImportVideoFrameSequence,
    ): ImportValidationResult<ImportTimingReconciliation> {
        val timestamps = frames.frames.map { frame ->
            frame.presentationTimestampNanos
                ?: return noRead("Imported video did not expose presentation timestamps for every decoded frame.")
        }
        if (timestamps.size < 2) {
            return noRead("Imported timing needs at least two timestamped decoded frames.")
        }
        return reconcileContainerTimestamps(timestamps.size, timestamps)
    }

    /** Reconciles ordered imported frames against a known capture frame interval. */
    fun reconcileKnownFrameInterval(
        frameCount: Int,
        frameIntervalSeconds: Double,
        intervalIsUserDeclared: Boolean = true,
    ): ImportValidationResult<ImportTimingReconciliation> {
        if (frameCount < 2) {
            return noRead("Imported timing needs at least two decoded frames.")
        }
        if (!frameIntervalSeconds.isFinite() || frameIntervalSeconds <= 0.0) {
            return noRead("Imported timing interval must be finite and positive.")
        }
        val stepMultipliers = List(frameCount - 1) { 1 }
        val assumptions = buildList {
            add(CONSTANT_VELOCITY_WINDOW_ASSUMPTION)
            if (intervalIsUserDeclared) add(USER_DECLARED_INTERVAL_ASSUMPTION)
        }
        return ImportValidationResult.Success(
            ImportTimingReconciliation(
                basis = ImportTimingBasis.VISUAL_FRAME_DELTA_INFERENCE,
                timestampsSeconds = timestampsFromSteps(stepMultipliers, frameIntervalSeconds),
                timestampGapSummary = gapSummary(stepMultipliers.map { it * frameIntervalSeconds }),
                frameStepMultipliers = stepMultipliers,
                confidence = if (intervalIsUserDeclared) VisualEstimateConfidence.LOW else VisualEstimateConfidence.MEDIUM,
                assumptions = assumptions,
            ),
        )
    }

    /** Reconciles app-owned recorded captures against the requested capture interval. */
    fun reconcileRecordedCaptureFrameInterval(
        frameCount: Int,
        frameIntervalSeconds: Double,
    ): ImportValidationResult<ImportTimingReconciliation> {
        if (frameCount < 2) {
            return noRead("Recorded timing needs at least two decoded frames.")
        }
        if (!frameIntervalSeconds.isFinite() || frameIntervalSeconds <= 0.0) {
            return noRead("Recorded timing interval must be finite and positive.")
        }
        val stepMultipliers = List(frameCount - 1) { 1 }
        return ImportValidationResult.Success(
            ImportTimingReconciliation(
                basis = ImportTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL,
                timestampsSeconds = timestampsFromSteps(stepMultipliers, frameIntervalSeconds),
                timestampGapSummary = gapSummary(stepMultipliers.map { it * frameIntervalSeconds }),
                frameStepMultipliers = stepMultipliers,
                confidence = VisualEstimateConfidence.LOW,
                assumptions = listOf(
                    CONSTANT_VELOCITY_WINDOW_ASSUMPTION,
                    RECORDED_CAPTURE_FRAME_DROP_ASSUMPTION,
                ),
            ),
        )
    }

    private fun reconcileContainerTimestamps(
        frameCount: Int,
        timestamps: List<Long>,
    ): ImportValidationResult<ImportTimingReconciliation> {
        val seconds = timestamps.map { it / 1_000_000_000.0 }
        val gaps = seconds.zipWithNext { a, b -> b - a }
        if (gaps.any { !it.isFinite() || it <= 0.0 }) {
            return noRead("Imported presentation timestamps must be strictly increasing.")
        }
        return ImportValidationResult.Success(
            ImportTimingReconciliation(
                basis = ImportTimingBasis.CONTAINER_PRESENTATION_TIMESTAMPS,
                timestampsSeconds = seconds,
                timestampGapSummary = gapSummary(gaps),
                frameStepMultipliers = List(frameCount - 1) { 1 },
                confidence = VisualEstimateConfidence.LOW,
                assumptions = listOf(CONTAINER_PTS_PROVENANCE_ASSUMPTION),
            ),
        )
    }

    private fun inferIntervalFromTimedPair(samples: List<ImportTrackSample>): Double? =
        samples.zipWithNext().mapNotNull { (a, b) ->
            val aPts = a.presentationTimestampNanos
            val bPts = b.presentationTimestampNanos
            if (aPts != null && bPts != null && bPts > aPts) (bPts - aPts) / 1_000_000_000.0 else null
        }.minOrNull()

    private fun inferBaseDistance(
        samples: List<ImportTrackSample>,
        intervalSeconds: Double,
        config: ImportTimingReconcilerConfig,
    ): Double? {
        val timedDistances = samples.zipWithNext().mapNotNull { (a, b) ->
            val aPts = a.presentationTimestampNanos
            val bPts = b.presentationTimestampNanos
            if (aPts != null && bPts != null && bPts > aPts) {
                val gap = (bPts - aPts) / 1_000_000_000.0
                adjacentDistance(a, b) / max(1, (gap / intervalSeconds).roundToInt())
            } else {
                null
            }
        }
        val fallbackDistances = adjacentDistances(samples)
        return (timedDistances.ifEmpty { fallbackDistances })
            .filter { it.isFinite() && it >= config.minMotionPx }
            .minOrNull()
    }

    private fun adjacentDistances(samples: List<ImportTrackSample>): List<Double> =
        samples.zipWithNext { a, b -> adjacentDistance(a, b) }

    private fun adjacentDistance(a: ImportTrackSample, b: ImportTrackSample): Double =
        hypot(b.centroidX - a.centroidX, b.centroidY - a.centroidY)

    private fun hasDirectionReversal(samples: List<ImportTrackSample>, minMotionPx: Double): Boolean {
        val totalDx = samples.last().centroidX - samples.first().centroidX
        if (abs(totalDx) < minMotionPx) return true
        val direction = if (totalDx > 0.0) 1 else -1
        return samples.zipWithNext().any { (a, b) ->
            val dx = b.centroidX - a.centroidX
            abs(dx) >= minMotionPx && dx.sign() != direction
        }
    }

    private fun violatesDiameterGate(samples: List<ImportTrackSample>, maxDiameterRatio: Double): Boolean {
        val diameters = samples.map { it.apparentDiameterPx }
        val minDiameter = diameters.minOrNull() ?: return true
        val maxDiameter = diameters.maxOrNull() ?: return true
        return minDiameter <= 0.0 || maxDiameter / minDiameter > maxDiameterRatio
    }

    private fun gapSummary(gaps: List<Double>): TimestampGapSummary {
        val sorted = gaps.sorted()
        val median = sorted[sorted.size / 2]
        return TimestampGapSummary(
            intervalCount = gaps.size,
            minGapSeconds = sorted.first(),
            medianGapSeconds = median,
            maxGapSeconds = sorted.last(),
        )
    }

    private fun timestampsFromSteps(stepMultipliers: List<Int>, intervalSeconds: Double): List<Double> =
        buildList {
            var current = 0.0
            add(current)
            stepMultipliers.forEach { multiplier ->
                current += multiplier * intervalSeconds
                add(current)
            }
        }

    private fun ImportTrackSample.hasFiniteGeometry(): Boolean =
        centroidX.isFinite() && centroidY.isFinite() && apparentDiameterPx.isFinite() && apparentDiameterPx > 0.0

    private fun Double.sign(): Int = if (this > 0.0) 1 else -1

    private fun noRead(message: String): ImportValidationResult.NoRead =
        ImportValidationResult.NoRead(
            reason = ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
            message = message,
        )
}
