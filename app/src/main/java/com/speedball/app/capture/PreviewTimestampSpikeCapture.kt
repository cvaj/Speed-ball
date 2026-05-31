package com.speedball.app.capture

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.view.Surface
import java.util.concurrent.atomic.AtomicInteger

/** Minimal decoder-free high-speed preview timestamp spike for proving SurfaceTexture timing. */
class PreviewTimestampSpikeCapture(private val context: Context) {
    private val lock = Any()
    private val terminalGate = TerminalCompletionGate()
    private var state = BurstRecorderState.Idle
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var glResources: PreviewGlResources? = null
    private var sensorTimestamps = mutableListOf<Long>()
    private var previewTimestamps = mutableListOf<Long>()
    private var captureCallbackCount = AtomicInteger(0)
    private var frameCallbackCount = AtomicInteger(0)
    private var completion: ((PreviewFrameOutcome) -> Unit)? = null
    private var options: BurstOptions? = null

    fun currentState(): BurstRecorderState = synchronized(lock) { state }

    @Suppress("MissingPermission")
    fun start(
        options: BurstOptions,
        availableModes: List<HighSpeedMode>,
        onComplete: (PreviewFrameOutcome) -> Unit,
    ): PreviewFrameOutcome.Failure? {
        synchronized(lock) {
            validatePreviewSpikeStart(options.mode, availableModes, state)?.let { return it }
            state = BurstRecorderState.Opening
            this.options = options.copy(durationMillis = clampBurstDurationMillis(options.durationMillis))
            completion = onComplete
            sensorTimestamps = mutableListOf()
            previewTimestamps = mutableListOf()
            captureCallbackCount = AtomicInteger(0)
            frameCallbackCount = AtomicInteger(0)
            terminalGate.reset()
        }

        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val failure = PreviewFrameOutcome.Failure(PreviewFrameFailure.CAMERA_PERMISSION_DENIED, "Camera permission is required.")
            finishSynchronously()
            return failure
        }
        val cameraId = HighSpeedCamera(context).findBackCameraId()
        if (cameraId == null) {
            val failure = PreviewFrameOutcome.Failure(PreviewFrameFailure.NO_BACK_CAMERA, "No back camera is available.")
            finishSynchronously()
            return failure
        }

