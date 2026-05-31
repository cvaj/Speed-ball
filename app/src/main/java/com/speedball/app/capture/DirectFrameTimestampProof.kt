package com.speedball.app.capture

import com.speedball.app.decode.DEFAULT_NEAR_DUPLICATE_SENSOR_THRESHOLD_NANOS
import com.speedball.app.decode.TimestampDiagnostics
import com.speedball.app.decode.buildOrderedTimestampDiagnostics
import com.speedball.app.decode.buildTimestampDiagnostics
import com.speedball.app.decode.expectedGapNanosForFps
import com.speedball.app.decode.normalizeSensorTimestamps
import kotlin.math.abs

const val DIRECT_ZERO_OFFSET_EQUIVALENT_BOUND_NANOS: Long = 1_000L

/** Timestamp proof verdict for consumed direct frames. This is not a measurement result. */
enum class DirectFrameTimestampVerdict {
    EXACT_SENSOR_MEMBERSHIP,
    ZERO_OFFSET_EQUIVALENT,
    REJECTED,
}

/** One consumed direct frame timestamp matched to sensor-clock evidence. */
data class DirectFrameTimestampMatch(
    val frameIndex: Int,
    val directTimestampNanos: Long,
    val sensorTimestampNanos: Long,
    val offsetNanos: Long,
)

/** Bounded diagnostics for direct timestamp proof. */
data class DirectFrameTimestampDiagnostics(
    val rawDirectTimestampCount: Int,
    val positiveDirectTimestampCount: Int,
    val uniqueDirectTimestampCount: Int,
    val rawSensorTimestampCount: Int,
    val positiveSensorTimestampCount: Int,
    val uniqueSensorTimestampCount: Int,
    val directGapNanos: List<Long>,
    val sensorGapNanos: List<Long>,
    val medianDirectGapMillis: Double?,
    val maximumDirectGapMillis: Double?,
    val expectedGapMillis: Double,
    val droppedFrameGapThresholdMillis: Double,
    val exactMatchCount: Int,
    val sensorMembershipCount: Int,
    val offsetNanos: List<Long>,
    val verdict: DirectFrameTimestampVerdict,
)

/** Terminal result from direct timestamp proof. */
sealed interface DirectFrameTimestampProofOutcome {
    data class Success(
        val matches: List<DirectFrameTimestampMatch>,
        val diagnostics: DirectFrameTimestampDiagnostics,
    ) : DirectFrameTimestampProofOutcome

    data class Failure(
        val reason: DirectTimingSourceFailure,
        val message: String,
        val diagnostics: DirectFrameTimestampDiagnostics,
    ) : DirectFrameTimestampProofOutcome
}

