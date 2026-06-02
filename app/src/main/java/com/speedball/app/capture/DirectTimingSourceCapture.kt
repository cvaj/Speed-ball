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
import android.graphics.ImageFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLES30
import android.os.Build
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

/** Bounded app-owned ARGB frame read from a direct live `SurfaceTexture` frame. */
data class DirectArgbFrame(
    val width: Int,
    val height: Int,
    val timestampNanos: Long,
    val argbPixels: IntArray,
) {
    init {
        require(width > 0 && height > 0) { "Direct ARGB frame dimensions must be positive." }
        require(timestampNanos > 0L) { "Direct ARGB frame timestamp must be positive." }
        require(argbPixels.size == width * height) { "Direct ARGB pixel count must match dimensions." }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is DirectArgbFrame &&
            width == other.width &&
            height == other.height &&
            timestampNanos == other.timestampNanos &&
            argbPixels.contentEquals(other.argbPixels)

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + timestampNanos.hashCode()
        result = 31 * result + argbPixels.contentHashCode()
        return result
    }
}

/** Camera2 owner for the companion-encoder direct timing proof session. */
class DirectCompanionTimingProofCapture(private val context: Context) {
    private val lock = Any()
    private val terminalGate = TerminalCompletionGate()
    private var state = BurstRecorderState.Idle
    private var handlerThread: HandlerThread? = null
    private var handler: Handler? = null
    private var cameraDevice: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var glResources: DirectGlReadbackResources? = null
    private var imageReader: ImageReader? = null
    private var companionScratch: CompanionEncoderScratch? = null
    private var collector: DirectFrameProofCollector? = null
    private var sensorTimestamps = mutableListOf<Long>()
    private var captureCallbackCount = AtomicInteger(0)
    private var frameAvailableCallbackCount = AtomicInteger(0)
    private var imageAcquireNullCount = AtomicInteger(0)
    private var readbackElapsedMillis = mutableListOf<Double>()
    private var requestListSize = 0
    private var completion: ((DirectSessionProbeOutcome) -> Unit)? = null
    private var options: BurstOptions? = null
    private var activeVariant: DirectProofVariant = companionGlBaselineVariant()

    fun currentState(): BurstRecorderState = synchronized(lock) { state }

