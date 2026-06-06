package com.speedball.app.ui

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class CalibrationUiSourceTest {
    @Test
    fun appProvidesLiveCameraFeedDuringCalibration() {
        val liveFeed = readMainSource("capture/CameraLiveFeedController.kt")
        val mainActivity = readMainSource("MainActivity.kt")

        assertTrue(liveFeed.contains("CameraDevice.TEMPLATE_PREVIEW"))
        assertTrue(liveFeed.contains("setRepeatingRequest"))
        assertTrue(liveFeed.contains("CONTROL_AE_TARGET_FPS_RANGE"))
        assertTrue(mainActivity.contains("startLiveCameraFeedIfReady()"))
        assertTrue(mainActivity.contains("cameraLiveFeedController.stop()"))
        assertTrue(mainActivity.contains("restartLiveCameraFeedIfReady()"))
        assertFalse(liveFeed.contains("MediaRecorder"))
        assertFalse(liveFeed.contains("BurstVideoDecoder"))
    }

    @Test
    fun calibrationUiUsesDistanceInputAndDraggableVerticalCaliperLines() {
        val app = readMainSource("ui/SpeedBallApp.kt")
        val mainActivity = readMainSource("MainActivity.kt")
        val manifest = readProjectFile("app/src/main/AndroidManifest.xml", "src/main/AndroidManifest.xml")

        assertTrue(app.contains("BasicTextField"))
        assertTrue(app.contains("DistanceInputOverlay"))
        assertTrue(app.contains("Distance ft"))
        assertTrue(app.contains("ToolsHandle"))
        assertTrue(app.contains("StatusStrip"))
        assertTrue(app.contains("ResultOverlay"))
        assertTrue(app.contains("CaptureStatusOverlay"))
        assertTrue(app.contains("buildResultOverlayLines"))
        assertTrue(app.contains("isSuccessfulResultStatus(state.captureStatus)"))
        assertTrue(app.contains("if (hasSuccessfulResultStatus)"))
        assertTrue(app.contains("normalized == \"visual estimate complete\""))
        assertTrue(app.contains("normalized == \"import estimate complete\""))
        assertTrue(app.contains("normalized == \"recorded estimate complete\""))
        assertTrue(app.contains("onDismissVisualEstimateReport"))
        assertFalse(app.contains("dismissedResultOverlayToken"))
        assertTrue(app.contains(".background(Color.Black)"))
        assertTrue(app.contains("VELOCITY \${it} MPH"))
        assertTrue(app.contains("ANGLE \${it} DEG"))
        assertTrue(app.contains("DISTANCE \${it} FT"))
        assertTrue(app.contains("text = \"CLEAR\""))
        assertTrue(app.contains("Status: \$statusText"))
        assertTrue(app.contains("Target: \$setupTarget"))
        assertTrue(app.contains("TextOverflow.Ellipsis"))
        assertTrue(app.contains("BottomToolDrawer"))
        assertTrue(app.contains("ApplicationSettingsPanel"))
        assertTrue(app.contains("align(Alignment.BottomCenter)"))
        assertTrue(app.contains("background(Color(0x66000000))"))
        assertTrue(app.contains("background(Color(0x77000000))"))
        assertTrue(app.contains("color: Color = Color(0x99207982)"))
        assertTrue(app.contains("controlsVisible"))
        assertTrue(app.contains("captureActive"))
        assertTrue(app.contains("OverlayButton(\"Hide\""))
        assertTrue(app.contains("OverlayButton(\"Run\""))
        assertTrue(app.contains("RunModePanel"))
        assertTrue(app.contains("state.appMode == SpeedBallAppMode.Setup"))
        assertTrue(app.contains("state.appMode == SpeedBallAppMode.Run"))
        assertTrue(app.contains("onManualShoot"))
        assertTrue(app.contains("onEnterSetupMode"))
        assertTrue(app.contains("aspectRatio(cameraAspect)"))
        assertTrue(app.contains("fillMaxHeight()"))
        assertTrue(app.contains("applyCameraPreviewTransform"))
        assertTrue(app.contains("sourceToViewRotationDegrees = previewRotationDegrees"))
        assertTrue(mainActivity.contains("readBackCameraPreviewOrientation"))
        assertTrue(app.contains("setTransform(matrix)"))
        assertTrue(app.contains("toPreviewOffset"))
        assertTrue(app.contains("normalizedToViewPoint"))
        assertTrue(app.contains("OverlayButton(\"Setup\""))
        assertTrue(app.contains("OverlayButton(\"Est 120\""))
        assertTrue(app.contains("OverlayButton(\"Record 120\""))
        assertTrue(app.contains("if (fineLineDrag) \"Fine\" else \"Coarse\""))
        assertTrue(app.contains("OverlayButton(\"Apply Distance\""))
        assertTrue(app.contains("Text(text = \"Tools\""))
        assertFalse(app.contains("OverlayButton(\"Tools\""))
        assertFalse(app.contains("align(Alignment.BottomStart)"))
        assertTrue(app.contains("modifier = Modifier.align(Alignment.TopCenter)"))
        assertFalse(app.contains("StatusRibbon"))
        assertFalse(app.contains("abs(change.position.x - aX)"))
        assertFalse(app.contains("abs(change.position.x - bX)"))
        assertFalse(app.contains("else change.position.x"))
        assertTrue(app.contains("var activeDragLineFraction: Float? = null"))
        assertTrue(app.contains("onDragStart = {"))
        assertTrue(app.contains("onDragEnd = {"))
        assertTrue(app.contains("onDragCancel = {"))
        assertTrue(app.contains("change.consume()"))
        assertTrue(app.contains("activeDragLineFraction = fraction"))
        assertTrue(app.contains("rememberUpdatedState"))
        assertTrue(app.contains(".pointerInput(Unit)"))
        assertTrue(app.contains("dragCaliperAOverrideFraction = fraction"))
        assertTrue(app.contains("dragCaliperBOverrideFraction = fraction"))
        assertTrue(app.contains("fun commitActiveDrag()"))
        assertTrue(app.contains("currentOnSetCaliperAFromPreview"))
        assertTrue(app.contains("currentOnSetCaliperBFromPreview"))
        assertTrue(app.contains("internal fun nextCaliperFraction("))
        assertTrue(app.contains("current + dragDeltaX / totalWidthPx * scale"))
        assertTrue(app.contains(".coerceIn(0f, 1f)"))
        assertFalse(app.contains("phase14State.calibrationPointA,\n                    phase14State.calibrationPointB"))
        assertTrue(mainActivity.contains("val clampedX = x.coerceIn(0f, viewWidth.toFloat())"))
        assertTrue(mainActivity.contains("NormalizedFramePoint((clampedX / viewWidth.toFloat()).toDouble(), 0.5)"))
        assertTrue(app.contains("when (currentSetupAdjustmentTargetLabel)"))
        assertTrue(app.contains("if (showControls) {"))
        assertTrue(app.contains("drawCircle(color = sampleColor"))
        assertTrue(app.contains("drawRect("))
        assertTrue(app.contains("axisHorizontalStart"))
        assertTrue(app.contains("axisVerticalEnd"))
        assertTrue(app.contains("phase14State.levelReference?.let"))
        assertTrue(app.contains("Math.toRadians(levelReference.rollDegrees)"))
        assertTrue(app.contains("levelHorizontalAxisColor"))
        assertTrue(app.contains("levelVerticalAxisColor"))
        assertFalse(app.contains("levelReference?.rollDegrees ?: 0.0"))
        assertTrue(mainActivity.contains("ensureSetupLevelReference()"))
        assertTrue(mainActivity.contains("startLiveLevelReferenceIfReady()"))
        assertTrue(mainActivity.contains("stopLiveLevelReference()"))
        assertTrue(mainActivity.contains("levelReferenceCapture.observeLive"))
        assertTrue(mainActivity.contains("if (!activityResumed || liveLevelReferenceActive || !hasCameraPermission() || selectedMode == null || isCaptureActiveStatus()) return"))
        assertTrue(mainActivity.contains("stopLiveLevelReference()"))
        assertTrue(mainActivity.contains("visualEstimateLevelRollDeg"))
        assertTrue(mainActivity.contains("LevelReferenceSnapshot("))
        assertTrue(mainActivity.contains("Phase14WorkflowEvent.LevelReferenceCaptured(snapshot)"))
        assertTrue(mainActivity.contains("applyDebugVisualEstimateSetupFromIntent()"))
        assertTrue(mainActivity.contains("private fun currentLevelReferenceDisplayRotation()"))
        assertTrue(mainActivity.contains("levelCaptureInProgress"))
        assertTrue(mainActivity.contains("TextToSpeech"))
        assertTrue(mainActivity.contains("ToneGenerator"))
        assertTrue(mainActivity.contains("VOICE_SHOOT_COMMANDS"))
        assertTrue(mainActivity.contains("handleShootCommand()"))
        assertTrue(mainActivity.contains("handleShootCommand(ShootTrigger.Manual)"))
        assertTrue(mainActivity.contains("shootNotReadyStatus()"))
        assertTrue(mainActivity.contains("\"VOICE_SHOOT_NOT_READY\""))
        assertTrue(mainActivity.contains("\"MANUAL_SHOOT_NOT_READY\""))
        assertTrue(mainActivity.contains("Log.i(logTag, \"\$event reason="))
        assertTrue(mainActivity.contains("return calibrationNoRead.message"))
        assertTrue(mainActivity.contains("return colorReadiness.message"))
        assertTrue(mainActivity.contains("phase14ColorNotReadyStatus()"))
        assertTrue(mainActivity.contains("Sample the ball color before measuring."))
        assertTrue(mainActivity.contains("Sampled ball color is outside the valid HSV range."))
        assertTrue(mainActivity.contains("Set the ball-flight ROI before measuring."))
        assertTrue(mainActivity.contains("Color region does not overlap the frame."))
        assertFalse(mainActivity.contains("return \"Distance calibration required\""))
        assertFalse(mainActivity.contains("return \"Ball color required\""))
        assertTrue(mainActivity.contains("Level required"))
        assertTrue(mainActivity.contains("playReadySignalThenRecordedHfrEstimate()"))
        assertTrue(mainActivity.contains("readySignalPending"))
        assertTrue(mainActivity.contains("startTimedRecordingEstimate()"))
        assertTrue(mainActivity.contains("companionSurfaceMode = BurstCompanionSurfaceMode.OFFSCREEN_PREVIEW"))
        assertFalse(mainActivity.contains("VOICE_RECORD_SUCCESS"))
        assertTrue(mainActivity.contains("if (previewSurface == null)"))
        assertTrue(mainActivity.contains("updateShellState(status = \"Live feed required\")"))
        assertTrue(mainActivity.contains("startAutoDirectVisualEstimateIfReady()"))
        assertTrue(mainActivity.contains("!autoStartDirectVisualEstimatePending || selectedMode == null || previewSurface == null || !activityResumed || !windowFocused"))
        assertTrue(mainActivity.contains("directVisualEstimateCapture.start(config, modes)"))
        assertFalse(mainActivity.contains("directVisualEstimateCapture.start(config, modes, visiblePreviewSurface)"))
        assertTrue(mainActivity.contains("cameraLiveFeedController.stop()"))
        assertTrue(mainActivity.contains("restartLiveCameraFeedIfReady()"))
        assertTrue(mainActivity.contains("private fun stopDirectVisualEstimate()"))
        assertTrue(mainActivity.contains("override fun onResume()"))
        assertTrue(mainActivity.contains("uniqueSensorTs=\${outcome.uniqueSensorTimestampCount}"))
        assertTrue(mainActivity.contains("reason = outcome.reason.toVisualEstimateNoReadReason()"))
        assertTrue(mainActivity.contains("DirectTimingSourceFailure.SESSION_CONFIGURATION_FAILED,"))
        assertTrue(mainActivity.contains("VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED"))
        assertTrue(mainActivity.contains("READY_SIGNAL_TO_ESTIMATE_DELAY_MILLIS"))
        assertFalse(mainActivity.contains("playReadySignalThenRecord()"))
        assertFalse(mainActivity.contains("READY_SIGNAL_TO_RECORD_DELAY_MILLIS"))
        assertTrue(mainActivity.contains("updateShellState(status = \"Ready to shoot\")"))
        assertTrue(mainActivity.contains("Say shoot or record"))
        assertTrue(mainActivity.contains("Listening for shoot"))
        assertTrue(mainActivity.contains("VOICE_RESTART_MAX_CONSECUTIVE_ERRORS"))
        assertTrue(mainActivity.contains("SpeechRecognizer.ERROR_NO_MATCH"))
        assertTrue(mainActivity.contains("SpeechRecognizer.ERROR_SPEECH_TIMEOUT"))
        assertTrue(mainActivity.contains("VOICE_RECORD_LISTEN_IDLE"))
        assertTrue(mainActivity.contains("voiceConsecutiveErrors = 0"))
        assertTrue(mainActivity.contains("SpeedBallRunCommandState.VoiceRetrying"))
        assertTrue(mainActivity.contains("VOICE_RECORD_LISTEN_DEGRADED"))
        assertFalse(mainActivity.contains("removeCallbacksAndMessages(null)"))
        assertFalse(mainActivity.contains("visualEstimateReportFor(status, lastDirectVisualEstimateOutcome)"))
        assertTrue(mainActivity.contains("readySpeech?.speak(\"Ready\""))
        assertTrue(mainActivity.contains("repeat(READY_BEEP_COUNT)"))
        assertTrue(mainActivity.contains("phase14WorkflowState.canArm()"))
        assertTrue(app.contains("FINE_DRAG_SCALE"))
        assertTrue(app.contains("detectTapGestures"))
        assertTrue(app.contains("detectDragGestures"))
        assertTrue(mainActivity.contains("SCREEN_ORIENTATION_LANDSCAPE"))
        assertTrue(mainActivity.contains("SYSTEM_UI_FLAG_IMMERSIVE_STICKY"))
        assertTrue(manifest.contains("android:screenOrientation=\"landscape\""))
        assertTrue(app.contains("start = Offset(a.x, 0f)"))
        assertTrue(app.contains("end = Offset(a.x, size.height)"))
        assertTrue(app.contains("start = Offset(b.x, 0f)"))
        assertTrue(app.contains("end = Offset(b.x, size.height)"))
        assertTrue(mainActivity.contains("parsePositiveFeet(knownDistanceFeetText)"))
        assertTrue(mainActivity.contains("knownDistanceFeet = distanceFeet ?: Double.NaN"))
        assertTrue(mainActivity.contains("val distanceFeet = phase14WorkflowState.knownDistanceFeet ?: return emptyCalibration()"))
        assertTrue(mainActivity.contains("scaleMode = PreviewScaleMode.FitCenter"))
        assertFalse(mainActivity.contains("normalizedPreviewPoint(clampedX, viewHeight / 2f, viewWidth, viewHeight)"))
        assertFalse(mainActivity.contains("x / viewWidth.toDouble()"))
        assertFalse(mainActivity.contains("DEFAULT_PHASE14_KNOWN_DISTANCE_FEET"))
        assertFalse(mainActivity.contains("knownDistanceFeet = 8.0"))
        assertFalse(app.contains(".height(220.dp)"))
    }

    @Test
    fun noReadReportsUseBottomPanelWithoutSuccessOverlayBlackout() {
        val app = readMainSource("ui/SpeedBallApp.kt")
        val shellState = readMainSource("ui/SpeedBallShellState.kt")

        assertTrue(shellState.contains("val visualEstimateReport: VisualEstimateReport?"))
        assertTrue(shellState.contains("enum class SpeedBallAppMode"))
        assertTrue(shellState.contains("sealed interface SpeedBallRunCommandState"))
        assertTrue(app.contains("VisualEstimateNoReadPanel"))
        assertFalse(app.contains("dismissedNoReadReportToken"))
        assertTrue(app.contains("state.visualEstimateReport?.takeIf { it.kind != VisualEstimateReportKind.Success }"))
        assertTrue(app.contains("state.visualEstimateReport?.takeIf { it.kind == VisualEstimateReportKind.Success }"))
        assertTrue(app.contains("ResultOverlay("))
        assertTrue(app.contains("val showNoReadReport = visibleNoReadReport != null"))
        assertTrue(app.contains("visibleNoReadReport?.let { report ->"))
        assertTrue(app.contains("onClear = { onDismissVisualEstimateReport(report.attemptId) }"))
        assertFalse(app.contains("VisualEstimateNoReadPanel(\n                lines = report.lines,\n                onClear = { dismissedResultOverlayToken"))
        assertFalse(app.contains("VisualEstimateNoReadPanel(\n                lines = report.lines,\n                onClear = { dismissedNoReadReportToken = report.dismissToken },\n                modifier = Modifier.fillMaxSize()"))
        assertTrue(app.contains("SetupPreviewSurface("))
        assertTrue(app.contains("AndroidView("))
    }

    @Test
    fun caliperDragUsesCaptainStyleContinuousFractionMath() {
        assertEquals(
            0.6f,
            nextCaliperFraction(current = 0.5f, dragDeltaX = 100f, totalWidthPx = 1000f, fineLineDrag = false),
            1e-6f,
        )
        assertEquals(
            0.505f,
            nextCaliperFraction(current = 0.5f, dragDeltaX = 100f, totalWidthPx = 1000f, fineLineDrag = true),
            1e-6f,
        )

        var fraction = 0.2f
        repeat(10) {
            fraction = nextCaliperFraction(fraction, dragDeltaX = 100f, totalWidthPx = 1000f, fineLineDrag = false)
        }
        assertEquals(1.0f, fraction, 1e-6f)

        fraction = 0.8f
        repeat(10) {
            fraction = nextCaliperFraction(fraction, dragDeltaX = -100f, totalWidthPx = 1000f, fineLineDrag = false)
        }
        assertEquals(0.0f, fraction, 1e-6f)
    }

    @Test
    fun liveColorSamplingUsesPreviewPixelsAndFailsClosed() {
        val mainActivity = readMainSource("MainActivity.kt")
        val reducer = readMainSource("measurement/Phase14WorkflowState.kt")

        assertTrue(mainActivity.contains("PixelCopy.request"))
        assertTrue(mainActivity.contains("averageBitmapHsv"))
        assertTrue(mainActivity.contains("ColorMath.argbToHsv"))
        assertTrue(mainActivity.contains("Phase14WorkflowEvent.ColorSampleCleared"))
        assertTrue(reducer.contains("data class ColorSampleCleared"))
        assertFalse(mainActivity.contains("DEFAULT_PHASE14_BALL_COLOR"))
    }

    private fun readMainSource(relativeFileName: String): String {
        val appPath = Path.of("app/src/main/java/com/speedball/app/$relativeFileName")
        val localPath = Path.of("src/main/java/com/speedball/app/$relativeFileName")
        val path = if (appPath.exists()) appPath else localPath
        return Files.readAllLines(path).joinToString("\n")
    }

    private fun readProjectFile(rootRelativeFileName: String, moduleRelativeFileName: String): String {
        val rootPath = Path.of(rootRelativeFileName)
        val modulePath = Path.of(moduleRelativeFileName)
        val path = if (rootPath.exists()) rootPath else modulePath
        return Files.readAllLines(path).joinToString("\n")
    }
}
