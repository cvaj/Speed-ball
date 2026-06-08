package com.speedball.app.importing

import com.speedball.app.measurement.Blob
import com.speedball.app.measurement.BlobCandidateDetectionOutcome
import com.speedball.app.measurement.BlobDetector
import com.speedball.app.measurement.EstimateTimingBasis
import com.speedball.app.measurement.MeasurementCalibrationState
import com.speedball.app.measurement.RecordedHfrPhysicalBallDetector
import com.speedball.app.measurement.RecordedHfrPhysicalDetectionOutcome
import com.speedball.app.measurement.RecordedHfrPhysicalDetectorConfig
import com.speedball.app.measurement.RecordedHfrPhysicalFrameMask
import com.speedball.app.measurement.RecordedHfrPhysicalFrameMaskOutcome
import com.speedball.app.measurement.RecordedHfrMotionBallDetector
import com.speedball.app.measurement.RecordedHfrMotionDetectionOutcome
import com.speedball.app.measurement.RecordedHfrMotionDetectorConfig
import com.speedball.app.measurement.RgbFrame
import com.speedball.app.measurement.TrackExtractionConfig
import com.speedball.app.measurement.VisualEstimateCandidateFrame
import com.speedball.app.measurement.VisualEstimateCandidateReducer
import com.speedball.app.measurement.VisualEstimateCandidateReductionOutcome
import com.speedball.app.measurement.VisualEstimateCaptureProofBuilder
import com.speedball.app.measurement.VisualEstimateConfidence
import com.speedball.app.measurement.VisualEstimateDetectorFrameTrace
import com.speedball.app.measurement.VisualEstimateDetectorTrace
import com.speedball.app.measurement.VisualEstimateDiagnostics
import com.speedball.app.measurement.VisualEstimateFramePipelineConfig
import com.speedball.app.measurement.VisualEstimateNoReadReason
import com.speedball.app.measurement.VisualEstimateOutcome
import com.speedball.app.measurement.VisualEstimatePipeline
import com.speedball.app.measurement.VisualEstimateProofThumbnailFrame
import kotlin.math.roundToInt

/**
 * Resource bounds for streaming recorded-HFR estimate processing.
 *
 * [maxScannedFrames] counts every decoded frame pulled from the source. That
 * count feeds the recorded-HFR drop gate. [maxRetainedCandidateFrames] counts
 * only frames where the detector found at least one blob, and it must never be
 * used as a capture/drop proxy. Candidate records retain no full-frame ARGB.
 */
data class RecordedHfrStreamingEstimateConfig(
    val frameIntervalSeconds: Double,
    val calibration: MeasurementCalibrationState,
    val framePipelineConfig: VisualEstimateFramePipelineConfig,
    val maxScannedFrames: Int,
    val maxRetainedCandidateFrames: Int,
    val maxProofFrames: Int = VisualEstimateCaptureProofBuilder.DEFAULT_MAX_PROOF_FRAMES,
    val proofThumbnailMaxWidth: Int = VisualEstimateCaptureProofBuilder.DEFAULT_THUMBNAIL_MAX_WIDTH,
    val proofThumbnailMaxHeight: Int = VisualEstimateCaptureProofBuilder.DEFAULT_THUMBNAIL_MAX_HEIGHT,
    val timingMode: RecordedHfrStreamingTimingMode = RecordedHfrStreamingTimingMode.FRAME_INDEX_INTERVAL,
    val proofOnly: Boolean = false,
    val physicalDetectorConfig: RecordedHfrPhysicalDetectorConfig? = null,
    val motionDetectorConfig: RecordedHfrMotionDetectorConfig? = null,
)

/** Estimate-only timing mode for recorded-HFR streaming candidates. */
enum class RecordedHfrStreamingTimingMode {
    FRAME_INDEX_INTERVAL,
    CONTAINER_PTS_DELTAS,
}

/**
 * Streaming recorded-HFR estimate result.
 *
 * [scannedFrameCount] is the decoded-frame count used by the capture/drop gate.
 * [retainedCandidateFrameCount] is detector evidence only; a clip with few
 * yellow-ball candidate frames can still pass the capture gate if every
 * decoded frame was scanned.
 */
data class RecordedHfrStreamingEstimateResult(
    val outcome: VisualEstimateOutcome,
    val timing: ImportTimingReconciliation?,
    val detectorTrace: VisualEstimateDetectorTrace,
    val proofThumbnails: List<VisualEstimateProofThumbnailFrame>,
    val scannedFrameCount: Int,
    val retainedCandidateFrameCount: Int,
    val retainedProofFrameCount: Int,
    val candidateBlobCount: Int,
    val selectedSampleCount: Int,
    val sourceWidth: Int,
    val sourceHeight: Int,
    val workingWidth: Int,
    val workingHeight: Int,
    val sourceValidity: RecordedHfrSourceValidity = RecordedHfrSourceValidity.NotEvaluated,
)

/** Bounded luma proof that the decoded source window is not black/near-black. */
data class RecordedHfrSourceValidity(
    val scannedFrameCount: Int,
    val roiPixelCount: Long,
    val meanLuma: Double,
    val brightestFrameMeanLuma: Double,
    val brightPixelFraction: Double,
    val verdict: String,
) {
    val passes: Boolean get() = verdict == "PASS"

    companion object {
        val NotEvaluated = RecordedHfrSourceValidity(
            scannedFrameCount = 0,
            roiPixelCount = 0L,
            meanLuma = 0.0,
            brightestFrameMeanLuma = 0.0,
            brightPixelFraction = 0.0,
            verdict = "NO_READ_SOURCE_VALIDITY_NOT_EVALUATED",
        )
    }
}

/**
 * Streams decoded recorded-HFR frames through the existing blob detector and
 * candidate reducer without retaining full-resolution frame pixels.
 */
