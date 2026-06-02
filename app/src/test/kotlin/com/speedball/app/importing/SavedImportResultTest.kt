package com.speedball.app.importing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SavedImportResultTest {
    @Test
    fun historyStoresOrdersDeletesAndReleasesGrant() {
        val history = SavedResultHistory()
        history.save(summary(id = "old", createdAt = 1_000L))
        history.save(summary(id = "new", createdAt = 2_000L))

        assertEquals(listOf("new", "old"), history.list().map { it.id })
        val grant = history.delete("new", ImportContentGrantState(persistedReadGrantActive = true))

        assertFalse(grant.persistedReadGrantActive)
        assertEquals(listOf("old"), history.list().map { it.id })
    }

    @Test
    fun clearRemovesHistoryAndReleasesGrant() {
        val history = SavedResultHistory(listOf(summary(id = "old")))

        val grant = history.clear(ImportContentGrantState(persistedReadGrantActive = true))

        assertTrue(history.list().isEmpty())
        assertFalse(grant.persistedReadGrantActive)
    }

    @Test
    fun noReadSummaryCannotCarryResultValues() {
        assertThrows(IllegalArgumentException::class.java) {
            summary(
                id = "bad",
                noReadReason = "BAD_TIMESTAMPS",
                speed = 75.0,
            )
        }
    }

    @Test
    fun exportEvidenceIsUsefulAndRedacted() {
        val text = ImportEvidenceExporter.format(summary(id = "result-1", speed = 72.4))

        assertTrue(text.contains("source=IMPORT_ESTIMATE"))
        assertTrue(text.contains("speedMph=72.4"))
        assertFalse(text.contains("content://"))
        assertFalse(text.contains("/sdcard"))
        assertFalse(text.contains("pixel", ignoreCase = true))
    }

    @Test
    fun recordedExportEvidenceUsesRecordedSourceAndTiming() {
        val text = ImportEvidenceExporter.format(
            summary(
                id = "result-1",
                speed = 72.4,
                sourceKind = ImportResultSourceKind.RECORDED_ESTIMATE,
                timingBasis = ImportTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL,
                assumptions = listOf(ImportTimingReconciler.RECORDED_CAPTURE_FRAME_DROP_ASSUMPTION),
            ),
        )

        assertTrue(text.contains("source=RECORDED_ESTIMATE"))
        assertTrue(text.contains("timing=RECORDED_CAPTURE_FRAME_INTERVAL"))
        assertTrue(text.contains("MediaRecorder"))
        assertFalse(text.contains("content://"))
        assertFalse(text.contains("/sdcard"))
    }

    private fun summary(
        id: String,
        createdAt: Long = 1_000L,
        noReadReason: String? = null,
        speed: Double? = null,
        sourceKind: ImportResultSourceKind = ImportResultSourceKind.IMPORT_ESTIMATE,
        timingBasis: ImportTimingBasis = ImportTimingBasis.CONTAINER_PRESENTATION_TIMESTAMPS,
        assumptions: List<String> = listOf("Imported estimate only."),
    ): SavedResultSummary =
        SavedResultSummary(
            id = id,
            createdAtEpochMillis = createdAt,
            sourceKind = sourceKind,
            evidence = ImportEvidenceSummary(
                sourceKind = sourceKind,
                timingBasis = timingBasis,
                frameCount = 4,
                detectionCount = 4,
                assumptions = assumptions,
            ),
            speedMilesPerHour = speed,
            launchAngleDegrees = null,
            noReadReason = noReadReason,
        )
}
