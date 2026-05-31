package com.speedball.app.decode

import kotlin.math.roundToLong

private const val NANOS_PER_MILLI = 1_000_000.0
private const val MILLIS_PER_SECOND = 1_000.0
private const val MEDIAN_GAP_TOLERANCE_RATIO = 0.15
private const val DROPPED_FRAME_GAP_RATIO = 1.5

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
    val positives = timestampsNanos.filter { it > 0L }
    val unique = positives.distinct().sorted()
    val gapNanos = unique.zipWithNext { a, b -> b - a }
    val gapMillis = gapNanos.map { it / NANOS_PER_MILLI }
    val expected = MILLIS_PER_SECOND / fps
    val lower = expected * (1.0 - MEDIAN_GAP_TOLERANCE_RATIO)
    val upper = expected * (1.0 + MEDIAN_GAP_TOLERANCE_RATIO)
    return TimestampDiagnostics(
        rawCount = timestampsNanos.size,
        positiveCount = positives.size,
        uniqueTimestampsNanos = unique,
        gapNanos = gapNanos,
        medianGapMillis = gapMillis.medianOrNull(),
        maximumGapMillis = gapMillis.maxOrNull(),
        expectedGapMillis = expected,
        gapLowerBoundMillis = lower,
        gapUpperBoundMillis = upper,
        droppedFrameGapThresholdMillis = expected * DROPPED_FRAME_GAP_RATIO,
    )
}

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
