package com.speedball.app.measurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ColorWorkflowStateTest {
    @Test
    fun hueWraparoundAndToleranceClampingPreserveThresholdBehavior() {
        val state = ColorWorkflowState().selectSample(
            sample = HsvColor(hueDegrees = 359.0, saturation = 0.9, value = 0.9),
            tolerance = HsvTolerance(hueDegrees = 5.0, saturation = 2.0, value = -1.0),
        )

        val readiness = assertInstanceOf(ColorWorkflowReadiness.Ready::class.java, state.readiness(10, 10))

        assertEquals(HsvTolerance(hueDegrees = 5.0, saturation = 1.0, value = 0.0), readiness.threshold.tolerance)
        assertTrue(readiness.threshold.matches(HsvColor(hueDegrees = 1.0, saturation = 0.9, value = 0.9)))
        assertFalse(readiness.threshold.matches(HsvColor(hueDegrees = 8.0, saturation = 0.9, value = 0.9)))
    }

    @Test
    fun roiClipsToFrameBounds() {
        val state = ColorWorkflowState().selectSample(
            sample = HsvColor(hueDegrees = 0.0, saturation = 1.0, value = 1.0),
            regionOfInterest = RegionOfInterest(left = -5, top = 2, rightExclusive = 12, bottomExclusive = 20),
        )

        val readiness = assertInstanceOf(ColorWorkflowReadiness.Ready::class.java, state.readiness(10, 8))

        assertEquals(RegionOfInterest(left = 0, top = 2, rightExclusive = 10, bottomExclusive = 8), readiness.regionOfInterest)
        assertNull(state.noReadOrNull(10, 8))
    }

    @Test
    fun emptyOrInvalidSampleCannotBecomeReady() {
        val missing = ColorWorkflowState()
        val nonFinite = ColorWorkflowState().selectSample(HsvColor(Double.NaN, 1.0, 1.0))
        val invalidSaturation = ColorWorkflowState().selectSample(HsvColor(0.0, 1.5, 1.0))
        val invalidValue = ColorWorkflowState().selectSample(HsvColor(0.0, 1.0, -0.1))

        listOf(missing, nonFinite, invalidSaturation, invalidValue).forEach { state ->
            assertInstanceOf(ColorWorkflowReadiness.NotReady::class.java, state.readiness(10, 10))
            assertNoRead(state)
        }
    }

    @Test
    fun invalidRoiCannotBecomeReady() {
        val state = ColorWorkflowState().selectSample(
            sample = HsvColor(hueDegrees = 0.0, saturation = 1.0, value = 1.0),
            regionOfInterest = RegionOfInterest(left = 20, top = 20, rightExclusive = 30, bottomExclusive = 30),
        )

        assertInstanceOf(ColorWorkflowReadiness.NotReady::class.java, state.readiness(10, 10))
        assertNoRead(state)
    }

    @Test
    fun clearingSampleRemovesDetectorEligibility() {
        val ready = ColorWorkflowState().selectSample(HsvColor(0.0, 1.0, 1.0))
        val cleared = ready.clear()

        assertInstanceOf(ColorWorkflowReadiness.Ready::class.java, ready.readiness(10, 10))
        assertInstanceOf(ColorWorkflowReadiness.NotReady::class.java, cleared.readiness(10, 10))
        assertEquals(ready.revision + 1L, cleared.revision)
        assertNoRead(cleared)
    }

    @Test
    fun frameDimensionsAreRequiredBeforeDetectorUse() {
        val state = ColorWorkflowState().selectSample(HsvColor(0.0, 1.0, 1.0))

        assertInstanceOf(ColorWorkflowReadiness.NotReady::class.java, state.readiness(0, 10))
        assertNoRead(state, frameWidth = 0, frameHeight = 10)
    }

    private fun assertNoRead(
        state: ColorWorkflowState,
        frameWidth: Int = 10,
        frameHeight: Int = 10,
    ) {
        val noRead = assertInstanceOf(MeasurementRunOutcome.NoRead::class.java, state.noReadOrNull(frameWidth, frameHeight))

        assertEquals(MeasurementRunFailure.DETECTION_FAILED, noRead.reason)
    }
}
