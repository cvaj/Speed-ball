package com.speedball.app.importing

import com.speedball.app.measurement.VisualEstimateConfidence
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportTimingReconcilerTest {
    @Test
    fun monotonicPresentationTimestampsProduceEstimateBasisOnly() {
        val result = assertSuccess(
            ImportTimingReconciler.reconcile(
                listOf(
                    sample(0, x = 0.0, pts = 0L),
                    sample(1, x = 3.0, pts = 10_000_000L),
                    sample(2, x = 6.0, pts = 20_000_000L),
                ),
            ),
        )

        assertEquals(ImportTimingBasis.CONTAINER_PRESENTATION_TIMESTAMPS, result.basis)
        assertEquals(listOf(0.0, 0.01, 0.02), result.timestampsSeconds)
        assertEquals(listOf(1, 1), result.frameStepMultipliers)
        assertEquals(0.01, result.timestampGapSummary.medianGapSeconds, 1.0e-12)
        assertEquals(VisualEstimateConfidence.LOW, result.confidence)
        assertTrue(result.assumptions.any { it.contains("re-encode") })
        assertTrue(result.assumptions.any { it.contains("VFR") })
    }

    @Test
    fun partialTimestampsAndCentroidDeltaInferSkippedFrameGap() {
        val result = assertSuccess(
            ImportTimingReconciler.reconcile(
                listOf(
                    sample(0, x = 0.0, pts = 0L),
                    sample(1, x = 3.0, pts = 10_000_000L),
                    sample(2, x = 18.0, pts = null),
                    sample(3, x = 21.2, pts = null),
                ),
            ),
        )

        assertEquals(ImportTimingBasis.PARTIAL_TIMESTAMP_VISUAL_GAP_RECONCILIATION, result.basis)
        assertEquals(0.0, result.timestampsSeconds[0], 1.0e-12)
        assertEquals(0.01, result.timestampsSeconds[1], 1.0e-12)
        assertEquals(0.06, result.timestampsSeconds[2], 1.0e-12)
        assertEquals(0.07, result.timestampsSeconds[3], 1.0e-12)
        assertEquals(listOf(1, 5, 1), result.frameStepMultipliers)
        assertEquals(0.05, result.timestampGapSummary.maxGapSeconds, 1.0e-12)
        assertTrue(result.assumptions.any { it.contains("re-encode") })
        assertTrue(result.assumptions.any { it.contains("constant in-plane velocity") })
    }

    @Test
    fun knownIntervalVisualDeltaInferenceWorksAndUserDeclaredIsLowConfidence() {
        val result = assertSuccess(
            ImportTimingReconciler.reconcile(
                samples = listOf(
                    sample(0, x = 0.0),
                    sample(1, x = 2.0),
                    sample(2, x = 6.0),
                    sample(3, x = 8.0),
                ),
                config = ImportTimingReconcilerConfig(
                    knownFrameIntervalSeconds = 0.01,
                    intervalIsUserDeclared = true,
                ),
            ),
        )

        assertEquals(ImportTimingBasis.VISUAL_FRAME_DELTA_INFERENCE, result.basis)
        assertEquals(listOf(1, 2, 1), result.frameStepMultipliers)
        assertEquals(VisualEstimateConfidence.LOW, result.confidence)
        assertTrue(result.assumptions.any { it.contains("user-declared frame interval") })
        assertTrue(result.assumptions.any { it.contains("uniform frame loss") })
    }

    @Test
    fun knownFrameIntervalBuildsDecodeOrderEstimateTiming() {
        val result = assertSuccess(
            ImportTimingReconciler.reconcileKnownFrameInterval(
                frameCount = 4,
                frameIntervalSeconds = 1.0 / 120.0,
                intervalIsUserDeclared = true,
            ),
        )

        assertEquals(ImportTimingBasis.VISUAL_FRAME_DELTA_INFERENCE, result.basis)
        assertEquals(listOf(1, 1, 1), result.frameStepMultipliers)
        assertEquals(1.0 / 120.0, result.timestampGapSummary.medianGapSeconds, 1.0e-12)
        assertEquals(VisualEstimateConfidence.LOW, result.confidence)
        assertTrue(result.assumptions.any { it.contains("user-declared frame interval") })
    }

    @Test
    fun recordedCaptureFrameIntervalUsesRecordedBasisAndFrameDropCaveat() {
        val result = assertSuccess(
            ImportTimingReconciler.reconcileRecordedCaptureFrameInterval(
                frameCount = 4,
                frameIntervalSeconds = 1.0 / 120.0,
            ),
        )

        assertEquals(ImportTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL, result.basis)
        assertEquals(listOf(1, 1, 1), result.frameStepMultipliers)
        assertEquals(1.0 / 120.0, result.timestampGapSummary.medianGapSeconds, 1.0e-12)
        assertEquals(VisualEstimateConfidence.LOW, result.confidence)
        assertTrue(result.assumptions.any { it.contains("MediaRecorder") })
        assertTrue(result.assumptions.any { it.contains("frame drop/coalescing") })
    }

    @Test
    fun invalidKnownFrameIntervalNoReads() {
        assertNoRead(
            ImportTimingReconciler.reconcileKnownFrameInterval(
                frameCount = 1,
                frameIntervalSeconds = 1.0 / 120.0,
            ),
        )
        assertNoRead(
            ImportTimingReconciler.reconcileKnownFrameInterval(
                frameCount = 4,
                frameIntervalSeconds = 0.0,
            ),
        )
    }

    @Test
    fun unknownIntervalNoReads() {
        assertNoRead(
            ImportTimingReconciler.reconcile(
                listOf(sample(0, x = 0.0), sample(1, x = 2.0), sample(2, x = 4.0)),
            ),
        )
    }

    @Test
    fun ambiguousDirectionDepthChangeAndHighResidualNoRead() {
        assertNoRead(
            ImportTimingReconciler.reconcile(
                listOf(sample(0, x = 0.0), sample(1, x = 3.0), sample(2, x = 1.0)),
                ImportTimingReconcilerConfig(knownFrameIntervalSeconds = 0.01),
            ),
        )
        assertNoRead(
            ImportTimingReconciler.reconcile(
                listOf(sample(0, x = 0.0, diameter = 10.0), sample(1, x = 3.0, diameter = 16.0)),
                ImportTimingReconcilerConfig(knownFrameIntervalSeconds = 0.01),
            ),
        )
        assertNoRead(
            ImportTimingReconciler.reconcile(
                listOf(sample(0, x = 0.0), sample(1, x = 3.0), sample(2, x = 7.4), sample(3, x = 10.4)),
                ImportTimingReconcilerConfig(knownFrameIntervalSeconds = 0.01),
            ),
        )
    }

    @Test
    fun deceleratingTrackFixtureToleratesMildDecelAndNoReadsStrongDecel() {
        val mild = assertSuccess(
            ImportTimingReconciler.reconcile(
                listOf(sample(0, x = 0.0), sample(1, x = 4.0), sample(2, x = 7.7), sample(3, x = 11.1)),
                ImportTimingReconcilerConfig(knownFrameIntervalSeconds = 0.01),
            ),
        )

        assertTrue(mild.assumptions.any { it.contains("constant in-plane velocity") })
        assertNoRead(
            ImportTimingReconciler.reconcile(
                listOf(sample(0, x = 0.0), sample(1, x = 4.0), sample(2, x = 7.0), sample(3, x = 9.0)),
                ImportTimingReconcilerConfig(knownFrameIntervalSeconds = 0.01),
            ),
        )
    }

    private fun assertSuccess(
        result: ImportValidationResult<ImportTimingReconciliation>,
    ): ImportTimingReconciliation =
        assertInstanceOf(ImportValidationResult.Success::class.java, result).let {
            assertInstanceOf(ImportTimingReconciliation::class.java, it.value)
        }

    private fun assertNoRead(result: ImportValidationResult<ImportTimingReconciliation>) {
        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertEquals(ImportNoReadReason.NO_TRUSTWORTHY_TIMING, noRead.reason)
    }

    private fun sample(
        frameIndex: Int,
        x: Double,
        y: Double = 0.0,
        diameter: Double = 10.0,
        pts: Long? = null,
    ): ImportTrackSample =
        ImportTrackSample(
            frameIndex = frameIndex,
            centroidX = x,
            centroidY = y,
            apparentDiameterPx = diameter,
            presentationTimestampNanos = pts,
        )
}
