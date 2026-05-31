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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.atomic.AtomicInteger

/** One atomic same-consumption direct frame proof record. */
data class DirectAtomicFrameProof(
    val frame: DirectFrameProof,
)

/** Camera2 owner for the companion-encoder direct timing proof session. */
class DirectCompanionTimingProofCapture(private val context: Context) {
    private val lock = Any()
    private val terminalGate = TerminalCompletionGate()
    private var state = BurstRecorderState.Idle
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraConstrainedHighSpeedCaptureSession? = null
    private var glResources: DirectGlReadbackResources? = null
    private var companionScratch: CompanionEncoderScratch? = null
    private var collector: DirectFrameProofCollector? = null
    private var sensorTimestamps = mutableListOf<Long>()
    private var captureCallbackCount = AtomicInteger(0)
    private var requestListSize = 0
    private var completion: ((DirectSessionProbeOutcome) -> Unit)? = null
    private var options: BurstOptions? = null

    fun currentState(): BurstRecorderState = synchronized(lock) { state }

    @Suppress("MissingPermission")
    fun start(
        options: BurstOptions,
        availableModes: List<HighSpeedMode>,
        onComplete: (DirectSessionProbeOutcome) -> Unit,
    ): DirectSessionProbeOutcome.Failure? {
        synchronized(lock) {
            validateDirectProofStart(options.mode, availableModes, state)?.let { return it }
            state = BurstRecorderState.Opening
            this.options = options.copy(durationMillis = clampBurstDurationMillis(options.durationMillis))
            completion = onComplete
            sensorTimestamps = mutableListOf()
            captureCallbackCount = AtomicInteger(0)
            requestListSize = 0
            collector = DirectFrameProofCollector(
                maxFrames = directProofMaxFrames(options.mode, this.options?.durationMillis ?: options.durationMillis),
                pixelConfig = DIRECT_CAPTURE_PIXEL_CONFIG,
            )
            terminalGate.reset()
        }

        if (context.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            val failure = directFailure(DirectTimingSourceFailure.CAMERA_OPEN_FAILED, "Camera permission is required.")
            finishSynchronously(failure)
            return failure
        }
        val cameraId = HighSpeedCamera(context).findBackCameraId()
        if (cameraId == null) {
            val failure = directFailure(DirectTimingSourceFailure.CAMERA_OPEN_FAILED, "No back camera is available.")
            finishSynchronously(failure)
            return failure
        }

        val safeOptions = this.options ?: options
        val scratch = prepareCompanionEncoderScratch(context, safeOptions.mode, safeOptions.durationMillis)
        if (scratch == null) {
            val failure = directFailure(DirectTimingSourceFailure.COMPANION_RECORDER_SETUP_FAILED, "Unable to prepare companion encoder scratch recorder.")
            finishSynchronously(failure)
            return failure
        }
        companionScratch = scratch
        Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_SCRATCH_PREPARED mode=${safeOptions.mode.label.sanitizedDirectCaptureLogToken()}")

        val thread = HandlerThread("speed-ball-direct-proof").also { it.start() }
        val backgroundHandler = Handler(thread.looper)
        handlerThread = thread
        handler = backgroundHandler
        backgroundHandler.post {
            if (!shouldHandleDirectFrame(currentState())) return@post
            Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_GL_SETUP_START mode=${safeOptions.mode.label.sanitizedDirectCaptureLogToken()}")
            val resources = createDirectGlResources(safeOptions.mode, backgroundHandler)
            if (resources == null) {
                Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_GL_SETUP_FAILED mode=${safeOptions.mode.label.sanitizedDirectCaptureLogToken()}")
                complete(directFailure(DirectTimingSourceFailure.PIXEL_READBACK_FAILED, "Unable to create direct GL readback resources."))
                return@post
            }
            if (!shouldHandleDirectFrame(currentState())) {
                runCatching { resources.release() }
                return@post
            }
            glResources = resources
            Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_GL_SETUP_READY mode=${safeOptions.mode.label.sanitizedDirectCaptureLogToken()}")
            openCamera(cameraId, safeOptions.mode, backgroundHandler, scratch.surface, resources.surface)
        }
        return null
    }

