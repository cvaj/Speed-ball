package com.speedball.core.units

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class UnitsTest {
    @Test
    fun convertsLengthsToFeet() {
        assertEquals(5.0, Units.feet(5.0), 1e-12)
        assertEquals(2.0, Units.inchesToFeet(24.0), 1e-12)
        assertEquals(3.0, Units.centimetersToFeet(91.44), 1e-12)
    }

    @Test
    fun convertsPixelSpeedToMilesPerHour() {
        val feetPerSecond = Units.pixelsPerSecondToFeetPerSecond(
            pixelsPerSecond = 120.0,
            pixelsPerFoot = 30.0,
        )
        val milesPerHour = Units.feetPerSecondToMilesPerHour(requireNotNull(feetPerSecond))

        assertEquals(4.0, feetPerSecond, 1e-12)
        assertEquals(2.7272728, requireNotNull(milesPerHour), 1e-12)
    }

    @Test
    fun invalidScaleDoesNotReturnSpeed() {
        assertNull(Units.pixelsPerSecondToFeetPerSecond(100.0, 0.0))
        assertNull(Units.pixelsPerSecondToFeetPerSecond(100.0, Double.NaN))
        assertNull(Units.feetPerSecondToMilesPerHour(Double.POSITIVE_INFINITY))
    }
}