object RecordedHfrStreamingEstimate {
    fun estimate(
        source: ImportFrameSource,
        config: RecordedHfrStreamingEstimateConfig,
        cancellationSignal: ImportCancellationSignal = ImportCancellationSignal { false },
    ): RecordedHfrStreamingEstimateResult {
        val configError = config.validate()
        if (configError != null) {
            return configError
        }
        val candidates = mutableListOf<VisualEstimateCandidateFrame>()
        val candidateThumbnails = mutableListOf<VisualEstimateProofThumbnailFrame>()
        val sourceThumbnails = mutableListOf<VisualEstimateProofThumbnailFrame>()
        val physicalMasks = mutableListOf<RecordedHfrPhysicalFrameMask>()
        val motionFrames = mutableListOf<RgbFrame>()
        val motionOriginalFrameIndexes = mutableListOf<Int>()
        val motionPresentationTimestampNanos = mutableListOf<Long?>()
        val sourceValidityAccumulator = SourceValidityAccumulator()
        var scannedFrameCount = 0
        var candidateBlobCount = 0
        var previousFrameIndex: Int? = null
        var sourceWidth = 0
        var sourceHeight = 0
        var workingWidth = 0
        var workingHeight = 0
        var firstPresentationTimestampNanos: Long? = null
        return try {
            while (true) {
                if (cancellationSignal.isCancelled()) {
                    return resourceNoRead(
                        message = "Recorded-HFR streaming decode was cancelled.",
                        config = config,
                        candidates = candidates,
                        candidateThumbnails = candidateThumbnails,
                        scannedFrameCount = scannedFrameCount,
                        candidateBlobCount = candidateBlobCount,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        workingWidth = workingWidth,
                        workingHeight = workingHeight,
                    )
                }
                if (scannedFrameCount >= config.maxScannedFrames) {
                    return resourceNoRead(
                        message = "Recorded-HFR streaming decode exceeded the scanned-frame limit.",
                        config = config,
                        candidates = candidates,
                        candidateThumbnails = candidateThumbnails,
                        scannedFrameCount = scannedFrameCount,
                        candidateBlobCount = candidateBlobCount,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        workingWidth = workingWidth,
                        workingHeight = workingHeight,
                    )
                }
                val frame = source.nextFrame() ?: break
                if (cancellationSignal.isCancelled()) {
                    return resourceNoRead(
                        message = "Recorded-HFR streaming decode was cancelled after frame decode.",
                        config = config,
                        candidates = candidates,
                        candidateThumbnails = candidateThumbnails,
                        scannedFrameCount = scannedFrameCount,
                        candidateBlobCount = candidateBlobCount,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        workingWidth = workingWidth,
                        workingHeight = workingHeight,
                    )
                }
                validateFrame(frame, previousFrameIndex)?.let { noRead ->
                    return noReadResult(
                        outcome = noRead,
                        trace = buildTrace(
                            detectorConfig = config.framePipelineConfig.trackConfig.detectorConfig,
                            candidates = candidates,
                            selected = emptyList(),
                            outcome = noRead,
                        ),
                        proofThumbnails = selectProofThumbnails(candidateThumbnails, emptySet(), config.maxProofFrames),
                        scannedFrameCount = scannedFrameCount,
                        candidateFrameCount = candidates.size,
                        candidateBlobCount = candidateBlobCount,
                        selectedSampleCount = 0,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        workingWidth = workingWidth,
                        workingHeight = workingHeight,
                    )
                }
                previousFrameIndex = frame.frameIndex
                scannedFrameCount += 1
                sourceWidth = if (sourceWidth == 0) frame.width else sourceWidth
                sourceHeight = if (sourceHeight == 0) frame.height else sourceHeight
                workingWidth = frame.width
                workingHeight = frame.height

                val timestampSeconds = when (config.timingMode) {
                    RecordedHfrStreamingTimingMode.FRAME_INDEX_INTERVAL -> frame.frameIndex * config.frameIntervalSeconds
                    RecordedHfrStreamingTimingMode.CONTAINER_PTS_DELTAS -> {
                        val ptsNanos = frame.presentationTimestampNanos
                            ?: return noReadResult(
                                outcome = basicTimingNoRead("Recorded-HFR window frames must expose container PTS."),
                                trace = buildTrace(
                                    detectorConfig = config.framePipelineConfig.trackConfig.detectorConfig,
                                    candidates = candidates,
                                    selected = emptyList(),
                                    outcome = basicTimingNoRead("Recorded-HFR window frames must expose container PTS."),
                                ),
                                proofThumbnails = selectProofThumbnails(candidateThumbnails, emptySet(), config.maxProofFrames),
                                scannedFrameCount = scannedFrameCount,
                                candidateFrameCount = candidates.size,
                                candidateBlobCount = candidateBlobCount,
                                selectedSampleCount = 0,
                                sourceWidth = sourceWidth,
                                sourceHeight = sourceHeight,
                                workingWidth = workingWidth,
                                workingHeight = workingHeight,
                            )
                        val basePts = firstPresentationTimestampNanos ?: ptsNanos.also { firstPresentationTimestampNanos = it }
                        (ptsNanos - basePts) / 1_000_000_000.0
                    }
                }
                val rgbFrame = RgbFrame(
                    width = frame.width,
                    height = frame.height,
                    argbPixels = frame.argbPixels,
                    timestampSeconds = timestampSeconds,
                )
                sourceValidityAccumulator.add(frame, config.framePipelineConfig.trackConfig.detectorConfig.roi)
                if (sourceThumbnails.size < config.maxProofFrames) {
                    sourceThumbnails += frame.toProofThumbnail(
                        compactPosition = scannedFrameCount - 1,
                        timestampSeconds = timestampSeconds,
                        maxWidth = config.proofThumbnailMaxWidth,
                        maxHeight = config.proofThumbnailMaxHeight,
                    )
                }
                if (config.proofOnly) continue
                if (cancellationSignal.isCancelled()) {
                    return resourceNoRead(
                        message = "Recorded-HFR streaming decode was cancelled before candidate detection.",
                        config = config,
                        candidates = candidates,
                        candidateThumbnails = candidateThumbnails,
                        scannedFrameCount = scannedFrameCount,
                        candidateBlobCount = candidateBlobCount,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        workingWidth = workingWidth,
                        workingHeight = workingHeight,
                    )
                }
                if (config.motionDetectorConfig != null) {
                    motionFrames += RgbFrame(
                        width = rgbFrame.width,
                        height = rgbFrame.height,
                        argbPixels = rgbFrame.argbPixels.copyOf(),
                        timestampSeconds = rgbFrame.timestampSeconds,
                    )
                    motionOriginalFrameIndexes += frame.frameIndex
                    motionPresentationTimestampNanos += frame.presentationTimestampNanos
                    continue
                }
                if (config.physicalDetectorConfig != null) {
                    when (
                        val mask = RecordedHfrPhysicalBallDetector.buildFrameMask(
                            frame = rgbFrame,
                            compactPosition = scannedFrameCount - 1,
                            originalFrameIndex = frame.frameIndex,
                            presentationTimestampNanos = frame.presentationTimestampNanos,
                            detectorConfig = config.framePipelineConfig.trackConfig.detectorConfig,
                        )
                    ) {
                        is RecordedHfrPhysicalFrameMaskOutcome.Failure -> {
                            return noReadFromCandidates(
                                reason = mask.reason,
                                message = mask.message,
                                config = config,
                                candidates = candidates,
                                candidateThumbnails = candidateThumbnails.ifEmpty { sourceThumbnails },
                                scannedFrameCount = scannedFrameCount,
                                candidateBlobCount = candidateBlobCount,
                                sourceWidth = sourceWidth,
                                sourceHeight = sourceHeight,
                                workingWidth = workingWidth,
                                workingHeight = workingHeight,
                            )
                        }
                        is RecordedHfrPhysicalFrameMaskOutcome.Success -> physicalMasks += mask.value
                    }
                    continue
                }
                when (val detection = BlobDetector.detectCandidates(rgbFrame, config.framePipelineConfig.trackConfig.detectorConfig)) {
                    is BlobCandidateDetectionOutcome.Failure -> {
                        if (detection.reason == com.speedball.app.measurement.MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED) {
                            return resourceNoRead(
                                message = detection.message,
                                config = config,
                                candidates = candidates,
                                candidateThumbnails = candidateThumbnails,
                                scannedFrameCount = scannedFrameCount,
                                candidateBlobCount = candidateBlobCount,
                                sourceWidth = sourceWidth,
                                sourceHeight = sourceHeight,
                                workingWidth = workingWidth,
                                workingHeight = workingHeight,
                            )
                        }
                        return noReadFromCandidates(
                            reason = detection.reason.toVisualReason(),
                            message = detection.message,
                            config = config,
                            candidates = candidates,
                            candidateThumbnails = candidateThumbnails,
                            scannedFrameCount = scannedFrameCount,
                            candidateBlobCount = candidateBlobCount,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                            workingWidth = workingWidth,
                            workingHeight = workingHeight,
                        )
                    }
                    is BlobCandidateDetectionOutcome.Success -> {
                        if (detection.blobs.isNotEmpty()) {
                            val budget = config.framePipelineConfig.trackConfig.candidateReductionBudget
                            if (budget != null && detection.blobs.size > budget.maxBlobsPerFrame) {
                                return resourceNoRead(
                                    message = "Recorded-HFR frame ${frame.frameIndex} produced ${detection.blobs.size} candidate blobs, exceeding the per-frame cap ${budget.maxBlobsPerFrame}.",
                                    config = config,
                                    candidates = candidates,
                                    candidateThumbnails = candidateThumbnails,
                                    scannedFrameCount = scannedFrameCount,
                                    candidateBlobCount = candidateBlobCount,
                                    sourceWidth = sourceWidth,
                                    sourceHeight = sourceHeight,
                                    workingWidth = workingWidth,
                                    workingHeight = workingHeight,
                                )
                            }
                            if (budget != null && candidateBlobCount + detection.blobs.size > budget.maxTotalCandidateBlobs) {
                                return resourceNoRead(
                                    message = "Recorded-HFR window produced ${candidateBlobCount + detection.blobs.size} candidate blobs, exceeding the window cap ${budget.maxTotalCandidateBlobs}.",
                                    config = config,
                                    candidates = candidates,
                                    candidateThumbnails = candidateThumbnails,
                                    scannedFrameCount = scannedFrameCount,
                                    candidateBlobCount = candidateBlobCount,
                                    sourceWidth = sourceWidth,
                                    sourceHeight = sourceHeight,
                                    workingWidth = workingWidth,
                                    workingHeight = workingHeight,
                                )
                            }
                            if (candidates.size >= config.maxRetainedCandidateFrames) {
                                return resourceNoRead(
                                    message = "Recorded-HFR retained candidate frames exceeded the reviewed resource cap.",
                                    config = config,
                                    candidates = candidates,
                                    candidateThumbnails = candidateThumbnails,
                                    scannedFrameCount = scannedFrameCount,
                                    candidateBlobCount = candidateBlobCount,
                                    sourceWidth = sourceWidth,
                                    sourceHeight = sourceHeight,
                                    workingWidth = workingWidth,
                                    workingHeight = workingHeight,
                                )
                            }
                            val compactPosition = candidates.size
                            candidates += VisualEstimateCandidateFrame(
                                compactPosition = compactPosition,
                                originalFrameIndex = frame.frameIndex,
                                timestampSeconds = timestampSeconds,
                                width = frame.width,
                                height = frame.height,
                                blobs = detection.blobs,
                                presentationTimestampNanos = frame.presentationTimestampNanos,
                            )
                            candidateBlobCount += detection.blobs.size
                            candidateThumbnails += frame.toProofThumbnail(
                                compactPosition = compactPosition,
                                timestampSeconds = timestampSeconds,
                                maxWidth = config.proofThumbnailMaxWidth,
                                maxHeight = config.proofThumbnailMaxHeight,
                            )
                        }
                    }
                }
            }
            val sourceValidity = sourceValidityAccumulator.toSummary(scannedFrameCount)
            if (config.motionDetectorConfig != null && !config.proofOnly) {
                if (!sourceValidity.passes) {
                    return sourceInvalidNoRead(
                        config = config,
                        sourceThumbnails = sourceThumbnails,
                        sourceValidity = sourceValidity,
                        scannedFrameCount = scannedFrameCount,
                        candidateFrameCount = candidates.size,
                        candidateBlobCount = candidateBlobCount,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        workingWidth = workingWidth,
                        workingHeight = workingHeight,
                    )
                }
                when (
                    val motion = RecordedHfrMotionBallDetector.detect(
                        frames = motionFrames,
                        detectorConfig = config.framePipelineConfig.trackConfig.detectorConfig,
                        motionConfig = config.motionDetectorConfig,
                        originalFrameIndexes = motionOriginalFrameIndexes,
                        presentationTimestampNanos = motionPresentationTimestampNanos,
                        isCancelled = cancellationSignal::isCancelled,
                    )
                ) {
                    is RecordedHfrMotionDetectionOutcome.Failure -> {
                        return noReadFromCandidates(
                            reason = motion.reason,
                            message = motion.message,
                            config = config,
                            candidates = candidates,
                            candidateThumbnails = sourceThumbnails,
                            scannedFrameCount = scannedFrameCount,
                            candidateBlobCount = candidateBlobCount,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                            workingWidth = workingWidth,
                            workingHeight = workingHeight,
                            sourceValidity = sourceValidity,
                        )
                    }
                    is RecordedHfrMotionDetectionOutcome.Success -> {
                        val budget = config.framePipelineConfig.trackConfig.candidateReductionBudget
                        if (budget != null && motion.frames.any { it.blobs.size > budget.maxBlobsPerFrame }) {
                            return resourceNoRead(
                                message = "Recorded-HFR motion detector produced more blobs per frame than the reducer cap.",
                                config = config,
                                candidates = candidates,
                                candidateThumbnails = sourceThumbnails,
                                scannedFrameCount = scannedFrameCount,
                                candidateBlobCount = candidateBlobCount,
                                sourceWidth = sourceWidth,
                                sourceHeight = sourceHeight,
                                workingWidth = workingWidth,
                                workingHeight = workingHeight,
                            )
                        }
                        if (budget != null && motion.candidateBlobCount > budget.maxTotalCandidateBlobs) {
                            return resourceNoRead(
                                message = "Recorded-HFR motion detector produced ${motion.candidateBlobCount} candidate blobs, exceeding the window cap ${budget.maxTotalCandidateBlobs}.",
                                config = config,
                                candidates = candidates,
                                candidateThumbnails = sourceThumbnails,
                                scannedFrameCount = scannedFrameCount,
                                candidateBlobCount = candidateBlobCount,
                                sourceWidth = sourceWidth,
                                sourceHeight = sourceHeight,
                                workingWidth = workingWidth,
                                workingHeight = workingHeight,
                            )
                        }
                        if (motion.frames.size > config.maxRetainedCandidateFrames) {
                            return resourceNoRead(
                                message = "Recorded-HFR motion detector retained more candidate frames than the reviewed resource cap.",
                                config = config,
                                candidates = candidates,
                                candidateThumbnails = sourceThumbnails,
                                scannedFrameCount = scannedFrameCount,
                                candidateBlobCount = candidateBlobCount,
                                sourceWidth = sourceWidth,
                                sourceHeight = sourceHeight,
                                workingWidth = workingWidth,
                                workingHeight = workingHeight,
                            )
                        }
                        candidates += motion.frames
                        candidateBlobCount = motion.candidateBlobCount
                        candidateThumbnails += sourceThumbnails
                    }
                }
            }
            if (config.physicalDetectorConfig != null && !config.proofOnly) {
                when (
                    val physical = RecordedHfrPhysicalBallDetector.detect(
                        masks = physicalMasks,
                        detectorConfig = config.framePipelineConfig.trackConfig.detectorConfig,
                        physicalConfig = config.physicalDetectorConfig,
                        isCancelled = cancellationSignal::isCancelled,
                    )
                ) {
                    is RecordedHfrPhysicalDetectionOutcome.Failure -> {
                        return noReadFromCandidates(
                            reason = physical.reason,
                            message = physical.message,
                            config = config,
                            candidates = candidates,
                            candidateThumbnails = candidateThumbnails.ifEmpty { sourceThumbnails },
                            scannedFrameCount = scannedFrameCount,
                            candidateBlobCount = candidateBlobCount,
                            sourceWidth = sourceWidth,
                            sourceHeight = sourceHeight,
                            workingWidth = workingWidth,
                            workingHeight = workingHeight,
                        )
                    }
                    is RecordedHfrPhysicalDetectionOutcome.Success -> {
                        candidates += physical.frames
                        candidateBlobCount = physical.candidateBlobCount
                        candidateThumbnails += sourceThumbnails
                    }
                }
            }
            finish(
                config = config,
                candidates = candidates,
                candidateThumbnails = candidateThumbnails,
                sourceThumbnails = sourceThumbnails,
                sourceValidity = sourceValidity,
                scannedFrameCount = scannedFrameCount,
                candidateBlobCount = candidateBlobCount,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                workingWidth = workingWidth,
                workingHeight = workingHeight,
                cancellationSignal = cancellationSignal,
            )
        } catch (_: RuntimeException) {
            resourceNoRead(
                message = "Recorded-HFR streaming decode failed before a safe estimate could be produced.",
                config = config,
                candidates = candidates,
                candidateThumbnails = candidateThumbnails,
                scannedFrameCount = scannedFrameCount,
                candidateBlobCount = candidateBlobCount,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                workingWidth = workingWidth,
                workingHeight = workingHeight,
            )
        } finally {
            source.close()
        }
    }

