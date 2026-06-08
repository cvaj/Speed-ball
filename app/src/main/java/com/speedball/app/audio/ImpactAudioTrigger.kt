package com.speedball.app.audio

import kotlin.math.sqrt

/**
 * Configuration for the loud-transient impact marker detector.
 *
 * [cooldownSampleCount] is reserved for future multi-marker detection and is
 * validated now so persisted/test configs cannot carry invalid cooldown values.
 * The current detector intentionally returns after the first marker.
 */
data class ImpactAudioTriggerConfig(
    val sampleRateHz: Int,
    val baselineSampleCount: Int,
    val triggerWindowSampleCount: Int,
    val thresholdMultiplier: Double,
    val minimumPeakDelta: Int,
    val minimumBaselineRms: Double,
    val cooldownSampleCount: Int,
    val maxArmSamples: Int,
    val actionableSampleCount: Int,
    val videoReadySampleIndex: Long = Long.MAX_VALUE,
    val acceptAfterSampleIndex: Long = Long.MAX_VALUE,
) {
    fun validate(): ImpactAudioTriggerResult.ResourceFailure? {
        if (sampleRateHz <= 0 || baselineSampleCount <= 0 || triggerWindowSampleCount <= 0 || maxArmSamples <= 0 || actionableSampleCount <= 0) {
            return ImpactAudioTriggerResult.ResourceFailure("Impact audio trigger sample counts must be positive.")
        }
        if (!thresholdMultiplier.isFinite() ||
            thresholdMultiplier <= 1.0 ||
            minimumPeakDelta < 0 ||
            !minimumBaselineRms.isFinite() ||
            minimumBaselineRms < 0.0 ||
            cooldownSampleCount < 0 ||
            videoReadySampleIndex < 0L ||
            acceptAfterSampleIndex < 0L
        ) {
            return ImpactAudioTriggerResult.ResourceFailure("Impact audio trigger thresholds must be valid.")
        }
        if (videoReadySampleIndex != Long.MAX_VALUE &&
            acceptAfterSampleIndex != Long.MAX_VALUE &&
            acceptAfterSampleIndex < videoReadySampleIndex
        ) {
            return ImpactAudioTriggerResult.ResourceFailure("Impact audio trigger phase boundaries must be monotonic.")
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

    fun sampleIndexForElapsedRealtime(elapsedRealtimeNanos: Long): Long? {
        if (framePosition < 0L || nanoTime <= 0L || sampleRateHz <= 0 || maxAnchorAgeNanos < 0L || elapsedRealtimeNanos < nanoTime) return null
        val offsetNanos = elapsedRealtimeNanos - nanoTime
        if (offsetNanos > maxAnchorAgeNanos) return null
        return framePosition + (offsetNanos.toDouble() * sampleRateHz / 1_000_000_000.0).toLong()
    }
}

/** Derived diagnostic data for the first loud impact marker. */
data class ImpactAudioEvent(
    val elapsedRealtimeNanos: Long,
    val peakAmplitude: Int,
    val baselineRms: Double,
    val triggerRatio: Double,
    val peakDelta: Double,
    val sampleIndex: Long,
    val offsetMillis: Double,
)

/** Terminal result from arming impact audio. Raw PCM is never retained. */
sealed interface ImpactAudioTriggerResult {
    data class Detected(val event: ImpactAudioEvent) : ImpactAudioTriggerResult
    data class NoImpact(
        val scannedSamples: Int,
        val actionableSamples: Int = 0,
        val preReadyTransientCount: Int = 0,
        val blankedTransientCount: Int = 0,
        val belowThresholdTransientCount: Int = 0,
        val strongestPostReadyPeakDelta: Double = 0.0,
        val strongestPostReadyRatio: Double = 0.0,
        val minimumPeakDelta: Int = 0,
        val minimumBaselineRms: Double = 0.0,
    ) : ImpactAudioTriggerResult
    data class PermissionDenied(val message: String) : ImpactAudioTriggerResult
    data class ResourceFailure(val message: String) : ImpactAudioTriggerResult
}

data class ImpactAudioArmedSession(
    val anchor: ImpactAudioClockAnchor,
    val markVideoReady: (Long) -> Unit,
    val enableAcceptance: (Long) -> Unit,
)

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
        val detector = ImpactAudioStreamingDetector(config, anchor)
        detector.feed(samples)?.let { return it }
        return detector.finish()
    }

    internal fun rms(samples: ShortArray, start: Int, count: Int): Double {
        var sumSquares = 0.0
        repeat(count) { offset ->
            val value = samples[start + offset].toDouble()
            sumSquares += value * value
        }
        return sqrt(sumSquares / count)
    }

    internal data class Peak(val amplitude: Int, val offset: Int)

    internal data class NoImpactDiagnostics(
        val config: ImpactAudioTriggerConfig,
        var preReadyTransientCount: Int = 0,
        var blankedTransientCount: Int = 0,
        var belowThresholdTransientCount: Int = 0,
        var strongestPostReadyPeakDelta: Double = 0.0,
        var strongestPostReadyRatio: Double = 0.0,
    ) {
        fun observePostReady(peakDelta: Double, ratio: Double, enoughDelta: Boolean) {
            strongestPostReadyPeakDelta = maxOf(strongestPostReadyPeakDelta, peakDelta)
            strongestPostReadyRatio = maxOf(strongestPostReadyRatio, ratio)
            if (!enoughDelta || ratio < config.thresholdMultiplier) {
                belowThresholdTransientCount += 1
            }
        }

        fun toResult(scanLimit: Int): ImpactAudioTriggerResult.NoImpact {
            val actionable = if (config.acceptAfterSampleIndex == Long.MAX_VALUE) {
                0
            } else {
                (scanLimit.toLong() - config.acceptAfterSampleIndex).coerceIn(0L, config.actionableSampleCount.toLong()).toInt()
            }
            return ImpactAudioTriggerResult.NoImpact(
                scannedSamples = scanLimit,
                actionableSamples = actionable,
                preReadyTransientCount = preReadyTransientCount,
                blankedTransientCount = blankedTransientCount,
                belowThresholdTransientCount = belowThresholdTransientCount,
                strongestPostReadyPeakDelta = strongestPostReadyPeakDelta,
                strongestPostReadyRatio = strongestPostReadyRatio,
                minimumPeakDelta = config.minimumPeakDelta,
                minimumBaselineRms = config.minimumBaselineRms,
            )
        }
    }

    internal fun peak(samples: ShortArray, start: Int, count: Int): Peak {
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

/**
 * Chunk-fed impact detector that evaluates each finalized window once.
 *
 * The per-position RMS and peak arithmetic intentionally matches
 * [ImpactAudioTrigger.detect]'s batch compatibility path. Streaming keeps a
 * monotonic scan cursor for O(n) window visits while preserving bit-identical
 * verdicts and scalar diagnostics for the same input.
 */
class ImpactAudioStreamingDetector(
    initialConfig: ImpactAudioTriggerConfig,
    private val anchor: ImpactAudioClockAnchor,
) {
    private var config = initialConfig
    private val samples = ShortArray(initialConfig.maxArmSamples.coerceAtLeast(1))
    private val diagnostics = ImpactAudioTrigger.NoImpactDiagnostics(initialConfig)
    private var written = 0
    private var nextStart = initialConfig.baselineSampleCount
    private var terminal: ImpactAudioTriggerResult? = initialConfig.validate()

    val writtenSampleCount: Int
        get() = written

    val evaluatedWindowCount: Int
        get() = (nextStart - config.baselineSampleCount).coerceAtLeast(0)

    fun updatePhaseBoundaries(
        videoReadySampleIndex: Long = config.videoReadySampleIndex,
        acceptAfterSampleIndex: Long = config.acceptAfterSampleIndex,
    ): ImpactAudioTriggerResult? {
        if (terminal != null) return terminal
        val updated = config.copy(
            videoReadySampleIndex = videoReadySampleIndex.coerceAtLeast(0L),
            acceptAfterSampleIndex = acceptAfterSampleIndex.coerceAtLeast(0L),
        )
        updated.validate()?.let {
            terminal = it
            return it
        }
        config = updated
        return processAvailable()
    }

    fun feed(chunk: ShortArray, offset: Int = 0, length: Int = chunk.size): ImpactAudioTriggerResult? {
        if (terminal != null) return terminal
        if (offset < 0 || length < 0 || offset + length > chunk.size) {
            terminal = ImpactAudioTriggerResult.ResourceFailure("Impact audio chunk bounds are invalid.")
            return terminal
        }
        val writable = minOf(length, samples.size - written)
        if (writable > 0) {
            System.arraycopy(chunk, offset, samples, written, writable)
            written += writable
        }
        return processAvailable()
    }

    fun finish(): ImpactAudioTriggerResult {
        terminal?.let { return it }
        processAvailable()?.let { return it }
        return diagnostics.toResult(minOf(written, config.maxArmSamples))
    }

    private fun processAvailable(): ImpactAudioTriggerResult? {
        terminal?.let { return it }
        if (config.acceptAfterSampleIndex == Long.MAX_VALUE) return null
        val scanLimit = minOf(written, config.maxArmSamples)
        if (scanLimit < config.baselineSampleCount + config.triggerWindowSampleCount) return null
        val lastStartInclusive = scanLimit - config.triggerWindowSampleCount
        while (nextStart <= lastStartInclusive) {
            val result = evaluateAt(nextStart)
            nextStart += 1
            if (result != null) {
                terminal = result
                return result
            }
        }
        val stopSample = (config.acceptAfterSampleIndex + config.actionableSampleCount)
            .coerceAtMost(config.maxArmSamples.toLong())
        if (scanLimit >= stopSample) {
            terminal = diagnostics.toResult(scanLimit)
            return terminal
        }
        return null
    }

    private fun evaluateAt(start: Int): ImpactAudioTriggerResult? {
        val baseline = ImpactAudioTrigger.rms(samples, start - config.baselineSampleCount, config.baselineSampleCount)
        val windowRms = ImpactAudioTrigger.rms(samples, start, config.triggerWindowSampleCount)
        val peak = ImpactAudioTrigger.peak(samples, start, config.triggerWindowSampleCount)
        val effectiveBaseline = maxOf(baseline, config.minimumBaselineRms)
        val ratio = if (effectiveBaseline > 0.0) windowRms / effectiveBaseline else 0.0
        val peakDelta = peak.amplitude - baseline
        val sampleIndex = (start + peak.offset).toLong()
        val baselineStart = (start - config.baselineSampleCount).toLong()
        val enoughDelta = peakDelta >= config.minimumPeakDelta
        if (sampleIndex >= config.acceptAfterSampleIndex) {
            diagnostics.observePostReady(peakDelta, ratio, enoughDelta)
        }
        if (ratio < config.thresholdMultiplier || !enoughDelta) return null
        if (sampleIndex < config.videoReadySampleIndex) {
            diagnostics.preReadyTransientCount += 1
            return null
        }
        if (sampleIndex < config.acceptAfterSampleIndex || baselineStart < config.acceptAfterSampleIndex) {
            diagnostics.blankedTransientCount += 1
            return null
        }
        val eventTimeNanos = anchor.elapsedRealtimeForSample(sampleIndex)
            ?: return ImpactAudioTriggerResult.ResourceFailure("Impact audio timestamp anchor is missing or stale.")
        return ImpactAudioTriggerResult.Detected(
            ImpactAudioEvent(
                elapsedRealtimeNanos = eventTimeNanos,
                peakAmplitude = peak.amplitude,
                baselineRms = baseline,
                triggerRatio = ratio,
                peakDelta = peakDelta,
                sampleIndex = sampleIndex,
                offsetMillis = sampleIndex.toDouble() * 1_000.0 / config.sampleRateHz,
            ),
        )
    }
}
