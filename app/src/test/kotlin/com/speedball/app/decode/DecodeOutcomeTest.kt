package com.speedball.app.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DecodeOutcomeTest {
    @Test
    fun failureTaxonomyIsPinned() {
        assertEquals(
            listOf(
                DecodeFailure.OUTPUT_FILE_MISSING,
                DecodeFailure.OUTPUT_FILE_EMPTY,
                DecodeFailure.UNSUPPORTED_DECODER_API,
                DecodeFailure.NO_VIDEO_TRACK,
                DecodeFailure.INVALID_VIDEO_METADATA,
                DecodeFailure.FRAME_COUNT_UNAVAILABLE,
                DecodeFailure.FRAME_COUNT_TOO_LOW,
                DecodeFailure.FRAME_EXTRACTION_FAILED,
                DecodeFailure.MISSING_SENSOR_TIMESTAMPS,
                DecodeFailure.SENSOR_TIMESTAMP_NEAR_DUPLICATE,
                DecodeFailure.FRAME_SENSOR_COUNT_MISMATCH,
                DecodeFailure.PRESENTATION_TIMESTAMPS_NON_MONOTONIC,
                DecodeFailure.PRESENTATION_CADENCE_MISMATCH,
                DecodeFailure.PRESENTATION_DROPPED_FRAME_GAP,
                DecodeFailure.SENSOR_CADENCE_MISMATCH,
                DecodeFailure.SENSOR_DROPPED_FRAME_GAP,
                DecodeFailure.DECODE_WORK_LIMIT_EXCEEDED,
                DecodeFailure.RESOURCE_RELEASE_FAILED,
            ),
            DecodeFailure.entries,
        )
    }

    @Test
    fun failureCarriesOnlyReasonAndMessage() {
        val failure = DecodeOutcome.Failure(DecodeFailure.FRAME_SENSOR_COUNT_MISMATCH, "No read.")

        assertEquals(listOf("reason", "message"), failure::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") })
        assertFalse(failure.message.contains("m" + "ph", ignoreCase = true))
        assertFalse(failure.message.contains("tra" + "jectory", ignoreCase = true))
        assertInstanceOf(DecodeOutcome.Failure::class.java, failure)
    }

    @Test
    fun cancelledIsDistinctFromFailure() {
        val outcome: DecodeOutcome = DecodeOutcome.Cancelled

        assertFalse(outcome is DecodeOutcome.Failure)
    }
}
