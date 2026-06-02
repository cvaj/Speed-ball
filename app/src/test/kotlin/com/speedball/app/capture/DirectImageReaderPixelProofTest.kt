package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirectImageReaderPixelProofTest {
    @Test
    fun imageReaderProofUsesOneAcquiredSnapshotBeforeClose() {
        val snapshot = FakeImageReaderSnapshot(timestampNanos = 1_234L)

        val outcome = buildDirectImageReaderPixelProofSignature(
            snapshot = snapshot,
            tileLeft = 0,
            tileTop = 0,
            tileWidth = 2,
            tileHeight = 2,
        )
        val success = assertInstanceOf(DirectImageReaderPixelProofOutcome.Success::class.java, outcome)

        assertEquals(1_234L, success.signature.timestampNanos)
        assertEquals(listOf("read:0,0,2,2", "close"), snapshot.events)
        assertTrue(snapshot.closed)
        assertFalse(snapshot.readAfterClose)
    }

    @Test
    fun imageReaderProofFailsLoudOnMissingTimestampAndStillCloses() {
        val snapshot = FakeImageReaderSnapshot(timestampNanos = 0L)

        val outcome = buildDirectImageReaderPixelProofSignature(
            snapshot = snapshot,
            tileLeft = 0,
            tileTop = 0,
            tileWidth = 2,
            tileHeight = 2,
        )
        val failure = assertInstanceOf(DirectImageReaderPixelProofOutcome.Failure::class.java, outcome)

        assertEquals(DirectTimingSourceFailure.MISSING_DIRECT_TIMESTAMPS, failure.reason)
        assertEquals(listOf("close"), snapshot.events)
        assertTrue(snapshot.closed)
    }

    @Test
    fun imageReaderProofFailsLoudOnTileConversionFailureAndStillCloses() {
        val snapshot = FakeImageReaderSnapshot(timestampNanos = 1_234L, failRead = true)

        val outcome = buildDirectImageReaderPixelProofSignature(
            snapshot = snapshot,
            tileLeft = 0,
            tileTop = 0,
            tileWidth = 2,
            tileHeight = 2,
        )
        val failure = assertInstanceOf(DirectImageReaderPixelProofOutcome.Failure::class.java, outcome)

        assertEquals(DirectTimingSourceFailure.PIXEL_READBACK_FAILED, failure.reason)
        assertEquals(listOf("read:0,0,2,2", "close"), snapshot.events)
        assertTrue(snapshot.closed)
    }

    private class FakeImageReaderSnapshot(
        override val timestampNanos: Long,
        private val failRead: Boolean = false,
    ) : DirectImageReaderSnapshot {
        override val width: Int = 2
        override val height: Int = 2
        val events = mutableListOf<String>()
        var closed = false
        var readAfterClose = false

        override fun readArgbTile(
            left: Int,
            top: Int,
            width: Int,
            height: Int,
        ): IntArray {
            if (closed) readAfterClose = true
            events += "read:$left,$top,$width,$height"
            if (failRead) error("unsupported image format")
            return intArrayOf(
                0xff101010.toInt(),
                0xff202020.toInt(),
                0xff303030.toInt(),
                0xff404040.toInt(),
            )
        }

        override fun close() {
            closed = true
            events += "close"
        }
    }
}
