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
import com.speedball.app.ui.SpeedBallApp
import com.speedball.app.ui.SpeedBallShellState
import com.speedball.app.ui.speedBallCaptureState
import com.speedball.app.ui.speedBallPlaceholderState

/** Launches the Compose shell and owns Phase 4 Camera2 capture diagnostics. */
class MainActivity : ComponentActivity() {
    private val logTag = "SPEEDBALL_CAPTURE"
    private lateinit var highSpeedCamera: HighSpeedCamera
    private lateinit var burstRecorder: HighSpeedBurstRecorder
    private var previewSurface: Surface? = null
    private var modes: List<HighSpeedMode> = emptyList()
    private var selectedMode: HighSpeedMode? = null
    private var lastDiagnostics: BurstDiagnostics? = null
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
            diagnosticLines = lastDiagnostics?.toUiLines().orEmpty(),
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

    private fun Double.format(decimals: Int): String =
        "%.${decimals}f".format(this)
}
