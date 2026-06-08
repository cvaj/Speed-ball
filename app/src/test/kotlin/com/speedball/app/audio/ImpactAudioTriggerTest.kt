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
        assertTrue(result.event.peakDelta > 10_000.0)
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

        val ordinaryNoise = ImpactAudioTrigger.detect(samples, config(minimumPeakDelta = 900), anchor())

        assertInstanceOf(ImpactAudioTriggerResult.NoImpact::class.java, ordinaryNoise)
    }

    @Test
    fun preReadyAndCueTailTransientsAreDiagnosticOnly() {
        val samples = ShortArray(1_100) { 90 }
        samples[180] = 2_500
        samples[420] = 2_800
        samples[820] = 2_700

        val result = assertInstanceOf(
            ImpactAudioTriggerResult.Detected::class.java,
            ImpactAudioTrigger.detect(
                samples,
                config(videoReadySampleIndex = 300, acceptAfterSampleIndex = 650),
                anchor(),
            ),
        )

        assertEquals(820L, result.event.sampleIndex)
    }

    @Test
    fun blankedCueSamplesDoNotBecomePostCueBaseline() {
        val samples = ShortArray(1_000) { 80 }
        repeat(120) { offset ->
            samples[520 + offset] = if (offset % 2 == 0) 2_200 else -2_200
        }
        samples[760] = 1_900

        val result = assertInstanceOf(
            ImpactAudioTriggerResult.Detected::class.java,
            ImpactAudioTrigger.detect(
                samples,
                config(videoReadySampleIndex = 400, acceptAfterSampleIndex = 640),
                anchor(),
            ),
        )

        assertEquals(760L, result.event.sampleIndex)
        assertTrue(result.event.triggerRatio > 4.0)
    }

    @Test
    fun mouthPopStyleAmbientDeltaBelowOldAbsoluteFloorTriggers() {
        val samples = ShortArray(900) { 70 }
        samples[560] = 1_850

        val result = assertInstanceOf(
            ImpactAudioTriggerResult.Detected::class.java,
            ImpactAudioTrigger.detect(
                samples,
                config(videoReadySampleIndex = 300, acceptAfterSampleIndex = 420),
                anchor(),
            ),
        )

        assertEquals(560L, result.event.sampleIndex)
        assertTrue(result.event.peakAmplitude < 4_000)
    }

    @Test
    fun quietRoomBreathOverNearSilentBaselineDoesNotFalseTrigger() {
        val samples = ShortArray(900) { index ->
            (if (index % 2 == 0) 6 else -6).toShort()
        }
        samples[520] = 120

        val result = assertInstanceOf(
            ImpactAudioTriggerResult.NoImpact::class.java,
            ImpactAudioTrigger.detect(
                samples,
                config(videoReadySampleIndex = 300, acceptAfterSampleIndex = 420),
                anchor(),
            ),
        )

        assertTrue(result.strongestPostReadyRatio > 1.0)
        assertTrue(result.strongestPostReadyPeakDelta < result.minimumPeakDelta)
    }

    @Test
    fun moderateAmbientSubPopTransientDoesNotFalseTrigger() {
        val samples = ShortArray(900) { index ->
            (if (index % 2 == 0) 210 else -210).toShort()
        }
        samples[560] = 350

        val result = assertInstanceOf(
            ImpactAudioTriggerResult.NoImpact::class.java,
            ImpactAudioTrigger.detect(
                samples,
                config(
                    thresholdMultiplier = 2.5,
                    minimumPeakDelta = 100,
                    videoReadySampleIndex = 300,
                    acceptAfterSampleIndex = 420,
                ),
                anchor(),
            ),
        )

        assertTrue(result.strongestPostReadyPeakDelta >= 100.0)
        assertTrue(result.strongestPostReadyRatio < 2.5)
    }

    @Test
    fun deviceBackedDeltaAroundOneHundredTriggersWhenPostReady() {
        val samples = ShortArray(900) { 25 }
        repeat(4) { samples[560 + it] = 132 }

        val result = assertInstanceOf(
            ImpactAudioTriggerResult.Detected::class.java,
            ImpactAudioTrigger.detect(
                samples,
                config(
                    thresholdMultiplier = 2.5,
                    minimumPeakDelta = 100,
                    videoReadySampleIndex = 300,
                    acceptAfterSampleIndex = 420,
                ),
                anchor(),
            ),
        )

        assertEquals(560L, result.event.sampleIndex)
        assertTrue(result.event.peakDelta >= 100.0)
        assertTrue(result.event.triggerRatio >= 2.5)
    }

    @Test
    fun streamingMatchesBatchForDetectedEventAcrossChunkBoundaries() {
        val samples = ShortArray(1_100) { 25 }
        repeat(4) { samples[560 + it] = 132 }

        assertStreamingMatchesBatch(
            samples = samples,
            chunkSizes = listOf(137, 211, 53, 701),
            config = config(
                thresholdMultiplier = 2.5,
                minimumPeakDelta = 100,
                videoReadySampleIndex = 300,
                acceptAfterSampleIndex = 420,
            ),
        )
    }

    @Test
    fun streamingMatchesBatchForPhaseDiagnosticsAndNoImpactStopSample() {
        val samples = ShortArray(900) { 80 }
        samples[180] = 2_000
        samples[460] = 2_000
        samples[760] = 180

        assertStreamingMatchesBatch(
            samples = samples,
            chunkSizes = listOf(111, 97, 203, 489),
            config = config(
                videoReadySampleIndex = 300,
                acceptAfterSampleIndex = 650,
                actionableSampleCount = 200,
            ),
        )
    }

    @Test
    fun streamingWorkCounterProcessesEachWindowOnce() {
        val samples = ShortArray(330_240) { 25 }
        val detector = ImpactAudioStreamingDetector(
            config(
                baselineSampleCount = 960,
                triggerWindowSampleCount = 96,
                thresholdMultiplier = 2.5,
                minimumPeakDelta = 100,
                maxArmSamples = samples.size,
                actionableSampleCount = 240_000,
                videoReadySampleIndex = 33_230,
                acceptAfterSampleIndex = 88_518,
            ),
            anchor(),
        )

        var offset = 0
        while (offset < samples.size) {
            val read = minOf(1024, samples.size - offset)
            detector.feed(samples, offset, read)
            offset += read
        }
        val result = assertInstanceOf(ImpactAudioTriggerResult.NoImpact::class.java, detector.finish())

        val possibleWindowsAtStop = result.scannedSamples - 960 - 96 + 1
        assertEquals(possibleWindowsAtStop, detector.evaluatedWindowCount)
        assertTrue(detector.evaluatedWindowCount < samples.size)
        assertEquals(240_000, result.actionableSamples)
    }

    @Test
    fun noImpactDiagnosticsSeparatePreReadyBlankedAndBelowThreshold() {
        val samples = ShortArray(900) { 80 }
        samples[180] = 2_000
        samples[460] = 2_000
        samples[760] = 180

        val result = assertInstanceOf(
            ImpactAudioTriggerResult.NoImpact::class.java,
            ImpactAudioTrigger.detect(
                samples,
                config(videoReadySampleIndex = 300, acceptAfterSampleIndex = 650),
                anchor(),
            ),
        )

        assertTrue(result.preReadyTransientCount > 0)
        assertTrue(result.blankedTransientCount > 0)
        assertTrue(result.belowThresholdTransientCount > 0)
        assertTrue(result.actionableSamples > 0)
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
        val badPhase = ImpactAudioTrigger.detect(
            latePop,
            config(videoReadySampleIndex = 300, acceptAfterSampleIndex = 200),
            anchor(),
        )

        assertInstanceOf(ImpactAudioTriggerResult.NoImpact::class.java, noImpact)
        assertInstanceOf(ImpactAudioTriggerResult.ResourceFailure::class.java, invalid)
        assertInstanceOf(ImpactAudioTriggerResult.ResourceFailure::class.java, badPhase)
    }

    private fun config(
        sampleRateHz: Int = 48_000,
        baselineSampleCount: Int = 120,
        triggerWindowSampleCount: Int = 4,
        thresholdMultiplier: Double = 4.0,
        cooldownSampleCount: Int = 96,
        maxArmSamples: Int = 48_000,
        minimumPeakDelta: Int = 600,
        minimumBaselineRms: Double = 25.0,
        actionableSampleCount: Int = 24_000,
        videoReadySampleIndex: Long = 0L,
        acceptAfterSampleIndex: Long = 0L,
    ): ImpactAudioTriggerConfig =
        ImpactAudioTriggerConfig(
            sampleRateHz = sampleRateHz,
            baselineSampleCount = baselineSampleCount,
            triggerWindowSampleCount = triggerWindowSampleCount,
            thresholdMultiplier = thresholdMultiplier,
            minimumPeakDelta = minimumPeakDelta,
            minimumBaselineRms = minimumBaselineRms,
            cooldownSampleCount = cooldownSampleCount,
            maxArmSamples = maxArmSamples,
            actionableSampleCount = actionableSampleCount,
            videoReadySampleIndex = videoReadySampleIndex,
            acceptAfterSampleIndex = acceptAfterSampleIndex,
        )

    private fun assertStreamingMatchesBatch(
        samples: ShortArray,
        chunkSizes: List<Int>,
        config: ImpactAudioTriggerConfig,
    ) {
        val batch = ImpactAudioTrigger.detect(samples, config, anchor())
        val detector = ImpactAudioStreamingDetector(config, anchor())
        var terminal: ImpactAudioTriggerResult? = null
        var offset = 0
        var chunkIndex = 0
        while (offset < samples.size && terminal == null) {
            val chunkSize = chunkSizes[chunkIndex % chunkSizes.size]
            val read = minOf(chunkSize, samples.size - offset)
            terminal = detector.feed(samples, offset, read)
            offset += read
            chunkIndex += 1
        }
        val streaming = terminal ?: detector.finish()

        assertResultEquals(batch, streaming)
    }

    private fun assertResultEquals(
        expected: ImpactAudioTriggerResult,
        actual: ImpactAudioTriggerResult,
    ) {
        assertEquals(expected::class.java, actual::class.java)
        when (expected) {
            is ImpactAudioTriggerResult.Detected -> {
                val actualDetected = actual as ImpactAudioTriggerResult.Detected
                assertEquals(expected.event.sampleIndex, actualDetected.event.sampleIndex)
                assertEquals(expected.event.elapsedRealtimeNanos, actualDetected.event.elapsedRealtimeNanos)
                assertEquals(expected.event.peakAmplitude, actualDetected.event.peakAmplitude)
                assertEquals(expected.event.baselineRms, actualDetected.event.baselineRms)
                assertEquals(expected.event.triggerRatio, actualDetected.event.triggerRatio)
                assertEquals(expected.event.peakDelta, actualDetected.event.peakDelta)
                assertEquals(expected.event.offsetMillis, actualDetected.event.offsetMillis)
            }
            is ImpactAudioTriggerResult.NoImpact -> {
                val actualNoImpact = actual as ImpactAudioTriggerResult.NoImpact
                assertEquals(expected.scannedSamples, actualNoImpact.scannedSamples)
                assertEquals(expected.actionableSamples, actualNoImpact.actionableSamples)
                assertEquals(expected.preReadyTransientCount, actualNoImpact.preReadyTransientCount)
                assertEquals(expected.blankedTransientCount, actualNoImpact.blankedTransientCount)
                assertEquals(expected.belowThresholdTransientCount, actualNoImpact.belowThresholdTransientCount)
                assertEquals(expected.strongestPostReadyPeakDelta, actualNoImpact.strongestPostReadyPeakDelta)
                assertEquals(expected.strongestPostReadyRatio, actualNoImpact.strongestPostReadyRatio)
                assertEquals(expected.minimumPeakDelta, actualNoImpact.minimumPeakDelta)
                assertEquals(expected.minimumBaselineRms, actualNoImpact.minimumBaselineRms)
            }
            is ImpactAudioTriggerResult.PermissionDenied -> {
                val actualPermission = actual as ImpactAudioTriggerResult.PermissionDenied
                assertEquals(expected.message, actualPermission.message)
            }
            is ImpactAudioTriggerResult.ResourceFailure -> {
                val actualFailure = actual as ImpactAudioTriggerResult.ResourceFailure
                assertEquals(expected.message, actualFailure.message)
            }
        }
    }

    private fun anchor(maxAnchorAgeNanos: Long = 1_000_000_000L): ImpactAudioClockAnchor =
        ImpactAudioClockAnchor(
            framePosition = 100L,
            nanoTime = 1_000_000_000L,
            sampleRateHz = 48_000,
            maxAnchorAgeNanos = maxAnchorAgeNanos,
        )
}