    fun stopActive() {
        if (currentState() == BurstRecorderState.Idle) return
        complete(directFailure(DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED, "Direct proof capture was cancelled."))
    }

    @Suppress("MissingPermission")
    private fun openCamera(
        cameraId: String,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
        companionSurface: Surface,
        directSurface: Surface,
    ) {
        if (!shouldHandleDirectFrame(currentState())) return
        val manager = context.getSystemService(CameraManager::class.java)
        if (manager == null) {
            complete(directFailure(DirectTimingSourceFailure.CAMERA_OPEN_FAILED, "Camera service is unavailable."))
            return
        }
        try {
            Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_OPEN_CAMERA_START mode=${mode.label.sanitizedDirectCaptureLogToken()}")
            manager.openCamera(
                cameraId,
                object : CameraDevice.StateCallback() {
                    override fun onOpened(device: CameraDevice) {
                        Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_CAMERA_OPENED mode=${mode.label.sanitizedDirectCaptureLogToken()}")
                        cameraDevice = device
                        configureSession(device, mode, backgroundHandler, companionSurface, directSurface)
                    }

                    override fun onDisconnected(device: CameraDevice) {
                        cameraDevice = device
                        complete(directFailure(DirectTimingSourceFailure.CAMERA_OPEN_FAILED, "Camera device disconnected."))
                    }

                    override fun onError(device: CameraDevice, error: Int) {
                        cameraDevice = device
                        complete(directFailure(DirectTimingSourceFailure.CAMERA_OPEN_FAILED, "Camera device error code $error."))
                    }
                },
                backgroundHandler,
            )
        } catch (exception: Exception) {
            complete(directFailure(DirectTimingSourceFailure.CAMERA_OPEN_FAILED, "Camera open failed: ${exception.message ?: exception.javaClass.simpleName}."))
        }
    }

