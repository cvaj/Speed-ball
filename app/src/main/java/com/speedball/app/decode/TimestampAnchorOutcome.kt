package com.speedball.app.decode

/**
 * Investigation-only verdict for value-anchored timestamp analysis.
 *
 * These types are developer diagnostics for comparing decoded presentation
 * timestamps with Camera2 `SENSOR_TIMESTAMP` evidence. They must not be
 * converted into `DecodeOutcome.Success`, must not feed measurement math, and
 * must not be treated as a source of measurement-ready frame timestamps.
 */
sealed interface TimestampAnchorOutcome {
    /**
     * A single diagnostic anchor survived the investigation gates.
     *
     * `matches` intentionally contains `TimestampAnchorMatch` values instead of
     * `FrameTimestampPair` values. A proven anchor is still not a measurement
     * result in Phase 6.
     */
    data class Proven(
        val candidate: TimestampAnchorCandidate,
        val matches: List<TimestampAnchorMatch>,
        val diagnostics: TimestampAnchorDiagnostics,
    ) : TimestampAnchorOutcome

    /** Diagnostic rejection with a typed reason and bounded developer details. */
    data class Rejected(
        val reason: TimestampAnchorFailure,
        val message: String,
        val diagnostics: TimestampAnchorDiagnostics,
    ) : TimestampAnchorOutcome
}

/** One diagnostic PTS-to-sensor match; not a measurement timestamp pair. */
data class TimestampAnchorMatch(
    val frameIndex: Int,
    val presentationTimeMicros: Long,
    val sensorIndex: Int,
    val sensorTimestampNanos: Long,
    val residualMicros: Long,
)

data class TimestampAnchorDroppedHole(
    val adjacentIndex: Int,
    val gapMicros: Long,
    val gapMultiple: Int,
)

/** Candidate offset evaluated by the timestamp anchor investigation. */
data class TimestampAnchorCandidate(
    val offsetMicros: Long,
    val wholeFrameShift: Int,
    val provenance: String,
)

/** Bounded diagnostic counters and residual summaries for anchor investigation. */
data class TimestampAnchorDiagnostics(
    val decodedFrameCount: Int,
    val rawSensorTimestampCount: Int,
    val uniqueSensorTimestampCount: Int,
    val nearDuplicateEvidence: NearDuplicateEvidence? = null,
    val evaluatedCandidates: List<TimestampAnchorCandidate> = emptyList(),
    val evaluatedCandidateCount: Int = 0,
    val survivingCandidateCount: Int = 0,
    val candidateFailureReasons: List<TimestampAnchorFailure> = emptyList(),
    val maximumResidualMicros: Long? = null,
    val medianResidualMicros: Long? = null,
    val presentationDroppedHoles: List<TimestampAnchorDroppedHole> = emptyList(),
    val sensorDroppedHoles: List<TimestampAnchorDroppedHole> = emptyList(),
)

/** Typed fail-loud reasons for diagnostic anchor rejection. */
enum class TimestampAnchorFailure {
    NO_CANDIDATE,
    AMBIGUOUS_OFFSETS,
    RESIDUAL_TOO_LARGE,
    NON_MONOTONIC_MAPPING,
    MANY_TO_ONE_MAPPING,
    SYNTHETIC_UNIFORM_PTS,
    SENSOR_NEAR_DUPLICATE,
    DROPPED_HOLE_MISMATCH,
    INSUFFICIENT_MATCHED_FRAMES,
}

