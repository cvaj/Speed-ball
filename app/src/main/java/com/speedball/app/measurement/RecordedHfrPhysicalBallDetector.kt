package com.speedball.app.measurement

import com.speedball.core.model.ImagePoint
import java.util.BitSet
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Fail-loud detector-stage reason for recorded-HFR physical candidate assembly. */
enum class RecordedHfrPhysicalDetectorReason {
    NO_MOTION_CANDIDATE,
    SHAPE_REJECTED,
    COLOR_INCOHERENT,
    BACKGROUND_SEPARATION_AMBIGUOUS,
    GLOBAL_MOTION_AMBIGUOUS,
    RESOURCE_LIMIT_EXCEEDED,
}

/** Numeric gates for recorded-HFR physical-ball candidate assembly. */
data class RecordedHfrPhysicalDetectorConfig(
    val minUsableFrames: Int = 4,
    val persistentFraction: Double = 0.80,
    val activityDilationPx: Int = 8,
    val fragmentMergePx: Int = 10,
    val maxPhysicalCandidatesPerFrame: Int = 3,
    val ambiguousRemovedFraction: Double = 0.85,
    val minMovingToThresholdFraction: Double = 0.12,
    val globalMotionSearchPx: Int = 8,
    val maxMedianGlobalShiftPx: Double = 1.5,
    val maxAdjacentGlobalShiftPx: Double = 4.0,
    val minVisibleShortSidePx: Int = 6,
    val minSolidity: Double = 0.55,
    val maxPrincipalAxisRatio: Double = 8.0,
    val elongatedPrincipalAxisRatio: Double = 3.0,
    val minCapsuleScore: Double = 0.35,
    val minCompactRoundness: Double = 0.80,
    val maxSaturationStdDev: Double = 0.28,
    val maxValueStdDev: Double = 0.32,
    val maxAdjacentAreaRatio: Double = 2.25,
    val minAdjacentAreaRatio: Double = 0.45,
    val maxTrackAreaRatio: Double = 3.0,
    val maxAdjacentShortSideRatio: Double = 2.0,
    val minAdjacentShortSideRatio: Double = 0.50,
    val maxTrackShortSideRatio: Double = 2.5,
    val maxPrincipalAxisRatioSwing: Double = 3.0,
    val maxAdjacentHueDistanceDegrees: Double = 18.0,
    val maxAdjacentSaturationDelta: Double = 0.22,
    val maxAdjacentValueDelta: Double = 0.28,
) {
    fun validate(): MeasurementRunOutcome.NoRead? {
        if (
            minUsableFrames <= 0 ||
            activityDilationPx < 0 ||
            fragmentMergePx < 0 ||
            maxPhysicalCandidatesPerFrame <= 0 ||
            minVisibleShortSidePx <= 0
        ) {
            return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                "Recorded-HFR physical detector config values are invalid.",
            )
        }
        if (
            persistentFraction <= 0.0 ||
            persistentFraction > 1.0 ||
            ambiguousRemovedFraction <= 0.0 ||
            ambiguousRemovedFraction >= 1.0 ||
            minMovingToThresholdFraction <= 0.0 ||
            minMovingToThresholdFraction > 1.0 ||
            globalMotionSearchPx.toDouble() <= maxAdjacentGlobalShiftPx
        ) {
            return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                "Recorded-HFR physical detector fractional gates and global-motion search window are invalid.",
            )
        }
        val finitePositive = listOf(
            persistentFraction,
            ambiguousRemovedFraction,
            minMovingToThresholdFraction,
            maxMedianGlobalShiftPx,
            maxAdjacentGlobalShiftPx,
            minSolidity,
            maxPrincipalAxisRatio,
            elongatedPrincipalAxisRatio,
            minCapsuleScore,
            minCompactRoundness,
            maxSaturationStdDev,
            maxValueStdDev,
            maxAdjacentAreaRatio,
            minAdjacentAreaRatio,
            maxTrackAreaRatio,
            maxAdjacentShortSideRatio,
            minAdjacentShortSideRatio,
            maxTrackShortSideRatio,
            maxPrincipalAxisRatioSwing,
            maxAdjacentHueDistanceDegrees,
            maxAdjacentSaturationDelta,
            maxAdjacentValueDelta,
        )
        if (finitePositive.any { !it.isFinite() || it <= 0.0 }) {
            return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                "Recorded-HFR physical detector numeric gates must be finite and positive.",
            )
        }
        if (minAdjacentAreaRatio >= 1.0 || minAdjacentShortSideRatio >= 1.0) {
            return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                "Recorded-HFR physical detector ratio floors must be below 1.",
            )
        }
        if (maxAdjacentHueDistanceDegrees > 180.0 || maxAdjacentSaturationDelta > 1.0 || maxAdjacentValueDelta > 1.0) {
            return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                "Recorded-HFR physical detector color consistency gates are invalid.",
            )
        }
        return null
    }
}

