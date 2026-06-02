package com.speedball.app.measurement

import com.speedball.core.model.ImagePoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** Detector configuration plus estimate gates for full-frame/ROI visual input. */
data class VisualEstimateFramePipelineConfig(
    val trackConfig: TrackExtractionConfig,
    val estimateConfig: VisualEstimatePipelineConfig = VisualEstimatePipelineConfig(),
    val timing: VisualEstimateFrameTiming = VisualEstimateFrameTiming.PreferRealTimestamps(),
)

/** Timing policy for visual estimates reduced from app-owned frames. */
sealed interface VisualEstimateFrameTiming {
    data object RequireRealTimestamps : VisualEstimateFrameTiming

    data class PreferRealTimestamps(
        val visualFrameIntervalSeconds: Double? = null,
    ) : VisualEstimateFrameTiming

    data class RequireVisualFrameDeltas(
        val frameIntervalSeconds: Double,
    ) : VisualEstimateFrameTiming
}

/**
 * Estimate pipeline entry point for bounded full-frame/ROI RGB frames.
 *
 * Frames are consumed in memory, converted to one blob per usable frame, and
 * reduced to samples before speed estimation. Real timestamps are used when
 * valid; configured visual frame-delta inference is the fallback for sources
 * without valid timestamps. Raw pixels are not stored in the result payload.
 */
object VisualEstimateFramePipeline {
    fun estimateFromFrames(
        sequence: TimedFrameSequence,
        calibration: MeasurementCalibrationState,
        config: VisualEstimateFramePipelineConfig,
    ): VisualEstimateOutcome {
        val samples = when (val outcome = extractSamples(sequence, config.trackConfig)) {
            is VisualEstimateSampleExtractionOutcome.Success -> outcome.samples
            is VisualEstimateSampleExtractionOutcome.Failure -> return outcome.toNoRead(
                frameCount = sequence.frames.size,
                timingBasis = config.timing.noReadTimingBasis(sequence.frames),
            )
        }
        return estimateSamplesWithPolicy(samples, calibration, config)
    }

    private fun extractSamples(
        sequence: TimedFrameSequence,
        config: TrackExtractionConfig,
    ): VisualEstimateSampleExtractionOutcome {
        validateSequenceForEstimate(sequence, config.detectorConfig.bounds)?.let {
            return VisualEstimateSampleExtractionOutcome.Failure(it.reason.toVisualReason(), it.message)
        }
        if (!config.maxFrameToFrameJumpPx.isFinite() || config.maxFrameToFrameJumpPx <= 0.0 || config.maxInteriorMisses < 0) {
            return VisualEstimateSampleExtractionOutcome.Failure(
                VisualEstimateNoReadReason.DETECTION_FAILED,
                "Track gates must be finite and positive.",
            )
        }
        if (config.allowDirectionalCandidateSelection) {
            return extractDirectionalSamples(sequence, config)
        }

        val samples = mutableListOf<VisualEstimateTrackSample>()
        var previousCentroid: ImagePoint? = null
        var interiorMisses = 0
        sequence.frames.forEachIndexed { index, frame ->
            when (val detection = BlobDetector.detect(frame, config.detectorConfig)) {
                is BlobDetectionOutcome.Failure -> {
                    if (detection.reason == MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED) {
                        return VisualEstimateSampleExtractionOutcome.Failure(VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED, detection.message)
                    }
                    if (detection.kind != BlobDetectionFailureKind.NO_BLOB || index == 0 || index == sequence.frames.lastIndex) {
                        return VisualEstimateSampleExtractionOutcome.Failure(detection.reason.toVisualReason(), detection.message)
                    }
                    interiorMisses += 1
                    if (interiorMisses > config.maxInteriorMisses) {
                        return VisualEstimateSampleExtractionOutcome.Failure(
                            VisualEstimateNoReadReason.DETECTION_FAILED,
                            "Too many interior frames had no usable ball detection.",
                        )
                    }
                }
                is BlobDetectionOutcome.Success -> {
                    val centroid = detection.blob.centroid
                    previousCentroid?.let { previous ->
                        if (centroid.distanceTo(previous) > config.maxFrameToFrameJumpPx) {
                            return VisualEstimateSampleExtractionOutcome.Failure(
                                VisualEstimateNoReadReason.DETECTION_FAILED,
                                "Detected ball movement exceeded the frame-to-frame jump gate.",
                            )
                        }
                    }
                    samples += VisualEstimateTrackSample(
                        timestampSeconds = frame.timestampSeconds,
                        xPx = centroid.xPx,
                        yPx = centroid.yPx,
                        apparentDiameterPx = min(detection.blob.bounds.width, detection.blob.bounds.height).toDouble(),
                    )
                    previousCentroid = centroid
                }
            }
        }
        return VisualEstimateSampleExtractionOutcome.Success(samples)
    }
}

