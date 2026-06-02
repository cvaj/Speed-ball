package com.speedball.app.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Range
import android.view.Surface
import com.speedball.app.measurement.MeasurementCalibrationState
import com.speedball.app.measurement.RgbFrame
import com.speedball.app.measurement.TimedFrameSequence
import com.speedball.app.measurement.VisualEstimateFramePipeline
import com.speedball.app.measurement.VisualEstimateFramePipelineConfig
import com.speedball.app.measurement.VisualEstimateOutcome
import java.util.concurrent.atomic.AtomicInteger

/** Configuration for a bounded direct live-frame visual estimate capture. */
data class DirectVisualEstimateCaptureConfig(
    val mode: HighSpeedMode,
    val calibration: MeasurementCalibrationState,
    val framePipelineConfig: VisualEstimateFramePipelineConfig,
    val durationMillis: Long = DEFAULT_DIRECT_VISUAL_ESTIMATE_DURATION_MILLIS,
    val readbackWidth: Int = DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH,
    val readbackHeight: Int = DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT,
    val maxFrames: Int = DEFAULT_DIRECT_VISUAL_ESTIMATE_MAX_FRAMES,
)

/** Terminal direct visual-estimate capture result. */
sealed interface DirectVisualEstimateCaptureOutcome {
    data class Completed(
        val estimateOutcome: VisualEstimateOutcome,
        val capturedFrameCount: Int,
        val frameAvailableCallbackCount: Int,
        val captureResultCallbackCount: Int,
        val readbackWidth: Int,
        val readbackHeight: Int,
    ) : DirectVisualEstimateCaptureOutcome

    data class Failure(
        val reason: DirectTimingSourceFailure,
        val message: String,
        val capturedFrameCount: Int = 0,
    ) : DirectVisualEstimateCaptureOutcome
}

/**
 * Camera2 owner for the S10+ direct live-frame estimate path.
 *
 * This path never decodes a recorded file and never mints a strict measurement
 * proof token. It consumes bounded app-owned ARGB frames from the live
 * `SurfaceTexture` source and produces only [VisualEstimateOutcome].
 */
class DirectVisualEstimateCapture(private val context: Context) {
    private val lock = Any()
    private val terminalGate = TerminalCompletionGate()
    private var state = BurstRecorderState.Idle
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var glResources: DirectGlReadbackResources? = null
    private var config: DirectVisualEstimateCaptureConfig? = null
    private var completion: ((DirectVisualEstimateCaptureOutcome) -> Unit)? = null
    private var frames = mutableListOf<DirectArgbFrame>()
    private var frameAvailableCallbackCount = AtomicInteger(0)
    private var captureCallbackCount = AtomicInteger(0)

    fun currentState(): BurstRecorderState = synchronized(lock) { state }

    @Suppress("MissingPermission")
    fun start(
        config: DirectVisualEstimateCaptureConfig,
        availableModes: List<HighSpeedMode>,
        onComplete: (DirectVisualEstimateCaptureOutcome) -> Unit,
    ): DirectVisualEstimateCaptureOutcome.Failure? {
        synchronized(lock) {
            validateDirectVisualEstimateStart(config, availableModes, state)?.let { return it }
            state = BurstRecorderState.Opening
            this.config = config.copy(durationMillis = clampBurstDurationMillis(config.durationMillis))
            completion = onComplete
            frames = mutableListOf()
            frameAvailableCallbackCount = AtomicInteger(0)
            captureCallbackCount = AtomicInteger(0)
            terminalGate.reset()
        }

        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val failure = DirectVisualEstimateCaptureOutcome.Failure(
                DirectTimingSourceFailure.CAMERA_OPEN_FAILED,
                "Camera permission is required.",
            )
            finishSynchronously(failure)
            return failure
        }
        val cameraId = HighSpeedCamera(context).findBackCameraId()
        if (cameraId == null) {
            val failure = DirectVisualEstimateCaptureOutcome.Failure(
                DirectTimingSourceFailure.CAMERA_OPEN_FAILED,
                "No back camera is available.",
            )
            finishSynchronously(failure)
            return failure
        }

