package com.speedball.app.decode

import kotlin.math.roundToLong

private const val NANOS_PER_MILLI = 1_000_000.0
private const val MILLIS_PER_SECOND = 1_000.0
private const val MEDIAN_GAP_TOLERANCE_RATIO = 0.15
private const val DROPPED_FRAME_GAP_RATIO = 1.5
private const val MAX_REPRESENTATIVE_NEAR_DUPLICATE_GAPS = 16

/** Shared timestamp normalization and cadence statistics for capture and decode proof. */
data class TimestampDiagnostics(
    val rawCount: Int,
    val positiveCount: Int,
    val uniqueTimestampsNanos: List<Long>,
    val gapNanos: List<Long>,
    val medianGapMillis: Double?,
    val maximumGapMillis: Double?,
    val expectedGapMillis: Double,
    val gapLowerBoundMillis: Double,
    val gapUpperBoundMillis: Double,
    val droppedFrameGapThresholdMillis: Double,
) {
    val uniqueCount: Int = uniqueTimestampsNanos.size

    val medianGapPassesRateBand: Boolean =
        medianGapMillis != null && medianGapMillis in gapLowerBoundMillis..gapUpperBoundMillis

    val maximumGapPassesDropThreshold: Boolean =
        maximumGapMillis == null || maximumGapMillis <= droppedFrameGapThresholdMillis
}

fun buildTimestampDiagnostics(
    timestampsNanos: List<Long>,
    fps: Int,
): TimestampDiagnostics {
    require(fps > 0) { "FPS must be positive." }
    val normalized = normalizeSensorTimestamps(timestampsNanos)
    val unique = normalized.uniqueTimestampsNanos
    val gapNanos = unique.zipWithNext { a, b -> b - a }
    val gapMillis = gapNanos.map { it / NANOS_PER_MILLI }
    val expected = MILLIS_PER_SECOND / fps
    val lower = expected * (1.0 - MEDIAN_GAP_TOLERANCE_RATIO)
    val upper = expected * (1.0 + MEDIAN_GAP_TOLERANCE_RATIO)
    return TimestampDiagnostics(
        rawCount = timestampsNanos.size,
        positiveCount = normalized.positiveCount,
        uniqueTimestampsNanos = normalized.uniqueTimestampsNanos,
        gapNanos = gapNanos,
        medianGapMillis = gapMillis.medianOrNull(),
        maximumGapMillis = gapMillis.maxOrNull(),
        expectedGapMillis = expected,
        gapLowerBoundMillis = lower,
        gapUpperBoundMillis = upper,
        droppedFrameGapThresholdMillis = expected * DROPPED_FRAME_GAP_RATIO,
    )
}

private data class NormalizedSensorTimestamps(
    val positiveCount: Int,
    val uniqueTimestampsNanos: List<Long>,
)

fun buildOrderedTimestampDiagnostics(
    timestampsNanos: List<Long>,
    fps: Int,
): TimestampDiagnostics {
    require(fps > 0) { "FPS must be positive." }
    val gapNanos = timestampsNanos.zipWithNext { a, b -> b - a }
    val gapMillis = gapNanos.map { it / NANOS_PER_MILLI }
    val expected = MILLIS_PER_SECOND / fps
    val lower = expected * (1.0 - MEDIAN_GAP_TOLERANCE_RATIO)
    val upper = expected * (1.0 + MEDIAN_GAP_TOLERANCE_RATIO)
    return TimestampDiagnostics(
        rawCount = timestampsNanos.size,
        positiveCount = timestampsNanos.count { it >= 0L },
        uniqueTimestampsNanos = timestampsNanos,
        gapNanos = gapNanos,
        medianGapMillis = gapMillis.medianOrNull(),
        maximumGapMillis = gapMillis.maxOrNull(),
        expectedGapMillis = expected,
        gapLowerBoundMillis = lower,
        gapUpperBoundMillis = upper,
        droppedFrameGapThresholdMillis = expected * DROPPED_FRAME_GAP_RATIO,
    )
}

fun expectedGapNanosForFps(fps: Int): Long =
    (1_000_000_000.0 / fps).roundToLong()