private fun extractDirectionalSamples(
    sequence: TimedFrameSequence,
    config: TrackExtractionConfig,
): VisualEstimateSampleExtractionOutcome {
    val frameCandidates = mutableListOf<Pair<RgbFrame, List<Blob>>>()
    sequence.frames.forEach { frame ->
        when (val detection = BlobDetector.detectCandidates(frame, config.detectorConfig)) {
            is BlobCandidateDetectionOutcome.Failure -> {
                if (detection.reason == MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED) {
                    return VisualEstimateSampleExtractionOutcome.Failure(VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED, detection.message)
                }
                return VisualEstimateSampleExtractionOutcome.Failure(detection.reason.toVisualReason(), detection.message)
            }
            is BlobCandidateDetectionOutcome.Success -> {
                if (detection.blobs.isNotEmpty()) {
                    frameCandidates += frame to detection.blobs
                }
            }
        }
    }
    if (frameCandidates.size < 4) {
        return VisualEstimateSampleExtractionOutcome.Failure(
            VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
            "At least four usable detections are required for an estimate.",
        )
    }
    val selected = if (config.seedPoint != null) {
        selectSeededMovingTrack(frameCandidates, config)
    } else {
        selectHighVelocityDirectionalTrack(frameCandidates, config)
    }
        ?: return VisualEstimateSampleExtractionOutcome.Failure(
            VisualEstimateNoReadReason.AMBIGUOUS_TRACK,
            "Multiple blobs did not form one coherent horizontal ball track or seeded moving ball track.",
        )
    return VisualEstimateSampleExtractionOutcome.Success(
        selected.map { (frame, blob) ->
            VisualEstimateTrackSample(
                timestampSeconds = frame.timestampSeconds,
                xPx = blob.centroid.xPx,
                yPx = blob.centroid.yPx,
                apparentDiameterPx = min(blob.bounds.width, blob.bounds.height).toDouble(),
            )
        },
    )
}

private fun selectSeededMovingTrack(
    frameCandidates: List<Pair<RgbFrame, List<Blob>>>,
    config: TrackExtractionConfig,
): List<Pair<RgbFrame, Blob>>? {
    val seedPoint = config.seedPoint ?: return selectHighVelocityDirectionalTrack(frameCandidates, config)
    val seedSearchRadius = config.seedSearchRadiusPx ?: DEFAULT_SEED_SEARCH_RADIUS_PX
    if (!seedSearchRadius.isFinite() || seedSearchRadius <= 0.0) return selectHighVelocityDirectionalTrack(frameCandidates, config)
    val first = frameCandidates.firstOrNull() ?: return null
    val seedBlob = first.second
        .filter { it.centroid.distanceTo(seedPoint) <= seedSearchRadius }
        .minByOrNull { it.centroid.distanceTo(seedPoint) }
        ?: return selectHighVelocityDirectionalTrack(frameCandidates, config)

    val tracked = mutableListOf(first.first to seedBlob)
    var previous = seedBlob
    frameCandidates.drop(1).forEach { (frame, candidates) ->
        val next = candidates
            .filter { it.centroid.distanceTo(previous.centroid) <= config.maxFrameToFrameJumpPx }
            .minByOrNull { it.centroid.distanceTo(previous.centroid) }
            ?: return@forEach
        tracked += frame to next
        previous = next
    }
    if (tracked.size < SEEDED_MOTION_MIN_USABLE_DETECTIONS) return selectHighVelocityDirectionalTrack(frameCandidates, config)
    return movingWindowFromSeededTrack(tracked) ?: selectHighVelocityDirectionalTrack(frameCandidates, config)
}