        val thread = HandlerThread("speed-ball-direct-visual-estimate").also { it.start() }
        val backgroundHandler = Handler(thread.looper)
        handlerThread = thread
        handler = backgroundHandler
        backgroundHandler.post {
            if (!shouldHandleDirectFrame(currentState())) return@post
            val resources = createVisualEstimateGlResources(config, backgroundHandler)
            if (resources == null) {
                complete(
                    DirectVisualEstimateCaptureOutcome.Failure(
                        DirectTimingSourceFailure.PIXEL_READBACK_FAILED,
                        "Unable to create direct visual-estimate GL readback resources.",
                    ),
                )
                return@post
            }
            if (!shouldHandleDirectFrame(currentState())) {
                runCatching { resources.release() }
                return@post
            }
            glResources = resources
            openCamera(cameraId, config.mode, backgroundHandler, resources.surface)
        }
        return null
    }

    fun stopActive() {
        if (currentState() == BurstRecorderState.Idle) return
        complete(
            DirectVisualEstimateCaptureOutcome.Failure(
                DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED,
                "Direct visual estimate capture was cancelled.",
                capturedFrameCount = synchronized(lock) { frames.size },
            ),
        )
    }

    @Suppress("MissingPermission")
    private fun openCamera(
        cameraId: String,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
        surface: Surface,
    ) {
        if (!shouldHandleDirectFrame(currentState())) return
        val manager = context.getSystemService(CameraManager::class.java)
        if (manager == null) {
            complete(DirectVisualEstimateCaptureOutcome.Failure(DirectTimingSourceFailure.CAMERA_OPEN_FAILED, "Camera service is unavailable."))
            return
        }
        try {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        configureSession(device, mode, backgroundHandler, surface)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        cameraDevice = device
                        complete(DirectVisualEstimateCaptureOutcome.Failure(DirectTimingSourceFailure.CAMERA_OPEN_FAILED, "Camera device disconnected."))
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        cameraDevice = device
                        complete(DirectVisualEstimateCaptureOutcome.Failure(DirectTimingSourceFailure.CAMERA_OPEN_FAILED, "Camera device error code $error."))
                    }
                },
                backgroundHandler,
            )
        } catch (exception: Exception) {
            complete(
                DirectVisualEstimateCaptureOutcome.Failure(
                    DirectTimingSourceFailure.CAMERA_OPEN_FAILED,
                    "Camera open failed: ${exception.message ?: exception.javaClass.simpleName}.",
                ),
            )
        }
    }

    private fun configureSession(
        device: CameraDevice,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
        surface: Surface,
    ) {
        synchronized(lock) { state = BurstRecorderState.Configuring }
        try {
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    session = cameraCaptureSession
                    startRepeating(device, cameraCaptureSession, mode, backgroundHandler, surface)
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    complete(
                        DirectVisualEstimateCaptureOutcome.Failure(
                            DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED,
                            "Direct visual estimate session configuration failed.",
                        ),
                    )
                }
            }
            device.createConstrainedHighSpeedCaptureSession(listOf(surface), callback, backgroundHandler)
        } catch (exception: Exception) {
            complete(
                DirectVisualEstimateCaptureOutcome.Failure(
                    DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED,
                    "Direct visual estimate session failed: ${exception.message ?: exception.javaClass.simpleName}.",
                ),
            )
        }
    }

    private fun startRepeating(
        device: CameraDevice,
        cameraCaptureSession: CameraCaptureSession,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
        surface: Surface,
    ) {
        try {
            val highSpeedSession = cameraCaptureSession as CameraConstrainedHighSpeedCaptureSession
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(surface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.aeTargetFpsLower, mode.aeTargetFpsUpper))
            }
            val burst = highSpeedSession.createHighSpeedRequestList(request.build())
            highSpeedSession.setRepeatingBurst(burst, directCaptureCallback(), backgroundHandler)
            synchronized(lock) { state = BurstRecorderState.Recording }
            backgroundHandler.postDelayed({ complete(null) }, config?.durationMillis ?: DEFAULT_DIRECT_VISUAL_ESTIMATE_DURATION_MILLIS)
        } catch (exception: Exception) {
            complete(
                DirectVisualEstimateCaptureOutcome.Failure(
                    DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED,
                    "Direct visual estimate repeating request failed: ${exception.message ?: exception.javaClass.simpleName}.",
                ),
            )
        }
    }

    private fun directCaptureCallback(): CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(
                session: CameraCaptureSession,
                request: CaptureRequest,
                result: android.hardware.camera2.TotalCaptureResult,
            ) {
                captureCallbackCount.incrementAndGet()
            }
        }

    private fun onFrameAvailable() {
        frameAvailableCallbackCount.incrementAndGet()
        val resources = glResources ?: return
        val safeConfig = config ?: return
        if (!shouldHandleDirectFrame(currentState())) return
        runCatching {
            val readFrames = resources.updateAndReadArgbFrames()
            var shouldComplete = false
            synchronized(lock) {
                readFrames.forEach { frame ->
                    if (frames.size < safeConfig.maxFrames) {
                        frames += frame
                    }
                }
                shouldComplete = frames.size >= safeConfig.maxFrames
            }
            if (shouldComplete) complete(null)
        }.onFailure {
            complete(
                DirectVisualEstimateCaptureOutcome.Failure(
                    DirectTimingSourceFailure.PIXEL_READBACK_FAILED,
                    "Direct visual estimate frame readback failed: ${it.message ?: it.javaClass.simpleName}.",
                    capturedFrameCount = synchronized(lock) { frames.size },
                ),
            )
        }
    }

    private fun finishSynchronously(primaryOutcome: DirectVisualEstimateCaptureOutcome.Failure) {
        synchronized(lock) {
            terminalGate.claim()
            state = BurstRecorderState.Releasing
            completion = null
        }
        releaseResources()
        synchronized(lock) {
            state = BurstRecorderState.Idle
            config = null
        }
    }

    private fun complete(primaryOutcome: DirectVisualEstimateCaptureOutcome.Failure?) {
        val releaseHandler: Handler?
        synchronized(lock) {
            if (!terminalGate.claim()) return
            state = BurstRecorderState.Releasing
            releaseHandler = handler
        }
        if (releaseHandler != null && Looper.myLooper() != releaseHandler.looper) {
            releaseHandler.post { finishClaimedCompletion(primaryOutcome) }
        } else {
            finishClaimedCompletion(primaryOutcome)
        }
    }

    private fun finishClaimedCompletion(primaryOutcome: DirectVisualEstimateCaptureOutcome.Failure?) {
        val callbackToRun = synchronized(lock) {
            val callback = completion
            completion = null
            callback
        }
        val releaseResult = releaseResources()
        val outcome = primaryOutcome ?: buildFinalOutcome(releaseResult)
        synchronized(lock) {
            state = BurstRecorderState.Idle
            config = null
        }
        callbackToRun?.invoke(outcome)
    }

    private fun buildFinalOutcome(releaseResult: DirectReleaseResourcesResult): DirectVisualEstimateCaptureOutcome {
        val safeConfig = config
            ?: return DirectVisualEstimateCaptureOutcome.Failure(
                DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED,
                "Direct visual estimate options were lost before completion.",
            )
        val frameCopy = synchronized(lock) { frames.toList() }
        if (releaseResult.failure != null) {
            return DirectVisualEstimateCaptureOutcome.Failure(
                reason = releaseResult.failure,
                message = "Direct visual estimate stopped, but a resource release step failed.",
                capturedFrameCount = frameCopy.size,
            )
        }
        if (frameCopy.isEmpty()) {
            return DirectVisualEstimateCaptureOutcome.Failure(
                reason = DirectTimingSourceFailure.MISSING_DIRECT_TIMESTAMPS,
                message = "No direct visual estimate frames were consumed.",
            )
        }
        val estimateOutcome = VisualEstimateFramePipeline.estimateFromFrames(
            sequence = TimedFrameSequence(frameCopy.map { it.toRgbFrame() }),
            calibration = safeConfig.calibration,
            config = safeConfig.framePipelineConfig,
        )
        return DirectVisualEstimateCaptureOutcome.Completed(
            estimateOutcome = estimateOutcome,
            capturedFrameCount = frameCopy.size,
            frameAvailableCallbackCount = frameAvailableCallbackCount.get(),
            captureResultCallbackCount = captureCallbackCount.get(),
            readbackWidth = safeConfig.readbackWidth,
            readbackHeight = safeConfig.readbackHeight,
        )
    }

    private fun releaseResources(): DirectReleaseResourcesResult =
        releaseDirectCaptureResourcesWithDiagnostics(
            DirectReleaseActions(
                stopRepeating = { session?.stopRepeating() },
                closeSession = { session?.close() },
                closeCamera = { cameraDevice?.close() },
                releaseGl = { glResources?.release() },
                quitThread = { handlerThread?.quitSafely() },
            ),
        )

    private fun createVisualEstimateGlResources(
        config: DirectVisualEstimateCaptureConfig,
        handler: Handler,
    ): DirectGlReadbackResources? =
        runCatching {
            DirectGlReadbackResources.create(
                frameWidth = config.mode.width,
                frameHeight = config.mode.height,
                readbackWidth = config.readbackWidth,
                readbackHeight = config.readbackHeight,
                readbackMode = DirectGlReadbackMode.INLINE_READ_PIXELS,
            ) {
                handler.post { onFrameAvailable() }
            }
        }.getOrNull()
}

private fun DirectArgbFrame.toRgbFrame(): RgbFrame =
    RgbFrame(
        width = width,
        height = height,
        argbPixels = argbPixels,
        timestampSeconds = timestampNanos / 1_000_000_000.0,
    )

internal fun validateDirectVisualEstimateStart(
    config: DirectVisualEstimateCaptureConfig,
    availableModes: List<HighSpeedMode>,
    recorderState: BurstRecorderState,
): DirectVisualEstimateCaptureOutcome.Failure? {
    validateDirectProofStart(config.mode, availableModes, recorderState)?.let {
        return DirectVisualEstimateCaptureOutcome.Failure(it.reason, it.message)
    }
    if (config.readbackWidth <= 0 || config.readbackHeight <= 0 || config.maxFrames <= 0) {
        return DirectVisualEstimateCaptureOutcome.Failure(
            DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED,
            "Direct visual estimate readback dimensions and frame cap must be positive.",
        )
    }
    return null
}

const val DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH: Int = 160
const val DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT: Int = 90
const val DEFAULT_DIRECT_VISUAL_ESTIMATE_MAX_FRAMES: Int = 48
const val DEFAULT_DIRECT_VISUAL_ESTIMATE_DURATION_MILLIS: Long = 800L