/** One decoded recorded-HFR frame after threshold extraction; only threshold-pixel HSV is retained. */
data class RecordedHfrPhysicalFrameMask(
    val compactPosition: Int,
    val originalFrameIndex: Int,
    val timestampSeconds: Double,
    val width: Int,
    val height: Int,
    val thresholdMask: BitSet,
    val thresholdPixelIndexes: IntArray,
    val hueDegrees: ByteArray,
    val saturation: ByteArray,
    val value: ByteArray,
    val thresholdPixelCount: Int,
    val presentationTimestampNanos: Long?,
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            other is RecordedHfrPhysicalFrameMask &&
            compactPosition == other.compactPosition &&
            originalFrameIndex == other.originalFrameIndex &&
            timestampSeconds == other.timestampSeconds &&
            width == other.width &&
            height == other.height &&
            thresholdMask == other.thresholdMask &&
            thresholdPixelIndexes.contentEquals(other.thresholdPixelIndexes) &&
            hueDegrees.contentEquals(other.hueDegrees) &&
            saturation.contentEquals(other.saturation) &&
            value.contentEquals(other.value) &&
            thresholdPixelCount == other.thresholdPixelCount &&
            presentationTimestampNanos == other.presentationTimestampNanos

    override fun hashCode(): Int {
        var result = compactPosition
        result = 31 * result + originalFrameIndex
        result = 31 * result + timestampSeconds.hashCode()
        result = 31 * result + width
        result = 31 * result + height
        result = 31 * result + thresholdMask.hashCode()
        result = 31 * result + thresholdPixelIndexes.contentHashCode()
        result = 31 * result + hueDegrees.contentHashCode()
        result = 31 * result + saturation.contentHashCode()
        result = 31 * result + value.contentHashCode()
        result = 31 * result + thresholdPixelCount
        result = 31 * result + (presentationTimestampNanos?.hashCode() ?: 0)
        return result
    }
}

sealed interface RecordedHfrPhysicalDetectionOutcome {
    data class Success(
        val frames: List<VisualEstimateCandidateFrame>,
        val candidateBlobCount: Int,
    ) : RecordedHfrPhysicalDetectionOutcome

    data class Failure(
        val reason: VisualEstimateNoReadReason,
        val message: String,
        val detectorReason: RecordedHfrPhysicalDetectorReason,
    ) : RecordedHfrPhysicalDetectionOutcome
}

sealed interface RecordedHfrPhysicalFrameMaskOutcome {
    data class Success(val value: RecordedHfrPhysicalFrameMask) : RecordedHfrPhysicalFrameMaskOutcome
    data class Failure(
        val reason: VisualEstimateNoReadReason,
        val message: String,
        val detectorReason: RecordedHfrPhysicalDetectorReason,
    ) : RecordedHfrPhysicalFrameMaskOutcome
}

/**
 * Builds recorded-HFR physical ball candidates from a bounded decoded window.
 *
 * The detector uses per-window temporal activity to separate the moving ball
 * from static same-color scene content, then merges fragments only inside each
 * moving region. It never stores full-frame ARGB pixels after thresholding.
 */
object RecordedHfrPhysicalBallDetector {
    fun buildFrameMask(
        frame: RgbFrame,
        compactPosition: Int,
        originalFrameIndex: Int,
        presentationTimestampNanos: Long?,
        detectorConfig: BlobDetectionConfig,
    ): RecordedHfrPhysicalFrameMaskOutcome {
        validateDetectorInputForMask(frame, detectorConfig)?.let {
            return RecordedHfrPhysicalFrameMaskOutcome.Failure(
                it.reason.toVisualReason(),
                it.message,
                RecordedHfrPhysicalDetectorReason.RESOURCE_LIMIT_EXCEEDED,
            )
        }
        val roi = detectorConfig.roi.clippedTo(frame.width, frame.height)
            ?: return RecordedHfrPhysicalFrameMaskOutcome.Failure(
                VisualEstimateNoReadReason.DETECTION_FAILED,
                "ROI does not overlap the frame.",
                RecordedHfrPhysicalDetectorReason.NO_MOTION_CANDIDATE,
            )
        val pixelCount = frame.width * frame.height
        val mask = BitSet(pixelCount)
        val retainedThresholdCapacity = min(detectorConfig.bounds.maxThresholdPixels, pixelCount)
        val indexes = IntArray(retainedThresholdCapacity)
        val hue = ByteArray(retainedThresholdCapacity)
        val saturation = ByteArray(retainedThresholdCapacity)
        val value = ByteArray(retainedThresholdCapacity)
        var thresholdPixels = 0
        var operations = 0
        for (y in roi.top until roi.bottomExclusive) {
            for (x in roi.left until roi.rightExclusive) {
                operations += 1
                if (operations > detectorConfig.bounds.maxOperationsPerFrame) {
                    return maskResourceFailure("Per-frame operation budget was exceeded.")
                }
                val index = y * frame.width + x
                val hsv = ColorMath.argbToHsv(frame.argbPixels[index])
                if (detectorConfig.threshold.matches(hsv)) {
                    if (thresholdPixels >= detectorConfig.bounds.maxThresholdPixels) {
                        return maskResourceFailure("Too many threshold-passing pixels in one frame.")
                    }
                    mask.set(index)
                    indexes[thresholdPixels] = index
                    hue[thresholdPixels] = quantize(hsv.hueDegrees / 360.0)
                    saturation[thresholdPixels] = quantize(hsv.saturation)
                    value[thresholdPixels] = quantize(hsv.value)
                    thresholdPixels += 1
                }
            }
        }
        return RecordedHfrPhysicalFrameMaskOutcome.Success(
            RecordedHfrPhysicalFrameMask(
                compactPosition = compactPosition,
                originalFrameIndex = originalFrameIndex,
                timestampSeconds = frame.timestampSeconds,
                width = frame.width,
                height = frame.height,
                thresholdMask = mask,
                thresholdPixelIndexes = indexes.copyOf(thresholdPixels),
                hueDegrees = hue.copyOf(thresholdPixels),
                saturation = saturation.copyOf(thresholdPixels),
                value = value.copyOf(thresholdPixels),
                thresholdPixelCount = thresholdPixels,
                presentationTimestampNanos = presentationTimestampNanos,
            ),
        )
    }