    private fun configureSession(
        device: CameraDevice,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
        companionSurface: Surface,
        directSurface: Surface,
    ) {
        synchronized(lock) { state = BurstRecorderState.Configuring }
        try {
            Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_CONFIGURE_START mode=${mode.label.sanitizedDirectCaptureLogToken()} surfaces=companion,direct")
            device.createConstrainedHighSpeedCaptureSession(
                listOf(companionSurface, directSurface),
                object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                        Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_CONFIGURED mode=${mode.label.sanitizedDirectCaptureLogToken()}")
                        val highSpeedSession = cameraCaptureSession as CameraConstrainedHighSpeedCaptureSession
                        session = highSpeedSession
                        startRepeatingDirect(device, highSpeedSession, mode, backgroundHandler, companionSurface, directSurface)
                    }

                    override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                        complete(directFailure(DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED, "Direct companion high-speed session configuration failed."))
                    }
                },
                backgroundHandler,
            )
        } catch (exception: Exception) {
            complete(directFailure(DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED, "Direct companion high-speed session failed: ${exception.message ?: exception.javaClass.simpleName}."))
        }
    }

    private fun startRepeatingDirect(
        device: CameraDevice,
        highSpeedSession: CameraConstrainedHighSpeedCaptureSession,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
        companionSurface: Surface,
        directSurface: Surface,
    ) {
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                addTarget(companionSurface)
                addTarget(directSurface)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.aeTargetFpsLower, mode.aeTargetFpsUpper))
            }
            val burst = highSpeedSession.createHighSpeedRequestList(request.build())
            requestListSize = burst.size
            Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_HIGH_SPEED_REQUEST_LIST mode=${mode.label.sanitizedDirectCaptureLogToken()} requests=${burst.size}")
            companionScratch?.start()
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
            backgroundHandler.postDelayed({ complete(null) }, options?.durationMillis ?: DEFAULT_BURST_DURATION_MILLIS)
        } catch (exception: Exception) {
            complete(directFailure(DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED, "Direct repeating request failed: ${exception.message ?: exception.javaClass.simpleName}."))
        }
    }

    private fun onFrameAvailable() {
        val resources = glResources ?: return
        val currentCollector = collector ?: return
        if (!shouldHandleDirectFrame(currentState())) return
        runCatching {
            val signature = resources.updateAndReadSignature()
            val failure = currentCollector.appendAtomicFrame(
                state = currentState(),
                frameIndex = currentCollector.snapshot().size,
                timestampNanos = signature.timestampNanos,
                width = signature.frameWidth,
                height = signature.frameHeight,
                signature = signature,
            )
            if (failure != null) {
                complete(directFailure(failure, "Direct frame proof callback failed with $failure."))
            }
            val frameCount = currentCollector.snapshot().size
            if (frameCount == 1 || frameCount % 30 == 0) {
                Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_FRAME_READBACK count=$frameCount timestamp=${signature.timestampNanos}")
            }
            if (frameCount >= DIRECT_TARGET_PROOF_FRAMES) {
                complete(null)
            }
        }.onFailure {
            complete(directFailure(DirectTimingSourceFailure.PIXEL_READBACK_FAILED, "Direct frame readback failed: ${it.message ?: it.javaClass.simpleName}."))
        }
    }

    private fun finishSynchronously(primaryOutcome: DirectSessionProbeOutcome.Failure) {
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

    private fun complete(primaryOutcome: DirectSessionProbeOutcome.Failure?) {
        val releaseHandler: Handler?
        synchronized(lock) {
            if (!terminalGate.claim()) return
            state = BurstRecorderState.Releasing
            collector?.markReleased()
            releaseHandler = handler
        }
        if (releaseHandler != null && Looper.myLooper() != releaseHandler.looper) {
            releaseHandler.post { finishClaimedCompletion(primaryOutcome) }
        } else {
            finishClaimedCompletion(primaryOutcome)
        }
    }

    private fun finishClaimedCompletion(primaryOutcome: DirectSessionProbeOutcome.Failure?) {
        val callbackToRun: ((DirectSessionProbeOutcome) -> Unit)? = synchronized(lock) {
            val callback = completion
            completion = null
            callback
        }
        val releaseFailure = releaseResources()
        val outcome = primaryOutcome ?: buildFinalOutcome(releaseFailure)
        synchronized(lock) {
            state = BurstRecorderState.Idle
            options = null
        }
        callbackToRun?.invoke(outcome)
    }

    private fun buildFinalOutcome(releaseFailure: DirectTimingSourceFailure?): DirectSessionProbeOutcome {
        val safeOptions = options ?: return directFailure(DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED, "Direct proof options were lost before completion.")
        val frameProofs = collector?.snapshot().orEmpty().map { it.frame }
        val sensorCopy = synchronized(lock) { sensorTimestamps.toList() }
        if (releaseFailure != null) {
            return directFailure(
                reason = releaseFailure,
                message = "Direct proof stopped, but a resource release step failed.",
                directTimestampCount = frameProofs.size,
                sensorTimestampCount = sensorCopy.size,
                pixelProofCount = frameProofs.size,
            )
        }
        if (frameProofs.isEmpty()) {
            return directFailure(
                reason = DirectTimingSourceFailure.MISSING_DIRECT_TIMESTAMPS,
                message = "No direct SurfaceTexture frames were consumed.",
                sensorTimestampCount = sensorCopy.size,
            )
        }
        return DirectSessionProbeOutcome.Success(
            shape = DirectProofSessionShape.COMPANION_ENCODER,
            requestListSize = requestListSize.coerceAtLeast(1),
            sensorTimestampsNanos = sensorCopy,
            frames = frameProofs,
            scratchCleanupStatus = CompanionScratchCleanupStatus.DELETED,
        )
    }

    private fun releaseResources(): DirectTimingSourceFailure? {
        var scratchStatus = CompanionScratchCleanupStatus.ALREADY_ABSENT
        val failure = releaseDirectCaptureResources(
            DirectReleaseActions(
                stopRepeating = {
                    Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_RELEASE_STEP stopRepeating")
                    session?.stopRepeating()
                },
                closeSession = {
                    Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_RELEASE_STEP closeSession")
                    session?.close()
                },
                closeCamera = {
                    Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_RELEASE_STEP closeCamera")
                    cameraDevice?.close()
                },
                releaseGl = {
                    Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_RELEASE_STEP releaseGl")
                    glResources?.release()
                },
                releaseCompanion = {
                    Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_RELEASE_STEP releaseCompanion")
                    val result = companionScratch?.releaseAndDelete()
                        ?: CompanionScratchCleanupResult(CompanionScratchCleanupStatus.ALREADY_ABSENT)
                    scratchStatus = result.status
                    result
                },
                quitThread = {
                    Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_RELEASE_STEP quitThread")
                    handlerThread?.quitSafely()
                },
            ),
        )
        Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_RELEASE_DONE failure=$failure scratch=$scratchStatus")
        session = null
        cameraDevice = null
        glResources = null
        companionScratch = null
        handler = null
        handlerThread = null
        return when {
            failure == DirectTimingSourceFailure.SCRATCH_FILE_CLEANUP_FAILED ||
                scratchStatus == CompanionScratchCleanupStatus.FAILED -> DirectTimingSourceFailure.SCRATCH_FILE_CLEANUP_FAILED
            failure != null -> failure
            else -> null
        }
    }

    private fun createDirectGlResources(mode: HighSpeedMode, handler: Handler): DirectGlReadbackResources? =
        runCatching {
            DirectGlReadbackResources.create(
                frameWidth = mode.width,
                frameHeight = mode.height,
                readbackWidth = DIRECT_READBACK_WIDTH,
                readbackHeight = DIRECT_READBACK_HEIGHT,
            ) {
                handler.post { onFrameAvailable() }
            }
        }.getOrNull()
}

