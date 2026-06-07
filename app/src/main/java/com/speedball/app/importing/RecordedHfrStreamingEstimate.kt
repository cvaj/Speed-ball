package com.speedball.app.importing

import com.speedball.app.measurement.Blob
import com.speedball.app.measurement.BlobCandidateDetectionOutcome
import com.speedball.app.measurement.BlobDetector
import com.speedball.app.measurement.EstimateTimingBasis
import com.speedball.app.measurement.MeasurementCalibrationState
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
)

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
            finish(
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
        scannedFrameCount: Int,
        candidateBlobCount: Int,
        sourceWidth: Int,
        sourceHeight: Int,
        workingWidth: Int,
        workingHeight: Int,
    ): RecordedHfrStreamingEstimateResult {
        val reduction = VisualEstimateCandidateReducer.reduce(candidates, config.framePipelineConfig.trackConfig)
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

    private const val MAX_TRACE_CANDIDATES_PER_FRAME = 16
}
