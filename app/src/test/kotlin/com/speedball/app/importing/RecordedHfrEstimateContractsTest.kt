package com.speedball.app.importing

import com.speedball.app.capture.BurstDiagnostics
import com.speedball.app.capture.buildBurstDiagnostics
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToLong

class RecordedHfrEstimateContractsTest {
    @Test
    fun captureGatePassesOnlyWhenDecodedFramesMatchUniqueSensorTimestampsAndCadencePasses() {
        val result = RecordedHfrCaptureGate.validate(
            decodedFrameCount = 240,
            extractedFrameCount = 240,
            diagnostics = diagnosticsWithUniqueCount(count = 240, gapMillis = 8.33),
            metadata = metadata(sampleCount = 240),
        )

        val success = assertInstanceOf(ImportValidationResult.Success::class.java, result)
        val proof = assertInstanceOf(RecordedHfrCaptureGateProof::class.java, success.value)
        assertEquals(240, proof.decodedFrameCount)
        assertEquals(240, proof.extractedFrameCount)
        assertEquals(240, proof.uniqueSensorTimestampCount)
        assertTrue(proof.sensorCadencePasses)
        assertTrue(proof.captureProofPasses)
        assertEquals("PASS", proof.dropVerdict)
        assertEquals("PASS", proof.cadenceVerdict)
    }

    @Test
    fun captureGateFailsLoudOnDecodedSensorCountMismatch() {
        val result = RecordedHfrCaptureGate.validate(
            decodedFrameCount = 239,
            extractedFrameCount = 239,
            diagnostics = diagnosticsWithUniqueCount(count = 240, gapMillis = 8.33),
            metadata = metadata(sampleCount = 239),
        )

        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertEquals(ImportNoReadReason.NO_TRUSTWORTHY_TIMING, noRead.reason)
        assertTrue(noRead.message.contains("did not match capture-side unique SENSOR_TIMESTAMP count"))
    }

    @Test
    fun captureGateFailsLoudOnExtractedDecodedCountMismatch() {
        val result = RecordedHfrCaptureGate.validate(
            decodedFrameCount = 240,
            extractedFrameCount = 239,
            diagnostics = diagnosticsWithUniqueCount(count = 240, gapMillis = 8.33),
            metadata = metadata(sampleCount = 240),
        )

        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertEquals(ImportNoReadReason.NO_TRUSTWORTHY_TIMING, noRead.reason)
        assertTrue(noRead.message.contains("extracted frame count"))
        assertTrue(noRead.message.contains("decoded sample count"))
    }

    @Test
    fun captureGateFailsLoudWhenSensorCadenceDoesNotProveRequestedBand() {
        val result = RecordedHfrCaptureGate.validate(
            decodedFrameCount = 240,
            extractedFrameCount = 240,
            diagnostics = diagnosticsWithUniqueCount(count = 240, gapMillis = 10.4),
            metadata = metadata(sampleCount = 240),
        )

        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertEquals(ImportNoReadReason.NO_TRUSTWORTHY_TIMING, noRead.reason)
        assertTrue(noRead.message.contains("sensor cadence"))
    }

    @Test
    fun workingResolutionKeeps720pAndDownscales1080pToBoundedDetectorSize() {
        val full720 = assertInstanceOf(RecordedHfrWorkingResolution::class.java, assertInstanceOf(
            ImportValidationResult.Success::class.java,
            RecordedHfrWorkingResolutionSelector.select(sourceWidth = 1280, sourceHeight = 720),
        ).value)
        val bounded1080 = assertInstanceOf(RecordedHfrWorkingResolution::class.java, assertInstanceOf(
            ImportValidationResult.Success::class.java,
            RecordedHfrWorkingResolutionSelector.select(sourceWidth = 1920, sourceHeight = 1080),
        ).value)

        assertEquals(1280, full720.source.width)
        assertEquals(720, full720.source.height)
        assertEquals(full720.source, full720.working)
        assertFalse(full720.downscaledForDetection)
        assertEquals(1920, bounded1080.source.width)
        assertEquals(1080, bounded1080.source.height)
        assertEquals(1280, bounded1080.working.width)
        assertEquals(720, bounded1080.working.height)
        assertTrue(bounded1080.downscaledForDetection)
    }

    private fun diagnosticsWithUniqueCount(count: Int, gapMillis: Double): BurstDiagnostics =
        buildBurstDiagnostics(
            timestampsNanos = List(count) { index -> timestampNanos(index * gapMillis) },
            callbackCount = count,
            requestedDurationMillis = 2_500L,
            fps = 120,
            outputPath = "/private/path/recorded-hfr.mp4",
            fileBytes = 1L,
        )

    private fun metadata(sampleCount: Int): ImportVideoMetadata =
        assertInstanceOf(ImportVideoMetadata::class.java, assertInstanceOf(
            ImportValidationResult.Success::class.java,
            ImportVideoMetadata.validate(
                mimeType = "video/mp4",
                width = 1280,
                height = 720,
                durationSeconds = 2.0,
                rotationDegrees = 0,
                sampleCount = sampleCount,
                hasMonotonicPresentationTimestamps = true,
            ),
        ).value)

    private fun timestampNanos(offsetMillis: Double): Long =
        1_000_000_000L + (offsetMillis * 1_000_000.0).roundToLong()
}
