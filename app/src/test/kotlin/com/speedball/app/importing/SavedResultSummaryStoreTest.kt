package com.speedball.app.importing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SavedResultSummaryStoreTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun storeRoundTripsRedactedSummaries() {
        val store = SavedResultSummaryStore(File(tempDir, "results.properties"))
        val summary = summary(speed = 71.2, assumptions = listOf("Estimate only."))

        assertInstanceOf(ImportValidationResult.Success::class.java, store.save(listOf(summary)))
        val loaded = assertInstanceOf(ImportValidationResult.Success::class.java, store.load()).value as SavedResultHistory
        val text = loaded.list().joinToString("\n")

        assertEquals(listOf("result-1"), loaded.list().map { it.id })
        assertTrue(text.contains("IMPORT_ESTIMATE"))
        assertFalse(text.contains("content://"))
        assertFalse(text.contains("/sdcard"))
        assertFalse(text.contains("pixel", ignoreCase = true))
    }

    @Test
    fun corruptStoreFailsLoudWithoutSynthesizingHistory() {
        val file = File(tempDir, "results.properties")
        file.writeText("count=1\nresult.0.id=broken\n")

        val loaded = SavedResultSummaryStore(file).load()

        assertInstanceOf(ImportValidationResult.NoRead::class.java, loaded)
    }

    private fun summary(
        speed: Double? = null,
        assumptions: List<String> = emptyList(),
    ): SavedResultSummary =
        SavedResultSummary(
            id = "result-1",
            createdAtEpochMillis = 1_000L,
            sourceKind = ImportResultSourceKind.IMPORT_ESTIMATE,
            evidence = ImportEvidenceSummary(
                sourceKind = ImportResultSourceKind.IMPORT_ESTIMATE,
                timingBasis = ImportTimingBasis.CONTAINER_PRESENTATION_TIMESTAMPS,
                frameCount = 4,
                detectionCount = 4,
                assumptions = assumptions,
            ),
            speedMilesPerHour = speed,
            launchAngleDegrees = null,
            noReadReason = null,
        )
}