fun proveDirectFrameTimestamps(
    rawDirectTimestampsNanos: List<Long>,
    rawSensorTimestampsNanos: List<Long>,
    fps: Int,
    nearDuplicateThresholdNanos: Long = DEFAULT_NEAR_DUPLICATE_SENSOR_THRESHOLD_NANOS,
): DirectFrameTimestampProofOutcome {
    require(fps > 0) { "FPS must be positive." }
    require(nearDuplicateThresholdNanos > 0L) { "Near-duplicate threshold must be positive." }

    val positiveDirect = rawDirectTimestampsNanos.filter { it > 0L }
    val normalizedSensor = normalizeSensorTimestamps(rawSensorTimestampsNanos)
    val sensor = normalizedSensor.uniqueTimestampsNanos
    val sensorSet = sensor.toSet()
    val exactMatches = positiveDirect.count { it in sensorSet }
    val offsets = offsetSamples(positiveDirect, sensor)
    val rejectedDiagnostics = diagnostics(
        rawDirectTimestampsNanos = rawDirectTimestampsNanos,
        rawSensorTimestampsNanos = rawSensorTimestampsNanos,
        fps = fps,
        exactMatches = exactMatches,
        offsetNanos = offsets,
        verdict = DirectFrameTimestampVerdict.REJECTED,
    )

    if (positiveDirect.isEmpty()) {
        return failure(DirectTimingSourceFailure.MISSING_DIRECT_TIMESTAMPS, "No positive direct frame timestamps were consumed.", rejectedDiagnostics)
    }
    if (sensor.isEmpty()) {
        return failure(DirectTimingSourceFailure.MISSING_SENSOR_TIMESTAMPS, "No positive SENSOR_TIMESTAMP callbacks were collected.", rejectedDiagnostics)
    }
    if (positiveDirect.distinct().size != positiveDirect.size) {
        return failure(DirectTimingSourceFailure.DUPLICATE_DIRECT_TIMESTAMPS, "Direct frame timestamps contained duplicate values.", rejectedDiagnostics)
    }
    if (!positiveDirect.isStrictlyIncreasing()) {
        return failure(DirectTimingSourceFailure.DIRECT_TIMESTAMPS_NON_MONOTONIC, "Direct frame timestamps were not strictly increasing.", rejectedDiagnostics)
    }
    if (positiveDirect.hasNearDuplicateGap(nearDuplicateThresholdNanos)) {
        return failure(DirectTimingSourceFailure.DIRECT_TIMESTAMP_NEAR_DUPLICATE, "Direct frame timestamps contained an impossible near-duplicate gap.", rejectedDiagnostics)
    }

    val directDiagnostics = buildOrderedTimestampDiagnostics(positiveDirect, fps)
    if (!directDiagnostics.medianGapPassesRateBand) {
        return failure(DirectTimingSourceFailure.DIRECT_CADENCE_MISMATCH, "Median direct timestamp gap was outside the requested fps band.", rejectedDiagnostics)
    }
    if (!directDiagnostics.maximumGapPassesDropThreshold) {
        return failure(DirectTimingSourceFailure.DIRECT_DROPPED_FRAME_GAP, "Direct timestamps contain a dropped-frame-sized gap.", rejectedDiagnostics)
    }

    if (positiveDirect.all { it in sensorSet }) {
        return DirectFrameTimestampProofOutcome.Success(
            matches = positiveDirect.mapIndexed { index, timestamp ->
                DirectFrameTimestampMatch(
                    frameIndex = index,
                    directTimestampNanos = timestamp,
                    sensorTimestampNanos = timestamp,
                    offsetNanos = 0L,
                )
            },
            diagnostics = diagnostics(
                rawDirectTimestampsNanos = rawDirectTimestampsNanos,
                rawSensorTimestampsNanos = rawSensorTimestampsNanos,
                fps = fps,
                exactMatches = exactMatches,
                offsetNanos = List(positiveDirect.size) { 0L },
                verdict = DirectFrameTimestampVerdict.EXACT_SENSOR_MEMBERSHIP,
            ),
        )
    }

    val zeroOffsetEquivalent = zeroOffsetEquivalentMatches(positiveDirect, sensor, fps)
    if (zeroOffsetEquivalent != null) {
        return zeroOffsetEquivalent
    }

    val uniqueNonzeroOffsets = offsets.filter { it != 0L }.distinct()
    if (positiveDirect.size == sensor.size && uniqueNonzeroOffsets.any { it.isWrongByKFrameAmbiguous(fps) }) {
        return failure(DirectTimingSourceFailure.AMBIGUOUS_WRONG_BY_K_OFFSET, "Direct timestamps matched only through an ambiguous whole-frame offset.", rejectedDiagnostics)
    }
    if (exactMatches > 0) {
        return failure(DirectTimingSourceFailure.SENSOR_MEMBERSHIP_UNAVAILABLE, "A direct timestamp had no SENSOR_TIMESTAMP counterpart.", rejectedDiagnostics)
    }
    if (uniqueNonzeroOffsets.isNotEmpty()) {
        return failure(DirectTimingSourceFailure.NONZERO_OFFSET_OUT_OF_BOUND, "Direct timestamps matched only through a nonzero offset outside the reviewed bound.", rejectedDiagnostics)
    }
    return failure(DirectTimingSourceFailure.SENSOR_MEMBERSHIP_UNAVAILABLE, "A direct timestamp had no SENSOR_TIMESTAMP counterpart.", rejectedDiagnostics)
}

private fun zeroOffsetEquivalentMatches(
    direct: List<Long>,
    sensor: List<Long>,
    fps: Int,
): DirectFrameTimestampProofOutcome.Success? {
    if (direct.size != sensor.size) return null
    val offsets = direct.zip(sensor).map { (directTimestamp, sensorTimestamp) -> directTimestamp - sensorTimestamp }
    val uniqueOffsets = offsets.distinct()
    if (uniqueOffsets.size != 1) return null
    val offset = uniqueOffsets.single()
    if (offset == 0L) return null
    if (abs(offset) >= DIRECT_ZERO_OFFSET_EQUIVALENT_BOUND_NANOS) return null
    if (offset.isWrongByKFrameAmbiguous(fps)) return null
    return DirectFrameTimestampProofOutcome.Success(
        matches = direct.mapIndexed { index, timestamp ->
            DirectFrameTimestampMatch(
                frameIndex = index,
                directTimestampNanos = timestamp,
                sensorTimestampNanos = sensor[index],
                offsetNanos = offset,
            )
        },
        diagnostics = diagnostics(
            rawDirectTimestampsNanos = direct,
            rawSensorTimestampsNanos = sensor,
            fps = fps,
            exactMatches = 0,
            offsetNanos = offsets,
            verdict = DirectFrameTimestampVerdict.ZERO_OFFSET_EQUIVALENT,
        ),
    )
}

