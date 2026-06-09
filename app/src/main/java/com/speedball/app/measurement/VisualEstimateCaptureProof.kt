package com.speedball.app.measurement

import kotlin.math.roundToInt

/**
 * Attempt-scoped diagnostic evidence from the direct visual-estimate capture.
 *
 * This proof is not a strict measurement proof and intentionally carries no
 * speed, angle, distance, or trajectory values. It exists only to show the
 * bounded frames and detector evidence that explain a success/no-read/failure.
 */
data class VisualEstimateCaptureProof(
    val attemptId: Long,
    val capturedFrameCount: Int,
    val frameAvailableCallbackCount: Int,
    val captureResultCallbackCount: Int,
    val uniqueSensorTimestampCount: Int,
    val readbackWidth: Int,
    val readbackHeight: Int,
    val detectorSummary: VisualEstimateDetectorSummary,
    val frames: List<VisualEstimateProofFrame>,
    val sourceKind: String = "DIRECT_READBACK",
    val sourceWidth: Int = readbackWidth,
    val sourceHeight: Int = readbackHeight,
    val workingWidth: Int = readbackWidth,
    val workingHeight: Int = readbackHeight,
    val decodedFrameCount: Int? = null,
    val requestedFps: Int? = null,
    val dropGateVerdict: String? = null,
    val cadenceGateVerdict: String? = null,
    val windowStartUs: Long? = null,
    val windowEndUs: Long? = null,
    val impactFrameIndex: Int? = null,
    val anchorErrorNanos: Long? = null,
    val preImpactMarginFrames: Int? = null,
    val decodeWallClockMillis: Long? = null,
    val sourceValidityVerdict: String? = null,
) {
    val hasCapturedFrames: Boolean get() = capturedFrameCount > 0

    fun withAttemptId(attemptId: Long): VisualEstimateCaptureProof =
        copy(attemptId = attemptId)
}

/**
 * One bounded thumbnail plus real detector overlays from the estimator trace.
 *
 * [thumbnailArgbPixels] is a low-resolution diagnostic copy from the processed
 * readback frame. It must not be treated as a full-resolution photo or as
 * exportable measurement evidence.
 */
class VisualEstimateProofFrame(
    val frameIndex: Int,
    val timestampSeconds: Double,
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
    val thumbnailArgbPixels: IntArray,
    val roi: VisualEstimateProofRect?,
    val candidates: List<VisualEstimateProofBlob>,
    val selected: VisualEstimateProofBlob?,
    val originalFrameIndex: Int = frameIndex,
) {
    val thumbnailHash: Int get() = thumbnailArgbPixels.contentHashCode()

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is VisualEstimateProofFrame &&
            frameIndex == other.frameIndex &&
            originalFrameIndex == other.originalFrameIndex &&
            timestampSeconds == other.timestampSeconds &&
            thumbnailWidth == other.thumbnailWidth &&
            thumbnailHeight == other.thumbnailHeight &&
            thumbnailArgbPixels.contentEquals(other.thumbnailArgbPixels) &&
            roi == other.roi &&
            candidates == other.candidates &&
            selected == other.selected

    override fun hashCode(): Int {
        var result = frameIndex
        result = 31 * result + originalFrameIndex
        result = 31 * result + timestampSeconds.hashCode()
        result = 31 * result + thumbnailWidth
        result = 31 * result + thumbnailHeight
        result = 31 * result + thumbnailArgbPixels.contentHashCode()
        result = 31 * result + (roi?.hashCode() ?: 0)
        result = 31 * result + candidates.hashCode()
        result = 31 * result + (selected?.hashCode() ?: 0)
        return result
    }
}

/** Bounded detector-count summary for one visual-estimate attempt. */
data class VisualEstimateDetectorSummary(
    val processedFrameCount: Int,
    val candidateFrameCount: Int,
    val candidateBlobCount: Int,
    val selectedSampleCount: Int,
    val noReadReason: VisualEstimateNoReadReason?,
    val noReadMessage: String?,
)

/** Rectangle in proof thumbnail coordinates. */
data class VisualEstimateProofRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
)

/** Candidate or selected blob overlay in proof thumbnail coordinates. */
data class VisualEstimateProofBlob(
    val centroidX: Float,
    val centroidY: Float,
    val bounds: VisualEstimateProofRect,
)

