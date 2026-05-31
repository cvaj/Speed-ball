package com.speedball.app.decode

/**
 * One decoded video frame paired with the Camera2 SENSOR_TIMESTAMP that remains
 * the measurement timing authority. Container PTS is never used as the
 * measurement timestamp.
 */
data class FrameTimestampPair(
    val frameIndex: Int,
    val sensorTimestampNanos: Long,
    val relativeTimestampSeconds: Double,
)
