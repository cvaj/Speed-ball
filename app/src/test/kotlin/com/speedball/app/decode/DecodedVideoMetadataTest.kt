package com.speedball.app.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

class DecodedVideoMetadataTest {
    @Test
    fun validVideoMetadataComputesPresentationGaps() {
        val result = validateDecodedVideoMetadata(validRaw(), requestedFps = 120)
        val success = assertInstanceOf(MetadataValidation.Success::class.java, result)

        assertEquals(4, success.metadata.frameCount)
        assertEquals(8.333, success.metadata.medianPresentationGapMillis!!, 0.001)
        assertEquals(8.334, success.metadata.maximumPresentationGapMillis!!, 0.001)
    }

    @Test
    fun firstPresentationTimestampAtZeroStillContributesToGaps() {
        val result = validateDecodedVideoMetadata(
            validRaw().copy(presentationTimeMicros = listOf(0L, 16_666L, 24_999L, 33_332L)),
            requestedFps = 120,
        )
        val success = assertInstanceOf(MetadataValidation.Success::class.java, result)

        assertEquals(8.333, success.metadata.medianPresentationGapMillis!!, 0.001)
        assertEquals(16.666, success.metadata.maximumPresentationGapMillis!!, 0.001)
    }

    @Test
    fun missingVideoTrackFailsLoud() {
        val failure = validateDecodedVideoMetadata(validRaw().copy(mimeType = "audio/mp4"), 120).asFailure()

        assertEquals(DecodeFailure.NO_VIDEO_TRACK, failure.reason)
    }

    @Test
    fun invalidDimensionsFailLoud() {
        val failure = validateDecodedVideoMetadata(validRaw().copy(width = 0), 120).asFailure()

        assertEquals(DecodeFailure.INVALID_VIDEO_METADATA, failure.reason)
    }

    @Test
    fun emptyPresentationListFailsAsUnavailableFrameCount() {
        val failure = validateDecodedVideoMetadata(validRaw().copy(presentationTimeMicros = emptyList()), 120).asFailure()

        assertEquals(DecodeFailure.FRAME_COUNT_UNAVAILABLE, failure.reason)
    }

    @Test
    fun fewerThanThreeFramesFailLoud() {
        val failure = validateDecodedVideoMetadata(validRaw().copy(presentationTimeMicros = listOf(0L, 8_333L)), 120).asFailure()

        assertEquals(DecodeFailure.FRAME_COUNT_TOO_LOW, failure.reason)
    }

    @Test
    fun nonMonotonicPresentationTimestampsFailLoud() {
        val failure = validateDecodedVideoMetadata(validRaw().copy(presentationTimeMicros = listOf(0L, 8_333L, 8_333L)), 120).asFailure()

        assertEquals(DecodeFailure.PRESENTATION_TIMESTAMPS_NON_MONOTONIC, failure.reason)
    }

    @Test
    fun releaseFailureDoesNotMaskPrimaryDecodeFailure() {
        assertEquals(
            DecodeFailure.FRAME_EXTRACTION_FAILED,
            resolveDecodeTerminalFailure(DecodeFailure.FRAME_EXTRACTION_FAILED, DecodeFailure.RESOURCE_RELEASE_FAILED),
        )
        assertEquals(
            DecodeFailure.RESOURCE_RELEASE_FAILED,
            resolveDecodeTerminalFailure(null, DecodeFailure.RESOURCE_RELEASE_FAILED),
        )
    }

    @Test
    fun samplingPlanUsesNonAdjacentFirstAndLastFrames() {
        val plan = buildFrameSamplingPlan(5, listOf(0L, 8_333L, 16_666L, 24_999L, 33_332L))!!

        assertEquals(listOf(0, 4), plan.indices)
        assertEquals(listOf(0L, 33_332L), plan.presentationTimeMicros)
    }

    @Test
    fun samplingPlanRequiresAtLeastThreeFrames() {
        assertEquals(null, buildFrameSamplingPlan(2, listOf(0L, 8_333L)))
    }

    @Test
    fun frameExtractionMethodRespectsMinSdkAvailability() {
        assertEquals(FrameExtractionMethod.INDEX, frameExtractionMethodForSdk(28))
        assertEquals(FrameExtractionMethod.PRESENTATION_TIME, frameExtractionMethodForSdk(26))
        assertEquals(null, frameExtractionMethodForSdk(25))
    }

    @Test
    fun decodeWorkBoundsAllowLongestInSpecBurstWithMargin() {
        val bounds = buildDecodeWorkBounds(requestedDurationMillis = 3_000L, requestedFps = 120)

        assertEquals(480, bounds.maximumSamples)
        assertEquals(12_000L, bounds.timeoutMillis)
    }

    private fun validRaw(): RawDecodedVideoMetadata =
        RawDecodedVideoMetadata(
            mimeType = "video/avc",
            width = 1280,
            height = 720,
            durationMicros = 25_000L,
            presentationTimeMicros = listOf(0L, 8_333L, 16_667L, 25_000L),
        )

    private fun MetadataValidation.asFailure(): MetadataValidation.Failure =
        assertInstanceOf(MetadataValidation.Failure::class.java, this)
}