/** Estimator-owned bounded detector trace for proof rendering and diagnostics. */
data class VisualEstimateDetectorTrace(
    val detectorConfig: BlobDetectionConfig,
    val frames: List<VisualEstimateDetectorFrameTrace>,
    val noReadReason: VisualEstimateNoReadReason? = null,
    val noReadMessage: String? = null,
) {
    val detectorSummary: VisualEstimateDetectorSummary
        get() = VisualEstimateDetectorSummary(
            processedFrameCount = frames.size,
            candidateFrameCount = frames.count { it.candidateCount > 0 },
            candidateBlobCount = frames.sumOf { it.candidateCount },
            selectedSampleCount = frames.count { it.selectedBlob != null },
            noReadReason = noReadReason,
            noReadMessage = noReadMessage,
        )

    fun withOutcome(outcome: VisualEstimateOutcome): VisualEstimateDetectorTrace =
        when (outcome) {
            is VisualEstimateOutcome.Success -> copy(noReadReason = null, noReadMessage = null)
            is VisualEstimateOutcome.NoRead -> copy(noReadReason = outcome.reason, noReadMessage = outcome.message)
        }
}

/** Bounded trace for one processed frame. Candidate overlays are capped. */
data class VisualEstimateDetectorFrameTrace(
    val frameIndex: Int,
    val timestampSeconds: Double,
    val roi: RegionOfInterest?,
    val candidateCount: Int,
    val candidates: List<Blob>,
    val selectedBlob: Blob? = null,
    val originalFrameIndex: Int = frameIndex,
)

/** Estimate outcome plus the detector trace produced while estimating. */
data class VisualEstimateFramePipelineResult(
    val outcome: VisualEstimateOutcome,
    val detectorTrace: VisualEstimateDetectorTrace,
)

/**
 * Builds bounded proof thumbnails from the same processed frames that fed the estimator.
 */
object VisualEstimateCaptureProofBuilder {
    const val DEFAULT_MAX_PROOF_FRAMES: Int = 8
    const val DEFAULT_THUMBNAIL_MAX_WIDTH: Int = 160
    const val DEFAULT_THUMBNAIL_MAX_HEIGHT: Int = 90

    fun empty(
        attemptId: Long,
        frameAvailableCallbackCount: Int,
        captureResultCallbackCount: Int,
        uniqueSensorTimestampCount: Int,
        readbackWidth: Int,
        readbackHeight: Int,
        detectorConfig: BlobDetectionConfig,
        noReadReason: VisualEstimateNoReadReason?,
        noReadMessage: String?,
        sourceKind: String = "DIRECT_READBACK",
        sourceWidth: Int = readbackWidth,
        sourceHeight: Int = readbackHeight,
        workingWidth: Int = readbackWidth,
        workingHeight: Int = readbackHeight,
        decodedFrameCount: Int? = null,
        requestedFps: Int? = null,
        dropGateVerdict: String? = null,
        cadenceGateVerdict: String? = null,
        windowStartUs: Long? = null,
        windowEndUs: Long? = null,
        impactFrameIndex: Int? = null,
        anchorErrorNanos: Long? = null,
        preImpactMarginFrames: Int? = null,
        decodeWallClockMillis: Long? = null,
        sourceValidityVerdict: String? = null,
    ): VisualEstimateCaptureProof =
        VisualEstimateCaptureProof(
            attemptId = attemptId,
            capturedFrameCount = 0,
            frameAvailableCallbackCount = frameAvailableCallbackCount,
            captureResultCallbackCount = captureResultCallbackCount,
            uniqueSensorTimestampCount = uniqueSensorTimestampCount,
            readbackWidth = readbackWidth,
            readbackHeight = readbackHeight,
            detectorSummary = VisualEstimateDetectorSummary(
                processedFrameCount = 0,
                candidateFrameCount = 0,
                candidateBlobCount = 0,
                selectedSampleCount = 0,
                noReadReason = noReadReason,
                noReadMessage = noReadMessage,
            ),
            frames = emptyList(),
            sourceKind = sourceKind,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            workingWidth = workingWidth,
            workingHeight = workingHeight,
            decodedFrameCount = decodedFrameCount,
            requestedFps = requestedFps,
            dropGateVerdict = dropGateVerdict,
            cadenceGateVerdict = cadenceGateVerdict,
            windowStartUs = windowStartUs,
            windowEndUs = windowEndUs,
            impactFrameIndex = impactFrameIndex,
            anchorErrorNanos = anchorErrorNanos,
            preImpactMarginFrames = preImpactMarginFrames,
            decodeWallClockMillis = decodeWallClockMillis,
            sourceValidityVerdict = sourceValidityVerdict,
        )