    private fun finish(
        config: RecordedHfrStreamingEstimateConfig,
        candidates: List<VisualEstimateCandidateFrame>,
        candidateThumbnails: List<VisualEstimateProofThumbnailFrame>,
        sourceThumbnails: List<VisualEstimateProofThumbnailFrame>,
        sourceValidity: RecordedHfrSourceValidity,
        scannedFrameCount: Int,
        candidateBlobCount: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        workingWidth: Int,
        workingHeight: Int,
        cancellationSignal: ImportCancellationSignal,
    ): RecordedHfrStreamingEstimateResult {
        if (config.proofOnly) {
            return proofOnlyNoRead(
                config = config,
                sourceThumbnails = sourceThumbnails,
                sourceValidity = sourceValidity,
                scannedFrameCount = scannedFrameCount,
                candidateFrameCount = candidates.size,
                candidateBlobCount = candidateBlobCount,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                workingWidth = workingWidth,
                workingHeight = workingHeight,
            )
        }
        if (!sourceValidity.passes) {
            return sourceInvalidNoRead(
                config = config,
                sourceThumbnails = sourceThumbnails,
                sourceValidity = sourceValidity,
                scannedFrameCount = scannedFrameCount,
                candidateFrameCount = candidates.size,
                candidateBlobCount = candidateBlobCount,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                workingWidth = workingWidth,
                workingHeight = workingHeight,
            )
        }
        if (cancellationSignal.isCancelled()) {
            return resourceNoRead(
                message = "Recorded-HFR streaming decode was cancelled before candidate reduction.",
                config = config,
                candidates = candidates,
                candidateThumbnails = candidateThumbnails,
                scannedFrameCount = scannedFrameCount,
                candidateBlobCount = candidateBlobCount,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                workingWidth = workingWidth,
                workingHeight = workingHeight,
            )
        }
        val reduction = VisualEstimateCandidateReducer.reduce(
            frameCandidates = candidates,
            config = config.framePipelineConfig.trackConfig,
            isCancelled = cancellationSignal::isCancelled,
        )
        if (reduction is VisualEstimateCandidateReductionOutcome.Failure) {
            return noReadFromCandidates(
                reason = reduction.reason,
                message = reduction.message,
                config = config,
                candidates = candidates,
                candidateThumbnails = candidateThumbnails,
                scannedFrameCount = scannedFrameCount,
                candidateBlobCount = candidateBlobCount,
                sourceWidth = sourceWidth,
                sourceHeight = sourceHeight,
                workingWidth = workingWidth,
                workingHeight = workingHeight,
            )
        }
        reduction as VisualEstimateCandidateReductionOutcome.Success
        config.physicalDetectorConfig?.let { physicalConfig ->
            RecordedHfrPhysicalBallDetector.validateSelectedTrack(reduction.selected, physicalConfig)?.let { failure ->
                return noReadFromCandidates(
                    reason = failure.reason,
                    message = failure.message,
                    config = config,
                    candidates = candidates,
                    candidateThumbnails = candidateThumbnails,
                    scannedFrameCount = scannedFrameCount,
                    candidateBlobCount = candidateBlobCount,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    workingWidth = workingWidth,
                    workingHeight = workingHeight,
                )
            }
        }
        val timing = when (
            val reconciled = when (config.timingMode) {
                RecordedHfrStreamingTimingMode.FRAME_INDEX_INTERVAL ->
                    ImportTimingReconciler.reconcileRecordedCaptureFrameIndexes(
                        frameIndexes = reduction.selected.map { (frame, _) -> frame.originalFrameIndex },
                        frameIntervalSeconds = config.frameIntervalSeconds,
                    )
                RecordedHfrStreamingTimingMode.CONTAINER_PTS_DELTAS ->
                    ImportTimingReconciler.reconcileRecordedContainerPresentationTimestamps(
                        presentationTimestampsNanos = reduction.selected.map { (frame, _) -> frame.presentationTimestampNanos },
                    )
            }
        ) {
            is ImportValidationResult.NoRead -> {
                val noRead = candidateNoRead(
                    reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
                    message = reconciled.message,
                    config = config,
                    frameCount = scannedFrameCount,
                    candidateFrameCount = candidates.size,
                    candidateBlobCount = candidateBlobCount,
                    selectedSampleCount = reduction.samples.size,
                    timestampGapSummary = null,
                    confidence = VisualEstimateConfidence.LOW,
                )
                val trace = buildTrace(
                    detectorConfig = config.framePipelineConfig.trackConfig.detectorConfig,
                    candidates = candidates,
                    selected = reduction.selected,
                    outcome = noRead,
                )
                return noReadResult(
                    outcome = noRead,
                    trace = trace,
                    proofThumbnails = selectProofThumbnails(
                        thumbnails = candidateThumbnails,
                        selectedPositions = reduction.selected.map { (frame, _) -> frame.compactPosition }.toSet(),
                        maxProofFrames = config.maxProofFrames,
                    ),
                    scannedFrameCount = scannedFrameCount,
                    candidateFrameCount = candidates.size,
                    candidateBlobCount = candidateBlobCount,
                    selectedSampleCount = reduction.samples.size,
                    sourceWidth = sourceWidth,
                    sourceHeight = sourceHeight,
                    workingWidth = workingWidth,
                    workingHeight = workingHeight,
                )
            }
            is ImportValidationResult.Success -> reconciled.value
        }
        val estimate = VisualEstimatePipeline
            .estimate(reduction.samples, config.calibration, config.framePipelineConfig.estimateConfig)
            .withRecordedTimingProvenance(
                timing = timing,
                frameCount = scannedFrameCount,
                candidateFrameCount = candidates.size,
                candidateBlobCount = candidateBlobCount,
                selectedSampleCount = reduction.samples.size,
            )
        val trace = buildTrace(
            detectorConfig = config.framePipelineConfig.trackConfig.detectorConfig,
            candidates = candidates,
            selected = reduction.selected,
            outcome = estimate,
        )
        val proofThumbnails = selectProofThumbnails(
            thumbnails = candidateThumbnails,
            selectedPositions = reduction.selected.map { (frame, _) -> frame.compactPosition }.toSet(),
            maxProofFrames = config.maxProofFrames,
        )
        return RecordedHfrStreamingEstimateResult(
            outcome = estimate,
            timing = timing,
            detectorTrace = trace,
            proofThumbnails = proofThumbnails,
            scannedFrameCount = scannedFrameCount,
            retainedCandidateFrameCount = candidates.size,
            retainedProofFrameCount = proofThumbnails.size,
            candidateBlobCount = candidateBlobCount,
            selectedSampleCount = reduction.samples.size,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            workingWidth = workingWidth,
            workingHeight = workingHeight,
        )
    }

