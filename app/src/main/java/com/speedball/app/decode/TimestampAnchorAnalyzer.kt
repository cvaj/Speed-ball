package com.speedball.app.decode

import kotlin.math.abs
import kotlin.math.roundToInt

private const val DEFAULT_ANCHOR_REQUESTED_FPS = 120
private const val BOUNDARY_SENSOR_SAMPLE_COUNT = 3
private const val INTERIOR_SAMPLE_COUNT = 3
private const val WHOLE_FRAME_SHIFT_GUARD_BAND = 2
private const val MAX_ANCHOR_RESIDUAL_MICROS = 750L
private const val MEDIAN_ANCHOR_RESIDUAL_MICROS = 250L

internal data class TimestampAnchorCandidateGenerationDiagnostics(
    val expectedGapMicros: Long,
    val shiftRange: IntRange,
    val baseOffsetCount: Int,
    val evaluatedWholeFrameShifts: List<Int>,
    val candidates: List<TimestampAnchorCandidate>,
)

internal data class TimestampAnchorMappingEvaluation(
    val candidate: TimestampAnchorCandidate,
    val matches: List<TimestampAnchorMatch>,
    val failure: TimestampAnchorFailure?,
    val maximumResidualMicros: Long?,
    val medianResidualMicros: Long?,
    val presentationDroppedHoles: List<TimestampAnchorDroppedHole> = emptyList(),
    val sensorDroppedHoles: List<TimestampAnchorDroppedHole> = emptyList(),
) {
    val passes: Boolean = failure == null
}

internal data class TimestampAnchorDroppedHole(
    val adjacentIndex: Int,
    val gapMicros: Long,
    val gapMultiple: Int,
)

internal data class TimestampAnchorHoleClassification(
    val expectedGapMillis: Double,
    val droppedFrameGapThresholdMillis: Double,
    val holes: List<TimestampAnchorDroppedHole>,
)

