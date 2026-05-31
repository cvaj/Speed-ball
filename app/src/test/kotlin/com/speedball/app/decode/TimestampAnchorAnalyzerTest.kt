package com.speedball.app.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
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

    @Test
    fun cleanCaptureBasedFixtureMapsEveryPts() {
        val metadata = metadata(presentationTimeMicros = listOf(0L, 8_333L, 16_666L, 24_999L))
        val evaluation = mapTimestampAnchorCandidate(
            metadata = metadata,
            uniqueSensorTimestampsNanos = metadata.presentationTimeMicros
                .map { (1_000_000L + it) * 1_000L },
            candidate = candidate(offsetMicros = 1_000_000L),
        )

        assertNull(evaluation.failure)
        assertEquals(metadata.frameCount, evaluation.matches.size)
        assertEquals(listOf(0, 1, 2, 3), evaluation.matches.map { it.sensorIndex })
        assertEquals(0L, evaluation.maximumResidualMicros)
        assertEquals(0L, evaluation.medianResidualMicros)
    }

    @Test
    fun mappingTraversalAdvancesMonotonicSensorPointerInPtsOrder() {
        val evaluation = mapTimestampAnchorCandidate(
            metadata = metadata(presentationTimeMicros = listOf(0L, 1_000L, 2_000L)),
            uniqueSensorTimestampsNanos = listOf(1_000L, 1_800L, 2_000L, 3_000L).map { it * 1_000L },
            candidate = candidate(offsetMicros = 1_000L),
        )

        assertNull(evaluation.failure)
        assertEquals(listOf(0, 2, 3), evaluation.matches.map { it.sensorIndex })
        assertEquals(listOf(0L, 0L, 0L), evaluation.matches.map { it.residualMicros })
    }

    @Test
    fun manyToOneMatchesReject() {
        val failure = validateTimestampAnchorMatches(
            matches = listOf(
                match(frameIndex = 0, sensorIndex = 0),
                match(frameIndex = 1, sensorIndex = 0),
                match(frameIndex = 2, sensorIndex = 1),
            ),
            decodedFrameCount = 3,
        )

        assertEquals(TimestampAnchorFailure.MANY_TO_ONE_MAPPING, failure)
    }

    @Test
    fun nonMonotonicMatchesReject() {
        val failure = validateTimestampAnchorMatches(
            matches = listOf(
                match(frameIndex = 0, sensorIndex = 1),
                match(frameIndex = 1, sensorIndex = 0),
                match(frameIndex = 2, sensorIndex = 2),
            ),
            decodedFrameCount = 3,
        )

        assertEquals(TimestampAnchorFailure.NON_MONOTONIC_MAPPING, failure)
    }

    @Test
    fun residualsJustInsideMaxTolerancePass() {
        val evaluation = mapTimestampAnchorCandidate(
            metadata = metadata(presentationTimeMicros = listOf(0L, 1_000L, 2_000L)),
            uniqueSensorTimestampsNanos = listOf(1_000L, 2_000L, 3_749L).map { it * 1_000L },
            candidate = candidate(offsetMicros = 1_000L),
        )

        assertNull(evaluation.failure)
        assertEquals(749L, evaluation.maximumResidualMicros)
    }

    @Test
    fun residualsJustOutsideMaxToleranceReject() {
        val evaluation = mapTimestampAnchorCandidate(
            metadata = metadata(presentationTimeMicros = listOf(0L, 1_000L, 2_000L)),
            uniqueSensorTimestampsNanos = listOf(1_000L, 2_000L, 3_751L).map { it * 1_000L },
            candidate = candidate(offsetMicros = 1_000L),
        )

        assertEquals(TimestampAnchorFailure.RESIDUAL_TOO_LARGE, evaluation.failure)
        assertEquals(751L, evaluation.maximumResidualMicros)
    }

    @Test
    fun residualsJustInsideMedianTolerancePass() {
        val evaluation = mapTimestampAnchorCandidate(
            metadata = metadata(presentationTimeMicros = listOf(0L, 1_000L, 2_000L)),
            uniqueSensorTimestampsNanos = listOf(1_250L, 2_250L, 3_000L).map { it * 1_000L },
            candidate = candidate(offsetMicros = 1_000L),
        )

        assertNull(evaluation.failure)
        assertEquals(250L, evaluation.medianResidualMicros)
    }

    @Test
    fun residualsJustOutsideMedianToleranceReject() {
        val evaluation = mapTimestampAnchorCandidate(
            metadata = metadata(presentationTimeMicros = listOf(0L, 1_000L, 2_000L)),
            uniqueSensorTimestampsNanos = listOf(1_251L, 2_251L, 3_000L).map { it * 1_000L },
            candidate = candidate(offsetMicros = 1_000L),
        )

        assertEquals(TimestampAnchorFailure.RESIDUAL_TOO_LARGE, evaluation.failure)
        assertEquals(251L, evaluation.medianResidualMicros)
    }

    @Test
    fun fewerThanThreeMatchedFramesRejects() {
        val evaluation = mapTimestampAnchorCandidate(
            metadata = metadata(presentationTimeMicros = listOf(0L, 1_000L)),
            uniqueSensorTimestampsNanos = listOf(1_000L, 2_000L).map { it * 1_000L },
            candidate = candidate(offsetMicros = 1_000L),
        )

        assertEquals(TimestampAnchorFailure.INSUFFICIENT_MATCHED_FRAMES, evaluation.failure)
        assertEquals(2, evaluation.matches.size)
    }

    @Test
    fun analyzerReportsResidualGateDiagnosticsWithoutProvingAnchor() {
        val metadata = metadata(presentationTimeMicros = listOf(0L, 1_000L, 2_000L))
        val outcome = analyzeTimestampAnchorEvidence(
            metadata = metadata,
            rawSensorTimestampsNanos = listOf(1_000L, 2_000L, 3_000L).map { it * 1_000L },
        )
        val rejected = assertInstanceOf(TimestampAnchorOutcome.Rejected::class.java, outcome)

        assertEquals(TimestampAnchorFailure.NO_CANDIDATE, rejected.reason)
        assertTrue(rejected.diagnostics.survivingCandidateCount > 0)
        assertEquals(0L, rejected.diagnostics.maximumResidualMicros)
        assertEquals(0L, rejected.diagnostics.medianResidualMicros)
        assertTrue(outcome !is TimestampAnchorOutcome.Proven)
    }

    @Test
    fun captureBasedPtsWithMatchingTwoIntervalHolePassesHoleGate() {
        val metadata = metadata(presentationTimeMicros = listOf(0L, 1_000L, 3_000L, 4_000L))
        val evaluation = mapTimestampAnchorCandidate(
            metadata = metadata,
            uniqueSensorTimestampsNanos = metadata.presentationTimeMicros
                .map { (1_000_000L + it) * 1_000L },
            candidate = candidate(offsetMicros = 1_000_000L),
            requestedFps = 1_000,
        )

        assertNull(evaluation.failure)
        assertEquals(listOf(TimestampAnchorDroppedHole(adjacentIndex = 1, gapMicros = 2_000L, gapMultiple = 2)), evaluation.presentationDroppedHoles)
        assertEquals(evaluation.presentationDroppedHoles, evaluation.sensorDroppedHoles)
    }

    @Test
    fun uniformPtsOverMappedSensorHoleRejectsAsSynthetic() {
        val agreement = validateTimestampAnchorHoleAgreement(
            matches = matches(
                presentationTimeMicros = listOf(0L, 1_000L, 2_000L, 3_000L),
                sensorTimeMicros = listOf(1_000_000L, 1_001_000L, 1_003_000L, 1_004_000L),
            ),
            requestedFps = 1_000,
        )

        assertEquals(TimestampAnchorFailure.SYNTHETIC_UNIFORM_PTS, agreement.failure)
        assertEquals(emptyList<TimestampAnchorDroppedHole>(), agreement.presentation.holes)
        assertEquals(listOf(TimestampAnchorDroppedHole(adjacentIndex = 1, gapMicros = 2_000L, gapMultiple = 2)), agreement.sensor.holes)
    }

    @Test
    fun ptsAndSensorHolesAtDifferentPositionsReject() {
        val agreement = validateTimestampAnchorHoleAgreement(
            matches = matches(
                presentationTimeMicros = listOf(0L, 1_000L, 3_000L, 4_000L),
                sensorTimeMicros = listOf(1_000_000L, 1_001_000L, 1_002_000L, 1_004_000L),
            ),
            requestedFps = 1_000,
        )

        assertEquals(TimestampAnchorFailure.DROPPED_HOLE_MISMATCH, agreement.failure)
        assertEquals(listOf(1), agreement.presentation.holes.map { it.adjacentIndex })
        assertEquals(listOf(2), agreement.sensor.holes.map { it.adjacentIndex })
    }

    @Test
    fun samePositionDifferentHoleSizeRejects() {
        val agreement = validateTimestampAnchorHoleAgreement(
            matches = matches(
                presentationTimeMicros = listOf(0L, 1_000L, 3_000L, 4_000L),
                sensorTimeMicros = listOf(1_000_000L, 1_001_000L, 1_004_000L, 1_005_000L),
            ),
            requestedFps = 1_000,
        )

        assertEquals(TimestampAnchorFailure.DROPPED_HOLE_MISMATCH, agreement.failure)
        assertEquals(listOf(2), agreement.presentation.holes.map { it.gapMultiple })
        assertEquals(listOf(3), agreement.sensor.holes.map { it.gapMultiple })
    }

    @Test
    fun droppedHoleClassifierUsesPhaseFiveExpectedGapAndThreshold() {
        val classification = classifyTimestampAnchorDroppedHoles(
            timestampsNanos = listOf(0L, 1_500_000L, 3_001_000L),
            requestedFps = 1_000,
        )

        assertEquals(1.0, classification.expectedGapMillis, 0.0)
        assertEquals(1.5, classification.droppedFrameGapThresholdMillis, 0.0)
        assertEquals(listOf(TimestampAnchorDroppedHole(adjacentIndex = 1, gapMicros = 1_501L, gapMultiple = 2)), classification.holes)
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

    private fun candidate(offsetMicros: Long): TimestampAnchorCandidate =
        TimestampAnchorCandidate(
            offsetMicros = offsetMicros,
            wholeFrameShift = 0,
            provenance = "test",
        )

    private fun match(
        frameIndex: Int,
        sensorIndex: Int,
        presentationTimeMicros: Long = frameIndex * 1_000L,
        sensorTimestampNanos: Long = sensorIndex * 1_000_000L,
    ): TimestampAnchorMatch =
        TimestampAnchorMatch(
            frameIndex = frameIndex,
            presentationTimeMicros = presentationTimeMicros,
            sensorIndex = sensorIndex,
            sensorTimestampNanos = sensorTimestampNanos,
            residualMicros = 0L,
        )

    private fun matches(
        presentationTimeMicros: List<Long>,
        sensorTimeMicros: List<Long>,
    ): List<TimestampAnchorMatch> =
        presentationTimeMicros.zip(sensorTimeMicros).mapIndexed { index, (ptsMicros, sensorMicros) ->
            match(
                frameIndex = index,
                sensorIndex = index,
                presentationTimeMicros = ptsMicros,
                sensorTimestampNanos = sensorMicros * 1_000L,
            )
        }

    private fun ts(offsetMillis: Double): Long =
        1_000_000_000L + (offsetMillis * 1_000_000.0).roundToLong()
}