    fun build(
        attemptId: Long,
        frames: List<RgbFrame>,
        frameAvailableCallbackCount: Int,
        captureResultCallbackCount: Int,
        uniqueSensorTimestampCount: Int,
        readbackWidth: Int,
        readbackHeight: Int,
        trace: VisualEstimateDetectorTrace,
        maxProofFrames: Int = DEFAULT_MAX_PROOF_FRAMES,
        sourceKind: String = "DIRECT_READBACK",
        sourceWidth: Int = readbackWidth,
        sourceHeight: Int = readbackHeight,
        workingWidth: Int = readbackWidth,
        workingHeight: Int = readbackHeight,
        decodedFrameCount: Int? = null,
        requestedFps: Int? = null,
        dropGateVerdict: String? = null,
        cadenceGateVerdict: String? = null,
        windowStartUs: Long? = null,
        windowEndUs: Long? = null,
        impactFrameIndex: Int? = null,
        anchorErrorNanos: Long? = null,
        preImpactMarginFrames: Int? = null,
        decodeWallClockMillis: Long? = null,
        sourceValidityVerdict: String? = null,
    ): VisualEstimateCaptureProof {
        if (frames.isEmpty()) {
            return empty(
                attemptId = attemptId,
                frameAvailableCallbackCount = frameAvailableCallbackCount,
                captureResultCallbackCount = captureResultCallbackCount,
                uniqueSensorTimestampCount = uniqueSensorTimestampCount,
                readbackWidth = readbackWidth,
                readbackHeight = readbackHeight,
                detectorConfig = trace.detectorConfig,
                noReadReason = trace.noReadReason,
                noReadMessage = trace.noReadMessage,
                sourceKind = sourceKind,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                workingWidth = workingWidth,
                workingHeight = workingHeight,
                decodedFrameCount = decodedFrameCount,
                requestedFps = requestedFps,
                dropGateVerdict = dropGateVerdict,
                cadenceGateVerdict = cadenceGateVerdict,
                windowStartUs = windowStartUs,
                windowEndUs = windowEndUs,
                impactFrameIndex = impactFrameIndex,
                anchorErrorNanos = anchorErrorNanos,
                preImpactMarginFrames = preImpactMarginFrames,
                decodeWallClockMillis = decodeWallClockMillis,
                sourceValidityVerdict = sourceValidityVerdict,
            )
        }
        val traceByFrameIndex = trace.frames.associateBy { it.frameIndex }
        val selectedTrackIndices = trace.frames
            .filter { it.selectedBlob != null }
            .map { it.frameIndex }
            .filter { it in frames.indices }
        val selectedIndices = (selectedTrackIndices + selectProofFrameIndices(frames.size, maxProofFrames))
            .distinct()
            .take(maxProofFrames)
            .filter { traceByFrameIndex.containsKey(it) }
        return VisualEstimateCaptureProof(
            attemptId = attemptId,
            capturedFrameCount = frames.size,
            frameAvailableCallbackCount = frameAvailableCallbackCount,
            captureResultCallbackCount = captureResultCallbackCount,
            uniqueSensorTimestampCount = uniqueSensorTimestampCount,
            readbackWidth = readbackWidth,
            readbackHeight = readbackHeight,
            detectorSummary = trace.detectorSummary,
            frames = selectedIndices.map { frameIndex ->
                val frame = frames[frameIndex]
                val thumbnail = frame.toThumbnail()
                val frameTrace = traceByFrameIndex[frameIndex]
                VisualEstimateProofFrame(
                    frameIndex = frameIndex,
                    timestampSeconds = frame.timestampSeconds,
                    thumbnailWidth = thumbnail.width,
                    thumbnailHeight = thumbnail.height,
                    thumbnailArgbPixels = thumbnail.argbPixels,
                    roi = frameTrace?.roi?.toProofRect(frame.width, frame.height, thumbnail.width, thumbnail.height),
                    candidates = frameTrace?.candidates.orEmpty()
                        .map { it.toProofBlob(frame.width, frame.height, thumbnail.width, thumbnail.height) },
                    selected = frameTrace?.selectedBlob
                        ?.toProofBlob(frame.width, frame.height, thumbnail.width, thumbnail.height),
                    originalFrameIndex = frameTrace?.originalFrameIndex ?: frameIndex,
                )
            },
            sourceKind = sourceKind,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            workingWidth = workingWidth,
            workingHeight = workingHeight,
            decodedFrameCount = decodedFrameCount,
            requestedFps = requestedFps,
            dropGateVerdict = dropGateVerdict,
            cadenceGateVerdict = cadenceGateVerdict,
            windowStartUs = windowStartUs,
            windowEndUs = windowEndUs,
            impactFrameIndex = impactFrameIndex,
            anchorErrorNanos = anchorErrorNanos,
            preImpactMarginFrames = preImpactMarginFrames,
            decodeWallClockMillis = decodeWallClockMillis,
            sourceValidityVerdict = sourceValidityVerdict,
        )
    }

