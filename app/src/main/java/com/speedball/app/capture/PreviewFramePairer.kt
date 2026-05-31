package com.speedball.app.capture

import com.speedball.app.decode.DEFAULT_NEAR_DUPLICATE_SENSOR_THRESHOLD_NANOS
import com.speedball.app.decode.buildOrderedTimestampDiagnostics
import com.speedball.app.decode.normalizeSensorTimestamps
import kotlin.math.abs
import kotlin.math.roundToLong

private const val NANOS_PER_SECOND = 1_000_000_000.0

/**
 * Pairs consumed preview frames to `SENSOR_TIMESTAMP` values by exact timestamp
 * identity. This produces timestamp pairs only; it never produces a measurement.
 */
fun pairPreviewFrameTimestamps(
    rawPreviewTimestampsNanos: List<Long>,
    rawSensorTimestampsNanos: List<Long>,
    fps: Int,
    nearDuplicateThresholdNanos: Long = DEFAULT_NEAR_DUPLICATE_SENSOR_THRESHOLD_NANOS,
): PreviewFrameOutcome {
    require(fps > 0) { "FPS must be positive." }
    require(nearDuplicateThresholdNanos > 0L) { "Near-duplicate threshold must be positive." }

    val positivePreview = rawPreviewTimestampsNanos.filter { it > 0L }
    val normalizedSensor = normalizeSensorTimestamps(rawSensorTimestampsNanos)
    val sensorSet = normalizedSensor.uniqueTimestampsNanos.toSet()
    val exactMatches = positivePreview.count { it in sensorSet }
    val unmatchedEdges = unmatchedPreviewEdges(positivePreview, sensorSet)
    val offsets = offsetSamples(positivePreview, normalizedSensor.uniqueTimestampsNanos, fps)
    val coalescingEvidence = positivePreview.size < normalizedSensor.uniqueTimestampsNanos.size
    val diagnostics = diagnostics(
        rawPreviewTimestampsNanos = rawPreviewTimestampsNanos,
        rawSensorTimestampsNanos = rawSensorTimestampsNanos,
        fps = fps,
        exactMatches = exactMatches,
        unmatchedEdges = unmatchedEdges,
        coalescingEvidence = coalescingEvidence,
        offsetNanos = offsets,
        verdict = PreviewFramePairingVerdict.REJECTED,
    )

    if (positivePreview.isEmpty()) {
        return failure(PreviewFrameFailure.MISSING_PREVIEW_TIMESTAMPS, "No positive SurfaceTexture timestamps were consumed.", diagnostics)
    }
    if (normalizedSensor.uniqueTimestampsNanos.isEmpty()) {
        return failure(PreviewFrameFailure.MISSING_SENSOR_TIMESTAMPS, "No positive SENSOR_TIMESTAMP callbacks were collected.", diagnostics)
    }
    if (positivePreview.distinct().size != positivePreview.size) {
        return failure(PreviewFrameFailure.DUPLICATE_PREVIEW_TIMESTAMPS, "SurfaceTexture timestamps contained duplicate values.", diagnostics)
    }
    if (!positivePreview.isStrictlyIncreasing()) {
        return failure(PreviewFrameFailure.PREVIEW_TIMESTAMPS_NON_MONOTONIC, "SurfaceTexture timestamps were not strictly increasing.", diagnostics)
    }
    if (positivePreview.hasNearDuplicateGap(nearDuplicateThresholdNanos)) {
        return failure(PreviewFrameFailure.PREVIEW_TIMESTAMP_NEAR_DUPLICATE, "SurfaceTexture timestamps contained an impossible near-duplicate gap.", diagnostics)
    }

    val previewDiagnostics = buildOrderedTimestampDiagnostics(positivePreview, fps)
    if (coalescingEvidence && (!previewDiagnostics.medianGapPassesRateBand || !previewDiagnostics.maximumGapPassesDropThreshold)) {
        return failure(PreviewFrameFailure.PREVIEW_UNDERCOUNT_COALESCING, "Consumed preview timestamps undercount sensor callbacks and show skipped preview-frame evidence.", diagnostics)
    }
    if (!previewDiagnostics.medianGapPassesRateBand) {
        return failure(PreviewFrameFailure.PREVIEW_CADENCE_MISMATCH, "Median SurfaceTexture timestamp gap was outside the requested fps band.", diagnostics)
    }
    if (!previewDiagnostics.maximumGapPassesDropThreshold) {
        return failure(PreviewFrameFailure.PREVIEW_DROPPED_FRAME_GAP, "SurfaceTexture timestamps contain a dropped-frame-sized gap.", diagnostics)
    }

    val uniqueOffsets = offsets.distinct()
    if (exactMatches == 0 && uniqueOffsets.size == 1 && uniqueOffsets.single() != 0L) {
        val expectedGap = expectedGapNanos(fps)
        val offset = abs(uniqueOffsets.single())
        val reason = if (offset % expectedGap <= 1L || expectedGap - (offset % expectedGap) <= 1L) {
            PreviewFrameFailure.AMBIGUOUS_OFFSET
        } else {
            PreviewFrameFailure.NONZERO_OFFSET_REQUIRES_REVIEW
        }
        return failure(reason, "Preview timestamps only matched SENSOR_TIMESTAMP values through a nonzero offset hypothesis.", diagnostics)
    }

    if (unmatchedEdges.interiorUnmatchedCount > 0) {
        return failure(PreviewFrameFailure.SENSOR_MEMBERSHIP_UNAVAILABLE, "An interior SurfaceTexture timestamp had no SENSOR_TIMESTAMP counterpart.", diagnostics)
    }
    if (unmatchedEdges.leadingCount > 1 || unmatchedEdges.trailingCount > 1) {
        return failure(PreviewFrameFailure.FRAME_SENSOR_COUNT_MISMATCH, "More than one leading or trailing preview timestamp lacked sensor membership.", diagnostics)
    }

    val pairedPreview = positivePreview.drop(unmatchedEdges.leadingCount).dropLast(unmatchedEdges.trailingCount)
    if (pairedPreview.isEmpty()) {
        return failure(PreviewFrameFailure.SENSOR_MEMBERSHIP_UNAVAILABLE, "No SurfaceTexture timestamps remained after warm-up trim.", diagnostics)
    }
    if (
        pairedPreview.all { it in sensorSet } &&
        positivePreview.size == normalizedSensor.uniqueTimestampsNanos.size &&
        (unmatchedEdges.leadingCount + unmatchedEdges.trailingCount) > 0
    ) {
        return failure(PreviewFrameFailure.AMBIGUOUS_OFFSET, "Exact membership could also be explained by a whole-frame shifted mapping.", diagnostics)
    }
    if (pairedPreview.all { it in sensorSet }) {
        val firstTimestamp = pairedPreview.first()
        val pairs = pairedPreview.mapIndexed { index, timestamp ->
            PreviewFrameTimestampPair(
                frameIndex = index,
                previewTimestampNanos = timestamp,
                sensorTimestampNanos = timestamp,
                relativeTimestampSeconds = (timestamp - firstTimestamp) / NANOS_PER_SECOND,
            )
        }
        return PreviewFrameOutcome.Success(
            pairs = pairs,
            diagnostics = diagnostics(
                rawPreviewTimestampsNanos = rawPreviewTimestampsNanos,
                rawSensorTimestampsNanos = rawSensorTimestampsNanos,
                fps = fps,
                exactMatches = exactMatches,
                unmatchedEdges = unmatchedEdges,
                coalescingEvidence = coalescingEvidence,
                offsetNanos = offsets,
                verdict = PreviewFramePairingVerdict.EXACT_VALUE_MEMBERSHIP,
            ),
        )
    }

    return failure(PreviewFrameFailure.SENSOR_MEMBERSHIP_UNAVAILABLE, "SurfaceTexture timestamps could not be mapped to SENSOR_TIMESTAMP values by exact membership.", diagnostics)
}

