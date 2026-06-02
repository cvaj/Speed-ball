package com.speedball.app.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaRecorder
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.util.Range
import android.view.Surface
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Owns one Camera2 constrained-high-speed burst at a time. */
class HighSpeedBurstRecorder(private val context: Context) {
    private val lock = Any()
    private var state = BurstRecorderState.Idle
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var recorder: MediaRecorder? = null
    private var recorderStarted = false
    private var outputFile: File? = null
    private var timestamps = mutableListOf<Long>()
    private var callbackCount = AtomicInteger(0)
    private var completion: ((BurstOutcome) -> Unit)? = null
    private var options: BurstOptions? = null
    private val terminalGate = TerminalCompletionGate()

    fun currentState(): BurstRecorderState = synchronized(lock) { state }

    @Suppress("MissingPermission")
    fun start(
        options: BurstOptions,
        previewSurface: Surface,
        availableModes: List<HighSpeedMode>,
        onComplete: (BurstOutcome) -> Unit,
    ): BurstOutcome.Failure? {
        synchronized(lock) {
            validateBurstStart(options.mode, availableModes, state)?.let { return it }
            state = BurstRecorderState.Opening
            this.options = options.copy(durationMillis = clampBurstDurationMillis(options.durationMillis))
            completion = onComplete
            timestamps = mutableListOf()
            callbackCount = AtomicInteger(0)
            recorderStarted = false
            terminalGate.reset()
        }

        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val failure = BurstOutcome.Failure(BurstFailure.CAMERA_PERMISSION_DENIED, "Camera permission is required.")
            finishSynchronously(stopRecorder = false)
            return failure
        }

        val cameraId = HighSpeedCamera(context).findBackCameraId()
        if (cameraId == null) {
            val failure = BurstOutcome.Failure(BurstFailure.NO_BACK_CAMERA, "No back camera is available.")
            finishSynchronously(stopRecorder = false)
            return failure
        }

        val thread = HandlerThread("speed-ball-high-speed").also { it.start() }
        val backgroundHandler = Handler(thread.looper)
        handlerThread = thread
        handler = backgroundHandler

        val preparedRecorder = createRecorder(options.mode)
        if (preparedRecorder == null) {
            val failure = BurstOutcome.Failure(BurstFailure.RECORDER_PREPARE_FAILED, "Unable to prepare MediaRecorder.")
            finishSynchronously(stopRecorder = false)
            return failure
        }
        recorder = preparedRecorder
        val recorderSurface = preparedRecorder.surface

        val manager = context.getSystemService(CameraManager::class.java)
        if (manager == null) {
            val failure = BurstOutcome.Failure(BurstFailure.CAMERA_OPEN_FAILED, "Camera service is unavailable.")
            finishSynchronously(stopRecorder = false)
            return failure
        }

