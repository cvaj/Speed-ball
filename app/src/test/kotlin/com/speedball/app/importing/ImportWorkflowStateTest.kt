package com.speedball.app.importing

import com.speedball.app.measurement.VisualEstimateNoReadReason
import com.speedball.app.measurement.VisualEstimateOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportWorkflowStateTest {
    @Test
    fun retryAndReselectClearStaleImportData() {
        val dirty = ImportWorkflowState(
            stage = ImportWorkflowStage.SUCCESS,
            frames = ImportVideoFrameSequence(emptyList()),
            outcome = VisualEstimateOutcome.NoRead(VisualEstimateNoReadReason.BAD_TIMESTAMPS, "bad"),
        )

        val retried = dirty.reduce(ImportWorkflowEvent.Retry)

        assertEquals(ImportWorkflowStage.IDLE, retried.stage)
        assertNull(retried.frames)
        assertNull(retried.outcome)
    }

    @Test
    fun estimateNoReadCompletionUsesNoReadStage() {
        val state = ImportWorkflowState(stage = ImportWorkflowStage.ESTIMATING).reduce(
            ImportWorkflowEvent.EstimateCompleted(
                VisualEstimateOutcome.NoRead(VisualEstimateNoReadReason.BAD_TIMESTAMPS, "No timing."),
            ),
        )

        assertEquals(ImportWorkflowStage.NO_READ, state.stage)
        assertTrue(state.outcome is VisualEstimateOutcome.NoRead)
    }

    @Test
    fun cancelAndDeleteSessionClearSensitiveState() {
        val state = ImportWorkflowState(
            stage = ImportWorkflowStage.READY,
            frames = ImportVideoFrameSequence(emptyList()),
        )

        assertEquals(ImportWorkflowStage.CANCELLED, state.reduce(ImportWorkflowEvent.Cancel).stage)
        assertNull(state.reduce(ImportWorkflowEvent.DeleteSession).frames)
    }
}