    fun detect(
        masks: List<RecordedHfrPhysicalFrameMask>,
        detectorConfig: BlobDetectionConfig,
        physicalConfig: RecordedHfrPhysicalDetectorConfig,
        isCancelled: () -> Boolean = { false },
    ): RecordedHfrPhysicalDetectionOutcome {
        physicalConfig.validate()?.let {
            return failure(RecordedHfrPhysicalDetectorReason.RESOURCE_LIMIT_EXCEEDED, it.message)
        }
        if (masks.size < physicalConfig.minUsableFrames) {
            return RecordedHfrPhysicalDetectionOutcome.Failure(
                VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
                "Recorded-HFR physical detector needs at least ${physicalConfig.minUsableFrames} decoded frames.",
                RecordedHfrPhysicalDetectorReason.NO_MOTION_CANDIDATE,
            )
        }
        val width = masks.first().width
        val height = masks.first().height
        if (masks.any { it.width != width || it.height != height }) {
            return failure(RecordedHfrPhysicalDetectorReason.RESOURCE_LIMIT_EXCEEDED, "Recorded-HFR physical detector frames must share dimensions.")
        }
        val pixelCount = width * height
        val persistent = persistentMask(masks, pixelCount, physicalConfig)
        try {
            globalMotionFailure(masks, persistent, detectorConfig, physicalConfig, isCancelled)?.let { return it }
        } catch (error: PhysicalDetectorResourceException) {
            return failure(
                RecordedHfrPhysicalDetectorReason.RESOURCE_LIMIT_EXCEEDED,
                error.message ?: "Recorded-HFR physical detector exceeded its global-motion budget.",
            )
        }

        val frames = mutableListOf<VisualEstimateCandidateFrame>()
        var candidateBlobCount = 0
        var bestMovingFraction = 0.0
        var framesWithMovingArea = 0
        var thresholdFrames = 0
        var highRemovedFrames = 0
        var colorRejectedCandidates = 0
        var shapeRejectedCandidates = 0
        masks.forEachIndexed { order, frame ->
            val moving = movingMask(order, masks, persistent, physicalConfig)
            val movingCount = moving.cardinality()
            if (movingCount > 0) framesWithMovingArea += 1
            if (frame.thresholdPixelCount > 0) {
                thresholdFrames += 1
                val movingFraction = movingCount.toDouble() / frame.thresholdPixelCount
                bestMovingFraction = max(bestMovingFraction, movingFraction)
                if (movingFraction < 1.0 - physicalConfig.ambiguousRemovedFraction) {
                    highRemovedFrames += 1
                }
            }
            val result = try {
                physicalBlobs(frame, moving, detectorConfig, physicalConfig)
            } catch (error: PhysicalDetectorResourceException) {
                return failure(
                    RecordedHfrPhysicalDetectorReason.RESOURCE_LIMIT_EXCEEDED,
                    error.message ?: "Recorded-HFR physical detector exceeded its resource budget.",
                )
            }
            colorRejectedCandidates += result.rejectedReasons.count { it == RecordedHfrPhysicalDetectorReason.COLOR_INCOHERENT }
            shapeRejectedCandidates += result.rejectedReasons.count { it == RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED }
            val blobs = result.blobs
            if (blobs.isNotEmpty()) {
                if (blobs.size > physicalConfig.maxPhysicalCandidatesPerFrame) {
                    return failure(
                        RecordedHfrPhysicalDetectorReason.BACKGROUND_SEPARATION_AMBIGUOUS,
                        "Recorded-HFR physical detector found ${blobs.size} moving physical candidates in one frame.",
                    )
                }
                frames += VisualEstimateCandidateFrame(
                    compactPosition = frame.compactPosition,
                    originalFrameIndex = frame.originalFrameIndex,
                    timestampSeconds = frame.timestampSeconds,
                    width = frame.width,
                    height = frame.height,
                    blobs = blobs,
                    presentationTimestampNanos = frame.presentationTimestampNanos,
                )
                candidateBlobCount += blobs.size
            }
        }
        if (thresholdFrames > 0 && highRemovedFrames == thresholdFrames) {
            return failure(
                RecordedHfrPhysicalDetectorReason.BACKGROUND_SEPARATION_AMBIGUOUS,
                "Recorded-HFR physical detector removed too much same-color area in every thresholded frame.",
            )
        }
        if (framesWithMovingArea < physicalConfig.minUsableFrames) {
            return RecordedHfrPhysicalDetectionOutcome.Failure(
                VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
                "Recorded-HFR physical detector found too few frames with moving ball-colored area.",
                RecordedHfrPhysicalDetectorReason.NO_MOTION_CANDIDATE,
            )
        }
        if (bestMovingFraction < physicalConfig.minMovingToThresholdFraction) {
            return failure(
                RecordedHfrPhysicalDetectorReason.BACKGROUND_SEPARATION_AMBIGUOUS,
                "Recorded-HFR physical detector could not separate the moving ball from static same-color background.",
            )
        }
        if (frames.isEmpty()) {
            if (colorRejectedCandidates > 0 && shapeRejectedCandidates == 0) {
                return failure(
                    RecordedHfrPhysicalDetectorReason.COLOR_INCOHERENT,
                    "Recorded-HFR physical detector found moving color but rejected it as color-incoherent.",
                )
            }
            return failure(
                RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED,
                "Recorded-HFR physical detector found moving color but no ball-like physical candidate.",
            )
        }
        if (frames.size < physicalConfig.minUsableFrames) {
            return RecordedHfrPhysicalDetectionOutcome.Failure(
                VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
                "Recorded-HFR physical detector found too few ball-like physical candidate frames.",
                RecordedHfrPhysicalDetectorReason.NO_MOTION_CANDIDATE,
            )
        }
        return RecordedHfrPhysicalDetectionOutcome.Success(frames, candidateBlobCount)
    }