    @Suppress("MissingPermission")
    fun start(
        options: BurstOptions,
        availableModes: List<HighSpeedMode>,
        variant: DirectProofVariant = companionGlBaselineVariant(),
        onComplete: (DirectSessionProbeOutcome) -> Unit,
    ): DirectSessionProbeOutcome.Failure? {
        synchronized(lock) {
            validateDirectProofStart(options.mode, availableModes, state)?.let { return it }
            validateDirectProofVariant(variant)?.let { return it }
            state = BurstRecorderState.Opening
            this.options = options.copy(durationMillis = clampBurstDurationMillis(options.durationMillis))
            activeVariant = variant
            completion = onComplete
            sensorTimestamps = mutableListOf()
            captureCallbackCount = AtomicInteger(0)
            frameAvailableCallbackCount = AtomicInteger(0)
            imageAcquireNullCount = AtomicInteger(0)
            readbackElapsedMillis = mutableListOf()
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
        val scratch = if (variant.requiresCompanionScratch) {
            prepareCompanionEncoderScratch(context, safeOptions.mode, safeOptions.durationMillis)
        } else {
            null
        }
        if (variant.requiresCompanionScratch && scratch == null) {
            val failure = directFailure(DirectTimingSourceFailure.COMPANION_RECORDER_SETUP_FAILED, "Unable to prepare companion encoder scratch recorder.")
            finishSynchronously(failure)
            return failure
        }
        companionScratch = scratch
        if (scratch != null) {
            Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_SCRATCH_PREPARED mode=${safeOptions.mode.label.sanitizedDirectCaptureLogToken()} variant=${variant.id}")
        }

        val thread = HandlerThread("speed-ball-direct-proof").also { it.start() }
        val backgroundHandler = Handler(thread.looper)
        handlerThread = thread
        handler = backgroundHandler
        backgroundHandler.post {
            if (!shouldHandleDirectFrame(currentState())) return@post
            when (variant.consumerModel) {
                DirectProofConsumerModel.SINGLE_SURFACE_TEXTURE,
                DirectProofConsumerModel.PBO_GL_READBACK,
                -> {
                    Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_GL_SETUP_START mode=${safeOptions.mode.label.sanitizedDirectCaptureLogToken()} variant=${variant.id}")
                    val resources = createDirectGlResources(safeOptions.mode, backgroundHandler, variant)
                    if (resources == null) {
                        Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_GL_SETUP_FAILED mode=${safeOptions.mode.label.sanitizedDirectCaptureLogToken()} variant=${variant.id}")
                        complete(directFailure(DirectTimingSourceFailure.PIXEL_READBACK_FAILED, "Unable to create direct GL readback resources."))
                        return@post
                    }
                    if (!shouldHandleDirectFrame(currentState())) {
                        runCatching { resources.release() }
                        return@post
                    }
                    glResources = resources
                    Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_GL_SETUP_READY mode=${safeOptions.mode.label.sanitizedDirectCaptureLogToken()} variant=${variant.id}")
                    openCamera(cameraId, safeOptions.mode, backgroundHandler, variant, scratch?.surface, resources.surface, null)
                }

                DirectProofConsumerModel.CONSTRAINED_IMAGE_READER,
                DirectProofConsumerModel.CONSTRAINED_PRIVATE_IMAGE_READER,
                DirectProofConsumerModel.STANDARD_IMAGE_READER,
                -> {
                    val reader = createDirectImageReader(safeOptions.mode, variant, backgroundHandler)
                    if (reader == null) {
                        complete(directFailure(DirectTimingSourceFailure.PIXEL_READBACK_FAILED, "Unable to create direct ImageReader resources."))
                        return@post
                    }
                    imageReader = reader
                    Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_IMAGE_READER_READY mode=${safeOptions.mode.label.sanitizedDirectCaptureLogToken()} variant=${variant.id} maxImages=${variant.imageReaderMaxImages}")
                    openCamera(cameraId, safeOptions.mode, backgroundHandler, variant, scratch?.surface, null, reader.surface)
                }
            }
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
        variant: DirectProofVariant,
        companionSurface: Surface?,
        directSurface: Surface?,
        imageReaderSurface: Surface?,
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
                        configureSession(device, mode, backgroundHandler, variant, companionSurface, directSurface, imageReaderSurface)
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
        variant: DirectProofVariant,
        companionSurface: Surface?,
        directSurface: Surface?,
        imageReaderSurface: Surface?,
    ) {
        synchronized(lock) { state = BurstRecorderState.Configuring }
        try {
            val surfaces = buildDirectVariantSurfaceList(variant, companionSurface, directSurface, imageReaderSurface)
            Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_CONFIGURE_START mode=${mode.label.sanitizedDirectCaptureLogToken()} variant=${variant.id} surfaces=${variant.surfaceOrder.joinToString(separator = ",")}")
            val callback = object : CameraCaptureSession.StateCallback() {
                override fun onConfigured(cameraCaptureSession: CameraCaptureSession) {
                    Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_CONFIGURED mode=${mode.label.sanitizedDirectCaptureLogToken()} variant=${variant.id}")
                    session = cameraCaptureSession
                    if (variant.constrainedHighSpeed) {
                        startRepeatingHighSpeedDirect(
                            device = device,
                            highSpeedSession = cameraCaptureSession as CameraConstrainedHighSpeedCaptureSession,
                            mode = mode,
                            backgroundHandler = backgroundHandler,
                            variant = variant,
                            surfaces = surfaces,
                        )
                    } else {
                        startRepeatingStandardDirect(
                            device = device,
                            cameraCaptureSession = cameraCaptureSession,
                            mode = mode,
                            backgroundHandler = backgroundHandler,
                            variant = variant,
                            surfaces = surfaces,
                        )
                    }
                }

                override fun onConfigureFailed(cameraCaptureSession: CameraCaptureSession) {
                    complete(directFailure(DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED, "Direct session configuration failed for ${variant.id}."))
                }
            }
            if (variant.constrainedHighSpeed) {
                device.createConstrainedHighSpeedCaptureSession(surfaces, callback, backgroundHandler)
            } else {
                device.createCaptureSession(surfaces, callback, backgroundHandler)
            }
        } catch (exception: Exception) {
            complete(directFailure(DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED, "Direct session failed for ${variant.id}: ${exception.message ?: exception.javaClass.simpleName}."))
        }
    }

    private fun startRepeatingHighSpeedDirect(
        device: CameraDevice,
        highSpeedSession: CameraConstrainedHighSpeedCaptureSession,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
        variant: DirectProofVariant,
        surfaces: List<Surface>,
    ) {
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                surfaces.forEach(::addTarget)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.aeTargetFpsLower, mode.aeTargetFpsUpper))
            }
            val burst = highSpeedSession.createHighSpeedRequestList(request.build())
            requestListSize = burst.size
            Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_HIGH_SPEED_REQUEST_LIST mode=${mode.label.sanitizedDirectCaptureLogToken()} variant=${variant.id} requests=${burst.size}")
            companionScratch?.start()
            highSpeedSession.setRepeatingBurst(burst, directCaptureCallback(), backgroundHandler)
            synchronized(lock) { state = BurstRecorderState.Recording }
            backgroundHandler.postDelayed({ complete(null) }, options?.durationMillis ?: DEFAULT_BURST_DURATION_MILLIS)
        } catch (exception: Exception) {
            complete(directFailure(DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED, "Direct repeating request failed for ${variant.id}: ${exception.message ?: exception.javaClass.simpleName}."))
        }
    }

    private fun startRepeatingStandardDirect(
        device: CameraDevice,
        cameraCaptureSession: CameraCaptureSession,
        mode: HighSpeedMode,
        backgroundHandler: Handler,
        variant: DirectProofVariant,
        surfaces: List<Surface>,
    ) {
        try {
            val request = device.createCaptureRequest(CameraDevice.TEMPLATE_RECORD).apply {
                surfaces.forEach(::addTarget)
                set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(mode.aeTargetFpsLower, mode.aeTargetFpsUpper))
            }.build()
            requestListSize = 1
            Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_STANDARD_REQUEST mode=${mode.label.sanitizedDirectCaptureLogToken()} variant=${variant.id} requests=1")
            val captureCallback = directCaptureCallback()
            cameraCaptureSession.setRepeatingRequest(request, captureCallback, backgroundHandler)
            synchronized(lock) { state = BurstRecorderState.Recording }
            backgroundHandler.postDelayed({ complete(null) }, options?.durationMillis ?: DEFAULT_BURST_DURATION_MILLIS)
        } catch (exception: Exception) {
            complete(directFailure(DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED, "Direct standard request failed for ${variant.id}: ${exception.message ?: exception.javaClass.simpleName}."))
        }
    }

    private fun directCaptureCallback(): CameraCaptureSession.CaptureCallback =
        object : CameraCaptureSession.CaptureCallback() {
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

    private fun onFrameAvailable() {
        frameAvailableCallbackCount.incrementAndGet()
        val resources = glResources ?: return
        if (!shouldHandleDirectFrame(currentState())) return
        runCatching {
            val readbackStart = System.nanoTime()
            val signatures = resources.updateAndReadSignatures()
            val readbackMillis = elapsedMillis(readbackStart)
            signatures.forEach { signature ->
                appendDirectSignature(signature, readbackMillis, source = resources.readbackSourceLabel)
            }
        }.onFailure {
            complete(directFailure(DirectTimingSourceFailure.PIXEL_READBACK_FAILED, "Direct frame readback failed: ${it.message ?: it.javaClass.simpleName}."))
        }
    }

    private fun onImageAvailable(reader: ImageReader) {
        frameAvailableCallbackCount.incrementAndGet()
        if (!shouldHandleDirectFrame(currentState())) return
        val image = runCatching { reader.acquireNextImage() }.getOrNull()
        if (image == null) {
            imageAcquireNullCount.incrementAndGet()
            return
        }
        val readbackStart = System.nanoTime()
        when (
            val outcome = buildDirectImageReaderPixelProofSignature(
                snapshot = AndroidImageReaderSnapshot(image),
                tileLeft = 0,
                tileTop = 0,
                tileWidth = DIRECT_READBACK_WIDTH,
                tileHeight = DIRECT_READBACK_HEIGHT,
            )
        ) {
            is DirectImageReaderPixelProofOutcome.Success ->
                appendDirectSignature(outcome.signature, elapsedMillis(readbackStart), source = "ImageReader")

            is DirectImageReaderPixelProofOutcome.Failure ->
                complete(directFailure(outcome.reason, outcome.message))
        }
    }

    private fun appendDirectSignature(
        signature: DirectPixelProofSignature,
        readbackMillis: Double,
        source: String,
    ) {
        val currentCollector = collector ?: return
        synchronized(lock) { readbackElapsedMillis.add(readbackMillis) }
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
            Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_FRAME_READBACK source=$source count=$frameCount timestamp=${signature.timestampNanos} readbackMs=${readbackMillis.formatMillisForDirectLog()} frameCallbacks=${frameAvailableCallbackCount.get()} captureCallbacks=${captureCallbackCount.get()}")
        }
        if (frameCount >= DIRECT_TARGET_PROOF_FRAMES) {
            complete(null)
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
        val releaseResult = releaseResources()
        val outcome = primaryOutcome?.withFinalCaptureContext(releaseResult) ?: buildFinalOutcome(releaseResult)
        synchronized(lock) {
            state = BurstRecorderState.Idle
            options = null
        }
        callbackToRun?.invoke(outcome)
    }

    private fun buildFinalOutcome(releaseResult: DirectReleaseResourcesResult): DirectSessionProbeOutcome {
        val safeOptions = options ?: return directFailure(DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED, "Direct proof options were lost before completion.")
        val frameProofs = collector?.snapshot().orEmpty().map { it.frame }
        val sensorCopy = synchronized(lock) { sensorTimestamps.toList() }
        val captureDiagnostics = buildCaptureDiagnostics(releaseResult, frameProofs.size, sensorCopy)
        if (releaseResult.failure != null) {
            return directFailure(
                reason = releaseResult.failure,
                message = "Direct proof stopped, but a resource release step failed.",
                directTimestampCount = frameProofs.size,
                sensorTimestampCount = sensorCopy.size,
                pixelProofCount = frameProofs.size,
                scratchCleanupStatus = releaseResult.scratchCleanupStatus,
                captureDiagnostics = captureDiagnostics,
            )
        }
        if (frameProofs.isEmpty()) {
            return directFailure(
                reason = DirectTimingSourceFailure.MISSING_DIRECT_TIMESTAMPS,
                message = "No direct source frames were consumed.",
                sensorTimestampCount = sensorCopy.size,
                scratchCleanupStatus = releaseResult.scratchCleanupStatus,
                captureDiagnostics = captureDiagnostics,
            )
        }
        return DirectSessionProbeOutcome.Success(
            shape = activeVariant.sessionShape,
            requestListSize = requestListSize.coerceAtLeast(1),
            sensorTimestampsNanos = sensorCopy,
            frames = frameProofs,
            scratchCleanupStatus = releaseResult.scratchCleanupStatus,
            captureDiagnostics = captureDiagnostics,
        )
    }

    private fun releaseResources(): DirectReleaseResourcesResult {
        val result = releaseDirectCaptureResourcesWithDiagnostics(
            DirectReleaseActions(
                stopRepeating = {
                    session?.stopRepeating()
                },
                closeSession = {
                    session?.close()
                },
                closeCamera = {
                    cameraDevice?.close()
                },
                releaseGl = {
                    glResources?.release()
                    imageReader?.close()
                },
                releaseCompanion = {
                    companionScratch?.releaseAndDelete()
                        ?: CompanionScratchCleanupResult(CompanionScratchCleanupStatus.ALREADY_ABSENT)
                },
                quitThread = {
                    handlerThread?.quitSafely()
                },
            ),
        )
        result.stepTimings.forEach { timing ->
            Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_RELEASE_STEP name=${timing.name} elapsedMs=${timing.elapsedMillis.formatMillisForDirectLog()} failed=${timing.failed}")
        }
        Log.i(DIRECT_CAPTURE_LOG_TAG, "DIRECT_CAPTURE_RELEASE_DONE failure=${result.failure} scratch=${result.scratchCleanupStatus} steps=${result.stepTimings.size}")
        session = null
        cameraDevice = null
        glResources = null
        imageReader = null
        companionScratch = null
        handler = null
        handlerThread = null
        return result
    }

    private fun DirectSessionProbeOutcome.Failure.withFinalCaptureContext(
        releaseResult: DirectReleaseResourcesResult,
    ): DirectSessionProbeOutcome.Failure {
        val frameProofs = collector?.snapshot().orEmpty().map { it.frame }
        val sensorCopy = synchronized(lock) { sensorTimestamps.toList() }
        return copy(
            requestListSize = if (this@DirectCompanionTimingProofCapture.requestListSize > 0) {
                this@DirectCompanionTimingProofCapture.requestListSize
            } else {
                this.requestListSize
            },
            directTimestampCount = directTimestampCount.coerceAtLeast(frameProofs.size),
            sensorTimestampCount = sensorTimestampCount.coerceAtLeast(sensorCopy.size),
            pixelProofCount = pixelProofCount.coerceAtLeast(frameProofs.size),
            scratchCleanupStatus = releaseResult.scratchCleanupStatus,
            captureDiagnostics = buildCaptureDiagnostics(releaseResult, frameProofs.size, sensorCopy),
        )
    }

    private fun buildCaptureDiagnostics(
        releaseResult: DirectReleaseResourcesResult,
        appendedFrameCount: Int,
        sensorTimestampsNanos: List<Long>,
    ): DirectCaptureDiagnostics {
        val readbacks = synchronized(lock) { readbackElapsedMillis.toList() }
        val safeOptions = options
        val producerGaps = sensorTimestampsNanos.orderedGapMillis()
        val producerGate = if (safeOptions != null) {
            evaluateProducerCadenceGate(
                requestedFps = safeOptions.mode.fps,
                medianGapMillis = producerGaps.medianOrNull(),
                maximumGapMillis = producerGaps.maxOrNull(),
            )
        } else {
            null
        }
        return DirectCaptureDiagnostics(
            variantId = activeVariant.id,
            consumerModel = activeVariant.consumerModel,
            surfaceOrder = activeVariant.surfaceOrder,
            requestTemplate = activeVariant.requestTemplate,
            directBufferWidth = safeOptions?.mode?.width,
            directBufferHeight = safeOptions?.mode?.height,
            requestListSize = requestListSize.takeIf { it > 0 },
            frameAvailableCallbackCount = frameAvailableCallbackCount.get(),
            captureResultCallbackCount = captureCallbackCount.get(),
            appendedDirectFrameCount = appendedFrameCount,
            readbackCount = readbacks.size,
            medianReadbackMillis = readbacks.medianOrNull(),
            maximumReadbackMillis = readbacks.maxOrNull(),
            producerMedianGapMillis = producerGate?.medianGapMillis,
            producerMaximumGapMillis = producerGate?.maximumGapMillis,
            producerInRequestedFpsBand = producerGate?.inRequestedFpsBand,
            consumerRatioInterpretable = producerGate?.let { canInterpretConsumerRatio(activeVariant, it) },
            imageAcquireNullCount = imageAcquireNullCount.get(),
            releaseStepTimings = releaseResult.stepTimings,
        )
    }

    private fun createDirectGlResources(
        mode: HighSpeedMode,
        handler: Handler,
        variant: DirectProofVariant,
    ): DirectGlReadbackResources? =
        runCatching {
            DirectGlReadbackResources.create(
                frameWidth = mode.width,
                frameHeight = mode.height,
                readbackWidth = DIRECT_READBACK_WIDTH,
                readbackHeight = DIRECT_READBACK_HEIGHT,
                readbackMode = directGlReadbackModeFor(variant),
            ) {
                handler.post { onFrameAvailable() }
            }
        }.getOrNull()

    @Suppress("NewApi")
    private fun createDirectImageReader(
        mode: HighSpeedMode,
        variant: DirectProofVariant,
        handler: Handler,
    ): ImageReader? =
        runCatching {
            val maxImages = variant.imageReaderMaxImages ?: DEFAULT_IMAGE_READER_MAX_IMAGES
            val reader = when (variant.consumerModel) {
                DirectProofConsumerModel.CONSTRAINED_PRIVATE_IMAGE_READER -> {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                        error("PRIVATE ImageReader usage flags require Android 10 or newer.")
                    }
                    ImageReader.newInstance(
                        mode.width,
                        mode.height,
                        ImageFormat.PRIVATE,
                        maxImages,
                        HardwareBuffer.USAGE_VIDEO_ENCODE,
                    )
                }

                DirectProofConsumerModel.CONSTRAINED_IMAGE_READER,
                DirectProofConsumerModel.STANDARD_IMAGE_READER,
                -> ImageReader.newInstance(
                    mode.width,
                    mode.height,
                    ImageFormat.YUV_420_888,
                    maxImages,
                )

                DirectProofConsumerModel.SINGLE_SURFACE_TEXTURE,
                DirectProofConsumerModel.PBO_GL_READBACK,
                -> error("Variant ${variant.id} does not use an ImageReader surface.")
            }
            reader.also { it.setOnImageAvailableListener({ onImageAvailable(it) }, handler) }
        }.getOrNull()

    private fun directFailure(
        reason: DirectTimingSourceFailure,
        message: String,
        requestListSize: Int = 0,
        directTimestampCount: Int = 0,
        sensorTimestampCount: Int = 0,
        pixelProofCount: Int = 0,
        scratchCleanupStatus: CompanionScratchCleanupStatus = CompanionScratchCleanupStatus.ALREADY_ABSENT,
        captureDiagnostics: DirectCaptureDiagnostics = DirectCaptureDiagnostics(
            variantId = activeVariant.id,
            consumerModel = activeVariant.consumerModel,
            surfaceOrder = activeVariant.surfaceOrder,
            requestTemplate = activeVariant.requestTemplate,
        ),
    ): DirectSessionProbeOutcome.Failure =
        com.speedball.app.capture.directFailure(
            reason = reason,
            message = message,
            shape = activeVariant.sessionShape,
            requestListSize = requestListSize,
            directTimestampCount = directTimestampCount,
            sensorTimestampCount = sensorTimestampCount,
            pixelProofCount = pixelProofCount,
            scratchCleanupStatus = scratchCleanupStatus,
            captureDiagnostics = captureDiagnostics,
        )
}

