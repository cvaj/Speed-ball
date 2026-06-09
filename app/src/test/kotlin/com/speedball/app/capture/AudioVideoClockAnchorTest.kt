package com.speedball.app.capture

import com.speedball.app.importing.ImportNoReadReason
import com.speedball.app.importing.ImportValidationResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class AudioVideoClockAnchorTest {
    @Test
    fun mapsPreAndPostImpactWindowAtOneTwentyFps() {
        val result = assertSuccess(
            ImpactWindowMapper.map(
                anchor = anchor(errorNanos = 8_000_000L),
                request = ImpactWindowRequest(
                    impactElapsedRealtimeNanos = 1_250_000_000L,
                    requestedFps = 120,
                ),
            ),
        )

        assertEquals(250_000L, result.impactOffsetUs)
        assertEquals(30, result.sensorDiagnosticFrameIndex)
        assertEquals(120, result.window.postImpactFrameCount)
        assertEquals(1, result.window.preImpactMarginFrames)
        assertEquals(0L, result.window.windowStartUs)
        assertEquals(1_250_000L, result.window.windowEndUs)
        assertEquals(241, result.window.maxFrames)
    }

    @Test
    fun preImpactMarginClampsToZeroNearContainerStart() {
        val result = assertSuccess(
            ImpactWindowMapper.map(
                anchor = anchor(errorNanos = 50_000_000L),
                request = ImpactWindowRequest(
                    impactElapsedRealtimeNanos = 1_020_000_000L,
                    requestedFps = 120,
                ),
            ),
        )

        assertEquals(0L, result.window.windowStartUs)
        assertEquals(1_020_000L, result.window.windowEndUs)
        assertEquals(6, result.window.preImpactMarginFrames)
    }

    @Test
    fun rejectsMissingUnmappableAndOverCeilingAnchors() {
        assertNoRead(
            ImpactWindowMapper.map(
                anchor = anchor(firstFrameElapsedRealtimeNanos = 0L),
                request = request(),
            ),
            "anchor",
        )
        assertNoRead(
            ImpactWindowMapper.map(
                anchor = anchor(timestampSource = CameraTimestampSourceLabel.UNKNOWN),
                request = request(),
            ),
            "anchor",
        )
        assertNoRead(
            ImpactWindowMapper.map(
                anchor = anchor(errorNanos = 110_000_000L),
                request = request(maxPreImpactMarginFrames = 12),
            ),
            "ceiling",
        )
    }

    @Test
    fun rejectsBadImpactAndRequestValues() {
        assertNoRead(
            ImpactWindowMapper.map(
                anchor = anchor(),
                request = request(impactElapsedRealtimeNanos = 900_000_000L),
            ),
            "before",
        )
        assertNoRead(
            ImpactWindowMapper.map(
                anchor = anchor(),
                request = request(requestedFps = 0),
            ),
            "positive",
        )
    }

    @Test
    fun sensorFrameIndexIsOnlyDiagnosticWhileWindowUsesContainerTime() {
        val result = assertSuccess(
            ImpactWindowMapper.map(
                anchor = anchor(errorNanos = 0L),
                request = request(impactElapsedRealtimeNanos = 2_000_000_000L, requestedFps = 120),
            ),
        )

        assertEquals(120, result.sensorDiagnosticFrameIndex)
        assertEquals(0L, result.window.windowStartUs)
        assertEquals(2_000_000L, result.window.windowEndUs)
    }

    private fun assertSuccess(
        result: ImportValidationResult<ImpactWindowMapping>,
    ): ImpactWindowMapping =
        assertInstanceOf(
            ImpactWindowMapping::class.java,
            assertInstanceOf(ImportValidationResult.Success::class.java, result).value,
        )

    private fun assertNoRead(result: ImportValidationResult<ImpactWindowMapping>, expected: String) {
        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertEquals(ImportNoReadReason.NO_TRUSTWORTHY_TIMING, noRead.reason)
        assertTrue(noRead.message.contains(expected, ignoreCase = true), noRead.message)
    }

    private fun request(
        impactElapsedRealtimeNanos: Long = 1_300_000_000L,
        requestedFps: Int = 120,
        maxPreImpactMarginFrames: Int = 12,
    ): ImpactWindowRequest =
        ImpactWindowRequest(
            impactElapsedRealtimeNanos = impactElapsedRealtimeNanos,
            requestedFps = requestedFps,
            maxPreImpactMarginFrames = maxPreImpactMarginFrames,
        )

    private fun anchor(
        firstFrameElapsedRealtimeNanos: Long = 1_000_000_000L,
        timestampSource: CameraTimestampSourceLabel = CameraTimestampSourceLabel.REALTIME,
        errorNanos: Long = 0L,
    ): AudioVideoClockAnchor =
        AudioVideoClockAnchor(
            firstFrameSensorTimestampNanos = firstFrameElapsedRealtimeNanos,
            firstFrameElapsedRealtimeNanos = firstFrameElapsedRealtimeNanos,
            recorderStartCommandElapsedNanos = firstFrameElapsedRealtimeNanos - 10_000_000L,
            timestampSource = timestampSource,
            endToEndAnchorErrorNanos = errorNanos,
        )
}
