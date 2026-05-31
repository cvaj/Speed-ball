package com.speedball.core.model

/**
 * Raw image point in pixels.
 *
 * The image coordinate system has `x` increasing rightward and `y` increasing
 * downward. Calibration APIs validate that point values are finite.
 */
data class ImagePoint(
    val xPx: Double,
    val yPx: Double,
)
