package com.speedball.app.capture

import com.speedball.app.decode.buildOrderedTimestampDiagnostics
import com.speedball.app.decode.buildTimestampDiagnostics
import com.speedball.app.decode.normalizeSensorTimestamps

/**
 * Fail-loud reasons for the decoder-free preview timestamp proof path.
 *
 * These reasons are diagnostic only. None of them may be converted into a
 * speed, trajectory, or measurement result.
 */
enum class PreviewFrameFailure {
    CAMERA_PERMISSION_DENIED,
    NO_BACK_CAMERA,
    UNSUPPORTED_MODE,
    CAPTURE_BUSY,
    CAMERA_OPEN_FAILED,
    CAMERA_DEVICE_DISCONNECTED,
    CAMERA_DEVICE_ERROR,
    SESSION_CONFIGURATION_FAILED,
    SURFACE_CONFIGURATION_REJECTED,
    GL_SETUP_FAILED,
    FRAME_TIMEOUT,
    MISSING_PREVIEW_TIMESTAMPS,
    MISSING_SENSOR_TIMESTAMPS,
    DUPLICATE_PREVIEW_TIMESTAMPS,
    PREVIEW_TIMESTAMPS_NON_MONOTONIC,
    PREVIEW_TIMESTAMP_NEAR_DUPLICATE,
    PREVIEW_CADENCE_MISMATCH,
    PREVIEW_DROPPED_FRAME_GAP,
    SENSOR_MEMBERSHIP_UNAVAILABLE,
    FRAME_SENSOR_COUNT_MISMATCH,
    PREVIEW_UNDERCOUNT_COALESCING,
    NONZERO_OFFSET_REQUIRES_REVIEW,
    AMBIGUOUS_OFFSET,
    LATE_CALLBACK_AFTER_TEARDOWN,
    RESOURCE_RELEASE_FAILED,
}

/** Pairing verdict for consumed preview timestamps. Not a measurement result. */
enum class PreviewFramePairingVerdict {
    NOT_EVALUATED,
    EXACT_VALUE_MEMBERSHIP,
    REJECTED,
}

/** One consumed preview frame timestamp matched to sensor evidence. */
data class PreviewFrameTimestampPair(
    val frameIndex: Int,
    val previewTimestampNanos: Long,
    val sensorTimestampNanos: Long,
    val relativeTimestampSeconds: Double,
)

/** Bounded developer diagnostics for preview timestamp proof. */
data class PreviewFrameDiagnostics(
    val rawPreviewTimestampCount: Int,
    val positivePreviewTimestampCount: Int,
    val uniquePreviewTimestampCount: Int,
    val rawSensorTimestampCount: Int,
    val positiveSensorTimestampCount: Int,
    val uniqueSensorTimestampCount: Int,
    val previewGapNanos: List<Long> = emptyList(),
    val sensorGapNanos: List<Long> = emptyList(),
    val medianPreviewGapMillis: Double?,
    val maximumPreviewGapMillis: Double?,
    val medianSensorGapMillis: Double?,
    val maximumSensorGapMillis: Double?,
    val expectedGapMillis: Double,
    val droppedFrameGapThresholdMillis: Double,
    val exactMatchCount: Int,
    val sensorMembershipCount: Int,
    val unmatchedLeadingPreviewCount: Int,
    val unmatchedTrailingPreviewCount: Int,
    val coalescingEvidence: Boolean,
    val offsetNanos: List<Long> = emptyList(),
    val verdict: PreviewFramePairingVerdict,
)

/** Terminal result from the preview timestamp proof path. */
sealed interface PreviewFrameOutcome {
    data class Success(
        val pairs: List<PreviewFrameTimestampPair>,
        val diagnostics: PreviewFrameDiagnostics,
    ) : PreviewFrameOutcome

    data class Failure(
        val reason: PreviewFrameFailure,
        val message: String,
        val diagnostics: PreviewFrameDiagnostics? = null,
    ) : PreviewFrameOutcome

    data object Cancelled : PreviewFrameOutcome
}

