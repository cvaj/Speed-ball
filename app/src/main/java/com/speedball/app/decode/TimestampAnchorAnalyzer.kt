package com.speedball.app.decode

/**
 * Evidence-only timestamp anchor investigation entrypoint.
 *
 * Task 3 intentionally implements only near-duplicate evidence capture. A
 * hypothetical post-collapse count match never proves a mapping and never
 * produces `TimestampAnchorOutcome.Proven`.
 */
fun analyzeTimestampAnchorEvidence(
    metadata: DecodedVideoMetadata,
    rawSensorTimestampsNanos: List<Long>,
    nearDuplicateThresholdNanos: Long = DEFAULT_NEAR_DUPLICATE_SENSOR_THRESHOLD_NANOS,
): TimestampAnchorOutcome {
    val evidence = buildNearDuplicateEvidence(
        timestampsNanos = rawSensorTimestampsNanos,
        decodedFrameCount = metadata.frameCount,
        nearDuplicateThresholdNanos = nearDuplicateThresholdNanos,
    )
    val diagnostics = TimestampAnchorDiagnostics(
        decodedFrameCount = metadata.frameCount,
        rawSensorTimestampCount = rawSensorTimestampsNanos.size,
        uniqueSensorTimestampCount = evidence.exactDistinctSensorTimestampCount,
        nearDuplicateEvidence = evidence,
    )
    if (evidence.nearDuplicateGroupCount > 0) {
        return TimestampAnchorOutcome.Rejected(
            reason = TimestampAnchorFailure.SENSOR_NEAR_DUPLICATE,
            message = "Near-duplicate SENSOR_TIMESTAMP values are evidence only and cannot be collapsed into a measurement mapping.",
            diagnostics = diagnostics,
        )
    }
    return TimestampAnchorOutcome.Rejected(
        reason = TimestampAnchorFailure.NO_CANDIDATE,
        message = "No timestamp anchor candidate evaluation is implemented for Task 3.",
        diagnostics = diagnostics,
    )
}