internal fun validateDirectProofVariant(variant: DirectProofVariant): DirectSessionProbeOutcome.Failure? =
    if (variant.surfaceOrder.any { it == DirectProofSurfaceRole.IMAGE_READER } && variant.imageReaderMaxImages == null) {
        DirectSessionProbeOutcome.Failure(
            shape = variant.sessionShape,
            reason = DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED,
            message = "ImageReader variant ${variant.id} requires maxImages evidence.",
            captureDiagnostics = DirectCaptureDiagnostics(
                variantId = variant.id,
                consumerModel = variant.consumerModel,
                surfaceOrder = variant.surfaceOrder,
                requestTemplate = variant.requestTemplate,
            ),
        )
    } else {
        null
    }

internal fun buildDirectVariantSurfaceList(
    variant: DirectProofVariant,
    companionSurface: Surface?,
    directSurface: Surface?,
    imageReaderSurface: Surface?,
): List<Surface> =
    variant.surfaceOrder.map { role ->
        when (role) {
            DirectProofSurfaceRole.COMPANION_ENCODER ->
                companionSurface ?: error("Variant ${variant.id} requires a companion encoder surface.")
            DirectProofSurfaceRole.DIRECT_GL_READBACK ->
                directSurface ?: error("Variant ${variant.id} requires a direct GL surface.")
            DirectProofSurfaceRole.IMAGE_READER ->
                imageReaderSurface ?: error("Variant ${variant.id} requires an ImageReader surface.")
        }
    }

