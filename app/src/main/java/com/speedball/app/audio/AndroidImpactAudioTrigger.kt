package com.speedball.app.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTimestamp
import android.media.MediaRecorder
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Single-owner Android microphone reader for the impact marker.
 *
 * It uses `AudioRecord.getTimestamp(AudioTimestamp.TIMEBASE_BOOTTIME)` so
 * detected sample indexes map to the same elapsed-realtime base as S10+
 * `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` camera timestamps. Raw PCM stays in
 * memory only and is dropped on every terminal path.
 */
class AndroidImpactAudioTrigger(private val context: Context) {
    private val executor: ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "speed-ball-impact-audio").apply { isDaemon = true }
    }
    private val cancelled = AtomicBoolean(false)
    @Volatile private var audioRecord: AudioRecord? = null

    fun start(
        config: ImpactAudioTriggerConfig,
        onArmed: (ImpactAudioArmedSession) -> Unit,
        onResult: (ImpactAudioTriggerResult) -> Unit,
    ) {
        cancelled.set(false)
        executor.execute {
            onResult(readUntilImpact(config, onArmed))
        }
    }

    fun cancel() {
        cancelled.set(true)
        releaseRecorder()
    }

    private fun readUntilImpact(
        config: ImpactAudioTriggerConfig,
        onArmed: (ImpactAudioArmedSession) -> Unit,
    ): ImpactAudioTriggerResult {
        config.validate()?.let { return it }
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return ImpactAudioTriggerResult.PermissionDenied("Microphone permission is required for impact-triggered recording.")
        }
        val minBuffer = AudioRecord.getMinBufferSize(
            config.sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) return ImpactAudioTriggerResult.ResourceFailure("Impact audio buffer size is unavailable.")
        val chunkSize = maxOf(minBuffer / 2, config.triggerWindowSampleCount * 4, 256)
        val recorder = createRecorder(config, minBuffer, chunkSize)
            ?: return ImpactAudioTriggerResult.ResourceFailure("Impact audio recorder could not be created.")
        audioRecord = recorder
        val chunk = ShortArray(chunkSize)
        val videoReadySampleIndex = AtomicLong(config.videoReadySampleIndex)
        val acceptAfterSampleIndex = AtomicLong(config.acceptAfterSampleIndex)
        return try {
            recorder.startRecording()
            val anchor = readHardwareAnchor(recorder, config)
                ?: return ImpactAudioTriggerResult.ResourceFailure("Impact audio hardware timestamp anchor is unavailable.")
            val detector = ImpactAudioStreamingDetector(config, anchor)
            onArmed(
                ImpactAudioArmedSession(
                    anchor = anchor,
                    markVideoReady = { sampleIndex -> videoReadySampleIndex.set(sampleIndex.coerceAtLeast(0L)) },
                    enableAcceptance = { sampleIndex -> acceptAfterSampleIndex.set(sampleIndex.coerceAtLeast(0L)) },
                ),
            )
            while (!cancelled.get() && detector.writtenSampleCount < currentStopSample(config, acceptAfterSampleIndex.get())) {
                detector.updatePhaseBoundaries(
                    videoReadySampleIndex = videoReadySampleIndex.get(),
                    acceptAfterSampleIndex = acceptAfterSampleIndex.get(),
                )?.let { return it }
                val remaining = currentStopSample(config, acceptAfterSampleIndex.get()) - detector.writtenSampleCount
                if (remaining <= 0) break
                val read = recorder.read(chunk, 0, minOf(chunk.size, remaining))
                if (read < 0) return ImpactAudioTriggerResult.ResourceFailure("Impact audio read failed.")
                if (read == 0) continue
                detector.updatePhaseBoundaries(
                    videoReadySampleIndex = videoReadySampleIndex.get(),
                    acceptAfterSampleIndex = acceptAfterSampleIndex.get(),
                )?.let { return it }
                when (val result = detector.feed(chunk, 0, read)) {
                    is ImpactAudioTriggerResult.Detected,
                    is ImpactAudioTriggerResult.ResourceFailure -> return result
                    is ImpactAudioTriggerResult.PermissionDenied -> return result
                    is ImpactAudioTriggerResult.NoImpact -> Unit
                    null -> Unit
                }
            }
            detector.updatePhaseBoundaries(
                videoReadySampleIndex = videoReadySampleIndex.get(),
                acceptAfterSampleIndex = acceptAfterSampleIndex.get(),
            )?.let { return it }
            detector.finish()
        } catch (_: RuntimeException) {
            ImpactAudioTriggerResult.ResourceFailure("Impact audio capture failed.")
        } finally {
            releaseRecorder()
        }
    }

    private fun currentStopSample(
        config: ImpactAudioTriggerConfig,
        acceptAfterSampleIndex: Long,
    ): Int =
        if (acceptAfterSampleIndex == Long.MAX_VALUE) {
            config.maxArmSamples
        } else {
            minOf(config.maxArmSamples, (acceptAfterSampleIndex + config.actionableSampleCount).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        }

    private fun readHardwareAnchor(
        recorder: AudioRecord,
        config: ImpactAudioTriggerConfig,
    ): ImpactAudioClockAnchor? {
        val timestamp = AudioTimestamp()
        repeat(8) {
            if (recorder.getTimestamp(timestamp, AudioTimestamp.TIMEBASE_BOOTTIME) == AudioRecord.SUCCESS) {
                return ImpactAudioClockAnchor(
                    framePosition = timestamp.framePosition,
                    nanoTime = timestamp.nanoTime,
                    sampleRateHz = config.sampleRateHz,
                    maxAnchorAgeNanos = config.maxArmSamples.toLong() * 1_000_000_000L / config.sampleRateHz,
                )
            }
            Thread.sleep(10L)
        }
        return null
    }

    private fun createRecorder(
        config: ImpactAudioTriggerConfig,
        minBuffer: Int,
        chunkSize: Int,
    ): AudioRecord? {
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            return null
        }
        val bufferSize = maxOf(minBuffer, chunkSize * 2)
        for (source in listOf(MediaRecorder.AudioSource.UNPROCESSED, MediaRecorder.AudioSource.MIC)) {
            val recorder = try {
                AudioRecord(
                    source,
                    config.sampleRateHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize,
                )
            } catch (_: SecurityException) {
                null
            } catch (_: RuntimeException) {
                null
            }
            if (recorder?.state == AudioRecord.STATE_INITIALIZED) return recorder
            recorder?.release()
        }
        return null
    }

    private fun releaseRecorder() {
        val recorder = audioRecord
        audioRecord = null
        if (recorder != null) {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }
    }
}
