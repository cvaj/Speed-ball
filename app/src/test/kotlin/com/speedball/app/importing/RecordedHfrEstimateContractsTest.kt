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
            metadataSampleCount = 240,
            scannedFrameCount = 240,
            retainedCandidateFrameCount = 6,
            diagnostics = diagnosticsWithUniqueCount(count = 240, gapMillis = 8.33),
            metadata = metadata(sampleCount = 240),
        )

        val success = assertInstanceOf(ImportValidationResult.Success::class.java, result)
        val proof = assertInstanceOf(RecordedHfrCaptureGateProof::class.java, success.value)
        assertEquals(240, proof.metadataSampleCount)
        assertEquals(240, proof.scannedFrameCount)
        assertEquals(6, proof.retainedCandidateFrameCount)
        assertEquals(240, proof.uniqueSensorTimestampCount)
        assertTrue(proof.sensorCadencePasses)
        assertTrue(proof.captureProofPasses)
        assertEquals("PASS", proof.dropVerdict)
        assertEquals("PASS", proof.cadenceVerdict)
    }

    @Test
    fun captureGateFailsLoudOnDecodedSensorCountMismatch() {
        val result = RecordedHfrCaptureGate.validate(
            metadataSampleCount = 239,
            scannedFrameCount = 239,
            retainedCandidateFrameCount = 6,
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
            metadataSampleCount = 240,
            scannedFrameCount = 239,
            retainedCandidateFrameCount = 6,
            diagnostics = diagnosticsWithUniqueCount(count = 240, gapMillis = 8.33),
            metadata = metadata(sampleCount = 240),
        )

        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertEquals(ImportNoReadReason.NO_TRUSTWORTHY_TIMING, noRead.reason)
        assertTrue(noRead.message.contains("scanned frame count"))
        assertTrue(noRead.message.contains("decoded sample count"))
    }

    @Test
    fun retainedCandidateCountDoesNotSatisfyOrBlockCaptureDropGate() {
        val passesWithFewCandidates = RecordedHfrCaptureGate.validate(
            metadataSampleCount = 240,
            scannedFrameCount = 240,
            retainedCandidateFrameCount = 4,
            diagnostics = diagnosticsWithUniqueCount(count = 240, gapMillis = 8.33),
            metadata = metadata(sampleCount = 240),
        )
        val failsEvenWhenCandidatesMatchSensorCount = RecordedHfrCaptureGate.validate(
            metadataSampleCount = 239,
            scannedFrameCount = 239,
            retainedCandidateFrameCount = 240,
            diagnostics = diagnosticsWithUniqueCount(count = 240, gapMillis = 8.33),
            metadata = metadata(sampleCount = 239),
        )

        assertInstanceOf(ImportValidationResult.Success::class.java, passesWithFewCandidates)
        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, failsEvenWhenCandidatesMatchSensorCount)
        assertTrue(noRead.message.contains("unique SENSOR_TIMESTAMP count"))
    }

    @Test
    fun captureGateFailsLoudWhenSensorCadenceDoesNotProveRequestedBand() {
        val result = RecordedHfrCaptureGate.validate(
            metadataSampleCount = 240,
            scannedFrameCount = 240,
            retainedCandidateFrameCount = 6,
            diagnostics = diagnosticsWithUniqueCount(count = 240, gapMillis = 10.4),
            metadata = metadata(sampleCount = 240),
        )

        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertEquals(ImportNoReadReason.NO_TRUSTWORTHY_TIMING, noRead.reason)
        assertTrue(noRead.message.contains("sensor cadence"))
    }

    @Test
    fun windowGateAcceptsBoundedSubsetWithoutSensorContainerCountEquality() {
        val result = RecordedHfrWindowCaptureGate.validate(
            metadataSampleCount = 325,
            decodedWindowFrameCount = 24,
            requestedWindowFrameCount = 24,
            minUsableFrameCount = 4,
            diagnostics = diagnosticsWithUniqueCount(count = 40, gapMillis = 8.33),
            metadata = metadata(sampleCount = 325),
            windowStartUs = 1_000_000,
            windowEndUs = 1_200_000,
            emittedFirstPtsUs = 1_000_000,
            emittedLastPtsUs = 1_191_667,
            sourceWidth = 1280,
            sourceHeight = 720,
            proofFrameCount = 4,
            sourceValidityPasses = true,
        )

        val proof = assertInstanceOf(RecordedHfrWindowGateProof::class.java, assertInstanceOf(
            ImportValidationResult.Success::class.java,
            result,
        ).value)
        assertEquals(325, proof.metadataSampleCount)
        assertEquals(24, proof.decodedWindowFrameCount)
        assertEquals(40, proof.uniqueSensorTimestampCount)
        assertFalse(proof.captureProofPasses)
        assertEquals("PASS", proof.windowVerdict)
        assertEquals("PASS", proof.cadenceVerdict)
    }

    @Test
    fun windowGateFailsLoudForShortOutsideBlackOrMissingProofWindow() {
        val commonDiagnostics = diagnosticsWithUniqueCount(count = 400, gapMillis = 8.33)
        val commonMetadata = metadata(sampleCount = 325)

        val short = RecordedHfrWindowCaptureGate.validate(
            metadataSampleCount = 325,
            decodedWindowFrameCount = 3,
            requestedWindowFrameCount = 24,
            minUsableFrameCount = 4,
            diagnostics = commonDiagnostics,
            metadata = commonMetadata,
            windowStartUs = 1_000_000,
            windowEndUs = 1_200_000,
            emittedFirstPtsUs = 1_000_000,
            emittedLastPtsUs = 1_050_000,
            sourceWidth = 1280,
            sourceHeight = 720,
            proofFrameCount = 1,
            sourceValidityPasses = true,
        )
        val outside = RecordedHfrWindowCaptureGate.validate(
            metadataSampleCount = 325,
            decodedWindowFrameCount = 24,
            requestedWindowFrameCount = 24,
            minUsableFrameCount = 4,
            diagnostics = commonDiagnostics,
            metadata = commonMetadata,
            windowStartUs = 1_000_000,
            windowEndUs = 1_200_000,
            emittedFirstPtsUs = 900_000,
            emittedLastPtsUs = 1_100_000,
            sourceWidth = 1280,
            sourceHeight = 720,
            proofFrameCount = 1,
            sourceValidityPasses = true,
        )
        val missingProof = RecordedHfrWindowCaptureGate.validate(
            metadataSampleCount = 325,
            decodedWindowFrameCount = 24,
            requestedWindowFrameCount = 24,
            minUsableFrameCount = 4,
            diagnostics = commonDiagnostics,
            metadata = commonMetadata,
            windowStartUs = 1_000_000,
            windowEndUs = 1_200_000,
            emittedFirstPtsUs = 1_000_000,
            emittedLastPtsUs = 1_100_000,
            sourceWidth = 1280,
            sourceHeight = 720,
            proofFrameCount = 0,
            sourceValidityPasses = true,
        )
        val black = RecordedHfrWindowCaptureGate.validate(
            metadataSampleCount = 325,
            decodedWindowFrameCount = 24,
            requestedWindowFrameCount = 24,
            minUsableFrameCount = 4,
            diagnostics = commonDiagnostics,
            metadata = commonMetadata,
            windowStartUs = 1_000_000,
            windowEndUs = 1_200_000,
            emittedFirstPtsUs = 1_000_000,
            emittedLastPtsUs = 1_100_000,
            sourceWidth = 1280,
            sourceHeight = 720,
            proofFrameCount = 1,
            sourceValidityPasses = false,
        )

        assertWindowNoRead(short, "frame count")
        assertWindowNoRead(outside, "outside")
        assertWindowNoRead(missingProof, "proof imagery")
        assertWindowNoRead(black, "black")
    }

    @Test
    fun workingResolutionDownscalesRecordedHfr720pAnd1080pTo640x360() {
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
        assertEquals(640, full720.working.width)
        assertEquals(360, full720.working.height)
        assertTrue(full720.downscaledForDetection)
        assertEquals(0.5, full720.scaleX, 0.0)
        assertEquals(0.5, full720.scaleY, 0.0)
        assertEquals(1, full720.scaleAreaPx(4))
        assertEquals(1920, bounded1080.source.width)
        assertEquals(1080, bounded1080.source.height)
        assertEquals(640, bounded1080.working.width)
        assertEquals(360, bounded1080.working.height)
        assertTrue(bounded1080.downscaledForDetection)
        assertEquals(1.0 / 3.0, bounded1080.scaleX, 0.0001)
        assertEquals(1.0 / 3.0, bounded1080.scaleY, 0.0001)
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

    private fun assertWindowNoRead(result: ImportValidationResult<RecordedHfrWindowGateProof>, expected: String) {
        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertEquals(ImportNoReadReason.NO_TRUSTWORTHY_TIMING, noRead.reason)
        assertTrue(noRead.message.contains(expected, ignoreCase = true), noRead.message)
    }
}