    fun validateSelectedTrack(
        selected: List<Pair<VisualEstimateCandidateFrame, Blob>>,
        config: RecordedHfrPhysicalDetectorConfig,
    ): RecordedHfrPhysicalDetectionOutcome.Failure? {
        val interior = selected.filterNot { (_, blob) -> blob.physicalMetrics?.touchesFrameEdge == true }
        if (interior.size < config.minUsableFrames) return null
        interior.zipWithNext().forEach { (previous, current) ->
            val a = previous.second
            val b = current.second
            val metricsA = a.physicalMetrics
            val metricsB = b.physicalMetrics
            if (!ratioInRange(a.areaPx.toDouble(), b.areaPx.toDouble(), config.minAdjacentAreaRatio, config.maxAdjacentAreaRatio)) {
                return failure(RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED, "Recorded-HFR physical detector rejected inconsistent candidate area.")
            }
            val shortA = min(a.bounds.width, a.bounds.height).toDouble()
            val shortB = min(b.bounds.width, b.bounds.height).toDouble()
            if (!ratioInRange(shortA, shortB, config.minAdjacentShortSideRatio, config.maxAdjacentShortSideRatio)) {
                return failure(RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED, "Recorded-HFR physical detector rejected inconsistent candidate short-side size.")
            }
            if (metricsA != null && metricsB != null) {
                if (hueDistanceDegrees(metricsA.meanHueDegrees, metricsB.meanHueDegrees) > config.maxAdjacentHueDistanceDegrees ||
                    abs(metricsA.meanSaturation - metricsB.meanSaturation) > config.maxAdjacentSaturationDelta ||
                    abs(metricsA.meanValue - metricsB.meanValue) > config.maxAdjacentValueDelta
                ) {
                    return failure(RecordedHfrPhysicalDetectorReason.COLOR_INCOHERENT, "Recorded-HFR physical detector rejected adjacent candidate color inconsistency.")
                }
            }
        }
        val areas = interior.map { it.second.areaPx.toDouble() }
        if ((areas.maxOrNull() ?: 1.0) / (areas.minOrNull() ?: 1.0).coerceAtLeast(1.0) > config.maxTrackAreaRatio) {
            return failure(RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED, "Recorded-HFR physical detector rejected whole-track area inconsistency.")
        }
        val shortSides = interior.map { min(it.second.bounds.width, it.second.bounds.height).toDouble() }
        if ((shortSides.maxOrNull() ?: 1.0) / (shortSides.minOrNull() ?: 1.0).coerceAtLeast(1.0) > config.maxTrackShortSideRatio) {
            return failure(RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED, "Recorded-HFR physical detector rejected whole-track short-side inconsistency.")
        }
        val axisRatios = interior.mapNotNull { it.second.physicalMetrics?.principalAxisRatio }
        if (axisRatios.size >= config.minUsableFrames) {
            val swing = (axisRatios.maxOrNull() ?: 1.0) / (axisRatios.minOrNull() ?: 1.0).coerceAtLeast(1.0)
            if (swing > config.maxPrincipalAxisRatioSwing) {
                return failure(RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED, "Recorded-HFR physical detector rejected whole-track PCA axis-ratio inconsistency.")
            }
        }
        return null
    }

    private fun physicalBlobs(
        frame: RecordedHfrPhysicalFrameMask,
        movingMask: BitSet,
        detectorConfig: BlobDetectionConfig,
        physicalConfig: RecordedHfrPhysicalDetectorConfig,
    ): PhysicalBlobsResult {
        val components = components(frame.width, frame.height, movingMask, detectorConfig)
        val merged = mergeComponents(components, physicalConfig.fragmentMergePx)
        val blobs = mutableListOf<Blob>()
        val rejectedReasons = mutableListOf<RecordedHfrPhysicalDetectorReason>()
        merged.forEach { component ->
            when (val result = component.toBlob(frame, physicalConfig)) {
                is PhysicalBlobBuildResult.Accepted -> blobs += result.blob
                is PhysicalBlobBuildResult.Rejected -> rejectedReasons += result.reason
            }
        }
        return PhysicalBlobsResult(blobs, rejectedReasons)
    }