        val thread = HandlerThread("speed-ball-preview-spike").also { it.start() }
        val backgroundHandler = Handler(thread.looper)
        handlerThread = thread
        handler = backgroundHandler
        backgroundHandler.post {
            if (currentState() == BurstRecorderState.Idle || currentState() == BurstRecorderState.Releasing) return@post
            val resources = createPreviewGlResources(options.mode, backgroundHandler)
            if (resources == null) {
                complete(PreviewFrameOutcome.Failure(PreviewFrameFailure.GL_SETUP_FAILED, "Unable to create preview GL resources."))
                return@post
            }
            if (currentState() == BurstRecorderState.Idle || currentState() == BurstRecorderState.Releasing) {
                runCatching { resources.release() }
                return@post
            }
            glResources = resources
            openCamera(cameraId, options.mode, backgroundHandler, resources.surface)
        }
        return null
    }

    fun stopActive() {
        if (currentState() == BurstRecorderState.Idle) return
        complete(PreviewFrameOutcome.Cancelled)
    }

    @Suppress("MissingPermission")
    private fun openCamera(
        cameraId: String,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
        previewSurface: Surface,
    ) {
        if (currentState() == BurstRecorderState.Idle || currentState() == BurstRecorderState.Releasing) return
        val manager = context.getSystemService(CameraManager::class.java)
        if (manager == null) {
            complete(PreviewFrameOutcome.Failure(PreviewFrameFailure.CAMERA_OPEN_FAILED, "Camera service is unavailable."))
            return
        }
        try {
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        cameraDevice = device
                        configureSession(device, mode, backgroundHandler, previewSurface)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        cameraDevice = device
                        complete(PreviewFrameOutcome.Failure(PreviewFrameFailure.CAMERA_DEVICE_DISCONNECTED, "Camera device disconnected."))
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        cameraDevice = device
                        complete(PreviewFrameOutcome.Failure(PreviewFrameFailure.CAMERA_DEVICE_ERROR, "Camera device error code $error."))
                    }
                },
                backgroundHandler,
            )
        } catch (exception: Exception) {
            complete(PreviewFrameOutcome.Failure(PreviewFrameFailure.CAMERA_OPEN_FAILED, "Camera open failed: ${exception.message ?: exception.javaClass.simpleName}."))
        }
    }

    private fun configureSession(
        device: CameraDevice,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
        previewSurface: Surface,
    ) {
        synchronized(lock) { state = BurstRecorderState.Configuring }
        try {
            device.createConstrainedHighSpeedCaptureSession(
                listOf(previewSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        val highSpeedSession = cameraCaptureSession as CameraConstrainedHighSpeedCaptureSession
                        session = highSpeedSession
                        startRepeatingPreview(device, highSpeedSession, previewSurface, mode, backgroundHandler)
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        complete(PreviewFrameOutcome.Failure(PreviewFrameFailure.SURFACE_CONFIGURATION_REJECTED, "Preview-only high-speed surface configuration was rejected."))
                    }
                },
                backgroundHandler,
            )
        } catch (exception: Exception) {
            complete(PreviewFrameOutcome.Failure(PreviewFrameFailure.SURFACE_CONFIGURATION_REJECTED, "Preview-only high-speed session failed: ${exception.message ?: exception.javaClass.simpleName}."))
        }
    }

    private fun startRepeatingPreview(
        device: CameraDevice,
        highSpeedSession: CameraConstrainedHighSpeedCaptureSession,
        previewSurface: Surface,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
    ) {
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(previewSurface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.aeTargetFpsLower, mode.aeTargetFpsUpper))
            }
            val burst = highSpeedSession.createHighSpeedRequestList(request.build())
            Log.i(LOG_TAG, "PREVIEW_HIGH_SPEED_REQUEST_LIST mode=${mode.label.sanitizedLogToken()} requests=${burst.size}")
            val captureCallback = object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult,
                ) {
                    captureCallbackCount.incrementAndGet()
                    result.get(CaptureResult.SENSOR_TIMESTAMP)?.let { timestamp ->
                        synchronized(lock) { sensorTimestamps.add(timestamp) }
                    }
                }
            }
            highSpeedSession.setRepeatingBurst(burst, captureCallback, backgroundHandler)
            synchronized(lock) { state = BurstRecorderState.Recording }
            backgroundHandler.postDelayed({ complete(buildFinalOutcome()) }, options?.durationMillis ?: DEFAULT_BURST_DURATION_MILLIS)
        } catch (exception: Exception) {
            complete(PreviewFrameOutcome.Failure(PreviewFrameFailure.SESSION_CONFIGURATION_FAILED, "Preview repeating request failed: ${exception.message ?: exception.javaClass.simpleName}."))
        }
    }

    private fun onFrameAvailable() {
        val resources = glResources ?: return
        if (!shouldHandlePreviewFrame(currentState())) return
        runCatching {
            resources.makeCurrent()
            resources.surfaceTexture.updateTexImage()
            frameCallbackCount.incrementAndGet()
            val timestamp = resources.surfaceTexture.timestamp
            synchronized(lock) { previewTimestamps.add(timestamp) }
        }.onFailure {
            complete(PreviewFrameOutcome.Failure(PreviewFrameFailure.FRAME_TIMEOUT, "Preview frame update failed: ${it.message ?: it.javaClass.simpleName}."))
        }
    }

    private fun buildFinalOutcome(): PreviewFrameOutcome {
        val safeOptions = options ?: return PreviewFrameOutcome.Failure(PreviewFrameFailure.SESSION_CONFIGURATION_FAILED, "Preview spike options were lost before completion.")
        val previewCopy = synchronized(lock) { previewTimestamps.toList() }
        val sensorCopy = synchronized(lock) { sensorTimestamps.toList() }
        return pairPreviewFrameTimestamps(
            rawPreviewTimestampsNanos = previewCopy,
            rawSensorTimestampsNanos = sensorCopy,
            fps = safeOptions.mode.fps,
        )
    }

    private fun finishSynchronously() {
        synchronized(lock) {
            terminalGate.claim()
            state = BurstRecorderState.Releasing
            completion = null
        }
        releaseResources()
        synchronized(lock) {
            state = BurstRecorderState.Idle
            options = null
        }
    }

    private fun complete(primaryOutcome: PreviewFrameOutcome) {
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

    private fun finishClaimedCompletion(primaryOutcome: PreviewFrameOutcome) {
        val callbackToRun: ((PreviewFrameOutcome) -> Unit)? = synchronized(lock) {
            val callback = completion
            completion = null
            callback
        }
        val releaseFailed = releaseResources()
        val outcome = resolvePreviewReleaseOutcome(primaryOutcome, releaseFailed)
        synchronized(lock) {
            state = BurstRecorderState.Idle
            options = null
        }
        callbackToRun?.invoke(outcome)
    }

    private fun releaseResources(): Boolean {
        val releaseFailed = releasePreviewResources(
            PreviewReleaseActions(
                stopRepeating = { session?.stopRepeating() },
                closeSession = { session?.close() },
                closeCamera = { cameraDevice?.close() },
                releaseGl = { glResources?.release() },
                quitThread = { handlerThread?.quitSafely() },
            ),
        )
        session = null
        cameraDevice = null
        glResources = null
        handler = null
        handlerThread = null
        return releaseFailed
    }

    private fun createPreviewGlResources(mode: HighSpeedMode, handler: Handler): PreviewGlResources? =
        runCatching {
            PreviewGlResources.create(mode.width, mode.height) {
                handler.post { onFrameAvailable() }
            }
        }.getOrNull()

    private companion object {
        const val LOG_TAG = "SPEEDBALL_CAPTURE"
        const val LOG_VALUE_CHUNK_SIZE = 80
    }
}