    fun buildFromThumbnails(
        attemptId: Long,
        scannedFrameCount: Int,
        frameAvailableCallbackCount: Int,
        captureResultCallbackCount: Int,
        uniqueSensorTimestampCount: Int,
        readbackWidth: Int,
        readbackHeight: Int,
        trace: VisualEstimateDetectorTrace,
        thumbnails: List<VisualEstimateProofThumbnailFrame>,
        sourceKind: String,
        sourceWidth: Int,
        sourceHeight: Int,
        workingWidth: Int,
        workingHeight: Int,
        decodedFrameCount: Int?,
        requestedFps: Int?,
        dropGateVerdict: String?,
        cadenceGateVerdict: String?,
        windowStartUs: Long? = null,
        windowEndUs: Long? = null,
        impactFrameIndex: Int? = null,
        anchorErrorNanos: Long? = null,
        preImpactMarginFrames: Int? = null,
        decodeWallClockMillis: Long? = null,
        sourceValidityVerdict: String? = null,
    ): VisualEstimateCaptureProof {
        val thumbnailsByPosition = thumbnails.associateBy { it.compactPosition }
        return VisualEstimateCaptureProof(
            attemptId = attemptId,
            capturedFrameCount = scannedFrameCount,
            frameAvailableCallbackCount = frameAvailableCallbackCount,
            captureResultCallbackCount = captureResultCallbackCount,
            uniqueSensorTimestampCount = uniqueSensorTimestampCount,
            readbackWidth = readbackWidth,
            readbackHeight = readbackHeight,
            detectorSummary = trace.detectorSummary,
            frames = trace.frames.mapNotNull { frameTrace ->
                val thumbnail = thumbnailsByPosition[frameTrace.frameIndex] ?: return@mapNotNull null
                VisualEstimateProofFrame(
                    frameIndex = frameTrace.frameIndex,
                    timestampSeconds = frameTrace.timestampSeconds,
                    thumbnailWidth = thumbnail.thumbnailWidth,
                    thumbnailHeight = thumbnail.thumbnailHeight,
                    thumbnailArgbPixels = thumbnail.thumbnailArgbPixels,
                    roi = frameTrace.roi?.toProofRect(thumbnail.sourceWidth, thumbnail.sourceHeight, thumbnail.thumbnailWidth, thumbnail.thumbnailHeight),
                    candidates = frameTrace.candidates.map {
                        it.toProofBlob(thumbnail.sourceWidth, thumbnail.sourceHeight, thumbnail.thumbnailWidth, thumbnail.thumbnailHeight)
                    },
                    selected = frameTrace.selectedBlob?.toProofBlob(
                        thumbnail.sourceWidth,
                        thumbnail.sourceHeight,
                        thumbnail.thumbnailWidth,
                        thumbnail.thumbnailHeight,
                    ),
                    originalFrameIndex = frameTrace.originalFrameIndex,
                )
            },
            sourceKind = sourceKind,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            workingWidth = workingWidth,
            workingHeight = workingHeight,
            decodedFrameCount = decodedFrameCount,
            requestedFps = requestedFps,
            dropGateVerdict = dropGateVerdict,
            cadenceGateVerdict = cadenceGateVerdict,
            windowStartUs = windowStartUs,
            windowEndUs = windowEndUs,
            impactFrameIndex = impactFrameIndex,
            anchorErrorNanos = anchorErrorNanos,
            preImpactMarginFrames = preImpactMarginFrames,
            decodeWallClockMillis = decodeWallClockMillis,
            sourceValidityVerdict = sourceValidityVerdict,
        )
    }