    private fun PhysicalComponent.toBlob(
        frame: RecordedHfrPhysicalFrameMask,
        config: RecordedHfrPhysicalDetectorConfig,
    ): PhysicalBlobBuildResult {
        val bounds = PixelBounds(minX, minY, maxX, maxY)
        val shortSide = min(bounds.width, bounds.height)
        if (shortSide < config.minVisibleShortSidePx) return PhysicalBlobBuildResult.Rejected(RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED)
        val area = pixels.size
        val centroid = ImagePoint(sumX / area, sumY / area)
        val compactness = area.toDouble() / (bounds.width * bounds.height).toDouble()
        val principalAxisRatio = principalAxisRatio(pixels, centroid, frame.width)
        val roundness = roundness(pixels.toSet(), frame.width, frame.height, area, bounds)
        val solidity = convexHullSolidity(pixels, frame.width, area)
        val capsuleScore = (roundness / 0.35).coerceIn(0.0, 1.0)
        val slots = pixels.map { thresholdSlot(frame, it) }
        val meanHue = circularMeanHueDegrees(slots, frame)
        val meanSaturation = slots.sumOf { unsigned(frame.saturation[it]) / 100.0 } / slots.size
        val meanValue = slots.sumOf { unsigned(frame.value[it]) / 100.0 } / slots.size
        val satStd = stdDev(slots) { unsigned(frame.saturation[it]) / 100.0 }
        val valueStd = stdDev(slots) { unsigned(frame.value[it]) / 100.0 }
        if (solidity < config.minSolidity || principalAxisRatio > config.maxPrincipalAxisRatio) {
            return PhysicalBlobBuildResult.Rejected(RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED)
        }
        if (principalAxisRatio > config.elongatedPrincipalAxisRatio) {
            if (capsuleScore < config.minCapsuleScore) {
                return PhysicalBlobBuildResult.Rejected(RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED)
            }
        } else if (roundness < config.minCompactRoundness) {
            return PhysicalBlobBuildResult.Rejected(RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED)
        }
        if (satStd > config.maxSaturationStdDev || valueStd > config.maxValueStdDev) {
            return PhysicalBlobBuildResult.Rejected(RecordedHfrPhysicalDetectorReason.COLOR_INCOHERENT)
        }
        val metrics = PhysicalBallCandidateMetrics(
            solidity = solidity,
            principalAxisRatio = principalAxisRatio,
            roundness = roundness,
            capsuleScore = capsuleScore,
            meanHueDegrees = meanHue,
            meanSaturation = meanSaturation,
            meanValue = meanValue,
            saturationStdDev = satStd,
            valueStdDev = valueStd,
            touchesFrameEdge = minX == 0 || minY == 0 || maxX == frame.width - 1 || maxY == frame.height - 1,
        )
        return PhysicalBlobBuildResult.Accepted(
            Blob(
                areaPx = area,
                centroid = centroid,
                bounds = bounds,
                compactness = compactness,
                physicalMetrics = metrics,
            ),
        )
    }

    private fun components(
        width: Int,
        height: Int,
        mask: BitSet,
        detectorConfig: BlobDetectionConfig,
    ): List<PhysicalComponent> {
        val visited = BitSet(width * height)
        val queue = IntArray(width * height)
        val out = mutableListOf<PhysicalComponent>()
        var componentCount = 0
        var operations = 0
        var start = mask.nextSetBit(0)
        while (start >= 0) {
            if (!visited[start]) {
                componentCount += 1
                if (componentCount > detectorConfig.bounds.maxComponentsPerFrame) {
                    throw PhysicalDetectorResourceException("Recorded-HFR physical detector exceeded the component cap.")
                }
                val component = collect(start, width, height, mask, visited, queue) { operations += 1 }
                if (operations > detectorConfig.bounds.maxOperationsPerFrame) {
                    throw PhysicalDetectorResourceException("Recorded-HFR physical detector exceeded the operation budget.")
                }
                if (component.pixels.size in detectorConfig.minAreaPx..detectorConfig.maxAreaPx) out += component
            }
            start = mask.nextSetBit(start + 1)
        }
        return out
    }

    private fun collect(
        start: Int,
        width: Int,
        height: Int,
        mask: BitSet,
        visited: BitSet,
        queue: IntArray,
        countOperation: () -> Unit,
    ): PhysicalComponent {
        var head = 0
        var tail = 0
        queue[tail++] = start
        visited.set(start)
        val pixels = mutableListOf<Int>()
        while (head < tail) {
            countOperation()
            val index = queue[head++]
            pixels += index
            val x = index % width
            val y = index / width
            fun enqueue(nx: Int, ny: Int) {
                if (nx !in 0 until width || ny !in 0 until height) return
                val next = ny * width + nx
                if (!mask[next] || visited[next]) return
                visited.set(next)
                queue[tail++] = next
            }
            enqueue(x - 1, y)
            enqueue(x + 1, y)
            enqueue(x, y - 1)
            enqueue(x, y + 1)
        }
        return PhysicalComponent.from(pixels, width)
    }

    private fun mergeComponents(components: List<PhysicalComponent>, mergePx: Int): List<PhysicalComponent> {
        val remaining = components.toMutableList()
        val merged = mutableListOf<PhysicalComponent>()
        while (remaining.isNotEmpty()) {
            var current = remaining.removeAt(0)
            var changed: Boolean
            do {
                changed = false
                val iterator = remaining.iterator()
                while (iterator.hasNext()) {
                    val next = iterator.next()
                    if (current.expandedOverlaps(next, mergePx)) {
                        current = PhysicalComponent.from(current.pixels + next.pixels, current.width)
                        iterator.remove()
                        changed = true
                    }
                }
            } while (changed)
            merged += current
        }
        return merged
    }

