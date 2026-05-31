package com.speedball.app.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TimestampAnchorOutcomeTest {
    @Test
    fun provenCarriesTimestampAnchorMatchesNotFrameTimestampPairs() {
        val proven = TimestampAnchorOutcome.Proven(
            candidate = TimestampAnchorCandidate(
                offsetMicros = 1_000L,
                wholeFrameShift = 0,
                provenance = "first-pts-early-sensor",
            ),
            matches = listOf(
                TimestampAnchorMatch(
                    frameIndex = 0,
                    presentationTimeMicros = 0L,
                    sensorIndex = 0,
                    sensorTimestampNanos = 1_000_000_000L,
                    residualMicros = 0L,
                ),
            ),
            diagnostics = diagnostics(),
        )

        assertInstanceOf(TimestampAnchorOutcome.Proven::class.java, proven)
        assertEquals(TimestampAnchorMatch::class.java, proven.matches.single()::class.java)
        val asAny: Any = proven
        assertFalse(asAny is DecodeOutcome)
        assertNoFrameTimestampPairField(TimestampAnchorOutcome.Proven::class.java)
    }

    @Test
    fun rejectedOutcomeCarriesOnlyReasonMessageAndDiagnostics() {
        val rejected = TimestampAnchorOutcome.Rejected(
            reason = TimestampAnchorFailure.SENSOR_NEAR_DUPLICATE,
            message = "Timestamp anchor rejected for developer diagnostics.",
            diagnostics = diagnostics(),
        )
        val fields = rejected::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }

        assertEquals(listOf("reason", "message", "diagnostics"), fields)
        assertNoPrivateOrMeasurementPayloadNames(fields + rejected.message + rejected.diagnostics::class.java.declaredFields.map { it.name })
    }

    @Test
    fun failureTaxonomyIsPinned() {
        assertEquals(
            listOf(
                TimestampAnchorFailure.NO_CANDIDATE,
                TimestampAnchorFailure.AMBIGUOUS_OFFSETS,
                TimestampAnchorFailure.RESIDUAL_TOO_LARGE,
                TimestampAnchorFailure.NON_MONOTONIC_MAPPING,
                TimestampAnchorFailure.MANY_TO_ONE_MAPPING,
                TimestampAnchorFailure.SYNTHETIC_UNIFORM_PTS,
                TimestampAnchorFailure.SENSOR_NEAR_DUPLICATE,
                TimestampAnchorFailure.DROPPED_HOLE_MISMATCH,
                TimestampAnchorFailure.INSUFFICIENT_MATCHED_FRAMES,
            ),
            TimestampAnchorFailure.entries,
        )
    }

    @Test
    fun decodeSuccessRemainsOnlyMeasurementPairContainer() {
        val successFields = DecodeOutcome.Success::class.java.declaredFields.filterNot { it.name.startsWith("$") }
        val successFieldNames = successFields.map { it.name }

        assertEquals(listOf("metadata", "pairs", "diagnostics"), successFieldNames)
        assertTrue(successFields.any { it.genericType.typeName.contains(FrameTimestampPair::class.java.simpleName) })
        assertFalse(TimestampAnchorOutcome.Proven::class.java.interfaces.contains(DecodeOutcome::class.java))
        assertNoFrameTimestampPairField(TimestampAnchorOutcome.Proven::class.java)
    }

    @Test
    fun anchorDiagnosticFormatterChunksEvidenceAndUsesDisplayNameOnly() {
        val outcome = TimestampAnchorOutcome.Rejected(
            reason = TimestampAnchorFailure.AMBIGUOUS_OFFSETS,
            message = "Timestamp anchor rejected.",
            diagnostics = diagnostics(
                nearDuplicateEvidence = NearDuplicateEvidence(
                    rawPositiveSensorTimestampCount = 5,
                    exactDistinctSensorTimestampCount = 4,
                    nearDuplicateGroupCount = 1,
                    hypotheticalPostCollapseSensorCount = 3,
                    representativeNearDuplicateGapsNanos = listOf(1L, 2L),
                    postCollapseComparison = PostCollapseSensorCountComparison.MATCHES_DECODED_COUNT,
                    interpretation = "Post-collapse evidence only.",
                ),
                evaluatedCandidates = listOf(
                    TimestampAnchorCandidate(offsetMicros = 1_000L, wholeFrameShift = -1, provenance = "hidden"),
                    TimestampAnchorCandidate(offsetMicros = 2_000L, wholeFrameShift = 0, provenance = "hidden"),
                    TimestampAnchorCandidate(offsetMicros = 3_000L, wholeFrameShift = 1, provenance = "hidden"),
                ),
                candidateFailureReasons = listOf(TimestampAnchorFailure.NO_CANDIDATE),
                presentationDroppedHoles = listOf(TimestampAnchorDroppedHole(adjacentIndex = 1, gapMicros = 2_000L, gapMultiple = 2)),
                sensorDroppedHoles = listOf(TimestampAnchorDroppedHole(adjacentIndex = 2, gapMicros = 2_000L, gapMultiple = 2)),
            ),
        )
        val lines = timestampAnchorDiagnosticLogLines(
            outcome = outcome,
            displayFileName = "/storage/emulated/0/DCIM/private/session.mp4",
            chunkSize = 1,
        )
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("file=session.mp4"))
        assertTrue(joined.contains("verdict=REJECTED reason=AMBIGUOUS_OFFSETS"))
        assertTrue(joined.contains("rawPositiveSensorTs=5"))
        assertTrue(joined.contains("exactDistinctSensorTs=4"))
        assertTrue(joined.contains("hypotheticalPostCollapseSensorTs=3"))
        assertTrue(joined.contains("postCollapse=postCollapseMatchesDecodedCount"))
        assertTrue(joined.contains("TIMESTAMP_ANCHOR_CANDIDATE_OFFSETS_US file=session.mp4 chunk=3 count=3 values=[3000]"))
        assertTrue(joined.contains("TIMESTAMP_ANCHOR_WHOLE_FRAME_SHIFTS file=session.mp4 chunk=3 count=3 values=[1]"))
        assertTrue(joined.contains("TIMESTAMP_ANCHOR_NEAR_DUPLICATE_GAPS_NS file=session.mp4 chunk=2 count=2 values=[2]"))
        assertTrue(joined.contains("holeAgreement=MISMATCH"))
        assertTrue(joined.contains("TIMESTAMP_ANCHOR_PRESENTATION_HOLES"))
        assertTrue(joined.contains("TIMESTAMP_ANCHOR_SENSOR_HOLES"))
        assertFalse(joined.contains("/storage/"))
        assertFalse(joined.contains("content://"))
        assertFalse(joined.contains("hidden"))
        assertNoPrivateOrMeasurementPayloadNames(lines)
    }

    private fun diagnostics(
        nearDuplicateEvidence: NearDuplicateEvidence? = null,
        evaluatedCandidates: List<TimestampAnchorCandidate> = emptyList(),
        candidateFailureReasons: List<TimestampAnchorFailure> = emptyList(),
        presentationDroppedHoles: List<TimestampAnchorDroppedHole> = emptyList(),
        sensorDroppedHoles: List<TimestampAnchorDroppedHole> = emptyList(),
    ): TimestampAnchorDiagnostics =
        TimestampAnchorDiagnostics(
            decodedFrameCount = 3,
            rawSensorTimestampCount = 4,
            uniqueSensorTimestampCount = 4,
            nearDuplicateEvidence = nearDuplicateEvidence,
            evaluatedCandidates = evaluatedCandidates,
            evaluatedCandidateCount = evaluatedCandidates.size.coerceAtLeast(1),
            survivingCandidateCount = 0,
            candidateFailureReasons = candidateFailureReasons,
            maximumResidualMicros = 100L,
            medianResidualMicros = 50L,
            presentationDroppedHoles = presentationDroppedHoles,
            sensorDroppedHoles = sensorDroppedHoles,
        )

    private fun assertNoFrameTimestampPairField(type: Class<*>) {
        val fieldTypeNames = type.declaredFields
            .filterNot { it.name.startsWith("$") }
            .flatMap { listOf(it.type.typeName, it.genericType.typeName) }

        assertTrue(
            fieldTypeNames.none { it.contains(FrameTimestampPair::class.java.simpleName) },
            "Timestamp anchor outcomes must not expose FrameTimestampPair fields: $fieldTypeNames",
        )
    }

    private fun assertNoPrivateOrMeasurementPayloadNames(values: List<String>) {
        val tokens = values
            .flatMap { value -> value.lowercase().split(Regex("[^a-z0-9]+")) }
            .filter { it.isNotBlank() }
        listOf("mph", "angle", "trajectory", "bitmap", "media", "path", "uri", "detection").forEach { forbidden ->
            assertFalse(tokens.contains(forbidden), "Anchor rejection payload leaked '$forbidden': $tokens")
        }
    }
}
