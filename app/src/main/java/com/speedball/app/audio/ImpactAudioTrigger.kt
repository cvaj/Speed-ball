package com.speedball.app.audio

import kotlin.math.sqrt

/** Configuration for the loud-transient impact marker detector. */
data class ImpactAudioTriggerConfig(
    val sampleRateHz: Int,
    val baselineSampleCount: Int,
    val triggerWindowSampleCount: Int,
    val thresholdMultiplier: Double,
    val absolutePeakFloor: Int,
    val cooldownSampleCount: Int,
    val maxArmSamples: Int,
) {
    fun validate(): ImpactAudioTriggerResult.ResourceFailure? {
        if (sampleRateHz <= 0 || baselineSampleCount <= 0 || triggerWindowSampleCount <= 0 || maxArmSamples <= 0) {
            return ImpactAudioTriggerResult.ResourceFailure("Impact audio trigger sample counts must be positive.")
        }
        if (!thresholdMultiplier.isFinite() || thresholdMultiplier <= 1.0 || absolutePeakFloor < 0 || cooldownSampleCount < 0) {
            return ImpactAudioTriggerResult.ResourceFailure("Impact audio trigger thresholds must be valid.")
        }
        return null
    }
}

/**
 * Hardware audio-clock anchor reduced from `AudioRecord.getTimestamp()`.
 *
 * [framePosition] is the audio frame position reported by the hardware clock and
 * [nanoTime] is the corresponding monotonic timestamp. A stale or missing anchor
 * must be included in the end-to-end timing error budget or fail loud.
 */
data class ImpactAudioClockAnchor(
    val framePosition: Long,
    val nanoTime: Long,
    val sampleRateHz: Int,
    val maxAnchorAgeNanos: Long,
) {
    fun elapsedRealtimeForSample(sampleIndex: Long): Long? {
        if (framePosition < 0L || nanoTime <= 0L || sampleRateHz <= 0 || maxAnchorAgeNanos < 0L || sampleIndex < framePosition) return null
        val offsetNanos = ((sampleIndex - framePosition).toDouble() * 1_000_000_000.0 / sampleRateHz).toLong()
        if (offsetNanos > maxAnchorAgeNanos) return null
        return nanoTime + offsetNanos
    }
}

/** Derived diagnostic data for the first loud impact marker. */
data class ImpactAudioEvent(
    val elapsedRealtimeNanos: Long,
    val peakAmplitude: Int,
    val baselineRms: Double,
    val triggerRatio: Double,
    val sampleIndex: Long,
    val offsetMillis: Double,
)

/** Terminal result from arming impact audio. Raw PCM is never retained. */
sealed interface ImpactAudioTriggerResult {
    data class Detected(val event: ImpactAudioEvent) : ImpactAudioTriggerResult
    data class NoImpact(val scannedSamples: Int) : ImpactAudioTriggerResult
    data class PermissionDenied(val message: String) : ImpactAudioTriggerResult
    data class ResourceFailure(val message: String) : ImpactAudioTriggerResult
}

/**
 * Pure loud-transient detector for the impact marker.
 *
 * The detector classifies no sound type. It only finds the first short-window
 * peak/RMS transient above an adaptive background baseline and returns derived
 * scalar diagnostics.
 */
object ImpactAudioTrigger {
    fun detect(
        samples: ShortArray,
        config: ImpactAudioTriggerConfig,
        anchor: ImpactAudioClockAnchor,
    ): ImpactAudioTriggerResult {
        config.validate()?.let { return it }
        if (samples.isEmpty()) return ImpactAudioTriggerResult.NoImpact(0)
        val scanLimit = minOf(samples.size, config.maxArmSamples)
        if (scanLimit < config.baselineSampleCount + config.triggerWindowSampleCount) {
            return ImpactAudioTriggerResult.NoImpact(scanLimit)
        }
        for (start in config.baselineSampleCount..(scanLimit - config.triggerWindowSampleCount)) {
            val baseline = rms(samples, start - config.baselineSampleCount, config.baselineSampleCount)
            val windowRms = rms(samples, start, config.triggerWindowSampleCount)
            val peak = peak(samples, start, config.triggerWindowSampleCount)
            val ratio = if (baseline > 0.0) windowRms / baseline else if (windowRms > 0.0) Double.POSITIVE_INFINITY else 0.0
            if (peak.amplitude >= config.absolutePeakFloor && ratio >= config.thresholdMultiplier) {
                val sampleIndex = (start + peak.offset).toLong()
                val eventTimeNanos = anchor.elapsedRealtimeForSample(sampleIndex)
                    ?: return ImpactAudioTriggerResult.ResourceFailure("Impact audio timestamp anchor is missing or stale.")
                return ImpactAudioTriggerResult.Detected(
                    ImpactAudioEvent(
                        elapsedRealtimeNanos = eventTimeNanos,
                        peakAmplitude = peak.amplitude,
                        baselineRms = baseline,
                        triggerRatio = ratio,
                        sampleIndex = sampleIndex,
                        offsetMillis = sampleIndex.toDouble() * 1_000.0 / config.sampleRateHz,
                    ),
                )
            }
        }
        return ImpactAudioTriggerResult.NoImpact(scanLimit)
    }

    private fun rms(samples: ShortArray, start: Int, count: Int): Double {
        var sumSquares = 0.0
        repeat(count) { offset ->
            val value = samples[start + offset].toDouble()
            sumSquares += value * value
        }
        return sqrt(sumSquares / count)
    }

    private data class Peak(val amplitude: Int, val offset: Int)

    private fun peak(samples: ShortArray, start: Int, count: Int): Peak {
        var max = 0
        var maxOffset = 0
        repeat(count) { offset ->
            val value = kotlin.math.abs(samples[start + offset].toInt())
            if (value > max) {
                max = value
                maxOffset = offset
            }
        }
        return Peak(max, maxOffset)
    }
}