internal data class TimestampAnchorHoleAgreement(
    val presentation: TimestampAnchorHoleClassification,
    val sensor: TimestampAnchorHoleClassification,
    val failure: TimestampAnchorFailure?,
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
    val evaluations = evaluateTimestampAnchorMappings(
        metadata = metadata,
        rawSensorTimestampsNanos = rawSensorTimestampsNanos,
        requestedFps = requestedFps,
        candidateGeneration = candidates,
    )
    val survivors = evaluations.filter { it.passes }
    val distinctSurvivors = distinctSurvivingMappings(survivors)
    val mappedDiagnostics = diagnostics.copy(
        evaluatedCandidates = candidates.candidates,
        evaluatedCandidateCount = candidates.candidates.size,
        survivingCandidateCount = distinctSurvivors.size,
        candidateFailureReasons = evaluations.mapNotNull { it.failure }.distinct(),
        maximumResidualMicros = evaluations.mapNotNull { it.maximumResidualMicros }.minOrNull(),
        medianResidualMicros = evaluations.mapNotNull { it.medianResidualMicros }.minOrNull(),
    )
    return when (distinctSurvivors.size) {
        1 -> {
            val survivor = distinctSurvivors.single()
            TimestampAnchorOutcome.Proven(
                candidate = survivor.candidate,
                matches = survivor.matches,
                diagnostics = mappedDiagnostics,
            )
        }
        0 -> TimestampAnchorOutcome.Rejected(
            reason = mostSpecificMappingFailure(evaluations),
            message = "No timestamp anchor candidate survived mapping, residual, and dropped-hole gates.",
            diagnostics = mappedDiagnostics,
        )
        else -> TimestampAnchorOutcome.Rejected(
            reason = TimestampAnchorFailure.AMBIGUOUS_OFFSETS,
            message = "Multiple timestamp anchor candidates survived; refusing to choose a frame-shifted offset.",
            diagnostics = mappedDiagnostics,
        )
    }
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

internal fun evaluateTimestampAnchorMappings(
    metadata: DecodedVideoMetadata,
    rawSensorTimestampsNanos: List<Long>,
    requestedFps: Int = DEFAULT_ANCHOR_REQUESTED_FPS,
    candidateGeneration: TimestampAnchorCandidateGenerationDiagnostics =
        generateTimestampAnchorCandidates(metadata, rawSensorTimestampsNanos, requestedFps),
): List<TimestampAnchorMappingEvaluation> {
    require(requestedFps > 0) { "FPS must be positive." }
    val uniqueSensorTimestampsNanos = buildTimestampDiagnostics(rawSensorTimestampsNanos, requestedFps)
        .uniqueTimestampsNanos
    return candidateGeneration.candidates.map { candidate ->
        mapTimestampAnchorCandidate(
            metadata = metadata,
            uniqueSensorTimestampsNanos = uniqueSensorTimestampsNanos,
            candidate = candidate,
            requestedFps = requestedFps,
        )
    }
}

internal fun mapTimestampAnchorCandidate(
    metadata: DecodedVideoMetadata,
    uniqueSensorTimestampsNanos: List<Long>,
    candidate: TimestampAnchorCandidate,
    maximumResidualToleranceMicros: Long = MAX_ANCHOR_RESIDUAL_MICROS,
    medianResidualToleranceMicros: Long = MEDIAN_ANCHOR_RESIDUAL_MICROS,
    requestedFps: Int = DEFAULT_ANCHOR_REQUESTED_FPS,
): TimestampAnchorMappingEvaluation {
    require(maximumResidualToleranceMicros >= 0L) { "Maximum residual tolerance must be non-negative." }
    require(medianResidualToleranceMicros >= 0L) { "Median residual tolerance must be non-negative." }
    require(requestedFps > 0) { "FPS must be positive." }
    val matches = mutableListOf<TimestampAnchorMatch>()
    var sensorCursor = 0
    for ((frameIndex, ptsMicros) in metadata.presentationTimeMicros.withIndex()) {
        if (sensorCursor >= uniqueSensorTimestampsNanos.size) break
        val targetMicros = ptsMicros + candidate.offsetMicros
        var bestIndex = sensorCursor
        while (bestIndex + 1 < uniqueSensorTimestampsNanos.size) {
            val currentResidual = abs(uniqueSensorTimestampsNanos[bestIndex] / 1_000L - targetMicros)
            val nextResidual = abs(uniqueSensorTimestampsNanos[bestIndex + 1] / 1_000L - targetMicros)
            if (nextResidual > currentResidual) break
            bestIndex += 1
        }
        val sensorTimestampNanos = uniqueSensorTimestampsNanos[bestIndex]
        matches += TimestampAnchorMatch(
            frameIndex = frameIndex,
            presentationTimeMicros = ptsMicros,
            sensorIndex = bestIndex,
            sensorTimestampNanos = sensorTimestampNanos,
            residualMicros = abs(sensorTimestampNanos / 1_000L - targetMicros),
        )
        sensorCursor = bestIndex + 1
    }
    val structuralFailure = validateTimestampAnchorMatches(matches, metadata.frameCount)
    val residuals = matches.map { it.residualMicros }
    val maxResidual = residuals.maxOrNull()
    val medianResidual = residuals.medianLongOrNull()
    val residualFailure = if (
        structuralFailure == null &&
        ((maxResidual ?: 0L) > maximumResidualToleranceMicros || (medianResidual ?: 0L) > medianResidualToleranceMicros)
    ) {
        TimestampAnchorFailure.RESIDUAL_TOO_LARGE
    } else {
        null
    }
    val holeAgreement = if (structuralFailure == null && residualFailure == null) {
        validateTimestampAnchorHoleAgreement(matches, requestedFps)
    } else {
        null
    }
    return TimestampAnchorMappingEvaluation(
        candidate = candidate,
        matches = matches,
        failure = structuralFailure ?: residualFailure ?: holeAgreement?.failure,
        maximumResidualMicros = maxResidual,
        medianResidualMicros = medianResidual,
        presentationDroppedHoles = holeAgreement?.presentation?.holes.orEmpty(),
        sensorDroppedHoles = holeAgreement?.sensor?.holes.orEmpty(),
    )
}

internal fun validateTimestampAnchorMatches(
    matches: List<TimestampAnchorMatch>,
    decodedFrameCount: Int,
): TimestampAnchorFailure? {
    if (matches.size < MINIMUM_RECONCILABLE_FRAME_COUNT) {
        return TimestampAnchorFailure.INSUFFICIENT_MATCHED_FRAMES
    }
    if (matches.map { it.sensorIndex }.distinct().size != matches.size) {
        return TimestampAnchorFailure.MANY_TO_ONE_MAPPING
    }
    if (!matches.map { it.sensorIndex }.zipWithNext().all { (a, b) -> b > a }) {
        return TimestampAnchorFailure.NON_MONOTONIC_MAPPING
    }
    if (matches.size != decodedFrameCount) {
        return TimestampAnchorFailure.NO_CANDIDATE
    }
    return null
}

internal fun validateTimestampAnchorHoleAgreement(
    matches: List<TimestampAnchorMatch>,
    requestedFps: Int = DEFAULT_ANCHOR_REQUESTED_FPS,
): TimestampAnchorHoleAgreement {
    val presentation = classifyTimestampAnchorDroppedHoles(
        timestampsNanos = matches.map { it.presentationTimeMicros * 1_000L },
        requestedFps = requestedFps,
    )
    val sensor = classifyTimestampAnchorDroppedHoles(
        timestampsNanos = matches.map { it.sensorTimestampNanos },
        requestedFps = requestedFps,
    )
    val presentationPositions = presentation.holes.map { it.adjacentIndex }
    val sensorPositions = sensor.holes.map { it.adjacentIndex }
    val failure = when {
        presentation.holes.isEmpty() && sensor.holes.isNotEmpty() ->
            TimestampAnchorFailure.SYNTHETIC_UNIFORM_PTS
        presentationPositions != sensorPositions ->
            TimestampAnchorFailure.DROPPED_HOLE_MISMATCH
        presentation.holes.zip(sensor.holes).any { (pts, sensorHole) -> pts.gapMultiple != sensorHole.gapMultiple } ->
            TimestampAnchorFailure.DROPPED_HOLE_MISMATCH
        else ->
            null
    }
    return TimestampAnchorHoleAgreement(
        presentation = presentation,
        sensor = sensor,
        failure = failure,
    )
}

internal fun classifyTimestampAnchorDroppedHoles(
    timestampsNanos: List<Long>,
    requestedFps: Int = DEFAULT_ANCHOR_REQUESTED_FPS,
): TimestampAnchorHoleClassification {
    val diagnostics = buildOrderedTimestampDiagnostics(timestampsNanos, requestedFps)
    val holes = diagnostics.gapNanos.mapIndexedNotNull { index, gapNanos ->
        val gapMillis = gapNanos / 1_000_000.0
        if (gapMillis > diagnostics.droppedFrameGapThresholdMillis) {
            TimestampAnchorDroppedHole(
                adjacentIndex = index,
                gapMicros = gapNanos / 1_000L,
                gapMultiple = (gapMillis / diagnostics.expectedGapMillis).roundToInt().coerceAtLeast(2),
            )
        } else {
            null
        }
    }
    return TimestampAnchorHoleClassification(
        expectedGapMillis = diagnostics.expectedGapMillis,
        droppedFrameGapThresholdMillis = diagnostics.droppedFrameGapThresholdMillis,
        holes = holes,
    )
}

private fun mostSpecificMappingFailure(evaluations: List<TimestampAnchorMappingEvaluation>): TimestampAnchorFailure {
    val failures = evaluations.mapNotNull { it.failure }.toSet()
    return when {
        TimestampAnchorFailure.RESIDUAL_TOO_LARGE in failures -> TimestampAnchorFailure.RESIDUAL_TOO_LARGE
        TimestampAnchorFailure.SYNTHETIC_UNIFORM_PTS in failures -> TimestampAnchorFailure.SYNTHETIC_UNIFORM_PTS
        TimestampAnchorFailure.DROPPED_HOLE_MISMATCH in failures -> TimestampAnchorFailure.DROPPED_HOLE_MISMATCH
        TimestampAnchorFailure.MANY_TO_ONE_MAPPING in failures -> TimestampAnchorFailure.MANY_TO_ONE_MAPPING
        TimestampAnchorFailure.NON_MONOTONIC_MAPPING in failures -> TimestampAnchorFailure.NON_MONOTONIC_MAPPING
        TimestampAnchorFailure.INSUFFICIENT_MATCHED_FRAMES in failures -> TimestampAnchorFailure.INSUFFICIENT_MATCHED_FRAMES
        else -> TimestampAnchorFailure.NO_CANDIDATE
    }
}

private fun distinctSurvivingMappings(
    survivors: List<TimestampAnchorMappingEvaluation>,
): List<TimestampAnchorMappingEvaluation> =
    survivors
        .groupBy { evaluation -> evaluation.matches.map { it.sensorIndex } }
        .values
        .map { equivalentMappings ->
            equivalentMappings.minWith(
                compareBy<TimestampAnchorMappingEvaluation> { it.maximumResidualMicros ?: Long.MAX_VALUE }
                    .thenBy { it.medianResidualMicros ?: Long.MAX_VALUE }
                    .thenBy { abs(it.candidate.offsetMicros) }
                    .thenBy { it.candidate.offsetMicros }
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
