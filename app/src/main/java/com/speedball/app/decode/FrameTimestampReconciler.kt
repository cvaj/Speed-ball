package com.speedball.app.decode

import kotlin.math.abs

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val NANOS_PER_MILLI = 1_000_000.0

/**
 * Conservative provisional near-duplicate threshold pending real device
 * histogram evidence. A nonzero adjacent SENSOR_TIMESTAMP gap below 1 ms is far
 * below a legitimate 120 fps frame period and fails loud instead of guessing.
 */
const val DEFAULT_NEAR_DUPLICATE_SENSOR_THRESHOLD_NANOS: Long = 1_000_000L

fun reconcileFrameTimestamps(
    metadata: DecodedVideoMetadata,
    rawSensorTimestampsNanos: List<Long>,
    requestedFps: Int,
    nearDuplicateThresholdNanos: Long = DEFAULT_NEAR_DUPLICATE_SENSOR_THRESHOLD_NANOS,
): DecodeOutcome {
    val sensorDiagnostics = buildTimestampDiagnostics(rawSensorTimestampsNanos, requestedFps)
    val presentationDiagnostics = buildOrderedTimestampDiagnostics(metadata.presentationTimeMicros.map { it * 1_000L }, requestedFps)
    val baseDiagnostics = buildReconciliationDiagnostics(
        metadata = metadata,
        sensorDiagnostics = sensorDiagnostics,
        presentationDiagnostics = presentationDiagnostics,
        nearDuplicateGapMillis = firstNearDuplicateGapMillis(sensorDiagnostics, nearDuplicateThresholdNanos),
        sampledFrames = emptyList(),
    )

    if (sensorDiagnostics.uniqueTimestampsNanos.isEmpty()) {
        return DecodeOutcome.Failure(DecodeFailure.MISSING_SENSOR_TIMESTAMPS, "No positive SENSOR_TIMESTAMP values were available for reconciliation.")
    }
    if (baseDiagnostics.nearDuplicateGapMillis != null) {
        return DecodeOutcome.Failure(DecodeFailure.SENSOR_TIMESTAMP_NEAR_DUPLICATE, "Adjacent SENSOR_TIMESTAMP values were closer than the provisional near-duplicate threshold.")
    }
    if (metadata.presentationTimeMicros.size != metadata.frameCount || !metadata.presentationTimeMicros.isStrictlyIncreasing()) {
        return DecodeOutcome.Failure(DecodeFailure.PRESENTATION_TIMESTAMPS_NON_MONOTONIC, "Decoded frame bookkeeping or PTS ordering was invalid.")
    }
    if (metadata.frameCount < MINIMUM_RECONCILABLE_FRAME_COUNT) {
        return DecodeOutcome.Failure(DecodeFailure.FRAME_COUNT_TOO_LOW, "At least three decoded frames are required.")
    }
    if (!presentationDiagnostics.medianGapPassesRateBand) {
        return DecodeOutcome.Failure(DecodeFailure.PRESENTATION_CADENCE_MISMATCH, "Median presentation timestamp gap was outside the requested fps band.")
    }
    if (!presentationDiagnostics.maximumGapPassesDropThreshold) {
        return DecodeOutcome.Failure(DecodeFailure.PRESENTATION_DROPPED_FRAME_GAP, "Presentation timestamps contain a dropped-frame-sized gap.")
    }
    if (!sensorDiagnostics.medianGapPassesRateBand) {
        return DecodeOutcome.Failure(DecodeFailure.SENSOR_CADENCE_MISMATCH, "Median SENSOR_TIMESTAMP gap was outside the requested fps band.")
    }
    if (!sensorDiagnostics.maximumGapPassesDropThreshold) {
        return DecodeOutcome.Failure(DecodeFailure.SENSOR_DROPPED_FRAME_GAP, "SENSOR_TIMESTAMP values contain a dropped-frame-sized gap.")
    }
    if (metadata.frameCount != sensorDiagnostics.uniqueCount) {
        return DecodeOutcome.Failure(DecodeFailure.FRAME_SENSOR_COUNT_MISMATCH, "Decoded frame count did not exactly match unique SENSOR_TIMESTAMP count.")
    }

    val firstSensor = sensorDiagnostics.uniqueTimestampsNanos.first()
    val pairs = sensorDiagnostics.uniqueTimestampsNanos.mapIndexed { index, timestamp ->
        FrameTimestampPair(
            frameIndex = index,
            sensorTimestampNanos = timestamp,
            relativeTimestampSeconds = (timestamp - firstSensor) / NANOS_PER_SECOND,
        )
    }
    return DecodeOutcome.Success(
        metadata = metadata,
        pairs = pairs,
        diagnostics = baseDiagnostics,
    )
}

