package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CameraTimestampSourceTest {
    @Test
    fun mapsKnownCameraTimestampSourceValues() {
        assertEquals(CameraTimestampSourceLabel.UNKNOWN, mapCameraTimestampSourceValue(0))
        assertEquals(CameraTimestampSourceLabel.REALTIME, mapCameraTimestampSourceValue(1))
        assertEquals(CameraTimestampSourceLabel.ABSENT, mapCameraTimestampSourceValue(null))
        assertEquals(CameraTimestampSourceLabel.UNEXPECTED, mapCameraTimestampSourceValue(7))
    }

    @Test
    fun realtimeReportIsSharedClockCandidate() {
        val report = buildCameraTimestampSourceReport(
            cameraRole = "back",
            cameraId = "0",
            sourceValue = CAMERA_TIMESTAMP_SOURCE_REALTIME_VALUE,
        )

        assertEquals("back", report.cameraRole)
        assertEquals("numeric-1chars", report.cameraIdClass)
        assertEquals(CameraTimestampSourceLabel.REALTIME, report.sourceLabel)
        assertTrue(report.sharedClockCandidate)
    }

    @Test
    fun unknownAbsentAndUnexpectedAreNotSharedClockCandidates() {
        listOf(null, 0, 3).forEach { value ->
            val report = buildCameraTimestampSourceReport(
                cameraRole = "back",
                cameraId = "0",
                sourceValue = value,
            )

            assertFalse(report.sharedClockCandidate)
        }
    }

    @Test
    fun successLogLineIsBoundedAndContainsNoMeasurementTokens() {
        val line = timestampSourceLogLine(
            CameraTimestampSourceReadResult.Success(
                buildCameraTimestampSourceReport(
                    cameraRole = "back",
                    cameraId = "0",
                    sourceValue = CAMERA_TIMESTAMP_SOURCE_UNKNOWN_VALUE,
                ),
            ),
        )

        assertEquals(
            "CAMERA_TIMESTAMP_SOURCE camera=back cameraIdClass=numeric-1chars " +
                "source=UNKNOWN value=0 sharedClockCandidate=false",
            line,
        )
        assertFalse(line.contains("/"))
        assertFalse(line.contains("mph", ignoreCase = true))
        assertFalse(line.contains("angle", ignoreCase = true))
        assertFalse(line.contains("trajectory", ignoreCase = true))
        assertFalse(line.contains("token", ignoreCase = true))
    }

    @Test
    fun failureLogLineSanitizesMessage() {
        val line = timestampSourceLogLine(
            CameraTimestampSourceReadResult.Failure(
                reason = BurstFailure.CAMERA_PERMISSION_DENIED,
                message = "Camera permission is required: /tmp/private file",
            ),
        )

        assertEquals(
            "CAMERA_TIMESTAMP_SOURCE_FAILURE camera=back reason=CAMERA_PERMISSION_DENIED " +
                "message=Camera_permission_is_required:_tmp_private_file",
            line,
        )
        assertFalse(line.contains("/"))
    }
}
