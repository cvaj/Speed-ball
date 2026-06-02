package com.speedball.app.ui

import android.graphics.Matrix
import android.graphics.RectF
import android.graphics.SurfaceTexture
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.view.Surface
import android.view.TextureView
import kotlin.math.cos
import com.speedball.app.measurement.NormalizedFramePoint
import com.speedball.app.measurement.NormalizedFrameRect
import com.speedball.app.measurement.Phase14WorkflowState
import com.speedball.app.measurement.FrameDimensions
import com.speedball.app.measurement.PreviewFrameTransform
import com.speedball.app.measurement.PreviewScaleMode
import kotlin.math.sin
import kotlin.math.tan

/** Renders the Phase 1 placeholder shell from a Compose-free state model. */
@Composable
fun SpeedBallApp(
    state: SpeedBallShellState = speedBallPlaceholderState(),
    onPreviewSurface: (Surface?) -> Unit = {},
    onKnownDistanceChanged: (String) -> Unit = {},
    onPreviewTap: (Float, Float, Int, Int) -> Unit = { _, _, _, _ -> },
    onSetCaliperAFromPreview: (Float, Int, Int) -> Unit = { _, _, _ -> },
    onSetCaliperBFromPreview: (Float, Int, Int) -> Unit = { _, _, _ -> },
    onRequestPermission: () -> Unit = {},
    onRefreshModes: () -> Unit = {},
    onUseKnownDistance: () -> Unit = {},
    onUseBallDiameterFallback: () -> Unit = {},
    onCaptureLevel: () -> Unit = {},
    onSampleColor: () -> Unit = {},
    onSetRoi: () -> Unit = {},
    onSelectCaliperA: () -> Unit = {},
    onSelectCaliperB: () -> Unit = {},
    onSelectColorPoint: () -> Unit = {},
    onSelectRoi: () -> Unit = {},
    onNudgeSetup: (Double, Double) -> Unit = { _, _ -> },
    onFineNudgeSetup: (Double, Double) -> Unit = { _, _ -> },
    onPickImport: () -> Unit = {},
    onArmEstimate: () -> Unit = {},
    onVoiceRecord: () -> Unit = {},
    onStartBurst: () -> Unit = {},
    onStartVisualEstimate: () -> Unit = {},
    onRetryEstimate: () -> Unit = {},
    onRecalibrateEstimate: () -> Unit = {},
    onStopBurst: () -> Unit = {},
) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xff1f6f78),
            secondary = Color(0xffc18a00),
            tertiary = Color(0xff6a7f2a),
            background = Color(0xfff8faf7),
            surface = Color(0xfff8faf7),
            onPrimary = Color.White,
            onSurface = Color(0xff1f2528),
        ),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                SetupPreviewSurface(
                    state = state,
                    phase14State = state.phase14State,
                    onPreviewSurface = onPreviewSurface,
                    onKnownDistanceChanged = onKnownDistanceChanged,
                    onPreviewTap = onPreviewTap,
                    onSetCaliperAFromPreview = onSetCaliperAFromPreview,
                    onSetCaliperBFromPreview = onSetCaliperBFromPreview,
                    onRequestPermission = onRequestPermission,
                    onRefreshModes = onRefreshModes,
                    onUseKnownDistance = onUseKnownDistance,
                    onUseBallDiameterFallback = onUseBallDiameterFallback,
                    onCaptureLevel = onCaptureLevel,
                    onSampleColor = onSampleColor,
                    onSetRoi = onSetRoi,
                    onSelectCaliperA = onSelectCaliperA,
                    onSelectCaliperB = onSelectCaliperB,
                    onSelectColorPoint = onSelectColorPoint,
                    onSelectRoi = onSelectRoi,
                    onNudgeSetup = onNudgeSetup,
                    onFineNudgeSetup = onFineNudgeSetup,
                    onVoiceRecord = onVoiceRecord,
                    onStartVisualEstimate = onStartVisualEstimate,
                    onPickImport = onPickImport,
                    onArmEstimate = onArmEstimate,
                    onRetryEstimate = onRetryEstimate,
                    onRecalibrateEstimate = onRecalibrateEstimate,
                    onStartBurst = onStartBurst,
                    onStopBurst = onStopBurst,
                )
            }
        }
    }
}

