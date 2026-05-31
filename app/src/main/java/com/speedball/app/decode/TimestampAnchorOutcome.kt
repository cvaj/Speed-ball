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