private fun selectHighVelocityDirectionalTrack(
    frameCandidates: List<Pair<RgbFrame, List<Blob>>>,
    config: TrackExtractionConfig,
): List<Pair<RgbFrame, Blob>>? {
    selectRansacStraightFlightTrack(frameCandidates, config)?.let { return it }

    var bestPath: StraightFlightPath? = null
    for (startIndex in 1 until frameCandidates.size) {
        val previousFrame = frameCandidates[startIndex - 1].first
        val currentFrame = frameCandidates[startIndex].first
        for (previousBlob in frameCandidates[startIndex - 1].second) {
            for (currentBlob in frameCandidates[startIndex].second) {
                val dx = currentBlob.centroid.xPx - previousBlob.centroid.xPx
                val stepDistance = currentBlob.centroid.distanceTo(previousBlob.centroid)
                if (stepDistance < HIGH_VELOCITY_STEP_DISTANCE_PX || abs(dx) < HIGH_VELOCITY_HORIZONTAL_STEP_PX) {
                    continue
                }
                val direction = if (dx > 0.0) 1.0 else -1.0
                val path = mutableListOf(previousFrame to previousBlob, currentFrame to currentBlob)
                var lastBlob = currentBlob
                for (next in frameCandidates.drop(startIndex + 1)) {
                    val nextBlob = next.second
                        .filter { candidate ->
                            val candidateDx = (candidate.centroid.xPx - lastBlob.centroid.xPx) * direction
                            candidate.centroid.distanceTo(lastBlob.centroid) <= config.maxFrameToFrameJumpPx &&
                                candidateDx >= -DIRECTION_REVERSAL_TOLERANCE_PX
                        }
                        .minByOrNull { candidate ->
                            val candidateDx = (candidate.centroid.xPx - lastBlob.centroid.xPx) * direction
                            val candidateDy = abs(candidate.centroid.yPx - lastBlob.centroid.yPx)
                            abs(candidateDx - stepDistance) + candidateDy * 0.5 + blobShapePenalty(candidate)
                        }
                        ?: break
                    path += next.first to nextBlob
                    lastBlob = nextBlob
                    if (path.size >= MAX_MOTION_WINDOW_SAMPLES) break
                }
                val bestCandidate = listStraightFlightWindows(path, direction)
                    .maxWithOrNull(compareBy<StraightFlightPath> { it.score }.thenBy { it.path.size })
                if (bestCandidate != null && (bestPath == null || bestCandidate.score > bestPath.score)) {
                    bestPath = bestCandidate
                }
            }
        }
    }
    return bestPath?.path
}

private fun selectRansacStraightFlightTrack(
    frameCandidates: List<Pair<RgbFrame, List<Blob>>>,
    config: TrackExtractionConfig,
): List<Pair<RgbFrame, Blob>>? {
    val candidates = frameCandidates.flatMapIndexed { frameOrder, (frame, blobs) ->
        blobs.map { blob -> RansacBlobCandidate(frameOrder, frame, blob) }
    }
    var bestConsensus: RansacStraightFlightConsensus? = null
    for (firstIndex in candidates.indices) {
        for (lastIndex in firstIndex + 1 until candidates.size) {
            val hypothesis = RansacLineHypothesis.from(candidates[firstIndex], candidates[lastIndex]) ?: continue
            val consensusPath = ransacConsensusPath(frameCandidates, hypothesis, config)
            if (consensusPath.size < SEEDED_MOTION_MIN_USABLE_DETECTIONS) continue

            val bestWindow = listStraightFlightWindows(consensusPath, hypothesis.direction)
                .maxWithOrNull(compareBy<StraightFlightPath> { it.score }.thenBy { it.path.size })
                ?: continue
            val averageInlierDistance = bestWindow.path
                .map { (_, blob) -> hypothesis.distanceTo(blob.centroid) }
                .average()
            val consensus = RansacStraightFlightConsensus(
                path = bestWindow,
                inlierCount = consensusPath.size,
                averageInlierDistancePx = averageInlierDistance,
            )
            if (bestConsensus == null || consensus.isBetterThan(bestConsensus)) {
                bestConsensus = consensus
            }
        }
    }
    return bestConsensus?.path?.path
}

