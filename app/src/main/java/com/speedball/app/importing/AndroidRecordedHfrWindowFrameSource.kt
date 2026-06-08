package com.speedball.app.importing

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.speedball.app.capture.ContainerTimeWindow
import java.io.File

/**
 * Android MediaCodec-backed recorded-HFR window source.
 *
 * The source owns extractor and decoder resources and emits at most one
 * converted working-resolution frame from each [nextFrame] call. It consumes
 * codec byte-buffer output only, preserving the recorded-HFR crash-fix
 * invariant that no render-surface plane API is reachable from this route.
 */
class AndroidRecordedHfrWindowFrameSource private constructor(
    private val extractor: MediaExtractor,
    private val codec: MediaCodec,
    private val window: ContainerTimeWindow,
    private val sourceWidth: Int,
    private val sourceHeight: Int,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val startedAtNanos: Long,
    private val deadlineNanos: Long,
) : ImportFrameSource {
    private val bufferInfo = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var closed = false
    private var emittedFrameCount = 0
    private var syncPrefixFrameCount = 0
    private var emittedFirstPtsUs: Long? = null
    private var emittedLastPtsUs: Long? = null
    private var previousPtsUs: Long? = null
    private var timedOut = false
    private var terminalNoReadMessage: String? = null
    var outputColorFormat: Int? = null
        private set
    var outputStride: Int? = null
        private set
    var outputSliceHeight: Int? = null
        private set

    val proof: RecordedHfrDecodedWindowProof
        get() = currentProof()

    fun terminalNoReadMessage(): String? = terminalNoReadMessage

    override fun nextFrame(): ImportVideoFrame? {
        if (closed || outputDone || terminalNoReadMessage != null) return null
        while (!outputDone && terminalNoReadMessage == null) {
            if (System.nanoTime() > deadlineNanos) {
                timedOut = true
                outputDone = true
                return null
            }
            feedInputIfNeeded()
            when (val outputIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> captureOutputFormat(codec.outputFormat)
                MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                else -> if (outputIndex >= 0) {
                    val frame = drainOutput(outputIndex)
                    if (frame != null) return frame
                }
            }
        }
        return null
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { codec.stop() }
        runCatching { codec.release() }
        runCatching { extractor.release() }
    }

    private fun feedInputIfNeeded() {
        if (inputDone) return
        val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (inputIndex < 0) return
        val inputBuffer = codec.getInputBuffer(inputIndex)
        if (inputBuffer == null) {
            terminalNoReadMessage = "Recorded-HFR decoder input buffer was unavailable."
            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
            return
        }
        val sampleTimeUs = extractor.sampleTime
        if (sampleTimeUs < 0L || sampleTimeUs > window.windowEndUs) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
            return
        }
        val sampleSize = extractor.readSampleData(inputBuffer, 0)
        if (sampleSize < 0) {
            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
            return
        }
        codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, extractor.sampleFlags)
        extractor.advance()
    }

    private fun drainOutput(outputIndex: Int): ImportVideoFrame? {
        val ptsUs = bufferInfo.presentationTimeUs
        val isEos = (bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
        if (ptsUs < window.windowStartUs) {
            syncPrefixFrameCount += 1
            codec.releaseOutputBuffer(outputIndex, false)
            if (isEos) outputDone = true
            return null
        }
        if (ptsUs > window.windowEndUs || emittedFrameCount >= window.maxFrames) {
            codec.releaseOutputBuffer(outputIndex, false)
            outputDone = true
            return null
        }
        previousPtsUs?.let { previous ->
            if (ptsUs <= previous) {
                terminalNoReadMessage = "Recorded-HFR window PTS were not strictly increasing."
                codec.releaseOutputBuffer(outputIndex, false)
                outputDone = true
                return null
            }
        }
        val outputFormat = codec.outputFormat
        captureOutputFormat(outputFormat)
        val outputBuffer = codec.getOutputBuffer(outputIndex)
        if (outputBuffer == null) {
            terminalNoReadMessage = "Recorded-HFR decoder output buffer was unavailable."
            codec.releaseOutputBuffer(outputIndex, false)
            outputDone = true
            return null
        }
        val outputLimit = bufferInfo.offset + bufferInfo.size
        if (bufferInfo.offset < 0 || outputLimit > outputBuffer.capacity()) {
            terminalNoReadMessage = "Recorded-HFR decoder output buffer bounds were invalid."
            codec.releaseOutputBuffer(outputIndex, false)
            outputDone = true
            return null
        }
        val readable = outputBuffer.duplicate().apply {
            position(bufferInfo.offset)
            limit(outputLimit)
        }.slice()
        val frame = when (
            val conversion = RecordedHfrByteBufferYuvConverter.convert(
                buffer = readable,
                frameIndex = emittedFrameCount,
                ptsUs = ptsUs,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                targetWidth = targetWidth,
                targetHeight = targetHeight,
                colorFormat = outputColorFormat ?: UNKNOWN_INT,
                stride = outputStride ?: UNKNOWN_INT,
                sliceHeight = outputSliceHeight ?: UNKNOWN_INT,
            )
        ) {
            is ImportValidationResult.NoRead -> {
                terminalNoReadMessage = conversion.message
                null
            }
            is ImportValidationResult.Success -> conversion.value
        }
        codec.releaseOutputBuffer(outputIndex, false)
        if (frame == null) {
            outputDone = true
            return null
        }
        previousPtsUs = ptsUs
        emittedFirstPtsUs = emittedFirstPtsUs ?: ptsUs
        emittedLastPtsUs = ptsUs
        emittedFrameCount += 1
        if (isEos || emittedFrameCount >= window.maxFrames) outputDone = true
        return frame
    }

    private fun captureOutputFormat(outputFormat: MediaFormat) {
        outputColorFormat = outputFormat.safeInt(MediaFormat.KEY_COLOR_FORMAT)
        outputStride = outputFormat.safeInt(MediaFormat.KEY_STRIDE)
        outputSliceHeight = outputFormat.safeInt(MediaFormat.KEY_SLICE_HEIGHT)
    }

    private fun currentProof(): RecordedHfrDecodedWindowProof =
        RecordedHfrDecodedWindowProof(
            requestedWindowStartUs = window.windowStartUs,
            requestedWindowEndUs = window.windowEndUs,
            emittedFrameCount = emittedFrameCount,
            emittedFirstPtsUs = emittedFirstPtsUs,
            emittedLastPtsUs = emittedLastPtsUs,
            syncPrefixFrameCount = syncPrefixFrameCount,
            decodeWallClockMillis = (System.nanoTime() - startedAtNanos) / 1_000_000L,
            timedOut = timedOut,
        )

    private fun MediaFormat.safeInt(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    companion object {
        private const val DEQUEUE_TIMEOUT_US = 5_000L
        private const val UNKNOWN_INT = -1

        fun create(
            file: File,
            window: ContainerTimeWindow,
            targetWidth: Int,
            targetHeight: Int,
            maxDecodeWallClockMillis: Long,
        ): ImportValidationResult<AndroidRecordedHfrWindowFrameSource> {
            if (!file.isFile || file.length() <= 0L) {
                return noRead("Recorded-HFR window video file is missing or empty.")
            }
            if (targetWidth <= 0 || targetHeight <= 0 || maxDecodeWallClockMillis <= 0L) {
                return noRead("Recorded-HFR window decode target and timeout must be valid.")
            }
            if (window.windowStartUs < 0L || window.windowEndUs <= window.windowStartUs || window.maxFrames <= 0) {
                return noRead("Recorded-HFR requested window bounds must be valid.")
            }
            val startedAt = System.nanoTime()
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            return try {
                extractor.setDataSource(file.absolutePath)
                val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                    extractor.getTrackFormat(index)
                        .getString(MediaFormat.KEY_MIME)
                        ?.startsWith("video/") == true
                } ?: return releaseAndNoRead(extractor, codec, "Recorded-HFR file does not contain a video track.")
                val format = extractor.getTrackFormat(trackIndex)
                val mime = format.getString(MediaFormat.KEY_MIME)
                    ?: return releaseAndNoRead(extractor, codec, "Recorded-HFR video MIME type is unavailable.")
                val sourceWidth = format.getInteger(MediaFormat.KEY_WIDTH)
                val sourceHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
                if (sourceWidth <= 0 || sourceHeight <= 0) {
                    return releaseAndNoRead(extractor, codec, "Recorded-HFR video dimensions are invalid.")
                }
                extractor.selectTrack(trackIndex)
                extractor.seekTo(window.windowStartUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
                codec = MediaCodec.createDecoderByType(mime).apply {
                    configure(format, null, null, 0)
                    start()
                }
                ImportValidationResult.Success(
                    AndroidRecordedHfrWindowFrameSource(
                        extractor = extractor,
                        codec = codec,
                        window = window,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        targetWidth = targetWidth,
                        targetHeight = targetHeight,
                        startedAtNanos = startedAt,
                        deadlineNanos = startedAt + maxDecodeWallClockMillis * 1_000_000L,
                    ),
                )
            } catch (_: RuntimeException) {
                releaseAndNoRead(extractor, codec, "Recorded-HFR MediaCodec window source could not be opened.")
            }
        }

        private fun releaseAndNoRead(
            extractor: MediaExtractor,
            codec: MediaCodec?,
            message: String,
        ): ImportValidationResult.NoRead {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            runCatching { extractor.release() }
            return noRead(message)
        }

        private fun noRead(message: String): ImportValidationResult.NoRead =
            ImportValidationResult.NoRead(ImportNoReadReason.NO_TRUSTWORTHY_TIMING, message)
    }
}
