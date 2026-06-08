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
    val requestedExposureMode: String,
    val requestedExposureTimeNanos: Long?,
    val actualExposureSampleCount: Int,
    val actualExposureMinNanos: Long?,
    val actualExposureMedianNanos: Long?,
    val actualExposureMaxNanos: Long?,
)

fun buildBurstOutcome(
    timestampsNanos: List<Long>,
    callbackCount: Int,
    requestedDurationMillis: Long,
    fps: Int,
    outputPath: String,
    fileBytes: Long,
    requestedExposureTimeNanos: Long? = null,
    actualExposureTimeNanos: List<Long> = emptyList(),
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
            requestedExposureTimeNanos = requestedExposureTimeNanos,
            actualExposureTimeNanos = actualExposureTimeNanos,
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
    requestedExposureTimeNanos: Long? = null,
    actualExposureTimeNanos: List<Long> = emptyList(),
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
    val exposureSamples = actualExposureTimeNanos.filter { it > 0L }.sorted()

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
        requestedExposureMode = if (requestedExposureTimeNanos == null) "AE" else "MANUAL",
        requestedExposureTimeNanos = requestedExposureTimeNanos,
        actualExposureSampleCount = exposureSamples.size,
        actualExposureMinNanos = exposureSamples.firstOrNull(),
        actualExposureMedianNanos = exposureSamples.medianLongOrNull(),
        actualExposureMaxNanos = exposureSamples.lastOrNull(),
    )
}

/** Compact, path-free log/report summary of requested and actual capture exposure. */
fun BurstDiagnostics.exposureSummary(): String =
    "exposureMode=$requestedExposureMode requestedExposureNs=${requestedExposureTimeNanos ?: "AE"} " +
        "actualExposureSamples=$actualExposureSampleCount " +
        "actualExposureNs=${actualExposureMinNanos ?: "n/a"}/${actualExposureMedianNanos ?: "n/a"}/${actualExposureMaxNanos ?: "n/a"}"

private fun List<Long>.medianLongOrNull(): Long? {
    if (isEmpty()) return null
    val middle = size / 2
    return if (size % 2 == 1) {
        this[middle]
    } else {
        ((this[middle - 1].toDouble() + this[middle].toDouble()) / 2.0).toLong()
    }
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