private fun ransacConsensusPath(
    frameCandidates: List<Pair<RgbFrame, List<Blob>>>,
    hypothesis: RansacLineHypothesis,
    config: TrackExtractionConfig,
): List<Pair<RgbFrame, Blob>> {
    val selected = frameCandidates.mapIndexedNotNull { frameOrder, (frame, blobs) ->
        if (frameOrder < hypothesis.first.frameOrder || frameOrder > hypothesis.last.frameOrder) {
            return@mapIndexedNotNull null
        }
        val bestInlier = blobs
            .filter { blob ->
                hypothesis.distanceTo(blob.centroid) <= RANSAC_STRAIGHT_FLIGHT_INLIER_DISTANCE_PX &&
                    hypothesis.signedTravelFromStart(blob.centroid) >= -DIRECTION_REVERSAL_TOLERANCE_PX &&
                    hypothesis.signedTravelFromStart(blob.centroid) <= hypothesis.horizontalTravelPx + DIRECTION_REVERSAL_TOLERANCE_PX
            }
            .minByOrNull { blob -> hypothesis.distanceTo(blob.centroid) + blobShapePenalty(blob) * 0.25 }
        bestInlier?.let { frame to it }
    }
    if (selected.zipWithNext().any { (previous, current) ->
            current.second.centroid.distanceTo(previous.second.centroid) > config.maxFrameToFrameJumpPx
        }
    ) {
        return emptyList()
    }
    return selected
}

private fun movingWindowFromSeededTrack(
    tracked: List<Pair<RgbFrame, Blob>>,
): List<Pair<RgbFrame, Blob>>? {
    val motionIndex = firstHighVelocityMotionIndex(tracked) ?: return null
    val windowStart = max(0, motionIndex - 1)
    val direction = horizontalDirection(tracked.drop(windowStart)) ?: return null
    val candidate = mutableListOf<Pair<RgbFrame, Blob>>()
    var stationaryAfterMotion = 0
    var previous = tracked[windowStart].second
    for ((index, current) in tracked.drop(windowStart).withIndex()) {
        val currentBlob = current.second
        if (index > 0) {
            val dx = (currentBlob.centroid.xPx - previous.centroid.xPx) * direction
            val stepDistance = currentBlob.centroid.distanceTo(previous.centroid)
            if (dx < -DIRECTION_REVERSAL_TOLERANCE_PX) break
            if (candidate.size >= SEEDED_MOTION_MIN_USABLE_DETECTIONS && stepDistance < STATIONARY_AFTER_MOTION_DISTANCE_PX) {
                stationaryAfterMotion += 1
                if (stationaryAfterMotion > MAX_STATIONARY_SAMPLES_AFTER_MOTION) break
            } else if (stepDistance >= STATIONARY_AFTER_MOTION_DISTANCE_PX) {
                stationaryAfterMotion = 0
            }
        }
        candidate += current
        previous = currentBlob
        if (candidate.size >= MAX_MOTION_WINDOW_SAMPLES) break
    }
    return listStraightFlightWindows(candidate, direction)
        .maxWithOrNull(compareBy<StraightFlightPath> { it.score }.thenBy { it.path.size })
        ?.path
}

private data class StraightFlightPath(
    val path: List<Pair<RgbFrame, Blob>>,
    val score: Double,
)

private data class RansacBlobCandidate(
    val frameOrder: Int,
    val frame: RgbFrame,
    val blob: Blob,
)

