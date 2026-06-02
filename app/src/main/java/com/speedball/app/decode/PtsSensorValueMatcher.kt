package com.speedball.app.decode

import kotlin.math.abs

private const val DEFAULT_VALUE_MATCH_TOLERANCE_MICROS = 750L
private const val DEFAULT_MINIMUM_CLEAN_VALUE_MATCH_RUN = 12

private data class PtsSensorValueMatch(
    val frameIndex: Int,
    val sensorIndex: Int,
    val residualMicros: Long,
    val ambiguous: Boolean,
)

/**
 * Searches for one constant offset that maps decoded PTS values to exact
 * SENSOR_TIMESTAMP values without requiring equal decoded/sensor counts.
 */
fun analyzePtsSensorValueMatch(
    presentationTimeMicros: List<Long>,
    uniqueSensorTimestampsNanos: List<Long>,
    toleranceMicros: Long = DEFAULT_VALUE_MATCH_TOLERANCE_MICROS,
    minimumCleanRun: Int = DEFAULT_MINIMUM_CLEAN_VALUE_MATCH_RUN,
): PtsSensorValueMatchDiagnostics {
    require(toleranceMicros >= 0L) { "Tolerance must be non-negative." }
    require(minimumCleanRun > 0) { "Minimum clean run must be positive." }
    if (presentationTimeMicros.isEmpty() || uniqueSensorTimestampsNanos.isEmpty()) {
        return PtsSensorValueMatchDiagnostics(
            verdict = PtsSensorValueMatchVerdict.NOT_EVALUATED,
            decodedFrameCount = presentationTimeMicros.size,
            uniqueSensorTimestampCount = uniqueSensorTimestampsNanos.size,
            evaluatedOffsetCount = 0,
            toleranceMicros = toleranceMicros,
            bestOffsetMicros = null,
            matchedFrameCount = 0,
            unambiguousFrameCount = 0,
            ambiguousFrameCount = 0,
            longestContiguousUnambiguousRun = 0,
            maximumResidualMicros = null,
            medianResidualMicros = null,
        )
    }

    val sensorMicros = uniqueSensorTimestampsNanos.map { it / 1_000L }
    val candidateOffsets = buildValueMatchOffsetCandidates(presentationTimeMicros, sensorMicros)
    val best = candidateOffsets.asSequence()
        .map { offset -> offset to mapValueMatchOffset(presentationTimeMicros, sensorMicros, offset, toleranceMicros) }
        .maxWithOrNull(
            compareBy<Pair<Long, List<PtsSensorValueMatch>>> { (_, matches) -> longestContiguousUnambiguousRun(matches) }
                .thenBy { (_, matches) -> matches.count { !it.ambiguous } }
                .thenBy { (_, matches) -> matches.size }
                .thenBy { (_, matches) -> -matches.count { it.ambiguous } }
                .thenBy { (_, matches) -> -(matches.map { it.residualMicros }.maxOrNull() ?: Long.MAX_VALUE) }
                .thenBy { (_, matches) -> -(matches.map { it.residualMicros }.medianLongOrNull() ?: Long.MAX_VALUE) }
                .thenBy { (offset, _) -> -abs(offset) },
        )

    val bestOffset = best?.first
    val bestMatches = best?.second.orEmpty()
    val residuals = bestMatches.map { it.residualMicros }
    val unambiguousCount = bestMatches.count { !it.ambiguous }
    val ambiguousCount = bestMatches.count { it.ambiguous }
    val cleanRun = longestContiguousUnambiguousRun(bestMatches)
    val verdict = when {
        bestMatches.isEmpty() -> PtsSensorValueMatchVerdict.NO_MATCH
        ambiguousCount > 0 -> PtsSensorValueMatchVerdict.AMBIGUOUS_MATCH
        cleanRun >= minimumCleanRun && bestMatches.size == presentationTimeMicros.size -> PtsSensorValueMatchVerdict.UNIQUE_MATCH
        else -> PtsSensorValueMatchVerdict.PARTIAL_MATCH
    }
    return PtsSensorValueMatchDiagnostics(
        verdict = verdict,
        decodedFrameCount = presentationTimeMicros.size,
        uniqueSensorTimestampCount = uniqueSensorTimestampsNanos.size,
        evaluatedOffsetCount = candidateOffsets.size,
        toleranceMicros = toleranceMicros,
        bestOffsetMicros = bestOffset,
        matchedFrameCount = bestMatches.size,
        unambiguousFrameCount = unambiguousCount,
        ambiguousFrameCount = ambiguousCount,
        longestContiguousUnambiguousRun = cleanRun,
        maximumResidualMicros = residuals.maxOrNull(),
        medianResidualMicros = residuals.medianLongOrNull(),
    )
}

private fun buildValueMatchOffsetCandidates(
    presentationTimeMicros: List<Long>,
    sensorMicros: List<Long>,
): List<Long> =
    presentationTimeMicros
        .flatMap { pts -> sensorMicros.map { sensor -> sensor - pts } }
        .distinct()

private fun mapValueMatchOffset(
    presentationTimeMicros: List<Long>,
    sensorMicros: List<Long>,
    offsetMicros: Long,
    toleranceMicros: Long,
): List<PtsSensorValueMatch> {
    val matches = mutableListOf<PtsSensorValueMatch>()
    var sensorCursor = 0
    for ((frameIndex, ptsMicros) in presentationTimeMicros.withIndex()) {
        val targetMicros = ptsMicros + offsetMicros
        while (sensorCursor < sensorMicros.size && sensorMicros[sensorCursor] < targetMicros - toleranceMicros) {
            sensorCursor += 1
        }
        if (sensorCursor >= sensorMicros.size) break
        var scanIndex = sensorCursor
        var bestIndex = -1
        var bestResidual = Long.MAX_VALUE
        var candidateCount = 0
        while (scanIndex < sensorMicros.size && sensorMicros[scanIndex] <= targetMicros + toleranceMicros) {
            val residual = abs(sensorMicros[scanIndex] - targetMicros)
            candidateCount += 1
            if (residual < bestResidual) {
                bestResidual = residual
                bestIndex = scanIndex
            }
            scanIndex += 1
        }
        if (bestIndex >= 0) {
            matches += PtsSensorValueMatch(
                frameIndex = frameIndex,
                sensorIndex = bestIndex,
                residualMicros = bestResidual,
                ambiguous = candidateCount > 1,
            )
            sensorCursor = bestIndex + 1
        }
    }
    return matches
}

private fun longestContiguousUnambiguousRun(matches: List<PtsSensorValueMatch>): Int {
    var best = 0
    var current = 0
    var previousFrameIndex: Int? = null
    for (match in matches) {
        current = if (!match.ambiguous && previousFrameIndex?.let { match.frameIndex == it + 1 } != false) {
            current + 1
        } else if (!match.ambiguous) {
            1
        } else {
            0
        }
        if (current > best) best = current
        previousFrameIndex = match.frameIndex
    }
    return best
}

private fun List<Long>.medianLongOrNull(): Long? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        ((sorted[middle - 1] + sorted[middle]) / 2.0).toLong()
    } else {
        sorted[middle]
    }
}
