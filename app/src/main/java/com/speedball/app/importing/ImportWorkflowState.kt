package com.speedball.app.importing

import com.speedball.app.measurement.VisualEstimateOutcome

/** Compose-free import workflow stage. */
enum class ImportWorkflowStage {
    IDLE,
    PICKER_REQUESTED,
    VALIDATING,
    EXTRACTING,
    READY,
    ESTIMATING,
    SUCCESS,
    NO_READ,
    CANCELLED,
}

/** Import workflow state with no raw media identifiers. */
data class ImportWorkflowState(
    val stage: ImportWorkflowStage = ImportWorkflowStage.IDLE,
    val access: ImportContentAccess? = null,
    val metadata: ImportVideoMetadata? = null,
    val frames: ImportVideoFrameSequence? = null,
    val timing: ImportTimingReconciliation? = null,
    val outcome: ImportEstimateOutcome? = null,
    val noReadReason: ImportNoReadReason? = null,
    val message: String? = null,
) {
    fun reduce(event: ImportWorkflowEvent): ImportWorkflowState =
        when (event) {
            ImportWorkflowEvent.RequestPicker -> cleared().copy(stage = ImportWorkflowStage.PICKER_REQUESTED)
            is ImportWorkflowEvent.SelectionValidated -> cleared().copy(
                stage = ImportWorkflowStage.VALIDATING,
                access = event.access,
            )
            is ImportWorkflowEvent.MetadataValidated -> copy(
                stage = ImportWorkflowStage.EXTRACTING,
                metadata = event.metadata,
                noReadReason = null,
                message = null,
            )
            is ImportWorkflowEvent.FramesExtracted -> copy(
                stage = ImportWorkflowStage.READY,
                frames = event.frames,
                timing = event.timing,
                noReadReason = null,
                message = null,
            )
            ImportWorkflowEvent.StartEstimate -> copy(stage = ImportWorkflowStage.ESTIMATING)
            is ImportWorkflowEvent.EstimateCompleted -> copy(
                stage = if (event.outcome is VisualEstimateOutcome.NoRead) ImportWorkflowStage.NO_READ else ImportWorkflowStage.SUCCESS,
                outcome = event.outcome,
                noReadReason = null,
                message = null,
            )
            is ImportWorkflowEvent.ImportFailed -> cleared().copy(
                stage = ImportWorkflowStage.NO_READ,
                noReadReason = event.reason,
                message = event.message,
            )
            ImportWorkflowEvent.Cancel -> cleared().copy(stage = ImportWorkflowStage.CANCELLED)
            ImportWorkflowEvent.Retry -> cleared()
            ImportWorkflowEvent.DeleteSession -> cleared()
        }

    private fun cleared(): ImportWorkflowState =
        ImportWorkflowState()
}

/** Import workflow events. */
sealed interface ImportWorkflowEvent {
    data object RequestPicker : ImportWorkflowEvent
    data class SelectionValidated(val access: ImportContentAccess) : ImportWorkflowEvent
    data class MetadataValidated(val metadata: ImportVideoMetadata) : ImportWorkflowEvent
    data class FramesExtracted(
        val frames: ImportVideoFrameSequence,
        val timing: ImportTimingReconciliation,
    ) : ImportWorkflowEvent
    data object StartEstimate : ImportWorkflowEvent
    data class EstimateCompleted(val outcome: ImportEstimateOutcome) : ImportWorkflowEvent
    data class ImportFailed(val reason: ImportNoReadReason, val message: String) : ImportWorkflowEvent
    data object Cancel : ImportWorkflowEvent
    data object Retry : ImportWorkflowEvent
    data object DeleteSession : ImportWorkflowEvent
}