internal fun buildGlVariantSurfaceList(
    variant: DirectProofVariant,
    companionSurface: Surface?,
    directSurface: Surface,
): List<Surface> =
    buildDirectVariantSurfaceList(variant, companionSurface, directSurface, null)

internal fun buildGlVariantSurfaceRoles(variant: DirectProofVariant): List<DirectProofSurfaceRole> =
    variant.surfaceOrder.onEach { role ->
        require(role != DirectProofSurfaceRole.IMAGE_READER) {
            "Variant ${variant.id} requires ImageReader surface wiring."
        }
    }

private class AndroidImageReaderSnapshot(
    private val image: Image,
) : DirectImageReaderSnapshot {
    override val timestampNanos: Long
        get() = image.timestamp

    override val width: Int
        get() = image.width

    override val height: Int
        get() = image.height

    override fun readArgbTile(
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ): IntArray {
        require(image.format == ImageFormat.YUV_420_888) { "Unsupported ImageReader format ${image.format}." }
        require(left >= 0 && top >= 0 && width > 0 && height > 0) { "Tile bounds must be positive." }
        require(left + width <= image.width && top + height <= image.height) { "Tile must fit in acquired image." }
        val yPlane = image.planes.firstOrNull() ?: error("ImageReader image had no Y plane.")
        val rowStride = yPlane.rowStride
        val pixelStride = yPlane.pixelStride
        require(rowStride > 0 && pixelStride > 0) { "ImageReader Y plane strides must be positive." }
        val buffer = yPlane.buffer.duplicate()
        val argb = IntArray(width * height)
        for (tileY in 0 until height) {
            for (tileX in 0 until width) {
                val sourceIndex = (top + tileY) * rowStride + (left + tileX) * pixelStride
                require(sourceIndex < buffer.limit()) { "ImageReader Y plane stride exceeded buffer limit." }
                val luma = buffer.get(sourceIndex).toInt() and 0xff
                argb[tileY * width + tileX] = (0xff shl 24) or (luma shl 16) or (luma shl 8) or luma
            }
        }
        return argb
    }

    override fun close() {
        image.close()
    }
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
    shape: DirectProofSessionShape = DirectProofSessionShape.COMPANION_ENCODER,
    requestListSize: Int = 0,
    directTimestampCount: Int = 0,
    sensorTimestampCount: Int = 0,
    pixelProofCount: Int = 0,
    scratchCleanupStatus: CompanionScratchCleanupStatus = CompanionScratchCleanupStatus.ALREADY_ABSENT,
    captureDiagnostics: DirectCaptureDiagnostics = DirectCaptureDiagnostics(),
): DirectSessionProbeOutcome.Failure =
    DirectSessionProbeOutcome.Failure(
        shape = shape,
        reason = reason,
        message = message,
        requestListSize = requestListSize,
        directTimestampCount = directTimestampCount,
        sensorTimestampCount = sensorTimestampCount,
        pixelProofCount = pixelProofCount,
        scratchCleanupStatus = scratchCleanupStatus,
        captureDiagnostics = captureDiagnostics,
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

internal data class DirectReleaseResourcesResult(
    val failure: DirectTimingSourceFailure?,
    val scratchCleanupStatus: CompanionScratchCleanupStatus,
    val stepTimings: List<DirectReleaseStepTiming>,
)

internal fun releaseDirectCaptureResources(actions: DirectReleaseActions): DirectTimingSourceFailure? =
    releaseDirectCaptureResourcesWithDiagnostics(actions).failure

internal fun releaseDirectCaptureResourcesWithDiagnostics(actions: DirectReleaseActions): DirectReleaseResourcesResult {
    var releaseFailed = false
    var scratchCleanupFailed = false
    var scratchCleanupStatus = CompanionScratchCleanupStatus.ALREADY_ABSENT
    val timings = mutableListOf<DirectReleaseStepTiming>()

    fun runStep(name: String, action: () -> Unit): Boolean {
        val start = System.nanoTime()
        var failed = false
        runCatching { action() }.onFailure {
            failed = true
        }
        timings += DirectReleaseStepTiming(name, elapsedMillis(start), failed)
        return failed
    }

    if (runStep("stopRepeating", actions.stopRepeating)) releaseFailed = true
    if (runStep("closeSession", actions.closeSession)) releaseFailed = true
    if (runStep("closeCamera", actions.closeCamera)) releaseFailed = true
    if (runStep("releaseGl", actions.releaseGl)) releaseFailed = true
    val companionStart = System.nanoTime()
    var companionFailed = false
    runCatching {
        scratchCleanupStatus = actions.releaseCompanion().status
        scratchCleanupFailed = scratchCleanupStatus == CompanionScratchCleanupStatus.FAILED
    }.onFailure {
        companionFailed = true
        scratchCleanupFailed = true
        scratchCleanupStatus = CompanionScratchCleanupStatus.FAILED
    }
    timings += DirectReleaseStepTiming("releaseCompanion", elapsedMillis(companionStart), companionFailed || scratchCleanupFailed)
    if (runStep("quitThread", actions.quitThread)) releaseFailed = true

    val failure = when {
        scratchCleanupFailed -> DirectTimingSourceFailure.SCRATCH_FILE_CLEANUP_FAILED
        releaseFailed -> DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED
        else -> null
    }
    return DirectReleaseResourcesResult(
        failure = failure,
        scratchCleanupStatus = scratchCleanupStatus,
        stepTimings = timings,
    )
}

internal fun directTargetProofFrames(): Int = DIRECT_TARGET_PROOF_FRAMES

internal enum class DirectGlReadbackMode {
    INLINE_READ_PIXELS,
    PBO_ASYNC_READBACK,
}

private data class PendingPboReadback(
    val slot: Int,
    val timestampNanos: Long,
)

internal fun directGlReadbackModeFor(variant: DirectProofVariant): DirectGlReadbackMode =
    when (variant.consumerModel) {
        DirectProofConsumerModel.PBO_GL_READBACK -> DirectGlReadbackMode.PBO_ASYNC_READBACK
        DirectProofConsumerModel.SINGLE_SURFACE_TEXTURE -> DirectGlReadbackMode.INLINE_READ_PIXELS
        DirectProofConsumerModel.CONSTRAINED_IMAGE_READER,
        DirectProofConsumerModel.CONSTRAINED_PRIVATE_IMAGE_READER,
        DirectProofConsumerModel.STANDARD_IMAGE_READER,
        -> error("Variant ${variant.id} does not use a GL readback surface.")
    }

private const val DIRECT_READBACK_WIDTH = 2
private const val DIRECT_READBACK_HEIGHT = 2
private const val DEFAULT_IMAGE_READER_MAX_IMAGES = 3
private const val MAX_DIRECT_PROOF_FRAMES = 360
private const val DIRECT_TARGET_PROOF_FRAMES = MIN_DIRECT_PROOF_TOKEN_FRAMES * 2
private const val DIRECT_CAPTURE_LOG_TAG = "SPEEDBALL_CAPTURE"
private val DIRECT_CAPTURE_PIXEL_CONFIG = DirectPixelProofConfig(
    maxTotalSamples = DIRECT_READBACK_WIDTH * DIRECT_READBACK_HEIGHT * MAX_DIRECT_PROOF_FRAMES,
    requireInterFrameVariation = true,
)

private fun String.sanitizedDirectCaptureLogToken(): String =
    replace(Regex("\\s+"), "_")

private fun elapsedMillis(startNanos: Long): Double =
    (System.nanoTime() - startNanos).coerceAtLeast(0L) / 1_000_000.0

private fun Double.formatMillisForDirectLog(): String =
    "%.3f".format(this)

private fun List<Double>.medianOrNull(): Double? {
    if (isEmpty()) return null
    val sorted = sorted()
    val middle = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[middle - 1] + sorted[middle]) / 2.0
    } else {
        sorted[middle]
    }
}

