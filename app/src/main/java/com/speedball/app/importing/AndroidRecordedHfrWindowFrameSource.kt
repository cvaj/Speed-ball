package com.speedball.app.importing

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.speedball.app.capture.ContainerTimeWindow
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

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
    val sourceMotionScoutSelection: RecordedHfrMotionScoutSelection?,
) : ImportFrameSource, ImportFrameSourceScanLimitTerminal {
    private val bufferInfo = MediaCodec.BufferInfo()
    private var inputDone = false
    private var outputDone = false
    private var closed = false
    private var decodedInWindowFrameCount = 0
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

    override fun isTerminalAtScannedFrameCount(scannedFrameCount: Int): Boolean =
        scannedFrameCount >= window.maxFrames

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
        if (ptsUs > window.windowEndUs || decodedInWindowFrameCount >= window.maxFrames) {
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
        val windowFrameIndex = decodedInWindowFrameCount
        val scout = sourceMotionScoutSelection
        if (scout != null && windowFrameIndex > scout.denseEndIndexInclusive) {
            codec.releaseOutputBuffer(outputIndex, false)
            outputDone = true
            return null
        }
        decodedInWindowFrameCount += 1
        if (scout != null && windowFrameIndex < scout.denseStartIndex) {
            previousPtsUs = ptsUs
            codec.releaseOutputBuffer(outputIndex, false)
            if (isEos) outputDone = true
            return null
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
                frameIndex = windowFrameIndex,
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
        if (isEos || decodedInWindowFrameCount >= window.maxFrames) outputDone = true
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
            emittedFrameCount = if (sourceMotionScoutSelection == null) emittedFrameCount else decodedInWindowFrameCount,
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
        private const val SCOUT_MAX_COMPONENTS_PER_FRAME = 4

        fun create(
            file: File,
            window: ContainerTimeWindow,
            targetWidth: Int,
            targetHeight: Int,
            maxDecodeWallClockMillis: Long,
            sourceMotionScoutConfig: RecordedHfrMotionScoutConfig = RecordedHfrMotionScoutConfig(),
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
            val deadlineNanos = startedAt + maxDecodeWallClockMillis * 1_000_000L
            val scoutSelection = if (sourceMotionScoutConfig.enabled) {
                scoutMotionWindow(
                    file = file,
                    window = window,
                    config = sourceMotionScoutConfig,
                    deadlineNanos = deadlineNanos,
                )
            } else {
                null
            }
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            return try {
                val session = openDecoderSession(file, extractor)
                val sourceWidth = session.sourceWidth
                val sourceHeight = session.sourceHeight
                if (sourceWidth <= 0 || sourceHeight <= 0) {
                    runCatching { session.codec.stop() }
                    runCatching { session.codec.release() }
                    return releaseAndNoRead(extractor, codec, "Recorded-HFR video dimensions are invalid.")
                }
                codec = session.codec
                extractor.seekTo(window.windowStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
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
                        deadlineNanos = deadlineNanos,
                        sourceMotionScoutSelection = scoutSelection,
                    ),
                )
            } catch (_: RuntimeException) {
                releaseAndNoRead(extractor, codec, "Recorded-HFR MediaCodec window source could not be opened.")
            }
        }

        private data class DecoderSession(
            val format: MediaFormat,
            val codec: MediaCodec,
            val sourceWidth: Int,
            val sourceHeight: Int,
        )

        private data class ScoutFrame(
            val windowIndex: Int,
            val luma: IntArray,
        )

        private data class ScoutHit(
            val frameIndex: Int,
            val centroidX: Double,
            val centroidY: Double,
            val areaPx: Int,
        )

        private data class ScoutRun(val hits: List<ScoutHit>) {
            val frameCount: Int get() = hits.size
            val startIndex: Int get() = hits.first().frameIndex
            val endIndexInclusive: Int get() = hits.last().frameIndex
            val travelPx: Double get() = hypot(
                hits.last().centroidX - hits.first().centroidX,
                hits.last().centroidY - hits.first().centroidY,
            )
            val meanAreaPx: Double get() = hits.sumOf { it.areaPx }.toDouble() / hits.size.toDouble()
        }

        private data class ScoutRunBuilder(val hits: MutableList<ScoutHit>) {
            val last: ScoutHit get() = hits.last()
            fun add(hit: ScoutHit) {
                hits += hit
            }
            fun build(): ScoutRun = ScoutRun(hits.toList())
        }

        private data class ScoutComponent(
            val areaPx: Int,
            val centroidX: Double,
            val centroidY: Double,
            val width: Int,
            val height: Int,
        ) {
            val axisRatio: Double get() = max(width, height).toDouble() / min(width, height).coerceAtLeast(1).toDouble()
        }

        private fun openDecoderSession(file: File, extractor: MediaExtractor): DecoderSession {
            extractor.setDataSource(file.absolutePath)
            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("video/") == true
            } ?: throw IllegalStateException("Recorded-HFR file does not contain a video track.")
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("Recorded-HFR video MIME type is unavailable.")
            val sourceWidth = format.getInteger(MediaFormat.KEY_WIDTH)
            val sourceHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
            extractor.selectTrack(trackIndex)
            val codec = MediaCodec.createDecoderByType(mime).apply {
                configure(format, null, null, 0)
                start()
            }
            return DecoderSession(
                format = format,
                codec = codec,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
            )
        }

        private fun scoutMotionWindow(
            file: File,
            window: ContainerTimeWindow,
            config: RecordedHfrMotionScoutConfig,
            deadlineNanos: Long,
        ): RecordedHfrMotionScoutSelection? {
            if (config.validate() != null) return null
            val extractor = MediaExtractor()
            var codec: MediaCodec? = null
            return try {
                val session = openDecoderSession(file, extractor)
                codec = session.codec
                extractor.seekTo(window.windowStartUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                drainScoutFrames(
                    extractor = extractor,
                    codec = codec,
                    window = window,
                    sourceWidth = session.sourceWidth,
                    sourceHeight = session.sourceHeight,
                    config = config,
                    deadlineNanos = deadlineNanos,
                )?.let { frames ->
                    selectMotionScoutWindow(
                        frames = frames,
                        scoutWidth = min(config.scoutWidth, session.sourceWidth).coerceAtLeast(1),
                        scoutHeight = min(config.scoutHeight, session.sourceHeight).coerceAtLeast(1),
                        config = config,
                    )
                }
            } catch (_: RuntimeException) {
                null
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { extractor.release() }
            }
        }

        private fun drainScoutFrames(
            extractor: MediaExtractor,
            codec: MediaCodec,
            window: ContainerTimeWindow,
            sourceWidth: Int,
            sourceHeight: Int,
            config: RecordedHfrMotionScoutConfig,
            deadlineNanos: Long,
        ): List<ScoutFrame>? {
            val info = MediaCodec.BufferInfo()
            val frames = mutableListOf<ScoutFrame>()
            var inputDone = false
            var outputDone = false
            var outputColorFormat = UNKNOWN_INT
            var outputStride = UNKNOWN_INT
            var outputSliceHeight = UNKNOWN_INT
            var decodedWindowIndex = 0
            val scoutWidth = min(config.scoutWidth, sourceWidth).coerceAtLeast(1)
            val scoutHeight = min(config.scoutHeight, sourceHeight).coerceAtLeast(1)
            while (!outputDone) {
                if (System.nanoTime() > deadlineNanos) return null
                if (!inputDone) {
                    val inputIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                    if (inputIndex >= 0) {
                        val inputBuffer = codec.getInputBuffer(inputIndex) ?: return null
                        val sampleTimeUs = extractor.sampleTime
                        if (sampleTimeUs < 0L || sampleTimeUs > window.windowEndUs) {
                            codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val sampleSize = extractor.readSampleData(inputBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inputIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inputIndex, 0, sampleSize, sampleTimeUs, extractor.sampleFlags)
                                extractor.advance()
                            }
                        }
                    }
                }
                when (val outputIndex = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val format = codec.outputFormat
                        outputColorFormat = format.safeInt(MediaFormat.KEY_COLOR_FORMAT) ?: UNKNOWN_INT
                        outputStride = format.safeInt(MediaFormat.KEY_STRIDE) ?: UNKNOWN_INT
                        outputSliceHeight = format.safeInt(MediaFormat.KEY_SLICE_HEIGHT) ?: UNKNOWN_INT
                    }
                    MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                    else -> if (outputIndex >= 0) {
                        val ptsUs = info.presentationTimeUs
                        val isEos = (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0
                        if (ptsUs < window.windowStartUs) {
                            codec.releaseOutputBuffer(outputIndex, false)
                            if (isEos) outputDone = true
                            continue
                        }
                        if (ptsUs > window.windowEndUs || decodedWindowIndex >= window.maxFrames) {
                            codec.releaseOutputBuffer(outputIndex, false)
                            outputDone = true
                            continue
                        }
                        val outputFormat = codec.outputFormat
                        outputColorFormat = outputFormat.safeInt(MediaFormat.KEY_COLOR_FORMAT) ?: outputColorFormat
                        outputStride = outputFormat.safeInt(MediaFormat.KEY_STRIDE) ?: outputStride
                        outputSliceHeight = outputFormat.safeInt(MediaFormat.KEY_SLICE_HEIGHT) ?: outputSliceHeight
                        val outputBuffer = codec.getOutputBuffer(outputIndex) ?: return null
                        val outputLimit = info.offset + info.size
                        if (info.offset < 0 || outputLimit > outputBuffer.capacity()) return null
                        val readable = outputBuffer.duplicate().apply {
                            position(info.offset)
                            limit(outputLimit)
                        }.slice()
                        val luma = when (
                            val conversion = RecordedHfrByteBufferYuvConverter.convertLuma(
                                buffer = readable,
                                sourceWidth = sourceWidth,
                                sourceHeight = sourceHeight,
                                targetWidth = scoutWidth,
                                targetHeight = scoutHeight,
                                colorFormat = outputColorFormat,
                                stride = outputStride,
                                sliceHeight = outputSliceHeight,
                            )
                        ) {
                            is ImportValidationResult.NoRead -> null
                            is ImportValidationResult.Success -> conversion.value
                        }
                        codec.releaseOutputBuffer(outputIndex, false)
                        if (luma == null) return null
                        frames += ScoutFrame(decodedWindowIndex, luma)
                        decodedWindowIndex += 1
                        if (isEos || decodedWindowIndex >= window.maxFrames) outputDone = true
                    }
                }
            }
            return frames.takeIf { it.size >= config.minRunFrameCount }
        }

        private fun selectMotionScoutWindow(
            frames: List<ScoutFrame>,
            scoutWidth: Int,
            scoutHeight: Int,
            config: RecordedHfrMotionScoutConfig,
        ): RecordedHfrMotionScoutSelection? {
            if (frames.size <= config.minDenseFrameCount) return null
            val pixelCount = frames.first().luma.size
            if (pixelCount <= 0 || frames.any { it.luma.size != pixelCount }) return null
            val background = medianScoutBackground(frames.map { it.luma }, pixelCount)
            if (scoutWidth * scoutHeight != pixelCount) return null
            val hitsByFrame = frames.map { frame ->
                scoutFrameHits(
                    luma = frame.luma,
                    background = background,
                    frameIndex = frame.windowIndex,
                    width = scoutWidth,
                    height = scoutHeight,
                    config = config,
                )
            }
            val runs = buildScoutRuns(hitsByFrame, config)
            val best = runs
                .filter {
                    it.frameCount >= config.minRunFrameCount &&
                        it.travelPx >= config.minRunTravelPx &&
                        it.meanAreaPx >= config.minRunMeanAreaPx
                }
                .maxWithOrNull(
                    compareBy<ScoutRun> { it.travelPx }
                        .thenBy { it.frameCount }
                        .thenBy { it.meanAreaPx },
                ) ?: return null
            val dense = paddedDenseRange(best, frames.last().windowIndex + 1, config)
            return RecordedHfrMotionScoutSelection(
                denseStartIndex = dense.first,
                denseEndIndexInclusive = dense.last,
                runStartIndex = best.startIndex,
                runEndIndexInclusive = best.endIndexInclusive,
                runFrameCount = best.frameCount,
                runTravelPx = best.travelPx,
                meanComponentAreaPx = best.meanAreaPx,
                sourceFrameCount = frames.size,
            )
        }

        private fun medianScoutBackground(frames: List<IntArray>, pixelCount: Int): IntArray {
            val values = IntArray(frames.size)
            return IntArray(pixelCount) { pixel ->
                frames.forEachIndexed { index, frame -> values[index] = frame[pixel] }
                values.sort()
                values[values.size / 2]
            }
        }

        private fun scoutFrameHits(
            luma: IntArray,
            background: IntArray,
            frameIndex: Int,
            width: Int,
            height: Int,
            config: RecordedHfrMotionScoutConfig,
        ): List<ScoutHit> {
            val mask = BooleanArray(luma.size)
            for (index in luma.indices) {
                if (abs(luma[index] - background[index]) >= config.lumaDifferenceThreshold) {
                    mask[index] = true
                }
            }
            return collectScoutComponents(mask, width, height)
                .asSequence()
                .filter { it.areaPx >= config.minComponentAreaPx }
                .filter { it.axisRatio <= config.maxComponentAxisRatio }
                .sortedByDescending { it.areaPx }
                .take(SCOUT_MAX_COMPONENTS_PER_FRAME)
                .map { component ->
                    ScoutHit(
                        frameIndex = frameIndex,
                        centroidX = component.centroidX,
                        centroidY = component.centroidY,
                        areaPx = component.areaPx,
                    )
                }
                .toList()
        }

        private fun collectScoutComponents(mask: BooleanArray, width: Int, height: Int): List<ScoutComponent> {
            val visited = BooleanArray(mask.size)
            val queue = IntArray(mask.size)
            val components = mutableListOf<ScoutComponent>()
            for (start in mask.indices) {
                if (!mask[start] || visited[start]) continue
                var head = 0
                var tail = 0
                queue[tail++] = start
                visited[start] = true
                var area = 0
                var sumX = 0.0
                var sumY = 0.0
                var minX = Int.MAX_VALUE
                var minY = Int.MAX_VALUE
                var maxX = Int.MIN_VALUE
                var maxY = Int.MIN_VALUE
                while (head < tail) {
                    val index = queue[head++]
                    val x = index % width
                    val y = index / width
                    area += 1
                    sumX += x
                    sumY += y
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                    tail = addScoutNeighbor(x - 1, y, width, height, mask, visited, queue, tail)
                    tail = addScoutNeighbor(x + 1, y, width, height, mask, visited, queue, tail)
                    tail = addScoutNeighbor(x, y - 1, width, height, mask, visited, queue, tail)
                    tail = addScoutNeighbor(x, y + 1, width, height, mask, visited, queue, tail)
                }
                components += ScoutComponent(
                    areaPx = area,
                    centroidX = sumX / area.toDouble(),
                    centroidY = sumY / area.toDouble(),
                    width = maxX - minX + 1,
                    height = maxY - minY + 1,
                )
            }
            return components
        }

        private fun addScoutNeighbor(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            mask: BooleanArray,
            visited: BooleanArray,
            queue: IntArray,
            tail: Int,
        ): Int {
            if (x !in 0 until width || y !in 0 until height) return tail
            val index = y * width + x
            if (!mask[index] || visited[index]) return tail
            visited[index] = true
            queue[tail] = index
            return tail + 1
        }

        private fun buildScoutRuns(
            hitsByFrame: List<List<ScoutHit>>,
            config: RecordedHfrMotionScoutConfig,
        ): List<ScoutRun> {
            val active = mutableListOf<ScoutRunBuilder>()
            val completed = mutableListOf<ScoutRun>()
            hitsByFrame.forEach { hits ->
                val frameIndex = hits.firstOrNull()?.frameIndex
                if (frameIndex != null) {
                    val expired = active.filter { frameIndex - it.last.frameIndex > config.maxRunGapFrames + 1 }
                    completed += expired.map { it.build() }
                    active.removeAll(expired.toSet())
                }
                val claimed = mutableSetOf<ScoutRunBuilder>()
                hits.forEach { hit ->
                    val run = active
                        .filterNot { it in claimed }
                        .filter { hit.frameIndex - it.last.frameIndex in 1..(config.maxRunGapFrames + 1) }
                        .minByOrNull { hypot(hit.centroidX - it.last.centroidX, hit.centroidY - it.last.centroidY) }
                    if (run == null) {
                        active += ScoutRunBuilder(mutableListOf(hit))
                    } else {
                        run.add(hit)
                        claimed += run
                    }
                }
            }
            completed += active.map { it.build() }
            return completed
        }

        private fun paddedDenseRange(
            run: ScoutRun,
            frameCount: Int,
            config: RecordedHfrMotionScoutConfig,
        ): IntRange {
            var start = (run.startIndex - config.densePaddingFrames).coerceAtLeast(0)
            var end = (run.endIndexInclusive + config.densePaddingFrames).coerceAtMost(frameCount - 1)
            while (end - start + 1 < config.minDenseFrameCount && (start > 0 || end < frameCount - 1)) {
                if (start > 0) start -= 1
                if (end - start + 1 >= config.minDenseFrameCount) break
                if (end < frameCount - 1) end += 1
            }
            return start..end
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

        private fun MediaFormat.safeInt(key: String): Int? =
            if (containsKey(key)) getInteger(key) else null
    }
}