private data class RansacLineHypothesis(
    val first: RansacBlobCandidate,
    val last: RansacBlobCandidate,
    val direction: Double,
    val horizontalTravelPx: Double,
    val spanPx: Double,
) {
    fun distanceTo(point: ImagePoint): Double {
        val firstPoint = first.blob.centroid
        val lastPoint = last.blob.centroid
        val dx = lastPoint.xPx - firstPoint.xPx
        val dy = lastPoint.yPx - firstPoint.yPx
        return abs(
            dy * point.xPx -
                dx * point.yPx +
                lastPoint.xPx * firstPoint.yPx -
                lastPoint.yPx * firstPoint.xPx,
        ) / spanPx
    }

    fun signedTravelFromStart(point: ImagePoint): Double =
        (point.xPx - first.blob.centroid.xPx) * direction

    companion object {
        fun from(first: RansacBlobCandidate, last: RansacBlobCandidate): RansacLineHypothesis? {
            if (first.frameOrder >= last.frameOrder) return null
            val firstPoint = first.blob.centroid
            val lastPoint = last.blob.centroid
            val dx = lastPoint.xPx - firstPoint.xPx
            val dy = lastPoint.yPx - firstPoint.yPx
            val horizontalTravel = abs(dx)
            val durationSeconds = last.frame.timestampSeconds - first.frame.timestampSeconds
            if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return null
            if (horizontalTravel < MIN_HIT_HORIZONTAL_TRAVEL_PX) return null
            val horizontalSpeed = horizontalTravel / durationSeconds
            if (horizontalSpeed < MIN_HIT_HORIZONTAL_SPEED_PX_PER_SECOND) return null
            val span = hypotLike(dx, dy)
            if (!span.isFinite() || span <= 0.0) return null
            return RansacLineHypothesis(
                first = first,
                last = last,
                direction = if (dx > 0.0) 1.0 else -1.0,
                horizontalTravelPx = horizontalTravel,
                spanPx = span,
            )
        }
    }
}

private data class RansacStraightFlightConsensus(
    val path: StraightFlightPath,
    val inlierCount: Int,
    val averageInlierDistancePx: Double,
) {
    fun isBetterThan(other: RansacStraightFlightConsensus?): Boolean {
        if (other == null) return true
        val thisScore = consensusScore()
        val otherScore = other.consensusScore()
        return thisScore > otherScore
    }

    private fun consensusScore(): Double =
        path.score +
            inlierCount * RANSAC_INLIER_COUNT_SCORE_WEIGHT -
            averageInlierDistancePx * RANSAC_INLIER_DISTANCE_SCORE_WEIGHT
}

private fun listStraightFlightWindows(
    path: List<Pair<RgbFrame, Blob>>,
    direction: Double,
): List<StraightFlightPath> {
    if (path.size < SEEDED_MOTION_MIN_USABLE_DETECTIONS) return emptyList()
    val candidates = mutableListOf<StraightFlightPath>()
    for (start in 0..path.size - SEEDED_MOTION_MIN_USABLE_DETECTIONS) {
        for (endExclusive in (start + SEEDED_MOTION_MIN_USABLE_DETECTIONS)..min(path.size, start + MAX_HIT_FLIGHT_WINDOW_SAMPLES)) {
            scoreStraightFlightWindow(path.subList(start, endExclusive), direction)?.let(candidates::add)
        }
    }
    return candidates
}

private fun scoreStraightFlightWindow(
    path: List<Pair<RgbFrame, Blob>>,
    direction: Double,
): StraightFlightPath? {
    if (path.size < SEEDED_MOTION_MIN_USABLE_DETECTIONS) return null
    val deltas = path.zipWithNext().map { (previous, current) ->
        val dx = (current.second.centroid.xPx - previous.second.centroid.xPx) * direction
        val dy = current.second.centroid.yPx - previous.second.centroid.yPx
        val dt = current.first.timestampSeconds - previous.first.timestampSeconds
        FlightStep(dx = dx, dy = dy, dt = dt)
    }
    if (deltas.any { !it.isUsable() || it.dx < HIGH_VELOCITY_HORIZONTAL_STEP_PX }) return null
    val horizontalTravel = deltas.sumOf { it.dx }
    val totalTravel = path.last().second.centroid.distanceTo(path.first().second.centroid)
    val durationSeconds = path.last().first.timestampSeconds - path.first().first.timestampSeconds
    if (!durationSeconds.isFinite() || durationSeconds <= 0.0) return null
    val averageHorizontalSpeedPxPerSecond = horizontalTravel / durationSeconds
    val minStepSpeed = deltas.minOf { hypotLike(it.dx, it.dy) / it.dt }
    val turnCost = deltas.zipWithNext().sumOf { (previous, current) ->
        abs(current.dy / current.dx - previous.dy / previous.dx)
    }
    val lineResidual = normalizedLineResidual(path)
    val compactness = path.map { it.second.compactness }.average()
    val shapePenalty = path.sumOf { (_, blob) -> blobShapePenalty(blob) } / path.size

    if (horizontalTravel < MIN_HIT_HORIZONTAL_TRAVEL_PX) return null
    if (totalTravel < HIGH_VELOCITY_STEP_DISTANCE_PX * (SEEDED_MOTION_MIN_USABLE_DETECTIONS - 1)) return null
    if (averageHorizontalSpeedPxPerSecond < MIN_HIT_HORIZONTAL_SPEED_PX_PER_SECOND) return null
    if (minStepSpeed < MIN_HIT_STEP_SPEED_PX_PER_SECOND) return null
    if (lineResidual > MAX_HIT_NORMALIZED_LINE_RESIDUAL) return null
    if (turnCost > MAX_HIT_SLOPE_CHANGE) return null

    val score = horizontalTravel * 4.0 +
        averageHorizontalSpeedPxPerSecond * 0.015 +
        compactness * 2.0 -
        lineResidual * 80.0 -
        turnCost * 18.0 -
        shapePenalty * 2.0
    return StraightFlightPath(path, score)
}

