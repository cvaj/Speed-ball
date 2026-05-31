package com.speedball.app.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong

class TimestampAnchorAnalyzerTest {
    @Test
    fun nearDuplicatePostCollapseMatchStillRejectsAsEvidenceOnly() {
        val first = ts(0.0)
        val outcome = analyzeTimestampAnchorEvidence(
            metadata = metadata(frameCount = 3),
            rawSensorTimestampsNanos = listOf(first, first + 1L, ts(8.333), ts(16.666)),
        )
        val rejected = assertInstanceOf(TimestampAnchorOutcome.Rejected::class.java, outcome)
        val evidence = rejected.diagnostics.nearDuplicateEvidence!!

        assertEquals(TimestampAnchorFailure.SENSOR_NEAR_DUPLICATE, rejected.reason)
        assertEquals(4, rejected.diagnostics.rawSensorTimestampCount)
        assertEquals(3, evidence.hypotheticalPostCollapseSensorCount)
        assertEquals(PostCollapseSensorCountComparison.MATCHES_DECODED_COUNT, evidence.postCollapseComparison)
        assertTrue(outcome !is TimestampAnchorOutcome.Proven)
    }

    @Test
    fun nearDuplicatePostCollapseMismatchRejectsWithEvidence() {
        val first = ts(0.0)
        val outcome = analyzeTimestampAnchorEvidence(
            metadata = metadata(frameCount = 3),
            rawSensorTimestampsNanos = listOf(first, first + 1L, ts(8.333), ts(16.666), ts(24.999)),
        )
        val rejected = assertInstanceOf(TimestampAnchorOutcome.Rejected::class.java, outcome)
        val evidence = rejected.diagnostics.nearDuplicateEvidence!!

        assertEquals(TimestampAnchorFailure.SENSOR_NEAR_DUPLICATE, rejected.reason)
        assertEquals(4, evidence.hypotheticalPostCollapseSensorCount)
        assertEquals(PostCollapseSensorCountComparison.STILL_MISMATCHED, evidence.postCollapseComparison)
    }

    @Test
    fun uniform120FpsGridsProduceShiftedCompetitors() {
        val sensorBaseMicros = 1_000_000L
        val metadata = metadata(presentationTimeMicros = listOf(0L, 8_333L, 16_666L, 24_999L))
        val generation = generateTimestampAnchorCandidates(
            metadata = metadata,
            rawSensorTimestampsNanos = metadata.presentationTimeMicros.map { (sensorBaseMicros + it) * 1_000L },
        )
        val offsets = generation.candidates.map { it.offsetMicros }.toSet()

        assertEquals(8_333L, generation.expectedGapMicros)
        assertEquals(-1..1, generation.shiftRange)
        assertTrue(sensorBaseMicros - 8_333L in offsets)
        assertTrue(sensorBaseMicros in offsets)
        assertTrue(sensorBaseMicros + 8_333L in offsets)
        assertTrue(generation.candidates.any { it.provenance.contains("shift=-1") })
        assertTrue(generation.candidates.any { it.provenance.contains("shift=1") })
        val deduplicatedBase = generation.candidates.single { it.offsetMicros == sensorBaseMicros }
        assertTrue(deduplicatedBase.provenance.contains("shift=0"))
        assertTrue(deduplicatedBase.provenance.contains("shift=-1"))
    }

    @Test
    fun s10ShapeDerivesRangeWideEnoughForFullCountDisplacement() {
        val metadata = metadata(frameCount = 266)
        val generation = generateTimestampAnchorCandidates(
            metadata = metadata,
            rawSensorTimestampsNanos = sensorTimestamps(count = 320),
        )

        assertTrue(-54 in generation.shiftRange)
        assertTrue(54 in generation.shiftRange)
        assertEquals((-56..56).toList(), generation.evaluatedWholeFrameShifts)
    }

    @Test
    fun singleSampledBaseStillEvaluatesShiftedCompetitors() {
        val metadata = metadata(presentationTimeMicros = listOf(0L))
        val generation = generateTimestampAnchorCandidates(
            metadata = metadata,
            rawSensorTimestampsNanos = listOf(1_000_000_000L),
        )
        val offsets = generation.candidates.map { it.offsetMicros }.toSet()

        assertEquals(-1..1, generation.shiftRange)
        assertTrue(991_667L in offsets)
        assertTrue(1_000_000L in offsets)
        assertTrue(1_008_333L in offsets)
    }

    @Test
    fun analyzerDiagnosticsListEvaluatedShiftsAndProvenance() {
        val metadata = metadata(frameCount = 4)
        val outcome = analyzeTimestampAnchorEvidence(
            metadata = metadata,
            rawSensorTimestampsNanos = sensorTimestamps(count = 4, baseMicros = 1_000_000L),
        )
        val rejected = assertInstanceOf(TimestampAnchorOutcome.Rejected::class.java, outcome)
        val diagnostics = rejected.diagnostics

        assertEquals(TimestampAnchorFailure.NO_CANDIDATE, rejected.reason)
        assertEquals(diagnostics.evaluatedCandidates.size, diagnostics.evaluatedCandidateCount)
        assertTrue(diagnostics.evaluatedCandidates.any { it.wholeFrameShift == -1 })
        assertTrue(diagnostics.evaluatedCandidates.any { it.wholeFrameShift == 0 })
        assertTrue(diagnostics.evaluatedCandidates.any { it.wholeFrameShift == 1 })
        assertTrue(diagnostics.evaluatedCandidates.any { it.provenance.contains("firstPts[0]") })
        assertTrue(diagnostics.evaluatedCandidates.all { it.provenance.contains("shift=") })
    }

    private fun metadata(frameCount: Int): DecodedVideoMetadata =
        metadata(List(frameCount) { index -> (index * 8_333.333).roundToLong() })

    private fun metadata(presentationTimeMicros: List<Long>): DecodedVideoMetadata =
        DecodedVideoMetadata(
            frameCount = presentationTimeMicros.size,
            width = 1280,
            height = 720,
            durationMicros = null,
            presentationTimeMicros = presentationTimeMicros,
            medianPresentationGapMillis = null,
            maximumPresentationGapMillis = null,
        )

    private fun sensorTimestamps(
        count: Int,
        baseMicros: Long = 1_000_000L,
        gapMicros: Long = 8_333L,
    ): List<Long> =
        List(count) { index -> (baseMicros + index * gapMicros) * 1_000L }

    private fun ts(offsetMillis: Double): Long =
        1_000_000_000L + (offsetMillis * 1_000_000.0).roundToLong()
}
