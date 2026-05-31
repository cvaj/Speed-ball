package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HighSpeedModeSelectionTest {
    @Test
    fun s10RangesSelect720p120ByDefault() {
        val modes = mapHighSpeedModes(s10Ranges())

        assertEquals(4, modes.count { it.recordSupported })
        assertEquals(HighSpeedMode(1280, 720, 120, 120, 120, true), selectDefaultMode(modes))
    }

    @Test
    fun selects1080p120When720p120IsAbsent() {
        val modes = mapHighSpeedModes(
            listOf(
                RawHighSpeedRange(1920, 1080, 30, 120),
                RawHighSpeedRange(1920, 1080, 120, 120),
            ),
        )

        assertEquals(HighSpeedMode(1920, 1080, 120, 120, 120, true), selectDefaultMode(modes))
    }

    @Test
    fun blendedRangeWithoutFixedRangeIsNotRecordSupported() {
        val modes = mapHighSpeedModes(listOf(RawHighSpeedRange(1280, 720, 30, 120)))

        assertEquals(1, modes.size)
        assertFalse(modes.single().recordSupported)
        assertEquals(30, modes.single().aeTargetFpsLower)
        assertNull(selectDefaultMode(modes))
    }

    @Test
    fun unsupported240RecordingFailsBeforeCameraOpen() {
        val modes = mapHighSpeedModes(s10Ranges())
        val mode240 = modes.first { it.width == 1280 && it.height == 720 && it.fps == 240 }

        val failure = validateBurstStart(mode240, modes, BurstRecorderState.Idle)

        assertNotNull(failure)
        assertEquals(BurstFailure.UNSUPPORTED_MODE, failure!!.reason)
    }

    @Test
    fun overlappingBurstFailsAsCaptureBusyBeforeCameraOpen() {
        val modes = mapHighSpeedModes(s10Ranges())
        val mode120 = selectDefaultMode(modes)!!

        val failure = validateBurstStart(mode120, modes, BurstRecorderState.Recording)

        assertNotNull(failure)
        assertEquals(BurstFailure.CAPTURE_BUSY, failure!!.reason)
    }

    @Test
    fun emptyRangesProduceNoModes() {
        assertTrue(mapHighSpeedModes(emptyList()).isEmpty())
    }

    private fun s10Ranges(): List<RawHighSpeedRange> =
        listOf(
            RawHighSpeedRange(1920, 1080, 30, 120),
            RawHighSpeedRange(1920, 1080, 120, 120),
            RawHighSpeedRange(1920, 1080, 30, 240),
            RawHighSpeedRange(1920, 1080, 240, 240),
            RawHighSpeedRange(1280, 720, 30, 120),
            RawHighSpeedRange(1280, 720, 120, 120),
            RawHighSpeedRange(1280, 720, 30, 240),
            RawHighSpeedRange(1280, 720, 240, 240),
        )
}