private data class UnmatchedPreviewEdges(
    val leadingCount: Int,
    val trailingCount: Int,
    val interiorUnmatchedCount: Int,
)

private fun diagnostics(
    rawPreviewTimestampsNanos: List<Long>,
    rawSensorTimestampsNanos: List<Long>,
    fps: Int,
    exactMatches: Int,
    unmatchedEdges: UnmatchedPreviewEdges,
    coalescingEvidence: Boolean,
    offsetNanos: List<Long>,
    verdict: PreviewFramePairingVerdict,
): PreviewFrameDiagnostics =
    buildPreviewFrameDiagnostics(
        rawPreviewTimestampsNanos = rawPreviewTimestampsNanos,
        rawSensorTimestampsNanos = rawSensorTimestampsNanos,
        fps = fps,
        exactMatchCount = exactMatches,
        sensorMembershipCount = exactMatches,
        unmatchedLeadingPreviewCount = unmatchedEdges.leadingCount,
        unmatchedTrailingPreviewCount = unmatchedEdges.trailingCount,
        coalescingEvidence = coalescingEvidence,
        offsetNanos = offsetNanos,
        verdict = verdict,
    )

private fun failure(
    reason: PreviewFrameFailure,
    message: String,
    diagnostics: PreviewFrameDiagnostics,
): PreviewFrameOutcome.Failure =
    PreviewFrameOutcome.Failure(reason, message, diagnostics)

