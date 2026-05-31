package com.speedball.core.velocity

import com.speedball.core.model.Detection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LeaveOneOutTest {
    @Test
    fun removesObviousOutlierAndKeepsOriginalIndices() {
        val detections = listOf(
            Detection(0.0, 0.0, 0.0),
            Detection(1.0, 10.0, 5.0),
            Detection(2.0, 200.0, 200.0),
            Detection(3.0, 30.0, 15.0),
            Detection(4.0, 40.0, 20.0),
        )
        val fit = assertSuccess(
            VelocityFit.fit(
                detections,
                VelocityFitOptions(
                    minTimeSpreadSecondsSquared = 1e-9,
                    maxOutlierPasses = 1,
                    minOutlierRmsImprovementPx = 1.0,
                ),
            ),
        )

        assertEquals(listOf(0, 1, 3, 4), fit.usedOriginalIndices)
        assertEquals(10.0, fit.vxPxPerSecond, 1e-9)
        assertEquals(5.0, fit.vyPxPerSecond, 1e-9)
        assertEquals(0.0, fit.rmsResidualPx, 1e-9)
    }

    @Test
    fun doesNotRemoveWhenImprovementIsBelowThreshold() {
        val detections = cleanLine()
        val fit = assertSuccess(
            VelocityFit.fit(
                detections,
                VelocityFitOptions(
                    minTimeSpreadSecondsSquared = 1e-9,
                    maxOutlierPasses = 1,
                    minOutlierRmsImprovementPx = 0.1,
                ),
            ),
        )

        assertEquals(listOf(0, 1, 2, 3), fit.usedOriginalIndices)
    }

    @Test
    fun tieBreakRemovesLowestOriginalIndex() {
        val fit = assertSuccess(
            VelocityFit.fit(
                cleanLine(),
                VelocityFitOptions(
                    minTimeSpreadSecondsSquared = 1e-9,
                    maxOutlierPasses = 1,
                    minOutlierRmsImprovementPx = 0.0,
                ),
            ),
        )

        assertEquals(listOf(1, 2, 3), fit.usedOriginalIndices)
    }

    @Test
    fun neverLeavesFewerThanThreePoints() {
        val fit = assertSuccess(
            VelocityFit.fit(
                cleanLine(),
                VelocityFitOptions(
                    minTimeSpreadSecondsSquared = 1e-9,
                    maxOutlierPasses = 10,
                    minOutlierRmsImprovementPx = 0.0,
                ),
            ),
        )

        assertEquals(3, fit.usedOriginalIndices.size)
    }

    private fun cleanLine(): List<Detection> =
        listOf(
            Detection(0.0, 0.0, 0.0),
            Detection(1.0, 10.0, 5.0),
            Detection(2.0, 20.0, 10.0),
            Detection(3.0, 30.0, 15.0),
        )
}
