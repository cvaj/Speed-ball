package com.speedball.app.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.params.StreamConfigurationMap

/** Reads Camera2 high-speed HAL modes and converts framework values to app data. */
class HighSpeedCamera(private val context: Context) {
    fun enumerateModes(): HighSpeedModesResult {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return HighSpeedModesResult.Failure(BurstFailure.CAMERA_PERMISSION_DENIED, "Camera permission is required.")
        }

        val manager = context.getSystemService(CameraManager::class.java)
            ?: return HighSpeedModesResult.Failure(BurstFailure.NO_BACK_CAMERA, "Camera service is unavailable.")
        val cameraId = findBackCameraId(manager)
            ?: return HighSpeedModesResult.Failure(BurstFailure.NO_BACK_CAMERA, "No back camera is available.")
        val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }
            .getOrElse {
                return HighSpeedModesResult.Failure(BurstFailure.NO_BACK_CAMERA, "Unable to read back camera characteristics.")
            }
        val map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)
            ?: return HighSpeedModesResult.Failure(BurstFailure.NO_HIGH_SPEED_MODES, "No stream configuration map is available.")
        val rawRanges = map.toRawHighSpeedRanges()
        if (rawRanges.isEmpty()) {
            return HighSpeedModesResult.Failure(BurstFailure.NO_HIGH_SPEED_MODES, "No high-speed video modes are exposed.")
        }
        val modes = mapHighSpeedModes(rawRanges)
        return if (modes.isEmpty()) {
            HighSpeedModesResult.Failure(BurstFailure.NO_HIGH_SPEED_MODES, "No supported 720p/1080p high-speed modes are exposed.")
        } else {
            HighSpeedModesResult.Success(modes)
        }
    }

    fun findBackCameraId(): String? {
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        return findBackCameraId(manager)
    }

    /** Reads back-camera orientation metadata used to correct the live TextureView preview. */
    fun readBackCameraPreviewOrientation(displayRotationDegrees: Int): CameraPreviewOrientation? {
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        val cameraId = findBackCameraId(manager) ?: return null
        val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }.getOrNull() ?: return null
        val sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: return null
        return CameraPreviewOrientation(
            cameraId = cameraId,
            sensorOrientationDegrees = sensorOrientation,
            displayRotationDegrees = displayRotationDegrees,
            textureViewRotationDegrees = backCameraTextureViewRotationDegrees(
                sensorOrientationDegrees = sensorOrientation,
                displayRotationDegrees = displayRotationDegrees,
            ),
        )
    }

    fun readBackCameraTimestampSource(): CameraTimestampSourceReadResult {
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            return CameraTimestampSourceReadResult.Failure(
                BurstFailure.CAMERA_PERMISSION_DENIED,
                "Camera permission is required.",
            )
        }

        val manager = context.getSystemService(CameraManager::class.java)
            ?: return CameraTimestampSourceReadResult.Failure(
                BurstFailure.NO_BACK_CAMERA,
                "Camera service is unavailable.",
            )
        val cameraId = findBackCameraId(manager)
            ?: return CameraTimestampSourceReadResult.Failure(
                BurstFailure.NO_BACK_CAMERA,
                "No back camera is available.",
            )
        val characteristics = runCatching { manager.getCameraCharacteristics(cameraId) }
            .getOrElse {
                return CameraTimestampSourceReadResult.Failure(
                    BurstFailure.NO_BACK_CAMERA,
                    "Unable to read back camera characteristics.",
                )
            }
        val source = characteristics.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        return CameraTimestampSourceReadResult.Success(
            buildCameraTimestampSourceReport(
                cameraRole = "back",
                cameraId = cameraId,
                sourceValue = source,
            ),
        )
    }

    private fun findBackCameraId(manager: CameraManager): String? =
        manager.cameraIdList.firstOrNull { id ->
            val characteristics = manager.getCameraCharacteristics(id)
            characteristics.get(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_BACK
        }

    private fun StreamConfigurationMap.toRawHighSpeedRanges(): List<RawHighSpeedRange> =
        highSpeedVideoSizes.flatMap { size ->
            getHighSpeedVideoFpsRangesFor(size).map { range ->
                RawHighSpeedRange(
                    width = size.width,
                    height = size.height,
                    lowerFps = range.lower,
                    upperFps = range.upper,
                )
            }
        }
}

/** Camera orientation data used to render the live TextureView without sideways or stretched preview frames. */
data class CameraPreviewOrientation(
    val cameraId: String,
    val sensorOrientationDegrees: Int,
    val displayRotationDegrees: Int,
    val textureViewRotationDegrees: Int,
)

private fun Int.normalizedDegrees(): Int =
    ((this % 360) + 360) % 360

internal fun backCameraTextureViewRotationDegrees(
    sensorOrientationDegrees: Int,
    displayRotationDegrees: Int,
): Int =
    displayRotationDegrees.normalizedDegrees()