    private fun proofOnlyNoRead(
        config: RecordedHfrStreamingEstimateConfig,
        sourceThumbnails: List<VisualEstimateProofThumbnailFrame>,
        sourceValidity: RecordedHfrSourceValidity,
        scannedFrameCount: Int,
        candidateFrameCount: Int,
        candidateBlobCount: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        workingWidth: Int,
        workingHeight: Int,
    ): RecordedHfrStreamingEstimateResult {
        val noRead = candidateNoRead(
            reason = VisualEstimateNoReadReason.EXCESSIVE_RESIDUAL,
            message = "Recorded-HFR proof-only window skipped candidate detection.",
            config = config,
            frameCount = scannedFrameCount,
            candidateFrameCount = candidateFrameCount,
            candidateBlobCount = candidateBlobCount,
            selectedSampleCount = 0,
            timestampGapSummary = null,
            confidence = null,
        )
        return noReadResult(
            outcome = noRead,
            trace = buildSourceTrace(
                detectorConfig = config.framePipelineConfig.trackConfig.detectorConfig,
                thumbnails = sourceThumbnails,
                outcome = noRead,
            ),
            proofThumbnails = sourceThumbnails,
            scannedFrameCount = scannedFrameCount,
            candidateFrameCount = candidateFrameCount,
            candidateBlobCount = candidateBlobCount,
            selectedSampleCount = 0,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            workingWidth = workingWidth,
            workingHeight = workingHeight,
            sourceValidity = sourceValidity,
        )
    }

