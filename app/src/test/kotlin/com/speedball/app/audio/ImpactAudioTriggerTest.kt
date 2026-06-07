package com.speedball.app.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImpactAudioTriggerTest {
    @Test
    fun quietBaselineDoesNotTrigger() {
        val result = ImpactAudioTrigger.detect(
            samples = ShortArray(600) { 80 },
            config = config(),
            anchor = anchor(),
        )

        assertInstanceOf(ImpactAudioTriggerResult.NoImpact::class.java, result)
    }

    @Test
    fun oneLoudPopTriggersOnceWithHardwareAnchoredTimestamp() {
        val samples = ShortArray(600) { 100 }
        samples[220] = 12_000
        samples[221] = -10_500

        val result = assertInstanceOf(
            ImpactAudioTriggerResult.Detected::class.java,
            ImpactAudioTrigger.detect(samples, config(), anchor()),
        )

        assertEquals(220L, result.event.sampleIndex)
        assertEquals(1_000_000_000L + 120L * 1_000_000_000L / 48_000L, result.event.elapsedRealtimeNanos)
        assertEquals(220.0 * 1_000.0 / 48_000.0, result.event.offsetMillis, 1.0e-9)
        assertTrue(result.event.triggerRatio > 4.0)
    }

    @Test
    fun repeatedLoudSamplesReturnTheFirstImpactMarker() {
        val samples = ShortArray(700) { 90 }
        samples[240] = 11_000
        samples[241] = 10_000
        samples[380] = 20_000

        val result = assertInstanceOf(
            ImpactAudioTriggerResult.Detected::class.java,
            ImpactAudioTrigger.detect(samples, config(cooldownSampleCount = 200), anchor()),
        )

        assertEquals(240L, result.event.sampleIndex)
        assertEquals(11_000, result.event.peakAmplitude)
    }

    @Test
    fun noisyBackgroundRequiresAdaptiveThreshold() {
        val samples = ShortArray(700) { index ->
            (if (index % 2 == 0) 500 else -500).toShort()
        }
        samples[260] = 1_200

        val ordinaryNoise = ImpactAudioTrigger.detect(samples, config(absolutePeakFloor = 1_000), anchor())

        assertInstanceOf(ImpactAudioTriggerResult.NoImpact::class.java, ordinaryNoise)
    }

    @Test
    fun staleOrMissingHardwareAnchorFailsLoud() {
        val samples = ShortArray(600) { 100 }
        samples[220] = 12_000

        val result = ImpactAudioTrigger.detect(
            samples = samples,
            config = config(),
            anchor = anchor(maxAnchorAgeNanos = 1),
        )

        val failure = assertInstanceOf(ImpactAudioTriggerResult.ResourceFailure::class.java, result)
        assertTrue(failure.message.contains("timestamp anchor"))
    }

    @Test
    fun maxArmDurationAndInvalidConfigFailWithoutFakeDetection() {
        val latePop = ShortArray(600) { 100 }
        latePop[520] = 12_000

        val noImpact = ImpactAudioTrigger.detect(latePop, config(maxArmSamples = 300), anchor())
        val invalid = ImpactAudioTrigger.detect(latePop, config(sampleRateHz = 0), anchor())

        assertInstanceOf(ImpactAudioTriggerResult.NoImpact::class.java, noImpact)
        assertInstanceOf(ImpactAudioTriggerResult.ResourceFailure::class.java, invalid)
    }

    private fun config(
        sampleRateHz: Int = 48_000,
        cooldownSampleCount: Int = 96,
        maxArmSamples: Int = 48_000,
        absolutePeakFloor: Int = 4_000,
    ): ImpactAudioTriggerConfig =
        ImpactAudioTriggerConfig(
            sampleRateHz = sampleRateHz,
            baselineSampleCount = 120,
            triggerWindowSampleCount = 4,
            thresholdMultiplier = 4.0,
            absolutePeakFloor = absolutePeakFloor,
            cooldownSampleCount = cooldownSampleCount,
            maxArmSamples = maxArmSamples,
        )

    private fun anchor(maxAnchorAgeNanos: Long = 1_000_000_000L): ImpactAudioClockAnchor =
        ImpactAudioClockAnchor(
            framePosition = 100L,
            nanoTime = 1_000_000_000L,
            sampleRateHz = 48_000,
            maxAnchorAgeNanos = maxAnchorAgeNanos,
        )
}
