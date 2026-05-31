package com.speedball.app.measurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ColorModelTest {
    @Test
    fun convertsKnownArgbColorsToHsv() {
        val red = ColorMath.argbToHsv(0xffff0000.toInt())
        val green = ColorMath.argbToHsv(0xff00ff00.toInt())
        val blue = ColorMath.argbToHsv(0xff0000ff.toInt())

        assertEquals(0.0, red.hueDegrees, 1.0e-9)
        assertEquals(120.0, green.hueDegrees, 1.0e-9)
        assertEquals(240.0, blue.hueDegrees, 1.0e-9)
        assertEquals(1.0, red.saturation, 1.0e-9)
        assertEquals(1.0, red.value, 1.0e-9)
    }

    @Test
    fun hueThresholdWrapsAroundZeroDegrees() {
        val threshold = HsvThreshold(
            center = HsvColor(359.0, 0.9, 0.9),
            tolerance = HsvTolerance(hueDegrees = 3.0, saturation = 0.2, value = 0.2),
        )

        assertTrue(threshold.matches(HsvColor(1.0, 0.9, 0.9)))
        assertFalse(threshold.matches(HsvColor(12.0, 0.9, 0.9)))
    }

    @Test
    fun toleranceAndRoiAreClampedOrRejected() {
        assertEquals(
            HsvTolerance(180.0, 0.0, 1.0),
            HsvTolerance(220.0, -1.0, 2.0).clamped(),
        )
        assertEquals(RegionOfInterest(0, 0, 4, 3), RegionOfInterest(-5, -1, 4, 3).clippedTo(10, 10))
        assertEquals(null, RegionOfInterest(8, 8, 20, 20).clippedTo(4, 4))
    }

    @Test
    fun invalidBoundsFailLoud() {
        val failure = FrameProcessingBounds(maxWidth = 0).validate()

        assertEquals(MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED, failure?.reason)
    }
}
