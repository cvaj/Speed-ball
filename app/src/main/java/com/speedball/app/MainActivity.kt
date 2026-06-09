package com.speedball.app

import android.Manifest
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import android.view.PixelCopy
import android.view.Surface
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.speedball.app.capture.BurstDiagnostics
import com.speedball.app.capture.BurstCompanionSurfaceMode
import com.speedball.app.capture.BurstFailure
import com.speedball.app.capture.BurstFrameAnchor
import com.speedball.app.capture.BurstOptions
import com.speedball.app.capture.BurstOutcome
import com.speedball.app.capture.BurstStopMode
import com.speedball.app.capture.CameraLiveFeedController
import com.speedball.app.capture.AudioVideoClockAnchor
import com.speedball.app.capture.ContainerTimeWindow
import com.speedball.app.capture.DirectCompanionTimingProofCapture
import com.speedball.app.capture.DirectPixelProofConfig
import com.speedball.app.capture.DirectPreviewControlReport
import com.speedball.app.capture.DirectProofRunId
import com.speedball.app.capture.DirectProofSessionShape
import com.speedball.app.capture.DirectProofVariant
import com.speedball.app.capture.DirectSessionProbeOutcome
import com.speedball.app.capture.DirectTimingSourceFailure
import com.speedball.app.capture.DirectTimingSourceProofOutcome
import com.speedball.app.capture.DirectTimingSourceProofRunResult
import com.speedball.app.capture.DirectTimingSourceProofRunner
import com.speedball.app.capture.DirectVisualEstimateCapture
import com.speedball.app.capture.DirectVisualEstimateCaptureConfig
import com.speedball.app.capture.DirectVisualEstimateCaptureOutcome
import com.speedball.app.capture.DeviceLevelReferenceCapture
import com.speedball.app.capture.HighSpeedBurstRecorder
import com.speedball.app.capture.HighSpeedCamera
import com.speedball.app.capture.HighSpeedMode
import com.speedball.app.capture.HighSpeedModesResult
import com.speedball.app.capture.ImpactWindowMapper
import com.speedball.app.capture.ImpactWindowMapping
import com.speedball.app.capture.ImpactWindowRequest
import com.speedball.app.capture.PreviewFrameOutcome
import com.speedball.app.capture.DEFAULT_DIRECT_VISUAL_ESTIMATE_MAX_FRAMES
import com.speedball.app.capture.DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT
import com.speedball.app.capture.DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH
import com.speedball.app.capture.PreviewTimestampSpikeCapture
import com.speedball.app.capture.companionGlBaselineVariant
import com.speedball.app.capture.plannedDirectProofVariants
import com.speedball.app.capture.previewFrameDiagnosticLogLines
import com.speedball.app.capture.selectDefaultMode
import com.speedball.app.capture.timestampSourceLogLine
import com.speedball.app.capture.exposureSummary
import com.speedball.app.audio.AndroidImpactAudioTrigger
import com.speedball.app.audio.ImpactAudioArmedSession
import com.speedball.app.audio.ImpactAudioEvent
import com.speedball.app.audio.ImpactAudioTriggerConfig
import com.speedball.app.audio.ImpactAudioTriggerResult
import com.speedball.app.decode.BurstVideoDecoder
import com.speedball.app.decode.DecodeCompletionGate
import com.speedball.app.decode.DecodeFailure
import com.speedball.app.decode.DecodeOutcome
import com.speedball.app.decode.ReconciliationDiagnostics
import com.speedball.app.decode.TimestampAnchorOutcome
import com.speedball.app.decode.buildDecodeWorkBounds
import com.speedball.app.decode.runDecodeWithTimeout
import com.speedball.app.decode.timestampAnchorDiagnosticLogLines
import com.speedball.app.importing.AndroidImportVideoFrameSource
import com.speedball.app.importing.AndroidRecordedHfrWindowFrameSource
import com.speedball.app.importing.ImportContentAccess
import com.speedball.app.importing.ImportContentSelection
import com.speedball.app.importing.ImportCancellationSignal
import com.speedball.app.importing.ImportEstimatePipeline
import com.speedball.app.importing.ImportEvidenceExporter
import com.speedball.app.importing.ImportEvidenceSummary
import com.speedball.app.importing.ImportFrameExtractionConfig
import com.speedball.app.importing.ImportFrameExtractor
import com.speedball.app.importing.ImportNoReadReason
import com.speedball.app.importing.ImportResultSourceKind
import com.speedball.app.importing.ImportTimingBasis
import com.speedball.app.importing.ImportTimingReconciliation
import com.speedball.app.importing.ImportTimingReconciler
import com.speedball.app.importing.ImportValidationResult
import com.speedball.app.importing.ImportVideoMetadata
import com.speedball.app.importing.ImportVideoFrameSequence
import com.speedball.app.importing.ImportWorkflowEvent
import com.speedball.app.importing.ImportWorkflowState
import com.speedball.app.importing.RecordedHfrByteBufferViabilityProbe
import com.speedball.app.importing.RecordedHfrCaptureGate
import com.speedball.app.importing.RecordedHfrDecodedWindowProof
import com.speedball.app.importing.RecordedHfrDecodedWindowValidator
import com.speedball.app.importing.RecordedHfrMotionScoutConfig
import com.speedball.app.importing.RecordedHfrMotionScoutSelection
import com.speedball.app.importing.RecordedHfrStreamingEstimate
import com.speedball.app.importing.RecordedHfrStreamingEstimateConfig
import com.speedball.app.importing.RecordedHfrStreamingTimingMode
import com.speedball.app.importing.RecordedHfrRetentionPolicy
import com.speedball.app.importing.RecordedHfrWindowCaptureGate
import com.speedball.app.importing.RecordedHfrWorkingResolution
import com.speedball.app.importing.RecordedHfrWorkingResolutionSelector
import com.speedball.app.importing.SavedResultHistory
import com.speedball.app.importing.SavedResultSummary
import com.speedball.app.importing.SavedResultSummaryStore
import com.speedball.app.measurement.CalibrationWorkflowState
import com.speedball.app.measurement.BlobDetectionConfig
import com.speedball.app.measurement.CandidateReductionBudget
import com.speedball.app.measurement.ColorWorkflowReadiness
import com.speedball.app.measurement.ColorWorkflowState
import com.speedball.app.measurement.ColorMath
import com.speedball.app.measurement.FrameProcessingBounds
import com.speedball.app.measurement.FrameDimensions
import com.speedball.app.measurement.HsvColor
import com.speedball.app.measurement.HsvThreshold
import com.speedball.app.measurement.HsvTolerance
import com.speedball.app.measurement.ExpectedBallSizePx
import com.speedball.app.measurement.LevelReferenceDisplayRotation
import com.speedball.app.measurement.LevelReferenceOutcome
import com.speedball.app.measurement.LevelReferenceSnapshot
import com.speedball.app.measurement.LevelReferenceSource
import com.speedball.app.measurement.MeasurementCalibrationState
import com.speedball.app.measurement.MinimumSpeedGatePolicy
import com.speedball.app.measurement.MeasurementWorkflowState
import com.speedball.app.measurement.NormalizedFramePoint
import com.speedball.app.measurement.NormalizedFramePolygon
import com.speedball.app.measurement.NormalizedFrameRect
import com.speedball.app.measurement.Phase14Geometry
import com.speedball.app.measurement.Phase14SetupMode
import com.speedball.app.measurement.Phase14WorkflowEvent
import com.speedball.app.measurement.Phase14WorkflowState
import com.speedball.app.measurement.PreviewFrameTransform
import com.speedball.app.measurement.PreviewScaleMode
import com.speedball.app.measurement.RegionOfInterest
import com.speedball.app.measurement.RecordedHfrMotionDetectorConfig
import com.speedball.app.measurement.TrackExtractionConfig
import com.speedball.app.measurement.VisualEstimateCaptureProof
import com.speedball.app.measurement.VisualEstimateCaptureProofBuilder
import com.speedball.app.measurement.VisualEstimateFramePipelineConfig
import com.speedball.app.measurement.VisualEstimateFrameTiming
import com.speedball.app.measurement.VisualEstimateNoReadReason
import com.speedball.app.measurement.VisualEstimateOutcome
import com.speedball.app.measurement.VisualEstimatePipelineConfig
import com.speedball.app.measurement.VisualEstimateScaleMode
import com.speedball.app.measurement.parsePositiveFeet
import com.speedball.app.ui.SpeedBallApp
import com.speedball.app.ui.SpeedBallAppMode
import com.speedball.app.ui.SpeedBallRunCommandState
import com.speedball.app.ui.SpeedBallShellState
import com.speedball.app.ui.VisualEstimateReport
import com.speedball.app.ui.VisualEstimateReportKind
import com.speedball.app.ui.decodeOutcomeUiLines
import com.speedball.app.ui.directProofRunUiLines
import com.speedball.app.ui.previewOutcomeUiLines
import com.speedball.app.ui.speedBallCaptureState
import com.speedball.app.ui.speedBallPlaceholderState
import com.speedball.app.ui.visualEstimateOutcomeUiLines
import com.speedball.app.ui.visualEstimateReportFor
import com.speedball.core.calibration.CalibrationResult
import com.speedball.core.model.ImagePoint
import java.io.File
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