    private fun persistentMask(
        masks: List<RecordedHfrPhysicalFrameMask>,
        pixelCount: Int,
        config: RecordedHfrPhysicalDetectorConfig,
    ): BitSet {
        val counts = IntArray(pixelCount)
        masks.forEach { frame ->
            var bit = frame.thresholdMask.nextSetBit(0)
            while (bit >= 0) {
                counts[bit] += 1
                bit = frame.thresholdMask.nextSetBit(bit + 1)
            }
        }
        val minCount = max(config.minUsableFrames, kotlin.math.ceil(masks.size * config.persistentFraction).toInt())
        val persistent = BitSet(pixelCount)
        counts.forEachIndexed { index, count -> if (count >= minCount) persistent.set(index) }
        return persistent
    }

    private fun movingMask(
        order: Int,
        masks: List<RecordedHfrPhysicalFrameMask>,
        persistent: BitSet,
        config: RecordedHfrPhysicalDetectorConfig,
    ): BitSet {
        val current = masks[order]
        val activity = BitSet(current.width * current.height)
        fun addDifference(otherOrder: Int) {
            if (otherOrder !in masks.indices) return
            val diff = current.thresholdMask.clone() as BitSet
            diff.xor(masks[otherOrder].thresholdMask)
            diff.and(current.thresholdMask)
            activity.or(diff)
        }
        addDifference(order - 1)
        addDifference(order + 1)
        val dilated = dilate(activity, current.width, current.height, config.activityDilationPx)
        val moving = current.thresholdMask.clone() as BitSet
        moving.and(dilated)
        val staticWithoutActivity = persistent.clone() as BitSet
        staticWithoutActivity.andNot(dilated)
        moving.andNot(staticWithoutActivity)
        return moving
    }

    private fun globalMotionFailure(
        masks: List<RecordedHfrPhysicalFrameMask>,
        persistent: BitSet,
        detectorConfig: BlobDetectionConfig,
        config: RecordedHfrPhysicalDetectorConfig,
        isCancelled: () -> Boolean,
    ): RecordedHfrPhysicalDetectionOutcome.Failure? {
        if (persistent.isEmpty) return null
        val minBackgroundPixels = max(96, masks.first().width * masks.first().height / 25)
        if (persistent.cardinality() < minBackgroundPixels) return null
        val shifts = masks.zipWithNext().map { (a, b) ->
            dominantShift(
                a = a,
                b = b,
                persistent = persistent,
                searchPx = config.globalMotionSearchPx,
                maxOperations = detectorConfig.bounds.maxOperationsPerFrame,
                isCancelled = isCancelled,
            )
        }
        if (shifts.isEmpty()) return null
        val magnitudes = shifts.map { hypot(it.first.toDouble(), it.second.toDouble()) }
        val median = magnitudes.sorted()[magnitudes.size / 2]
        val maxShift = magnitudes.maxOrNull() ?: 0.0
        if (median > config.maxMedianGlobalShiftPx || maxShift > config.maxAdjacentGlobalShiftPx) {
            return failure(
                RecordedHfrPhysicalDetectorReason.GLOBAL_MOTION_AMBIGUOUS,
                "Recorded-HFR physical detector rejected camera/background motion before ball detection.",
            )
        }
        return null
    }

    private fun dominantShift(
        a: RecordedHfrPhysicalFrameMask,
        b: RecordedHfrPhysicalFrameMask,
        persistent: BitSet,
        searchPx: Int,
        maxOperations: Int,
        isCancelled: () -> Boolean,
    ): Pair<Int, Int> {
        var best = 0 to 0
        var bestScore = Int.MIN_VALUE
        val samples = sampledPersistentBits(persistent, searchPx, maxOperations)
        for (dy in -searchPx..searchPx) {
            for (dx in -searchPx..searchPx) {
                if (isCancelled()) {
                    throw PhysicalDetectorResourceException("Recorded-HFR physical detector global-motion search was cancelled.")
                }
                var score = 0
                samples.forEach { bit ->
                    val x = bit % a.width
                    val y = bit / a.width
                    val nx = x + dx
                    val ny = y + dy
                    if (nx in 0 until a.width && ny in 0 until a.height) {
                        val shifted = ny * a.width + nx
                        if (a.thresholdMask[bit] && b.thresholdMask[shifted]) score += 1
                    }
                }
                if (score > bestScore) {
                    bestScore = score
                    best = dx to dy
                }
            }
        }
        return best
    }

    private fun sampledPersistentBits(persistent: BitSet, searchPx: Int, maxOperations: Int): IntArray {
        val shiftCount = (searchPx * 2 + 1) * (searchPx * 2 + 1)
        val persistentCount = persistent.cardinality()
        val sampleBudget = (maxOperations / shiftCount).coerceAtLeast(1).coerceAtMost(persistentCount)
        val stride = ((persistentCount + sampleBudget - 1) / sampleBudget).coerceAtLeast(1)
        val out = IntArray(sampleBudget)
        var count = 0
        var ordinal = 0
        var bit = persistent.nextSetBit(0)
        while (bit >= 0 && count < sampleBudget) {
            if (ordinal % stride == 0) {
                out[count++] = bit
            }
            ordinal += 1
            bit = persistent.nextSetBit(bit + 1)
        }
        return if (count == out.size) out else out.copyOf(count)
    }

