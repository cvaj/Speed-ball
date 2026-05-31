package com.speedball.app.measurement

/**
 * Production workflow state for calibration/color/source readiness.
 *
 * This reducer is deliberately not a measurement calculator. It never stores
 * workflow-local speed, angle, trajectory, or success fields. The only result
 * carrier is [MeasurementRunOutcome], and a successful result can enter only as
 * an already-produced outcome from the gated measurement pipeline.
 */
data class MeasurementWorkflowState(
    val calibration: WorkflowPrerequisite = WorkflowPrerequisite.Missing,
    val colorSample: WorkflowPrerequisite = WorkflowPrerequisite.Missing,
    val sourceProof: WorkflowSourceProofState = WorkflowSourceProofState.Unproven,
    val capture: WorkflowCaptureState = WorkflowCaptureState.Idle,
    val result: MeasurementRunOutcome = sourceUnprovenNoRead(),
) {
    fun reduce(event: MeasurementWorkflowEvent): MeasurementWorkflowState =
        when (event) {
            MeasurementWorkflowEvent.CalibrationSelected ->
                copy(calibration = WorkflowPrerequisite.Ready)

            MeasurementWorkflowEvent.CalibrationCleared ->
                copy(calibration = WorkflowPrerequisite.Missing, capture = WorkflowCaptureState.Idle, result = calibrationMissingNoRead())

            MeasurementWorkflowEvent.ColorSampleSelected ->
                copy(colorSample = WorkflowPrerequisite.Ready)

            MeasurementWorkflowEvent.ColorSampleCleared ->
                copy(colorSample = WorkflowPrerequisite.Missing, capture = WorkflowCaptureState.Idle, result = colorMissingNoRead())

            MeasurementWorkflowEvent.BoundSourceProofAvailable ->
                copy(sourceProof = WorkflowSourceProofState.BoundDirectInputAvailable)

            MeasurementWorkflowEvent.SourceProofCleared ->
                copy(sourceProof = WorkflowSourceProofState.Unproven, capture = WorkflowCaptureState.Idle, result = sourceUnprovenNoRead())

            MeasurementWorkflowEvent.ArmCapture ->
                if (isReadyForBoundCapture()) {
                    copy(capture = WorkflowCaptureState.Armed)
                } else {
                    copy(capture = WorkflowCaptureState.NotReady, result = firstMissingPreconditionNoRead())
                }

            MeasurementWorkflowEvent.StartCapture ->
                if (isReadyForBoundCapture()) {
                    copy(capture = WorkflowCaptureState.Running)
                } else {
                    copy(capture = WorkflowCaptureState.NotReady, result = firstMissingPreconditionNoRead())
                }

            MeasurementWorkflowEvent.DeveloperProofDiagnosticReceived ->
                this

            is MeasurementWorkflowEvent.PipelineMeasurementCompleted ->
                copy(capture = WorkflowCaptureState.Complete, result = event.outcome)
        }

    private fun isReadyForBoundCapture(): Boolean =
        calibration == WorkflowPrerequisite.Ready &&
            colorSample == WorkflowPrerequisite.Ready &&
            sourceProof == WorkflowSourceProofState.BoundDirectInputAvailable

    private fun firstMissingPreconditionNoRead(): MeasurementRunOutcome.NoRead =
        when {
            calibration == WorkflowPrerequisite.Missing -> calibrationMissingNoRead()
            colorSample == WorkflowPrerequisite.Missing -> colorMissingNoRead()
            sourceProof == WorkflowSourceProofState.Unproven -> sourceUnprovenNoRead()
            else -> sourceUnprovenNoRead()
        }
}

enum class WorkflowPrerequisite {
    Missing,
    Ready,
}

enum class WorkflowSourceProofState {
    Unproven,
    BoundDirectInputAvailable,
}

enum class WorkflowCaptureState {
    Idle,
    NotReady,
    Armed,
    Running,
    Complete,
}

sealed interface MeasurementWorkflowEvent {
    data object CalibrationSelected : MeasurementWorkflowEvent
    data object CalibrationCleared : MeasurementWorkflowEvent
    data object ColorSampleSelected : MeasurementWorkflowEvent
    data object ColorSampleCleared : MeasurementWorkflowEvent
    data object BoundSourceProofAvailable : MeasurementWorkflowEvent
    data object SourceProofCleared : MeasurementWorkflowEvent
    data object ArmCapture : MeasurementWorkflowEvent
    data object StartCapture : MeasurementWorkflowEvent
    data object DeveloperProofDiagnosticReceived : MeasurementWorkflowEvent
    data class PipelineMeasurementCompleted(val outcome: MeasurementRunOutcome) : MeasurementWorkflowEvent
}

private fun calibrationMissingNoRead(): MeasurementRunOutcome.NoRead =
    MeasurementRunOutcome.NoRead(
        reason = MeasurementRunFailure.BAD_CALIBRATION,
        message = "Distance calibration is required before measurement.",
    )

private fun colorMissingNoRead(): MeasurementRunOutcome.NoRead =
    MeasurementRunOutcome.NoRead(
        reason = MeasurementRunFailure.DETECTION_FAILED,
        message = "Ball color sample is required before measurement.",
    )

private fun sourceUnprovenNoRead(): MeasurementRunOutcome.NoRead =
    MeasurementRunOutcome.NoRead(
        reason = MeasurementRunFailure.UNPROVEN_TIMING,
        message = "No production frame source has proven image/timestamp pairing.",
    )