    private fun RecordedHfrStreamingEstimateConfig.validate(): RecordedHfrStreamingEstimateResult? {
        if (!frameIntervalSeconds.isFinite() || frameIntervalSeconds <= 0.0) {
            val noRead = basicNoRead("Recorded-HFR frame interval must be finite and positive.")
            return emptyResult(this, noRead)
        }
        if (maxScannedFrames <= 0 || maxRetainedCandidateFrames <= 0 || maxProofFrames <= 0) {
            val noRead = basicNoRead("Recorded-HFR streaming resource limits must be positive.")
            return emptyResult(this, noRead)
        }
        if (proofThumbnailMaxWidth <= 0 || proofThumbnailMaxHeight <= 0) {
            val noRead = basicNoRead("Recorded-HFR proof thumbnail limits must be positive.")
            return emptyResult(this, noRead)
        }
        if (physicalDetectorConfig != null && motionDetectorConfig != null) {
            val noRead = basicNoRead("Recorded-HFR detector configuration cannot enable both physical and fixed-camera motion detectors.")
            return emptyResult(this, noRead)
        }
        motionDetectorConfig?.validate()?.let {
            val noRead = basicNoRead(it.message)
            return emptyResult(this, noRead)
        }
        return null
    }

    private fun emptyResult(
        config: RecordedHfrStreamingEstimateConfig,
        outcome: VisualEstimateOutcome.NoRead,
    ): RecordedHfrStreamingEstimateResult =
        noReadResult(
            outcome = outcome,
            trace = VisualEstimateDetectorTrace(config.framePipelineConfig.trackConfig.detectorConfig, emptyList()).withOutcome(outcome),
            proofThumbnails = emptyList(),
            scannedFrameCount = 0,
            candidateFrameCount = 0,
            candidateBlobCount = 0,
            selectedSampleCount = 0,
            sourceWidth = 0,
            sourceHeight = 0,
            workingWidth = 0,
            workingHeight = 0,
        )

