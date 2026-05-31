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

    private fun diagnostics(): TimestampAnchorDiagnostics =
        TimestampAnchorDiagnostics(
            decodedFrameCount = 3,
            rawSensorTimestampCount = 4,
            uniqueSensorTimestampCount = 4,
            evaluatedCandidateCount = 1,
            survivingCandidateCount = 0,
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