private fun unmatchedPreviewEdges(preview: List<Long>, sensorSet: Set<Long>): UnmatchedPreviewEdges {
    val unmatched = preview.mapIndexedNotNull { index, timestamp -> index.takeIf { timestamp !in sensorSet } }
    if (unmatched.isEmpty()) return UnmatchedPreviewEdges(0, 0, 0)
    var leading = 0
    while (leading < preview.size && preview[leading] !in sensorSet) {
        leading += 1
    }
    var trailing = 0
    while (trailing < preview.size - leading && preview[preview.lastIndex - trailing] !in sensorSet) {
        trailing += 1
    }
    val edgeIndexes = buildSet {
        addAll(0 until leading)
        addAll((preview.size - trailing) until preview.size)
    }
    return UnmatchedPreviewEdges(
        leadingCount = leading,
        trailingCount = trailing,
        interiorUnmatchedCount = unmatched.count { it !in edgeIndexes },
    )
}

private fun offsetSamples(preview: List<Long>, sensor: List<Long>, fps: Int): List<Long> {
    if (preview.isEmpty() || sensor.isEmpty()) return emptyList()
    val sensorSet = sensor.toSet()
    if (preview.any { it in sensorSet }) return preview.map { timestamp -> if (timestamp in sensorSet) 0L else timestamp - nearestSensorTimestamp(timestamp, sensor) }
    return preview.zip(sensor).map { (previewTimestamp, sensorTimestamp) -> previewTimestamp - sensorTimestamp }
        .take(MAX_OFFSET_SAMPLES)
}

private fun nearestSensorTimestamp(timestamp: Long, sensor: List<Long>): Long =
    sensor.minBy { abs(it - timestamp) }

private fun expectedGapNanos(fps: Int): Long =
    (NANOS_PER_SECOND / fps).roundToLong()

private fun List<Long>.isStrictlyIncreasing(): Boolean =
    zipWithNext().all { (a, b) -> b > a }

private fun List<Long>.hasNearDuplicateGap(thresholdNanos: Long): Boolean =
    zipWithNext().any { (a, b) -> b - a in 1 until thresholdNanos }

private const val MAX_OFFSET_SAMPLES = 80
