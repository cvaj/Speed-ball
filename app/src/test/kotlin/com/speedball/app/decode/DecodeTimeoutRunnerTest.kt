package com.speedball.app.decode

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test
import java.util.concurrent.Executors

class DecodeTimeoutRunnerTest {
    @Test
    fun timeoutReturnsTypedWorkLimitFailure() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val outcome = runDecodeWithTimeout(executor, timeoutMillis = 10L) {
                Thread.sleep(500L)
                DecodeOutcome.Cancelled
            }
            val failure = assertInstanceOf(DecodeOutcome.Failure::class.java, outcome)

            assertEquals(DecodeFailure.DECODE_WORK_LIMIT_EXCEEDED, failure.reason)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun completedWorkReturnsOriginalOutcome() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val outcome = runDecodeWithTimeout(executor, timeoutMillis = 1_000L) {
                DecodeOutcome.Failure(DecodeFailure.FRAME_SENSOR_COUNT_MISMATCH, "No exact count match.")
            }
            val failure = assertInstanceOf(DecodeOutcome.Failure::class.java, outcome)

            assertEquals(DecodeFailure.FRAME_SENSOR_COUNT_MISMATCH, failure.reason)
        } finally {
            executor.shutdownNow()
        }
    }
}
