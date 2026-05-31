package com.speedball.core.model

/**
 * Ball-center detection in raw image coordinates at a measured timestamp.
 *
 * `timestampSeconds` is measured in seconds and must come from the frame timing
 * source for the capture path. `xPx` and `yPx` are raw image pixels with image
 * `y` increasing downward. Public measurement APIs validate that all values are
 * finite and that timestamps are strictly increasing.
 */
data class Detection(
    val timestampSeconds: Double,
    val xPx: Double,
    val yPx: Double,
)