/**
 * State machine helper for direct capture callbacks.
 *
 * A frame callback may append at most one atomic timestamp/pixel proof record,
 * and never while the capture is idle or releasing.
 */
class DirectFrameProofCollector(
    private val maxFrames: Int,
    private val pixelConfig: DirectPixelProofConfig,
) {
    private val records = mutableListOf<DirectAtomicFrameProof>()
    private var released = false

    init {
        require(maxFrames > 0) { "Max frames must be positive." }
    }

    fun snapshot(): List<DirectAtomicFrameProof> = records.toList()

    fun markReleased() {
        released = true
    }

    fun appendAtomicFrame(
        state: BurstRecorderState,
        frameIndex: Int,
        timestampNanos: Long,
        width: Int,
        height: Int,
        signature: DirectPixelProofSignature,
    ): DirectTimingSourceFailure? {
        if (!shouldHandleDirectFrame(state) || released) {
            return DirectTimingSourceFailure.LATE_CALLBACK_AFTER_TEARDOWN
        }
        if (records.size >= maxFrames) {
            return DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED
        }
        val frame = DirectFrameProof(
            frameIndex = frameIndex,
            timestampNanos = timestampNanos,
            width = width,
            height = height,
            pixelSignature = signature,
        )
        val totalSamples = records.sumOf { it.frame.pixelSignature.sampleCount } + signature.sampleCount
        if (totalSamples > pixelConfig.maxTotalSamples) {
            return DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED
        }
        records += DirectAtomicFrameProof(frame)
        return null
    }
}

internal fun shouldHandleDirectFrame(state: BurstRecorderState): Boolean =
    state != BurstRecorderState.Idle && state != BurstRecorderState.Releasing

fun validateDirectProofStart(
    mode: HighSpeedMode,
    availableModes: List<HighSpeedMode>,
    recorderState: BurstRecorderState,
): DirectSessionProbeOutcome.Failure? {
    if (recorderState != BurstRecorderState.Idle) {
        return directFailure(DirectTimingSourceFailure.CAPTURE_BUSY, "A direct proof capture is already active.")
    }
    val knownMode = availableModes.any {
        it.width == mode.width &&
            it.height == mode.height &&
            it.fps == mode.fps &&
            it.aeTargetFpsLower == mode.aeTargetFpsLower &&
            it.aeTargetFpsUpper == mode.aeTargetFpsUpper
    }
    if (!knownMode || mode.aeTargetFpsLower != mode.fps || mode.aeTargetFpsUpper != mode.fps) {
        return directFailure(DirectTimingSourceFailure.UNSUPPORTED_MODE, "This high-speed mode is not supported for direct timing proof.")
    }
    return null
}

internal fun directFailure(
    reason: DirectTimingSourceFailure,
    message: String,
    requestListSize: Int = 0,
    directTimestampCount: Int = 0,
    sensorTimestampCount: Int = 0,
    pixelProofCount: Int = 0,
): DirectSessionProbeOutcome.Failure =
    DirectSessionProbeOutcome.Failure(
        shape = DirectProofSessionShape.COMPANION_ENCODER,
        reason = reason,
        message = message,
        requestListSize = requestListSize,
        directTimestampCount = directTimestampCount,
        sensorTimestampCount = sensorTimestampCount,
        pixelProofCount = pixelProofCount,
    )

