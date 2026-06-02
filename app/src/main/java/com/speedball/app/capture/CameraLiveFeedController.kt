package com.speedball.app.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface

/** Drives the always-visible Camera2 feed used for field calibration before a recording owns the camera. */
class CameraLiveFeedController(private val context: Context) {
    private val lock = Any()
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var activeSurface: Surface? = null
    private var activeMode: HighSpeedMode? = null
    private var active = false

    @Suppress("MissingPermission")
    fun start(
        surface: Surface,
        mode: HighSpeedMode,
        onFailure: (String) -> Unit,
    ) {
        synchronized(lock) {
            if (active && activeSurface == surface && activeMode == mode) return
        }
        stop()
        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            onFailure("Camera permission is required for the live feed.")
            return
        }
        val manager = context.getSystemService(CameraManager::class.java)
        if (manager == null) {
            onFailure("Camera service is unavailable for the live feed.")
            return
        }
        val cameraId = HighSpeedCamera(context).findBackCameraId()
        if (cameraId == null) {
            onFailure("No back camera is available for the live feed.")
            return
        }
        val thread = HandlerThread("speed-ball-live-feed").also { it.start() }
        val backgroundHandler = Handler(thread.looper)
        synchronized(lock) {
            handlerThread = thread
            handler = backgroundHandler
            activeSurface = surface
            activeMode = mode
            active = true
        }
        try {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        synchronized(lock) {
                            if (!active) {
                                device.close()
                                return
                            }
                            cameraDevice = device
                        }
                        configureLiveFeed(device, surface, mode, backgroundHandler, onFailure)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        onFailure("Live camera feed disconnected.")
                        stop()
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        onFailure("Live camera feed error code $error.")
                        stop()
                    }
                },
                backgroundHandler,
            )
        } catch (exception: RuntimeException) {
            onFailure("Live camera feed failed to open: ${exception.message ?: exception.javaClass.simpleName}.")
            stop()
        }
    }

    fun stop() {
        val sessionToClose: CameraCaptureSession?
        val deviceToClose: CameraDevice?
        val threadToQuit: HandlerThread?
        synchronized(lock) {
            active = false
            activeSurface = null
            activeMode = null
            sessionToClose = session
            deviceToClose = cameraDevice
            threadToQuit = handlerThread
            session = null
            cameraDevice = null
            handler = null
            handlerThread = null
        }
        runCatching { sessionToClose?.stopRepeating() }
        runCatching { sessionToClose?.abortCaptures() }
        runCatching { sessionToClose?.close() }
        runCatching { deviceToClose?.close() }
        threadToQuit?.quitSafely()
    }

    private fun configureLiveFeed(
        device: CameraDevice,
        surface: Surface,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
        onFailure: (String) -> Unit,
    ) {
        try {
            device.createCaptureSession(
                listOf(surface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        synchronized(lock) {
                            if (!active) {
                                cameraCaptureSession.close()
                                return
                            }
                            session = cameraCaptureSession
                        }
                        startRepeatingLiveFeed(device, cameraCaptureSession, surface, mode, backgroundHandler, onFailure)
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        onFailure("Live camera feed configuration failed.")
                        stop()
                    }
                },
                backgroundHandler,
            )
        } catch (exception: RuntimeException) {
            onFailure("Live camera feed configuration failed: ${exception.message ?: exception.javaClass.simpleName}.")
            stop()
        }
    }

    private fun startRepeatingLiveFeed(
        device: CameraDevice,
        cameraCaptureSession: CameraCaptureSession,
        surface: Surface,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
        onFailure: (String) -> Unit,
    ) {
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, android.util.Range(mode.aeTargetFpsLower, mode.aeTargetFpsUpper))
            }.build()
            cameraCaptureSession.setRepeatingRequest(request, null, backgroundHandler)
        } catch (exception: RuntimeException) {
            onFailure("Live camera feed failed: ${exception.message ?: exception.javaClass.simpleName}.")
            stop()
        }
    }
}
