package com.speedball.app.decode

/** Exactly-once terminal gate for background decode work racing lifecycle cancellation. */
class DecodeCompletionGate {
    private var generation: Int = 0
    private var delivered: Boolean = false

    @Synchronized
    fun startRun(): Int {
        generation += 1
        delivered = false
        return generation
    }

    @Synchronized
    fun cancel(): DecodeOutcome.Cancelled {
        generation += 1
        delivered = true
        return DecodeOutcome.Cancelled
    }

    @Synchronized
    fun accept(runGeneration: Int, outcome: DecodeOutcome): DecodeOutcome? =
        if (runGeneration == generation && !delivered) {
            delivered = true
            outcome
        } else {
            null
        }
}