fun previewFrameDiagnosticLogLines(
    outcome: PreviewFrameOutcome,
    modeLabel: String,
    chunkSize: Int,
): List<String> {
    require(chunkSize > 0) { "Chunk size must be positive." }
    val mode = modeLabel.sanitizedLogToken()
    val diagnostics = when (outcome) {
        is PreviewFrameOutcome.Success -> outcome.diagnostics
        is PreviewFrameOutcome.Failure -> outcome.diagnostics
        PreviewFrameOutcome.Cancelled -> null
    }
    val verdict = when (outcome) {
        is PreviewFrameOutcome.Success -> "SUCCESS pairs=${outcome.pairs.size}"
        is PreviewFrameOutcome.Failure -> "FAILURE reason=${outcome.reason}"
        PreviewFrameOutcome.Cancelled -> "CANCELLED"
    }
    return buildList {
        add(
            "PREVIEW_FRAME_DIAGNOSTICS mode=$mode verdict=$verdict " +
                "rawPreviewTs=${diagnostics?.rawPreviewTimestampCount ?: "n/a"} " +
                "positivePreviewTs=${diagnostics?.positivePreviewTimestampCount ?: "n/a"} " +
                "uniquePreviewTs=${diagnostics?.uniquePreviewTimestampCount ?: "n/a"} " +
                "rawSensorTs=${diagnostics?.rawSensorTimestampCount ?: "n/a"} " +
                "positiveSensorTs=${diagnostics?.positiveSensorTimestampCount ?: "n/a"} " +
                "uniqueSensorTs=${diagnostics?.uniqueSensorTimestampCount ?: "n/a"} " +
                "previewMedianMs=${diagnostics?.medianPreviewGapMillis ?: "n/a"} " +
                "previewMaxMs=${diagnostics?.maximumPreviewGapMillis ?: "n/a"} " +
                "sensorMedianMs=${diagnostics?.medianSensorGapMillis ?: "n/a"} " +
                "sensorMaxMs=${diagnostics?.maximumSensorGapMillis ?: "n/a"} " +
                "expectedMs=${diagnostics?.expectedGapMillis ?: "n/a"} " +
                "dropThresholdMs=${diagnostics?.droppedFrameGapThresholdMillis ?: "n/a"} " +
                "exactMatches=${diagnostics?.exactMatchCount ?: "n/a"} " +
                "sensorMembership=${diagnostics?.sensorMembershipCount ?: "n/a"} " +
                "unmatchedLeadingPreview=${diagnostics?.unmatchedLeadingPreviewCount ?: "n/a"} " +
                "unmatchedTrailingPreview=${diagnostics?.unmatchedTrailingPreviewCount ?: "n/a"} " +
                "coalescing=${diagnostics?.coalescingEvidence ?: "n/a"} " +
                "pairingVerdict=${diagnostics?.verdict ?: "n/a"}",
        )
        addChunkedLongLines(
            label = "PREVIEW_SENSOR_GAPS_NS",
            mode = mode,
            values = diagnostics?.sensorGapNanos.orEmpty(),
            chunkSize = chunkSize,
        )
        addChunkedLongLines(
            label = "PREVIEW_SURFACE_GAPS_NS",
            mode = mode,
            values = diagnostics?.previewGapNanos.orEmpty(),
            chunkSize = chunkSize,
        )
        addChunkedLongLines(
            label = "PREVIEW_TIMESTAMP_OFFSETS_NS",
            mode = mode,
            values = diagnostics?.offsetNanos.orEmpty(),
            chunkSize = chunkSize,
        )
    }
}

fun buildPreviewFrameDiagnostics(
    rawPreviewTimestampsNanos: List<Long>,
    rawSensorTimestampsNanos: List<Long>,
    fps: Int,
    exactMatchCount: Int,
    sensorMembershipCount: Int,
    unmatchedLeadingPreviewCount: Int,
    unmatchedTrailingPreviewCount: Int,
    coalescingEvidence: Boolean,
    offsetNanos: List<Long>,
    verdict: PreviewFramePairingVerdict,
): PreviewFrameDiagnostics {
    val positivePreview = rawPreviewTimestampsNanos.filter { it > 0L }
    val uniquePreviewInConsumedOrder = positivePreview.distinct()
    val previewDiagnostics = if (uniquePreviewInConsumedOrder.isNotEmpty()) {
        buildOrderedTimestampDiagnostics(uniquePreviewInConsumedOrder, fps)
    } else {
        null
    }
    val sensorDiagnostics = buildTimestampDiagnostics(rawSensorTimestampsNanos, fps)
    val normalizedSensor = normalizeSensorTimestamps(rawSensorTimestampsNanos)
    return PreviewFrameDiagnostics(
        rawPreviewTimestampCount = rawPreviewTimestampsNanos.size,
        positivePreviewTimestampCount = positivePreview.size,
        uniquePreviewTimestampCount = uniquePreviewInConsumedOrder.size,
        rawSensorTimestampCount = rawSensorTimestampsNanos.size,
        positiveSensorTimestampCount = normalizedSensor.positiveCount,
        uniqueSensorTimestampCount = normalizedSensor.uniqueTimestampsNanos.size,
        previewGapNanos = previewDiagnostics?.gapNanos.orEmpty(),
        sensorGapNanos = sensorDiagnostics.gapNanos,
        medianPreviewGapMillis = previewDiagnostics?.medianGapMillis,
        maximumPreviewGapMillis = previewDiagnostics?.maximumGapMillis,
        medianSensorGapMillis = sensorDiagnostics.medianGapMillis,
        maximumSensorGapMillis = sensorDiagnostics.maximumGapMillis,
        expectedGapMillis = sensorDiagnostics.expectedGapMillis,
        droppedFrameGapThresholdMillis = sensorDiagnostics.droppedFrameGapThresholdMillis,
        exactMatchCount = exactMatchCount,
        sensorMembershipCount = sensorMembershipCount,
        unmatchedLeadingPreviewCount = unmatchedLeadingPreviewCount,
        unmatchedTrailingPreviewCount = unmatchedTrailingPreviewCount,
        coalescingEvidence = coalescingEvidence,
        offsetNanos = offsetNanos,
        verdict = verdict,
    )
}

private fun MutableList<String>.addChunkedLongLines(
    label: String,
    mode: String,
    values: List<Long>,
    chunkSize: Int,
) {
    if (values.isEmpty()) {
        add("$label mode=$mode chunk=0 count=0 values=[]")
        return
    }
    values.chunked(chunkSize).forEachIndexed { index, chunk ->
        add("$label mode=$mode chunk=${index + 1} count=${values.size} values=${chunk.joinToString(prefix = "[", postfix = "]")}")
    }
}

private fun String.sanitizedLogToken(): String =
    substringAfterLast('/')
        .substringAfterLast('\\')
        .ifBlank { "unknown" }
        .replace(Regex("\\s+"), "_")
