package com.speedball.app.ui

import com.speedball.app.decode.DecodeOutcome

/** Availability marker for skeleton workflow sections before implementation phases land. */
enum class PlaceholderStatus {
    Pending,
    Unavailable,
}

/** One visible row in the Phase 1 shell workflow list. */
data class WorkflowSection(
    val title: String,
    val status: PlaceholderStatus,
    val detail: String,
)

/** Compose-free state used by JVM tests to guard the no-fake-result contract. */
data class SpeedBallShellState(
    val title: String,
    val sections: List<WorkflowSection>,
    val cameraPermission: String = "Not requested",
    val captureStatus: String = "Idle",
    val modeLines: List<String> = emptyList(),
    val selectedModeLine: String? = null,
    val diagnosticLines: List<String> = emptyList(),
    val failureLine: String? = null,
) {
    /** User-visible strings exposed by the placeholder shell. */
    val visibleText: List<String>
        get() = buildList {
            add(title)
            add(cameraPermission)
            add(captureStatus)
            selectedModeLine?.let(::add)
            addAll(modeLines)
            addAll(diagnosticLines)
            failureLine?.let(::add)
            sections.forEach { section ->
                add(section.title)
                add(section.status.name)
                add(section.detail)
            }
        }
}

/** Builds the only Phase 1 app state: a truthful no-read placeholder shell. */
fun speedBallPlaceholderState(): SpeedBallShellState =
    SpeedBallShellState(
        title = "Speed-ball",
        sections = listOf(
            WorkflowSection("Mode", PlaceholderStatus.Pending, "Camera modes will come from device capability checks."),
            WorkflowSection("Calibrate", PlaceholderStatus.Pending, "Distance setup is not available in this skeleton."),
            WorkflowSection("Sample Color", PlaceholderStatus.Pending, "Color sampling arrives with detection."),
            WorkflowSection("Capture", PlaceholderStatus.Unavailable, "Camera capture is not implemented in this phase."),
            WorkflowSection("Import", PlaceholderStatus.Unavailable, "Video import is not implemented in this phase."),
            WorkflowSection("Results", PlaceholderStatus.Unavailable, "No read until measurement phases exist."),
        ),
    )

fun speedBallCaptureState(
    permissionLabel: String,
    captureStatus: String,
    modeLines: List<String>,
    selectedModeLine: String?,
    diagnosticLines: List<String>,
    failureLine: String?,
): SpeedBallShellState =
    speedBallPlaceholderState().copy(
        cameraPermission = permissionLabel,
        captureStatus = captureStatus,
        modeLines = modeLines,
        selectedModeLine = selectedModeLine,
        diagnosticLines = diagnosticLines,
        failureLine = failureLine,
        sections = listOf(
            WorkflowSection("Mode", PlaceholderStatus.Pending, "Modes are loaded from the device HAL."),
            WorkflowSection("Calibrate", PlaceholderStatus.Pending, "Distance setup is not available in this phase."),
            WorkflowSection("Sample Color", PlaceholderStatus.Pending, "Color sampling arrives with detection."),
            WorkflowSection("Capture", PlaceholderStatus.Pending, "Developer burst diagnostics are available."),
            WorkflowSection("Import", PlaceholderStatus.Unavailable, "Video import is not implemented in this phase."),
            WorkflowSection("Results", PlaceholderStatus.Unavailable, "No read until detection and measurement phases exist."),
        ),
    )

fun decodeOutcomeUiLines(outcome: DecodeOutcome?): List<String> =
    when (outcome) {
        null -> emptyList()
        DecodeOutcome.Cancelled -> listOf("decode=cancelled")
        is DecodeOutcome.Failure -> listOf("decodeFailure=${outcome.reason} message=${outcome.message}")
        is DecodeOutcome.Success -> buildList {
            val diagnostics = outcome.diagnostics
            add("decodeFrames=${diagnostics.decodedFrameCount} uniqueSensorTs=${diagnostics.uniqueSensorTimestampCount} exactCountPass=${diagnostics.exactCountPasses}")
            add(
                "sensorGapMs median=${diagnostics.medianSensorGapMillis.formatOrNa()} max=${diagnostics.maximumSensorGapMillis.formatOrNa()} " +
                    "dropThreshold=${diagnostics.droppedFrameGapThresholdMillis.format(2)}",
            )
            add(
                "ptsGapMs median=${diagnostics.medianPresentationGapMillis.formatOrNa()} max=${diagnostics.maximumPresentationGapMillis.formatOrNa()} " +
                    "clock=${diagnostics.presentationClockAssessment}",
            )
            diagnostics.ptsToSensorOffsetSummary?.let {
                add("ptsSensorOffsetMicros min=${it.minimumOffsetMicros} max=${it.maximumOffsetMicros} spread=${it.spreadMicros}")
            }
            if (diagnostics.sampledFrames.isNotEmpty()) {
                add(
                    "sampledFrames=" + diagnostics.sampledFrames.joinToString { sample ->
                        "${sample.frameIndex}:${sample.width}x${sample.height}@${sample.presentationTimeMicros}us"
                    },
                )
            }
        }
    }

private fun Double?.formatOrNa(): String =
    this?.format(2) ?: "n/a"

private fun Double.format(decimals: Int): String =
    "%.${decimals}f".format(this)