    private fun sourceInvalidNoRead(
        config: RecordedHfrStreamingEstimateConfig,
        sourceThumbnails: List<VisualEstimateProofThumbnailFrame>,
        sourceValidity: RecordedHfrSourceValidity,
        scannedFrameCount: Int,
        candidateFrameCount: Int,
        candidateBlobCount: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        workingWidth: Int,
        workingHeight: Int,
    ): RecordedHfrStreamingEstimateResult {
        val noRead = candidateNoRead(
            reason = VisualEstimateNoReadReason.DETECTION_FAILED,
            message = "Recorded window source frames were black or invalid.",
            config = config,
            frameCount = scannedFrameCount,
            candidateFrameCount = candidateFrameCount,
            candidateBlobCount = candidateBlobCount,
            selectedSampleCount = 0,
            timestampGapSummary = null,
            confidence = null,
        )
        return noReadResult(
            outcome = noRead,
            trace = buildSourceTrace(
                detectorConfig = config.framePipelineConfig.trackConfig.detectorConfig,
                thumbnails = sourceThumbnails,
                outcome = noRead,
            ),
            proofThumbnails = sourceThumbnails,
            scannedFrameCount = scannedFrameCount,
            candidateFrameCount = candidateFrameCount,
            candidateBlobCount = candidateBlobCount,
            selectedSampleCount = 0,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            workingWidth = workingWidth,
            workingHeight = workingHeight,
            sourceValidity = sourceValidity,
        )
    }

    private fun validateFrame(
        frame: ImportVideoFrame,
        previousFrameIndex: Int?,
    ): VisualEstimateOutcome.NoRead? {
        if (frame.frameIndex < 0 || frame.width <= 0 || frame.height <= 0) {
            return basicNoRead("Recorded-HFR decoded frame metadata is invalid.")
        }
        if (previousFrameIndex != null && frame.frameIndex <= previousFrameIndex) {
            return basicNoRead("Recorded-HFR decoded frame indexes must be strictly increasing.")
        }
        val pixelCount = frame.width.toLong() * frame.height.toLong()
        if (pixelCount <= 0L || pixelCount > Int.MAX_VALUE || frame.argbPixels.size != pixelCount.toInt()) {
            return VisualEstimateOutcome.NoRead(
                reason = VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED,
                message = "Recorded-HFR decoded frame pixels are invalid or exceed safe limits.",
            )
        }
        return null
    }

    private fun resourceNoRead(
        message: String,
        config: RecordedHfrStreamingEstimateConfig,
        candidates: List<VisualEstimateCandidateFrame>,
        candidateThumbnails: List<VisualEstimateProofThumbnailFrame>,
        scannedFrameCount: Int,
        candidateBlobCount: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        workingWidth: Int,
        workingHeight: Int,
    ): RecordedHfrStreamingEstimateResult =
        noReadFromCandidates(
            reason = VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED,
            message = message,
            config = config,
            candidates = candidates,
            candidateThumbnails = candidateThumbnails,
            scannedFrameCount = scannedFrameCount,
            candidateBlobCount = candidateBlobCount,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            workingWidth = workingWidth,
            workingHeight = workingHeight,
        )

    private fun noReadFromCandidates(
        reason: VisualEstimateNoReadReason,
        message: String,
        config: RecordedHfrStreamingEstimateConfig,
        candidates: List<VisualEstimateCandidateFrame>,
        candidateThumbnails: List<VisualEstimateProofThumbnailFrame>,
        scannedFrameCount: Int,
        candidateBlobCount: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        workingWidth: Int,
        workingHeight: Int,
        sourceValidity: RecordedHfrSourceValidity = RecordedHfrSourceValidity.NotEvaluated,
    ): RecordedHfrStreamingEstimateResult {
        val noRead = candidateNoRead(
            reason = reason,
            message = message,
            config = config,
            frameCount = scannedFrameCount,
            candidateFrameCount = candidates.size,
            candidateBlobCount = candidateBlobCount,
            selectedSampleCount = 0,
            timestampGapSummary = null,
            confidence = null,
        )
        val trace = buildTrace(
            detectorConfig = config.framePipelineConfig.trackConfig.detectorConfig,
            candidates = candidates,
            selected = emptyList(),
            outcome = noRead,
        )
        val proofThumbnails = selectProofThumbnails(
            thumbnails = candidateThumbnails,
            selectedPositions = emptySet(),
            maxProofFrames = config.maxProofFrames,
        )
        return noReadResult(
            outcome = noRead,
            trace = trace,
            proofThumbnails = proofThumbnails,
            scannedFrameCount = scannedFrameCount,
            candidateFrameCount = candidates.size,
            candidateBlobCount = candidateBlobCount,
            selectedSampleCount = 0,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            workingWidth = workingWidth,
            workingHeight = workingHeight,
            sourceValidity = sourceValidity,
        )
    }

    private fun noReadResult(
        outcome: VisualEstimateOutcome.NoRead,
        trace: VisualEstimateDetectorTrace,
        proofThumbnails: List<VisualEstimateProofThumbnailFrame>,
        scannedFrameCount: Int,
        candidateFrameCount: Int,
        candidateBlobCount: Int,
        selectedSampleCount: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        workingWidth: Int,
        workingHeight: Int,
        sourceValidity: RecordedHfrSourceValidity = RecordedHfrSourceValidity.NotEvaluated,
    ): RecordedHfrStreamingEstimateResult =
        RecordedHfrStreamingEstimateResult(
            outcome = outcome,
            timing = null,
            detectorTrace = trace,
            proofThumbnails = proofThumbnails,
            scannedFrameCount = scannedFrameCount,
            retainedCandidateFrameCount = candidateFrameCount,
            retainedProofFrameCount = proofThumbnails.size,
            candidateBlobCount = candidateBlobCount,
            selectedSampleCount = selectedSampleCount,
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            workingWidth = workingWidth,
            workingHeight = workingHeight,
            sourceValidity = sourceValidity,
        )

    private fun candidateNoRead(
        reason: VisualEstimateNoReadReason,
        message: String,
        config: RecordedHfrStreamingEstimateConfig,
        frameCount: Int,
        candidateFrameCount: Int,
        candidateBlobCount: Int,
        selectedSampleCount: Int,
        timestampGapSummary: com.speedball.app.measurement.TimestampGapSummary?,
        confidence: VisualEstimateConfidence?,
    ): VisualEstimateOutcome.NoRead =
        VisualEstimateOutcome.NoRead(
            reason = reason,
            message = message,
            diagnostics = VisualEstimateDiagnostics(
                frameCount = frameCount,
                detectionCount = selectedSampleCount,
                timingBasis = EstimateTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL,
                timestampGapSummary = timestampGapSummary,
                fitResidualPx = null,
                confidence = confidence,
                candidateFrameCount = candidateFrameCount,
                candidateBlobCount = candidateBlobCount,
                selectedSampleCount = selectedSampleCount,
                assumptions = listOf(
                    VisualEstimateDiagnostics.PLANAR_MOTION_ASSUMPTION,
                    ImportTimingReconciler.CONSTANT_VELOCITY_WINDOW_ASSUMPTION,
                    ImportTimingReconciler.RECORDED_CAPTURE_FRAME_DROP_ASSUMPTION,
                ),
            ),
        )

