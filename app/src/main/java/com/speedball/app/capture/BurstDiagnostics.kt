package com.speedball.app.capture

import com.speedball.app.decode.buildTimestampDiagnostics
import kotlin.math.floor
import kotlin.math.roundToInt

private const val NANOS_PER_SECOND = 1_000_000_000.0
private const val UNIQUE_COUNT_MINIMUM_RATIO = 0.80

/** Sensor-timestamp proof and file diagnostics for one bounded capture burst. */
data class BurstDiagnostics(
    val callbackCount: Int,
    val uniqueTimestampCount: Int,
    val firstTimestampNanos: Long?,
    val lastTimestampNanos: Long?,
    val medianGapMillis: Double?,
    val maximumGapMillis: Double?,
    val expectedMedianGapMillis: Double,
    val medianGapLowerBoundMillis: Double,
    val medianGapUpperBoundMillis: Double,
    val droppedFrameGapThresholdMillis: Double,
    val medianGapPassesRateBand: Boolean,
    val timestampSpanSeconds: Double?,
    val expectedUniqueTimestampCount: Int,
    val minimumUniqueTimestampCount: Int,
    val uniqueCountPassesRequestedMinimum: Boolean,
    val captureProofPasses: Boolean,
    val displayOutputName: String,
    val fileBytes: Long,
)

fun buildBurstOutcome(
    timestampsNanos: List<Long>,
    callbackCount: Int,
    requestedDurationMillis: Long,
    fps: Int,
    outputPath: String,
    fileBytes: Long,
): BurstOutcome {
    val positiveTimestamps = timestampsNanos.filter { it > 0L }
    if (positiveTimestamps.isEmpty()) {
        return BurstOutcome.Failure(BurstFailure.NO_SENSOR_TIMESTAMPS, "No SENSOR_TIMESTAMP values were collected.")
    }
    return BurstOutcome.Success(
        diagnostics = buildBurstDiagnostics(
            timestampsNanos = positiveTimestamps,
            callbackCount = callbackCount,
            requestedDurationMillis = requestedDurationMillis,
            fps = fps,
            outputPath = outputPath,
            fileBytes = fileBytes,
        ),
    )
}

fun buildBurstDiagnostics(
    timestampsNanos: List<Long>,
    callbackCount: Int,
    requestedDurationMillis: Long,
    fps: Int,
    outputPath: String,
    fileBytes: Long,
): BurstDiagnostics {
    require(timestampsNanos.isNotEmpty()) { "At least one timestamp is required." }
    val timestampDiagnostics = buildTimestampDiagnostics(timestampsNanos, fps)
    val unique = timestampDiagnostics.uniqueTimestampsNanos
    require(unique.isNotEmpty()) { "At least one positive timestamp is required." }

    val expectedUnique = ((requestedDurationMillis / 1_000.0) * fps).roundToInt()
    val minimumUnique = floor(expectedUnique * UNIQUE_COUNT_MINIMUM_RATIO).toInt()
    val uniquePasses = unique.size >= minimumUnique
    val first = unique.first()
    val last = unique.last()
    val span = if (unique.size > 1) (last - first) / NANOS_PER_SECOND else 0.0

    return BurstDiagnostics(
        callbackCount = callbackCount,
        uniqueTimestampCount = unique.size,
        firstTimestampNanos = first,
        lastTimestampNanos = last,
        medianGapMillis = timestampDiagnostics.medianGapMillis,
        maximumGapMillis = timestampDiagnostics.maximumGapMillis,
        expectedMedianGapMillis = timestampDiagnostics.expectedGapMillis,
        medianGapLowerBoundMillis = timestampDiagnostics.gapLowerBoundMillis,
        medianGapUpperBoundMillis = timestampDiagnostics.gapUpperBoundMillis,
        droppedFrameGapThresholdMillis = timestampDiagnostics.droppedFrameGapThresholdMillis,
        medianGapPassesRateBand = timestampDiagnostics.medianGapPassesRateBand,
        timestampSpanSeconds = span,
        expectedUniqueTimestampCount = expectedUnique,
        minimumUniqueTimestampCount = minimumUnique,
        uniqueCountPassesRequestedMinimum = uniquePasses,
        captureProofPasses = uniquePasses && timestampDiagnostics.medianGapPassesRateBand,
        displayOutputName = outputPath.displayFileName(),
        fileBytes = fileBytes,
    )
}

fun resolveTerminalFailure(
    primaryFailure: BurstFailure?,
    releaseFailure: BurstFailure?,
): BurstFailure? = primaryFailure ?: releaseFailure

fun shouldDeliverSynchronously(reason: BurstFailure): Boolean =
    reason == BurstFailure.CAMERA_PERMISSION_DENIED ||
        reason == BurstFailure.NO_BACK_CAMERA ||
        reason == BurstFailure.RECORDER_PREPARE_FAILED ||
        reason == BurstFailure.CAMERA_OPEN_FAILED

fun String.displayFileName(): String =
    substringAfterLast('/').ifBlank { this }