private fun directProofMaxFrames(mode: HighSpeedMode, durationMillis: Long): Int =
    ((mode.fps * clampBurstDurationMillis(durationMillis)) / 1_000L).toInt()
        .coerceAtLeast(1)
        .coerceAtMost(MAX_DIRECT_PROOF_FRAMES)

internal class DirectReleaseActions(
    val stopRepeating: () -> Unit = {},
    val closeSession: () -> Unit = {},
    val closeCamera: () -> Unit = {},
    val releaseGl: () -> Unit = {},
    val releaseCompanion: () -> CompanionScratchCleanupResult = {
        CompanionScratchCleanupResult(CompanionScratchCleanupStatus.ALREADY_ABSENT)
    },
    val quitThread: () -> Unit = {},
)

internal fun releaseDirectCaptureResources(actions: DirectReleaseActions): DirectTimingSourceFailure? {
    var releaseFailed = false
    var scratchCleanupFailed = false
    runCatching { actions.stopRepeating() }.onFailure { releaseFailed = true }
    runCatching { actions.closeSession() }.onFailure { releaseFailed = true }
    runCatching { actions.closeCamera() }.onFailure { releaseFailed = true }
    runCatching { actions.releaseGl() }.onFailure { releaseFailed = true }
    runCatching {
        scratchCleanupFailed = actions.releaseCompanion().status == CompanionScratchCleanupStatus.FAILED
    }.onFailure { scratchCleanupFailed = true }
    runCatching { actions.quitThread() }.onFailure { releaseFailed = true }
    return when {
        scratchCleanupFailed -> DirectTimingSourceFailure.SCRATCH_FILE_CLEANUP_FAILED
        releaseFailed -> DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED
        else -> null
    }
}

private const val DIRECT_READBACK_WIDTH = 2
private const val DIRECT_READBACK_HEIGHT = 2
private const val MAX_DIRECT_PROOF_FRAMES = 360
private const val DIRECT_TARGET_PROOF_FRAMES = 1
private const val DIRECT_CAPTURE_LOG_TAG = "SPEEDBALL_CAPTURE"
private val DIRECT_CAPTURE_PIXEL_CONFIG = DirectPixelProofConfig(
    maxTotalSamples = DIRECT_READBACK_WIDTH * DIRECT_READBACK_HEIGHT * MAX_DIRECT_PROOF_FRAMES,
    requireInterFrameVariation = true,
)

private fun String.sanitizedDirectCaptureLogToken(): String =
    replace(Regex("\\s+"), "_")

/**
 * GL resources for direct same-`updateTexImage()` readback.
 *
 * `updateAndReadSignature()` calls `updateTexImage()` once, captures that
 * timestamp, renders the external OES texture into a small pbuffer, and reads a
 * bounded RGBA aggregate signature before any later frame consumption.
 */
