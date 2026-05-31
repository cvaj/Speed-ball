package com.speedball.app.decode

import kotlin.math.abs

private const val DEFAULT_ANCHOR_REQUESTED_FPS = 120
private const val BOUNDARY_SENSOR_SAMPLE_COUNT = 3
private const val INTERIOR_SAMPLE_COUNT = 3
private const val WHOLE_FRAME_SHIFT_GUARD_BAND = 2

internal data class TimestampAnchorCandidateGenerationDiagnostics(
    val expectedGapMicros: Long,
    val shiftRange: IntRange,
    val baseOffsetCount: Int,
    val evaluatedWholeFrameShifts: List<Int>,
    val candidates: List<TimestampAnchorCandidate>,
)

private data class BaseOffsetSource(
    val offsetMicros: Long,
    val provenance: String,
)

/**
 * Evidence-only timestamp anchor investigation entrypoint.
 *
 * Phase 6 anchor analysis is diagnostic-only. Candidate generation can explain
 * what offsets would be evaluated, but it never produces measurement-ready
 * timestamps or `DecodeOutcome.Success`.
 */
fun analyzeTimestampAnchorEvidence(
    metadata: DecodedVideoMetadata,
    rawSensorTimestampsNanos: List<Long>,
    nearDuplicateThresholdNanos: Long = DEFAULT_NEAR_DUPLICATE_SENSOR_THRESHOLD_NANOS,
    requestedFps: Int = DEFAULT_ANCHOR_REQUESTED_FPS,
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
    val candidates = generateTimestampAnchorCandidates(
        metadata = metadata,
        rawSensorTimestampsNanos = rawSensorTimestampsNanos,
        requestedFps = requestedFps,
    )
    return TimestampAnchorOutcome.Rejected(
        reason = TimestampAnchorFailure.NO_CANDIDATE,
        message = "Timestamp anchor candidate generation is diagnostic-only until mapping and ambiguity gates are implemented.",
        diagnostics = diagnostics.copy(
            evaluatedCandidates = candidates.candidates,
            evaluatedCandidateCount = candidates.candidates.size,
        ),
    )
}

/**
 * Generates bounded diagnostic PTS-to-sensor offset candidates.
 *
 * Every sampled base offset is expanded by whole-frame competitors so a
 * uniform grid cannot hide an equally plausible off-by-k-frame anchor merely
 * because the sampled base list missed that shifted offset.
 */
internal fun generateTimestampAnchorCandidates(
    metadata: DecodedVideoMetadata,
    rawSensorTimestampsNanos: List<Long>,
    requestedFps: Int = DEFAULT_ANCHOR_REQUESTED_FPS,
): TimestampAnchorCandidateGenerationDiagnostics {
    require(requestedFps > 0) { "FPS must be positive." }
    val expectedGapMicros = expectedGapNanosForFps(requestedFps) / 1_000L
    val sensorMicros = buildTimestampDiagnostics(rawSensorTimestampsNanos, requestedFps)
        .uniqueTimestampsNanos
        .map { it / 1_000L }
    val shiftRange = wholeFrameShiftRange(
        decodedFrameCount = metadata.frameCount,
        uniqueSensorTimestampCount = sensorMicros.size,
    )
    val baseOffsets = buildBaseOffsetSources(
        presentationTimeMicros = metadata.presentationTimeMicros,
        sensorMicros = sensorMicros,
    )
    return TimestampAnchorCandidateGenerationDiagnostics(
        expectedGapMicros = expectedGapMicros,
        shiftRange = shiftRange,
        baseOffsetCount = baseOffsets.size,
        evaluatedWholeFrameShifts = shiftRange.toList(),
        candidates = expandAndDeduplicateCandidates(
            baseOffsets = baseOffsets,
            shiftRange = shiftRange,
            expectedGapMicros = expectedGapMicros,
        ),
    )
}

