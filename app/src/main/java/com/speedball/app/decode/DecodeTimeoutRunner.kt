package com.speedball.app.decode

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Runs decode work behind a bounded wait so native media calls cannot hang the app state forever. */
fun runDecodeWithTimeout(
    workerExecutor: ExecutorService,
    timeoutMillis: Long,
    task: () -> DecodeOutcome,
): DecodeOutcome {
    val future = workerExecutor.submit(Callable { task() })
    return try {
        future.get(timeoutMillis, TimeUnit.MILLISECONDS)
    } catch (_: TimeoutException) {
        future.cancel(true)
        DecodeOutcome.Failure(DecodeFailure.DECODE_WORK_LIMIT_EXCEEDED, "Decode work exceeded the bounded timeout.")
    } catch (_: InterruptedException) {
        future.cancel(true)
        Thread.currentThread().interrupt()
        DecodeOutcome.Cancelled
    } catch (exception: ExecutionException) {
        DecodeOutcome.Failure(
            DecodeFailure.INVALID_VIDEO_METADATA,
            "Decode work failed: ${exception.cause?.message ?: exception.cause?.javaClass?.simpleName ?: exception.javaClass.simpleName}.",
        )
    }
}