private data class FlightStep(
    val dx: Double,
    val dy: Double,
    val dt: Double,
) {
    fun isUsable(): Boolean =
        dx.isFinite() && dy.isFinite() && dt.isFinite() && dt > 0.0
}

private fun normalizedLineResidual(path: List<Pair<RgbFrame, Blob>>): Double {
    val first = path.first().second.centroid
    val last = path.last().second.centroid
    val span = first.distanceTo(last)
    if (!span.isFinite() || span <= 0.0) return Double.POSITIVE_INFINITY
    val dx = last.xPx - first.xPx
    val dy = last.yPx - first.yPx
    return path.drop(1).dropLast(1).maxOfOrNull { (_, blob) ->
        abs(dy * blob.centroid.xPx - dx * blob.centroid.yPx + last.xPx * first.yPx - last.yPx * first.xPx) / span
    }?.div(span) ?: 0.0
}

private fun blobShapePenalty(blob: Blob): Double {
    val longSide = max(blob.bounds.width, blob.bounds.height).toDouble()
    val shortSide = min(blob.bounds.width, blob.bounds.height).toDouble().coerceAtLeast(1.0)
    val elongation = longSide / shortSide
    val compactnessPenalty = max(0.0, MIN_HIT_BLOB_COMPACTNESS - blob.compactness)
    return max(0.0, elongation - MAX_HIT_BLOB_ELONGATION) + compactnessPenalty * 2.0
}

private fun hypotLike(x: Double, y: Double): Double =
    kotlin.math.hypot(x, y)

private fun firstHighVelocityMotionIndex(
    tracked: List<Pair<RgbFrame, Blob>>,
): Int? {
    if (tracked.size < SEEDED_MOTION_MIN_USABLE_DETECTIONS) return null
    for (index in 1 until tracked.size) {
        val previous = tracked[index - 1].second.centroid
        val current = tracked[index].second.centroid
        val dx = current.xPx - previous.xPx
        val stepDistance = current.distanceTo(previous)
        if (stepDistance < HIGH_VELOCITY_STEP_DISTANCE_PX || abs(dx) < HIGH_VELOCITY_HORIZONTAL_STEP_PX) {
            continue
        }
        val direction = if (dx > 0.0) 1.0 else -1.0
        val lookahead = tracked.drop(index - 1).take(HIGH_VELOCITY_LOOKAHEAD_SAMPLES)
        val directionalSteps = lookahead.zipWithNext().count { (a, b) ->
            val stepDx = (b.second.centroid.xPx - a.second.centroid.xPx) * direction
            stepDx >= -DIRECTION_REVERSAL_TOLERANCE_PX
        }
        if (directionalSteps >= HIGH_VELOCITY_MIN_DIRECTIONAL_STEPS) {
            return index
        }
    }
    return null
}