/** Launches the Compose shell and owns Camera2 capture plus Phase 5 decode diagnostics. */
class MainActivity : ComponentActivity() {
    private val logTag = "SPEEDBALL_CAPTURE"
    private lateinit var highSpeedCamera: HighSpeedCamera
    private lateinit var burstRecorder: HighSpeedBurstRecorder
    private lateinit var previewSpikeCapture: PreviewTimestampSpikeCapture
    private lateinit var directProofCapture: DirectCompanionTimingProofCapture
    private lateinit var directVisualEstimateCapture: DirectVisualEstimateCapture
    private lateinit var levelReferenceCapture: DeviceLevelReferenceCapture
    private lateinit var cameraLiveFeedController: CameraLiveFeedController
    private lateinit var impactAudioTrigger: AndroidImpactAudioTrigger
    private val burstVideoDecoder = BurstVideoDecoder()
    private val decodeCompletionGate = DecodeCompletionGate()
    private val decodeSupervisorExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val decodeWorkerExecutor: ExecutorService = Executors.newCachedThreadPool()
    private val directProofExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var previewSurface: Surface? = null
    private var modes: List<HighSpeedMode> = emptyList()
    private var selectedMode: HighSpeedMode? = null
    private var lastDiagnostics: BurstDiagnostics? = null
    private var lastDecodeOutcome: DecodeOutcome? = null
    private var lastPreviewOutcome: PreviewFrameOutcome? = null
    private var lastDirectProofResult: DirectTimingSourceProofRunResult? = null
    private var lastDirectVisualEstimateOutcome: VisualEstimateOutcome? = null
    private var lastFailure: BurstOutcome.Failure? = null
    private var calibrationWorkflowState = CalibrationWorkflowState()
    private var colorWorkflowState = ColorWorkflowState()
    private var measurementWorkflowState = MeasurementWorkflowState()
    private var phase14WorkflowState = Phase14WorkflowState()
    private var setupAdjustmentTarget = SetupAdjustmentTarget.CaliperA
    private var knownDistanceFeetText = INITIAL_KNOWN_DISTANCE_FEET_TEXT
    private var calibrationPlaneDepthFeetText = INITIAL_CALIBRATION_PLANE_DEPTH_FEET_TEXT
    private var ballPlaneDepthFeetText = INITIAL_BALL_PLANE_DEPTH_FEET_TEXT
    private var motionBlobSideRatioText = INITIAL_MOTION_BLOB_SIDE_RATIO_TEXT
    private var launchHeightFeetText = INITIAL_LAUNCH_HEIGHT_FEET_TEXT
    private var importWorkflowState = ImportWorkflowState()
    private lateinit var savedResultStore: SavedResultSummaryStore
    private var savedResultHistory = SavedResultHistory()
    private var lastImportExportText: String? = null
    private var captureStatus: String = "Idle"
    private var shellState by mutableStateOf(speedBallPlaceholderState())
    private var autoStartPending = false
    private var autoStartPreviewPending = false
    private var autoStartDirectProofPending = false
    private var autoStartDirectVisualEstimatePending = false
    private var autoLogTimestampSourcePending = false
    private var levelCaptureInProgress = false
    private var liveLevelReferenceActive = false
    private var readySignalPending = false
    private var requestedDirectProofVariantId: String? = null
    private var debugVisualEstimateKnownBallDiameterFeet: Double? = null
    private var debugImportKnownFrameRateFps: Double? = null
    private var debugImportStartFrameIndex: Int = 0
    private var debugImportFrameCount: Int = DEFAULT_IMPORT_MAX_FRAMES
    private var voiceRecordListening = false
    private var voiceConsecutiveErrors = 0
    private var voiceRestartRunnable: Runnable? = null
    private var reportAutoClearRunnable: Runnable? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private var readySpeech: TextToSpeech? = null
    private var readySpeechReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private var activityResumed = false
    private var windowFocused = false
    private var previewRotationDegrees = 0
    private var appMode = SpeedBallAppMode.Setup
    private var runCommandState: SpeedBallRunCommandState = SpeedBallRunCommandState.SetupInvalid("Setup not ready")
    private var visualEstimateAttemptId = 0L
    private var currentVisualEstimateReport: VisualEstimateReport? = null
    private var dismissedVisualEstimateAttemptId: Long? = null
    @Volatile private var activeImpactMapping: ImpactWindowMapping? = null
    @Volatile private var activeImpactEvent: ImpactAudioEvent? = null
    @Volatile private var activeImpactNoRead: String? = null
    @Volatile private var activeImpactAudioSession: ImpactAudioArmedSession? = null
    @Volatile private var activeImpactVideoAnchor: BurstFrameAnchor? = null

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateShellState(status = if (it) "Permission granted" else "Permission denied")
        if (it) refreshModes()
    }

    private val requestAudioPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) {
            startVoiceRecordListener()
        } else {
            voiceRecordListening = false
            runCommandState = SpeedBallRunCommandState.VoiceUnavailable
            updateShellState(status = "Microphone permission denied")
        }
    }

    private val importDocumentPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            importWorkflowState = importWorkflowState.reduce(ImportWorkflowEvent.Cancel)
            updateShellState(status = "Import cancelled")
        } else {
            handleImportSelection(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureCameraWindow()
        highSpeedCamera = HighSpeedCamera(this)
        burstRecorder = HighSpeedBurstRecorder(this)
        previewSpikeCapture = PreviewTimestampSpikeCapture(this)
        directProofCapture = DirectCompanionTimingProofCapture(this)
        directVisualEstimateCapture = DirectVisualEstimateCapture(this)
        levelReferenceCapture = DeviceLevelReferenceCapture(this)
        cameraLiveFeedController = CameraLiveFeedController(this)
        impactAudioTrigger = AndroidImpactAudioTrigger(this)
        readySpeech = TextToSpeech(this) { status ->
            readySpeechReady = status == TextToSpeech.SUCCESS
            if (readySpeechReady) {
                readySpeech?.language = Locale.getDefault()
            }
        }
        savedResultStore = SavedResultSummaryStore(File(filesDir, SAVED_RESULTS_FILE_NAME))
        when (val loaded = savedResultStore.load()) {
            is ImportValidationResult.Success -> savedResultHistory = loaded.value
            is ImportValidationResult.NoRead -> {
                savedResultHistory = SavedResultHistory()
                Log.e(logTag, "SAVED_RESULT_STORE_FAILURE reason=${loaded.reason} message=${loaded.message.compactForLog()}")
            }
        }
        autoStartPending = intent.getBooleanExtra("autoStart120", false)
        autoStartPreviewPending = intent.getBooleanExtra("autoStartPreview120", false)
        autoStartDirectProofPending = intent.getBooleanExtra("autoStartDirectProof120", false)
        autoStartDirectVisualEstimatePending = intent.getBooleanExtra("autoStartVisualEstimate120", false)
        autoLogTimestampSourcePending = intent.getBooleanExtra("autoLogTimestampSource", false) && isDebuggableBuild()
        requestedDirectProofVariantId = intent.getStringExtra("directProofVariant")
        applyDebugVisualEstimateSetupFromIntent()
        configurePreviewProofWindow()
        updateShellState(status = "Idle")
        setContent {
            val state: SpeedBallShellState = shellState
            SpeedBallApp(
                state = state,
                onPreviewSurface = {
                    previewSurface = it
                    if (it == null) {
                        cameraLiveFeedController.stop()
                    } else {
                        startLiveCameraFeedIfReady()
                    }
                    startAutoDirectVisualEstimateIfReady()
                    startAutoBurstIfReady()
                },
                onKnownDistanceChanged = { updateKnownDistanceFeetInput(it) },
                onCalibrationPlaneDepthChanged = { updateCalibrationPlaneDepthFeetInput(it) },
                onBallPlaneDepthChanged = { updateBallPlaneDepthFeetInput(it) },
                onMotionBlobSideRatioChanged = { updateMotionBlobSideRatioInput(it) },
                onLaunchHeightChanged = { updateLaunchHeightFeetInput(it) },
                onPreviewTap = { x, y, width, height -> handlePreviewTap(x, y, width, height) },
                onSetCaliperAFromPreview = { x, width, height -> setCaliperLineFromPreview(SetupAdjustmentTarget.CaliperA, x, width, height) },
                onSetCaliperBFromPreview = { x, width, height -> setCaliperLineFromPreview(SetupAdjustmentTarget.CaliperB, x, width, height) },
                onRequestPermission = { ensureCameraPermission() },
                onRefreshModes = { refreshModes() },
                onUseKnownDistance = { useKnownDistanceSetup() },
                onUseBallDiameterFallback = { useBallDiameterFallbackSetup() },
                onCaptureLevel = { captureLevelReferenceForSetup() },
                onSampleColor = { sampleDefaultEstimateColor() },
                onSelectCaliperA = { selectSetupAdjustmentTarget(SetupAdjustmentTarget.CaliperA) },
                onSelectCaliperB = { selectSetupAdjustmentTarget(SetupAdjustmentTarget.CaliperB) },
                onSelectColorPoint = { selectSetupAdjustmentTarget(SetupAdjustmentTarget.ColorPoint) },
                onSelectImpactZone = { selectSetupAdjustmentTarget(SetupAdjustmentTarget.ImpactZone) },
                onSelectBallBox = { selectSetupAdjustmentTarget(SetupAdjustmentTarget.BallBox) },
                onSetImpactZoneCornerFromPreview = { corner, x, y, width, height ->
                    setImpactZoneCornerFromPreview(corner, x, y, width, height)
                },
                onSetBallBoxCornerFromPreview = { corner, x, y, width, height ->
                    setBallBoxCornerFromPreview(corner, x, y, width, height)
                },
                onNudgeSetup = { dx, dy -> nudgeSelectedSetupTarget(dx, dy) },
                onFineNudgeSetup = { dx, dy -> nudgeSelectedSetupTarget(dx, dy) },
                onPickImport = { requestImportVideo() },
                onArmEstimate = { armDirectVisualEstimate() },
                onVoiceRecord = { toggleVoiceRecordListener() },
                onStartBurst = { startBurst() },
                onStartVisualEstimate = { startDirectVisualEstimate() },
                onRetryEstimate = { retryDirectVisualEstimate() },
                onRecalibrateEstimate = { recalibrateDirectVisualEstimate() },
                onStopBurst = { stopBurst() },
                onEnterRunMode = { enterRunMode() },
                onEnterSetupMode = { enterSetupMode() },
                onManualShoot = { handleShootCommand(ShootTrigger.Manual) },
                onDismissVisualEstimateReport = { dismissVisualEstimateReport(it) },
            )
        }
        logTimestampSourceIfRequested()
        startRecordedHfrByteBufferProbeIfRequested()
        startRecordedHfrWindowSourceProbeIfRequested()
        startDebugImportIfRequested()
        if (!hasRecordedHfrDebugProbeIntent() && (hasCameraPermission() || autoStartPending || autoStartPreviewPending || autoStartDirectProofPending || autoStartDirectVisualEstimatePending)) {
            refreshModes()
        }
    }

    override fun onResume() {
        super.onResume()
        activityResumed = true
        startLiveCameraFeedIfReady()
        startLiveLevelReferenceIfReady()
        startAutoPreviewIfReady()
        startAutoDirectProofIfReady()
        startAutoDirectVisualEstimateIfReady()
    }

    override fun onPause() {
        activityResumed = false
        cameraLiveFeedController.stop()
        stopLiveLevelReference()
        stopVoiceRecordListener()
        stopBurst()
        stopPreviewSpike()
        stopDirectProof()
        stopDirectVisualEstimate()
        levelReferenceCapture.stop()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        windowFocused = hasFocus
        if (hasFocus) configureCameraWindow()
        if (hasFocus) startAutoPreviewIfReady()
        if (hasFocus) startAutoDirectProofIfReady()
        if (hasFocus) startAutoDirectVisualEstimateIfReady()
    }

    override fun onStop() {
        cameraLiveFeedController.stop()
        stopLiveLevelReference()
        stopVoiceRecordListener()
        stopBurst()
        stopPreviewSpike()
        stopDirectProof()
        stopDirectVisualEstimate()
        levelReferenceCapture.stop()
        super.onStop()
    }

    override fun onDestroy() {
        cameraLiveFeedController.stop()
        stopLiveLevelReference()
        stopVoiceRecordListener()
        impactAudioTrigger.cancel()
        readySpeech?.shutdown()
        readySpeech = null
        readySpeechReady = false
        levelReferenceCapture.stop()
        decodeSupervisorExecutor.shutdownNow()
        decodeWorkerExecutor.shutdownNow()
        directProofExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun ensureCameraPermission() {
        if (hasCameraPermission()) {
            updateShellState(status = "Permission granted")
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    private fun toggleVoiceRecordListener() {
        if (voiceRecordListening) {
            stopVoiceRecordListener()
            runCommandState = SpeedBallRunCommandState.ManualReady
            updateShellState(status = "Voice trigger off")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestAudioPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        startVoiceRecordListener()
    }

    private fun enterRunMode() {
        appMode = SpeedBallAppMode.Run
        val notReadyStatus = shootNotReadyStatus()
        if (notReadyStatus != null) {
            runCommandState = SpeedBallRunCommandState.SetupInvalid(notReadyStatus)
            updateShellState(status = notReadyStatus)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecordListener()
        } else {
            voiceRecordListening = false
            runCommandState = SpeedBallRunCommandState.ManualReady
            updateShellState(status = "Run mode manual ready")
        }
    }

    private fun enterSetupMode() {
        appMode = SpeedBallAppMode.Setup
        cancelReportAutoClear()
        currentVisualEstimateReport = null
        dismissedVisualEstimateAttemptId = visualEstimateAttemptId
        stopVoiceRecordListener()
        runCommandState = SpeedBallRunCommandState.SetupInvalid(shootNotReadyStatus() ?: "Setup mode")
        updateShellState(status = "Setup mode")
    }

    private fun startVoiceRecordListener(resetErrorCount: Boolean = true) {
        cancelVoiceRestart()
        if (resetErrorCount) {
            voiceConsecutiveErrors = 0
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            voiceRecordListening = false
            runCommandState = SpeedBallRunCommandState.VoiceUnavailable
            updateShellState(status = "Voice trigger unavailable")
            return
        }
        voiceRecordListening = true
        val recognizer = speechRecognizer ?: SpeechRecognizer.createSpeechRecognizer(this).also {
            it.setRecognitionListener(voiceRecognitionListener())
            speechRecognizer = it
        }
        if (appMode == SpeedBallAppMode.Run && runCommandState !is SpeedBallRunCommandState.Capturing) {
            runCommandState = if (currentVisualEstimateReport?.kind == VisualEstimateReportKind.Success) {
                SpeedBallRunCommandState.Reporting
            } else {
                SpeedBallRunCommandState.ManualReady
            }
        }
        updateShellState(status = if (currentVisualEstimateReport?.kind == VisualEstimateReportKind.Success) "Say clear to restart" else VOICE_RUN_PROMPT)
        recognizer.startListening(voiceRecognizerIntent())
    }

    private fun stopVoiceRecordListener() {
        voiceRecordListening = false
        cancelVoiceRestart()
        speechRecognizer?.let {
            runCatching { it.stopListening() }
            runCatching { it.cancel() }
            runCatching { it.destroy() }
        }
        speechRecognizer = null
    }

    private fun cancelVoiceRestart() {
        voiceRestartRunnable?.let { mainHandler.removeCallbacks(it) }
        voiceRestartRunnable = null
    }

    private fun restartVoiceRecordListenerSoon(delayMillis: Long = VOICE_RESTART_DELAY_MILLIS) {
        if (!voiceRecordListening) return
        cancelVoiceRestart()
        val restart = Runnable {
            voiceRestartRunnable = null
            if (voiceRecordListening) {
                runCatching { speechRecognizer?.startListening(voiceRecognizerIntent()) }
            }
        }
        voiceRestartRunnable = restart
        mainHandler.postDelayed(restart, delayMillis)
    }

    private fun voiceRestartDelayMillis(errorCount: Int): Long {
        val multiplier = 1L shl (errorCount - 1).coerceAtLeast(0)
        return (VOICE_RESTART_DELAY_MILLIS * multiplier).coerceAtMost(VOICE_RESTART_MAX_DELAY_MILLIS)
    }

    private fun voiceRecognitionListener(): RecognitionListener =
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                voiceConsecutiveErrors = 0
                if (appMode == SpeedBallAppMode.Run) {
                    val notReadyStatus = shootNotReadyStatus()
                    if (notReadyStatus != null) {
                        runCommandState = SpeedBallRunCommandState.SetupInvalid(notReadyStatus)
                        updateShellState(status = notReadyStatus)
                        return
                    }
                    if (currentVisualEstimateReport?.kind == VisualEstimateReportKind.Success) {
                        runCommandState = SpeedBallRunCommandState.Reporting
                        updateShellState(status = "Say clear to restart")
                        return
                    } else {
                        runCommandState = SpeedBallRunCommandState.Listening
                    }
                }
                updateShellState(status = VOICE_LISTENING_PROMPT)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() = Unit

            override fun onError(error: Int) {
                if (!voiceRecordListening) return
                Log.i(logTag, "VOICE_RECORD_LISTEN_ERROR code=$error")
                if (isIdleVoiceRecognizerError(error)) {
                    voiceConsecutiveErrors = 0
                    val delayMillis = VOICE_IDLE_RESTART_DELAY_MILLIS
                    if (appMode == SpeedBallAppMode.Run && currentVisualEstimateReport?.kind == VisualEstimateReportKind.Success) {
                        runCommandState = SpeedBallRunCommandState.Reporting
                    } else if (appMode == SpeedBallAppMode.Run) {
                        runCommandState = SpeedBallRunCommandState.Listening
                    }
                    Log.i(logTag, "VOICE_RECORD_LISTEN_IDLE code=$error nextDelayMs=$delayMillis")
                    updateShellState(status = if (currentVisualEstimateReport?.kind == VisualEstimateReportKind.Success) "Say clear to restart" else VOICE_LISTENING_PROMPT)
                    restartVoiceRecordListenerSoon(delayMillis)
                    return
                }
                voiceConsecutiveErrors += 1
                if (voiceConsecutiveErrors >= VOICE_RESTART_MAX_CONSECUTIVE_ERRORS) {
                    cancelVoiceRestart()
                    runCommandState = SpeedBallRunCommandState.VoiceError(error, "Use manual Shoot")
                    Log.i(logTag, "VOICE_RECORD_LISTEN_DEGRADED code=$error attempts=$voiceConsecutiveErrors")
                    stopVoiceRecordListener()
                    updateShellState(status = "Voice degraded - use Shoot")
                    return
                }
                val delayMillis = voiceRestartDelayMillis(voiceConsecutiveErrors)
                runCommandState = SpeedBallRunCommandState.VoiceRetrying(voiceConsecutiveErrors, delayMillis)
                updateShellState(status = "Voice retrying")
                restartVoiceRecordListenerSoon(delayMillis)
            }

            override fun onResults(results: Bundle?) {
                val heard = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                if (heard.any(::isClearCommand)) {
                    Log.i(logTag, "VOICE_CLEAR_COMMAND recognized")
                    handleClearCommand()
                } else if (heard.any(::isShootCommand)) {
                    Log.i(logTag, "VOICE_SHOOT_COMMAND recognized")
                    handleShootCommand()
                } else if (heard.any(::isRecordCommand)) {
                    Log.i(logTag, "VOICE_RECORD_COMMAND recognized")
                    stopVoiceRecordListener()
                    handleShootCommand()
                } else {
                    Log.i(logTag, "VOICE_RECORD_IGNORED alternatives=${heard.size}")
                    updateShellState(status = VOICE_RUN_PROMPT)
                    restartVoiceRecordListenerSoon()
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val heard = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    .orEmpty()
                if (heard.any(::isClearCommand)) {
                    Log.i(logTag, "VOICE_CLEAR_COMMAND partial")
                    handleClearCommand()
                } else if (heard.any(::isShootCommand)) {
                    Log.i(logTag, "VOICE_SHOOT_COMMAND partial ignored")
                } else if (heard.any(::isRecordCommand)) {
                    Log.i(logTag, "VOICE_RECORD_COMMAND partial ignored")
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

    private fun voiceRecognizerIntent(): Intent =
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

    private fun handleShootCommand(trigger: ShootTrigger = ShootTrigger.Voice) {
        if (readySignalPending) return
        if (currentVisualEstimateReport?.kind == VisualEstimateReportKind.Success) {
            runCommandState = SpeedBallRunCommandState.Reporting
            updateShellState(status = "Clear result before next shot")
            return
        }
        val notReadyStatus = shootNotReadyStatus()
        if (notReadyStatus != null) {
            val event = if (trigger == ShootTrigger.Voice) "VOICE_SHOOT_NOT_READY" else "MANUAL_SHOOT_NOT_READY"
            Log.i(logTag, "$event reason=${notReadyStatus.compactForLog()}")
            runCommandState = SpeedBallRunCommandState.SetupInvalid(notReadyStatus)
            updateShellState(status = notReadyStatus)
            if (trigger == ShootTrigger.Voice && notReadyStatus == "Capture already running") {
                restartVoiceRecordListenerSoon()
            } else if (trigger == ShootTrigger.Voice) {
                stopVoiceRecordListener()
            }
            return
        }
        stopVoiceRecordListener()
        runCommandState = SpeedBallRunCommandState.Capturing
        updateShellState(status = "Arming impact audio")
        startSoundTriggeredRecordingEstimate()
    }

    private fun handleClearCommand() {
        val report = visibleVisualEstimateReport()
        if (report == null) {
            if (appMode == SpeedBallAppMode.Run) {
                resumeRunModeCommandPath()
            }
            return
        }
        Log.i(logTag, "VOICE_CLEAR_RESULT attempt=${report.attemptId}")
        stopVoiceRecordListener()
        dismissVisualEstimateReport(report.attemptId)
    }

    private fun shootNotReadyStatus(): String? {
        if (!hasCameraPermission()) return "Permission required"
        if (selectedMode == null) return "Mode required"
        if (previewSurface == null) return "Live feed required"
        if (isCaptureActiveStatus()) return "Capture already running"
        updatePhase14Geometry()
        if (phase14WorkflowState.geometry == null) return "Camera geometry required"
        val calibrationNoRead = calibrationWorkflowState.noReadOrNull()
        val hasBallFallback = phase14WorkflowState.knownBallDiameterFeet != null
        if (calibrationNoRead != null && !hasBallFallback) return calibrationNoRead.message
        if (phase14WorkflowState.levelReference?.hasOnlyFiniteValues() != true) return "Level required"
        return null
    }

    private fun phase14ColorNotReadyStatus(): String? {
        val sample = phase14WorkflowState.colorSample
        if (phase14WorkflowState.colorSamplePoint?.isInFrame() != true || sample == null) {
            return "Sample the ball color before measuring."
        }
        if (!sample.hueDegrees.isFinite() ||
            !sample.saturation.isFinite() ||
            !sample.value.isFinite() ||
            sample.saturation !in 0.0..1.0 ||
            sample.value !in 0.0..1.0
        ) {
            return "Sampled ball color is outside the valid HSV range."
        }
        return null
    }

    private fun playReadySignalAfterArmed(onComplete: (Long, String) -> Unit) {
        var ttsCompleteNanos: Long? = null
        var ttsCompletionSource = "beeps_only"
        var beepsCompleteNanos: Long? = null
        var completed = false

        fun completeIfReady() {
            val tts = ttsCompleteNanos ?: return
            val beeps = beepsCompleteNanos ?: return
            if (completed) return
            completed = true
            onComplete(maxOf(tts, beeps), ttsCompletionSource)
        }

        fun markTtsComplete(source: String) {
            mainHandler.post {
                if (ttsCompleteNanos == null) {
                    ttsCompletionSource = source
                    ttsCompleteNanos = SystemClock.elapsedRealtimeNanos()
                    completeIfReady()
                }
            }
        }

        val utteranceId = "$READY_TTS_UTTERANCE_ID-$visualEstimateAttemptId"
        val speech = readySpeech
        if (readySpeechReady && speech != null) {
            speech.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId?.startsWith(READY_TTS_UTTERANCE_ID) == true) {
                            markTtsComplete("tts_done")
                        }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId?.startsWith(READY_TTS_UTTERANCE_ID) == true) {
                            markTtsComplete("tts_error")
                        }
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        if (utteranceId?.startsWith(READY_TTS_UTTERANCE_ID) == true) {
                            markTtsComplete("tts_error_$errorCode")
                        }
                    }
                },
            )
            val speakResult = speech.speak("Ready", TextToSpeech.QUEUE_FLUSH, null, utteranceId)
            if (speakResult == TextToSpeech.ERROR) {
                markTtsComplete("tts_error")
            } else {
                mainHandler.postDelayed(
                    {
                        if (ttsCompleteNanos == null) {
                            readySpeech?.stop()
                            markTtsComplete("tts_timeout_stop")
                        }
                    },
                    READY_TTS_MAX_WAIT_MILLIS,
                )
            }
        } else {
            ttsCompleteNanos = SystemClock.elapsedRealtimeNanos()
        }

        repeat(READY_BEEP_COUNT) { index ->
            mainHandler.postDelayed({ playReadyBeep() }, index * READY_BEEP_SPACING_MILLIS)
        }
        mainHandler.postDelayed(
            {
                beepsCompleteNanos = SystemClock.elapsedRealtimeNanos()
                completeIfReady()
            },
            readyBeepSequenceMillis(),
        )
    }

    private fun playReadyBeep() {
        val tone = runCatching {
            ToneGenerator(AudioManager.STREAM_NOTIFICATION, READY_BEEP_VOLUME_PERCENT)
        }.getOrNull() ?: return
        tone.startTone(ToneGenerator.TONE_PROP_BEEP, READY_BEEP_DURATION_MILLIS)
        mainHandler.postDelayed(
            { tone.release() },
            (READY_BEEP_DURATION_MILLIS + READY_BEEP_RELEASE_PADDING_MILLIS).toLong(),
        )
    }

    private fun readyBeepSequenceMillis(): Long =
        (READY_BEEP_COUNT - 1).coerceAtLeast(0) * READY_BEEP_SPACING_MILLIS +
            READY_BEEP_DURATION_MILLIS +
            READY_BEEP_RELEASE_PADDING_MILLIS

    private fun isShootCommand(text: String): Boolean =
        text.lowercase(Locale.US)
            .split(Regex("[^a-z]+"))
            .any { it in VOICE_SHOOT_COMMANDS }

    private fun isRecordCommand(text: String): Boolean =
        text.lowercase(Locale.US)
            .split(Regex("[^a-z]+"))
            .any { it in VOICE_RECORD_COMMANDS }

    private fun isClearCommand(text: String): Boolean =
        text.lowercase(Locale.US)
            .split(Regex("[^a-z]+"))
            .any { it in VOICE_CLEAR_COMMANDS }

    private fun isIdleVoiceRecognizerError(error: Int): Boolean =
        error == SpeechRecognizer.ERROR_NO_MATCH ||
            error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT

    private enum class ShootTrigger {
        Voice,
        Manual,
    }

    private fun startLiveCameraFeedIfReady() {
        if (!activityResumed || !hasCameraPermission()) return
        val surface = previewSurface ?: return
        val mode = selectedMode ?: return
        cameraLiveFeedController.start(surface, mode) { message ->
            runOnUiThread {
                Log.e(logTag, "LIVE_FEED_FAILURE message=${message.compactForLog()}")
                if (captureStatus == "Idle" || captureStatus == "Live camera feed") {
                    updateShellState(status = "Live feed failed")
                }
            }
        }
        if (captureStatus == "Idle" || captureStatus == "Modes loaded" || captureStatus == "Permission granted") {
            updateShellState(status = "Live camera feed")
        }
    }

    private fun restartLiveCameraFeedIfReady() {
        if (activityResumed) {
            startLiveCameraFeedIfReady()
            startLiveLevelReferenceIfReady()
        }
    }

    private fun refreshModes() {
        if (!hasCameraPermission()) {
            lastFailure = BurstOutcome.Failure(BurstFailure.CAMERA_PERMISSION_DENIED, "Camera permission is required.")
            updateShellState(status = "Permission required")
            return
        }
        when (val result = highSpeedCamera.enumerateModes()) {
            is HighSpeedModesResult.Success -> {
                modes = result.modes
                selectedMode = selectDefaultMode(result.modes)
                refreshPreviewOrientation()
                updatePhase14Geometry()
                lastFailure = null
                Log.i(logTag, "MODES ${modes.joinToString { it.label + ":recordSupported=" + it.recordSupported }}")
                updateShellState(status = "Modes loaded")
                startLiveCameraFeedIfReady()
                startLiveLevelReferenceIfReady()
                startAutoPreviewIfReady()
                startAutoDirectProofIfReady()
                startAutoDirectVisualEstimateIfReady()
                startAutoBurstIfReady()
            }
            is HighSpeedModesResult.Failure -> {
                modes = emptyList()
                selectedMode = null
                phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.GeometryCleared)
                lastFailure = BurstOutcome.Failure(result.reason, result.message)
                Log.e(logTag, "MODE_FAILURE reason=${result.reason} message=${result.message}")
                updateShellState(status = "Mode load failed")
            }
        }
    }

    private fun startBurst() {
        if (!hasCameraPermission()) {
            lastFailure = BurstOutcome.Failure(BurstFailure.CAMERA_PERMISSION_DENIED, "Camera permission is required.")
            updateShellState(status = "Permission required")
            return
        }
        if (modes.isEmpty()) refreshModes()
        val mode = selectedMode
        val surface = previewSurface
        if (mode == null || surface == null) {
            lastFailure = BurstOutcome.Failure(BurstFailure.UNSUPPORTED_MODE, "A 120 fps mode and preview surface are required.")
            updateShellState(status = "Capture unavailable")
            return
        }
        captureStatus = "Recording"
        lastFailure = null
        lastDecodeOutcome = null
        lastPreviewOutcome = null
        lastDirectProofResult = null
        updateShellState(status = captureStatus)
        cameraLiveFeedController.stop()
        val immediateFailure = burstRecorder.start(BurstOptions(mode), surface, modes) { outcome ->
            runOnUiThread {
                when (outcome) {
                    is BurstOutcome.Success -> {
                        lastDiagnostics = outcome.diagnostics
                        lastFailure = null
                        captureStatus = if (outcome.diagnostics.captureProofPasses) "Burst complete" else "Burst proof failed"
                        Log.i(
                            logTag,
                            "BURST_SUCCESS callbacks=${outcome.diagnostics.callbackCount} uniqueTs=${outcome.diagnostics.uniqueTimestampCount} " +
                                "expected=${outcome.diagnostics.expectedUniqueTimestampCount} min=${outcome.diagnostics.minimumUniqueTimestampCount} " +
                                "medianGapMs=${outcome.diagnostics.medianGapMillis?.format(2)} band=${outcome.diagnostics.medianGapLowerBoundMillis.format(2)}..${outcome.diagnostics.medianGapUpperBoundMillis.format(2)} " +
                                "medianPass=${outcome.diagnostics.medianGapPassesRateBand} proof=${outcome.diagnostics.captureProofPasses} " +
                                "file=${outcome.diagnostics.displayOutputName} bytes=${outcome.diagnostics.fileBytes}",
                        )
                        if (outcome.diagnostics.captureProofPasses) {
                            startDecodeProof(outcome)
                        }
                    }
                    is BurstOutcome.Failure -> {
                        lastFailure = outcome
                        captureStatus = "Burst failed"
                        Log.e(logTag, "BURST_FAILURE reason=${outcome.reason} message=${outcome.message}")
                    }
                }
                updateShellState(status = captureStatus)
                restartLiveCameraFeedIfReady()
            }
        }
        if (immediateFailure != null) {
            lastFailure = immediateFailure
            captureStatus = "Burst failed"
            Log.e(logTag, "BURST_FAILURE reason=${immediateFailure.reason} message=${immediateFailure.message}")
            updateShellState(status = captureStatus)
            restartLiveCameraFeedIfReady()
        }
    }

    private fun stopBurst() {
        impactAudioTrigger.cancel()
        burstRecorder.stopActive()
        cancelDecodeProof()
        restartLiveCameraFeedIfReady()
    }

    private fun captureLevelReferenceForSetup() {
        updatePhase14Geometry()
        captureLevelReference(status = "Capturing level", onSuccess = null)
    }

    private fun captureLevelReference(
        status: String,
        onSuccess: (() -> Unit)?,
    ) {
        if (levelCaptureInProgress) return
        stopLiveLevelReference()
        levelCaptureInProgress = true
        updateShellState(status = status)
        levelReferenceCapture.capture(currentDisplayRotation()) { outcome ->
            runOnUiThread {
                levelCaptureInProgress = false
                when (outcome) {
                    is LevelReferenceOutcome.Success -> {
                        phase14WorkflowState = phase14WorkflowState.reduce(
                            Phase14WorkflowEvent.LevelReferenceCaptured(outcome.snapshot),
                        )
                        updateLegacyWorkflowFromPhase14()
                        Log.i(
                            logTag,
                            "LEVEL_REFERENCE_READY rollDeg=${outcome.snapshot.rollDegrees.format(2)} " +
                                "pitchDeg=${outcome.snapshot.pitchDegrees?.format(2) ?: "n/a"} source=${outcome.snapshot.source} " +
                                "samples=${outcome.snapshot.sampleCount}",
                        )
                        updateShellState(status = "Level ready")
                        if (onSuccess == null) {
                            startLiveLevelReferenceIfReady()
                        } else {
                            onSuccess.invoke()
                        }
                    }
                    is LevelReferenceOutcome.Failure -> {
                        phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.LevelReferenceCleared)
                        Log.e(logTag, "LEVEL_REFERENCE_FAILURE message=${outcome.message.compactForLog()}")
                        updateShellState(status = "Level failed")
                        if (onSuccess == null) startLiveLevelReferenceIfReady()
                    }
                }
            }
        }
    }

    private fun ensureSetupLevelReference() {
        if (!activityResumed || levelCaptureInProgress || !hasCameraPermission() || selectedMode == null) return
        if (phase14WorkflowState.levelReference?.hasOnlyFiniteValues() == true) return
        captureLevelReference(status = "Capturing level", onSuccess = null)
    }

    private fun startLiveLevelReferenceIfReady() {
        if (!activityResumed || liveLevelReferenceActive || !hasCameraPermission() || selectedMode == null || isCaptureActiveStatus()) return
        liveLevelReferenceActive = levelReferenceCapture.observeLive(currentDisplayRotation()) { outcome ->
            runOnUiThread {
                if (!liveLevelReferenceActive) return@runOnUiThread
                phase14WorkflowState = phase14WorkflowState.reduce(
                    Phase14WorkflowEvent.LevelReferenceCaptured(outcome.snapshot),
                )
                updateLegacyWorkflowFromPhase14()
                updateShellState(status = captureStatus)
            }
        }
    }

    private fun stopLiveLevelReference() {
        if (!liveLevelReferenceActive) return
        liveLevelReferenceActive = false
        levelReferenceCapture.stop()
    }

    private fun isCaptureActiveStatus(): Boolean =
        captureStatus.equals("Recording", ignoreCase = true) ||
            captureStatus.contains("running", ignoreCase = true) ||
            captureStatus.contains("arming", ignoreCase = true) ||
            captureStatus.contains("armed", ignoreCase = true) ||
            captureStatus.contains("starting", ignoreCase = true) ||
            captureStatus.contains("cueing", ignoreCase = true) ||
            captureStatus.contains("window decoding", ignoreCase = true)

    private fun startSoundTriggeredRecordingEstimate() {
        readySignalPending = true
        if (!hasCameraPermission()) {
            readySignalPending = false
            lastFailure = BurstOutcome.Failure(BurstFailure.CAMERA_PERMISSION_DENIED, "Camera permission is required.")
            updateShellState(status = "Permission required")
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            readySignalPending = false
            finishRecordedVisualEstimateNoRead(
                reason = VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                message = "Microphone permission is required for impact-triggered recording.",
                failure = true,
                proof = null,
                status = "Recorded HFR no-read",
            )
            return
        }
        if (modes.isEmpty()) refreshModes()
        val mode = selectedMode
        if (mode == null) {
            readySignalPending = false
            lastFailure = BurstOutcome.Failure(BurstFailure.UNSUPPORTED_MODE, "A 120 fps mode is required.")
            updateShellState(status = "Voice capture unavailable")
            return
        }
        updatePhase14Geometry()
        if (phase14WorkflowState.levelReference?.hasOnlyFiniteValues() != true) {
            readySignalPending = false
            captureLevelReference(status = "Leveling before record", onSuccess = { startSoundTriggeredRecordingEstimate() })
            return
        }
        beginVisualEstimateAttempt()
        val attemptId = visualEstimateAttemptId
        activeImpactMapping = null
        activeImpactEvent = null
        activeImpactNoRead = null
        activeImpactAudioSession = null
        activeImpactVideoAnchor = null
        phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.StartCapture)
        stopVoiceRecordListener()
        stopLiveLevelReference()
        lastFailure = null
        lastDecodeOutcome = null
        lastPreviewOutcome = null
        lastDirectProofResult = null
        lastDirectVisualEstimateOutcome = null
        currentVisualEstimateReport = null
        dismissedVisualEstimateAttemptId = null
        lastImportExportText = null
        importWorkflowState = ImportWorkflowState()
        captureStatus = "Impact audio arming"
        runCommandState = SpeedBallRunCommandState.Capturing
        Log.i(logTag, "RECORDED_HFR_AUDIO_ARMING attempt=$attemptId mode=${mode.label.compactForLog()} source=SOUND_TRIGGERED_WINDOW")
        updateShellState(status = captureStatus)
        cameraLiveFeedController.stop()
        impactAudioTrigger.start(
            config = soundTriggerConfig(mode),
            onArmed = { session ->
                mainHandler.post {
                    if (attemptId == visualEstimateAttemptId) {
                        handleImpactAudioArmed(attemptId, mode, session)
                    }
                }
            },
            onResult = { result ->
                mainHandler.post {
                    if (attemptId == visualEstimateAttemptId) {
                        handleImpactAudioResult(attemptId, mode, result)
                    }
                }
            },
        )
    }

    private fun handleImpactAudioArmed(
        attemptId: Long,
        mode: HighSpeedMode,
        session: ImpactAudioArmedSession,
    ) {
        activeImpactAudioSession = session
        captureStatus = "Impact audio armed"
        Log.i(
            logTag,
            "RECORDED_HFR_AUDIO_ARMED attempt=$attemptId audioFrame=${session.anchor.framePosition} sampleRate=${session.anchor.sampleRateHz}",
        )
        updateShellState(status = captureStatus)
        startRecordedHfrAfterImpactAudioArmed(attemptId, mode, session)
    }

    private fun startRecordedHfrAfterImpactAudioArmed(
        attemptId: Long,
        mode: HighSpeedMode,
        session: ImpactAudioArmedSession,
    ) {
        captureStatus = "Recorded HFR starting"
        Log.i(logTag, "RECORDED_HFR_START attempt=$attemptId mode=${mode.label.compactForLog()} source=SOUND_TRIGGERED_WINDOW")
        updateShellState(status = captureStatus)
        val immediateFailure = burstRecorder.start(
            BurstOptions(
                mode = mode,
                durationMillis = SOUND_TRIGGER_TOTAL_RECORDING_MILLIS,
                maxAutoExposureTimeNanos = RECORDED_HFR_MAX_ESTIMATE_EXPOSURE_NANOS,
                companionSurfaceMode = BurstCompanionSurfaceMode.OFFSCREEN_PREVIEW,
                stopMode = BurstStopMode.ExternalStop,
                onFirstFrameAnchor = { anchor ->
                    mainHandler.post {
                        if (attemptId == visualEstimateAttemptId) {
                            handleRecordedHfrFirstFrameAnchor(attemptId, mode, session, anchor)
                        }
                    }
                },
            ),
            previewSurface,
            modes,
        ) { outcome ->
            runOnUiThread {
                when (outcome) {
                    is BurstOutcome.Success -> {
                        lastDiagnostics = outcome.diagnostics
                        lastFailure = null
                        captureStatus = "Recorded HFR window decoding"
                        Log.i(
                            logTag,
                            "RECORDED_HFR_CAPTURE_SUCCESS attempt=$visualEstimateAttemptId callbacks=${outcome.diagnostics.callbackCount} " +
                                "uniqueSensorTs=${outcome.diagnostics.uniqueTimestampCount} medianGapPass=${outcome.diagnostics.medianGapPassesRateBand} " +
                                "captureProofPass=${outcome.diagnostics.captureProofPasses} source=${outcome.width}x${outcome.height}@${outcome.requestedFps} " +
                                "companion=${outcome.companionSurfaceMode} file=${outcome.diagnostics.displayOutputName} bytes=${outcome.diagnostics.fileBytes} " +
                                outcome.diagnostics.exposureSummary(),
                        )
                        updateShellState(status = captureStatus)
                        startRecordedWindowEstimate(outcome)
                    }
                    is BurstOutcome.Failure -> {
                        readySignalPending = false
                        impactAudioTrigger.cancel()
                        lastFailure = outcome
                        Log.e(logTag, "RECORDED_HFR_CAPTURE_FAILURE attempt=$visualEstimateAttemptId reason=${outcome.reason} message=${outcome.message.compactForLog()}")
                        finishRecordedVisualEstimateNoRead(
                            reason = VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                            message = outcome.message,
                            failure = true,
                            proof = null,
                            status = "Recorded HFR capture failed",
                        )
                    }
                }
            }
        }
        if (immediateFailure != null) {
            readySignalPending = false
            impactAudioTrigger.cancel()
            lastFailure = immediateFailure
            Log.e(logTag, "RECORDED_HFR_CAPTURE_FAILURE attempt=$visualEstimateAttemptId reason=${immediateFailure.reason} message=${immediateFailure.message.compactForLog()}")
            finishRecordedVisualEstimateNoRead(
                reason = VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                message = immediateFailure.message,
                failure = true,
                proof = null,
                status = "Recorded HFR capture failed",
            )
        }
    }

    private fun handleRecordedHfrFirstFrameAnchor(
        attemptId: Long,
        mode: HighSpeedMode,
        session: ImpactAudioArmedSession,
        anchor: BurstFrameAnchor,
    ) {
        activeImpactVideoAnchor = anchor
        val videoReadySampleIndex = session.anchor.sampleIndexForElapsedRealtime(anchor.elapsedRealtimeNanos)
        if (videoReadySampleIndex == null) {
            readySignalPending = false
            activeImpactNoRead = "BAD_AUDIO_VIDEO_ANCHOR"
            impactAudioTrigger.cancel()
            burstRecorder.stopActive()
            Log.i(logTag, "RECORDED_HFR_IMPACT_NO_READ reason=BAD_AUDIO_VIDEO_ANCHOR")
            return
        }
        session.markVideoReady(videoReadySampleIndex)
        captureStatus = "Recorded HFR cueing"
        Log.i(
            logTag,
            "RECORDED_HFR_FIRST_FRAME_ANCHOR attempt=$attemptId source=${anchor.timestampSource.logLabel} videoReadySample=$videoReadySampleIndex",
        )
        updateShellState(status = captureStatus)
        playReadySignalAfterArmed { cueCompleteElapsedNanos, cueCompletionSource ->
            val acceptElapsedNanos = cueCompleteElapsedNanos + READY_CUE_ACCEPT_MARGIN_MILLIS * 1_000_000L
            val acceptAfterSampleIndex = session.anchor.sampleIndexForElapsedRealtime(acceptElapsedNanos)
            if (acceptAfterSampleIndex == null) {
                readySignalPending = false
                activeImpactNoRead = "BAD_READY_CUE_AUDIO_ANCHOR"
                impactAudioTrigger.cancel()
                burstRecorder.stopActive()
                Log.i(logTag, "RECORDED_HFR_IMPACT_NO_READ reason=BAD_READY_CUE_AUDIO_ANCHOR")
                return@playReadySignalAfterArmed
            }
            session.enableAcceptance(acceptAfterSampleIndex)
            readySignalPending = false
            captureStatus = "Recorded HFR ready"
            Log.i(
                logTag,
                "RECORDED_HFR_READY_CUE_EMITTED attempt=$attemptId completionSource=$cueCompletionSource acceptAfterSample=$acceptAfterSampleIndex",
            )
            Log.i(logTag, "RECORDED_HFR_MARKER_ACCEPTANCE_ENABLED attempt=$attemptId sampleIndex=$acceptAfterSampleIndex")
            updateShellState(status = captureStatus)
        }
    }

    private fun handleImpactAudioResult(
        attemptId: Long,
        mode: HighSpeedMode,
        result: ImpactAudioTriggerResult,
    ) {
        when (result) {
            is ImpactAudioTriggerResult.Detected -> handleImpactAudioDetected(attemptId, mode, result)
            is ImpactAudioTriggerResult.NoImpact -> {
                readySignalPending = false
                activeImpactNoRead = "NO_IMPACT_SOUND_DETECTED"
                Log.i(
                    logTag,
                    "RECORDED_HFR_IMPACT_NO_READ reason=NO_IMPACT_SOUND_DETECTED armed=${activeImpactAudioSession != null} " +
                        "samples=${result.scannedSamples} actionableSamples=${result.actionableSamples} " +
                        "preReady=${result.preReadyTransientCount} blanked=${result.blankedTransientCount} belowThreshold=${result.belowThresholdTransientCount} " +
                        "strongestDelta=${result.strongestPostReadyPeakDelta.format(2)} strongestRatio=${result.strongestPostReadyRatio.format(2)} " +
                        "minimumDelta=${result.minimumPeakDelta} minimumBaselineRms=${result.minimumBaselineRms.format(2)}",
                )
                if (activeImpactVideoAnchor == null) {
                    finishRecordedVisualEstimateNoRead(
                        reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                        message = "Impact audio did not detect a post-ready marker before HFR produced a usable video anchor.",
                        failure = false,
                        proof = null,
                        status = "Recorded HFR no-read",
                    )
                } else {
                    burstRecorder.stopActive()
                }
            }
            is ImpactAudioTriggerResult.PermissionDenied -> {
                readySignalPending = false
                activeImpactNoRead = result.message
                Log.i(logTag, "RECORDED_HFR_IMPACT_NO_READ reason=MIC_PERMISSION")
                finishOrStopAfterImpactAudioFailure(
                    message = result.message,
                    failure = true,
                )
            }
            is ImpactAudioTriggerResult.ResourceFailure -> {
                readySignalPending = false
                activeImpactNoRead = result.message
                Log.i(logTag, "RECORDED_HFR_IMPACT_NO_READ reason=RESOURCE_FAILURE message=${result.message.compactForLog()}")
                finishOrStopAfterImpactAudioFailure(
                    message = result.message,
                    failure = true,
                )
            }
        }
    }

    private fun finishOrStopAfterImpactAudioFailure(
        message: String,
        failure: Boolean,
    ) {
        if (activeImpactVideoAnchor == null) {
            finishRecordedVisualEstimateNoRead(
                reason = VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                message = message,
                failure = failure,
                proof = null,
                status = "Recorded HFR no-read",
            )
        } else {
            burstRecorder.stopActive()
        }
    }

    private fun handleImpactAudioDetected(
        attemptId: Long,
        mode: HighSpeedMode,
        result: ImpactAudioTriggerResult.Detected,
    ) {
        val anchor = activeImpactVideoAnchor
        if (anchor == null) {
            readySignalPending = false
            activeImpactNoRead = "BAD_AUDIO_VIDEO_ANCHOR"
            Log.i(logTag, "RECORDED_HFR_IMPACT_NO_READ reason=BAD_AUDIO_VIDEO_ANCHOR")
            burstRecorder.stopActive()
            return
        }
        val clockAnchor = AudioVideoClockAnchor(
            firstFrameSensorTimestampNanos = anchor.sensorTimestampNanos,
            firstFrameElapsedRealtimeNanos = anchor.elapsedRealtimeNanos,
            recorderStartCommandElapsedNanos = anchor.recorderStartCommandElapsedNanos,
            timestampSource = anchor.timestampSource,
            endToEndAnchorErrorNanos = defaultSoundWindowAnchorErrorNanos(mode),
        )
        when (
            val mapped = ImpactWindowMapper.map(
                anchor = clockAnchor,
                request = ImpactWindowRequest(
                    impactElapsedRealtimeNanos = result.event.elapsedRealtimeNanos,
                    requestedFps = mode.fps,
                    preImpactMillis = SOUND_TRIGGER_PRE_IMPACT_CAPTURE_MILLIS,
                    postImpactMillis = SOUND_TRIGGER_POST_IMPACT_CAPTURE_MILLIS,
                    maxPreImpactMarginFrames = SOUND_TRIGGER_MAX_PRE_IMPACT_FRAMES,
                ),
            )
        ) {
            is ImportValidationResult.NoRead -> {
                readySignalPending = false
                activeImpactNoRead = mapped.message
                Log.i(logTag, "RECORDED_HFR_IMPACT_NO_READ reason=${mapped.message.compactForLog()}")
                burstRecorder.stopActive()
            }
            is ImportValidationResult.Success -> {
                readySignalPending = false
                activeImpactEvent = result.event
                activeImpactMapping = mapped.value
                Log.i(
                    logTag,
                    "RECORDED_HFR_IMPACT_DETECTED attempt=$attemptId offsetMs=${result.event.offsetMillis.format(2)} " +
                        "impactFrame=${mapped.value.sensorDiagnosticFrameIndex} anchorErrorMs=${(mapped.value.endToEndAnchorErrorNanos / 1_000_000.0).format(2)} " +
                        "peakDelta=${result.event.peakDelta.format(2)} ratio=${result.event.triggerRatio.format(2)}",
                )
                Log.i(
                    logTag,
                    "RECORDED_ESTIMATE_WINDOW windowStartUs=${mapped.value.window.windowStartUs} windowEndUs=${mapped.value.window.windowEndUs} " +
                        "postFrames=${mapped.value.window.postImpactFrameCount} preMarginFrames=${mapped.value.window.preImpactMarginFrames} " +
                        "totalFrames=${mapped.value.window.maxFrames} fps=${mode.fps}",
                )
                mainHandler.postDelayed({ burstRecorder.stopActive() }, SOUND_TRIGGER_POST_IMPACT_CAPTURE_MILLIS)
            }
        }
    }

    private fun soundTriggerConfig(mode: HighSpeedMode): ImpactAudioTriggerConfig =
        ImpactAudioTriggerConfig(
            sampleRateHz = SOUND_TRIGGER_SAMPLE_RATE_HZ,
            baselineSampleCount = SOUND_TRIGGER_BASELINE_SAMPLES,
            triggerWindowSampleCount = SOUND_TRIGGER_WINDOW_SAMPLES,
            thresholdMultiplier = SOUND_TRIGGER_THRESHOLD_MULTIPLIER,
            minimumPeakDelta = SOUND_TRIGGER_MINIMUM_PEAK_DELTA,
            minimumBaselineRms = SOUND_TRIGGER_MINIMUM_BASELINE_RMS,
            cooldownSampleCount = SOUND_TRIGGER_COOLDOWN_SAMPLES,
            maxArmSamples = (SOUND_TRIGGER_SAMPLE_RATE_HZ * (SOUND_TRIGGER_TOTAL_AUDIO_BUFFER_MILLIS / 1_000.0)).toInt()
                .coerceAtLeast(mode.fps),
            actionableSampleCount = (SOUND_TRIGGER_SAMPLE_RATE_HZ * (SOUND_TRIGGER_USER_ACTIONABLE_MILLIS / 1_000.0)).toInt()
                .coerceAtLeast(mode.fps),
        )

    private fun defaultSoundWindowAnchorErrorNanos(mode: HighSpeedMode): Long =
        1_000_000_000L / mode.fps

    private fun startTimedRecordingEstimate() {
        readySignalPending = false
        if (!hasCameraPermission()) {
            lastFailure = BurstOutcome.Failure(BurstFailure.CAMERA_PERMISSION_DENIED, "Camera permission is required.")
            updateShellState(status = "Permission required")
            return
        }
        if (modes.isEmpty()) refreshModes()
        val mode = selectedMode
        if (mode == null) {
            lastFailure = BurstOutcome.Failure(BurstFailure.UNSUPPORTED_MODE, "A 120 fps mode is required.")
            updateShellState(status = "Voice capture unavailable")
            return
        }
        updatePhase14Geometry()
        if (phase14WorkflowState.levelReference?.hasOnlyFiniteValues() != true) {
            captureLevelReference(status = "Leveling before record", onSuccess = { startTimedRecordingEstimate() })
            return
        }
        beginVisualEstimateAttempt()
        phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.StartCapture)
        stopLiveLevelReference()
        lastFailure = null
        lastDecodeOutcome = null
        lastPreviewOutcome = null
        lastDirectProofResult = null
        lastDirectVisualEstimateOutcome = null
        currentVisualEstimateReport = null
        dismissedVisualEstimateAttemptId = null
        lastImportExportText = null
        importWorkflowState = ImportWorkflowState()
        captureStatus = "Recorded HFR capture running"
        runCommandState = SpeedBallRunCommandState.Capturing
        Log.i(logTag, "RECORDED_HFR_START attempt=$visualEstimateAttemptId mode=${mode.label.compactForLog()} source=RECORDER_OFFSCREEN_PREVIEW")
        updateShellState(status = captureStatus)
        cameraLiveFeedController.stop()
        val immediateFailure = burstRecorder.start(
            BurstOptions(
                mode,
                durationMillis = AUTO_RECORD_ESTIMATE_DURATION_MILLIS,
                maxAutoExposureTimeNanos = RECORDED_HFR_MAX_ESTIMATE_EXPOSURE_NANOS,
                companionSurfaceMode = BurstCompanionSurfaceMode.OFFSCREEN_PREVIEW,
            ),
            previewSurface,
            modes,
        ) { outcome ->
            runOnUiThread {
                when (outcome) {
                    is BurstOutcome.Success -> {
                        lastDiagnostics = outcome.diagnostics
                        lastFailure = null
                        captureStatus = "Recorded HFR decoding"
                        Log.i(
                            logTag,
                            "RECORDED_HFR_CAPTURE_SUCCESS attempt=$visualEstimateAttemptId callbacks=${outcome.diagnostics.callbackCount} " +
                                "uniqueSensorTs=${outcome.diagnostics.uniqueTimestampCount} medianGapPass=${outcome.diagnostics.medianGapPassesRateBand} " +
                                "captureProofPass=${outcome.diagnostics.captureProofPasses} source=${outcome.width}x${outcome.height}@${outcome.requestedFps} " +
                                "companion=${outcome.companionSurfaceMode} file=${outcome.diagnostics.displayOutputName} bytes=${outcome.diagnostics.fileBytes} " +
                                outcome.diagnostics.exposureSummary(),
                        )
                        updateShellState(status = captureStatus)
                        startRecordedEstimate(outcome)
                    }
                    is BurstOutcome.Failure -> {
                        lastFailure = outcome
                        Log.e(logTag, "RECORDED_HFR_CAPTURE_FAILURE attempt=$visualEstimateAttemptId reason=${outcome.reason} message=${outcome.message.compactForLog()}")
                        finishRecordedVisualEstimateNoRead(
                            reason = VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                            message = outcome.message,
                            failure = true,
                            proof = null,
                            status = "Recorded HFR capture failed",
                        )
                    }
                }
            }
        }
        if (immediateFailure != null) {
            lastFailure = immediateFailure
            Log.e(logTag, "RECORDED_HFR_CAPTURE_FAILURE attempt=$visualEstimateAttemptId reason=${immediateFailure.reason} message=${immediateFailure.message.compactForLog()}")
            finishRecordedVisualEstimateNoRead(
                reason = VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                message = immediateFailure.message,
                failure = true,
                proof = null,
                status = "Recorded HFR capture failed",
            )
        }
    }

    private fun startRecordedEstimate(outcome: BurstOutcome.Success) {
        val file = outcome.outputFile
        val fps = outcome.requestedFps ?: selectedMode?.fps
        if (file == null || fps == null || !file.isFile || file.length() <= 0L) {
            finishRecordedVisualEstimateNoRead(
                reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                message = "Recorded capture did not produce a usable video file.",
                failure = true,
                proof = null,
                status = "Recorded HFR no-read",
            )
            return
        }
        decodeWorkerExecutor.execute {
            val estimate = try {
                runRecordedEstimate(file, fps, outcome.diagnostics)
            } finally {
                runCatching { file.delete() }
            }
            runOnUiThread {
                finishImportRunOutcome(estimate, completePrefix = "Recorded")
            }
        }
    }

    private fun startRecordedWindowEstimate(outcome: BurstOutcome.Success) {
        val file = outcome.outputFile
        val fps = outcome.requestedFps ?: selectedMode?.fps
        val mapping = activeImpactMapping
        if (file == null || fps == null || !file.isFile || file.length() <= 0L) {
            finishRecordedVisualEstimateNoRead(
                reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                message = "Recorded capture did not produce a usable video file.",
                failure = true,
                proof = null,
                status = "Recorded HFR no-read",
            )
            return
        }
        if (mapping == null) {
            val message = activeImpactNoRead ?: "NO_IMPACT_SOUND_DETECTED"
            retainRecordedHfrFailure(file, "no-impact")
            finishRecordedVisualEstimateNoRead(
                reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                message = message,
                failure = false,
                proof = null,
                status = "Recorded HFR no-read",
            )
            return
        }
        decodeWorkerExecutor.execute {
            val estimate = runRecordedWindowEstimate(file, fps, outcome.diagnostics, mapping)
            applyRecordedHfrRetention(file, estimate)
            runOnUiThread {
                finishImportRunOutcome(estimate, completePrefix = "Recorded")
            }
        }
    }

    private fun applyRecordedHfrRetention(file: File, outcome: ImportRunOutcome) {
        when (outcome) {
            is ImportRunOutcome.Estimate -> {
                RecordedHfrRetentionPolicy.cleanupSuccess(file)
                Log.i(logTag, "RECORDED_HFR_RETAIN retained=false reason=success file=${RecordedHfrRetentionPolicy.displayName(file)}")
            }
            is ImportRunOutcome.NoRead -> retainRecordedHfrFailure(file, outcome.reason.name)
        }
    }

    private fun retainRecordedHfrFailure(file: File, reason: String) {
        val retention = RecordedHfrRetentionPolicy.retainFailure(file, debugBuild = isDebuggableBuild())
        Log.i(
            logTag,
            "RECORDED_HFR_RETAIN retained=${retention.retained} reason=${reason.compactForLog()} " +
                "file=${retention.displayName} bytes=${retention.bytes}",
        )
    }

    private fun startPreviewSpike() {
        if (!hasCameraPermission()) {
            lastPreviewOutcome = PreviewFrameOutcome.Failure(com.speedball.app.capture.PreviewFrameFailure.CAMERA_PERMISSION_DENIED, "Camera permission is required.")
            updateShellState(status = "Permission required")
            return
        }
        if (modes.isEmpty()) refreshModes()
        val mode = selectedMode
        if (mode == null) {
            lastPreviewOutcome = PreviewFrameOutcome.Failure(com.speedball.app.capture.PreviewFrameFailure.UNSUPPORTED_MODE, "A fixed high-speed mode is required.")
            updateShellState(status = "Preview proof unavailable")
            return
        }
        captureStatus = "Preview proof running"
        lastPreviewOutcome = null
        lastFailure = null
        lastDecodeOutcome = null
        Log.i(logTag, "PREVIEW_PATH_START mode=${mode.label.compactForLog()}")
        updateShellState(status = captureStatus)
        cameraLiveFeedController.stop()
        val immediateFailure = previewSpikeCapture.start(BurstOptions(mode), modes) { outcome ->
            runOnUiThread {
                lastPreviewOutcome = outcome
                captureStatus = when (outcome) {
                    is PreviewFrameOutcome.Success -> "Preview proof complete"
                    is PreviewFrameOutcome.Failure -> "Preview proof failed"
                    PreviewFrameOutcome.Cancelled -> "Preview proof cancelled"
                }
                logPreviewOutcome(mode, outcome)
                updateShellState(status = captureStatus)
                restartLiveCameraFeedIfReady()
            }
        }
        if (immediateFailure != null) {
            lastPreviewOutcome = immediateFailure
            captureStatus = "Preview proof failed"
            logPreviewOutcome(mode, immediateFailure)
            updateShellState(status = captureStatus)
            restartLiveCameraFeedIfReady()
        }
    }

    private fun stopPreviewSpike() {
        previewSpikeCapture.stopActive()
        restartLiveCameraFeedIfReady()
    }

    private fun startDirectProof() {
        if (!hasCameraPermission()) {
            lastDirectProofResult = immediateDirectProofFailure(DirectTimingSourceFailure.CAMERA_OPEN_FAILED, "Camera permission is required.")
            updateShellState(status = "Permission required")
            return
        }
        if (modes.isEmpty()) refreshModes()
        val mode = selectedMode
        if (mode == null) {
            lastDirectProofResult = immediateDirectProofFailure(DirectTimingSourceFailure.UNSUPPORTED_MODE, "A fixed high-speed mode is required.")
            updateShellState(status = "Direct proof unavailable")
            return
        }
        captureStatus = "Direct proof running"
        lastDirectProofResult = null
        lastPreviewOutcome = null
        lastFailure = null
        lastDecodeOutcome = null
        updateShellState(status = captureStatus)
        cameraLiveFeedController.stop()
        val options = BurstOptions(mode)
        val variant = selectedDirectProofVariant()
        directProofExecutor.execute {
            val runner = DirectTimingSourceProofRunner(
                companionProbe = { runDirectCompanionProbe(options, variant) },
                previewControl = { runPreviewControlProbe(options) },
                primaryShape = variant.sessionShape,
                logger = { line -> Log.i(logTag, line) },
            )
            val result = runner.run(
                runId = DirectProofRunId("direct-${variant.id}-${System.currentTimeMillis()}"),
                mode = mode,
                pixelConfig = DIRECT_UI_PIXEL_CONFIG,
            )
            runOnUiThread {
                lastDirectProofResult = result
                captureStatus = when (result.proofOutcome) {
                    is DirectTimingSourceProofOutcome.Success -> "Direct proof complete"
                    is DirectTimingSourceProofOutcome.Failure -> "Direct proof failed"
                    DirectTimingSourceProofOutcome.Cancelled -> "Direct proof cancelled"
                }
                updateShellState(status = captureStatus)
                restartLiveCameraFeedIfReady()
            }
        }
    }

    private fun stopDirectProof() {
        directProofCapture.stopActive()
        restartLiveCameraFeedIfReady()
    }

    private fun startDirectVisualEstimate() {
        readySignalPending = false
        beginVisualEstimateAttempt()
        phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.StartCapture)
        if (!hasCameraPermission()) {
            val noRead = VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                message = "Camera permission is required.",
            )
            lastDirectVisualEstimateOutcome = noRead
            currentVisualEstimateReport = visualEstimateReportFor(visualEstimateAttemptId, noRead, failure = true)
            runCommandState = SpeedBallRunCommandState.SetupInvalid("Permission required")
            updateShellState(status = "Permission required")
            return
        }
        if (modes.isEmpty()) refreshModes()
        val mode = selectedMode
        if (mode == null) {
            val noRead = VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                message = "A fixed high-speed mode is required.",
            )
            lastDirectVisualEstimateOutcome = noRead
            currentVisualEstimateReport = visualEstimateReportFor(visualEstimateAttemptId, noRead, failure = true)
            runCommandState = SpeedBallRunCommandState.SetupInvalid("Mode required")
            updateShellState(status = "Visual estimate unavailable")
            return
        }
        if (previewSurface == null) {
            val noRead = VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.DETECTION_FAILED,
                message = "Live camera feed is required before estimating.",
            )
            lastDirectVisualEstimateOutcome = noRead
            currentVisualEstimateReport = visualEstimateReportFor(visualEstimateAttemptId, noRead)
            phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.ArmCapture)
            runCommandState = SpeedBallRunCommandState.SetupInvalid("Live feed required")
            updateShellState(status = "Live feed required")
            return
        }
        val config = buildDirectVisualEstimateConfig(mode)
        if (config == null) {
            phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.ArmCapture)
            lastDirectVisualEstimateOutcome?.let { noRead ->
                currentVisualEstimateReport = visualEstimateReportFor(visualEstimateAttemptId, noRead)
            }
            runCommandState = SpeedBallRunCommandState.SetupInvalid("Visual estimate setup incomplete")
            updateShellState(status = "Visual estimate setup incomplete")
            return
        }
        stopLiveLevelReference()
        captureStatus = "Visual estimate running"
        lastDirectVisualEstimateOutcome = null
        currentVisualEstimateReport = null
        dismissedVisualEstimateAttemptId = null
        runCommandState = SpeedBallRunCommandState.Capturing
        lastDirectProofResult = null
        lastPreviewOutcome = null
        lastFailure = null
        lastDecodeOutcome = null
        updateShellState(status = captureStatus)
        cameraLiveFeedController.stop()
        val immediateFailure = directVisualEstimateCapture.start(config, modes) { outcome ->
            runOnUiThread {
                handleDirectVisualEstimateOutcome(outcome)
            }
        }
        if (immediateFailure != null) {
            handleDirectVisualEstimateOutcome(immediateFailure)
        }
    }

    private fun stopDirectVisualEstimate() {
        directVisualEstimateCapture.stopActive()
        restartLiveCameraFeedIfReady()
    }

    private fun beginVisualEstimateAttempt() {
        cancelReportAutoClear()
        visualEstimateAttemptId += 1
        currentVisualEstimateReport = null
        dismissedVisualEstimateAttemptId = null
    }

    private fun dismissVisualEstimateReport(attemptId: Long) {
        if (currentVisualEstimateReport?.attemptId != attemptId) return
        cancelReportAutoClear()
        dismissedVisualEstimateAttemptId = attemptId
        currentVisualEstimateReport = null
        if (appMode == SpeedBallAppMode.Run) {
            resumeRunModeCommandPath()
        } else {
            updateShellState(status = captureStatus)
        }
    }

    private fun visibleVisualEstimateReport(): VisualEstimateReport? =
        currentVisualEstimateReport?.takeUnless { it.attemptId == dismissedVisualEstimateAttemptId }

    private fun scheduleReportAutoClear(report: VisualEstimateReport?) {
        cancelReportAutoClear()
        if (appMode != SpeedBallAppMode.Run || report == null) return
        val attemptId = report.attemptId
        val runnable = Runnable {
            reportAutoClearRunnable = null
            if (currentVisualEstimateReport?.attemptId == attemptId) {
                Log.i(logTag, "REPORT_AUTO_CLEAR attempt=$attemptId")
                dismissVisualEstimateReport(attemptId)
            }
        }
        reportAutoClearRunnable = runnable
        mainHandler.postDelayed(runnable, REPORT_AUTO_CLEAR_DELAY_MILLIS)
    }

    private fun cancelReportAutoClear() {
        reportAutoClearRunnable?.let { mainHandler.removeCallbacks(it) }
        reportAutoClearRunnable = null
    }

    private fun resumeRunModeCommandPath() {
        if (appMode != SpeedBallAppMode.Run) return
        if (currentVisualEstimateReport?.kind == VisualEstimateReportKind.Success) {
            runCommandState = SpeedBallRunCommandState.Reporting
            if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                startVoiceRecordListener()
            } else {
                stopVoiceRecordListener()
                updateShellState(status = captureStatus)
            }
            return
        }
        val notReadyStatus = shootNotReadyStatus()
        if (notReadyStatus != null) {
            runCommandState = SpeedBallRunCommandState.SetupInvalid(notReadyStatus)
            updateShellState(status = notReadyStatus)
            return
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startVoiceRecordListener()
        } else {
            voiceRecordListening = false
            runCommandState = SpeedBallRunCommandState.ManualReady
            updateShellState(status = "Run mode manual ready")
        }
    }

    private fun handleDirectVisualEstimateOutcome(outcome: DirectVisualEstimateCaptureOutcome) {
        var reportKind = VisualEstimateReportKind.NoRead
        when (outcome) {
            is DirectVisualEstimateCaptureOutcome.Completed -> {
                lastDirectVisualEstimateOutcome = outcome.estimateOutcome
                phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.CaptureCompleted(outcome.estimateOutcome))
                captureStatus = when (outcome.estimateOutcome) {
                    is VisualEstimateOutcome.Success -> "Visual estimate complete"
                    is VisualEstimateOutcome.NoRead -> "Visual estimate no-read"
                }
                currentVisualEstimateReport = visualEstimateReportFor(
                    attemptId = visualEstimateAttemptId,
                    outcome = outcome.estimateOutcome,
                    captureProof = outcome.captureProof.withAttemptId(visualEstimateAttemptId),
                )
                scheduleReportAutoClear(currentVisualEstimateReport)
                reportKind = currentVisualEstimateReport?.kind ?: VisualEstimateReportKind.NoRead
                Log.i(
                    logTag,
                    "VISUAL_ESTIMATE_COMPLETE frames=${outcome.capturedFrameCount} callbacks=${outcome.frameAvailableCallbackCount} " +
                        "captureCallbacks=${outcome.captureResultCallbackCount} uniqueSensorTs=${outcome.uniqueSensorTimestampCount} " +
                        "readback=${outcome.readbackWidth}x${outcome.readbackHeight} " +
                        "candidateFrames=${outcome.captureProof.detectorSummary.candidateFrameCount} " +
                        "candidateBlobs=${outcome.captureProof.detectorSummary.candidateBlobCount} " +
                        "selectedSamples=${outcome.captureProof.detectorSummary.selectedSampleCount}",
                )
                visualEstimateOutcomeUiLines(outcome.estimateOutcome).forEach { line ->
                    Log.i(logTag, "VISUAL_ESTIMATE_RESULT ${line.compactForLog()}")
                }
            }
            is DirectVisualEstimateCaptureOutcome.Failure -> {
                val noRead = VisualEstimateOutcome.NoRead(
                    reason = outcome.reason.toVisualEstimateNoReadReason(),
                    message = outcome.message,
                )
                lastDirectVisualEstimateOutcome = noRead
                phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.CaptureCompleted(noRead))
                captureStatus = "Visual estimate failed"
                currentVisualEstimateReport = visualEstimateReportFor(
                    attemptId = visualEstimateAttemptId,
                    outcome = noRead,
                    failure = true,
                    captureProof = outcome.captureProof?.withAttemptId(visualEstimateAttemptId),
                )
                scheduleReportAutoClear(currentVisualEstimateReport)
                reportKind = VisualEstimateReportKind.Failure
                val proofSummary = outcome.captureProof?.detectorSummary
                Log.i(
                    logTag,
                    "VISUAL_ESTIMATE_FAILURE reason=${outcome.reason} frames=${outcome.capturedFrameCount} " +
                        "candidateFrames=${proofSummary?.candidateFrameCount ?: 0} candidateBlobs=${proofSummary?.candidateBlobCount ?: 0} " +
                        "selectedSamples=${proofSummary?.selectedSampleCount ?: 0} message=${outcome.message.compactForLog()}",
                )
                lastDirectVisualEstimateOutcome?.let { noRead ->
                    visualEstimateOutcomeUiLines(noRead).forEach { line ->
                        Log.i(logTag, "VISUAL_ESTIMATE_RESULT ${line.compactForLog()}")
                    }
                }
            }
        }
        dismissedVisualEstimateAttemptId = null
        runCommandState = if (reportKind == VisualEstimateReportKind.Success) {
            SpeedBallRunCommandState.Reporting
        } else {
            SpeedBallRunCommandState.ManualReady
        }
        updateShellState(status = captureStatus)
        restartLiveCameraFeedIfReady()
        if (appMode == SpeedBallAppMode.Run && reportKind != VisualEstimateReportKind.Success) {
            resumeRunModeCommandPath()
        }
    }

    private fun DirectTimingSourceFailure.toVisualEstimateNoReadReason(): VisualEstimateNoReadReason =
        when (this) {
            DirectTimingSourceFailure.MISSING_DIRECT_TIMESTAMPS,
            DirectTimingSourceFailure.INSUFFICIENT_DIRECT_FRAMES,
            DirectTimingSourceFailure.DUPLICATE_DIRECT_TIMESTAMPS,
            DirectTimingSourceFailure.DIRECT_TIMESTAMPS_NON_MONOTONIC,
            DirectTimingSourceFailure.DIRECT_CADENCE_MISMATCH,
            DirectTimingSourceFailure.DIRECT_DROPPED_FRAME_GAP,
            DirectTimingSourceFailure.DIRECT_TIMESTAMP_NEAR_DUPLICATE,
            DirectTimingSourceFailure.MISSING_SENSOR_TIMESTAMPS,
            DirectTimingSourceFailure.SENSOR_MEMBERSHIP_UNAVAILABLE,
            DirectTimingSourceFailure.NONZERO_OFFSET_OUT_OF_BOUND,
            DirectTimingSourceFailure.AMBIGUOUS_WRONG_BY_K_OFFSET ->
                VisualEstimateNoReadReason.BAD_TIMESTAMPS

            DirectTimingSourceFailure.UNSUPPORTED_MODE,
            DirectTimingSourceFailure.CAPTURE_BUSY,
            DirectTimingSourceFailure.CAMERA_OPEN_FAILED,
            DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED,
            DirectTimingSourceFailure.COMPANION_RECORDER_SETUP_FAILED,
            DirectTimingSourceFailure.SCRATCH_FILE_CLEANUP_FAILED,
            DirectTimingSourceFailure.PIXEL_READBACK_FAILED,
            DirectTimingSourceFailure.BLANK_OR_STALE_PIXEL_PROOF,
            DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED,
            DirectTimingSourceFailure.LATE_CALLBACK_AFTER_TEARDOWN,
            DirectTimingSourceFailure.PROOF_TOKEN_REJECTED ->
                VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED
        }

    private fun runDirectCompanionProbe(
        options: BurstOptions,
        variant: DirectProofVariant,
    ): DirectSessionProbeOutcome {
        val latch = CountDownLatch(1)
        val result = AtomicReference<DirectSessionProbeOutcome?>()
        val immediateFailure = directProofCapture.start(options, modes, variant) { outcome ->
            result.set(outcome)
            latch.countDown()
        }
        if (immediateFailure != null) return immediateFailure
        val completed = latch.await(options.durationMillis + DIRECT_PROOF_TIMEOUT_PADDING_MILLIS, TimeUnit.MILLISECONDS)
        if (!completed) {
            directProofCapture.stopActive()
            return directProbeFailure(DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED, "Direct proof timed out before completion.")
        }
        return result.get() ?: directProbeFailure(DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED, "Direct proof completed without an outcome.")
    }

    private fun selectedDirectProofVariant(): DirectProofVariant {
        val requested = requestedDirectProofVariantId
        if (requested.isNullOrBlank()) return companionGlBaselineVariant()
        return plannedDirectProofVariants().firstOrNull { it.id == requested }
            ?: companionGlBaselineVariant().also {
                Log.i(logTag, "DIRECT_PROOF_VARIANT_FALLBACK requested=${requested.compactForLog()} fallback=${it.id}")
            }
    }

    private fun runPreviewControlProbe(options: BurstOptions): PreviewFrameOutcome {
        Thread.sleep(DIRECT_PROOF_CAMERA_SETTLE_MILLIS)
        val latch = CountDownLatch(1)
        val result = AtomicReference<PreviewFrameOutcome?>()
        val immediateFailure = previewSpikeCapture.start(options, modes) { outcome ->
            result.set(outcome)
            latch.countDown()
        }
        if (immediateFailure != null) return immediateFailure
        val completed = latch.await(options.durationMillis + DIRECT_PROOF_TIMEOUT_PADDING_MILLIS, TimeUnit.MILLISECONDS)
        if (!completed) {
            previewSpikeCapture.stopActive()
            return PreviewFrameOutcome.Failure(com.speedball.app.capture.PreviewFrameFailure.FRAME_TIMEOUT, "Preview control timed out before completion.")
        }
        return result.get() ?: PreviewFrameOutcome.Failure(com.speedball.app.capture.PreviewFrameFailure.SESSION_CONFIGURATION_FAILED, "Preview control completed without an outcome.")
    }

    private fun startDecodeProof(outcome: BurstOutcome.Success) {
        val file = outcome.outputFile
        val fps = outcome.requestedFps
        val durationMillis = outcome.requestedDurationMillis
        if (file == null || fps == null || durationMillis == null) {
            lastDecodeOutcome = DecodeOutcome.Failure(DecodeFailure.OUTPUT_FILE_MISSING, "Capture did not provide an exact recorder output file for decode proof.")
            captureStatus = "Decode proof failed"
            updateShellState(status = captureStatus)
            return
        }
        captureStatus = "Decode proof running"
        updateShellState(status = captureStatus)
        val generation = decodeCompletionGate.startRun()
        val bounds = buildDecodeWorkBounds(durationMillis, fps)
        decodeSupervisorExecutor.execute {
            val decodeOutcome = runDecodeWithTimeout(decodeWorkerExecutor, bounds.timeoutMillis) {
                burstVideoDecoder.decode(
                    outputFile = file,
                    rawSensorTimestampsNanos = outcome.sensorTimestampsNanos,
                    requestedFps = fps,
                    requestedDurationMillis = durationMillis,
                    anchorDiagnosticsSink = { anchorOutcome -> logTimestampAnchorOutcome(file, anchorOutcome) },
                )
            }
            runOnUiThread {
                val acceptedOutcome = decodeCompletionGate.accept(generation, decodeOutcome)
                if (acceptedOutcome == null) {
                    Log.i(logTag, "DECODE_CANCELLED late_result_discarded file=${file.displayNameOnly()}")
                    return@runOnUiThread
                }
                lastDecodeOutcome = acceptedOutcome
                captureStatus = when (acceptedOutcome) {
                    is DecodeOutcome.Success -> "Decode proof complete"
                    is DecodeOutcome.Failure -> "Decode proof failed"
                    DecodeOutcome.Cancelled -> "Decode cancelled"
                }
                logDecodeOutcome(file, acceptedOutcome)
                updateShellState(status = captureStatus)
            }
        }
    }

    private fun cancelDecodeProof() {
        if (captureStatus == "Decode proof running") {
            lastDecodeOutcome = decodeCompletionGate.cancel()
            captureStatus = "Decode cancelled"
            updateShellState(status = captureStatus)
        } else {
            decodeCompletionGate.cancel()
        }
    }

    private fun startAutoBurstIfReady() {
        if (!autoStartPending || previewSurface == null || selectedMode == null) return
        autoStartPending = false
        startBurst()
    }

    private fun startAutoPreviewIfReady() {
        if (!autoStartPreviewPending || selectedMode == null || !activityResumed || !windowFocused) return
        autoStartPreviewPending = false
        startPreviewSpike()
    }

    private fun startAutoDirectProofIfReady() {
        if (!autoStartDirectProofPending || selectedMode == null || !activityResumed || !windowFocused) return
        autoStartDirectProofPending = false
        startDirectProof()
    }

    private fun startAutoDirectVisualEstimateIfReady() {
        if (!autoStartDirectVisualEstimatePending || selectedMode == null || previewSurface == null || !activityResumed || !windowFocused) return
        autoStartDirectVisualEstimatePending = false
        startDirectVisualEstimate()
    }

    private fun logTimestampSourceIfRequested() {
        if (!autoLogTimestampSourcePending) return
        autoLogTimestampSourcePending = false
        Log.i(logTag, timestampSourceLogLine(highSpeedCamera.readBackCameraTimestampSource()))
    }

    private fun startRecordedHfrByteBufferProbeIfRequested() {
        if (!intent.getBooleanExtra("autoProbeRecordedHfrByteBuffer", false) || !isDebuggableBuild()) return
        val explicitPath = intent.getStringExtra("recordedHfrProbePath")
        val file = explicitPath?.let(::File) ?: latestRecordedHfrMovieFile()
        if (file == null) {
            Log.e(logTag, "RECORDED_HFR_BYTEBUFFER_SPIKE_NO_READ reason=missing_file")
            return
        }
        decodeWorkerExecutor.execute {
            when (val result = RecordedHfrByteBufferViabilityProbe.run(file)) {
                is ImportValidationResult.NoRead -> Log.e(
                    logTag,
                    "RECORDED_HFR_BYTEBUFFER_SPIKE_NO_READ file=${file.displayNameOnly()} reason=${result.reason} message=${result.message.compactForLog()}",
                )
                is ImportValidationResult.Success -> Log.i(
                    logTag,
                    "RECORDED_HFR_BYTEBUFFER_SPIKE_RESULT file=${file.displayNameOnly()} ${result.value.toLogFields()}",
                )
            }
        }
    }

    private fun latestRecordedHfrMovieFile(): File? =
        getExternalFilesDir(android.os.Environment.DIRECTORY_MOVIES)
            ?.listFiles { candidate -> candidate.isFile && candidate.extension.equals("mp4", ignoreCase = true) }
            ?.maxByOrNull { it.lastModified() }

    private fun hasRecordedHfrDebugProbeIntent(): Boolean =
        isDebuggableBuild() &&
            (
                intent.getBooleanExtra("autoProbeRecordedHfrByteBuffer", false) ||
                    intent.getBooleanExtra("autoProbeRecordedHfrWindowSource", false)
            )

    private fun startRecordedHfrWindowSourceProbeIfRequested() {
        if (!intent.getBooleanExtra("autoProbeRecordedHfrWindowSource", false) || !isDebuggableBuild()) return
        val explicitPath = intent.getStringExtra("recordedHfrProbePath")
        val file = explicitPath?.let(::File) ?: latestRecordedHfrMovieFile()
        if (file == null) {
            Log.e(logTag, "RECORDED_HFR_WINDOW_SOURCE_SPIKE_NO_READ reason=missing_file")
            return
        }
        val window = ContainerTimeWindow(
            windowStartUs = intent.getLongExtra("recordedHfrProbeWindowStartUs", 0L),
            windowEndUs = intent.getLongExtra("recordedHfrProbeWindowEndUs", 200_000L),
            preImpactMarginFrames = 0,
            postImpactFrameCount = intent.intExtraOrNull("recordedHfrProbeMaxFrames") ?: 24,
            maxFrames = intent.intExtraOrNull("recordedHfrProbeMaxFrames") ?: 24,
        )
        val targetWidth = intent.intExtraOrNull("recordedHfrProbeTargetWidth") ?: 1280
        val targetHeight = intent.intExtraOrNull("recordedHfrProbeTargetHeight") ?: 720
        decodeWorkerExecutor.execute {
            when (
                val validation = AndroidRecordedHfrWindowFrameSource.create(
                    file = file,
                    window = window,
                    targetWidth = targetWidth,
                    targetHeight = targetHeight,
                    maxDecodeWallClockMillis = RECORDED_HFR_WINDOW_DECODE_TIMEOUT_MILLIS,
                )
            ) {
                is ImportValidationResult.NoRead -> Log.e(
                    logTag,
                    "RECORDED_HFR_WINDOW_SOURCE_SPIKE_NO_READ file=${file.displayNameOnly()} reason=${validation.reason} message=${validation.message.compactForLog()}",
                )
                is ImportValidationResult.Success -> {
                    val source = validation.value
                    try {
                        while (source.nextFrame() != null) {
                            // Drain one bounded window to prove the pull source on device.
                        }
                    } finally {
                        source.close()
                    }
                    val proof = source.proof
                    val terminal = source.terminalNoReadMessage()
                    val logLine = "file=${file.displayNameOnly()} decoded=${proof.emittedFrameCount} " +
                        "syncPrefix=${proof.syncPrefixFrameCount} firstPtsUs=${proof.emittedFirstPtsUs ?: -1} " +
                        "lastPtsUs=${proof.emittedLastPtsUs ?: -1} decodeMs=${proof.decodeWallClockMillis} " +
                        "timedOut=${proof.timedOut} colorFormat=${source.outputColorFormat ?: -1} " +
                        "stride=${source.outputStride ?: -1} sliceHeight=${source.outputSliceHeight ?: -1}"
                    if (terminal == null) {
                        Log.i(logTag, "RECORDED_HFR_WINDOW_SOURCE_SPIKE_RESULT $logLine")
                    } else {
                        Log.e(logTag, "RECORDED_HFR_WINDOW_SOURCE_SPIKE_NO_READ $logLine message=${terminal.compactForLog()}")
                    }
                }
            }
        }
    }

    private fun applyDebugVisualEstimateSetupFromIntent() {
        if (!isDebuggableBuild()) return
        val pointAx = intent.doubleExtraOrNull("visualEstimatePointAX")
        val pointAy = intent.doubleExtraOrNull("visualEstimatePointAY")
        val pointBx = intent.doubleExtraOrNull("visualEstimatePointBX")
        val pointBy = intent.doubleExtraOrNull("visualEstimatePointBY")
        val knownFeet = intent.doubleExtraOrNull("visualEstimateKnownFeet")
        if (pointAx != null && pointAy != null && pointBx != null && pointBy != null && knownFeet != null) {
            knownDistanceFeetText = knownFeet.format(2)
            calibrationWorkflowState = calibrationWorkflowState.select(
                pointA = ImagePoint(pointAx, pointAy),
                pointB = ImagePoint(pointBx, pointBy),
                knownDistanceFeet = knownFeet,
            )
            measurementWorkflowState = measurementWorkflowState.reduce(com.speedball.app.measurement.MeasurementWorkflowEvent.CalibrationSelected)
            phase14WorkflowState = phase14WorkflowState.reduce(
                Phase14WorkflowEvent.KnownDistanceSelected(
                    pointA = NormalizedFramePoint(pointAx / DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH, pointAy / DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT),
                    pointB = NormalizedFramePoint(pointBx / DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH, pointBy / DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT),
                    knownDistanceFeet = knownFeet,
                ),
            )
        }
        debugVisualEstimateKnownBallDiameterFeet =
            intent.doubleExtraOrNull("visualEstimateKnownBallDiameterFeet")
                ?: intent.doubleExtraOrNull("visualEstimateKnownBallDiameterInches")?.let { it / 12.0 }
        debugVisualEstimateKnownBallDiameterFeet?.let {
            phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.KnownBallDiameterChanged(it))
        }
        debugImportKnownFrameRateFps = intent.doubleExtraOrNull("visualEstimateKnownFrameRateFps")
            ?: intent.doubleExtraOrNull("importKnownFrameRateFps")
        debugImportStartFrameIndex = intent.intExtraOrNull("visualEstimateImportStartFrameIndex")
            ?: intent.intExtraOrNull("importStartFrameIndex")
            ?: 0
        debugImportFrameCount = intent.intExtraOrNull("visualEstimateImportFrameCount")
            ?: intent.intExtraOrNull("importFrameCount")
            ?: DEFAULT_IMPORT_MAX_FRAMES

        val hue = intent.doubleExtraOrNull("visualEstimateHueDeg")
        val saturation = intent.doubleExtraOrNull("visualEstimateSaturation")
        val value = intent.doubleExtraOrNull("visualEstimateValue")
        val colorPointX = intent.doubleExtraOrNull("visualEstimateColorPointX")
        val colorPointY = intent.doubleExtraOrNull("visualEstimateColorPointY")
        val roiLeft = intent.doubleExtraOrNull("visualEstimateRoiLeft")
        val roiTop = intent.doubleExtraOrNull("visualEstimateRoiTop")
        val roiRight = intent.doubleExtraOrNull("visualEstimateRoiRight")
        val roiBottom = intent.doubleExtraOrNull("visualEstimateRoiBottom")
        if (hue != null && saturation != null && value != null) {
            val colorPoint = NormalizedFramePoint(
                (colorPointX ?: DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH / 2.0) / DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH,
                (colorPointY ?: DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT / 2.0) / DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT,
            )
            val roi = if (roiLeft != null && roiTop != null && roiRight != null && roiBottom != null) {
                NormalizedFrameRect(
                    left = roiLeft / DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH,
                    top = roiTop / DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT,
                    right = roiRight / DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH,
                    bottom = roiBottom / DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT,
                )
            } else {
                NormalizedFrameRect(0.0, 0.0, 1.0, 1.0)
            }
            colorWorkflowState = colorWorkflowState.selectSample(
                sample = HsvColor(hueDegrees = hue, saturation = saturation, value = value),
                tolerance = HsvTolerance(
                    hueDegrees = intent.doubleExtraOrNull("visualEstimateHueToleranceDeg") ?: 8.0,
                    saturation = intent.doubleExtraOrNull("visualEstimateSaturationTolerance") ?: 0.12,
                    value = intent.doubleExtraOrNull("visualEstimateValueTolerance") ?: 0.12,
                ),
            )
            measurementWorkflowState = measurementWorkflowState.reduce(com.speedball.app.measurement.MeasurementWorkflowEvent.ColorSampleSelected)
            phase14WorkflowState = phase14WorkflowState
                .reduce(
                    Phase14WorkflowEvent.ColorSampleSelected(
                        point = colorPoint,
                        sample = HsvColor(hueDegrees = hue, saturation = saturation, value = value),
                        tolerance = HsvTolerance(
                            hueDegrees = intent.doubleExtraOrNull("visualEstimateHueToleranceDeg") ?: 8.0,
                            saturation = intent.doubleExtraOrNull("visualEstimateSaturationTolerance") ?: 0.12,
                            value = intent.doubleExtraOrNull("visualEstimateValueTolerance") ?: 0.12,
                        ),
                    ),
                )
                .reduce(Phase14WorkflowEvent.RegionOfInterestSelected(roi))
        }
        val levelRoll = intent.doubleExtraOrNull("visualEstimateLevelRollDeg")
        if (levelRoll != null) {
            val snapshot = LevelReferenceSnapshot(
                rollDegrees = levelRoll,
                pitchDegrees = intent.doubleExtraOrNull("visualEstimateLevelPitchDeg"),
                sampleCount = intent.intExtraOrNull("visualEstimateLevelSamples") ?: 30,
                source = LevelReferenceSource.GRAVITY_SENSOR,
                displayRotation = currentLevelReferenceDisplayRotation(),
                maxGyroMagnitudeRadPerSecond = intent.doubleExtraOrNull("visualEstimateLevelMaxGyroRadPerSec") ?: 0.0,
                capturedAtEpochMillis = System.currentTimeMillis(),
            )
            phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.LevelReferenceCaptured(snapshot))
        }
    }

    private fun updatePhase14Geometry() {
        val mode = selectedMode
        phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.PermissionChanged(hasCameraPermission()))
        phase14WorkflowState = if (mode == null) {
            phase14WorkflowState.reduce(Phase14WorkflowEvent.GeometryCleared)
        } else {
            phase14WorkflowState.reduce(Phase14WorkflowEvent.GeometryChanged(mode.toPhase14Geometry()))
        }
        applyDebugVisualEstimateSetupFromIntent()
    }

    private fun useKnownDistanceSetup() {
        updatePhase14Geometry()
        val distanceFeet = parsePositiveFeet(knownDistanceFeetText)
        if (distanceFeet == null) {
            applyKnownDistanceCalibration(
                pointA = currentCaliperPointA(),
                pointB = currentCaliperPointB(),
                knownDistanceFeet = Double.NaN,
                status = "Enter distance in feet",
            )
            return
        }
        applyKnownDistanceCalibration(
            pointA = currentCaliperPointA(),
            pointB = currentCaliperPointB(),
            knownDistanceFeet = distanceFeet,
            status = "Known distance ready",
        )
    }

    private fun useBallDiameterFallbackSetup() {
        updatePhase14Geometry()
        phase14WorkflowState = phase14WorkflowState
            .reduce(Phase14WorkflowEvent.SetupModeSelected(Phase14SetupMode.BallDiameterFallback))
            .reduce(Phase14WorkflowEvent.KnownBallDiameterChanged(DEFAULT_PHASE14_BALL_DIAMETER_FEET))
        debugVisualEstimateKnownBallDiameterFeet = DEFAULT_PHASE14_BALL_DIAMETER_FEET
        updateLegacyWorkflowFromPhase14()
        updateShellState(status = "Ball-size fallback ready")
    }

    private fun sampleDefaultEstimateColor() {
        updatePhase14Geometry()
        setupAdjustmentTarget = SetupAdjustmentTarget.ColorPoint
        samplePreviewColorAt(phase14WorkflowState.colorSamplePoint ?: DEFAULT_COLOR_SAMPLE_POINT)
    }

    private fun setDefaultEstimateRoi() {
        updatePhase14Geometry()
        phase14WorkflowState = phase14WorkflowState.reduce(
            Phase14WorkflowEvent.RegionOfInterestSelected(phase14WorkflowState.regionOfInterest ?: DEFAULT_PHASE14_ROI),
        )
        updateLegacyWorkflowFromPhase14()
        setupAdjustmentTarget = SetupAdjustmentTarget.Roi
        updateShellState(status = "ROI ready")
    }

    private fun selectSetupAdjustmentTarget(target: SetupAdjustmentTarget) {
        setupAdjustmentTarget = target
        updateShellState(status = "Setup target ${target.label}")
    }

    private fun nudgeSelectedSetupTarget(dx: Double, dy: Double) {
        phase14WorkflowState = when (setupAdjustmentTarget) {
            SetupAdjustmentTarget.CaliperA -> {
                val pointA = currentCaliperPointA()
                val pointB = currentCaliperPointB()
                phase14WorkflowState.reduce(
                    Phase14WorkflowEvent.KnownDistanceSelected(
                        pointA = pointA.nudged(dx, 0.0),
                        pointB = pointB,
                        knownDistanceFeet = parsePositiveFeet(knownDistanceFeetText) ?: Double.NaN,
                    ),
                )
            }
            SetupAdjustmentTarget.CaliperB -> {
                val pointA = currentCaliperPointA()
                val pointB = currentCaliperPointB()
                phase14WorkflowState.reduce(
                    Phase14WorkflowEvent.KnownDistanceSelected(
                        pointA = pointA,
                        pointB = pointB.nudged(dx, 0.0),
                        knownDistanceFeet = parsePositiveFeet(knownDistanceFeetText) ?: Double.NaN,
                    ),
                )
            }
            SetupAdjustmentTarget.ColorPoint -> {
                val point = phase14WorkflowState.colorSamplePoint ?: DEFAULT_COLOR_SAMPLE_POINT
                val moved = point.nudged(dx, dy)
                val sample = phase14WorkflowState.colorSample
                if (sample == null) {
                    phase14WorkflowState.reduce(Phase14WorkflowEvent.ColorSampleCleared(moved))
                } else {
                    phase14WorkflowState.reduce(
                        Phase14WorkflowEvent.ColorSampleSelected(
                            point = moved,
                            sample = sample,
                            tolerance = phase14WorkflowState.colorTolerance,
                        ),
                    )
                }
            }
            SetupAdjustmentTarget.Roi -> {
                val roi = phase14WorkflowState.regionOfInterest ?: DEFAULT_PHASE14_ROI
                phase14WorkflowState.reduce(Phase14WorkflowEvent.RegionOfInterestSelected(roi.nudged(dx, dy)))
            }
            SetupAdjustmentTarget.ImpactZone -> {
                val impactZone = phase14WorkflowState.impactZone ?: DEFAULT_PHASE14_IMPACT_ZONE
                phase14WorkflowState.reduce(Phase14WorkflowEvent.ImpactZoneSelected(impactZone.nudged(dx, dy)))
            }
            SetupAdjustmentTarget.BallBox -> {
                val ballBox = phase14WorkflowState.expectedBallBounds ?: DEFAULT_PHASE14_BALL_BOX
                phase14WorkflowState.reduce(Phase14WorkflowEvent.ExpectedBallBoundsSelected(ballBox.nudged(dx, dy)))
            }
        }
        updateLegacyWorkflowFromPhase14()
        updateShellState(status = "Setup target ${setupAdjustmentTarget.label} moved")
    }

    private fun updateKnownDistanceFeetInput(text: String) {
        knownDistanceFeetText = text
        val distanceFeet = parsePositiveFeet(text)
        if (phase14WorkflowState.calibrationPointA != null || phase14WorkflowState.calibrationPointB != null) {
            applyKnownDistanceCalibration(
                pointA = currentCaliperPointA(),
                pointB = currentCaliperPointB(),
                knownDistanceFeet = distanceFeet ?: Double.NaN,
                status = if (distanceFeet == null) "Enter distance in feet" else "Known distance ${distanceFeet.format(2)} ft",
            )
        } else {
            updateShellState(status = if (distanceFeet == null) "Enter distance in feet" else "Distance ${distanceFeet.format(2)} ft")
        }
    }

    private fun updateCalibrationPlaneDepthFeetInput(text: String) {
        calibrationPlaneDepthFeetText = text
        updateShellState(status = depthCorrectionStatus())
    }

    private fun updateBallPlaneDepthFeetInput(text: String) {
        ballPlaneDepthFeetText = text
        updateShellState(status = depthCorrectionStatus())
    }

    private fun updateMotionBlobSideRatioInput(text: String) {
        motionBlobSideRatioText = text
        val ratio = parseMotionBlobSideRatio(text)
        updateShellState(status = if (ratio == null) "Enter ball shape ratio >= 1.0" else "Ball shape ratio ${ratio.format(2)}")
    }

    private fun updateLaunchHeightFeetInput(text: String) {
        launchHeightFeetText = text
        val heightFeet = parseLaunchHeightFeet(text)
        updateShellState(status = if (heightFeet == null) "Enter launch height >= 0 ft" else "Launch height ${heightFeet.format(1)} ft")
    }

    private fun depthCorrectionStatus(): String =
        when (currentScaleModeOrNull()) {
            null -> "Enter both plane depths in feet"
            VisualEstimateScaleMode.SamePlane -> "Depth correction off"
            is VisualEstimateScaleMode.DepthCorrected -> "Depth correction ready"
        }

    private fun currentScaleModeOrNull(): VisualEstimateScaleMode? {
        val calibrationText = calibrationPlaneDepthFeetText.trim()
        val ballText = ballPlaneDepthFeetText.trim()
        if (calibrationText.isEmpty() && ballText.isEmpty()) return VisualEstimateScaleMode.SamePlane
        val calibrationDepthFeet = parsePositiveFeet(calibrationText) ?: return null
        val ballDepthFeet = parsePositiveFeet(ballText) ?: return null
        return VisualEstimateScaleMode.DepthCorrected(
            ballPlaneDepthFeet = ballDepthFeet,
            calibrationPlaneDepthFeet = calibrationDepthFeet,
        )
    }

    private fun parseMotionBlobSideRatio(text: String): Double? =
        text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 1.0 }

    private fun parseLaunchHeightFeet(text: String): Double? =
        text.trim().toDoubleOrNull()?.takeIf { it.isFinite() && it >= 0.0 }

    private fun handlePreviewTap(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
    ) {
        when (setupAdjustmentTarget) {
            SetupAdjustmentTarget.CaliperA -> {
                val point = normalizedPreviewPoint(x, y, viewWidth, viewHeight) ?: return updateShellState(status = "Tap inside camera feed")
                setCaliperPoint(point, SetupAdjustmentTarget.CaliperA, "Caliper A set")
            }
            SetupAdjustmentTarget.CaliperB -> {
                val point = normalizedPreviewPoint(x, y, viewWidth, viewHeight) ?: return updateShellState(status = "Tap inside camera feed")
                setCaliperPoint(point, SetupAdjustmentTarget.CaliperB, "Caliper B set")
            }
            SetupAdjustmentTarget.ColorPoint -> {
                val point = normalizedPreviewPoint(x, y, viewWidth, viewHeight) ?: return updateShellState(status = "Tap inside camera feed")
                samplePreviewColorAt(point)
            }
            SetupAdjustmentTarget.Roi -> {
                val point = normalizedPreviewPoint(x, y, viewWidth, viewHeight) ?: return updateShellState(status = "Tap inside camera feed")
                phase14WorkflowState = phase14WorkflowState.reduce(
                    Phase14WorkflowEvent.RegionOfInterestSelected(centerRoiAt(point)),
                )
                updateLegacyWorkflowFromPhase14()
                updateShellState(status = "ROI centered")
            }
            SetupAdjustmentTarget.ImpactZone -> {
                val point = normalizedOverlayPoint(x, y, viewWidth, viewHeight) ?: return updateShellState(status = "Tap inside setup overlay")
                phase14WorkflowState = phase14WorkflowState.reduce(
                    Phase14WorkflowEvent.ImpactZoneSelected(moveNearestPolygonCorner(phase14WorkflowState.impactZone ?: DEFAULT_PHASE14_IMPACT_ZONE, point)),
                )
                updateLegacyWorkflowFromPhase14()
                updateShellState(status = "Impact zone point moved")
            }
            SetupAdjustmentTarget.BallBox -> {
                val point = normalizedOverlayPoint(x, y, viewWidth, viewHeight) ?: return updateShellState(status = "Tap inside setup overlay")
                samplePreviewColorAt(normalizedPreviewPoint(x, y, viewWidth, viewHeight) ?: point)
                phase14WorkflowState = phase14WorkflowState.reduce(
                    Phase14WorkflowEvent.ExpectedBallBoundsSelected(moveNearestPolygonCorner(phase14WorkflowState.expectedBallBounds ?: centerBallBoxAt(point), point)),
                )
                updateLegacyWorkflowFromPhase14()
                updateShellState(status = "Ball box point moved")
            }
        }
    }

    private fun setImpactZoneCornerFromPreview(
        cornerIndex: Int,
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
    ) {
        val point = normalizedOverlayPoint(x, y, viewWidth, viewHeight) ?: return
        val current = phase14WorkflowState.impactZone ?: DEFAULT_PHASE14_IMPACT_ZONE
        val points = current.points.toMutableList()
        if (cornerIndex !in points.indices) return
        points[cornerIndex] = point.coercedInFrame()
        phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.ImpactZoneSelected(NormalizedFramePolygon(points)))
        updateLegacyWorkflowFromPhase14()
        updateShellState(status = "Impact zone corner ${cornerIndex + 1} moved")
    }

    private fun setBallBoxCornerFromPreview(
        cornerIndex: Int,
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
    ) {
        val point = normalizedOverlayPoint(x, y, viewWidth, viewHeight) ?: return
        val current = phase14WorkflowState.expectedBallBounds ?: DEFAULT_PHASE14_BALL_BOX
        val points = current.points.toMutableList()
        if (cornerIndex !in points.indices) return
        points[cornerIndex] = point.coercedInFrame()
        phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.ExpectedBallBoundsSelected(NormalizedFramePolygon(points)))
        updateLegacyWorkflowFromPhase14()
        updateShellState(status = "Ball box corner ${cornerIndex + 1} moved")
    }

    private fun setCaliperLineFromPreview(
        target: SetupAdjustmentTarget,
        x: Float,
        viewWidth: Int,
        viewHeight: Int,
    ) {
        if (viewWidth <= 0 || viewHeight <= 0) return
        val clampedX = x.coerceIn(0f, viewWidth.toFloat())
        val point = NormalizedFramePoint((clampedX / viewWidth.toFloat()).toDouble(), 0.5)
        setupAdjustmentTarget = target
        setCaliperPoint(point, target, "Caliper ${target.label} moved")
    }

    private fun setCaliperPoint(
        point: NormalizedFramePoint,
        target: SetupAdjustmentTarget,
        status: String,
    ) {
        val pointA = currentCaliperPointA()
        val pointB = currentCaliperPointB()
        val moved = point.coercedInFrame()
        applyKnownDistanceCalibration(
            pointA = if (target == SetupAdjustmentTarget.CaliperA) moved else pointA,
            pointB = if (target == SetupAdjustmentTarget.CaliperB) moved else pointB,
            knownDistanceFeet = parsePositiveFeet(knownDistanceFeetText) ?: Double.NaN,
            status = status,
        )
    }

    private fun applyKnownDistanceCalibration(
        pointA: NormalizedFramePoint,
        pointB: NormalizedFramePoint,
        knownDistanceFeet: Double,
        status: String,
    ) {
        updatePhase14Geometry()
        phase14WorkflowState = phase14WorkflowState.reduce(
            Phase14WorkflowEvent.KnownDistanceSelected(
                pointA = pointA.coercedInFrame(),
                pointB = pointB.coercedInFrame(),
                knownDistanceFeet = knownDistanceFeet,
            ),
        )
        updateLegacyWorkflowFromPhase14()
        updateShellState(status = status)
    }

    private fun samplePreviewColorAt(point: NormalizedFramePoint) {
        val surface = previewSurface
        val mode = selectedMode
        if (surface == null || mode == null) {
            phase14WorkflowState = phase14WorkflowState.reduce(
                Phase14WorkflowEvent.ColorSampleCleared(point),
            )
            updateLegacyWorkflowFromPhase14()
            updateShellState(status = "Live feed required for color")
            return
        }
        val sampleRect = sampleRectFor(point, mode.width, mode.height)
        val bitmap = Bitmap.createBitmap(sampleRect.width(), sampleRect.height(), Bitmap.Config.ARGB_8888)
        try {
            PixelCopy.request(
                surface,
                sampleRect,
                bitmap,
                { result ->
                    if (result == PixelCopy.SUCCESS) {
                        val hsv = averageBitmapHsv(bitmap)
                        bitmap.recycle()
                        phase14WorkflowState = phase14WorkflowState.reduce(
                            Phase14WorkflowEvent.ColorSampleSelected(
                                point = point,
                                sample = hsv,
                                tolerance = DEFAULT_PHASE14_COLOR_TOLERANCE,
                            ),
                        )
                        updateLegacyWorkflowFromPhase14()
                        updateShellState(status = "Color sampled from live feed")
                    } else {
                        bitmap.recycle()
                        clearPreviewColorSample(point)
                    }
                },
                mainHandler,
            )
        } catch (_: IllegalArgumentException) {
            bitmap.recycle()
            clearPreviewColorSample(point)
        }
    }

    private fun clearPreviewColorSample(point: NormalizedFramePoint) {
        phase14WorkflowState = phase14WorkflowState.reduce(
            Phase14WorkflowEvent.ColorSampleCleared(point),
        )
        updateLegacyWorkflowFromPhase14()
        updateShellState(status = "Color sample failed")
    }

    private fun normalizedPreviewPoint(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): NormalizedFramePoint? {
        val mode = selectedMode ?: return null
        if (viewWidth <= 0 || viewHeight <= 0) return null
        return PreviewFrameTransform(
            view = FrameDimensions(viewWidth, viewHeight),
            source = FrameDimensions(mode.width, mode.height),
            scaleMode = PreviewScaleMode.FitCenter,
            sourceToViewRotationDegrees = previewRotationDegrees,
        ).viewPointToNormalized(x.toDouble(), y.toDouble())
    }

    private fun normalizedOverlayPoint(
        x: Float,
        y: Float,
        viewWidth: Int,
        viewHeight: Int,
    ): NormalizedFramePoint? {
        if (viewWidth <= 0 || viewHeight <= 0) return null
        return NormalizedFramePoint(
            x = (x / viewWidth.toFloat()).coerceIn(0f, 1f).toDouble(),
            y = (y / viewHeight.toFloat()).coerceIn(0f, 1f).toDouble(),
        )
    }

    private fun currentCaliperPointA(): NormalizedFramePoint =
        phase14WorkflowState.calibrationPointA ?: DEFAULT_CALIPER_POINT_A

    private fun currentCaliperPointB(): NormalizedFramePoint =
        phase14WorkflowState.calibrationPointB ?: DEFAULT_CALIPER_POINT_B

    private fun centerRoiAt(point: NormalizedFramePoint): NormalizedFrameRect {
        val current = phase14WorkflowState.regionOfInterest ?: DEFAULT_PHASE14_ROI
        val width = current.right - current.left
        val height = current.bottom - current.top
        val left = (point.x - width / 2.0).coerceIn(0.0, 1.0 - width)
        val top = (point.y - height / 2.0).coerceIn(0.0, 1.0 - height)
        return NormalizedFrameRect(left, top, left + width, top + height)
    }

    private fun centerBallBoxAt(point: NormalizedFramePoint): NormalizedFramePolygon =
        DEFAULT_PHASE14_BALL_BOX.centeredAt(point)

    private fun moveNearestPolygonCorner(
        polygon: NormalizedFramePolygon,
        point: NormalizedFramePoint,
    ): NormalizedFramePolygon {
        val points = polygon.points.toMutableList()
        val index = points.indices.minByOrNull { pointIndex ->
            val dx = points[pointIndex].x - point.x
            val dy = points[pointIndex].y - point.y
            dx * dx + dy * dy
        } ?: return polygon
        points[index] = point.coercedInFrame()
        return NormalizedFramePolygon(points)
    }

    private fun sampleRectFor(
        point: NormalizedFramePoint,
        width: Int,
        height: Int,
    ): Rect {
        val centerX = (point.x * width).toInt().coerceIn(0, width - 1)
        val centerY = (point.y * height).toInt().coerceIn(0, height - 1)
        val radius = COLOR_SAMPLE_RADIUS_PX
        return Rect(
            (centerX - radius).coerceAtLeast(0),
            (centerY - radius).coerceAtLeast(0),
            (centerX + radius + 1).coerceAtMost(width),
            (centerY + radius + 1).coerceAtMost(height),
        )
    }

    private fun averageBitmapHsv(bitmap: Bitmap): HsvColor {
        var red = 0L
        var green = 0L
        var blue = 0L
        val count = bitmap.width * bitmap.height
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val pixel = bitmap.getPixel(x, y)
                red += (pixel shr 16) and 0xff
                green += (pixel shr 8) and 0xff
                blue += pixel and 0xff
            }
        }
        val averagedArgb = (0xff shl 24) or
            (((red / count).toInt() and 0xff) shl 16) or
            (((green / count).toInt() and 0xff) shl 8) or
            ((blue / count).toInt() and 0xff)
        return ColorMath.argbToHsv(averagedArgb)
    }

    private fun requestImportVideo() {
        importWorkflowState = importWorkflowState.reduce(ImportWorkflowEvent.RequestPicker)
        lastImportExportText = null
        updateShellState(status = "Select import video")
        importDocumentPicker.launch(arrayOf("video/*"))
    }

    private fun startDebugImportIfRequested() {
        if (!isDebuggableBuild()) return
        if (!intent.getBooleanExtra("debugAutoImport", false)) return
        val uri = intent.data ?: return
        handleImportSelection(uri)
    }

    private fun handleImportSelection(uri: Uri) {
        val access = ImportContentAccess.validate(
            ImportContentSelection(
                reference = uri.toString(),
                selectedByUser = true,
                persistableReadGrantAvailable = false,
                retainBeyondActiveSession = false,
            ),
        )
        if (access is ImportValidationResult.NoRead) {
            importWorkflowState = importWorkflowState.reduce(ImportWorkflowEvent.ImportFailed(access.reason, access.message))
            updateShellState(status = "Import no-read")
            return
        }
        access as ImportValidationResult.Success
        importWorkflowState = importWorkflowState.reduce(ImportWorkflowEvent.SelectionValidated(access.value))
        updateShellState(status = "Import validating")
        decodeWorkerExecutor.execute {
            val outcome = runImportEstimate(uri)
            runOnUiThread {
                finishImportRunOutcome(outcome, completePrefix = "Import")
            }
        }
    }

    private fun finishImportRunOutcome(
        outcome: ImportRunOutcome,
        completePrefix: String,
    ) {
        when (outcome) {
            is ImportRunOutcome.NoRead -> {
                importWorkflowState = importWorkflowState.reduce(ImportWorkflowEvent.ImportFailed(outcome.reason, outcome.message))
                val summary = outcome.toSavedSummary()
                savedResultHistory.save(summary)
                persistSavedResults()
                lastImportExportText = ImportEvidenceExporter.format(summary)
                Log.i(logTag, "IMPORT_ESTIMATE_NO_READ reason=${outcome.reason} message=${outcome.message.compactForLog()}")
                if (outcome.sourceKind == ImportResultSourceKind.RECORDED_ESTIMATE) {
                    val visualNoRead = outcome.visualNoRead
                    finishRecordedVisualEstimateNoRead(
                        reason = visualNoRead?.reason ?: outcome.reason.toVisualEstimateNoReadReason(),
                        message = visualNoRead?.message ?: outcome.message,
                        failure = false,
                        proof = outcome.captureProof,
                        status = "$completePrefix no-read",
                    )
                    return
                }
                updateShellState(status = "$completePrefix no-read")
            }
            is ImportRunOutcome.Estimate -> {
                importWorkflowState = importWorkflowState
                    .reduce(ImportWorkflowEvent.MetadataValidated(outcome.metadata))
                    .reduce(ImportWorkflowEvent.FramesExtracted(outcome.frames, outcome.timing))
                    .reduce(ImportWorkflowEvent.StartEstimate)
                    .reduce(ImportWorkflowEvent.EstimateCompleted(outcome.estimate))
                val summary = outcome.toSavedSummary()
                savedResultHistory.save(summary)
                persistSavedResults()
                lastImportExportText = ImportEvidenceExporter.format(summary)
                visualEstimateOutcomeUiLines(outcome.estimate).forEach { line ->
                    Log.i(logTag, "IMPORT_ESTIMATE_RESULT ${line.compactForLog()}")
                }
                if (outcome.sourceKind == ImportResultSourceKind.RECORDED_ESTIMATE) {
                    finishRecordedVisualEstimateOutcome(outcome.estimate, outcome.captureProof)
                    return
                }
                updateShellState(
                    status = when (outcome.estimate) {
                        is VisualEstimateOutcome.Success -> "$completePrefix estimate complete"
                        is VisualEstimateOutcome.NoRead -> "$completePrefix estimate no-read"
                    },
                )
            }
        }
        restartLiveCameraFeedIfReady()
    }

    private fun finishRecordedVisualEstimateOutcome(
        outcome: VisualEstimateOutcome,
        proof: VisualEstimateCaptureProof?,
    ) {
        lastDirectVisualEstimateOutcome = outcome
        phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.CaptureCompleted(outcome))
        captureStatus = when (outcome) {
            is VisualEstimateOutcome.Success -> "Recorded estimate complete"
            is VisualEstimateOutcome.NoRead -> "Recorded estimate no-read"
        }
        currentVisualEstimateReport = visualEstimateReportFor(
            attemptId = visualEstimateAttemptId,
            outcome = outcome,
            captureProof = proof?.withAttemptId(visualEstimateAttemptId),
        )
        scheduleReportAutoClear(currentVisualEstimateReport)
        dismissedVisualEstimateAttemptId = null
        visualEstimateOutcomeUiLines(outcome).forEach { line ->
            Log.i(logTag, "RECORDED_HFR_ESTIMATE_RESULT ${line.compactForLog()}")
        }
        runCommandState = if (currentVisualEstimateReport?.kind == VisualEstimateReportKind.Success) {
            SpeedBallRunCommandState.Reporting
        } else {
            SpeedBallRunCommandState.ManualReady
        }
        updateShellState(status = captureStatus)
        restartLiveCameraFeedIfReady()
        if (appMode == SpeedBallAppMode.Run && currentVisualEstimateReport?.kind != VisualEstimateReportKind.Success) {
            resumeRunModeCommandPath()
        }
    }

    private fun finishRecordedVisualEstimateNoRead(
        reason: VisualEstimateNoReadReason,
        message: String,
        failure: Boolean,
        proof: VisualEstimateCaptureProof?,
        status: String,
    ) {
        val noRead = VisualEstimateOutcome.NoRead(reason = reason, message = message)
        lastDirectVisualEstimateOutcome = noRead
        phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.CaptureCompleted(noRead))
        captureStatus = status
        currentVisualEstimateReport = visualEstimateReportFor(
            attemptId = visualEstimateAttemptId,
            outcome = noRead,
            failure = failure,
            captureProof = proof?.withAttemptId(visualEstimateAttemptId),
        )
        scheduleReportAutoClear(currentVisualEstimateReport)
        dismissedVisualEstimateAttemptId = null
        visualEstimateOutcomeUiLines(noRead).forEach { line ->
            Log.i(logTag, "RECORDED_HFR_ESTIMATE_RESULT ${line.compactForLog()}")
        }
        runCommandState = SpeedBallRunCommandState.ManualReady
        updateShellState(status = captureStatus)
        restartLiveCameraFeedIfReady()
        if (appMode == SpeedBallAppMode.Run) resumeRunModeCommandPath()
    }

    private fun runImportEstimate(uri: Uri): ImportRunOutcome {
        val importFrameCount = debugImportFrameCount.coerceIn(2, DEFAULT_IMPORT_MAX_FRAMES)
        val importStartFrameIndex = debugImportStartFrameIndex.coerceAtLeast(0)
        Log.i(logTag, "IMPORT_ESTIMATE_STAGE metadata startFrame=$importStartFrameIndex frameCount=$importFrameCount")
        val metadata = when (
            val validation = AndroidImportVideoFrameSource.readMetadata(
                context = this,
                uri = uri,
                mimeType = contentResolver.getType(uri),
                maxSamplesToProbe = importFrameCount,
            )
        ) {
            is ImportValidationResult.NoRead -> return ImportRunOutcome.NoRead(
                validation.reason,
                validation.message,
            )
            is ImportValidationResult.Success -> validation.value
        }
        importWorkflowState = importWorkflowState.reduce(ImportWorkflowEvent.MetadataValidated(metadata))
        Log.i(logTag, "IMPORT_ESTIMATE_STAGE source")
        val source = when (
            val validation = AndroidImportVideoFrameSource.create(
                context = this,
                uri = uri,
                targetWidth = DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH,
                targetHeight = DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT,
                maxFrames = importFrameCount,
                startFrameIndex = importStartFrameIndex,
            )
        ) {
            is ImportValidationResult.NoRead -> return ImportRunOutcome.NoRead(
                validation.reason,
                validation.message,
                metadata,
            )
            is ImportValidationResult.Success -> validation.value
        }
        Log.i(logTag, "IMPORT_ESTIMATE_STAGE extract")
        val frames = when (
            val extraction = ImportFrameExtractor.extract(
                source = source,
                config = ImportFrameExtractionConfig(
                    maxFrames = importFrameCount,
                    maxWidth = DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH,
                    maxHeight = DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT,
                    maxTotalPixels = DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH.toLong() *
                        DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT.toLong() *
                        importFrameCount.toLong(),
                ),
            )
        ) {
            is ImportValidationResult.NoRead -> return ImportRunOutcome.NoRead(
                extraction.reason,
                extraction.message,
                metadata,
            )
            is ImportValidationResult.Success -> extraction.value
        }
        Log.i(logTag, "IMPORT_ESTIMATE_STAGE timing frames=${frames.frames.size}")
        val timing = when (val reconciliation = reconcileImportTiming(frames)) {
            is ImportValidationResult.NoRead -> return ImportRunOutcome.NoRead(
                reason = reconciliation.reason,
                message = reconciliation.message,
                metadata = metadata,
                frameCount = frames.frames.size,
            )
            is ImportValidationResult.Success -> reconciliation.value
        }
        Log.i(logTag, "IMPORT_ESTIMATE_STAGE estimate")
        val config = buildImportEstimateConfig(metadata)
            ?: return ImportRunOutcome.NoRead(
                reason = ImportNoReadReason.INVALID_METADATA,
                message = "Import estimate needs distance setup, color sample, and ROI before processing.",
                metadata = metadata,
                timing = timing,
                frameCount = frames.frames.size,
            )
        val estimate = ImportEstimatePipeline.estimate(
            frames = frames,
            timing = timing,
            calibration = config.calibration,
            config = config.framePipelineConfig,
        )
        return ImportRunOutcome.Estimate(metadata, frames, timing, estimate)
    }

    private fun runRecordedEstimate(
        file: File,
        fps: Int,
        diagnostics: BurstDiagnostics,
    ): ImportRunOutcome {
        val maxSamplesToProbe = (diagnostics.uniqueTimestampCount + 1).coerceAtLeast(DEFAULT_IMPORT_MAX_FRAMES)
        val importFrameCount = DEFAULT_IMPORT_MAX_FRAMES
        Log.i(logTag, "RECORDED_ESTIMATE_STAGE metadata frameCount=$importFrameCount")
        val metadata = when (
            val validation = AndroidImportVideoFrameSource.readMetadata(
                file = file,
                maxSamplesToProbe = maxSamplesToProbe,
            )
        ) {
            is ImportValidationResult.NoRead -> return ImportRunOutcome.NoRead(
                validation.reason,
                validation.message,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
            )
            is ImportValidationResult.Success -> validation.value
        }
        val workingResolution = when (
            val selected = RecordedHfrWorkingResolutionSelector.select(
                sourceWidth = if (metadata.rotationDegrees == 90 || metadata.rotationDegrees == 270) metadata.height else metadata.width,
                sourceHeight = if (metadata.rotationDegrees == 90 || metadata.rotationDegrees == 270) metadata.width else metadata.height,
            )
        ) {
            is ImportValidationResult.NoRead -> return ImportRunOutcome.NoRead(
                selected.reason,
                selected.message,
                metadata,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
            )
            is ImportValidationResult.Success -> selected.value
        }
        val runtimeConfig = buildRecordedEstimateConfig(metadata, workingResolution)
            ?: return ImportRunOutcome.NoRead(
                reason = ImportNoReadReason.INVALID_METADATA,
                message = "Recorded estimate needs valid distance setup before processing.",
                metadata = metadata,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
            )
        Log.i(logTag, "RECORDED_ESTIMATE_STAGE source")
        val source = when (
            val validation = AndroidImportVideoFrameSource.create(
                context = this,
                file = file,
                targetWidth = workingResolution.working.width,
                targetHeight = workingResolution.working.height,
                maxFrames = importFrameCount,
            )
        ) {
            is ImportValidationResult.NoRead -> return ImportRunOutcome.NoRead(
                validation.reason,
                validation.message,
                metadata,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
            )
            is ImportValidationResult.Success -> validation.value
        }
        Log.i(logTag, "RECORDED_ESTIMATE_STAGE streaming")
        val estimate = RecordedHfrStreamingEstimate.estimate(
            source = source,
            config = RecordedHfrStreamingEstimateConfig(
                frameIntervalSeconds = 1.0 / fps.toDouble(),
                calibration = runtimeConfig.calibration,
                framePipelineConfig = runtimeConfig.framePipelineConfig,
                maxScannedFrames = importFrameCount,
                maxRetainedCandidateFrames = RECORDED_HFR_MAX_RETAINED_CANDIDATE_FRAMES,
                maxProofFrames = VisualEstimateCaptureProofBuilder.DEFAULT_MAX_PROOF_FRAMES,
                proofThumbnailMaxWidth = RECORDED_HFR_PROOF_THUMBNAIL_MAX_WIDTH,
                proofThumbnailMaxHeight = RECORDED_HFR_PROOF_THUMBNAIL_MAX_HEIGHT,
                motionDetectorConfig = runtimeConfig.motionDetectorConfig ?: RecordedHfrMotionDetectorConfig(),
            ),
        )
        logMotionScoutSelection(estimate.motionScoutSelection, window = false)
        val gateValidation = RecordedHfrCaptureGate.validate(
            metadataSampleCount = metadata.sampleCount,
            scannedFrameCount = estimate.scannedFrameCount,
            retainedCandidateFrameCount = estimate.retainedCandidateFrameCount,
            diagnostics = diagnostics,
            metadata = metadata,
        )
        val gateNoRead = if (gateValidation is ImportValidationResult.NoRead) {
            VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                message = gateValidation.message,
                diagnostics = when (val outcome = estimate.outcome) {
                    is VisualEstimateOutcome.Success -> outcome.diagnostics
                    is VisualEstimateOutcome.NoRead -> outcome.diagnostics
                },
            )
        } else {
            null
        }
        val gate = (gateValidation as? ImportValidationResult.Success)?.value
        val proofTrace = when (val outcome = estimate.outcome) {
            is VisualEstimateOutcome.NoRead -> estimate.detectorTrace.withOutcome(outcome)
            is VisualEstimateOutcome.Success -> gateNoRead?.let { estimate.detectorTrace.withOutcome(it) } ?: estimate.detectorTrace
        }
        val proof = VisualEstimateCaptureProofBuilder.buildFromThumbnails(
            attemptId = visualEstimateAttemptId,
            scannedFrameCount = estimate.scannedFrameCount,
            frameAvailableCallbackCount = estimate.scannedFrameCount,
            captureResultCallbackCount = diagnostics.callbackCount,
            uniqueSensorTimestampCount = diagnostics.uniqueTimestampCount,
            readbackWidth = workingResolution.working.width,
            readbackHeight = workingResolution.working.height,
            trace = proofTrace,
            thumbnails = estimate.proofThumbnails,
            sourceKind = "RECORDED_HFR",
            sourceWidth = workingResolution.source.width,
            sourceHeight = workingResolution.source.height,
            workingWidth = workingResolution.working.width,
            workingHeight = workingResolution.working.height,
            decodedFrameCount = metadata.sampleCount,
            requestedFps = fps,
            dropGateVerdict = gate?.dropVerdict ?: "NO_READ",
            cadenceGateVerdict = gate?.cadenceVerdict ?: if (diagnostics.medianGapPassesRateBand && diagnostics.captureProofPasses) "PASS" else "NO_READ",
        )
        if (estimate.outcome is VisualEstimateOutcome.NoRead) {
            val noRead = estimate.outcome
            Log.i(
                logTag,
                "RECORDED_HFR_ESTIMATE_NO_READ attempt=$visualEstimateAttemptId source=${workingResolution.source.width}x${workingResolution.source.height} " +
                    "working=${workingResolution.working.width}x${workingResolution.working.height} decoded=${metadata.sampleCount ?: 0} " +
                    "scanned=${estimate.scannedFrameCount} candidates=${estimate.retainedCandidateFrameCount} selected=${estimate.selectedSampleCount} " +
                    "uniqueSensorTs=${diagnostics.uniqueTimestampCount} drop=${gate?.dropVerdict ?: "NO_READ"} cadence=${proof.cadenceGateVerdict} " +
                    "reason=${noRead.reason}",
            )
            return ImportRunOutcome.NoRead(
                reason = if (noRead.reason == VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED) {
                    ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED
                } else {
                    ImportNoReadReason.NO_TRUSTWORTHY_TIMING
                },
                message = noRead.message,
                metadata = metadata,
                timing = estimate.timing,
                frameCount = estimate.scannedFrameCount,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
                captureProof = proof,
                visualNoRead = noRead,
            )
        }
        if (gateNoRead != null || gateValidation is ImportValidationResult.NoRead) {
            val validation = gateValidation as ImportValidationResult.NoRead
            Log.i(
                logTag,
                "RECORDED_HFR_ESTIMATE_NO_READ attempt=$visualEstimateAttemptId source=${workingResolution.source.width}x${workingResolution.source.height} " +
                    "working=${workingResolution.working.width}x${workingResolution.working.height} decoded=${metadata.sampleCount ?: 0} " +
                    "scanned=${estimate.scannedFrameCount} candidates=${estimate.retainedCandidateFrameCount} selected=${estimate.selectedSampleCount} " +
                    "uniqueSensorTs=${diagnostics.uniqueTimestampCount} drop=NO_READ cadence=${proof.cadenceGateVerdict}",
            )
            return ImportRunOutcome.NoRead(
                validation.reason,
                validation.message,
                metadata,
                timing = estimate.timing,
                frameCount = estimate.scannedFrameCount,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
                captureProof = proof,
                visualNoRead = gateNoRead,
            )
        }
        gate ?: return ImportRunOutcome.NoRead(
            ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
            "Recorded capture gate did not return a usable proof.",
            metadata,
            timing = estimate.timing,
            frameCount = estimate.scannedFrameCount,
            sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
            captureProof = proof,
        )
        if (estimate.timing == null) {
            val noRead = VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                "Recorded-HFR estimate did not produce retained-frame timing.",
            )
            Log.i(
                logTag,
                "RECORDED_HFR_ESTIMATE_NO_READ attempt=$visualEstimateAttemptId source=${workingResolution.source.width}x${workingResolution.source.height} " +
                    "working=${workingResolution.working.width}x${workingResolution.working.height} decoded=${metadata.sampleCount ?: 0} " +
                    "scanned=${estimate.scannedFrameCount} candidates=${estimate.retainedCandidateFrameCount} selected=${estimate.selectedSampleCount} " +
                    "uniqueSensorTs=${diagnostics.uniqueTimestampCount} drop=${gate.dropVerdict} cadence=${proof.cadenceGateVerdict}",
            )
            return ImportRunOutcome.NoRead(
                ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
                noRead.message,
                metadata,
                timing = estimate.timing,
                frameCount = estimate.scannedFrameCount,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
                captureProof = proof,
                visualNoRead = noRead,
            )
        }
        Log.i(
                logTag,
                "RECORDED_HFR_ESTIMATE_COMPLETE attempt=$visualEstimateAttemptId source=${workingResolution.source.width}x${workingResolution.source.height} " +
                    "working=${workingResolution.working.width}x${workingResolution.working.height} metadataSamples=${gate.metadataSampleCount} " +
                    "scanned=${gate.scannedFrameCount} candidates=${gate.retainedCandidateFrameCount} selected=${estimate.selectedSampleCount} " +
                    "proofFrames=${estimate.retainedProofFrameCount} uniqueSensorTs=${gate.uniqueSensorTimestampCount} drop=${gate.dropVerdict} cadence=${gate.cadenceVerdict}",
        )
        return ImportRunOutcome.Estimate(
            metadata = metadata,
            frames = ImportVideoFrameSequence(emptyList()),
            timing = estimate.timing,
            estimate = estimate.outcome,
            sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
            captureProof = proof,
            frameCount = estimate.scannedFrameCount,
        )
    }

    private fun runRecordedWindowEstimate(
        file: File,
        fps: Int,
        diagnostics: BurstDiagnostics,
        mapping: ImpactWindowMapping,
    ): ImportRunOutcome {
        val importFrameCount = mapping.window.maxFrames
        Log.i(logTag, "RECORDED_ESTIMATE_STAGE metadata frameCount=$importFrameCount window=true")
        val metadata = when (
            val validation = AndroidImportVideoFrameSource.readMetadata(
                file = file,
                maxSamplesToProbe = (diagnostics.uniqueTimestampCount + 1).coerceAtLeast(DEFAULT_IMPORT_MAX_FRAMES),
            )
        ) {
            is ImportValidationResult.NoRead -> return ImportRunOutcome.NoRead(
                validation.reason,
                validation.message,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
            )
            is ImportValidationResult.Success -> validation.value
        }
        val workingResolution = when (
            val selected = RecordedHfrWorkingResolutionSelector.select(
                sourceWidth = if (metadata.rotationDegrees == 90 || metadata.rotationDegrees == 270) metadata.height else metadata.width,
                sourceHeight = if (metadata.rotationDegrees == 90 || metadata.rotationDegrees == 270) metadata.width else metadata.height,
            )
        ) {
            is ImportValidationResult.NoRead -> return ImportRunOutcome.NoRead(
                selected.reason,
                selected.message,
                metadata,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
            )
            is ImportValidationResult.Success -> selected.value
        }
        val runtimeConfig = buildRecordedEstimateConfig(metadata, workingResolution)
            ?: return ImportRunOutcome.NoRead(
                reason = ImportNoReadReason.INVALID_METADATA,
                message = "Recorded estimate needs valid distance setup before processing.",
                metadata = metadata,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
            )
        val windowStartedAtNanos = System.nanoTime()
        val windowDeadlineNanos = windowStartedAtNanos + RECORDED_HFR_WINDOW_DECODE_TIMEOUT_MILLIS * 1_000_000L
        val windowCancellation = ImportCancellationSignal { System.nanoTime() > windowDeadlineNanos }
        val earlyExposureNoRead = buildRecordedHfrExposureNoRead(diagnostics, null)
        Log.i(logTag, "RECORDED_ESTIMATE_STAGE source window=true")
        val source = when (
            val validation = AndroidRecordedHfrWindowFrameSource.create(
                file = file,
                window = mapping.window,
                targetWidth = workingResolution.working.width,
                targetHeight = workingResolution.working.height,
                maxDecodeWallClockMillis = RECORDED_HFR_WINDOW_DECODE_TIMEOUT_MILLIS,
                sourceMotionScoutConfig = RecordedHfrMotionScoutConfig(),
            )
        ) {
            is ImportValidationResult.NoRead -> return ImportRunOutcome.NoRead(
                validation.reason,
                validation.message,
                metadata,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
            )
            is ImportValidationResult.Success -> validation.value
        }
        Log.i(logTag, "RECORDED_ESTIMATE_STAGE streaming window=true proofOnly=${earlyExposureNoRead != null}")
        val estimate = RecordedHfrStreamingEstimate.estimate(
            source = source,
            config = RecordedHfrStreamingEstimateConfig(
                frameIntervalSeconds = 1.0 / fps.toDouble(),
                calibration = runtimeConfig.calibration,
                framePipelineConfig = runtimeConfig.framePipelineConfig,
                maxScannedFrames = mapping.window.maxFrames,
                maxRetainedCandidateFrames = RECORDED_HFR_MAX_RETAINED_CANDIDATE_FRAMES.coerceAtMost(mapping.window.maxFrames),
                maxProofFrames = VisualEstimateCaptureProofBuilder.DEFAULT_MAX_PROOF_FRAMES,
                proofThumbnailMaxWidth = RECORDED_HFR_PROOF_THUMBNAIL_MAX_WIDTH,
                proofThumbnailMaxHeight = RECORDED_HFR_PROOF_THUMBNAIL_MAX_HEIGHT,
                timingMode = RecordedHfrStreamingTimingMode.CONTAINER_PTS_DELTAS,
                proofOnly = earlyExposureNoRead != null,
                motionDetectorConfig = runtimeConfig.motionDetectorConfig ?: RecordedHfrMotionDetectorConfig(),
                motionScoutConfig = RecordedHfrMotionScoutConfig(enabled = false),
            ),
            cancellationSignal = windowCancellation,
        )
        logMotionScoutSelection(source.sourceMotionScoutSelection ?: estimate.motionScoutSelection, window = true)
        val decodeProof = source.proof
        val terminalNoReadMessage = source.terminalNoReadMessage()
        Log.i(
            logTag,
            "RECORDED_ESTIMATE_WINDOW decoded=${decodeProof.emittedFrameCount} syncPrefix=${decodeProof.syncPrefixFrameCount} " +
                "firstPtsUs=${decodeProof.emittedFirstPtsUs ?: -1} lastPtsUs=${decodeProof.emittedLastPtsUs ?: -1} " +
                "decodeMs=${decodeProof.decodeWallClockMillis} timedOut=${decodeProof.timedOut} " +
                "colorFormat=${source.outputColorFormat ?: -1} stride=${source.outputStride ?: -1} sliceHeight=${source.outputSliceHeight ?: -1} " +
                diagnostics.exposureSummary(),
        )
        val decodedWindowValidation = RecordedHfrDecodedWindowValidator.validate(
            proof = decodeProof,
            requestedMaxFrames = mapping.window.maxFrames,
            minUsableFrames = RECORDED_HFR_WINDOW_MIN_USABLE_FRAMES,
            maxDecodeWallClockMillis = RECORDED_HFR_WINDOW_DECODE_TIMEOUT_MILLIS,
        )
        val gateValidation = RecordedHfrWindowCaptureGate.validate(
            metadataSampleCount = metadata.sampleCount,
            decodedWindowFrameCount = decodeProof.emittedFrameCount,
            requestedWindowFrameCount = mapping.window.maxFrames,
            minUsableFrameCount = RECORDED_HFR_WINDOW_MIN_USABLE_FRAMES,
            diagnostics = diagnostics,
            metadata = metadata,
            windowStartUs = mapping.window.windowStartUs,
            windowEndUs = mapping.window.windowEndUs,
            emittedFirstPtsUs = decodeProof.emittedFirstPtsUs,
            emittedLastPtsUs = decodeProof.emittedLastPtsUs,
            sourceWidth = workingResolution.source.width,
            sourceHeight = workingResolution.source.height,
            proofFrameCount = estimate.retainedProofFrameCount,
            sourceValidityPasses = estimate.sourceValidity.passes,
        )
        val gate = (gateValidation as? ImportValidationResult.Success)?.value
        val gateNoRead = if (gateValidation is ImportValidationResult.NoRead) {
            VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                message = gateValidation.message,
                diagnostics = when (val outcome = estimate.outcome) {
                    is VisualEstimateOutcome.Success -> outcome.diagnostics
                    is VisualEstimateOutcome.NoRead -> outcome.diagnostics
                },
            )
        } else {
            null
        }
        val exposureNoRead = earlyExposureNoRead ?: buildRecordedHfrExposureNoRead(diagnostics, estimate.outcome)
        val proofTrace = when (val outcome = estimate.outcome) {
            is VisualEstimateOutcome.NoRead -> estimate.detectorTrace.withOutcome(exposureNoRead ?: outcome)
            is VisualEstimateOutcome.Success -> (exposureNoRead ?: gateNoRead)?.let { estimate.detectorTrace.withOutcome(it) } ?: estimate.detectorTrace
        }
        val proof = VisualEstimateCaptureProofBuilder.buildFromThumbnails(
            attemptId = visualEstimateAttemptId,
            scannedFrameCount = estimate.scannedFrameCount,
            frameAvailableCallbackCount = estimate.scannedFrameCount,
            captureResultCallbackCount = diagnostics.callbackCount,
            uniqueSensorTimestampCount = diagnostics.uniqueTimestampCount,
            readbackWidth = workingResolution.working.width,
            readbackHeight = workingResolution.working.height,
            trace = proofTrace,
            thumbnails = estimate.proofThumbnails,
            sourceKind = "RECORDED_HFR_WINDOW",
            sourceWidth = workingResolution.source.width,
            sourceHeight = workingResolution.source.height,
            workingWidth = workingResolution.working.width,
            workingHeight = workingResolution.working.height,
            decodedFrameCount = metadata.sampleCount,
            requestedFps = fps,
            dropGateVerdict = gate?.windowVerdict ?: "NO_READ",
            cadenceGateVerdict = gate?.cadenceVerdict ?: if (diagnostics.medianGapPassesRateBand && diagnostics.captureProofPasses) "PASS" else "NO_READ",
            windowStartUs = mapping.window.windowStartUs,
            windowEndUs = mapping.window.windowEndUs,
            impactFrameIndex = mapping.sensorDiagnosticFrameIndex,
            preImpactMarginFrames = mapping.window.preImpactMarginFrames,
            anchorErrorNanos = mapping.endToEndAnchorErrorNanos,
            decodeWallClockMillis = decodeProof.decodeWallClockMillis,
            sourceValidityVerdict = gate?.sourceValidityVerdict ?: estimate.sourceValidity.verdict,
        )
        terminalNoReadMessage?.let { terminalMessage ->
            val noRead = VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                message = terminalMessage,
            )
            return ImportRunOutcome.NoRead(
                ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
                terminalMessage,
                metadata,
                frameCount = decodeProof.emittedFrameCount,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
                captureProof = proof,
                visualNoRead = noRead,
            )
        }
        if (exposureNoRead != null) {
            Log.i(
                logTag,
                "RECORDED_HFR_ESTIMATE_NO_READ attempt=$visualEstimateAttemptId window=true source=${workingResolution.source.width}x${workingResolution.source.height} " +
                    "working=${workingResolution.working.width}x${workingResolution.working.height} decoded=${decodeProof.emittedFrameCount} " +
                    "scanned=${estimate.scannedFrameCount} candidates=${estimate.retainedCandidateFrameCount} selected=${estimate.selectedSampleCount} " +
                    "sourceValidity=${proof.sourceValidityVerdict} reason=${exposureNoRead.reason} ${diagnostics.exposureSummary()}",
            )
            return ImportRunOutcome.NoRead(
                ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
                exposureNoRead.message,
                metadata,
                timing = estimate.timing,
                frameCount = estimate.scannedFrameCount,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
                captureProof = proof,
                visualNoRead = exposureNoRead,
            )
        }
        if (decodedWindowValidation is ImportValidationResult.NoRead) {
            val sourceInvalidMessage = if (!estimate.sourceValidity.passes && estimate.scannedFrameCount > 0) {
                "Recorded window source frames were black or invalid."
            } else {
                decodedWindowValidation.message
            }
            val noRead = VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                message = sourceInvalidMessage,
            )
            return ImportRunOutcome.NoRead(
                decodedWindowValidation.reason,
                sourceInvalidMessage,
                metadata,
                frameCount = decodeProof.emittedFrameCount,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
                captureProof = proof,
                visualNoRead = noRead,
            )
        }
        if (estimate.outcome is VisualEstimateOutcome.NoRead) {
            val noRead = estimate.outcome
            Log.i(
                logTag,
                "RECORDED_HFR_ESTIMATE_NO_READ attempt=$visualEstimateAttemptId window=true source=${workingResolution.source.width}x${workingResolution.source.height} " +
                    "working=${workingResolution.working.width}x${workingResolution.working.height} decoded=${decodeProof.emittedFrameCount} " +
                    "scanned=${estimate.scannedFrameCount} candidates=${estimate.retainedCandidateFrameCount} selected=${estimate.selectedSampleCount} " +
                    "uniqueSensorTs=${diagnostics.uniqueTimestampCount} window=${gate?.windowVerdict ?: "NO_READ"} cadence=${proof.cadenceGateVerdict} " +
                    "sourceValidity=${proof.sourceValidityVerdict} " +
                    "reason=${noRead.reason}",
            )
            return ImportRunOutcome.NoRead(
                reason = if (noRead.reason == VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED) {
                    ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED
                } else {
                    ImportNoReadReason.NO_TRUSTWORTHY_TIMING
                },
                message = noRead.message,
                metadata = metadata,
                timing = estimate.timing,
                frameCount = estimate.scannedFrameCount,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
                captureProof = proof,
                visualNoRead = noRead,
            )
        }
        if (gateNoRead != null || gateValidation is ImportValidationResult.NoRead) {
            val validation = gateValidation as ImportValidationResult.NoRead
            Log.i(
                logTag,
                "RECORDED_HFR_ESTIMATE_NO_READ attempt=$visualEstimateAttemptId window=true source=${workingResolution.source.width}x${workingResolution.source.height} " +
                    "working=${workingResolution.working.width}x${workingResolution.working.height} decoded=${decodeProof.emittedFrameCount} " +
                    "scanned=${estimate.scannedFrameCount} candidates=${estimate.retainedCandidateFrameCount} selected=${estimate.selectedSampleCount} " +
                    "uniqueSensorTs=${diagnostics.uniqueTimestampCount} window=NO_READ cadence=${proof.cadenceGateVerdict}",
            )
            return ImportRunOutcome.NoRead(
                validation.reason,
                validation.message,
                metadata,
                timing = estimate.timing,
                frameCount = estimate.scannedFrameCount,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
                captureProof = proof,
                visualNoRead = gateNoRead,
            )
        }
        if (estimate.timing == null) {
            val noRead = VisualEstimateOutcome.NoRead(
                VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                "Recorded-HFR window estimate did not produce retained-frame timing.",
            )
            return ImportRunOutcome.NoRead(
                ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
                noRead.message,
                metadata,
                timing = estimate.timing,
                frameCount = estimate.scannedFrameCount,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
                captureProof = proof,
                visualNoRead = noRead,
            )
        }
        Log.i(
            logTag,
            "RECORDED_HFR_ESTIMATE_COMPLETE attempt=$visualEstimateAttemptId window=true source=${workingResolution.source.width}x${workingResolution.source.height} " +
                "working=${workingResolution.working.width}x${workingResolution.working.height} metadataSamples=${gate?.metadataSampleCount ?: 0} " +
                "decoded=${decodeProof.emittedFrameCount} scanned=${estimate.scannedFrameCount} candidates=${estimate.retainedCandidateFrameCount} " +
                "selected=${estimate.selectedSampleCount} proofFrames=${estimate.retainedProofFrameCount} uniqueSensorTs=${diagnostics.uniqueTimestampCount} " +
                "window=${gate?.windowVerdict ?: "PASS"} cadence=${gate?.cadenceVerdict ?: "PASS"} decodeMs=${decodeProof.decodeWallClockMillis}",
        )
        return ImportRunOutcome.Estimate(
            metadata = metadata,
            frames = ImportVideoFrameSequence(emptyList()),
            timing = estimate.timing,
            estimate = estimate.outcome,
            sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
            captureProof = proof,
            frameCount = estimate.scannedFrameCount,
        )
    }

    private fun logMotionScoutSelection(selection: RecordedHfrMotionScoutSelection?, window: Boolean) {
        val selected = selection ?: run {
            Log.i(logTag, "RECORDED_HFR_MOTION_SCOUT window=$window selected=false")
            return
        }
        Log.i(
            logTag,
            "RECORDED_HFR_MOTION_SCOUT window=$window selected=true " +
                "sourceFrames=${selected.sourceFrameCount} dense=${selected.denseStartIndex}-${selected.denseEndIndexInclusive} " +
                "run=${selected.runStartIndex}-${selected.runEndIndexInclusive} runFrames=${selected.runFrameCount} " +
                "travelPx=${selected.runTravelPx.format(2)} meanAreaPx=${selected.meanComponentAreaPx.format(2)}",
        )
    }

    private fun buildRecordedHfrExposureNoRead(
        diagnostics: BurstDiagnostics,
        outcome: VisualEstimateOutcome?,
    ): VisualEstimateOutcome.NoRead? {
        val medianExposure = diagnostics.actualExposureMedianNanos ?: return null
        if (medianExposure <= RECORDED_HFR_MAX_ESTIMATE_EXPOSURE_NANOS) return null
        val visualDiagnostics = when (outcome) {
            is VisualEstimateOutcome.Success -> outcome.diagnostics
            is VisualEstimateOutcome.NoRead -> outcome.diagnostics
            null -> null
        }
        return VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.EXCESSIVE_RESIDUAL,
            message = "Recorded-HFR median exposure ${medianExposure}ns exceeds the ${RECORDED_HFR_MAX_ESTIMATE_EXPOSURE_NANOS}ns blur-risk gate. This is a coarse field-tunable pre-filter; residual and track-quality gates remain the fine accuracy check.",
            diagnostics = visualDiagnostics,
        )
    }

    private fun reconcileImportTiming(frames: ImportVideoFrameSequence): ImportValidationResult<ImportTimingReconciliation> {
        val knownFrameRateFps = debugImportKnownFrameRateFps
        return if (knownFrameRateFps != null && knownFrameRateFps.isFinite() && knownFrameRateFps > 0.0) {
            ImportTimingReconciler.reconcileKnownFrameInterval(
                frameCount = frames.frames.size,
                frameIntervalSeconds = 1.0 / knownFrameRateFps,
                intervalIsUserDeclared = true,
            )
        } else {
            ImportTimingReconciler.reconcileContainerPresentationTimestamps(frames)
        }
    }

    private fun buildImportEstimateConfig(metadata: ImportVideoMetadata): ImportEstimateRuntimeConfig? {
        val sourceDimensions = if (metadata.rotationDegrees == 90 || metadata.rotationDegrees == 270) {
            FrameDimensions(metadata.height, metadata.width)
        } else {
            FrameDimensions(metadata.width, metadata.height)
        }
        val readbackDimensions = FrameDimensions(
            DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH,
            DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT,
        )
        val importGeometry = Phase14Geometry(
            modeId = "import-${metadata.width}x${metadata.height}",
            previewTransformId = "import-fit",
            source = sourceDimensions,
            readback = readbackDimensions,
        )
        val calibration = buildImportCalibration(importGeometry)
        val colorReadiness = buildImportColor(importGeometry).readiness(readbackDimensions.width, readbackDimensions.height)
        if (colorReadiness !is ColorWorkflowReadiness.Ready) return null
        val knownBallDiameterFeet = if (phase14WorkflowState.setupMode == Phase14SetupMode.BallDiameterFallback) {
            phase14WorkflowState.knownBallDiameterFeet
        } else {
            debugVisualEstimateKnownBallDiameterFeet
        }
        if (calibration.pixelsPerFoot() is CalibrationResult.Failure && knownBallDiameterFeet == null) return null
        val scaleMode = currentScaleModeOrNull() ?: return null
        return ImportEstimateRuntimeConfig(
            calibration = calibration,
            framePipelineConfig = VisualEstimateFramePipelineConfig(
                trackConfig = TrackExtractionConfig(
                    detectorConfig = BlobDetectionConfig(
                        threshold = colorReadiness.threshold,
                        roi = colorReadiness.regionOfInterest,
                        minAreaPx = IMPORT_ESTIMATE_MIN_BLOB_AREA_PX,
                        maxAreaPx = max(8, (readbackDimensions.width * readbackDimensions.height) / 4),
                        minCompactness = 0.0,
                        bounds = FrameProcessingBounds(
                            maxWidth = readbackDimensions.width,
                            maxHeight = readbackDimensions.height,
                            maxPixels = readbackDimensions.width * readbackDimensions.height,
                            maxFrameCount = DEFAULT_IMPORT_MAX_FRAMES,
                            maxThresholdPixels = readbackDimensions.width * readbackDimensions.height,
                            maxComponentsPerFrame = readbackDimensions.width * readbackDimensions.height,
                            maxOperationsPerFrame = readbackDimensions.width * readbackDimensions.height * 20,
                        ),
                    ),
                    maxFrameToFrameJumpPx = readbackDimensions.width.toDouble(),
                    maxInteriorMisses = 1,
                    allowDirectionalCandidateSelection = true,
                ),
                estimateConfig = VisualEstimatePipelineConfig(
                    maxEstimateRmsResidualPx = if (knownBallDiameterFeet != null) BALL_DIAMETER_ESTIMATE_MAX_RMS_RESIDUAL_PX else IMPORT_ESTIMATE_MAX_RMS_RESIDUAL_PX,
                    maxEstimateRmsResidualDiameterFraction = ESTIMATE_RESIDUAL_BLOB_DIAMETER_FRACTION,
                    maxOutlierPasses = 0,
                    maxRejectedOutlierCount = 4,
                    knownBallDiameterFeet = knownBallDiameterFeet,
                    allowApparentScaleChangeEstimate = knownBallDiameterFeet != null,
                    requireFittedPathProgression = false,
                    minEstimateMilesPerHour = HIT_BALL_MIN_ESTIMATE_MPH,
                    scaleMode = scaleMode,
                    levelReference = phase14WorkflowState.levelReference,
                ),
                timing = VisualEstimateFrameTiming.RequireRealTimestamps,
            ),
        )
    }

    private fun buildRecordedEstimateConfig(
        metadata: ImportVideoMetadata,
        workingResolution: RecordedHfrWorkingResolution,
    ): ImportEstimateRuntimeConfig? {
        val importGeometry = Phase14Geometry(
            modeId = "recorded-${metadata.width}x${metadata.height}",
            previewTransformId = "recorded-fit-r$previewRotationDegrees",
            source = workingResolution.source,
            readback = workingResolution.working,
        )
        val calibration = buildImportCalibration(importGeometry)
        val colorReadiness = buildImportColor(importGeometry, requireRegionOfInterest = false).readiness(
            workingResolution.working.width,
            workingResolution.working.height,
        )
        val detectorThreshold = (colorReadiness as? ColorWorkflowReadiness.Ready)?.threshold
            ?: HsvThreshold(
                center = HsvColor(120.0, 1.0, 1.0),
                tolerance = HsvTolerance(180.0, 1.0, 1.0),
            )
        val detectorRoi = (colorReadiness as? ColorWorkflowReadiness.Ready)?.regionOfInterest
            ?: RegionOfInterest(0, 0, workingResolution.working.width, workingResolution.working.height)
        val knownBallDiameterFeet = if (phase14WorkflowState.setupMode == Phase14SetupMode.BallDiameterFallback) {
            phase14WorkflowState.knownBallDiameterFeet
        } else {
            debugVisualEstimateKnownBallDiameterFeet
        }
        if (calibration.pixelsPerFoot() is CalibrationResult.Failure && knownBallDiameterFeet == null) return null
        val scaleMode = currentScaleModeOrNull() ?: return null
        val motionBlobSideRatio = parseMotionBlobSideRatio(motionBlobSideRatioText) ?: return null
        val launchHeightFeet = parseLaunchHeightFeet(launchHeightFeetText) ?: return null
        val workingPixels = workingResolution.working.width * workingResolution.working.height
        return ImportEstimateRuntimeConfig(
            calibration = calibration,
            framePipelineConfig = VisualEstimateFramePipelineConfig(
                trackConfig = TrackExtractionConfig(
                    detectorConfig = BlobDetectionConfig(
                        threshold = detectorThreshold,
                        roi = detectorRoi,
                        minAreaPx = workingResolution.scaleAreaPx(IMPORT_ESTIMATE_MIN_BLOB_AREA_PX),
                        maxAreaPx = max(8, workingPixels / 4),
                        minCompactness = 0.0,
                        bounds = FrameProcessingBounds(
                            maxWidth = workingResolution.working.width,
                            maxHeight = workingResolution.working.height,
                            maxPixels = workingPixels,
                            maxFrameCount = DEFAULT_IMPORT_MAX_FRAMES,
                            maxThresholdPixels = workingPixels,
                            maxComponentsPerFrame = workingPixels,
                            maxOperationsPerFrame = workingPixels * 20,
                        ),
                    ),
                    maxFrameToFrameJumpPx = workingResolution.working.width.toDouble(),
                    maxInteriorMisses = 1,
                    allowDirectionalCandidateSelection = true,
                    maxCandidateTimestampGapSpreadRatio = 2.5,
                    candidateReductionBudget = CandidateReductionBudget(
                        maxBlobsPerFrame = RECORDED_HFR_MAX_BLOBS_PER_FRAME,
                        maxTotalCandidateBlobs = RECORDED_HFR_MAX_TOTAL_CANDIDATE_BLOBS,
                        maxRansacCandidates = RECORDED_HFR_MAX_RANSAC_CANDIDATES,
                        maxRansacPairHypotheses = RECORDED_HFR_MAX_RANSAC_PAIR_HYPOTHESES,
                        ransacCancellationCheckInterval = RECORDED_HFR_RANSAC_CANCELLATION_CHECK_INTERVAL,
                    ),
                ),
                estimateConfig = VisualEstimatePipelineConfig(
                    maxEstimateRmsResidualPx = if (knownBallDiameterFeet != null) BALL_DIAMETER_ESTIMATE_MAX_RMS_RESIDUAL_PX else IMPORT_ESTIMATE_MAX_RMS_RESIDUAL_PX,
                    maxEstimateRmsResidualDiameterFraction = ESTIMATE_RESIDUAL_BLOB_DIAMETER_FRACTION,
                    maxOutlierPasses = 0,
                    maxRejectedOutlierCount = 4,
                    knownBallDiameterFeet = knownBallDiameterFeet,
                    allowApparentScaleChangeEstimate = true,
                    requireFittedPathProgression = false,
                    minEstimateMilesPerHour = null,
                    minimumSpeedGatePolicy = MinimumSpeedGatePolicy.DISABLED,
                    scaleMode = scaleMode,
                    levelReference = phase14WorkflowState.levelReference,
                    launchHeightFeet = launchHeightFeet,
                ),
                timing = VisualEstimateFrameTiming.RequireRealTimestamps,
            ),
            motionDetectorConfig = RecordedHfrMotionDetectorConfig(
                maxCandidatePrincipalAxisRatio = motionBlobSideRatio,
                inclusionPolygon = buildRecordedMotionInclusionPolygon(importGeometry),
                expectedBallSizePx = buildRecordedExpectedBallSize(importGeometry),
            ),
        )
    }

    private fun buildRecordedMotionInclusionPolygon(geometry: Phase14Geometry) =
        phase14WorkflowState.impactZone
            ?.let { com.speedball.app.measurement.DetectionReadbackTransform(geometry.source, geometry.readback).normalizedToReadbackPolygon(it) }

    private fun buildRecordedExpectedBallSize(geometry: Phase14Geometry): ExpectedBallSizePx? {
        val bounds = phase14WorkflowState.expectedBallBounds ?: return null
        val polygon = com.speedball.app.measurement.DetectionReadbackTransform(geometry.source, geometry.readback)
            .normalizedToReadbackPolygon(bounds)
            ?: return null
        val minX = polygon.points.minOf { it.xPx }
        val maxX = polygon.points.maxOf { it.xPx }
        val minY = polygon.points.minOf { it.yPx }
        val maxY = polygon.points.maxOf { it.yPx }
        return ExpectedBallSizePx(
            widthPx = maxX - minX,
            heightPx = maxY - minY,
        )
    }

    private fun buildImportCalibration(geometry: Phase14Geometry): MeasurementCalibrationState {
        if (phase14WorkflowState.setupMode != Phase14SetupMode.KnownDistance) return emptyCalibration()
        val pointA = phase14WorkflowState.calibrationPointA ?: return emptyCalibration()
        val pointB = phase14WorkflowState.calibrationPointB ?: return emptyCalibration()
        val distanceFeet = phase14WorkflowState.knownDistanceFeet ?: return emptyCalibration()
        val transform = com.speedball.app.measurement.DetectionReadbackTransform(geometry.source, geometry.readback)
        return MeasurementCalibrationState(
            pointA = transform.normalizedToReadbackPoint(pointA),
            pointB = transform.normalizedToReadbackPoint(pointB),
            knownDistanceFeet = distanceFeet,
        )
    }

    private fun emptyCalibration(): MeasurementCalibrationState =
        MeasurementCalibrationState(pointA = null, pointB = null, knownDistanceFeet = null)

    private fun buildImportColor(
        geometry: Phase14Geometry,
        requireRegionOfInterest: Boolean = true,
    ): ColorWorkflowState {
        val point = phase14WorkflowState.colorSamplePoint ?: return ColorWorkflowState(tolerance = phase14WorkflowState.colorTolerance)
        val sample = phase14WorkflowState.colorSample ?: return ColorWorkflowState(tolerance = phase14WorkflowState.colorTolerance)
        val transform = com.speedball.app.measurement.DetectionReadbackTransform(geometry.source, geometry.readback)
        val readbackRoi = phase14WorkflowState.regionOfInterest
            ?.let { transform.normalizedToReadbackRoi(it) }
            ?: if (requireRegionOfInterest) {
                return ColorWorkflowState(tolerance = phase14WorkflowState.colorTolerance)
            } else {
                null
            }
        return ColorWorkflowState(tolerance = phase14WorkflowState.colorTolerance).selectSample(
            sample = sample.takeIf { point.isInFrame() } ?: sample,
            tolerance = phase14WorkflowState.colorTolerance,
            regionOfInterest = readbackRoi,
        )
    }

    private fun ImportNoReadReason.toVisualEstimateNoReadReason(): VisualEstimateNoReadReason =
        when (this) {
            ImportNoReadReason.NO_TRUSTWORTHY_TIMING,
            ImportNoReadReason.INVALID_METADATA ->
                VisualEstimateNoReadReason.BAD_TIMESTAMPS
            ImportNoReadReason.UNSUPPORTED_MEDIA,
            ImportNoReadReason.PERMISSION_DENIED,
            ImportNoReadReason.RESOURCE_LIMIT_EXCEEDED ->
                VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED
        }

    private fun armDirectVisualEstimate() {
        updatePhase14Geometry()
        updateLegacyWorkflowFromPhase14()
        phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.ArmCapture)
        updateShellState(status = if (phase14WorkflowState.canArm()) "Visual estimate armed" else "Visual estimate not ready")
    }

    private fun retryDirectVisualEstimate() {
        phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.Retry)
        lastDirectVisualEstimateOutcome = null
        currentVisualEstimateReport = null
        dismissedVisualEstimateAttemptId = null
        updateShellState(status = "Visual estimate retry ready")
    }

    private fun recalibrateDirectVisualEstimate() {
        enterSetupMode()
        phase14WorkflowState = phase14WorkflowState.reduce(Phase14WorkflowEvent.Recalibrate)
        calibrationWorkflowState = CalibrationWorkflowState()
        debugVisualEstimateKnownBallDiameterFeet = null
        currentVisualEstimateReport = null
        dismissedVisualEstimateAttemptId = null
        updateLegacyWorkflowFromPhase14()
        updateShellState(status = "Recalibrate estimate")
    }

    private fun updateLegacyWorkflowFromPhase14() {
        val readback = phase14WorkflowState.geometry?.readback
        calibrationWorkflowState = phase14WorkflowState.buildCalibrationForActiveReadback()
        colorWorkflowState = phase14WorkflowState.buildColorForActiveReadback()
        measurementWorkflowState = measurementWorkflowState
            .reduce(
                if (calibrationWorkflowState.noReadOrNull() == null || phase14WorkflowState.setupMode == Phase14SetupMode.BallDiameterFallback) {
                    com.speedball.app.measurement.MeasurementWorkflowEvent.CalibrationSelected
                } else {
                    com.speedball.app.measurement.MeasurementWorkflowEvent.CalibrationCleared
                },
            )
            .reduce(
                if (colorWorkflowState.noReadOrNull(
                        readback?.width ?: DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH,
                        readback?.height ?: DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT,
                    ) == null
                ) {
                    com.speedball.app.measurement.MeasurementWorkflowEvent.ColorSampleSelected
                } else {
                    com.speedball.app.measurement.MeasurementWorkflowEvent.ColorSampleCleared
                },
            )
    }

    private fun buildDirectVisualEstimateConfig(mode: HighSpeedMode): DirectVisualEstimateCaptureConfig? {
        updatePhase14Geometry()
        updateLegacyWorkflowFromPhase14()
        val activeGeometry = phase14WorkflowState.geometry
        if (activeGeometry == null) {
            lastDirectVisualEstimateOutcome = VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.DETECTION_FAILED,
                message = "Camera geometry is unavailable. Reopen the camera and retry.",
            )
            return null
        }
        val readbackWidth = activeGeometry.readback.width
        val readbackHeight = activeGeometry.readback.height
        val colorReadiness = colorWorkflowState.readiness(readbackWidth, readbackHeight)
        if (colorReadiness is ColorWorkflowReadiness.NotReady) {
            lastDirectVisualEstimateOutcome = VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.DETECTION_FAILED,
                message = colorReadiness.message,
            )
            return null
        }
        val knownBallDiameterFeet = if (phase14WorkflowState.setupMode == Phase14SetupMode.BallDiameterFallback) {
            phase14WorkflowState.knownBallDiameterFeet
        } else {
            debugVisualEstimateKnownBallDiameterFeet
        }
        val calibrationNoRead = calibrationWorkflowState.noReadOrNull()
        if (calibrationNoRead != null && knownBallDiameterFeet == null) {
            lastDirectVisualEstimateOutcome = VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.BAD_CALIBRATION,
                message = calibrationNoRead.message,
            )
            return null
        }
        val levelReference = phase14WorkflowState.levelReference
        if (levelReference?.hasOnlyFiniteValues() != true) {
            lastDirectVisualEstimateOutcome = VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.BAD_CALIBRATION,
                message = "Capture a still-phone IMU level reference before estimating.",
            )
            return null
        }
        colorReadiness as ColorWorkflowReadiness.Ready
        return DirectVisualEstimateCaptureConfig(
            mode = mode,
            readbackWidth = readbackWidth,
            readbackHeight = readbackHeight,
            attemptId = visualEstimateAttemptId,
            calibration = calibrationWorkflowState.toMeasurementCalibrationState(),
            framePipelineConfig = VisualEstimateFramePipelineConfig(
                trackConfig = TrackExtractionConfig(
                    detectorConfig = BlobDetectionConfig(
                        threshold = colorReadiness.threshold,
                        roi = colorReadiness.regionOfInterest,
                        minAreaPx = 1,
                        maxAreaPx = max(8, (readbackWidth * readbackHeight) / 4),
                        minCompactness = 0.0,
                        bounds = FrameProcessingBounds(
                            maxWidth = readbackWidth,
                            maxHeight = readbackHeight,
                            maxPixels = readbackWidth * readbackHeight,
                            maxFrameCount = DEFAULT_DIRECT_VISUAL_ESTIMATE_MAX_FRAMES,
                            maxThresholdPixels = readbackWidth * readbackHeight,
                            maxComponentsPerFrame = readbackWidth * readbackHeight,
                            maxOperationsPerFrame = readbackWidth * readbackHeight * 20,
                        ),
                    ),
                    maxFrameToFrameJumpPx = readbackWidth.toDouble(),
                    maxInteriorMisses = 1,
                    allowDirectionalCandidateSelection = true,
                ),
                estimateConfig = VisualEstimatePipelineConfig(
                    maxEstimateRmsResidualPx = if (knownBallDiameterFeet != null) BALL_DIAMETER_ESTIMATE_MAX_RMS_RESIDUAL_PX else 2.0,
                    maxEstimateRmsResidualDiameterFraction = ESTIMATE_RESIDUAL_BLOB_DIAMETER_FRACTION,
                    knownBallDiameterFeet = knownBallDiameterFeet,
                    allowApparentScaleChangeEstimate = knownBallDiameterFeet != null,
                    minEstimateMilesPerHour = HIT_BALL_MIN_ESTIMATE_MPH,
                    levelReference = levelReference,
                ),
                timing = VisualEstimateFrameTiming.PreferRealTimestamps(
                    visualFrameIntervalSeconds = 1.0 / mode.fps,
                ),
            ),
        )
    }

    private fun configurePreviewProofWindow() {
        if ((!autoStartPending && !autoStartPreviewPending && !autoStartDirectProofPending && !autoStartDirectVisualEstimatePending) || !isDebuggableBuild()) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        Log.i(logTag, "PREVIEW_PROOF_WINDOW debugShowWhenLocked=true")
    }

    private fun configureCameraWindow() {
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            )
    }

    private fun isDebuggableBuild(): Boolean =
        (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun updateShellState(status: String) {
        captureStatus = status
        shellState = speedBallCaptureState(
            permissionLabel = if (hasCameraPermission()) "Granted" else "Not granted",
            captureStatus = status,
            appMode = appMode,
            runCommandState = runCommandState,
            modeLines = modes.map { mode ->
                val support = if (mode.recordSupported && mode.fps == 120) "recordable" else "unsupported for Phase 4 record"
                "${mode.label} ($support)"
            },
            selectedModeLine = selectedMode?.label,
            diagnosticLines = lastDiagnostics?.toUiLines().orEmpty() +
                decodeOutcomeUiLines(lastDecodeOutcome) +
                previewOutcomeUiLines(lastPreviewOutcome) +
                directProofRunUiLines(lastDirectProofResult) +
                lastDirectVisualEstimateOutcome?.let(::visualEstimateOutcomeUiLines).orEmpty(),
            failureLine = lastFailure?.let { "Failure: ${it.reason} - ${it.message}" },
            calibrationState = calibrationWorkflowState,
            colorState = colorWorkflowState,
            workflowState = measurementWorkflowState,
            phase14State = phase14WorkflowState,
            importWorkflowState = importWorkflowState,
            savedResultSummaries = savedResultHistory.list(),
            exportEvidenceText = lastImportExportText,
            setupAdjustmentTargetLabel = setupAdjustmentTarget.label,
            knownDistanceFeetText = knownDistanceFeetText,
            calibrationPlaneDepthFeetText = calibrationPlaneDepthFeetText,
            ballPlaneDepthFeetText = ballPlaneDepthFeetText,
            motionBlobSideRatioText = motionBlobSideRatioText,
            launchHeightFeetText = launchHeightFeetText,
            previewRotationDegrees = previewRotationDegrees,
            visualEstimateReport = visibleVisualEstimateReport(),
            workflowFrameWidth = selectedMode?.width ?: 0,
            workflowFrameHeight = selectedMode?.height ?: 0,
        )
    }

    private fun immediateDirectProofFailure(
        reason: DirectTimingSourceFailure,
        message: String,
    ): DirectTimingSourceProofRunResult =
        DirectTimingSourceProofRunResult(
            companionOutcome = directProbeFailure(reason, message),
            previewControl = DirectPreviewControlReport(attempted = false, outcome = null),
            proofOutcome = DirectTimingSourceProofOutcome.Failure(reason, message),
        )

    private fun directProbeFailure(
        reason: DirectTimingSourceFailure,
        message: String,
    ): DirectSessionProbeOutcome.Failure =
        DirectSessionProbeOutcome.Failure(
            shape = DirectProofSessionShape.COMPANION_ENCODER,
            reason = reason,
            message = message,
        )

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun currentDisplayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display?.rotation ?: Surface.ROTATION_0
        } else {
            windowManager.defaultDisplay.rotation
        }

    private fun currentDisplayRotationDegrees(): Int =
        when (currentDisplayRotation()) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }

    private fun currentLevelReferenceDisplayRotation(): LevelReferenceDisplayRotation =
        when (currentDisplayRotation()) {
            Surface.ROTATION_90 -> LevelReferenceDisplayRotation.ROTATION_90
            Surface.ROTATION_180 -> LevelReferenceDisplayRotation.ROTATION_180
            Surface.ROTATION_270 -> LevelReferenceDisplayRotation.ROTATION_270
            else -> LevelReferenceDisplayRotation.ROTATION_0
        }

    private fun refreshPreviewOrientation() {
        val orientation = highSpeedCamera.readBackCameraPreviewOrientation(currentDisplayRotationDegrees())
        previewRotationDegrees = orientation?.textureViewRotationDegrees ?: 0
        if (orientation != null) {
            Log.i(
                logTag,
                "LIVE_PREVIEW_ORIENTATION cameraId=${orientation.cameraId} " +
                    "sensorDeg=${orientation.sensorOrientationDegrees} displayDeg=${orientation.displayRotationDegrees} " +
                    "textureRotationDeg=${orientation.textureViewRotationDegrees}",
            )
        } else {
            Log.e(logTag, "LIVE_PREVIEW_ORIENTATION unavailable using textureRotationDeg=0")
        }
    }

    private fun BurstDiagnostics.toUiLines(): List<String> =
        listOf(
            "callbacks=$callbackCount uniqueTs=$uniqueTimestampCount expected=$expectedUniqueTimestampCount min=$minimumUniqueTimestampCount",
            "medianGapMs=${medianGapMillis?.format(2) ?: "n/a"} band=${medianGapLowerBoundMillis.format(2)}..${medianGapUpperBoundMillis.format(2)} pass=$medianGapPassesRateBand",
            "proof=$captureProofPasses file=$displayOutputName bytes=$fileBytes",
            exposureSummary(),
        )

    private fun logDecodeOutcome(file: File, outcome: DecodeOutcome) {
        when (outcome) {
            DecodeOutcome.Cancelled -> Log.i(logTag, "DECODE_CANCELLED file=${file.displayNameOnly()}")
            is DecodeOutcome.Failure -> {
                Log.e(logTag, "DECODE_FAILURE reason=${outcome.reason} message=${outcome.message} file=${file.displayNameOnly()}")
                outcome.diagnostics?.let(::logDecodeDiagnostics)
            }
            is DecodeOutcome.Success -> {
                Log.i(
                    logTag,
                    "DECODE_SUCCESS " +
                        "file=${file.displayNameOnly()}",
                )
                logDecodeDiagnostics(outcome.diagnostics)
            }
        }
    }

    private fun logDecodeDiagnostics(diagnostics: ReconciliationDiagnostics) {
        Log.i(
            logTag,
            "DECODE_DIAGNOSTICS decoded=${diagnostics.decodedFrameCount} uniqueTs=${diagnostics.uniqueSensorTimestampCount} " +
                "sensorMedianMs=${diagnostics.medianSensorGapMillis?.format(2)} sensorMaxMs=${diagnostics.maximumSensorGapMillis?.format(2)} " +
                "ptsMedianMs=${diagnostics.medianPresentationGapMillis?.format(2)} ptsMaxMs=${diagnostics.maximumPresentationGapMillis?.format(2)} " +
                "dropThresholdMs=${diagnostics.droppedFrameGapThresholdMillis.format(2)} exactCount=${diagnostics.exactCountPasses} " +
                "nearDuplicateMs=${diagnostics.nearDuplicateGapMillis?.format(6)} clock=${diagnostics.presentationClockAssessment} " +
                "samples=${diagnostics.sampledFrames.joinToString { "${it.frameIndex}:${it.width}x${it.height}@${it.presentationTimeMicros}us" }}",
        )
        logLongList("DECODE_SENSOR_GAPS_NS", diagnostics.sensorGapNanos)
        logLongList("DECODE_PTS_GAPS_US", diagnostics.presentationGapMicros)
        logLongList("DECODE_PTS_SENSOR_OFFSETS_US", diagnostics.ptsToSensorOffsetMicros)
        diagnostics.ptsSensorValueMatch?.let { valueMatch ->
            Log.i(
                logTag,
                "DECODE_PTS_SENSOR_VALUE_MATCH " +
                    "verdict=${valueMatch.verdict} decoded=${valueMatch.decodedFrameCount} uniqueTs=${valueMatch.uniqueSensorTimestampCount} " +
                    "evaluatedOffsets=${valueMatch.evaluatedOffsetCount} toleranceUs=${valueMatch.toleranceMicros} " +
                    "matched=${valueMatch.matchedFrameCount} unambiguous=${valueMatch.unambiguousFrameCount} " +
                    "ambiguous=${valueMatch.ambiguousFrameCount} longestCleanRun=${valueMatch.longestContiguousUnambiguousRun} " +
                    "maxResidualUs=${valueMatch.maximumResidualMicros ?: "n/a"} medianResidualUs=${valueMatch.medianResidualMicros ?: "n/a"} " +
                    "offsetUs=${valueMatch.bestOffsetMicros ?: "n/a"}",
            )
        }
    }

    private fun logTimestampAnchorOutcome(file: File, outcome: TimestampAnchorOutcome) {
        timestampAnchorDiagnosticLogLines(outcome, file.displayNameOnly(), LOG_VALUE_CHUNK_SIZE).forEach { line ->
            Log.i(logTag, line)
        }
    }

    private fun logPreviewOutcome(mode: HighSpeedMode, outcome: PreviewFrameOutcome) {
        when (outcome) {
            PreviewFrameOutcome.Cancelled -> Log.i(logTag, "PREVIEW_TIMESTAMP_SPIKE_CANCELLED mode=${mode.label.compactForLog()}")
            is PreviewFrameOutcome.Failure -> Log.i(logTag, "PREVIEW_TIMESTAMP_SPIKE_FAILURE mode=${mode.label.compactForLog()} reason=${outcome.reason} message=${outcome.message.compactForLog()}")
            is PreviewFrameOutcome.Success -> Log.i(logTag, "PREVIEW_TIMESTAMP_SPIKE_SUCCESS mode=${mode.label.compactForLog()} pairs=${outcome.pairs.size}")
        }
        previewFrameDiagnosticLogLines(outcome, mode.label, LOG_VALUE_CHUNK_SIZE).forEach { line ->
            Log.i(logTag, line)
        }
    }

    private fun File.displayNameOnly(): String =
        name.ifBlank { absolutePath.substringAfterLast('/') }

    private fun logLongList(label: String, values: List<Long>) {
        if (values.isEmpty()) {
            Log.i(logTag, "$label count=0 values=[]")
            return
        }
        values.chunked(LOG_VALUE_CHUNK_SIZE).forEachIndexed { index, chunk ->
            Log.i(logTag, "$label chunk=${index + 1} count=${values.size} values=${chunk.joinToString(prefix = "[", postfix = "]")}")
        }
    }

    private fun Double.format(decimals: Int): String =
        "%.${decimals}f".format(this)

    private fun HighSpeedMode.toPhase14Geometry(): Phase14Geometry =
        Phase14Geometry(
            modeId = label,
            previewTransformId = "surface-${width}x${height}-fit-r$previewRotationDegrees",
            source = com.speedball.app.measurement.FrameDimensions(width, height),
            readback = com.speedball.app.measurement.FrameDimensions(
                DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_WIDTH,
                DEFAULT_DIRECT_VISUAL_ESTIMATE_READBACK_HEIGHT,
            ),
        )

    private fun String.compactForLog(): String =
        replace(Regex("\\s+"), "_")

    private data class ImportEstimateRuntimeConfig(
        val calibration: MeasurementCalibrationState,
        val framePipelineConfig: VisualEstimateFramePipelineConfig,
        val motionDetectorConfig: RecordedHfrMotionDetectorConfig? = null,
    )

    private sealed interface ImportRunOutcome {
        data class Estimate(
            val metadata: ImportVideoMetadata,
            val frames: ImportVideoFrameSequence,
            val timing: ImportTimingReconciliation,
            val estimate: VisualEstimateOutcome,
            val sourceKind: ImportResultSourceKind = ImportResultSourceKind.IMPORT_ESTIMATE,
            val captureProof: VisualEstimateCaptureProof? = null,
            val frameCount: Int = frames.frames.size,
        ) : ImportRunOutcome

        data class NoRead(
            val reason: ImportNoReadReason,
            val message: String,
            val metadata: ImportVideoMetadata? = null,
            val timing: ImportTimingReconciliation? = null,
            val frameCount: Int = 0,
            val sourceKind: ImportResultSourceKind = ImportResultSourceKind.IMPORT_ESTIMATE,
            val captureProof: VisualEstimateCaptureProof? = null,
            val visualNoRead: VisualEstimateOutcome.NoRead? = null,
        ) : ImportRunOutcome
    }

    private fun ImportRunOutcome.Estimate.toSavedSummary(): SavedResultSummary {
        val diagnostics = when (estimate) {
            is VisualEstimateOutcome.Success -> estimate.diagnostics
            is VisualEstimateOutcome.NoRead -> estimate.diagnostics
        }
        return SavedResultSummary(
            id = nextSavedResultId(),
            createdAtEpochMillis = System.currentTimeMillis(),
            sourceKind = sourceKind,
            evidence = ImportEvidenceSummary(
                sourceKind = sourceKind,
                timingBasis = timing.basis,
                frameCount = frameCount,
                detectionCount = diagnostics?.detectionCount ?: 0,
                assumptions = timing.assumptions + diagnostics?.assumptions.orEmpty(),
            ),
            speedMilesPerHour = (estimate as? VisualEstimateOutcome.Success)?.milesPerHour,
            launchAngleDegrees = (estimate as? VisualEstimateOutcome.Success)?.launchAngleDegrees,
            noReadReason = (estimate as? VisualEstimateOutcome.NoRead)?.reason?.name,
        )
    }

    private fun ImportRunOutcome.NoRead.toSavedSummary(): SavedResultSummary =
        SavedResultSummary(
            id = nextSavedResultId(),
            createdAtEpochMillis = System.currentTimeMillis(),
            sourceKind = sourceKind,
            evidence = ImportEvidenceSummary(
                sourceKind = sourceKind,
                timingBasis = timing?.basis ?: ImportTimingBasis.NO_TRUSTWORTHY_TIMING,
                frameCount = frameCount,
                detectionCount = 0,
                assumptions = timing?.assumptions.orEmpty(),
            ),
            speedMilesPerHour = null,
            launchAngleDegrees = null,
            noReadReason = reason.name,
        )

    private fun nextSavedResultId(): String =
        "result-${System.currentTimeMillis()}-${savedResultHistory.list().size + 1}"

    private fun persistSavedResults() {
        when (val saved = savedResultStore.save(savedResultHistory.list())) {
            is ImportValidationResult.NoRead -> Log.e(logTag, "SAVED_RESULT_STORE_FAILURE reason=${saved.reason} message=${saved.message.compactForLog()}")
            is ImportValidationResult.Success -> Unit
        }
    }

    @Suppress("DEPRECATION")
    private fun android.content.Intent.doubleExtraOrNull(name: String): Double? =
        extras?.get(name)?.let { value ->
            when (value) {
                is Number -> value.toDouble()
                else -> null
            }
        }

    @Suppress("DEPRECATION")
    private fun android.content.Intent.intExtraOrNull(name: String): Int? =
        extras?.get(name)?.let { value ->
            when (value) {
                is Number -> value.toInt()
                else -> null
            }
        }

    private fun NormalizedFramePoint.nudged(dx: Double, dy: Double): NormalizedFramePoint =
        NormalizedFramePoint(
            x = (x + dx).coerceIn(0.0, 1.0),
            y = (y + dy).coerceIn(0.0, 1.0),
        )

    private fun NormalizedFramePoint.coercedInFrame(): NormalizedFramePoint =
        NormalizedFramePoint(x = x.coerceIn(0.0, 1.0), y = y.coerceIn(0.0, 1.0))

    private fun NormalizedFrameRect.nudged(dx: Double, dy: Double): NormalizedFrameRect {
        val width = right - left
        val height = bottom - top
        val nudgedLeft = (left + dx).coerceIn(0.0, 1.0 - width)
        val nudgedTop = (top + dy).coerceIn(0.0, 1.0 - height)
        return NormalizedFrameRect(
            left = nudgedLeft,
            top = nudgedTop,
            right = nudgedLeft + width,
            bottom = nudgedTop + height,
        )
    }

    private fun NormalizedFramePolygon.nudged(dx: Double, dy: Double): NormalizedFramePolygon =
        NormalizedFramePolygon(points.map { it.nudged(dx, dy) })

    private fun NormalizedFramePolygon.centeredAt(point: NormalizedFramePoint): NormalizedFramePolygon {
        val centerX = points.map { it.x }.average()
        val centerY = points.map { it.y }.average()
        val unclamped = points.map { NormalizedFramePoint(it.x + point.x - centerX, it.y + point.y - centerY) }
        val minX = unclamped.minOf { it.x }
        val maxX = unclamped.maxOf { it.x }
        val minY = unclamped.minOf { it.y }
        val maxY = unclamped.maxOf { it.y }
        val shiftX = when {
            minX < 0.0 -> -minX
            maxX > 1.0 -> 1.0 - maxX
            else -> 0.0
        }
        val shiftY = when {
            minY < 0.0 -> -minY
            maxY > 1.0 -> 1.0 - maxY
            else -> 0.0
        }
        return NormalizedFramePolygon(unclamped.map { NormalizedFramePoint(it.x + shiftX, it.y + shiftY).coercedInFrame() })
    }

    private enum class SetupAdjustmentTarget(val label: String) {
        CaliperA("A"),
        CaliperB("B"),
        ColorPoint("Color"),
        Roi("ROI"),
        ImpactZone("Impact"),
        BallBox("Ball Box"),
    }

    private companion object {
        const val LOG_VALUE_CHUNK_SIZE = 80
        const val DIRECT_PROOF_TIMEOUT_PADDING_MILLIS = 15_000L
        const val DIRECT_PROOF_CAMERA_SETTLE_MILLIS = 1_000L
        const val SAVED_RESULTS_FILE_NAME = "saved-result-summaries.properties"
        const val DEFAULT_IMPORT_MAX_FRAMES = 1200
        const val RECORDED_HFR_MAX_RETAINED_CANDIDATE_FRAMES = 360
        const val RECORDED_HFR_PROOF_THUMBNAIL_MAX_WIDTH = 96
        const val RECORDED_HFR_PROOF_THUMBNAIL_MAX_HEIGHT = 54
        const val RECORDED_HFR_WINDOW_MIN_USABLE_FRAMES = 4
        const val RECORDED_HFR_WINDOW_DECODE_TIMEOUT_MILLIS = 300_000L
        const val RECORDED_HFR_MAX_ESTIMATE_EXPOSURE_NANOS = 12_000_000L
        const val RECORDED_HFR_MAX_BLOBS_PER_FRAME = 32
        const val RECORDED_HFR_MAX_TOTAL_CANDIDATE_BLOBS = 150
        const val RECORDED_HFR_MAX_RANSAC_CANDIDATES = 90
        const val RECORDED_HFR_MAX_RANSAC_PAIR_HYPOTHESES = 4_096
        const val RECORDED_HFR_RANSAC_CANCELLATION_CHECK_INTERVAL = 128
        const val BALL_DIAMETER_ESTIMATE_MAX_RMS_RESIDUAL_PX = 24.0
        const val IMPORT_ESTIMATE_MAX_RMS_RESIDUAL_PX = 8.0
        const val ESTIMATE_RESIDUAL_BLOB_DIAMETER_FRACTION = 0.35
        const val IMPORT_ESTIMATE_MIN_BLOB_AREA_PX = 4
        const val HIT_BALL_MIN_ESTIMATE_MPH = 25.0
        const val AUTO_RECORD_ESTIMATE_DURATION_MILLIS = 3_000L
        const val SOUND_TRIGGER_SAMPLE_RATE_HZ = 48_000
        const val SOUND_TRIGGER_BASELINE_SAMPLES = 960
        const val SOUND_TRIGGER_WINDOW_SAMPLES = 96
        const val SOUND_TRIGGER_THRESHOLD_MULTIPLIER = 2.5
        const val SOUND_TRIGGER_MINIMUM_PEAK_DELTA = 100
        const val SOUND_TRIGGER_MINIMUM_BASELINE_RMS = 25.0
        const val SOUND_TRIGGER_COOLDOWN_SAMPLES = 4_800
        const val SOUND_TRIGGER_USER_ACTIONABLE_MILLIS = 5_000L
        const val SOUND_TRIGGER_PRE_IMPACT_CAPTURE_MILLIS = 1_000L
        const val SOUND_TRIGGER_POST_IMPACT_CAPTURE_MILLIS = 1_000L
        const val SOUND_TRIGGER_AUDIO_ARM_BUDGET_MILLIS = 1_500L
        const val SOUND_TRIGGER_HFR_START_BUDGET_MILLIS = 2_500L
        const val SOUND_TRIGGER_READY_CUE_BUDGET_MILLIS = 2_000L
        const val SOUND_TRIGGER_TOTAL_AUDIO_BUFFER_MILLIS =
            SOUND_TRIGGER_AUDIO_ARM_BUDGET_MILLIS +
                SOUND_TRIGGER_HFR_START_BUDGET_MILLIS +
                SOUND_TRIGGER_READY_CUE_BUDGET_MILLIS +
                SOUND_TRIGGER_USER_ACTIONABLE_MILLIS +
                SOUND_TRIGGER_POST_IMPACT_CAPTURE_MILLIS
        const val SOUND_TRIGGER_TOTAL_RECORDING_MILLIS =
            SOUND_TRIGGER_HFR_START_BUDGET_MILLIS +
                SOUND_TRIGGER_READY_CUE_BUDGET_MILLIS +
                SOUND_TRIGGER_USER_ACTIONABLE_MILLIS +
                SOUND_TRIGGER_POST_IMPACT_CAPTURE_MILLIS
        const val SOUND_TRIGGER_MAX_PRE_IMPACT_FRAMES = 12
        const val VOICE_IDLE_RESTART_DELAY_MILLIS = 250L
        const val VOICE_RESTART_DELAY_MILLIS = 350L
        const val VOICE_RESTART_MAX_DELAY_MILLIS = 2_800L
        const val VOICE_RESTART_MAX_CONSECUTIVE_ERRORS = 3
        const val REPORT_AUTO_CLEAR_DELAY_MILLIS = 10_000L
        const val READY_BEEP_COUNT = 3
        const val READY_BEEP_DURATION_MILLIS = 110
        const val READY_BEEP_SPACING_MILLIS = 170L
        const val READY_BEEP_RELEASE_PADDING_MILLIS = 50L
        const val READY_BEEP_VOLUME_PERCENT = 90
        const val READY_TTS_MAX_WAIT_MILLIS = 1_500L
        const val READY_CUE_ACCEPT_MARGIN_MILLIS = 150L
        const val READY_TTS_UTTERANCE_ID = "ready-to-shoot"
        const val VOICE_RUN_PROMPT = "Say shoot, record, cheese, or smile"
        const val VOICE_LISTENING_PROMPT = "Listening for shoot/record/cheese/smile"
        val VOICE_SHOOT_COMMANDS = setOf(
            "shoot",
        )
        val VOICE_RECORD_COMMANDS = setOf(
            "record",
            "cheese",
            "smile",
        )
        val VOICE_CLEAR_COMMANDS = setOf(
            "clear",
            "reset",
            "restart",
        )
        const val INITIAL_KNOWN_DISTANCE_FEET_TEXT = "11.0"
        const val INITIAL_CALIBRATION_PLANE_DEPTH_FEET_TEXT = ""
        const val INITIAL_BALL_PLANE_DEPTH_FEET_TEXT = ""
        const val INITIAL_MOTION_BLOB_SIDE_RATIO_TEXT = "2.00"
        const val INITIAL_LAUNCH_HEIGHT_FEET_TEXT = "4.0"
        const val COLOR_SAMPLE_RADIUS_PX = 4
        const val DEFAULT_PHASE14_BALL_DIAMETER_FEET = 3.8 / 12.0
        val DEFAULT_CALIPER_POINT_A = NormalizedFramePoint(0.20, 0.50)
        val DEFAULT_CALIPER_POINT_B = NormalizedFramePoint(0.80, 0.50)
        val DEFAULT_COLOR_SAMPLE_POINT = NormalizedFramePoint(0.50, 0.50)
        val DEFAULT_PHASE14_ROI = NormalizedFrameRect(0.10, 0.10, 0.90, 0.90)
        val DEFAULT_PHASE14_IMPACT_ZONE = NormalizedFramePolygon(
            listOf(
                NormalizedFramePoint(0.20, 0.25),
                NormalizedFramePoint(0.80, 0.25),
                NormalizedFramePoint(0.85, 0.75),
                NormalizedFramePoint(0.15, 0.75),
            ),
        )
        val DEFAULT_PHASE14_BALL_BOX = NormalizedFramePolygon(
            listOf(
                NormalizedFramePoint(0.47, 0.45),
                NormalizedFramePoint(0.53, 0.45),
                NormalizedFramePoint(0.53, 0.55),
                NormalizedFramePoint(0.47, 0.55),
            ),
        )
        val DEFAULT_PHASE14_COLOR_TOLERANCE = HsvTolerance(hueDegrees = 25.0, saturation = 0.45, value = 0.45)
        val DIRECT_UI_PIXEL_CONFIG = DirectPixelProofConfig(maxTotalSamples = 1_440)
    }
}
