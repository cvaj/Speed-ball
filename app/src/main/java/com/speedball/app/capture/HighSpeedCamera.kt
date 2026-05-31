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
