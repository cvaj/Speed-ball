package com.speedball.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/** Renders the Phase 1 placeholder shell from a Compose-free state model. */
@Composable
fun SpeedBallApp(
    state: SpeedBallShellState = speedBallPlaceholderState(),
    onPreviewSurface: (Surface?) -> Unit = {},
    onRequestPermission: () -> Unit = {},
    onRefreshModes: () -> Unit = {},
    onStartBurst: () -> Unit = {},
    onStopBurst: () -> Unit = {},
) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp),
                contentPadding = PaddingValues(vertical = 20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                item {
                    Column {
                        Text(
                            text = state.title,
                            style = MaterialTheme.typography.headlineMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = "Capture foundation diagnostics. Measurement results remain pending.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
                item {
                    PreviewSurface(onPreviewSurface = onPreviewSurface)
                }
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(text = "Camera: ${state.cameraPermission}", style = MaterialTheme.typography.bodyMedium)
                        Text(text = "Capture: ${state.captureStatus}", style = MaterialTheme.typography.bodyMedium)
                        state.selectedModeLine?.let {
                            Text(text = "Selected: $it", style = MaterialTheme.typography.bodyMedium)
                        }
                        state.failureLine?.let {
                            Text(text = it, style = MaterialTheme.typography.bodyMedium)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = onRequestPermission) { Text("Permission") }
                            Button(onClick = onRefreshModes) { Text("Modes") }
                            Button(onClick = onStartBurst) { Text("Start 120") }
                            Button(onClick = onStopBurst) { Text("Stop") }
                        }
                    }
                }
                if (state.modeLines.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "High-speed modes",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            state.modeLines.forEach { line ->
                                Text(text = line, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (state.resultLines.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Results",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            state.resultLines.forEach { line ->
                                Text(text = line, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (state.diagnosticLines.isNotEmpty()) {
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                text = "Last burst diagnostics",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Medium,
                            )
                            state.diagnosticLines.forEach { line ->
                                Text(text = line, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                items(state.sections) { section ->
                    WorkflowSectionRow(section)
                }
            }
        }
    }
}

@Composable
private fun WorkflowSectionRow(section: WorkflowSection) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = section.status.name,
                style = MaterialTheme.typography.labelLarge,
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = section.detail,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun PreviewSurface(onPreviewSurface: (Surface?) -> Unit) {
    AndroidView(
        modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
        factory = { context ->
            SurfaceView(context).apply {
                holder.setFixedSize(1280, 720)
                holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            onPreviewSurface(holder.surface)
                        }

                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                            onPreviewSurface(holder.surface)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            onPreviewSurface(null)
                        }
                    },
                )
            }
        },
    )
}

@Preview
@Composable
private fun SpeedBallAppPreview() {
    SpeedBallApp()
}
