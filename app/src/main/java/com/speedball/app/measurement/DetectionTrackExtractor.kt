package com.speedball.app.measurement

import com.speedball.core.model.Detection
import com.speedball.core.model.ImagePoint

/**
 * Gates for turning per-frame blobs into a timestamped detection track.
 *
 * Directional candidate selection is opt-in because the strict measurement
 * path must fail on ambiguous blobs. Estimate-only import/live paths may enable
 * it when the expected ball path is one-directional and disclosed as an
 * estimate assumption.
 */
data class TrackExtractionConfig(
    val detectorConfig: BlobDetectionConfig,
    val maxFrameToFrameJumpPx: Double,
    val maxInteriorMisses: Int = 0,
    val allowDirectionalCandidateSelection: Boolean = false,
    val seedPoint: ImagePoint? = null,
    val seedSearchRadiusPx: Double? = null,
    val allowStationaryPrefix: Boolean = false,
    val candidateReductionBudget: CandidateReductionBudget? = null,
)

/**
 * Optional work budget for candidate-heavy estimate reducers.
 *
 * The default `null` budget preserves legacy direct/import behavior. Recorded
 * HFR opts into this because a sound-triggered full-frame window can contain
 * many color components, and over-budget evidence must fail loudly instead of
 * creating an unbounded RANSAC/allocation workload.
 */
data class CandidateReductionBudget(
    val maxBlobsPerFrame: Int,
    val maxTotalCandidateBlobs: Int,
    val maxRansacCandidates: Int,
    val maxRansacPairHypotheses: Int,
    val ransacCancellationCheckInterval: Int,
) {
    fun validate(): MeasurementRunOutcome.NoRead? {
        if (
            maxBlobsPerFrame <= 0 ||
            maxTotalCandidateBlobs <= 0 ||
            maxRansacCandidates <= 0 ||
            maxRansacPairHypotheses <= 0 ||
            ransacCancellationCheckInterval <= 0
        ) {
            return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                "Candidate-reduction budget values must be positive.",
            )
        }
        if (maxRansacCandidates > maxTotalCandidateBlobs) {
            return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                "RANSAC candidate budget cannot exceed total candidate budget.",
            )
        }
        val fullPairCount = maxRansacCandidates.toLong() * (maxRansacCandidates - 1L) / 2L
        if (fullPairCount > maxRansacPairHypotheses.toLong()) {
            return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                "RANSAC pair budget must cover the configured candidate budget.",
            )
        }
        return null
    }
}

sealed interface TrackExtractionOutcome {
    data class Success(val detections: List<Detection>) : TrackExtractionOutcome
    data class Failure(val reason: MeasurementRunFailure, val message: String) : TrackExtractionOutcome
}

/** Extracts a safe, timestamp-preserving ball track from ordered frames. */
object DetectionTrackExtractor {
    fun extract(
        sequence: TimedFrameSequence,
        config: TrackExtractionConfig,
    ): TrackExtractionOutcome {
        validateSequence(sequence, config.detectorConfig.bounds)?.let {
            return TrackExtractionOutcome.Failure(it.reason, it.message)
        }
        if (!config.maxFrameToFrameJumpPx.isFinite() || config.maxFrameToFrameJumpPx <= 0.0 || config.maxInteriorMisses < 0) {
            return TrackExtractionOutcome.Failure(
                MeasurementRunFailure.DETECTION_FAILED,
                "Track gates must be finite and positive.",
            )
        }

        val detections = mutableListOf<Detection>()
        var previousDetection: Detection? = null
        var interiorMisses = 0

        sequence.frames.forEachIndexed { index, frame ->
            when (val outcome = BlobDetector.detect(frame, config.detectorConfig)) {
                is BlobDetectionOutcome.Failure -> {
                    if (outcome.reason == MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED) {
                        return TrackExtractionOutcome.Failure(outcome.reason, outcome.message)
                    }
                    if (outcome.kind != BlobDetectionFailureKind.NO_BLOB) {
                        return TrackExtractionOutcome.Failure(outcome.reason, outcome.message)
                    }
                    if (index == 0 || index == sequence.frames.lastIndex) {
                        return TrackExtractionOutcome.Failure(outcome.reason, outcome.message)
                    }
                    interiorMisses += 1
                    if (interiorMisses > config.maxInteriorMisses) {
                        return TrackExtractionOutcome.Failure(
                            MeasurementRunFailure.DETECTION_FAILED,
                            "Too many interior frames had no usable ball detection.",
                        )
                    }
                }
                is BlobDetectionOutcome.Success -> {
                    val centroid = outcome.blob.centroid
                    val detection = Detection(
                        timestampSeconds = frame.timestampSeconds,
                        xPx = centroid.xPx,
                        yPx = centroid.yPx,
                    )
                    previousDetection?.let { previous ->
                        if (centroid.distanceTo(com.speedball.core.model.ImagePoint(previous.xPx, previous.yPx)) > config.maxFrameToFrameJumpPx) {
                            return TrackExtractionOutcome.Failure(
                                MeasurementRunFailure.DETECTION_FAILED,
                                "Detected ball movement exceeded the frame-to-frame jump gate.",
                            )
                        }
                    }
                    detections += detection
                    previousDetection = detection
                }
            }
        }

        if (detections.size < 3) {
            return TrackExtractionOutcome.Failure(
                MeasurementRunFailure.INSUFFICIENT_DETECTIONS,
                "At least three timestamped detections are required.",
            )
        }
        return TrackExtractionOutcome.Success(detections)
    }
}

private fun validateSequence(
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
    var previousTimestamp: Double? = null
    var expectedWidth: Int? = null
    var expectedHeight: Int? = null
    sequence.frames.forEach { frame ->
        if (!frame.timestampSeconds.isFinite()) {
            return MeasurementRunOutcome.NoRead(MeasurementRunFailure.BAD_FRAME_SEQUENCE, "Frame timestamps must be finite.")
        }
        previousTimestamp?.let { previous ->
            if (frame.timestampSeconds <= previous) {
                return MeasurementRunOutcome.NoRead(MeasurementRunFailure.BAD_FRAME_SEQUENCE, "Frame timestamps must be strictly increasing.")
            }
        }
        previousTimestamp = frame.timestampSeconds

        if (frame.width <= 0 || frame.height <= 0) {
            return MeasurementRunOutcome.NoRead(MeasurementRunFailure.BAD_FRAME_SEQUENCE, "Frame dimensions must be positive.")
        }
        val expectedPixelsLong = frame.width.toLong() * frame.height.toLong()
        if (frame.width > bounds.maxWidth || frame.height > bounds.maxHeight || expectedPixelsLong > bounds.maxPixels.toLong()) {
            return MeasurementRunOutcome.NoRead(MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED, "Frame dimensions exceed processing bounds.")
        }
        if (frame.argbPixels.size.toLong() != expectedPixelsLong) {
            return MeasurementRunOutcome.NoRead(MeasurementRunFailure.BAD_FRAME_SEQUENCE, "Frame pixel count does not match dimensions.")
        }
        expectedWidth?.let {
            if (frame.width != it || frame.height != expectedHeight) {
                return MeasurementRunOutcome.NoRead(MeasurementRunFailure.BAD_FRAME_SEQUENCE, "All frames in a measurement run must have identical dimensions.")
            }
        }
        expectedWidth = frame.width
        expectedHeight = frame.height
    }
    return null
}