internal class PreviewReleaseActions(
    val stopRepeating: () -> Unit = {},
    val closeSession: () -> Unit = {},
    val closeCamera: () -> Unit = {},
    val releaseGl: () -> Unit = {},
    val quitThread: () -> Unit = {},
)

internal fun shouldHandlePreviewFrame(state: BurstRecorderState): Boolean =
    state != BurstRecorderState.Idle && state != BurstRecorderState.Releasing

internal fun resolvePreviewReleaseOutcome(
    primaryOutcome: PreviewFrameOutcome,
    releaseFailed: Boolean,
): PreviewFrameOutcome =
    if (releaseFailed && primaryOutcome !is PreviewFrameOutcome.Failure) {
        PreviewFrameOutcome.Failure(PreviewFrameFailure.RESOURCE_RELEASE_FAILED, "Preview spike stopped, but a resource release step failed.")
    } else {
        primaryOutcome
    }

internal fun releasePreviewResources(actions: PreviewReleaseActions): Boolean {
    var releaseFailed = false
    runCatching { actions.stopRepeating() }.onFailure { releaseFailed = true }
    runCatching { actions.closeSession() }.onFailure { releaseFailed = true }
    runCatching { actions.closeCamera() }.onFailure { releaseFailed = true }
    runCatching { actions.releaseGl() }.onFailure { releaseFailed = true }
    runCatching { actions.quitThread() }.onFailure { releaseFailed = true }
    return releaseFailed
}

private fun String.sanitizedLogToken(): String =
    replace(Regex("\\s+"), "_")

fun validatePreviewSpikeStart(
    mode: HighSpeedMode,
    availableModes: List<HighSpeedMode>,
    recorderState: BurstRecorderState,
): PreviewFrameOutcome.Failure? {
    if (recorderState != BurstRecorderState.Idle) {
        return PreviewFrameOutcome.Failure(PreviewFrameFailure.CAPTURE_BUSY, "A preview timestamp spike is already active.")
    }
    val knownMode = availableModes.any {
        it.width == mode.width &&
            it.height == mode.height &&
            it.fps == mode.fps &&
            it.aeTargetFpsLower == mode.aeTargetFpsLower &&
            it.aeTargetFpsUpper == mode.aeTargetFpsUpper
    }
    if (!knownMode || mode.aeTargetFpsLower != mode.fps || mode.aeTargetFpsUpper != mode.fps) {
        return PreviewFrameOutcome.Failure(PreviewFrameFailure.UNSUPPORTED_MODE, "This high-speed mode is not supported for preview timestamp proof.")
    }
    return null
}

private class PreviewGlResources private constructor(
    private val eglDisplay: EGLDisplay,
    private val eglContext: EGLContext,
    private val eglSurface: EGLSurface,
    val surfaceTexture: SurfaceTexture,
    val surface: Surface,
) {
    fun makeCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun release() {
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
        runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
        runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
        runCatching { EGL14.eglTerminate(eglDisplay) }
    }

    companion object {
        fun create(width: Int, height: Int, onFrameAvailable: () -> Unit): PreviewGlResources {
            val eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(eglDisplay != EGL14.EGL_NO_DISPLAY) { "No EGL display." }
            check(EGL14.eglInitialize(eglDisplay, null, 0, null, 0)) { "EGL initialize failed." }
            val configs = arrayOfNulls<EGLConfig>(1)
            val configCount = IntArray(1)
            val attributes = intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_NONE,
            )
            check(EGL14.eglChooseConfig(eglDisplay, attributes, 0, configs, 0, 1, configCount, 0) && configCount[0] > 0) {
                "EGL config unavailable."
            }
            val config = configs[0] ?: error("EGL config missing.")
            val context = EGL14.eglCreateContext(
                eglDisplay,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
            check(context != EGL14.EGL_NO_CONTEXT) { "EGL context creation failed." }
            val eglSurface = EGL14.eglCreatePbufferSurface(
                eglDisplay,
                config,
                intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE),
                0,
            )
            check(eglSurface != EGL14.EGL_NO_SURFACE) { "EGL pbuffer creation failed." }
            check(EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, context)) { "EGL make-current failed." }
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val textureId = textures[0]
            check(textureId != 0) { "OES texture creation failed." }
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            val surfaceTexture = SurfaceTexture(textureId)
            surfaceTexture.setDefaultBufferSize(width, height)
            surfaceTexture.setOnFrameAvailableListener { onFrameAvailable() }
            return PreviewGlResources(
                eglDisplay = eglDisplay,
                eglContext = context,
                eglSurface = eglSurface,
                surfaceTexture = surfaceTexture,
                surface = Surface(surfaceTexture),
            )
        }
    }
}