private fun List<Long>.orderedGapMillis(): List<Double> =
    asSequence()
        .filter { it > 0L }
        .distinct()
        .sorted()
        .toList()
        .zipWithNext { left, right -> (right - left).coerceAtLeast(0L) / 1_000_000.0 }

/**
 * GL resources for direct same-`updateTexImage()` readback.
 *
 * Inline mode reads a bounded RGBA aggregate immediately after one
 * `updateTexImage()` call. PBO mode issues an asynchronous pixel-pack read and
 * maps the previous frame's PBO on the next callback so CPU readback is not
 * serialized before the BufferQueue can advance.
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
    private val readbackMode: DirectGlReadbackMode,
    private val pboIds: IntArray,
    val surfaceTexture: SurfaceTexture,
    val surface: Surface,
) {
    private var nextPboSlot = 0
    private var pendingPbo: PendingPboReadback? = null
    val readbackSourceLabel: String =
        when (readbackMode) {
            DirectGlReadbackMode.INLINE_READ_PIXELS -> "GL"
            DirectGlReadbackMode.PBO_ASYNC_READBACK -> "PBO_GL"
        }

    fun makeCurrent() {
        EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
    }

    fun updateAndReadSignatures(): List<DirectPixelProofSignature> {
        makeCurrent()
        val readySignature = if (readbackMode == DirectGlReadbackMode.PBO_ASYNC_READBACK) {
            consumePendingPboFrame()?.toPixelProofSignature()
        } else {
            null
        }
        surfaceTexture.updateTexImage()
        val timestamp = surfaceTexture.timestamp
        drawCurrentFrame()
        return when (readbackMode) {
            DirectGlReadbackMode.INLINE_READ_PIXELS -> listOf(readInlineArgbFrame(timestamp).toPixelProofSignature())
            DirectGlReadbackMode.PBO_ASYNC_READBACK -> {
                issuePboReadback(timestamp)
                listOfNotNull(readySignature)
            }
        }
    }

    fun updateAndReadArgbFrames(): List<DirectArgbFrame> {
        check(readbackMode == DirectGlReadbackMode.INLINE_READ_PIXELS) {
            "Direct ARGB estimate frames require inline readback."
        }
        makeCurrent()
        surfaceTexture.updateTexImage()
        val timestamp = surfaceTexture.timestamp
        drawCurrentFrame()
        return listOf(readInlineArgbFrame(timestamp))
    }

    private fun drawCurrentFrame() {
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
    }

    private fun readInlineRgbaBuffer(): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(readbackWidth * readbackHeight * 4).order(ByteOrder.nativeOrder())
        GLES20.glReadPixels(0, 0, readbackWidth, readbackHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
        buffer.rewind()
        return buffer
    }

    private fun issuePboReadback(timestamp: Long) {
        val ids = pboIds
        check(ids.isNotEmpty()) { "PBO readback resources are unavailable." }
        val slot = nextPboSlot
        val pboId = ids[slot]
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboId)
        GLES30.glBufferData(GLES30.GL_PIXEL_PACK_BUFFER, readbackWidth * readbackHeight * 4, null, GLES30.GL_STREAM_READ)
        GLES30.glReadPixels(0, 0, readbackWidth, readbackHeight, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, 0)
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        pendingPbo = PendingPboReadback(slot = slot, timestampNanos = timestamp)
        nextPboSlot = (slot + 1) % ids.size
    }

    private fun consumePendingPboFrame(): DirectArgbFrame? {
        val pending = pendingPbo ?: return null
        val pboId = pboIds[pending.slot]
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, pboId)
        val mapped = GLES30.glMapBufferRange(
            GLES30.GL_PIXEL_PACK_BUFFER,
            0,
            readbackWidth * readbackHeight * 4,
            GLES30.GL_MAP_READ_BIT,
        ) as? ByteBuffer ?: error("PBO readback map failed.")
        val copy = ByteBuffer.allocateDirect(readbackWidth * readbackHeight * 4).order(ByteOrder.nativeOrder())
        mapped.limit(readbackWidth * readbackHeight * 4)
        mapped.position(0)
        copy.put(mapped)
        copy.rewind()
        check(GLES30.glUnmapBuffer(GLES30.GL_PIXEL_PACK_BUFFER)) { "PBO readback unmap failed." }
        GLES30.glBindBuffer(GLES30.GL_PIXEL_PACK_BUFFER, 0)
        pendingPbo = null
        return buildFrameFromRgba(pending.timestampNanos, copy)
    }

    private fun readInlineArgbFrame(timestamp: Long): DirectArgbFrame =
        buildFrameFromRgba(timestamp, readInlineRgbaBuffer())

    private fun DirectArgbFrame.toPixelProofSignature(): DirectPixelProofSignature =
        buildDirectPixelProofSignature(
            timestampNanos = timestampNanos,
            frameWidth = frameWidth,
            frameHeight = frameHeight,
            tileLeft = 0,
            tileTop = 0,
            tileWidth = width,
            tileHeight = height,
            argbPixels = argbPixels,
        )

    private fun buildFrameFromRgba(
        timestamp: Long,
        buffer: ByteBuffer,
    ): DirectArgbFrame {
        val argb = IntArray(readbackWidth * readbackHeight)
        for (index in argb.indices) {
            val red = buffer.get().toInt() and 0xff
            val green = buffer.get().toInt() and 0xff
            val blue = buffer.get().toInt() and 0xff
            val alpha = buffer.get().toInt() and 0xff
            argb[index] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return DirectArgbFrame(
            timestampNanos = timestamp,
            width = readbackWidth,
            height = readbackHeight,
            argbPixels = argb,
        )
    }

    fun release() {
        runCatching { surface.release() }
        runCatching { surfaceTexture.release() }
        if (pboIds.isNotEmpty()) {
            runCatching { GLES30.glDeleteBuffers(pboIds.size, pboIds, 0) }
        }
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
            readbackMode: DirectGlReadbackMode,
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
                if (readbackMode == DirectGlReadbackMode.PBO_ASYNC_READBACK) {
                    EGLExt.EGL_OPENGL_ES3_BIT_KHR
                } else {
                    EGL14.EGL_OPENGL_ES2_BIT
                },
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
                intArrayOf(
                    EGL14.EGL_CONTEXT_CLIENT_VERSION,
                    if (readbackMode == DirectGlReadbackMode.PBO_ASYNC_READBACK) 3 else 2,
                    EGL14.EGL_NONE,
                ),
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
            val pboIds = createPboIds(readbackMode)
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
                readbackMode = readbackMode,
                pboIds = pboIds,
                surfaceTexture = surfaceTexture,
                surface = Surface(surfaceTexture),
            )
        }

        private fun createPboIds(readbackMode: DirectGlReadbackMode): IntArray {
            if (readbackMode != DirectGlReadbackMode.PBO_ASYNC_READBACK) return IntArray(0)
            val ids = IntArray(2)
            GLES30.glGenBuffers(ids.size, ids, 0)
            check(ids.all { it != 0 }) { "PBO buffer creation failed." }
            return ids
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
