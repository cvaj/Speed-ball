package com.speedball.app.capture

const val CAMERA_TIMESTAMP_SOURCE_UNKNOWN_VALUE = 0
const val CAMERA_TIMESTAMP_SOURCE_REALTIME_VALUE = 1

/** Public Camera2 timestamp-source labels reduced to app-owned, testable values. */
enum class CameraTimestampSourceLabel(val logLabel: String) {
    REALTIME("REALTIME"),
    UNKNOWN("UNKNOWN"),
    ABSENT("ABSENT"),
    UNEXPECTED("UNEXPECTED"),
}

/** Bounded report of the back camera's timestamp-source characteristic. */
data class CameraTimestampSourceReport(
    val cameraRole: String,
    val cameraIdClass: String,
    val sourceValue: Int?,
    val sourceLabel: CameraTimestampSourceLabel,
    val sharedClockCandidate: Boolean,
)

/** Terminal result for the developer-only timestamp-source characteristic read. */
sealed interface CameraTimestampSourceReadResult {
    data class Success(val report: CameraTimestampSourceReport) : CameraTimestampSourceReadResult
    data class Failure(val reason: BurstFailure, val message: String) : CameraTimestampSourceReadResult
}

fun mapCameraTimestampSourceValue(sourceValue: Int?): CameraTimestampSourceLabel =
    when (sourceValue) {
        null -> CameraTimestampSourceLabel.ABSENT
        CAMERA_TIMESTAMP_SOURCE_UNKNOWN_VALUE -> CameraTimestampSourceLabel.UNKNOWN
        CAMERA_TIMESTAMP_SOURCE_REALTIME_VALUE -> CameraTimestampSourceLabel.REALTIME
        else -> CameraTimestampSourceLabel.UNEXPECTED
    }

fun buildCameraTimestampSourceReport(
    cameraRole: String,
    cameraId: String?,
    sourceValue: Int?,
): CameraTimestampSourceReport {
    val sourceLabel = mapCameraTimestampSourceValue(sourceValue)
    return CameraTimestampSourceReport(
        cameraRole = cameraRole.ifBlank { "unknown" },
        cameraIdClass = cameraId.toCameraIdClass(),
        sourceValue = sourceValue,
        sourceLabel = sourceLabel,
        sharedClockCandidate = sourceLabel == CameraTimestampSourceLabel.REALTIME,
    )
}

fun timestampSourceLogLine(result: CameraTimestampSourceReadResult): String =
    when (result) {
        is CameraTimestampSourceReadResult.Success -> {
            val report = result.report
            "CAMERA_TIMESTAMP_SOURCE camera=${report.cameraRole} " +
                "cameraIdClass=${report.cameraIdClass} " +
                "source=${report.sourceLabel.logLabel} " +
                "value=${report.sourceValue?.toString() ?: "absent"} " +
                "sharedClockCandidate=${report.sharedClockCandidate}"
        }
        is CameraTimestampSourceReadResult.Failure ->
            "CAMERA_TIMESTAMP_SOURCE_FAILURE camera=back reason=${result.reason} " +
                "message=${sanitizeTimestampSourceLogToken(result.message)}"
    }

private fun String?.toCameraIdClass(): String =
    when {
        this == null -> "absent"
        isBlank() -> "blank"
        all { it.isDigit() } -> "numeric-${length}chars"
        else -> "opaque-${length}chars"
    }

private fun sanitizeTimestampSourceLogToken(raw: String): String =
    raw.trim()
        .ifBlank { "unavailable" }
        .replace(Regex("[^A-Za-z0-9_.:-]+"), "_")
        .take(96)