    private fun dilate(mask: BitSet, width: Int, height: Int, radius: Int): BitSet {
        if (radius <= 0) return mask.clone() as BitSet
        val out = BitSet(width * height)
        var bit = mask.nextSetBit(0)
        while (bit >= 0) {
            val cx = bit % width
            val cy = bit / width
            for (y in max(0, cy - radius)..min(height - 1, cy + radius)) {
                for (x in max(0, cx - radius)..min(width - 1, cx + radius)) {
                    out.set(y * width + x)
                }
            }
            bit = mask.nextSetBit(bit + 1)
        }
        return out
    }

    private fun principalAxisRatio(pixels: List<Int>, centroid: ImagePoint, width: Int): Double {
        if (pixels.size < 2) return 1.0
        var xx = 0.0
        var yy = 0.0
        var xy = 0.0
        pixels.forEach { index ->
            val dx = index % width - centroid.xPx
            val dy = index / width - centroid.yPx
            xx += dx * dx
            yy += dy * dy
            xy += dx * dy
        }
        xx /= pixels.size
        yy /= pixels.size
        xy /= pixels.size
        val trace = xx + yy
        val determinantPart = sqrt(max(0.0, (xx - yy) * (xx - yy) + 4.0 * xy * xy))
        val largest = (trace + determinantPart) / 2.0
        val smallest = ((trace - determinantPart) / 2.0).coerceAtLeast(1.0e-6)
        return sqrt(largest / smallest).coerceAtLeast(1.0)
    }

    private fun convexHullSolidity(pixels: List<Int>, width: Int, area: Int): Double {
        val points = pixels
            .map { HullPoint(it % width, it / width) }
            .distinct()
            .sortedWith(compareBy<HullPoint> { it.x }.thenBy { it.y })
        if (points.size < 3) return 1.0
        fun cross(o: HullPoint, a: HullPoint, b: HullPoint): Long =
            (a.x - o.x).toLong() * (b.y - o.y).toLong() - (a.y - o.y).toLong() * (b.x - o.x).toLong()
        val lower = mutableListOf<HullPoint>()
        points.forEach { point ->
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower.last(), point) <= 0L) {
                lower.removeAt(lower.lastIndex)
            }
            lower += point
        }
        val upper = mutableListOf<HullPoint>()
        points.asReversed().forEach { point ->
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper.last(), point) <= 0L) {
                upper.removeAt(upper.lastIndex)
            }
            upper += point
        }
        val hull = (lower.dropLast(1) + upper.dropLast(1))
        if (hull.size < 3) return 1.0
        var twiceArea = 0L
        hull.forEachIndexed { index, point ->
            val next = hull[(index + 1) % hull.size]
            twiceArea += point.x.toLong() * next.y.toLong() - point.y.toLong() * next.x.toLong()
        }
        val hullArea = abs(twiceArea).toDouble() / 2.0
        if (hullArea <= 0.0) return 1.0
        return (area.toDouble() / hullArea).coerceIn(0.0, 1.0)
    }

    private fun roundness(pixelSet: Set<Int>, width: Int, height: Int, area: Int, bounds: PixelBounds): Double {
        var perimeter = 0
        pixelSet.forEach { index ->
            val x = index % width
            val y = index / width
            fun outside(nx: Int, ny: Int): Boolean =
                nx !in 0 until width || ny !in 0 until height || (ny * width + nx) !in pixelSet
            if (outside(x - 1, y)) perimeter += 1
            if (outside(x + 1, y)) perimeter += 1
            if (outside(x, y - 1)) perimeter += 1
            if (outside(x, y + 1)) perimeter += 1
        }
        if (perimeter <= 0) return 0.0
        val circularity = (4.0 * PI * area.toDouble() / (perimeter * perimeter).toDouble()).coerceIn(0.0, 1.0)
        val cornerSize = max(1, min(bounds.width, bounds.height) / 4)
        var cornerPixels = 0
        var cornerArea = 0
        fun addCorner(xRange: IntRange, yRange: IntRange) {
            for (y in yRange) {
                for (x in xRange) {
                    cornerArea += 1
                    if ((y * width + x) in pixelSet) cornerPixels += 1
                }
            }
        }
        addCorner(bounds.left until bounds.left + cornerSize, bounds.top until bounds.top + cornerSize)
        addCorner(bounds.rightInclusive - cornerSize + 1..bounds.rightInclusive, bounds.top until bounds.top + cornerSize)
        addCorner(bounds.left until bounds.left + cornerSize, bounds.bottomInclusive - cornerSize + 1..bounds.bottomInclusive)
        addCorner(bounds.rightInclusive - cornerSize + 1..bounds.rightInclusive, bounds.bottomInclusive - cornerSize + 1..bounds.bottomInclusive)
        val cornerRoundness = if (cornerArea == 0) 0.0 else 1.0 - cornerPixels.toDouble() / cornerArea
        return max(circularity, cornerRoundness).coerceIn(0.0, 1.0)
    }

    private fun thresholdSlot(frame: RecordedHfrPhysicalFrameMask, pixelIndex: Int): Int {
        val slot = frame.thresholdPixelIndexes.binarySearch(pixelIndex)
        if (slot < 0) {
            throw PhysicalDetectorResourceException("Recorded-HFR physical detector lost sparse HSV data for a threshold pixel.")
        }
        return slot
    }

    private fun circularMeanHueDegrees(slots: List<Int>, frame: RecordedHfrPhysicalFrameMask): Double {
        if (slots.isEmpty()) return 0.0
        var x = 0.0
        var y = 0.0
        slots.forEach { slot ->
            val radians = unsigned(frame.hueDegrees[slot]) / 100.0 * 2.0 * PI
            x += cos(radians)
            y += sin(radians)
        }
        val angle = atan2(y / slots.size, x / slots.size)
        return Math.toDegrees(if (angle < 0.0) angle + 2.0 * PI else angle)
    }

    private fun hueDistanceDegrees(a: Double, b: Double): Double {
        val direct = abs(a - b) % 360.0
        return min(direct, 360.0 - direct)
    }

    private fun stdDev(pixels: List<Int>, valueAt: (Int) -> Double): Double {
        if (pixels.isEmpty()) return 0.0
        val mean = pixels.sumOf(valueAt) / pixels.size
        return sqrt(pixels.sumOf { val d = valueAt(it) - mean; d * d } / pixels.size)
    }

    private fun ratioInRange(a: Double, b: Double, minRatio: Double, maxRatio: Double): Boolean {
        val ratio = b / a.coerceAtLeast(1.0)
        return ratio in minRatio..maxRatio
    }

    private fun validateDetectorInputForMask(frame: RgbFrame, config: BlobDetectionConfig): MeasurementRunOutcome.NoRead? {
        config.bounds.validate()?.let { return it }
        if (frame.width <= 0 || frame.height <= 0) return noRead("Frame dimensions must be positive.")
        if (frame.width > config.bounds.maxWidth || frame.height > config.bounds.maxHeight) {
            return noRead("Frame dimensions exceed processing bounds.")
        }
        val expectedPixels = frame.width.toLong() * frame.height.toLong()
        if (expectedPixels > config.bounds.maxPixels || frame.argbPixels.size.toLong() != expectedPixels) {
            return noRead("Frame pixel count exceeds processing bounds or does not match dimensions.")
        }
        return null
    }

    private fun maskResourceFailure(message: String): RecordedHfrPhysicalFrameMaskOutcome.Failure =
        RecordedHfrPhysicalFrameMaskOutcome.Failure(
            VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED,
            message,
            RecordedHfrPhysicalDetectorReason.RESOURCE_LIMIT_EXCEEDED,
        )

    private fun failure(
        reason: RecordedHfrPhysicalDetectorReason,
        message: String,
    ): RecordedHfrPhysicalDetectionOutcome.Failure =
        RecordedHfrPhysicalDetectionOutcome.Failure(
            reason = when (reason) {
                RecordedHfrPhysicalDetectorReason.NO_MOTION_CANDIDATE -> VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS
                RecordedHfrPhysicalDetectorReason.RESOURCE_LIMIT_EXCEEDED -> VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED
                else -> VisualEstimateNoReadReason.AMBIGUOUS_TRACK
            },
            message = "$reason: $message",
            detectorReason = reason,
        )

    private fun noRead(message: String): MeasurementRunOutcome.NoRead =
        MeasurementRunOutcome.NoRead(MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED, message)

    private fun MeasurementRunFailure.toVisualReason(): VisualEstimateNoReadReason =
        when (this) {
            MeasurementRunFailure.BAD_CALIBRATION -> VisualEstimateNoReadReason.BAD_CALIBRATION
            MeasurementRunFailure.BAD_FRAME_SEQUENCE -> VisualEstimateNoReadReason.BAD_TIMESTAMPS
            MeasurementRunFailure.DETECTION_FAILED -> VisualEstimateNoReadReason.DETECTION_FAILED
            MeasurementRunFailure.INSUFFICIENT_DETECTIONS -> VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS
            MeasurementRunFailure.MEASUREMENT_REJECTED -> VisualEstimateNoReadReason.EXCESSIVE_RESIDUAL
            MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED -> VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED
            MeasurementRunFailure.UNPROVEN_TIMING -> VisualEstimateNoReadReason.BAD_TIMESTAMPS
        }

    private fun quantize(value: Double): Byte =
        ((value.coerceIn(0.0, 1.0) * 100.0).toInt() and 0xff).toByte()

    private fun unsigned(value: Byte): Int = value.toInt() and 0xff
}

