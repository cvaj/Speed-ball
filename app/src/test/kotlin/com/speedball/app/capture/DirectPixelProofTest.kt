package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class DirectPixelProofTest {
    @Test
    fun aggregateSignatureIsDeterministicAndDoesNotExposeRawPixels() {
        val pixels = intArrayOf(0xff101010.toInt(), 0xff202020.toInt(), 0xff303030.toInt(), 0xff404040.toInt())
        val first = signature(timestamp = 1_000L, pixels = pixels)
        val second = signature(timestamp = 1_000L, pixels = pixels.copyOf())

        assertEquals(first, second)
        assertEquals(4, first.sampleCount)
        assertEquals(48L, first.variationScore)
        val fields = first::class.java.declaredFields.map { it.name }.filterNot { it.startsWith("$") }
        assertFalse(fields.any { it.contains("pixel", ignoreCase = true) || it.contains("bitmap", ignoreCase = true) })
        assertFalse(first.toString().contains("ff101010", ignoreCase = true))
    }

    @Test
    fun aggregateSignatureChangesWhenPixelsChange() {
        val first = signature(
            timestamp = 1_000L,
            pixels = intArrayOf(0xff101010.toInt(), 0xff202020.toInt(), 0xff303030.toInt(), 0xff404040.toInt()),
        )
        val second = signature(
            timestamp = 1_000L,
            pixels = intArrayOf(0xff101010.toInt(), 0xff202020.toInt(), 0xff303030.toInt(), 0xff505050.toInt()),
        )

        assertNotEquals(first.aggregateHash, second.aggregateHash)
        assertNotEquals(first.aggregateChecksum, second.aggregateChecksum)
    }

    @Test
    fun validationSucceedsWhenSignaturesMatchAcceptedTimestamps() {
        val timestamps = listOf(1_000L, 2_000L)
        val signatures = listOf(
            signature(timestamp = 1_000L, pixels = variedPixels(0x10)),
            signature(timestamp = 2_000L, pixels = variedPixels(0x20)),
        )
        val success = assertInstanceOf(
            DirectPixelProofOutcome.Success::class.java,
            validateDirectPixelProofSignatures(timestamps, signatures, DirectPixelProofConfig(maxTotalSamples = 8)),
        )

        assertEquals(2, success.signatures.size)
        assertEquals(8, success.diagnostics.totalSampleCount)
        assertEquals(2, success.diagnostics.uniqueSignatureCount)
    }

    @Test
    fun validationRejectsMissingTimestampsOrCountMismatch() {
        val signature = signature(timestamp = 1_000L, pixels = variedPixels())

        assertEquals(
            DirectTimingSourceFailure.MISSING_DIRECT_TIMESTAMPS,
            validateDirectPixelProofSignatures(emptyList(), listOf(signature), DirectPixelProofConfig(maxTotalSamples = 4)).asFailure().reason,
        )
        assertEquals(
            DirectTimingSourceFailure.PIXEL_READBACK_FAILED,
            validateDirectPixelProofSignatures(listOf(1_000L, 2_000L), listOf(signature), DirectPixelProofConfig(maxTotalSamples = 4)).asFailure().reason,
        )
    }

    @Test
    fun validationRejectsTimestampOrderMismatch() {
        val signatures = listOf(
            signature(timestamp = 2_000L, pixels = variedPixels(0x20)),
            signature(timestamp = 1_000L, pixels = variedPixels(0x10)),
        )
        val failure = validateDirectPixelProofSignatures(
            expectedTimestampNanos = listOf(1_000L, 2_000L),
            signatures = signatures,
            config = DirectPixelProofConfig(maxTotalSamples = 8),
        ).asFailure()

        assertEquals(DirectTimingSourceFailure.PIXEL_READBACK_FAILED, failure.reason)
    }

    @Test
    fun validationRejectsResourceLimitAndBlankOrStaleProof() {
        val varied = signature(timestamp = 1_000L, pixels = variedPixels())
        assertEquals(
            DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED,
            validateDirectPixelProofSignatures(listOf(1_000L), listOf(varied), DirectPixelProofConfig(maxTotalSamples = 3)).asFailure().reason,
        )

        val blank = signature(
            timestamp = 1_000L,
            pixels = intArrayOf(0xff202020.toInt(), 0xff202020.toInt(), 0xff202020.toInt(), 0xff202020.toInt()),
        )
        assertEquals(
            DirectTimingSourceFailure.BLANK_OR_STALE_PIXEL_PROOF,
            validateDirectPixelProofSignatures(listOf(1_000L), listOf(blank), DirectPixelProofConfig(maxTotalSamples = 4)).asFailure().reason,
        )
    }

    @Test
    fun validationRejectsRepeatedIdenticalSignaturesWhenVariationRequired() {
        val first = signature(timestamp = 1_000L, pixels = variedPixels())
        val repeated = first.copy(timestampNanos = 2_000L)

        assertEquals(
            DirectTimingSourceFailure.BLANK_OR_STALE_PIXEL_PROOF,
            validateDirectPixelProofSignatures(
                expectedTimestampNanos = listOf(1_000L, 2_000L),
                signatures = listOf(first, repeated),
                config = DirectPixelProofConfig(maxTotalSamples = 8, requireInterFrameVariation = true),
            ).asFailure().reason,
        )
        assertInstanceOf(
            DirectPixelProofOutcome.Success::class.java,
            validateDirectPixelProofSignatures(
                expectedTimestampNanos = listOf(1_000L, 2_000L),
                signatures = listOf(first, repeated),
                config = DirectPixelProofConfig(maxTotalSamples = 8, requireInterFrameVariation = false),
            ),
        )
    }

    @Test
    fun builderRejectsInvalidDimensionsAndEmptySamples() {
        assertThrows(IllegalArgumentException::class.java) {
            signature(timestamp = 1_000L, pixels = intArrayOf())
        }
        assertThrows(IllegalArgumentException::class.java) {
            buildDirectPixelProofSignature(
                timestampNanos = 1_000L,
                frameWidth = 2,
                frameHeight = 2,
                tileLeft = 0,
                tileTop = 0,
                tileWidth = 2,
                tileHeight = 2,
                argbPixels = intArrayOf(1, 2, 3),
            )
        }
    }

    private fun DirectPixelProofOutcome.asFailure(): DirectPixelProofOutcome.Failure =
        assertInstanceOf(DirectPixelProofOutcome.Failure::class.java, this)

    private fun signature(
        timestamp: Long,
        pixels: IntArray,
    ): DirectPixelProofSignature =
        buildDirectPixelProofSignature(
            timestampNanos = timestamp,
            frameWidth = 2,
            frameHeight = 2,
            tileLeft = 0,
            tileTop = 0,
            tileWidth = 2,
            tileHeight = 2,
            argbPixels = pixels,
        )

    private fun variedPixels(base: Int = 0x10): IntArray =
        intArrayOf(
            argb(base),
            argb(base + 0x10),
            argb(base + 0x20),
            argb(base + 0x30),
        )

    private fun argb(value: Int): Int =
        (0xff shl 24) or (value shl 16) or (value shl 8) or value
}