fun buildReconciliationDiagnostics(
    metadata: DecodedVideoMetadata,
    sensorDiagnostics: TimestampDiagnostics,
    presentationDiagnostics: TimestampDiagnostics,
    nearDuplicateGapMillis: Double?,
    sampledFrames: List<DecodedFrameSample>,
): ReconciliationDiagnostics {
    val offsets = ptsToSensorOffsetSummary(metadata, sensorDiagnostics.uniqueTimestampsNanos)
    return ReconciliationDiagnostics(
        decodedFrameCount = metadata.frameCount,
        uniqueSensorTimestampCount = sensorDiagnostics.uniqueCount,
        medianSensorGapMillis = sensorDiagnostics.medianGapMillis,
        maximumSensorGapMillis = sensorDiagnostics.maximumGapMillis,
        medianPresentationGapMillis = presentationDiagnostics.medianGapMillis,
        maximumPresentationGapMillis = presentationDiagnostics.maximumGapMillis,
        expectedGapMillis = sensorDiagnostics.expectedGapMillis,
        gapLowerBoundMillis = sensorDiagnostics.gapLowerBoundMillis,
        gapUpperBoundMillis = sensorDiagnostics.gapUpperBoundMillis,
        droppedFrameGapThresholdMillis = sensorDiagnostics.droppedFrameGapThresholdMillis,
        exactCountPasses = metadata.frameCount == sensorDiagnostics.uniqueCount,
        nearDuplicateGapMillis = nearDuplicateGapMillis,
        ptsToSensorOffsetSummary = offsets,
        presentationClockAssessment = assessPresentationClock(metadata),
        sampledFrames = sampledFrames,
    )
}

fun firstNearDuplicateGapMillis(
    diagnostics: TimestampDiagnostics,
    thresholdNanos: Long = DEFAULT_NEAR_DUPLICATE_SENSOR_THRESHOLD_NANOS,
): Double? =
    diagnostics.gapNanos.firstOrNull { it > 0L && it < thresholdNanos }?.let { it / NANOS_PER_MILLI }

private fun ptsToSensorOffsetSummary(
    metadata: DecodedVideoMetadata,
    uniqueSensorTimestampsNanos: List<Long>,
): OffsetSummary? {
    if (metadata.frameCount != uniqueSensorTimestampsNanos.size || metadata.frameCount == 0) return null
    val firstPts = metadata.presentationTimeMicros.first()
    val firstSensor = uniqueSensorTimestampsNanos.first()
    val offsets = metadata.presentationTimeMicros.zip(uniqueSensorTimestampsNanos).map { (pts, sensor) ->
        val relativePtsMicros = pts - firstPts
        val relativeSensorMicros = (sensor - firstSensor) / 1_000L
        relativeSensorMicros - relativePtsMicros
    }
    val min = offsets.min()
    val max = offsets.max()
    return OffsetSummary(
        minimumOffsetMicros = min,
        maximumOffsetMicros = max,
        spreadMicros = max - min,
    )
}

private fun assessPresentationClock(metadata: DecodedVideoMetadata): PresentationClockAssessment {
    val gaps = metadata.presentationTimeMicros.zipWithNext { a, b -> b - a }
    if (gaps.size < 2) return PresentationClockAssessment.UNKNOWN
    val spread = gaps.max() - gaps.min()
    return if (abs(spread) <= 1L) {
        PresentationClockAssessment.SYNTHETIC_UNIFORM_CANDIDATE
    } else {
        PresentationClockAssessment.CAPTURE_BASED_CANDIDATE
    }
}