private fun horizontalDirection(
    tracked: List<Pair<RgbFrame, Blob>>,
): Double? {
    if (tracked.size < SEEDED_MOTION_MIN_USABLE_DETECTIONS) return null
    val seedX = tracked.first().second.centroid.xPx
    val strongest = tracked.drop(1)
        .map { (_, blob) -> blob.centroid.xPx - seedX }
        .maxByOrNull { abs(it) }
        ?: return null
    if (abs(strongest) < MOTION_START_DISTANCE_PX) return null
    return if (strongest > 0.0) 1.0 else -1.0
}

private const val SEEDED_MOTION_MIN_USABLE_DETECTIONS = 4
private const val DEFAULT_SEED_SEARCH_RADIUS_PX = 18.0
private const val MOTION_START_DISTANCE_PX = 2.0
private const val HIGH_VELOCITY_STEP_DISTANCE_PX = 3.0
private const val HIGH_VELOCITY_HORIZONTAL_STEP_PX = 1.5
private const val MIN_HIT_HORIZONTAL_SPEED_PX_PER_SECOND = 240.0
private const val MIN_HIT_STEP_SPEED_PX_PER_SECOND = 240.0
private const val MIN_HIT_HORIZONTAL_TRAVEL_PX = 9.0
private const val MAX_HIT_NORMALIZED_LINE_RESIDUAL = 0.06
private const val MAX_HIT_SLOPE_CHANGE = 0.60
private const val MIN_HIT_BLOB_COMPACTNESS = 0.35
private const val MAX_HIT_BLOB_ELONGATION = 4.0
private const val HIGH_VELOCITY_LOOKAHEAD_SAMPLES = 6
private const val HIGH_VELOCITY_MIN_DIRECTIONAL_STEPS = 3
private const val DIRECTION_REVERSAL_TOLERANCE_PX = 1.0
private const val STATIONARY_AFTER_MOTION_DISTANCE_PX = 0.75
private const val MAX_STATIONARY_SAMPLES_AFTER_MOTION = 3
private const val MAX_MOTION_WINDOW_SAMPLES = 32
private const val MAX_HIT_FLIGHT_WINDOW_SAMPLES = 12
private const val RANSAC_STRAIGHT_FLIGHT_INLIER_DISTANCE_PX = 2.5
private const val RANSAC_INLIER_COUNT_SCORE_WEIGHT = 12.0
private const val RANSAC_INLIER_DISTANCE_SCORE_WEIGHT = 8.0

private fun estimateSamplesWithPolicy(
    samples: List<VisualEstimateTrackSample>,
    calibration: MeasurementCalibrationState,
    config: VisualEstimateFramePipelineConfig,
): VisualEstimateOutcome =
    when (val timing = config.timing) {
        VisualEstimateFrameTiming.RequireRealTimestamps ->
            VisualEstimatePipeline.estimate(samples, calibration, config.estimateConfig)

        is VisualEstimateFrameTiming.PreferRealTimestamps ->
            if (samples.haveStrictIncreasingFiniteTimestamps()) {
                VisualEstimatePipeline.estimate(samples, calibration, config.estimateConfig)
            } else {
                estimateWithOptionalVisualFrameInterval(
                    samples = samples,
                    calibration = calibration,
                    estimateConfig = config.estimateConfig,
                    frameIntervalSeconds = timing.visualFrameIntervalSeconds,
                )
            }

        is VisualEstimateFrameTiming.RequireVisualFrameDeltas ->
            VisualEstimatePipeline.estimateWithVisualFrameDeltas(
                samples = samples,
                calibration = calibration,
                frameIntervalSeconds = timing.frameIntervalSeconds,
                config = config.estimateConfig,
            )
    }

private fun estimateWithOptionalVisualFrameInterval(
    samples: List<VisualEstimateTrackSample>,
    calibration: MeasurementCalibrationState,
    estimateConfig: VisualEstimatePipelineConfig,
    frameIntervalSeconds: Double?,
): VisualEstimateOutcome {
    if (frameIntervalSeconds == null) {
        return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
            message = "Visual estimate needs real frame timestamps or a known per-frame interval for visual frame-delta inference.",
            diagnostics = VisualEstimateDiagnostics(
                frameCount = samples.size,
                detectionCount = samples.size,
                timingBasis = EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE,
                timestampGapSummary = null,
                fitResidualPx = null,
                confidence = null,
            ),
        )
    }
    return VisualEstimatePipeline.estimateWithVisualFrameDeltas(
        samples = samples,
        calibration = calibration,
        frameIntervalSeconds = frameIntervalSeconds,
        config = estimateConfig,
    )
}

