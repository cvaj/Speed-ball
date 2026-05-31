package com.speedball.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import android.view.Surface
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.speedball.app.capture.BurstDiagnostics
import com.speedball.app.capture.BurstFailure
import com.speedball.app.capture.BurstOptions
import com.speedball.app.capture.BurstOutcome
import com.speedball.app.capture.HighSpeedBurstRecorder
import com.speedball.app.capture.HighSpeedCamera
import com.speedball.app.capture.HighSpeedMode
import com.speedball.app.capture.HighSpeedModesResult
import com.speedball.app.capture.selectDefaultMode
import com.speedball.app.decode.BurstVideoDecoder
import com.speedball.app.decode.DecodeCompletionGate
import com.speedball.app.decode.DecodeFailure
import com.speedball.app.decode.DecodeOutcome
import com.speedball.app.decode.ReconciliationDiagnostics
import com.speedball.app.decode.TimestampAnchorOutcome
import com.speedball.app.decode.buildDecodeWorkBounds
import com.speedball.app.decode.runDecodeWithTimeout
import com.speedball.app.decode.timestampAnchorDiagnosticLogLines
import com.speedball.app.ui.SpeedBallApp
import com.speedball.app.ui.SpeedBallShellState
import com.speedball.app.ui.decodeOutcomeUiLines
import com.speedball.app.ui.speedBallCaptureState
import com.speedball.app.ui.speedBallPlaceholderState
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** Launches the Compose shell and owns Camera2 capture plus Phase 5 decode diagnostics. */
class MainActivity : ComponentActivity() {
    private val logTag = "SPEEDBALL_CAPTURE"
    private lateinit var highSpeedCamera: HighSpeedCamera
    private lateinit var burstRecorder: HighSpeedBurstRecorder
    private val burstVideoDecoder = BurstVideoDecoder()
    private val decodeCompletionGate = DecodeCompletionGate()
    private val decodeSupervisorExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val decodeWorkerExecutor: ExecutorService = Executors.newCachedThreadPool()
    private var previewSurface: Surface? = null
    private var modes: List<HighSpeedMode> = emptyList()
    private var selectedMode: HighSpeedMode? = null
    private var lastDiagnostics: BurstDiagnostics? = null
    private var lastDecodeOutcome: DecodeOutcome? = null
    private var lastFailure: BurstOutcome.Failure? = null
    private var captureStatus: String = "Idle"
    private var shellState by mutableStateOf(speedBallPlaceholderState())
    private var autoStartPending = false

    private val requestCameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        updateShellState(status = if (it) "Permission granted" else "Permission denied")
        if (it) refreshModes()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        highSpeedCamera = HighSpeedCamera(this)
        burstRecorder = HighSpeedBurstRecorder(this)
        autoStartPending = intent.getBooleanExtra("autoStart120", false)
        updateShellState(status = "Idle")
        setContent {
            val state: SpeedBallShellState = shellState
            SpeedBallApp(
                state = state,
                onPreviewSurface = {
                    previewSurface = it
                    startAutoBurstIfReady()
                },
                onRequestPermission = { ensureCameraPermission() },
                onRefreshModes = { refreshModes() },
                onStartBurst = { startBurst() },
                onStopBurst = { stopBurst() },
            )
        }
        if (autoStartPending) refreshModes()
    }

    override fun onPause() {
        stopBurst()
        super.onPause()
    }

    override fun onStop() {
        stopBurst()
        super.onStop()
    }

    override fun onDestroy() {
        decodeSupervisorExecutor.shutdownNow()
        decodeWorkerExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun ensureCameraPermission() {
        if (hasCameraPermission()) {
            updateShellState(status = "Permission granted")
        } else {
            requestCameraPermission.launch(Manifest.permission.CAMERA)
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
                lastFailure = null
                Log.i(logTag, "MODES ${modes.joinToString { it.label + ":recordSupported=" + it.recordSupported }}")
                updateShellState(status = "Modes loaded")
                startAutoBurstIfReady()
            }
            is HighSpeedModesResult.Failure -> {
                modes = emptyList()
                selectedMode = null
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
        updateShellState(status = captureStatus)
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
            }
        }
        if (immediateFailure != null) {
            lastFailure = immediateFailure
            captureStatus = "Burst failed"
            Log.e(logTag, "BURST_FAILURE reason=${immediateFailure.reason} message=${immediateFailure.message}")
            updateShellState(status = captureStatus)
        }
    }

    private fun stopBurst() {
        burstRecorder.stopActive()
        cancelDecodeProof()
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

    private fun updateShellState(status: String) {
        captureStatus = status
        shellState = speedBallCaptureState(
            permissionLabel = if (hasCameraPermission()) "Granted" else "Not granted",
            captureStatus = status,
            modeLines = modes.map { mode ->
                val support = if (mode.recordSupported && mode.fps == 120) "recordable" else "unsupported for Phase 4 record"
                "${mode.label} ($support)"
            },
            selectedModeLine = selectedMode?.label,
            diagnosticLines = lastDiagnostics?.toUiLines().orEmpty() + decodeOutcomeUiLines(lastDecodeOutcome),
            failureLine = lastFailure?.let { "Failure: ${it.reason} - ${it.message}" },
        )
    }

    private fun hasCameraPermission(): Boolean =
        checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun BurstDiagnostics.toUiLines(): List<String> =
        listOf(
            "callbacks=$callbackCount uniqueTs=$uniqueTimestampCount expected=$expectedUniqueTimestampCount min=$minimumUniqueTimestampCount",
            "medianGapMs=${medianGapMillis?.format(2) ?: "n/a"} band=${medianGapLowerBoundMillis.format(2)}..${medianGapUpperBoundMillis.format(2)} pass=$medianGapPassesRateBand",
            "proof=$captureProofPasses file=$displayOutputName bytes=$fileBytes",
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
    }

    private fun logTimestampAnchorOutcome(file: File, outcome: TimestampAnchorOutcome) {
        timestampAnchorDiagnosticLogLines(outcome, file.displayNameOnly(), LOG_VALUE_CHUNK_SIZE).forEach { line ->
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

    private companion object {
        const val LOG_VALUE_CHUNK_SIZE = 80
    }
}