fun timestampAnchorDiagnosticLogLines(
    outcome: TimestampAnchorOutcome,
    displayFileName: String,
    chunkSize: Int,
): List<String> {
    require(chunkSize > 0) { "Chunk size must be positive." }
    val fileName = displayFileName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "unknown" }
    val diagnostics = when (outcome) {
        is TimestampAnchorOutcome.Proven -> outcome.diagnostics
        is TimestampAnchorOutcome.Rejected -> outcome.diagnostics
    }
    val evidence = diagnostics.nearDuplicateEvidence
    val verdictFields = when (outcome) {
        is TimestampAnchorOutcome.Proven ->
            "verdict=PROVEN offsetMicros=${outcome.candidate.offsetMicros} wholeFrameShift=${outcome.candidate.wholeFrameShift} matches=${outcome.matches.size}"
        is TimestampAnchorOutcome.Rejected ->
            "verdict=REJECTED reason=${outcome.reason}"
    }
    val holeAgreement = when {
        diagnostics.presentationDroppedHoles.isEmpty() && diagnostics.sensorDroppedHoles.isEmpty() -> "NO_HOLES"
        diagnostics.presentationDroppedHoles == diagnostics.sensorDroppedHoles -> "MATCH"
        else -> "MISMATCH"
    }
    return buildList {
        add(
            "TIMESTAMP_ANCHOR_DIAGNOSTIC file=$fileName $verdictFields " +
                "decoded=${diagnostics.decodedFrameCount} rawPositiveSensorTs=${evidence?.rawPositiveSensorTimestampCount ?: diagnostics.rawSensorTimestampCount} " +
                "exactDistinctSensorTs=${evidence?.exactDistinctSensorTimestampCount ?: diagnostics.uniqueSensorTimestampCount} " +
                "hypotheticalPostCollapseSensorTs=${evidence?.hypotheticalPostCollapseSensorCount ?: "n/a"} " +
                "postCollapse=${evidence?.postCollapseComparison?.diagnosticName ?: "n/a"} nearDuplicateGroups=${evidence?.nearDuplicateGroupCount ?: 0} " +
                "evaluatedCandidates=${diagnostics.evaluatedCandidateCount} survivingMappings=${diagnostics.survivingCandidateCount} " +
                "maxResidualUs=${diagnostics.maximumResidualMicros ?: "n/a"} medianResidualUs=${diagnostics.medianResidualMicros ?: "n/a"} " +
                "presentationHoles=${diagnostics.presentationDroppedHoles.size} sensorHoles=${diagnostics.sensorDroppedHoles.size} holeAgreement=$holeAgreement " +
                "failures=${diagnostics.candidateFailureReasons.joinToString(separator = ",").ifEmpty { "none" }}",
        )
        evidence?.interpretation?.let { interpretation ->
            add("TIMESTAMP_ANCHOR_POST_COLLAPSE file=$fileName interpretation=${interpretation.compactForLog()}")
        }
        addChunkedLongLines(
            label = "TIMESTAMP_ANCHOR_CANDIDATE_OFFSETS_US",
            fileName = fileName,
            values = diagnostics.evaluatedCandidates.map { it.offsetMicros },
            chunkSize = chunkSize,
        )
        addChunkedIntLines(
            label = "TIMESTAMP_ANCHOR_WHOLE_FRAME_SHIFTS",
            fileName = fileName,
            values = diagnostics.evaluatedCandidates.map { it.wholeFrameShift }.distinct(),
            chunkSize = chunkSize,
        )
        addChunkedLongLines(
            label = "TIMESTAMP_ANCHOR_NEAR_DUPLICATE_GAPS_NS",
            fileName = fileName,
            values = evidence?.representativeNearDuplicateGapsNanos.orEmpty(),
            chunkSize = chunkSize,
        )
        addChunkedStringLines(
            label = "TIMESTAMP_ANCHOR_PRESENTATION_HOLES",
            fileName = fileName,
            values = diagnostics.presentationDroppedHoles.map { it.logToken() },
            chunkSize = chunkSize,
        )
        addChunkedStringLines(
            label = "TIMESTAMP_ANCHOR_SENSOR_HOLES",
            fileName = fileName,
            values = diagnostics.sensorDroppedHoles.map { it.logToken() },
            chunkSize = chunkSize,
        )
    }
}

private fun MutableList<String>.addChunkedLongLines(
    label: String,
    fileName: String,
    values: List<Long>,
    chunkSize: Int,
) {
    addChunkedStringLines(label, fileName, values.map { it.toString() }, chunkSize)
}

private fun MutableList<String>.addChunkedIntLines(
    label: String,
    fileName: String,
    values: List<Int>,
    chunkSize: Int,
) {
    addChunkedStringLines(label, fileName, values.map { it.toString() }, chunkSize)
}

private fun MutableList<String>.addChunkedStringLines(
    label: String,
    fileName: String,
    values: List<String>,
    chunkSize: Int,
) {
    if (values.isEmpty()) {
        add("$label file=$fileName chunk=0 count=0 values=[]")
        return
    }
    values.chunked(chunkSize).forEachIndexed { index, chunk ->
        add("$label file=$fileName chunk=${index + 1} count=${values.size} values=${chunk.joinToString(prefix = "[", postfix = "]")}")
    }
}

private fun TimestampAnchorDroppedHole.logToken(): String =
    "adjacentIndex=$adjacentIndex,gapMicros=$gapMicros,gapMultiple=$gapMultiple"

private fun String.compactForLog(): String =
    replace(Regex("\\s+"), "_")