private fun diagnostics(
    rawDirectTimestampsNanos: List<Long>,
    rawSensorTimestampsNanos: List<Long>,
    fps: Int,
    exactMatches: Int,
    offsetNanos: List<Long>,
    verdict: DirectFrameTimestampVerdict,
): DirectFrameTimestampDiagnostics {
    val positiveDirect = rawDirectTimestampsNanos.filter { it > 0L }
    val directUniqueInConsumedOrder = positiveDirect.distinct()
    val directDiagnostics = if (directUniqueInConsumedOrder.isNotEmpty()) {
        buildOrderedTimestampDiagnostics(directUniqueInConsumedOrder, fps)
    } else {
        emptyTimestampDiagnostics(fps)
    }
    val sensorDiagnostics = buildTimestampDiagnostics(rawSensorTimestampsNanos, fps)
    val normalizedSensor = normalizeSensorTimestamps(rawSensorTimestampsNanos)
    return DirectFrameTimestampDiagnostics(
        rawDirectTimestampCount = rawDirectTimestampsNanos.size,
        positiveDirectTimestampCount = positiveDirect.size,
        uniqueDirectTimestampCount = directUniqueInConsumedOrder.size,
        rawSensorTimestampCount = rawSensorTimestampsNanos.size,
        positiveSensorTimestampCount = normalizedSensor.positiveCount,
        uniqueSensorTimestampCount = normalizedSensor.uniqueTimestampsNanos.size,
        directGapNanos = directDiagnostics.gapNanos,
        sensorGapNanos = sensorDiagnostics.gapNanos,
        medianDirectGapMillis = directDiagnostics.medianGapMillis,
        maximumDirectGapMillis = directDiagnostics.maximumGapMillis,
        expectedGapMillis = directDiagnostics.expectedGapMillis,
        droppedFrameGapThresholdMillis = directDiagnostics.droppedFrameGapThresholdMillis,
        exactMatchCount = exactMatches,
        sensorMembershipCount = exactMatches,
        offsetNanos = offsetNanos.take(MAX_DIRECT_OFFSET_SAMPLES),
        verdict = verdict,
    )
}

private fun emptyTimestampDiagnostics(fps: Int): TimestampDiagnostics =
    buildOrderedTimestampDiagnostics(emptyList(), fps)

private fun failure(
    reason: DirectTimingSourceFailure,
    message: String,
    diagnostics: DirectFrameTimestampDiagnostics,
): DirectFrameTimestampProofOutcome.Failure =
    DirectFrameTimestampProofOutcome.Failure(reason, message, diagnostics)

private fun offsetSamples(direct: List<Long>, sensor: List<Long>): List<Long> {
    if (direct.isEmpty() || sensor.isEmpty()) return emptyList()
    val sensorSet = sensor.toSet()
    return if (direct.any { it in sensorSet }) {
        direct.map { timestamp -> if (timestamp in sensorSet) 0L else timestamp - nearestTimestamp(timestamp, sensor) }
    } else {
        direct.zip(sensor).map { (directTimestamp, sensorTimestamp) -> directTimestamp - sensorTimestamp }
    }.take(MAX_DIRECT_OFFSET_SAMPLES)
}

private fun nearestTimestamp(timestamp: Long, sensor: List<Long>): Long =
    sensor.minBy { abs(it - timestamp) }

private fun Long.isWrongByKFrameAmbiguous(fps: Int): Boolean {
    val expectedGap = expectedGapNanosForFps(fps)
    if (expectedGap <= 0L) return false
    val remainder = abs(this) % expectedGap
    return abs(this) >= expectedGap / 2 && (remainder <= 1_000L || expectedGap - remainder <= 1_000L)
}

private fun List<Long>.isStrictlyIncreasing(): Boolean =
    zipWithNext().all { (a, b) -> b > a }

private fun List<Long>.hasNearDuplicateGap(thresholdNanos: Long): Boolean =
    zipWithNext().any { (a, b) -> b - a in 1 until thresholdNanos }

private const val MAX_DIRECT_OFFSET_SAMPLES = 80