    private fun basicNoRead(message: String): VisualEstimateOutcome.NoRead =
        VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED,
            message = message,
            diagnostics = VisualEstimateDiagnostics(
                frameCount = 0,
                detectionCount = 0,
                timingBasis = EstimateTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL,
                timestampGapSummary = null,
                fitResidualPx = null,
                confidence = null,
                candidateFrameCount = 0,
                candidateBlobCount = 0,
                selectedSampleCount = 0,
            ),
        )

    private fun basicTimingNoRead(message: String): VisualEstimateOutcome.NoRead =
        VisualEstimateOutcome.NoRead(
            reason = VisualEstimateNoReadReason.BAD_TIMESTAMPS,
            message = message,
            diagnostics = VisualEstimateDiagnostics(
                frameCount = 0,
                detectionCount = 0,
                timingBasis = EstimateTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL,
                timestampGapSummary = null,
                fitResidualPx = null,
                confidence = null,
                candidateFrameCount = 0,
                candidateBlobCount = 0,
                selectedSampleCount = 0,
            ),
        )

    private fun buildTrace(
        detectorConfig: com.speedball.app.measurement.BlobDetectionConfig,
        candidates: List<VisualEstimateCandidateFrame>,
        selected: List<Pair<VisualEstimateCandidateFrame, Blob>>,
        outcome: VisualEstimateOutcome,
    ): VisualEstimateDetectorTrace {
        val selectedByPosition = selected.associate { (frame, blob) -> frame.compactPosition to blob }
        return VisualEstimateDetectorTrace(
            detectorConfig = detectorConfig,
            frames = candidates.map { frame ->
                VisualEstimateDetectorFrameTrace(
                    frameIndex = frame.compactPosition,
                    timestampSeconds = frame.timestampSeconds,
                    roi = detectorConfig.roi.clippedTo(frame.width, frame.height),
                    candidateCount = frame.blobs.size,
                    candidates = frame.blobs.take(MAX_TRACE_CANDIDATES_PER_FRAME),
                    selectedBlob = selectedByPosition[frame.compactPosition],
                    originalFrameIndex = frame.originalFrameIndex,
                )
            },
        ).withOutcome(outcome)
    }

    private fun buildSourceTrace(
        detectorConfig: com.speedball.app.measurement.BlobDetectionConfig,
        thumbnails: List<VisualEstimateProofThumbnailFrame>,
        outcome: VisualEstimateOutcome,
    ): VisualEstimateDetectorTrace =
        VisualEstimateDetectorTrace(
            detectorConfig = detectorConfig,
            frames = thumbnails.map { thumbnail ->
                VisualEstimateDetectorFrameTrace(
                    frameIndex = thumbnail.compactPosition,
                    timestampSeconds = thumbnail.timestampSeconds,
                    roi = detectorConfig.roi.clippedTo(thumbnail.sourceWidth, thumbnail.sourceHeight),
                    candidateCount = 0,
                    candidates = emptyList(),
                    selectedBlob = null,
                    originalFrameIndex = thumbnail.originalFrameIndex,
                )
            },
        ).withOutcome(outcome)

    private fun selectProofThumbnails(
        thumbnails: List<VisualEstimateProofThumbnailFrame>,
        selectedPositions: Set<Int>,
        maxProofFrames: Int,
    ): List<VisualEstimateProofThumbnailFrame> {
        if (thumbnails.isEmpty() || maxProofFrames <= 0) return emptyList()
        val byPosition = thumbnails.associateBy { it.compactPosition }
        val selected = selectedPositions.sorted()
            .mapNotNull { byPosition[it] }
            .take(maxProofFrames)
        if (selected.size >= maxProofFrames) return selected
        val selectedPositionSet = selected.map { it.compactPosition }.toSet()
        val remainingSlots = maxProofFrames - selected.size
        val nonSelected = thumbnails.filterNot { it.compactPosition in selectedPositionSet }
        val fill = VisualEstimateCaptureProofBuilder
            .selectProofFrameIndices(nonSelected.size, remainingSlots)
            .map { nonSelected[it] }
        return (selected + fill).distinctBy { it.compactPosition }.sortedBy { it.compactPosition }
    }

    private fun VisualEstimateOutcome.withRecordedTimingProvenance(
        timing: ImportTimingReconciliation,
        frameCount: Int,
        candidateFrameCount: Int,
        candidateBlobCount: Int,
        selectedSampleCount: Int,
    ): VisualEstimateOutcome =
        when (this) {
            is VisualEstimateOutcome.Success -> VisualEstimateOutcome.Success(
                milesPerHour = milesPerHour,
                launchAngleDegrees = launchAngleDegrees,
                diagnostics = diagnostics.withRecordedTimingProvenance(
                    timing = timing,
                    frameCount = frameCount,
                    candidateFrameCount = candidateFrameCount,
                    candidateBlobCount = candidateBlobCount,
                    selectedSampleCount = selectedSampleCount,
                ),
            )
            is VisualEstimateOutcome.NoRead -> copy(
                diagnostics = diagnostics?.withRecordedTimingProvenance(
                    timing = timing,
                    frameCount = frameCount,
                    candidateFrameCount = candidateFrameCount,
                    candidateBlobCount = candidateBlobCount,
                    selectedSampleCount = selectedSampleCount,
                ) ?: VisualEstimateDiagnostics(
                    frameCount = frameCount,
                    detectionCount = selectedSampleCount,
                    timingBasis = timing.basis.toEstimateTimingBasis(),
                    timestampGapSummary = timing.timestampGapSummary,
                    fitResidualPx = null,
                    confidence = timing.confidence,
                    candidateFrameCount = candidateFrameCount,
                    candidateBlobCount = candidateBlobCount,
                    selectedSampleCount = selectedSampleCount,
                    assumptions = listOf(VisualEstimateDiagnostics.PLANAR_MOTION_ASSUMPTION) + timing.assumptions,
                ),
            )
        }

    private fun VisualEstimateDiagnostics.withRecordedTimingProvenance(
        timing: ImportTimingReconciliation,
        frameCount: Int,
        candidateFrameCount: Int,
        candidateBlobCount: Int,
        selectedSampleCount: Int,
    ): VisualEstimateDiagnostics =
        copy(
            frameCount = frameCount,
            detectionCount = selectedSampleCount,
            timingBasis = timing.basis.toEstimateTimingBasis(),
            timestampGapSummary = timing.timestampGapSummary,
            confidence = timing.confidence,
            candidateFrameCount = candidateFrameCount,
            candidateBlobCount = candidateBlobCount,
            selectedSampleCount = selectedSampleCount,
            assumptions = (assumptions + timing.assumptions).distinct(),
        )

    private fun ImportTimingBasis.toEstimateTimingBasis(): EstimateTimingBasis =
        when (this) {
            ImportTimingBasis.CONTAINER_PRESENTATION_TIMESTAMPS ->
                EstimateTimingBasis.IMPORT_CONTAINER_PRESENTATION_TIMESTAMPS
            ImportTimingBasis.PARTIAL_TIMESTAMP_VISUAL_GAP_RECONCILIATION ->
                EstimateTimingBasis.IMPORT_PARTIAL_TIMESTAMP_VISUAL_GAP_RECONCILIATION
            ImportTimingBasis.VISUAL_FRAME_DELTA_INFERENCE ->
                EstimateTimingBasis.IMPORT_VISUAL_FRAME_DELTA_INFERENCE
            ImportTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL ->
                EstimateTimingBasis.RECORDED_CAPTURE_FRAME_INTERVAL
            ImportTimingBasis.RECORDED_CONTAINER_PRESENTATION_TIMESTAMPS ->
                EstimateTimingBasis.RECORDED_CONTAINER_PRESENTATION_TIMESTAMPS
            ImportTimingBasis.NO_TRUSTWORTHY_TIMING ->
                EstimateTimingBasis.IMPORT_VISUAL_FRAME_DELTA_INFERENCE
        }

    private fun com.speedball.app.measurement.MeasurementRunFailure.toVisualReason(): VisualEstimateNoReadReason =
        when (this) {
            com.speedball.app.measurement.MeasurementRunFailure.BAD_CALIBRATION -> VisualEstimateNoReadReason.BAD_CALIBRATION
            com.speedball.app.measurement.MeasurementRunFailure.BAD_FRAME_SEQUENCE -> VisualEstimateNoReadReason.BAD_TIMESTAMPS
            com.speedball.app.measurement.MeasurementRunFailure.DETECTION_FAILED -> VisualEstimateNoReadReason.DETECTION_FAILED
            com.speedball.app.measurement.MeasurementRunFailure.INSUFFICIENT_DETECTIONS -> VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS
            com.speedball.app.measurement.MeasurementRunFailure.MEASUREMENT_REJECTED -> VisualEstimateNoReadReason.EXCESSIVE_RESIDUAL
            com.speedball.app.measurement.MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED -> VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED
            com.speedball.app.measurement.MeasurementRunFailure.UNPROVEN_TIMING -> VisualEstimateNoReadReason.BAD_TIMESTAMPS
        }

    private fun ImportVideoFrame.toProofThumbnail(
        compactPosition: Int,
        timestampSeconds: Double,
        maxWidth: Int,
        maxHeight: Int,
    ): VisualEstimateProofThumbnailFrame {
        val scale = minOf(
            maxWidth.toDouble() / width,
            maxHeight.toDouble() / height,
            1.0,
        )
        val targetWidth = maxOf(1, (width * scale).roundToInt())
        val targetHeight = maxOf(1, (height * scale).roundToInt())
        val targetPixels = IntArray(targetWidth * targetHeight)
        for (y in 0 until targetHeight) {
            val sourceY = (y * height / targetHeight).coerceIn(0, height - 1)
            for (x in 0 until targetWidth) {
                val sourceX = (x * width / targetWidth).coerceIn(0, width - 1)
                targetPixels[y * targetWidth + x] = argbPixels[sourceY * width + sourceX]
            }
        }
        return VisualEstimateProofThumbnailFrame(
            compactPosition = compactPosition,
            originalFrameIndex = frameIndex,
            timestampSeconds = timestampSeconds,
            sourceWidth = width,
            sourceHeight = height,
            thumbnailWidth = targetWidth,
            thumbnailHeight = targetHeight,
            thumbnailArgbPixels = targetPixels,
        )
    }

    private class SourceValidityAccumulator {
        private var roiPixelCount = 0L
        private var lumaTotal = 0L
        private var brightPixelCount = 0L
        private var brightestFrameMeanLuma = 0.0

        fun add(frame: ImportVideoFrame, roi: com.speedball.app.measurement.RegionOfInterest?) {
            val clipped = roi?.clippedTo(frame.width, frame.height)
                ?: com.speedball.app.measurement.RegionOfInterest(0, 0, frame.width, frame.height)
            var frameLumaTotal = 0L
            var framePixelCount = 0L
            for (y in clipped.top until clipped.bottomExclusive) {
                val row = y * frame.width
                for (x in clipped.left until clipped.rightExclusive) {
                    val luma = frame.argbPixels[row + x].luma()
                    lumaTotal += luma
                    frameLumaTotal += luma
                    framePixelCount += 1
                    if (luma >= SOURCE_VALIDITY_BRIGHT_LUMA_THRESHOLD) brightPixelCount += 1
                }
            }
            roiPixelCount += framePixelCount
            if (framePixelCount > 0L) {
                brightestFrameMeanLuma = maxOf(brightestFrameMeanLuma, frameLumaTotal.toDouble() / framePixelCount)
            }
        }

        fun toSummary(scannedFrameCount: Int): RecordedHfrSourceValidity {
            if (scannedFrameCount <= 0 || roiPixelCount <= 0L) {
                return RecordedHfrSourceValidity(
                    scannedFrameCount = scannedFrameCount,
                    roiPixelCount = roiPixelCount,
                    meanLuma = 0.0,
                    brightestFrameMeanLuma = brightestFrameMeanLuma,
                    brightPixelFraction = 0.0,
                    verdict = "NO_READ_SOURCE_EMPTY",
                )
            }
            val meanLuma = lumaTotal.toDouble() / roiPixelCount
            val brightPixelFraction = brightPixelCount.toDouble() / roiPixelCount
            val passes = meanLuma >= SOURCE_VALIDITY_MIN_MEAN_LUMA ||
                brightestFrameMeanLuma >= SOURCE_VALIDITY_MIN_FRAME_MEAN_LUMA ||
                brightPixelFraction >= SOURCE_VALIDITY_MIN_BRIGHT_PIXEL_FRACTION
            return RecordedHfrSourceValidity(
                scannedFrameCount = scannedFrameCount,
                roiPixelCount = roiPixelCount,
                meanLuma = meanLuma,
                brightestFrameMeanLuma = brightestFrameMeanLuma,
                brightPixelFraction = brightPixelFraction,
                verdict = if (passes) "PASS" else "NO_READ_BLACK_OR_INVALID_SOURCE",
            )
        }
    }

    private fun Int.luma(): Int {
        val r = (this shr 16) and 0xff
        val g = (this shr 8) and 0xff
        val b = this and 0xff
        return (77 * r + 150 * g + 29 * b) shr 8
    }

    private const val SOURCE_VALIDITY_BRIGHT_LUMA_THRESHOLD = 24
    private const val SOURCE_VALIDITY_MIN_MEAN_LUMA = 8.0
    private const val SOURCE_VALIDITY_MIN_FRAME_MEAN_LUMA = 12.0
    private const val SOURCE_VALIDITY_MIN_BRIGHT_PIXEL_FRACTION = 0.001

    private const val MAX_TRACE_CANDIDATES_PER_FRAME = 16
}