private fun wholeFrameShiftRange(
    decodedFrameCount: Int,
    uniqueSensorTimestampCount: Int,
): IntRange {
    val mismatch = abs(uniqueSensorTimestampCount - decodedFrameCount)
    // Equal or one-apart counts still need the minimum +/-1 competitor search.
    // Larger mismatches get the full count gap plus a small reviewed guard band
    // so the S10+ N=266/M=320 shape includes at least +/-54 and nearby offsets.
    val span = if (mismatch <= 1) 1 else mismatch + WHOLE_FRAME_SHIFT_GUARD_BAND
    return -span..span
}

private fun buildBaseOffsetSources(
    presentationTimeMicros: List<Long>,
    sensorMicros: List<Long>,
): List<BaseOffsetSource> {
    if (presentationTimeMicros.isEmpty() || sensorMicros.isEmpty()) return emptyList()
    val sources = mutableListOf<BaseOffsetSource>()
    val firstPts = presentationTimeMicros.first()
    sensorMicros.take(BOUNDARY_SENSOR_SAMPLE_COUNT).forEachIndexed { sensorIndex, sensor ->
        sources += BaseOffsetSource(
            offsetMicros = sensor - firstPts,
            provenance = "firstPts[0]->earlySensor[$sensorIndex]",
        )
    }
    val lastPtsIndex = presentationTimeMicros.lastIndex
    val lateSensorStart = (sensorMicros.size - BOUNDARY_SENSOR_SAMPLE_COUNT).coerceAtLeast(0)
    sensorMicros.takeLast(BOUNDARY_SENSOR_SAMPLE_COUNT).forEachIndexed { index, sensor ->
        sources += BaseOffsetSource(
            offsetMicros = sensor - presentationTimeMicros.last(),
            provenance = "lastPts[$lastPtsIndex]->lateSensor[${lateSensorStart + index}]",
        )
    }
    val interiorPtsIndexes = sampledInteriorIndexes(presentationTimeMicros.size)
    val interiorSensorIndexes = sampledInteriorIndexes(sensorMicros.size)
    for (ptsIndex in interiorPtsIndexes) {
        for (sensorIndex in interiorSensorIndexes) {
            sources += BaseOffsetSource(
                offsetMicros = sensorMicros[sensorIndex] - presentationTimeMicros[ptsIndex],
                provenance = "interiorPts[$ptsIndex]->interiorSensor[$sensorIndex]",
            )
        }
    }
    return sources.distinctBy { it.offsetMicros to it.provenance }
}

private fun sampledInteriorIndexes(size: Int): List<Int> {
    if (size <= 2) return emptyList()
    val interior = 1 until size - 1
    if (interior.count() <= INTERIOR_SAMPLE_COUNT) return interior.toList()
    val lastInterior = size - 2
    return (1..INTERIOR_SAMPLE_COUNT)
        .map { sample -> 1 + ((lastInterior - 1) * sample) / (INTERIOR_SAMPLE_COUNT + 1) }
        .distinct()
}

private fun expandAndDeduplicateCandidates(
    baseOffsets: List<BaseOffsetSource>,
    shiftRange: IntRange,
    expectedGapMicros: Long,
): List<TimestampAnchorCandidate> {
    val provenanceByOffset = linkedMapOf<Long, MutableList<Pair<Int, String>>>()
    for (base in baseOffsets) {
        for (shift in shiftRange) {
            val offset = base.offsetMicros + shift * expectedGapMicros
            provenanceByOffset.getOrPut(offset) { mutableListOf() } += shift to base.provenance
        }
    }
    return provenanceByOffset
        .toSortedMap()
        .map { (offset, provenances) ->
            val first = provenances.first()
            TimestampAnchorCandidate(
                offsetMicros = offset,
                wholeFrameShift = first.first,
                provenance = provenances.joinToString(separator = ";") { (shift, provenance) ->
                    "$provenance shift=$shift"
                },
            )
        }
}