internal class DirectGlReadbackResources private constructor(
    private val eglDisplay: EGLDisplay,
    private val eglContext: EGLContext,
    private val eglSurface: EGLSurface,
    private val textureId: Int,
    private val program: Int,
    private val positionBuffer: FloatBuffer,
    private val texCoordBuffer: FloatBuffer,
    private val readbackWidth: Int,
    private val readbackHeight: Int,
    private val frameWidth: Int,
    private val frameHeight: Int,
    val surfaceTexture: SurfaceTexture,
    val surface: Surface,
) {
    fun makeCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun updateAndReadSignature(): DirectPixelProofSignature {
        makeCurrent()
        surfaceTexture.updateTexImage()
        val timestamp = surfaceTexture.timestamp
        GLES20.glViewport(0, 0, readbackWidth, readbackHeight)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureId)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        val positionLocation = GLES20.glGetAttribLocation(program, "aPosition")
        val texCoordLocation = GLES20.glGetAttribLocation(program, "aTexCoord")
        GLES20.glEnableVertexAttribArray(positionLocation)
        GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glEnableVertexAttribArray(texCoordLocation)
        GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        val buffer = ByteBuffer.allocateDirect(readbackWidth * readbackHeight * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, readbackWidth, readbackHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        val argb = IntArray(readbackWidth * readbackHeight)
        for (index in argb.indices) {
            val red = buffer.get().toInt() and 0xff
            val green = buffer.get().toInt() and 0xff
            val blue = buffer.get().toInt() and 0xff
            val alpha = buffer.get().toInt() and 0xff
            argb[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return buildDirectPixelProofSignature(
            timestampNanos = timestamp,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            tileLeft = 0,
            tileTop = 0,
            tileWidth = readbackWidth,
            tileHeight = readbackHeight,
            argbPixels = argb,
        )
    }

    fun release() {
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        runCatching { GLES20.glDeleteProgram(program) }
        runCatching { GLES20.glDeleteTextures(1, intArrayOf(textureId), 0) }
        runCatching { EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT) }
        runCatching { EGL14.eglDestroySurface(eglDisplay, eglSurface) }
        runCatching { EGL14.eglDestroyContext(eglDisplay, eglContext) }
        runCatching { EGL14.eglTerminate(eglDisplay) }
    }

    companion object {
        fun create(
            frameWidth: Int,
            frameHeight: Int,
            readbackWidth: Int,
            readbackHeight: Int,
            onFrameAvailable: () -> Unit,
        ): DirectGlReadbackResources {
            require(frameWidth > 0 && frameHeight > 0) { "Frame dimensions must be positive." }
            require(readbackWidth > 0 && readbackHeight > 0) { "Readback dimensions must be positive." }
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
                intArrayOf(EGL14.EGL_WIDTH, readbackWidth, EGL14.EGL_HEIGHT, readbackHeight, EGL14.EGL_NONE),
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
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val surfaceTexture = SurfaceTexture(textureId)
            surfaceTexture.setDefaultBufferSize(frameWidth, frameHeight)
            surfaceTexture.setOnFrameAvailableListener { onFrameAvailable() }
            return DirectGlReadbackResources(
                eglDisplay = eglDisplay,
                eglContext = context,
                eglSurface = eglSurface,
                textureId = textureId,
                program = createOesProgram(),
                positionBuffer = floatBufferOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
                texCoordBuffer = floatBufferOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f),
                readbackWidth = readbackWidth,
                readbackHeight = readbackHeight,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                surfaceTexture = surfaceTexture,
                surface = Surface(surfaceTexture),
            )
        }
    }
}

private fun createOesProgram(): Int {
    val vertex = compileShader(
        GLES20.GL_VERTEX_SHADER,
        """
        attribute vec2 aPosition;
        attribute vec2 aTexCoord;
        varying vec2 vTexCoord;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            vTexCoord = aTexCoord;
        }
        """.trimIndent(),
    )
    val fragment = compileShader(
        GLES20.GL_FRAGMENT_SHADER,
        """
        #extension GL_OES_EGL_image_external : require
        precision mediump float;
        uniform samplerExternalOES uTexture;
        varying vec2 vTexCoord;
        void main() {
            gl_FragColor = texture2D(uTexture, vTexCoord);
        }
        """.trimIndent(),
    )
    val program = GLES20.glCreateProgram()
    GLES20.glAttachShader(program, vertex)
    GLES20.glAttachShader(program, fragment)
    GLES20.glLinkProgram(program)
    val linked = IntArray(1)
    GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
    check(linked[0] == GLES20.GL_TRUE) { "OES program link failed." }
    GLES20.glDeleteShader(vertex)
    GLES20.glDeleteShader(fragment)
    return program
}

private fun compileShader(type: Int, source: String): Int {
    val shader = GLES20.glCreateShader(type)
    GLES20.glShaderSource(shader, source)
    GLES20.glCompileShader(shader)
    val compiled = IntArray(1)
    GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
    check(compiled[0] == GLES20.GL_TRUE) { "OES shader compile failed." }
    return shader
}

private fun floatBufferOf(vararg values: Float): FloatBuffer =
    ByteBuffer.allocateDirect(values.size * Float.SIZE_BYTES)
        .order(ByteOrder.nativeOrder())
        .asFloatBuffer()
        .apply {
            put(values)
            position(0)
        }