@Composable
private fun SetupPreviewSurface(
    state: SpeedBallShellState,
    phase14State: Phase14WorkflowState,
    onPreviewSurface: (Surface?) -> Unit,
    onKnownDistanceChanged: (String) -> Unit,
    onPreviewTap: (Float, Float, Int, Int) -> Unit,
    onSetCaliperAFromPreview: (Float, Int, Int) -> Unit,
    onSetCaliperBFromPreview: (Float, Int, Int) -> Unit,
    onRequestPermission: () -> Unit,
    onRefreshModes: () -> Unit,
    onUseKnownDistance: () -> Unit,
    onUseBallDiameterFallback: () -> Unit,
    onCaptureLevel: () -> Unit,
    onSampleColor: () -> Unit,
    onSetRoi: () -> Unit,
    onSelectCaliperA: () -> Unit,
    onSelectCaliperB: () -> Unit,
    onSelectColorPoint: () -> Unit,
    onSelectRoi: () -> Unit,
    onNudgeSetup: (Double, Double) -> Unit,
    onFineNudgeSetup: (Double, Double) -> Unit,
    onVoiceRecord: () -> Unit,
    onStartVisualEstimate: () -> Unit,
    onPickImport: () -> Unit,
    onArmEstimate: () -> Unit,
    onRetryEstimate: () -> Unit,
    onRecalibrateEstimate: () -> Unit,
    onStartBurst: () -> Unit,
    onStopBurst: () -> Unit,
) {
    var controlsVisible by remember { mutableStateOf(false) }
    var settingsVisible by remember { mutableStateOf(false) }
    var fineLineDrag by remember { mutableStateOf(false) }
    var dragCaliperAOverrideFraction by remember { mutableStateOf<Float?>(null) }
    var dragCaliperBOverrideFraction by remember { mutableStateOf<Float?>(null) }
    var dismissedResultOverlayToken by remember { mutableStateOf<String?>(null) }
    val currentPhase14State by rememberUpdatedState(phase14State)
    val currentSetupAdjustmentTargetLabel by rememberUpdatedState(state.setupAdjustmentTargetLabel)
    val currentFineLineDrag by rememberUpdatedState(fineLineDrag)
    val currentOnSetCaliperAFromPreview by rememberUpdatedState(onSetCaliperAFromPreview)
    val currentOnSetCaliperBFromPreview by rememberUpdatedState(onSetCaliperBFromPreview)
    val currentOnPreviewTap by rememberUpdatedState(onPreviewTap)
    val sourceDimensions = phase14State.geometry?.source
    val previewRotationDegrees = state.previewRotationDegrees
    val cameraAspect = sourceDimensions?.let { it.width.toFloat() / it.height.toFloat() } ?: (16f / 9f)
    val captureActive = state.captureStatus.contains("record", ignoreCase = true) ||
        state.captureStatus.contains("running", ignoreCase = true)
    val showControls = controlsVisible && !captureActive
    val hasSuccessfulResultStatus = isSuccessfulResultStatus(state.captureStatus)
    val resultOverlayLines = if (hasSuccessfulResultStatus) {
        buildResultOverlayLines(state.diagnosticLines)
    } else {
        emptyList()
    }
    val resultOverlayToken = resultOverlayLines.joinToString(separator = "|")
    val showResultOverlay = resultOverlayLines.isNotEmpty() && dismissedResultOverlayToken != resultOverlayToken
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        val containerAspect = maxWidth.value / maxHeight.value
        val previewModifier = if (containerAspect > cameraAspect) {
            Modifier
                .fillMaxHeight()
                .aspectRatio(cameraAspect)
                .align(Alignment.Center)
        } else {
            Modifier
                .fillMaxWidth()
                .aspectRatio(cameraAspect)
                .align(Alignment.Center)
        }
        AndroidView(
            modifier = previewModifier,
            factory = { context ->
                TextureView(context).apply {
                    var cameraSurface: Surface? = null
                    fun publishSurface(texture: SurfaceTexture) {
                        val bufferWidth = sourceDimensions?.width ?: 1280
                        val bufferHeight = sourceDimensions?.height ?: 720
                        texture.setDefaultBufferSize(bufferWidth, bufferHeight)
                        applyCameraPreviewTransform(bufferWidth, bufferHeight, previewRotationDegrees)
                        cameraSurface?.release()
                        cameraSurface = Surface(texture)
                        onPreviewSurface(cameraSurface)
                    }
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                            publishSurface(surface)
                        }

                        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                            val bufferWidth = sourceDimensions?.width ?: 1280
                            val bufferHeight = sourceDimensions?.height ?: 720
                            surface.setDefaultBufferSize(bufferWidth, bufferHeight)
                            applyCameraPreviewTransform(bufferWidth, bufferHeight, previewRotationDegrees)
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            onPreviewSurface(null)
                            cameraSurface?.release()
                            cameraSurface = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                    if (isAvailable) {
                        surfaceTexture?.let(::publishSurface)
                    }
                }
            },
            update = { view ->
                sourceDimensions?.let { dimensions ->
                    view.surfaceTexture?.setDefaultBufferSize(dimensions.width, dimensions.height)
                    view.applyCameraPreviewTransform(dimensions.width, dimensions.height, previewRotationDegrees)
                }
            },
        )
        Canvas(
            modifier = previewModifier
                .pointerInput(Unit) {
                    var activeDragLineFraction: Float? = null
                    var activeDragTarget: String? = null
                    var activeDragCanvasSize: IntSize? = null

                    fun commitActiveDrag() {
                        val fraction = activeDragLineFraction
                        val canvasSize = activeDragCanvasSize
                        when (activeDragTarget) {
                            "A" -> if (fraction != null && canvasSize != null) {
                                currentOnSetCaliperAFromPreview(
                                    fraction * canvasSize.width.toFloat(),
                                    canvasSize.width,
                                    canvasSize.height,
                                )
                            }
                            "B" -> if (fraction != null && canvasSize != null) {
                                currentOnSetCaliperBFromPreview(
                                    fraction * canvasSize.width.toFloat(),
                                    canvasSize.width,
                                    canvasSize.height,
                                )
                            }
                        }
                        dragCaliperAOverrideFraction = null
                        dragCaliperBOverrideFraction = null
                        activeDragLineFraction = null
                        activeDragTarget = null
                        activeDragCanvasSize = null
                    }

                    detectDragGestures(
                        onDragStart = {
                            val pointA = currentPhase14State.calibrationPointA ?: DEFAULT_CALIPER_A
                            val pointB = currentPhase14State.calibrationPointB ?: DEFAULT_CALIPER_B
                            activeDragTarget = currentSetupAdjustmentTargetLabel
                            activeDragLineFraction = when (activeDragTarget) {
                                "A" -> pointA.x.toFloat()
                                "B" -> pointB.x.toFloat()
                                else -> null
                            }
                        },
                        onDragEnd = {
                            commitActiveDrag()
                        },
                        onDragCancel = {
                            commitActiveDrag()
                        },
                        onDrag = { change, dragAmount ->
                            val canvasSize = IntSize(size.width, size.height)
                            val pointA = currentPhase14State.calibrationPointA ?: DEFAULT_CALIPER_A
                            val pointB = currentPhase14State.calibrationPointB ?: DEFAULT_CALIPER_B
                            activeDragCanvasSize = canvasSize
                            change.consume()
                            when (activeDragTarget) {
                                "A" -> {
                                    val fraction = nextCaliperFraction(
                                        current = activeDragLineFraction ?: pointA.x.toFloat(),
                                        dragDeltaX = dragAmount.x,
                                        totalWidthPx = canvasSize.width.toFloat(),
                                        fineLineDrag = currentFineLineDrag,
                                    )
                                    activeDragLineFraction = fraction
                                    dragCaliperAOverrideFraction = fraction
                                }
                                "B" -> {
                                    val fraction = nextCaliperFraction(
                                        current = activeDragLineFraction ?: pointB.x.toFloat(),
                                        dragDeltaX = dragAmount.x,
                                        totalWidthPx = canvasSize.width.toFloat(),
                                        fineLineDrag = currentFineLineDrag,
                                    )
                                    activeDragLineFraction = fraction
                                    dragCaliperBOverrideFraction = fraction
                                }
                            }
                        },
                    )
                }
                .pointerInput(Unit) {
                    detectTapGestures { tap ->
                        when (currentSetupAdjustmentTargetLabel) {
                            "Color", "ROI" -> currentOnPreviewTap(tap.x, tap.y, size.width, size.height)
                        }
                    }
                },
        ) {
            val pointA = dragCaliperAOverrideFraction?.let { NormalizedFramePoint(it.toDouble(), 0.5) } ?:
                (phase14State.calibrationPointA ?: DEFAULT_CALIPER_A)
            val pointB = dragCaliperBOverrideFraction?.let { NormalizedFramePoint(it.toDouble(), 0.5) } ?:
                (phase14State.calibrationPointB ?: DEFAULT_CALIPER_B)
            val caliperColor = Color(0xfff5f7fb)
            val sampleColor = Color(0xffffd400)
            val roiColor = Color(0xff1fbf75)
            val levelColor = Color(0xff34d6ff)
            val levelHorizontalAxisColor = Color(0xff34d6ff)
            val levelVerticalAxisColor = Color(0xffffc247)
            val referenceAxisShadow = Color(0xaa000000)

            val a = pointA.toCaliperOffset(size)
            val b = pointB.toCaliperOffset(size)
            drawLine(
                color = caliperColor,
                start = Offset(a.x, 0f),
                end = Offset(a.x, size.height),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
            drawLine(
                color = caliperColor,
                start = Offset(b.x, 0f),
                end = Offset(b.x, size.height),
                strokeWidth = 4f,
                cap = StrokeCap.Round,
            )
            phase14State.levelReference?.let { levelReference ->
                val axisRollRadians = Math.toRadians(levelReference.rollDegrees)
                val horizontalX = cos(axisRollRadians).toFloat()
                val horizontalY = sin(axisRollRadians).toFloat()
                val verticalX = horizontalY
                val verticalY = -horizontalX
                val axisRightEnd = Offset(
                    x = size.width - size.width * 0.045f,
                    y = size.height - size.height * 0.075f,
                )
                val axisHorizontalStart = Offset(
                    x = axisRightEnd.x - horizontalX * size.width * 0.22f,
                    y = axisRightEnd.y - horizontalY * size.width * 0.22f,
                )
                val axisVerticalEnd = Offset(
                    x = axisRightEnd.x + verticalX * size.height * 0.16f,
                    y = axisRightEnd.y + verticalY * size.height * 0.16f,
                )
                drawLine(
                    color = referenceAxisShadow,
                    start = axisHorizontalStart,
                    end = axisRightEnd,
                    strokeWidth = 8f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = referenceAxisShadow,
                    start = axisRightEnd,
                    end = axisVerticalEnd,
                    strokeWidth = 8f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = levelHorizontalAxisColor,
                    start = axisHorizontalStart,
                    end = axisRightEnd,
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
                drawLine(
                    color = levelVerticalAxisColor,
                    start = axisRightEnd,
                    end = axisVerticalEnd,
                    strokeWidth = 4f,
                    cap = StrokeCap.Round,
                )
            }
            if (showControls) {
                val colorPoint = phase14State.colorSamplePoint ?: DEFAULT_COLOR_POINT
                val roi = phase14State.regionOfInterest ?: DEFAULT_ROI
                drawCircle(color = sampleColor, radius = 11f, center = colorPoint.toPreviewOffset(size, sourceDimensions, previewRotationDegrees))
                val roiRect = roi.toPreviewRect(size, sourceDimensions, previewRotationDegrees)
                drawRect(
                    color = roiColor,
                    topLeft = roiRect.topLeft,
                    size = Size(
                        width = roiRect.width,
                        height = roiRect.height,
                    ),
                    style = Stroke(width = 3f),
                )
                phase14State.levelReference?.let { level ->
                    val centerY = size.height / 2f
                    val slope = tan(Math.toRadians(level.rollDegrees)).toFloat()
                    val halfWidth = size.width / 2f
                    drawLine(
                        color = levelColor,
                        start = Offset(0f, centerY - slope * halfWidth),
                        end = Offset(size.width, centerY + slope * halfWidth),
                        strokeWidth = 3f,
                        cap = StrokeCap.Round,
                    )
                }
            }
        }
        if (showControls) {
            val hideAllTools = {
                settingsVisible = false
                controlsVisible = false
            }
            if (settingsVisible) {
                ApplicationSettingsPanel(
                    statusText = state.captureStatus,
                    setupTarget = state.setupAdjustmentTargetLabel,
                    knownDistanceFeetText = state.knownDistanceFeetText,
                    onKnownDistanceChanged = onKnownDistanceChanged,
                    onUseKnownDistance = onUseKnownDistance,
                    onRequestPermission = onRequestPermission,
                    onRefreshModes = onRefreshModes,
                    onCaptureLevel = onCaptureLevel,
                    onUseBallDiameterFallback = onUseBallDiameterFallback,
                    onSelectColorPoint = onSelectColorPoint,
                    onSampleColor = onSampleColor,
                    onSelectRoi = onSelectRoi,
                    onPickImport = onPickImport,
                    onStartVisualEstimate = onStartVisualEstimate,
                    onRecalibrateEstimate = onRecalibrateEstimate,
                    onStartBurst = onStartBurst,
                    onStopBurst = onStopBurst,
                    onClose = { settingsVisible = false },
                    onHide = hideAllTools,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            } else {
                BottomToolDrawer(
                    statusText = state.captureStatus,
                    setupTarget = state.setupAdjustmentTargetLabel,
                    fineLineDrag = fineLineDrag,
                    onToggleFineLineDrag = { fineLineDrag = !fineLineDrag },
                    onSelectCaliperA = onSelectCaliperA,
                    onSelectCaliperB = onSelectCaliperB,
                    onVoiceRecord = onVoiceRecord,
                    onOpenSettings = { settingsVisible = true },
                    onHide = hideAllTools,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
        } else if (!captureActive) {
            ToolsHandle(
                onClick = { controlsVisible = true },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
        if (captureActive && !showResultOverlay) {
            CaptureStatusOverlay(
                statusText = state.captureStatus,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        if (showResultOverlay) {
            ResultOverlay(
                lines = resultOverlayLines,
                onClear = { dismissedResultOverlayToken = resultOverlayToken },
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun CaptureStatusOverlay(
    statusText: String,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .widthIn(min = 260.dp)
            .background(Color(0xAA000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = statusText,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Black,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun buildResultOverlayLines(lines: List<String>): List<String> {
    val speedLine = lines.firstOrNull { it.startsWith("speed-estimate ") || it.startsWith("speed ") } ?: return emptyList()
    val distanceLine = lines.firstOrNull { it.startsWith("distance-estimate ") || it.startsWith("trajectory ") }
    return buildList {
        parseResultValue(speedLine, "mph")?.let { add("VELOCITY ${it} MPH") }
        parseResultValue(speedLine, "angleDeg")?.let { add("ANGLE ${it} DEG") }
        parseResultValue(distanceLine, "carryFt")?.let { add("DISTANCE ${it} FT") }
    }.takeIf { it.isNotEmpty() } ?: emptyList()
}

private fun isSuccessfulResultStatus(status: String): Boolean {
    val normalized = status.lowercase()
    return normalized == "visual estimate complete" ||
        normalized == "import estimate complete" ||
        normalized == "recorded estimate complete"
}

@Composable
private fun ResultOverlay(
    lines: List<String>,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black)
            .padding(horizontal = 36.dp, vertical = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        lines.forEach { line ->
            Text(
                text = line,
                color = Color.White,
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Black,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Box(
            modifier = Modifier
                .padding(top = 28.dp)
                .widthIn(min = 180.dp)
                .height(48.dp)
                .background(Color.White, RoundedCornerShape(6.dp))
                .clickable(onClick = onClear),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = "CLEAR",
                color = Color.Black,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

private fun parseResultValue(line: String?, key: String): String? {
    if (line == null) return null
    val token = line
        .split(" ")
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter("=")
        ?.takeIf { it.isNotBlank() && it != "na" }
    return token
}

internal fun nextCaliperFraction(
    current: Float,
    dragDeltaX: Float,
    totalWidthPx: Float,
    fineLineDrag: Boolean,
): Float {
    if (totalWidthPx <= 0f) return current.coerceIn(0f, 1f)
    val scale = if (fineLineDrag) FINE_DRAG_SCALE else 1f
    return (current + dragDeltaX / totalWidthPx * scale).coerceIn(0f, 1f)
}

@Composable
private fun ToolsHandle(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .padding(bottom = 10.dp)
            .width(116.dp)
            .height(34.dp)
            .background(Color(0x55000000), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = "Tools", color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BottomToolDrawer(
    statusText: String,
    setupTarget: String,
    fineLineDrag: Boolean,
    onToggleFineLineDrag: () -> Unit,
    onSelectCaliperA: () -> Unit,
    onSelectCaliperB: () -> Unit,
    onVoiceRecord: () -> Unit,
    onOpenSettings: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x66000000))
            .padding(start = 8.dp, top = 6.dp, end = 88.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusStrip(statusText = statusText, setupTarget = setupTarget)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OverlayButton("A", onSelectCaliperA, Modifier.weight(1f))
            OverlayButton("B", onSelectCaliperB, Modifier.weight(1f))
            OverlayButton(if (fineLineDrag) "Fine" else "Coarse", onToggleFineLineDrag, Modifier.weight(1f))
            OverlayButton("Voice", onVoiceRecord, Modifier.weight(1f))
            OverlayButton("Setup", onOpenSettings, Modifier.weight(1f))
            OverlayButton("Hide", onHide, Modifier.weight(1f))
        }
    }
}

@Composable
private fun ApplicationSettingsPanel(
    statusText: String,
    setupTarget: String,
    knownDistanceFeetText: String,
    onKnownDistanceChanged: (String) -> Unit,
    onUseKnownDistance: () -> Unit,
    onRequestPermission: () -> Unit,
    onRefreshModes: () -> Unit,
    onCaptureLevel: () -> Unit,
    onUseBallDiameterFallback: () -> Unit,
    onSelectColorPoint: () -> Unit,
    onSampleColor: () -> Unit,
    onSelectRoi: () -> Unit,
    onPickImport: () -> Unit,
    onStartVisualEstimate: () -> Unit,
    onRecalibrateEstimate: () -> Unit,
    onStartBurst: () -> Unit,
    onStopBurst: () -> Unit,
    onClose: () -> Unit,
    onHide: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0x77000000))
            .padding(start = 8.dp, top = 6.dp, end = 88.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusStrip(statusText = statusText, setupTarget = setupTarget)
        DistanceInputOverlay(
            value = knownDistanceFeetText,
            onValueChange = onKnownDistanceChanged,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OverlayButton("Apply Distance", onUseKnownDistance, Modifier.weight(1f))
            OverlayButton("Permission", onRequestPermission, Modifier.weight(1f))
            OverlayButton("Modes", onRefreshModes, Modifier.weight(1f))
            OverlayButton("Level", onCaptureLevel, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OverlayButton("Ball", onUseBallDiameterFallback, Modifier.weight(1f))
            OverlayButton("Color", onSelectColorPoint, Modifier.weight(1f))
            OverlayButton("Sample", onSampleColor, Modifier.weight(1f))
            OverlayButton("ROI", onSelectRoi, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
            OverlayButton("Import", onPickImport, Modifier.weight(1f))
            OverlayButton("Est 120", onStartVisualEstimate, Modifier.weight(1f))
            OverlayButton("Recal", onRecalibrateEstimate, Modifier.weight(1f))
            OverlayButton("Record 120", onStartBurst, Modifier.weight(1f))
            OverlayButton("Stop", onStopBurst, Modifier.weight(1f))
            OverlayButton("Close", onClose, Modifier.weight(1f))
            OverlayButton("Hide", onHide, Modifier.weight(1f))
        }
    }
}

@Composable
private fun StatusStrip(
    statusText: String,
    setupTarget: String,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(Color(0x99000000), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "Status: $statusText",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = "Target: $setupTarget",
            color = Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun OverlayButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .height(28.dp)
            .background(Color(0x99207982), RoundedCornerShape(6.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun DistanceInputOverlay(
    value: String,
    onValueChange: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(Color(0x660b0f10), RoundedCornerShape(5.dp))
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(text = "Distance ft", color = Color.White, style = MaterialTheme.typography.labelSmall)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.White),
            modifier = Modifier.weight(1f),
        )
    }
}

@Preview
@Composable
private fun SpeedBallAppPreview() {
    SpeedBallApp()
}

private fun NormalizedFramePoint.toOffset(size: Size): Offset =
    Offset(x = (x * size.width).toFloat(), y = (y * size.height).toFloat())

private fun NormalizedFramePoint.toCaliperOffset(size: Size): Offset =
    Offset(x = (x.coerceIn(0.0, 1.0) * size.width).toFloat(), y = size.height / 2f)

private fun NormalizedFramePoint.toPreviewOffset(
    size: Size,
    sourceDimensions: FrameDimensions?,
    previewRotationDegrees: Int,
): Offset {
    val viewWidth = size.width.toInt()
    val viewHeight = size.height.toInt()
    if (sourceDimensions == null || viewWidth <= 0 || viewHeight <= 0) return toOffset(size)
    val point = PreviewFrameTransform(
        view = FrameDimensions(viewWidth, viewHeight),
        source = sourceDimensions,
        scaleMode = PreviewScaleMode.FitCenter,
        sourceToViewRotationDegrees = previewRotationDegrees,
    ).normalizedToViewPoint(this)
    return point?.let { Offset(it.xPx.toFloat(), it.yPx.toFloat()) } ?: toOffset(size)
}

private data class PreviewRect(val topLeft: Offset, val width: Float, val height: Float)

private fun NormalizedFrameRect.toPreviewRect(
    size: Size,
    sourceDimensions: FrameDimensions?,
    previewRotationDegrees: Int,
): PreviewRect {
    val corners = listOf(
        NormalizedFramePoint(left, top),
        NormalizedFramePoint(right, top),
        NormalizedFramePoint(right, bottom),
        NormalizedFramePoint(left, bottom),
    ).map { it.toPreviewOffset(size, sourceDimensions, previewRotationDegrees) }
    val minX = corners.minOf { it.x }
    val maxX = corners.maxOf { it.x }
    val minY = corners.minOf { it.y }
    val maxY = corners.maxOf { it.y }
    return PreviewRect(Offset(minX, minY), maxX - minX, maxY - minY)
}

private fun TextureView.applyCameraPreviewTransform(
    bufferWidth: Int,
    bufferHeight: Int,
    previewRotationDegrees: Int,
) {
    if (width <= 0 || height <= 0 || bufferWidth <= 0 || bufferHeight <= 0) return
    val normalizedRotation = ((previewRotationDegrees % 360) + 360) % 360
    val viewRect = RectF(0f, 0f, width.toFloat(), height.toFloat())
    val bufferRect = if (normalizedRotation == 90 || normalizedRotation == 270) {
        RectF(0f, 0f, bufferHeight.toFloat(), bufferWidth.toFloat())
    } else {
        RectF(0f, 0f, bufferWidth.toFloat(), bufferHeight.toFloat())
    }
    val centerX = viewRect.centerX()
    val centerY = viewRect.centerY()
    bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY())
    val matrix = Matrix().apply {
        setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL)
        if (normalizedRotation == 90 || normalizedRotation == 270) {
            val scale = maxOf(
                height.toFloat() / bufferHeight.toFloat(),
                width.toFloat() / bufferWidth.toFloat(),
            )
            postScale(scale, scale, centerX, centerY)
            postRotate((normalizedRotation - 180).toFloat(), centerX, centerY)
        } else if (normalizedRotation == 180) {
            postRotate(180f, centerX, centerY)
        }
    }
    setTransform(matrix)
}

private val DEFAULT_CALIPER_A = NormalizedFramePoint(0.20, 0.50)
private val DEFAULT_CALIPER_B = NormalizedFramePoint(0.80, 0.50)
private val DEFAULT_COLOR_POINT = NormalizedFramePoint(0.50, 0.50)
private val DEFAULT_ROI = NormalizedFrameRect(0.10, 0.10, 0.90, 0.90)
private const val FINE_DRAG_SCALE = 0.05f