    fun selectProofFrameIndices(frameCount: Int, maxProofFrames: Int): List<Int> {
        if (frameCount <= 0 || maxProofFrames <= 0) return emptyList()
        if (frameCount <= maxProofFrames) return (0 until frameCount).toList()
        if (maxProofFrames == 1) return listOf(0)
        val indices = (0 until maxProofFrames)
            .map { order -> (order * (frameCount - 1).toDouble() / (maxProofFrames - 1)).roundToInt() }
            .distinct()
            .toMutableList()
        var fill = 0
        while (indices.size < maxProofFrames && fill < frameCount) {
            if (fill !in indices) indices += fill
            fill += 1
        }
        return indices.sorted()
    }
}

/** Downscaled proof pixels retained by streaming sources without full frame ARGB. */
data class VisualEstimateProofThumbnailFrame(
    val compactPosition: Int,
    val originalFrameIndex: Int,
    val timestampSeconds: Double,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val thumbnailWidth: Int,
    val thumbnailHeight: Int,
    val thumbnailArgbPixels: IntArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is VisualEstimateProofThumbnailFrame &&
                compactPosition == other.compactPosition &&
                originalFrameIndex == other.originalFrameIndex &&
                timestampSeconds == other.timestampSeconds &&
                sourceWidth == other.sourceWidth &&
                sourceHeight == other.sourceHeight &&
                thumbnailWidth == other.thumbnailWidth &&
                thumbnailHeight == other.thumbnailHeight &&
                thumbnailArgbPixels.contentEquals(other.thumbnailArgbPixels)

    override fun hashCode(): Int {
        var result = compactPosition
        result = 31 * result + originalFrameIndex
        result = 31 * result + timestampSeconds.hashCode()
        result = 31 * result + sourceWidth
        result = 31 * result + sourceHeight
        result = 31 * result + thumbnailWidth
        result = 31 * result + thumbnailHeight
        result = 31 * result + thumbnailArgbPixels.contentHashCode()
        return result
    }
}

private data class ThumbnailFrame(
    val width: Int,
    val height: Int,
    val argbPixels: IntArray,
)

private fun RgbFrame.toThumbnail(): ThumbnailFrame {
    val scale = minOf(
        VisualEstimateCaptureProofBuilder.DEFAULT_THUMBNAIL_MAX_WIDTH.toDouble() / width,
        VisualEstimateCaptureProofBuilder.DEFAULT_THUMBNAIL_MAX_HEIGHT.toDouble() / height,
        1.0,
    )
    val targetWidth = maxOf(1, (width * scale).roundToInt())
    val targetHeight = maxOf(1, (height * scale).roundToInt())
    if (targetWidth == width && targetHeight == height) {
        return ThumbnailFrame(width, height, argbPixels.copyOf())
    }
    val target = IntArray(targetWidth * targetHeight)
    for (y in 0 until targetHeight) {
        val sourceY = (y * height / targetHeight).coerceIn(0, height - 1)
        for (x in 0 until targetWidth) {
            val sourceX = (x * width / targetWidth).coerceIn(0, width - 1)
            target[y * targetWidth + x] = argbPixels[sourceY * width + sourceX]
        }
    }
    return ThumbnailFrame(targetWidth, targetHeight, target)
}

private fun RegionOfInterest.toProofRect(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): VisualEstimateProofRect =
    VisualEstimateProofRect(
        left = left * targetWidth.toFloat() / sourceWidth,
        top = top * targetHeight.toFloat() / sourceHeight,
        right = rightExclusive * targetWidth.toFloat() / sourceWidth,
        bottom = bottomExclusive * targetHeight.toFloat() / sourceHeight,
    )

private fun Blob.toProofBlob(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
): VisualEstimateProofBlob =
    VisualEstimateProofBlob(
        centroidX = centroid.xPx.toFloat() * targetWidth / sourceWidth,
        centroidY = centroid.yPx.toFloat() * targetHeight / sourceHeight,
        bounds = VisualEstimateProofRect(
            left = bounds.left * targetWidth.toFloat() / sourceWidth,
            top = bounds.top * targetHeight.toFloat() / sourceHeight,
            right = (bounds.rightInclusive + 1) * targetWidth.toFloat() / sourceWidth,
            bottom = (bounds.bottomInclusive + 1) * targetHeight.toFloat() / sourceHeight,
        ),
    )
