package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreviewFrameOutcomeTest {
    @Test
    fun failureTaxonomyIsPinned() {
        assertEquals(
            listOf(
                PreviewFrameFailure.CAMERA_PERMISSION_DENIED,
                PreviewFrameFailure.NO_BACK_CAMERA,
                PreviewFrameFailure.UNSUPPORTED_MODE,
                PreviewFrameFailure.CAPTURE_BUSY,
                PreviewFrameFailure.CAMERA_OPEN_FAILED,
                PreviewFrameFailure.CAMERA_DEVICE_DISCONNECTED,
                PreviewFrameFailure.CAMERA_DEVICE_ERROR,
                PreviewFrameFailure.SESSION_CONFIGURATION_FAILED,
                PreviewFrameFailure.SURFACE_CONFIGURATION_REJECTED,
                PreviewFrameFailure.GL_SETUP_FAILED,
                PreviewFrameFailure.FRAME_TIMEOUT,
                PreviewFrameFailure.MISSING_PREVIEW_TIMESTAMPS,
                PreviewFrameFailure.MISSING_SENSOR_TIMESTAMPS,
                PreviewFrameFailure.DUPLICATE_PREVIEW_TIMESTAMPS,
                PreviewFrameFailure.PREVIEW_TIMESTAMPS_NON_MONOTONIC,
                PreviewFrameFailure.PREVIEW_TIMESTAMP_NEAR_DUPLICATE,
                PreviewFrameFailure.PREVIEW_CADENCE_MISMATCH,
                PreviewFrameFailure.PREVIEW_DROPPED_FRAME_GAP,
                PreviewFrameFailure.SENSOR_MEMBERSHIP_UNAVAILABLE,
                PreviewFrameFailure.FRAME_SENSOR_COUNT_MISMATCH,
                PreviewFrameFailure.PREVIEW_UNDERCOUNT_COALESCING,
                PreviewFrameFailure.NONZERO_OFFSET_REQUIRES_REVIEW,
                PreviewFrameFailure.AMBIGUOUS_OFFSET,
                PreviewFrameFailure.LATE_CALLBACK_AFTER_TEARDOWN,
                PreviewFrameFailure.RESOURCE_RELEASE_FAILED,
            ),
            PreviewFrameFailure.entries,
        )
    }

    @Test
    fun failureCarriesOnlyReasonMessageAndDiagnostics() {
        val failure = PreviewFrameOutcome.Failure(
            reason = PreviewFrameFailure.SURFACE_CONFIGURATION_REJECTED,
            message = "Preview surface rejected.",
            diagnostics = diagnostics(),
        )
        val fields = failure::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }

        assertEquals(listOf("reason", "message", "diagnostics"), fields)
        assertInstanceOf(PreviewFrameOutcome.Failure::class.java, failure)
        assertNoMeasurementPayloadNames(fields + failure.message + failure.diagnostics!!::class.java.declaredFields.map { it.name })
    }

    @Test
    fun successPairsAreTimestampOnly() {
        val success = PreviewFrameOutcome.Success(
            pairs = listOf(
                PreviewFrameTimestampPair(
                    frameIndex = 0,
                    previewTimestampNanos = 1_000_000_000L,
                    sensorTimestampNanos = 1_000_000_000L,
                    relativeTimestampSeconds = 0.0,
                ),
            ),
            diagnostics = diagnostics(verdict = PreviewFramePairingVerdict.EXACT_VALUE_MEMBERSHIP),
        )

        assertEquals(
            listOf("frameIndex", "previewTimestampNanos", "sensorTimestampNanos", "relativeTimestampSeconds"),
            success.pairs.single()::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") },
        )
        assertNoMeasurementPayloadNames(success::class.java.declaredFields.map { it.name })
    }

    @Test
    fun diagnosticFormatterUsesDisplayNameOnlyAndChunksBoundedArrays() {
        val outcome = PreviewFrameOutcome.Failure(
            reason = PreviewFrameFailure.PREVIEW_UNDERCOUNT_COALESCING,
            message = "Preview consumed fewer frames than sensor callbacks.",
            diagnostics = diagnostics(
                previewGapNanos = listOf(8_333_333L, 16_666_666L, 8_333_333L),
                sensorGapNanos = listOf(8_333_333L, 8_333_333L),
                offsetNanos = listOf(0L, 1L, 2L),
                coalescingEvidence = true,
            ),
        )
        val lines = previewFrameDiagnosticLogLines(
            outcome = outcome,
            modeLabel = "/storage/emulated/0/private/1280x720 @ 120 fps",
            chunkSize = 2,
        )
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("mode=1280x720_@_120_fps"))
        assertTrue(joined.contains("verdict=FAILURE reason=PREVIEW_UNDERCOUNT_COALESCING"))
        assertTrue(joined.contains("rawPreviewTs=4"))
        assertTrue(joined.contains("positiveSensorTs=5"))
        assertTrue(joined.contains("coalescing=true"))
        assertTrue(joined.contains("PREVIEW_SURFACE_GAPS_NS mode=1280x720_@_120_fps chunk=2 count=3 values=[8333333]"))
        assertTrue(joined.contains("PREVIEW_TIMESTAMP_OFFSETS_NS mode=1280x720_@_120_fps chunk=2 count=3 values=[2]"))
        assertFalse(joined.contains("/storage/"))
        assertFalse(joined.contains("content://"))
        assertNoMeasurementPayloadNames(lines)
    }

    @Test
    fun cancelledFormatsWithoutDiagnostics() {
        val lines = previewFrameDiagnosticLogLines(PreviewFrameOutcome.Cancelled, modeLabel = "", chunkSize = 3)
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("verdict=CANCELLED"))
        assertTrue(joined.contains("mode=unknown"))
        assertTrue(joined.contains("PREVIEW_SENSOR_GAPS_NS mode=unknown chunk=0 count=0 values=[]"))
    }

    private fun diagnostics(
        previewGapNanos: List<Long> = emptyList(),
        sensorGapNanos: List<Long> = emptyList(),
        offsetNanos: List<Long> = emptyList(),
        coalescingEvidence: Boolean = false,
        verdict: PreviewFramePairingVerdict = PreviewFramePairingVerdict.REJECTED,
    ): PreviewFrameDiagnostics =
        PreviewFrameDiagnostics(
            rawPreviewTimestampCount = 4,
            positivePreviewTimestampCount = 4,
            uniquePreviewTimestampCount = 4,
            rawSensorTimestampCount = 6,
            positiveSensorTimestampCount = 5,
            uniqueSensorTimestampCount = 5,
            previewGapNanos = previewGapNanos,
            sensorGapNanos = sensorGapNanos,
            medianPreviewGapMillis = 8.33,
            maximumPreviewGapMillis = 16.66,
            medianSensorGapMillis = 8.33,
            maximumSensorGapMillis = 8.33,
            expectedGapMillis = 8.333,
            droppedFrameGapThresholdMillis = 12.5,
            exactMatchCount = 4,
            sensorMembershipCount = 4,
            unmatchedLeadingPreviewCount = 0,
            unmatchedTrailingPreviewCount = 0,
            coalescingEvidence = coalescingEvidence,
            offsetNanos = offsetNanos,
            verdict = verdict,
        )

    private fun assertNoMeasurementPayloadNames(values: List<String>) {
        val tokens = values
            .flatMap { value -> value.lowercase().split(Regex("[^a-z0-9]+")) }
            .filter { it.isNotBlank() }
        listOf("mph", "angle", "trajectory", "bitmap", "media", "uri", "detection").forEach { forbidden ->
            assertFalse(tokens.contains(forbidden), "Preview proof payload leaked '$forbidden': $tokens")
        }
    }
}
