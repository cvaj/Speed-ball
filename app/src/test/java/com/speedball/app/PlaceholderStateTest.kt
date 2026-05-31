package com.speedball.app

import com.speedball.app.ui.PlaceholderStatus
import com.speedball.app.ui.speedBallPlaceholderState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PlaceholderStateTest {
    @Test
    fun exposesEveryWorkflowSectionAsPendingOrUnavailable() {
        val state = speedBallPlaceholderState()

        assertEquals("Speed-ball", state.title)
        assertEquals(
            listOf("Mode", "Calibrate", "Sample Color", "Capture", "Import", "Results"),
            state.sections.map { it.title },
        )
        assertTrue(state.sections.all { it.status == PlaceholderStatus.Pending || it.status == PlaceholderStatus.Unavailable })
    }

    @Test
    fun doesNotExposeFakeMeasurementValues() {
        val visibleText = speedBallPlaceholderState().visibleText.joinToString(separator = " ")
        val forbiddenPatterns = listOf(
            Regex("""\b\d+(\.\d+)?\s*mph\b""", RegexOption.IGNORE_CASE),
            Regex("""\b\d+(\.\d+)?\s*fps\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(720p|1080p|240|120)\b""", RegexOption.IGNORE_CASE),
            Regex("""\b(result|carry|apex|trajectory)\s*[:=]\s*\d""", RegexOption.IGNORE_CASE),
        )

        forbiddenPatterns.forEach { pattern ->
            assertFalse(pattern.containsMatchIn(visibleText), "Unexpected fake value matching $pattern in: $visibleText")
        }
    }
}
