package com.speedball.app.measurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DetectionTrackExtractorTest {
    @Test
    fun extractsCleanTrackWithOriginalTimestamps() {
        val sequence = TimedFrameSequence(
            listOf(
                trackFrame(1, 5, 0.00),
                trackFrame(2, 4, 0.10),
                trackFrame(3, 3, 0.20),
            ),
        )

        val outcome = DetectionTrackExtractor.extract(sequence, trackConfig())

        assertTrue(outcome is TrackExtractionOutcome.Success)
        val detections = (outcome as TrackExtractionOutcome.Success).detections
        assertEquals(listOf(0.00, 0.10, 0.20), detections.map { it.timestampSeconds })
        assertEquals(3.0, detections.last().xPx, 1.0e-9)
    }

    @Test
    fun rejectsBadTimestampsCountsResolutionAndJumps() {
        assertFailure(
            TimedFrameSequence(listOf(trackFrame(1, 1, 0.0), trackFrame(2, 1, 0.0), trackFrame(3, 1, 0.1))),
            MeasurementRunFailure.BAD_FRAME_SEQUENCE,
        )
        assertFailure(
            TimedFrameSequence(listOf(trackFrame(1, 1, 0.0), trackFrame(2, 1, 0.1))),
            MeasurementRunFailure.INSUFFICIENT_DETECTIONS,
        )
        assertFailure(
            TimedFrameSequence(listOf(trackFrame(1, 1, 0.0), trackFrame(8, 1, 0.1), trackFrame(9, 1, 0.2))),
            MeasurementRunFailure.DETECTION_FAILED,
            config = trackConfig(maxJump = 2.0),
        )
        assertFailure(
            TimedFrameSequence(
                listOf(
                    trackFrame(1, 1, 0.0),
                    RgbFrame(99, 99, IntArray(99 * 99), 0.1),
                    trackFrame(3, 1, 0.2),
                ),
            ),
            MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
        )
    }

    @Test
    fun rejectedInteriorFrameDoesNotRenumberSurvivingTimestamps() {
        val sequence = TimedFrameSequence(
            listOf(
                trackFrame(1, 5, 0.00),
                trackFrame(2, 4, 0.10),
                frameWithRedPixels(10, 10, emptySet(), 0.20),
                trackFrame(4, 2, 0.30),
                trackFrame(5, 1, 0.40),
            ),
        )

        val outcome = DetectionTrackExtractor.extract(sequence, trackConfig(maxInteriorMisses = 1))

        assertTrue(outcome is TrackExtractionOutcome.Success)
        val timestamps = (outcome as TrackExtractionOutcome.Success).detections.map { it.timestampSeconds }
        assertEquals(listOf(0.00, 0.10, 0.30, 0.40), timestamps)
    }

    @Test
    fun ambiguousInteriorFrameAbortsInsteadOfBeingToleratedAsMiss() {
        val sequence = TimedFrameSequence(
            listOf(
                trackFrame(1, 5, 0.00),
                frameWithRedPixels(10, 10, setOf(2 to 4, 8 to 4), 0.10),
                trackFrame(3, 3, 0.20),
                trackFrame(4, 2, 0.30),
            ),
        )

        assertFailure(
            sequence = sequence,
            reason = MeasurementRunFailure.DETECTION_FAILED,
            config = trackConfig(maxInteriorMisses = 1),
        )
    }

    private fun assertFailure(
        sequence: TimedFrameSequence,
        reason: MeasurementRunFailure,
        config: TrackExtractionConfig = trackConfig(),
    ) {
        val outcome = DetectionTrackExtractor.extract(sequence, config)

        assertTrue(outcome is TrackExtractionOutcome.Failure)
        assertEquals(reason, (outcome as TrackExtractionOutcome.Failure).reason)
    }

    private fun trackFrame(x: Int, y: Int, timestampSeconds: Double): RgbFrame =
        frameWithRedPixels(10, 10, setOf(x to y), timestampSeconds)

    private fun trackConfig(
        maxJump: Double = 5.0,
        maxInteriorMisses: Int = 0,
    ): TrackExtractionConfig =
        TrackExtractionConfig(
            detectorConfig = defaultConfig(10, 10),
            maxFrameToFrameJumpPx = maxJump,
            maxInteriorMisses = maxInteriorMisses,
        )
}
