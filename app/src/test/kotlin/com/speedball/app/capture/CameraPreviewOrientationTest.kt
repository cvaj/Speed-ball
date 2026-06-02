package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class CameraPreviewOrientationTest {
    @Test
    fun liveTextureViewRotationUsesDisplayRotationForLandscapeSurfaceTransform() {
        assertEquals(
            90,
            backCameraTextureViewRotationDegrees(
                sensorOrientationDegrees = 90,
                displayRotationDegrees = 90,
            ),
        )
        assertEquals(
            0,
            backCameraTextureViewRotationDegrees(
                sensorOrientationDegrees = 90,
                displayRotationDegrees = 0,
            ),
        )
        assertEquals(
            180,
            backCameraTextureViewRotationDegrees(
                sensorOrientationDegrees = 90,
                displayRotationDegrees = 180,
            ),
        )
        assertEquals(
            270,
            backCameraTextureViewRotationDegrees(
                sensorOrientationDegrees = 90,
                displayRotationDegrees = 270,
            ),
        )
    }
}