private fun List<VisualEstimateTrackSample>.haveStrictIncreasingFiniteTimestamps(): Boolean =
    size >= 2 &&
        all { it.timestampSeconds.isFinite() } &&
        zipWithNext().all { (previous, current) -> current.timestampSeconds > previous.timestampSeconds }

private fun VisualEstimateFrameTiming.noReadTimingBasis(frames: List<RgbFrame>): EstimateTimingBasis =
    when (this) {
        VisualEstimateFrameTiming.RequireRealTimestamps -> EstimateTimingBasis.REAL_PER_FRAME_TIMESTAMPS
        is VisualEstimateFrameTiming.RequireVisualFrameDeltas -> EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE
        is VisualEstimateFrameTiming.PreferRealTimestamps ->
            if (frames.haveStrictIncreasingFiniteFrameTimestamps()) {
                EstimateTimingBasis.REAL_PER_FRAME_TIMESTAMPS
            } else {
                EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE
            }
    }

private fun List<RgbFrame>.haveStrictIncreasingFiniteFrameTimestamps(): Boolean =
    size >= 2 &&
        all { it.timestampSeconds.isFinite() } &&
        zipWithNext().all { (previous, current) -> current.timestampSeconds > previous.timestampSeconds }

private sealed interface VisualEstimateSampleExtractionOutcome {
    data class Success(val samples: List<VisualEstimateTrackSample>) : VisualEstimateSampleExtractionOutcome
    data class Failure(
        val reason: VisualEstimateNoReadReason,
        val message: String,
    ) : VisualEstimateSampleExtractionOutcome
}

private fun VisualEstimateSampleExtractionOutcome.Failure.toNoRead(
    frameCount: Int,
    timingBasis: EstimateTimingBasis,
): VisualEstimateOutcome.NoRead =
    VisualEstimateOutcome.NoRead(
        reason = reason,
        message = message,
        diagnostics = VisualEstimateDiagnostics(
            frameCount = frameCount,
            detectionCount = 0,
            timingBasis = timingBasis,
            timestampGapSummary = null,
            fitResidualPx = null,
            confidence = null,
        ),
    )

private fun validateSequenceForEstimate(
    sequence: TimedFrameSequence,
    bounds: FrameProcessingBounds,
): MeasurementRunOutcome.NoRead? {
    bounds.validate()?.let { return it }
    if (sequence.frames.isEmpty()) {
        return MeasurementRunOutcome.NoRead(MeasurementRunFailure.BAD_FRAME_SEQUENCE, "Frame sequence is empty.")
    }
    if (sequence.frames.size > bounds.maxFrameCount) {
        return MeasurementRunOutcome.NoRead(MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED, "Frame count exceeds processing bounds.")
    }
    sequence.frames.forEach { frame ->
        if (frame.width <= 0 || frame.height <= 0 || !frame.timestampSeconds.isFinite()) {
            return MeasurementRunOutcome.NoRead(MeasurementRunFailure.BAD_FRAME_SEQUENCE, "Frame dimensions and timestamp must be valid.")
        }
    }
    return null
}

private fun MeasurementRunFailure.toVisualReason(): VisualEstimateNoReadReason =
    when (this) {
        MeasurementRunFailure.UNPROVEN_TIMING,
        MeasurementRunFailure.BAD_FRAME_SEQUENCE -> VisualEstimateNoReadReason.BAD_TIMESTAMPS
        MeasurementRunFailure.DETECTION_FAILED -> VisualEstimateNoReadReason.DETECTION_FAILED
        MeasurementRunFailure.INSUFFICIENT_DETECTIONS -> VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS
        MeasurementRunFailure.BAD_CALIBRATION -> VisualEstimateNoReadReason.BAD_CALIBRATION
        MeasurementRunFailure.MEASUREMENT_REJECTED -> VisualEstimateNoReadReason.EXCESSIVE_RESIDUAL
        MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED -> VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED
    }