        try {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        configureSession(device, previewSurface, recorderSurface, options.mode, manager, cameraId)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        cameraDevice = device
                        complete(
                            BurstOutcome.Failure(BurstFailure.CAMERA_DEVICE_DISCONNECTED, "Camera device disconnected."),
                            stopRecorder = false,
                        )
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        cameraDevice = device
                        complete(
                            BurstOutcome.Failure(BurstFailure.CAMERA_DEVICE_ERROR, "Camera device error code $error."),
                            stopRecorder = false,
                        )
                    }
                },
                backgroundHandler,
            )
        } catch (exception: RuntimeException) {
            val failure = BurstOutcome.Failure(BurstFailure.CAMERA_OPEN_FAILED, "Camera open failed: ${exception.message ?: exception.javaClass.simpleName}.")
            finishSynchronously(stopRecorder = false)
            return failure
        }

        return null
    }

    fun stopActive() {
        if (currentState() == BurstRecorderState.Idle) return
        complete(null, stopRecorder = true)
    }

    private fun configureSession(
        device: CameraDevice,
        previewSurface: Surface,
        recorderSurface: Surface,
        mode: HighSpeedMode,
        manager: CameraManager,
        cameraId: String,
    ) {
        val backgroundHandler = handler ?: return
        synchronized(lock) { state = BurstRecorderState.Configuring }
        try {
            device.createConstrainedHighSpeedCaptureSession(
                listOf(previewSurface, recorderSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        val highSpeedSession = cameraCaptureSession as CameraConstrainedHighSpeedCaptureSession
                        session = highSpeedSession
                        startRepeatingBurst(device, highSpeedSession, previewSurface, recorderSurface, mode, manager, cameraId)
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        complete(
                            BurstOutcome.Failure(BurstFailure.SESSION_CONFIGURATION_FAILED, "High-speed capture session configuration failed."),
                            stopRecorder = false,
                        )
                    }
                },
                backgroundHandler,
            )
        } catch (exception: RuntimeException) {
            complete(
                BurstOutcome.Failure(BurstFailure.SESSION_CONFIGURATION_FAILED, "High-speed session creation failed: ${exception.message ?: exception.javaClass.simpleName}."),
                stopRecorder = false,
            )
        }
    }

    private fun startRepeatingBurst(
        device: CameraDevice,
        highSpeedSession: CameraConstrainedHighSpeedCaptureSession,
        previewSurface: Surface,
        recorderSurface: Surface,
        mode: HighSpeedMode,
        manager: CameraManager,
        cameraId: String,
    ) {
        val backgroundHandler = handler ?: return
        try {
            val baseRequest = buildRecordingRequest(device, previewSurface, recorderSurface, mode, exposureTimeNanos = null)
            val preferredExposureNanos = options?.preferredExposureTimeNanos
            val manualRequest = resolveManualExposureTimeNanos(manager, cameraId, preferredExposureNanos)
                ?.let { exposureNanos ->
                    buildRecordingRequest(device, previewSurface, recorderSurface, mode, exposureTimeNanos = exposureNanos)
                }
            val burst = createHighSpeedRequestListWithFallback(highSpeedSession, manualRequest, baseRequest)
            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    callbackCount.incrementAndGet()
                    result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { timestamp ->
                        synchronized(lock) { timestamps.add(timestamp) }
                    }
                }
            }
            highSpeedSession.setRepeatingBurst(burst, captureCallback, backgroundHandler)
            recorder?.start()
            recorderStarted = true
            synchronized(lock) { state = BurstRecorderState.Recording }
            backgroundHandler.postDelayed({ complete(null, stopRecorder = true) }, options?.durationMillis ?: DEFAULT_BURST_DURATION_MILLIS)
        } catch (exception: RuntimeException) {
            complete(
                BurstOutcome.Failure(BurstFailure.RECORDING_FAILED, "High-speed recording failed: ${exception.message ?: exception.javaClass.simpleName}."),
                stopRecorder = recorderStarted,
            )
        }
    }

    private fun buildRecordingRequest(
        device: CameraDevice,
        previewSurface: Surface,
        recorderSurface: Surface,
        mode: HighSpeedMode,
        exposureTimeNanos: Long?,
    ): CaptureRequest =
        device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
            addTarget(previewSurface)
            addTarget(recorderSurface)
            set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.aeTargetFpsLower, mode.aeTargetFpsUpper))
            if (exposureTimeNanos != null) {
                set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposureTimeNanos)
            }
        }.build()

    private fun createHighSpeedRequestListWithFallback(
        highSpeedSession: CameraConstrainedHighSpeedCaptureSession,
        manualRequest: CaptureRequest?,
        fallbackRequest: CaptureRequest,
    ): List<CaptureRequest> =
        if (manualRequest == null) {
            highSpeedSession.createHighSpeedRequestList(fallbackRequest)
        } else {
            try {
                highSpeedSession.createHighSpeedRequestList(manualRequest)
            } catch (_: RuntimeException) {
                highSpeedSession.createHighSpeedRequestList(fallbackRequest)
            }
        }

    private fun resolveManualExposureTimeNanos(
        manager: CameraManager,
        cameraId: String,
        preferredExposureNanos: Long?,
    ): Long? {
        val requested = preferredExposureNanos ?: return null
        if (!requested.isFinitePositiveExposure()) return null
        val characteristics = try {
            manager.getCameraCharacteristics(cameraId)
        } catch (_: RuntimeException) {
            return null
        }
        val capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)
            ?: return null
        if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR !in capabilities) {
            return null
        }
        val exposureRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            ?: return null
        return exposureRange.clamp(requested)
    }

    private fun createRecorder(mode: HighSpeedMode): MediaRecorder? {
        val file = File(
            context.getExternalFilesDir(Environment.DIRECTORY_MOVIES),
            "speed_ball_${mode.width}x${mode.height}_${mode.fps}_${System.currentTimeMillis()}.mp4",
        )
        outputFile = file
        @Suppress("DEPRECATION")
        val mediaRecorder = MediaRecorder()
        return try {
            mediaRecorder.apply {
                setVideoSource(MediaRecorder.VideoSource.SURFACE)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setOutputFile(file.absolutePath)
                setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                setVideoSize(mode.width, mode.height)
                setVideoFrameRate(mode.fps)
                setCaptureRate(mode.fps.toDouble())
                setVideoEncodingBitRate(if (mode.width >= 1920) 40_000_000 else 24_000_000)
                prepare()
            }
        } catch (_: Exception) {
            runCatching { mediaRecorder.release() }
            null
        }
    }

    private fun finishSynchronously(stopRecorder: Boolean) {
        synchronized(lock) {
            terminalGate.claim()
            state = BurstRecorderState.Releasing
            completion = null
        }
        releaseResources(stopRecorder)
        synchronized(lock) {
            state = BurstRecorderState.Idle
            options = null
        }
    }

    private fun complete(primaryOutcome: BurstOutcome?, stopRecorder: Boolean) {
        val callbackToRun: ((BurstOutcome) -> Unit)?
        val outcome: BurstOutcome
        synchronized(lock) {
            if (!terminalGate.claim()) return
            state = BurstRecorderState.Releasing
            callbackToRun = completion
            completion = null
        }

        val releaseFailure = releaseResources(stopRecorder)
        outcome = when (primaryOutcome) {
            is BurstOutcome.Failure -> {
                val reason = resolveTerminalFailure(primaryOutcome.reason, releaseFailure) ?: primaryOutcome.reason
                primaryOutcome.copy(reason = reason)
            }
            is BurstOutcome.Success -> primaryOutcome
            null -> buildFinalOutcome(releaseFailure)
        }

        synchronized(lock) {
            state = BurstRecorderState.Idle
            options = null
        }
        callbackToRun?.invoke(outcome)
    }

    private fun buildFinalOutcome(releaseFailure: BurstFailure?): BurstOutcome {
        if (releaseFailure != null) {
            return BurstOutcome.Failure(releaseFailure, "Capture stopped, but a resource release step failed.")
        }
        val safeOptions = options ?: return BurstOutcome.Failure(BurstFailure.RECORDING_FAILED, "Capture options were lost before completion.")
        val output = outputFile
        val timestampCopy = synchronized(lock) { timestamps.toList() }
        val outcome = buildBurstOutcome(
            timestampsNanos = timestampCopy,
            callbackCount = callbackCount.get(),
            requestedDurationMillis = safeOptions.durationMillis,
            fps = safeOptions.mode.fps,
            outputPath = output?.absolutePath.orEmpty(),
            fileBytes = output?.length() ?: 0L,
        )
        return if (outcome is BurstOutcome.Success) {
            outcome.copy(
                outputFile = output,
                sensorTimestampsNanos = timestampCopy,
                requestedFps = safeOptions.mode.fps,
                requestedDurationMillis = safeOptions.durationMillis,
                width = safeOptions.mode.width,
                height = safeOptions.mode.height,
            )
        } else {
            outcome
        }
    }

    private fun releaseResources(stopRecorder: Boolean): BurstFailure? {
        var releaseFailed = false
        runCatching { session?.stopRepeating() }.onFailure { releaseFailed = true }
        if (stopRecorder && recorderStarted) {
            runCatching { recorder?.stop() }.onFailure { releaseFailed = true }
        }
        runCatching {
            recorder?.reset()
            recorder?.release()
        }.onFailure { releaseFailed = true }
        runCatching { session?.close() }.onFailure { releaseFailed = true }
        runCatching { cameraDevice?.close() }.onFailure { releaseFailed = true }
        runCatching { handlerThread?.quitSafely() }.onFailure { releaseFailed = true }
        recorder = null
        session = null
        cameraDevice = null
        handler = null
        handlerThread = null
        recorderStarted = false
        return if (releaseFailed) BurstFailure.RESOURCE_RELEASE_FAILED else null
    }
}

private fun Long.isFinitePositiveExposure(): Boolean = this > 0L
