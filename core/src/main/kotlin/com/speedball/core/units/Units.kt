package com.speedball.core.units

/** Unit conversion helpers used by the core measurement pipeline. */
object Units {
    const val MILES_PER_HOUR_PER_FOOT_PER_SECOND: Double = 0.6818182
    private const val CENTIMETERS_PER_FOOT: Double = 30.48

    fun feet(value: Double): Double = value

    fun inchesToFeet(inches: Double): Double = inches / 12.0

    fun centimetersToFeet(centimeters: Double): Double = centimeters / CENTIMETERS_PER_FOOT

    fun pixelsPerSecondToFeetPerSecond(
        pixelsPerSecond: Double,
        pixelsPerFoot: Double,
    ): Double? {
        if (!pixelsPerSecond.isFinite() || !pixelsPerFoot.isFinite() || pixelsPerFoot <= 0.0) {
            return null
        }
        return pixelsPerSecond / pixelsPerFoot
    }

    fun feetPerSecondToMilesPerHour(feetPerSecond: Double): Double? {
        if (!feetPerSecond.isFinite()) {
            return null
        }
        return feetPerSecond * MILES_PER_HOUR_PER_FOOT_PER_SECOND
    }
}