enum class PostCollapseSensorCountComparison(val diagnosticName: String) {
    MATCHES_DECODED_COUNT("postCollapseMatchesDecodedCount"),
    STILL_MISMATCHED("postCollapseStillMismatched"),
    UNAVAILABLE("postCollapseUnavailable"),
}

/**
 * Evidence-only near-duplicate summary. A post-collapse count match can suggest
 * callback double-fire explains a mismatch, but it is never an acceptance rule.
 */
data class NearDuplicateEvidence(
    val rawPositiveSensorTimestampCount: Int,
    val exactDistinctSensorTimestampCount: Int,
    val nearDuplicateGroupCount: Int,
    val hypotheticalPostCollapseSensorCount: Int?,
    val representativeNearDuplicateGapsNanos: List<Long>,
    val postCollapseComparison: PostCollapseSensorCountComparison,
    val interpretation: String,
)

fun buildNearDuplicateEvidence(
    timestampsNanos: List<Long>,
    decodedFrameCount: Int?,
    nearDuplicateThresholdNanos: Long = DEFAULT_NEAR_DUPLICATE_SENSOR_THRESHOLD_NANOS,
): NearDuplicateEvidence {
    require(nearDuplicateThresholdNanos > 0L) { "Near-duplicate threshold must be positive." }
    val normalized = normalizeSensorTimestamps(timestampsNanos)
    val unique = normalized.uniqueTimestampsNanos
    val nearDuplicateGroups = unique.nearDuplicateGroups(nearDuplicateThresholdNanos)
    val postCollapseCount = if (unique.isEmpty()) {
        null
    } else {
        unique.size - nearDuplicateGroups.sumOf { it.size - 1 }
    }
    val comparison = when {
        decodedFrameCount == null || decodedFrameCount <= 0 || postCollapseCount == null ->
            PostCollapseSensorCountComparison.UNAVAILABLE
        postCollapseCount == decodedFrameCount ->
            PostCollapseSensorCountComparison.MATCHES_DECODED_COUNT
        else ->
            PostCollapseSensorCountComparison.STILL_MISMATCHED
    }
    return NearDuplicateEvidence(
        rawPositiveSensorTimestampCount = normalized.positiveCount,
        exactDistinctSensorTimestampCount = unique.size,
        nearDuplicateGroupCount = nearDuplicateGroups.size,
        hypotheticalPostCollapseSensorCount = postCollapseCount,
        representativeNearDuplicateGapsNanos = nearDuplicateGroups
            .flatMap { group -> group.zipWithNext { a, b -> b - a } }
            .take(MAX_REPRESENTATIVE_NEAR_DUPLICATE_GAPS),
        postCollapseComparison = comparison,
        interpretation = comparison.interpretation,
    )
}

private fun normalizeSensorTimestamps(timestampsNanos: List<Long>): NormalizedSensorTimestamps {
    val positives = timestampsNanos.filter { it > 0L }
    return NormalizedSensorTimestamps(
        positiveCount = positives.size,
        uniqueTimestampsNanos = positives.distinct().sorted(),
    )
}

private val PostCollapseSensorCountComparison.interpretation: String
    get() = when (this) {
        PostCollapseSensorCountComparison.MATCHES_DECODED_COUNT ->
            "Post-collapse sensor count matches decoded sample count; callback double-fire may explain the count mismatch. Evidence only."
        PostCollapseSensorCountComparison.STILL_MISMATCHED ->
            "Post-collapse sensor count still differs from decoded sample count; record-then-decode may have encoder/frame-loss issues beyond near-duplicate callbacks. Evidence only."
        PostCollapseSensorCountComparison.UNAVAILABLE ->
            "Post-collapse comparison is unavailable; evidence is incomplete."
    }

private fun List<Long>.nearDuplicateGroups(thresholdNanos: Long): List<List<Long>> {
    if (size < 2) return emptyList()
    val groups = mutableListOf<List<Long>>()
    var current = mutableListOf(first())
    for (timestamp in drop(1)) {
        val previous = current.last()
        if (timestamp - previous in 1 until thresholdNanos) {
            current += timestamp
        } else {
            if (current.size > 1) groups += current.toList()
            current = mutableListOf(timestamp)
        }
    }
    if (current.size > 1) groups += current.toList()
    return groups
}

internal fun List<Double>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}