private data class PhysicalComponent(
    val pixels: List<Int>,
    val width: Int,
    val minX: Int,
    val maxX: Int,
    val minY: Int,
    val maxY: Int,
    val sumX: Double,
    val sumY: Double,
) {
    fun expandedOverlaps(other: PhysicalComponent, expansion: Int): Boolean =
        minX - expansion <= other.maxX &&
            maxX + expansion >= other.minX &&
            minY - expansion <= other.maxY &&
            maxY + expansion >= other.minY

    companion object {
        fun from(pixels: List<Int>, width: Int): PhysicalComponent {
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var sumX = 0.0
            var sumY = 0.0
            pixels.forEach { index ->
                val x = index % width
                val y = index / width
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
                sumX += x
                sumY += y
            }
            return PhysicalComponent(pixels, width, minX, maxX, minY, maxY, sumX, sumY)
        }
    }
}

private data class PhysicalBlobsResult(
    val blobs: List<Blob>,
    val rejectedReasons: List<RecordedHfrPhysicalDetectorReason>,
)

private sealed interface PhysicalBlobBuildResult {
    data class Accepted(val blob: Blob) : PhysicalBlobBuildResult
    data class Rejected(val reason: RecordedHfrPhysicalDetectorReason) : PhysicalBlobBuildResult
}

private class PhysicalDetectorResourceException(message: String) : RuntimeException(message)

private data class HullPoint(val x: Int, val y: Int)
