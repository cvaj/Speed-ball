package com.speedball.app.measurement

import com.speedball.core.model.ImagePoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Fail-loud detector-stage reason for fixed-camera recorded-HFR motion detection. */
enum class RecordedHfrMotionDetectorReason {
    NO_FOREGROUND_MOTION,
    FOREGROUND_AMBIGUOUS,
    BALL_NOT_ISOLATED,
    GLOBAL_CAMERA_MOTION,
    GLOBAL_LIGHTING_CHANGE,
    RESOURCE_LIMIT_EXCEEDED,
}

/**
 * Numeric gates for median-background recorded-HFR ball isolation.
 *
 * The detector is intentionally conservative: it can rank bounded foreground
 * fragments by selected color, preferred near-circular side ratio,
 * compactness, size, and edge contact, but it does not convert those fragments
 * to mph. Side ratio is scoring evidence rather than a standalone veto because
 * close or fast balls can be real motion-blur streaks. The downstream reducer
 * still has to select a smooth, one-directional, high-centroid-motion flight
 * path or the attempt fails loudly before mph conversion.
 */
data class RecordedHfrMotionDetectorConfig(
    val minWindowFramesForMedian: Int = 7,
    val minUsableDetections: Int = 4,
    val lumaDifferenceThreshold: Int = 18,
    val minForegroundAreaPx: Int = 20,
    val maxForegroundAreaFractionPerFrame: Double = 0.45,
    val maxMedianForegroundAreaFraction: Double = 0.35,
    val openRadiusPx: Int = 1,
    val closeRadiusPx: Int = 2,
    val minCandidateAreaPx: Int = 250,
    val maxCandidateAreaFrameFraction: Double = 0.025,
    val maxCandidateLongSideFrameFraction: Double = 0.22,
    val maxCandidateShortSideFrameFraction: Double = 0.20,
    val minCandidateShortSidePx: Int = 12,
    val minCandidateCompactness: Double = 0.20,
    val maxCandidatePrincipalAxisRatio: Double = 1.30,
    val maxCandidateBlobsPerFrame: Int = 6,
    val globalMotionSearchPx: Int = 6,
    val maxMedianGlobalShiftPx: Double = 1.5,
    val maxAdjacentGlobalShiftPx: Double = 4.0,
    val minGlobalMotionImprovementRatio: Double = 0.20,
) {
    fun validate(): MeasurementRunOutcome.NoRead? {
        if (
            minWindowFramesForMedian < 3 ||
            minUsableDetections <= 0 ||
            lumaDifferenceThreshold !in 1..255 ||
            minForegroundAreaPx <= 0 ||
            openRadiusPx < 0 ||
            closeRadiusPx < 0 ||
            minCandidateAreaPx <= 0 ||
            minCandidateShortSidePx <= 0 ||
            maxCandidateBlobsPerFrame <= 0 ||
            globalMotionSearchPx <= 0 ||
            maxCandidatePrincipalAxisRatio < 1.0
        ) {
            return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                "Recorded-HFR motion detector integer gates are invalid.",
            )
        }
        val fractions = listOf(
            maxForegroundAreaFractionPerFrame,
            maxMedianForegroundAreaFraction,
            maxCandidateAreaFrameFraction,
            maxCandidateLongSideFrameFraction,
            maxCandidateShortSideFrameFraction,
            minCandidateCompactness,
            maxCandidatePrincipalAxisRatio,
            maxMedianGlobalShiftPx,
            maxAdjacentGlobalShiftPx,
            minGlobalMotionImprovementRatio,
        )
        if (fractions.any { !it.isFinite() || it <= 0.0 } ||
            maxForegroundAreaFractionPerFrame > 1.0 ||
            maxMedianForegroundAreaFraction > 1.0 ||
            maxCandidateAreaFrameFraction > 1.0 ||
            maxCandidateLongSideFrameFraction > 1.0 ||
            maxCandidateShortSideFrameFraction > 1.0 ||
            minCandidateCompactness > 1.0 ||
            minGlobalMotionImprovementRatio >= 1.0 ||
            globalMotionSearchPx.toDouble() <= maxAdjacentGlobalShiftPx
        ) {
            return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                "Recorded-HFR motion detector fractional gates are invalid.",
            )
        }
        return null
    }
}

sealed interface RecordedHfrMotionDetectionOutcome {
    data class Success(
        val frames: List<VisualEstimateCandidateFrame>,
        val candidateBlobCount: Int,
    ) : RecordedHfrMotionDetectionOutcome

    data class Failure(
        val reason: VisualEstimateNoReadReason,
        val message: String,
        val detectorReason: RecordedHfrMotionDetectorReason,
    ) : RecordedHfrMotionDetectionOutcome
}

/** Pure Kotlin median-background detector for fixed-camera recorded-HFR windows. */
object RecordedHfrMotionBallDetector {
    fun detect(
        frames: List<RgbFrame>,
        detectorConfig: BlobDetectionConfig,
        motionConfig: RecordedHfrMotionDetectorConfig,
        originalFrameIndexes: List<Int> = frames.indices.toList(),
        presentationTimestampNanos: List<Long?> = frames.map { null },
        isCancelled: () -> Boolean = { false },
    ): RecordedHfrMotionDetectionOutcome {
        motionConfig.validate()?.let {
            return failure(RecordedHfrMotionDetectorReason.RESOURCE_LIMIT_EXCEEDED, it.message)
        }
        validateFrames(frames, detectorConfig)?.let { return it }
        if (frames.size < motionConfig.minWindowFramesForMedian) {
            return RecordedHfrMotionDetectionOutcome.Failure(
                VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
                "Recorded-HFR motion detector needs at least ${motionConfig.minWindowFramesForMedian} decoded frames for median background.",
                RecordedHfrMotionDetectorReason.NO_FOREGROUND_MOTION,
            )
        }
        if (originalFrameIndexes.size != frames.size || presentationTimestampNanos.size != frames.size) {
            return failure(RecordedHfrMotionDetectorReason.RESOURCE_LIMIT_EXCEEDED, "Recorded-HFR motion detector metadata lengths must match frames.")
        }
        val width = frames.first().width
        val height = frames.first().height
        val pixelCount = width * height
        val roi = detectorConfig.roi.clippedTo(width, height)
            ?: return failure(RecordedHfrMotionDetectorReason.RESOURCE_LIMIT_EXCEEDED, "ROI does not overlap the frame.")
        val background = medianBackground(frames, pixelCount, isCancelled)
            ?: return failure(RecordedHfrMotionDetectorReason.RESOURCE_LIMIT_EXCEEDED, "Recorded-HFR motion detector was cancelled while building the background.")
        try {
            globalCameraMotionFailure(frames, background, roi, detectorConfig.bounds, motionConfig, isCancelled)?.let { return it }
        } catch (failure: MotionDetectorResourceException) {
            return failure(
                RecordedHfrMotionDetectorReason.RESOURCE_LIMIT_EXCEEDED,
                failure.message ?: "Recorded-HFR motion detector global-motion search failed.",
            )
        }
        val candidateFrames = mutableListOf<VisualEstimateCandidateFrame>()
        val foregroundFractions = mutableListOf<Double>()
        var candidateBlobCount = 0
        var hadForeground = false
        var hadOversizedParent = false
        frames.forEachIndexed { index, frame ->
            if (isCancelled()) return failure(RecordedHfrMotionDetectorReason.RESOURCE_LIMIT_EXCEEDED, "Recorded-HFR motion detector was cancelled.")
            val mask = foregroundMask(frame, background, roi, motionConfig)
            val foregroundCount = mask.countTrue()
            val foregroundFraction = foregroundCount.toDouble() / pixelCount.toDouble()
            foregroundFractions += foregroundFraction
            if (foregroundCount >= motionConfig.minForegroundAreaPx) {
                hadForeground = true
            }
            if (foregroundFraction > motionConfig.maxForegroundAreaFractionPerFrame) {
                return RecordedHfrMotionDetectionOutcome.Failure(
                    VisualEstimateNoReadReason.GLOBAL_LIGHTING_CHANGE,
                    "Recorded-HFR motion foreground covered too much of one frame for a fixed-camera estimate.",
                    RecordedHfrMotionDetectorReason.GLOBAL_LIGHTING_CHANGE,
                )
            }
            if (foregroundCount < motionConfig.minForegroundAreaPx) return@forEachIndexed
            val processedMask = morphologyClose(
                morphologyOpen(mask, width, height, roi, motionConfig.openRadiusPx),
                width,
                height,
                roi,
                motionConfig.closeRadiusPx,
            )
            val components = collectComponents(processedMask, width, height, roi, detectorConfig.bounds, isCancelled)
                ?: return failure(RecordedHfrMotionDetectorReason.RESOURCE_LIMIT_EXCEEDED, "Recorded-HFR motion component processing exceeded resource limits.")
            val candidates = components.flatMap { component ->
                val seededCandidates = component.toColorSeededBallCandidates(
                    frame = frame,
                    foregroundMask = processedMask,
                    width = width,
                    height = height,
                    foregroundAreaFraction = foregroundFraction,
                    threshold = detectorConfig.threshold,
                    bounds = detectorConfig.bounds,
                    config = motionConfig,
                    isCancelled = isCancelled,
                )
                if (seededCandidates.isNotEmpty()) {
                    seededCandidates
                } else {
                    component.toScoredBallCandidate(
                        frame = frame,
                        mask = processedMask,
                        width = width,
                        height = height,
                        foregroundAreaFraction = foregroundFraction,
                        threshold = detectorConfig.threshold,
                        config = motionConfig,
                    )?.let(::listOf) ?: emptyList<ScoredMotionCandidate>().also {
                        if (component.areaPx >= motionConfig.minCandidateAreaPx) {
                            hadOversizedParent = true
                        }
                    }
                }
            }.sortedByDescending { it.score }
                .take(motionConfig.maxCandidateBlobsPerFrame)
                .map { it.blob }
            if (candidates.isNotEmpty()) {
                candidateFrames += VisualEstimateCandidateFrame(
                    compactPosition = candidateFrames.size,
                    originalFrameIndex = originalFrameIndexes[index],
                    timestampSeconds = frame.timestampSeconds,
                    width = width,
                    height = height,
                    blobs = candidates,
                    presentationTimestampNanos = presentationTimestampNanos[index],
                )
                candidateBlobCount += candidates.size
            }
        }
        if (foregroundFractions.median() > motionConfig.maxMedianForegroundAreaFraction) {
            return RecordedHfrMotionDetectionOutcome.Failure(
                VisualEstimateNoReadReason.FOREGROUND_AMBIGUOUS,
                "Recorded-HFR median foreground area is too large for reliable ball isolation.",
                RecordedHfrMotionDetectorReason.FOREGROUND_AMBIGUOUS,
            )
        }
        if (!hadForeground) {
            return RecordedHfrMotionDetectionOutcome.Failure(
                VisualEstimateNoReadReason.NO_FOREGROUND_MOTION,
                NO_MOVING_BLOBS_IN_VIEW_MESSAGE,
                RecordedHfrMotionDetectorReason.NO_FOREGROUND_MOTION,
            )
        }
        if (candidateFrames.size < motionConfig.minUsableDetections) {
            return RecordedHfrMotionDetectionOutcome.Failure(
                if (hadOversizedParent) VisualEstimateNoReadReason.BALL_NOT_ISOLATED else VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS,
                if (hadOversizedParent) {
                    "Moving foreground was detected, but no isolated ball-sized component survived the motion detector."
                } else if (candidateBlobCount == 0) {
                    NO_MOVING_BLOBS_IN_VIEW_MESSAGE
                } else {
                    "Recorded-HFR motion detector found too few isolated ball candidates."
                },
                if (hadOversizedParent) RecordedHfrMotionDetectorReason.BALL_NOT_ISOLATED else RecordedHfrMotionDetectorReason.NO_FOREGROUND_MOTION,
            )
        }
        return RecordedHfrMotionDetectionOutcome.Success(candidateFrames, candidateBlobCount)
    }

    private fun validateFrames(
        frames: List<RgbFrame>,
        detectorConfig: BlobDetectionConfig,
    ): RecordedHfrMotionDetectionOutcome.Failure? {
        detectorConfig.bounds.validate()?.let {
            return failure(RecordedHfrMotionDetectorReason.RESOURCE_LIMIT_EXCEEDED, it.message)
        }
        if (frames.isEmpty()) {
            return failure(RecordedHfrMotionDetectorReason.NO_FOREGROUND_MOTION, "Recorded-HFR motion detector received no frames.")
        }
        val width = frames.first().width
        val height = frames.first().height
        if (width <= 0 || height <= 0) {
            return failure(RecordedHfrMotionDetectorReason.RESOURCE_LIMIT_EXCEEDED, "Recorded-HFR motion frame dimensions are invalid.")
        }
        val pixelCount = width.toLong() * height.toLong()
        if (
            width > detectorConfig.bounds.maxWidth ||
            height > detectorConfig.bounds.maxHeight ||
            pixelCount <= 0L ||
            pixelCount > detectorConfig.bounds.maxPixels.toLong() ||
            frames.size > detectorConfig.bounds.maxFrameCount
        ) {
            return failure(RecordedHfrMotionDetectorReason.RESOURCE_LIMIT_EXCEEDED, "Recorded-HFR motion frames exceed processing bounds.")
        }
        frames.forEach { frame ->
            if (frame.width != width || frame.height != height || frame.argbPixels.size.toLong() != pixelCount || !frame.timestampSeconds.isFinite()) {
                return failure(RecordedHfrMotionDetectorReason.RESOURCE_LIMIT_EXCEEDED, "Recorded-HFR motion frames must have matching dimensions, pixels, and finite timestamps.")
            }
        }
        return null
    }
}

private const val NO_MOVING_BLOBS_IN_VIEW_MESSAGE =
    "It appears there are no moving ball blobs in the camera frame view."

private data class MotionComponent(
    val areaPx: Int,
    val centroid: ImagePoint,
    val bounds: PixelBounds,
    val compactness: Double,
    val touchesFrameEdge: Boolean,
)

private data class ScoredMotionCandidate(
    val blob: Blob,
    val score: Double,
)

private fun MotionComponent.toScoredBallCandidate(
    frame: RgbFrame,
    mask: BooleanArray,
    width: Int,
    height: Int,
    foregroundAreaFraction: Double,
    threshold: HsvThreshold,
    config: RecordedHfrMotionDetectorConfig,
    parentAreaPx: Int = areaPx,
    parentAreaRatio: Double = 1.0,
): ScoredMotionCandidate? {
    val frameArea = width * height
    val maxArea = max(config.minCandidateAreaPx, (frameArea * config.maxCandidateAreaFrameFraction).toInt())
    val longSide = max(bounds.width, bounds.height)
    val shortSide = min(bounds.width, bounds.height)
    val maxLongSide = (max(width, height) * config.maxCandidateLongSideFrameFraction).toInt().coerceAtLeast(config.minCandidateShortSidePx)
    val maxShortSide = (min(width, height) * config.maxCandidateShortSideFrameFraction).toInt().coerceAtLeast(config.minCandidateShortSidePx)
    val axisRatio = longSide.toDouble() / shortSide.coerceAtLeast(1).toDouble()
    if (
        areaPx < config.minCandidateAreaPx ||
        areaPx > maxArea ||
        longSide > maxLongSide ||
        shortSide > maxShortSide ||
        shortSide < config.minCandidateShortSidePx ||
        compactness < config.minCandidateCompactness
    ) {
        return null
    }
    val colorMatchFraction = colorMatchFraction(frame, mask, bounds, threshold)
    val sideRatioRange = (config.maxCandidatePrincipalAxisRatio - 1.0).coerceAtLeast(1.0e-6)
    val sideRatioScore = (1.0 - ((axisRatio - 1.0) / sideRatioRange)).coerceIn(0.0, 1.0)
    val compactnessScore = compactness.coerceIn(0.0, 1.0)
    val areaScore = (areaPx.toDouble() / maxArea.toDouble()).coerceIn(0.0, 1.0)
    val edgePenalty = if (touchesFrameEdge) 0.20 else 0.0
    val score = colorMatchFraction * 4.0 +
        sideRatioScore * 3.0 +
        compactnessScore +
        areaScore * 0.5 -
        edgePenalty
    return ScoredMotionCandidate(
        blob = Blob(
            areaPx = areaPx,
            centroid = centroid,
            bounds = bounds,
            compactness = compactness,
            motionMetrics = MotionBallCandidateMetrics(
                parentAreaPx = parentAreaPx,
                parentAreaRatio = parentAreaRatio,
                foregroundAreaFraction = foregroundAreaFraction,
                touchesFrameEdge = touchesFrameEdge,
            ),
        ),
        score = score,
    )
}

private fun MotionComponent.toColorSeededBallCandidates(
    frame: RgbFrame,
    foregroundMask: BooleanArray,
    width: Int,
    height: Int,
    foregroundAreaFraction: Double,
    threshold: HsvThreshold,
    bounds: FrameProcessingBounds,
    config: RecordedHfrMotionDetectorConfig,
    isCancelled: () -> Boolean,
): List<ScoredMotionCandidate> {
    val colorMask = BooleanArray(foregroundMask.size)
    var colorPixelCount = 0
    for (y in this.bounds.top..this.bounds.bottomInclusive) {
        for (x in this.bounds.left..this.bounds.rightInclusive) {
            val index = y * width + x
            if (!foregroundMask[index]) continue
            if (threshold.matches(ColorMath.argbToHsv(frame.argbPixels[index]))) {
                colorMask[index] = true
                colorPixelCount += 1
            }
        }
    }
    if (colorPixelCount < config.minCandidateAreaPx) return emptyList()
    val childRoi = RegionOfInterest(
        left = this.bounds.left,
        top = this.bounds.top,
        rightExclusive = this.bounds.rightInclusive + 1,
        bottomExclusive = this.bounds.bottomInclusive + 1,
    )
    val components = collectComponents(colorMask, width, height, childRoi, bounds, isCancelled) ?: return emptyList()
    return components.mapNotNull { child ->
        child.toScoredBallCandidate(
            frame = frame,
            mask = colorMask,
            width = width,
            height = height,
            foregroundAreaFraction = foregroundAreaFraction,
            threshold = threshold,
            config = config,
            parentAreaPx = areaPx,
            parentAreaRatio = child.areaPx.toDouble() / areaPx.toDouble(),
        )
    }
}

private fun colorMatchFraction(
    frame: RgbFrame,
    mask: BooleanArray,
    bounds: PixelBounds,
    threshold: HsvThreshold,
): Double {
    var pixels = 0
    var matches = 0
    for (y in bounds.top..bounds.bottomInclusive) {
        for (x in bounds.left..bounds.rightInclusive) {
            val index = y * frame.width + x
            if (!mask[index]) continue
            pixels += 1
            if (threshold.matches(ColorMath.argbToHsv(frame.argbPixels[index]))) {
                matches += 1
            }
        }
    }
    return if (pixels == 0) 0.0 else matches.toDouble() / pixels.toDouble()
}

private fun medianBackground(
    frames: List<RgbFrame>,
    pixelCount: Int,
    isCancelled: () -> Boolean,
): IntArray? {
    val values = IntArray(frames.size)
    return IntArray(pixelCount) { pixel ->
        if (isCancelled()) return null
        frames.forEachIndexed { index, frame -> values[index] = luma(frame.argbPixels[pixel]) }
        values.sort()
        values[values.size / 2]
    }
}

private fun globalCameraMotionFailure(
    frames: List<RgbFrame>,
    background: IntArray,
    roi: RegionOfInterest,
    bounds: FrameProcessingBounds,
    config: RecordedHfrMotionDetectorConfig,
    isCancelled: () -> Boolean,
): RecordedHfrMotionDetectionOutcome.Failure? {
    if (frames.size < 2) return null
    val samples = sampledBackgroundStructurePixels(
        background = background,
        roi = roi,
        width = frames.first().width,
        maxOperations = bounds.maxOperationsPerFrame,
        searchPx = config.globalMotionSearchPx,
    )
    if (samples.isEmpty()) return null
    val shifts = frames.mapNotNull { frame ->
        dominantBackgroundShift(
            frame = frame,
            background = background,
            samples = samples,
            roi = roi,
            searchPx = config.globalMotionSearchPx,
            maxOperations = bounds.maxOperationsPerFrame,
            minImprovementRatio = config.minGlobalMotionImprovementRatio,
            isCancelled = isCancelled,
        )
    }
    if (shifts.isEmpty()) return null
    val magnitudes = shifts.map { hypot(it.first.toDouble(), it.second.toDouble()) }
    val median = magnitudes.sorted()[magnitudes.size / 2]
    val maxShift = magnitudes.maxOrNull() ?: 0.0
    if (median > config.maxMedianGlobalShiftPx || maxShift > config.maxAdjacentGlobalShiftPx) {
        return failure(
            RecordedHfrMotionDetectorReason.GLOBAL_CAMERA_MOTION,
            "Recorded-HFR motion detector rejected camera/background movement before ball detection.",
        )
    }
    return null
}

private fun dominantBackgroundShift(
    frame: RgbFrame,
    background: IntArray,
    samples: IntArray,
    roi: RegionOfInterest,
    searchPx: Int,
    maxOperations: Int,
    minImprovementRatio: Double,
    isCancelled: () -> Boolean,
): Pair<Int, Int>? {
    var zeroAverage: Double? = null
    var best = 0 to 0
    var bestAverage = Double.POSITIVE_INFINITY
    var operations = 0
    for (dy in -searchPx..searchPx) {
        for (dx in -searchPx..searchPx) {
            if (isCancelled()) {
                throw MotionDetectorResourceException("Recorded-HFR motion detector global-motion search was cancelled.")
            }
            var sum = 0L
            var count = 0
            for (sample in samples) {
                operations += 1
                if (operations > maxOperations) {
                    throw MotionDetectorResourceException("Recorded-HFR motion detector global-motion search exceeded the operation budget.")
                }
                val x = sample % frame.width
                val y = sample / frame.width
                val nx = x + dx
                val ny = y + dy
                if (nx !in roi.left until roi.rightExclusive || ny !in roi.top until roi.bottomExclusive) continue
                val shifted = ny * frame.width + nx
                sum += kotlin.math.abs(background[sample] - luma(frame.argbPixels[shifted]))
                count += 1
            }
            if (count == 0) continue
            val average = sum.toDouble() / count.toDouble()
            if (dx == 0 && dy == 0) zeroAverage = average
            if (average < bestAverage) {
                bestAverage = average
                best = dx to dy
            }
        }
    }
    val zero = zeroAverage ?: return null
    if (zero <= 0.0) return null
    val improvement = (zero - bestAverage) / zero
    return if (best != (0 to 0) && improvement >= minImprovementRatio) best else null
}

private fun sampledBackgroundStructurePixels(
    background: IntArray,
    roi: RegionOfInterest,
    width: Int,
    maxOperations: Int,
    searchPx: Int,
): IntArray {
    val shiftCount = (searchPx * 2 + 1) * (searchPx * 2 + 1)
    val roiPixels = (roi.rightExclusive - roi.left) * (roi.bottomExclusive - roi.top)
    if (roiPixels <= 0) return IntArray(0)
    val sampleBudget = (maxOperations / shiftCount).coerceAtLeast(1).coerceAtMost(roiPixels)
    val stride = ((roiPixels + sampleBudget - 1) / sampleBudget).coerceAtLeast(1)
    val out = IntArray(sampleBudget)
    val occupiedCells = BooleanArray(BACKGROUND_STRUCTURE_GRID_COLUMNS * BACKGROUND_STRUCTURE_GRID_ROWS)
    var written = 0
    var ordinal = 0
    for (y in roi.top until roi.bottomExclusive) {
        for (x in roi.left until roi.rightExclusive) {
            if (ordinal % stride == 0 && written < out.size && isBackgroundStructure(background, width, x, y, roi)) {
                out[written++] = y * width + x
                val cellX = ((x - roi.left) * BACKGROUND_STRUCTURE_GRID_COLUMNS / (roi.rightExclusive - roi.left)).coerceIn(0, BACKGROUND_STRUCTURE_GRID_COLUMNS - 1)
                val cellY = ((y - roi.top) * BACKGROUND_STRUCTURE_GRID_ROWS / (roi.bottomExclusive - roi.top)).coerceIn(0, BACKGROUND_STRUCTURE_GRID_ROWS - 1)
                occupiedCells[cellY * BACKGROUND_STRUCTURE_GRID_COLUMNS + cellX] = true
            }
            ordinal += 1
        }
    }
    if (occupiedCells.count { it } < BACKGROUND_STRUCTURE_MIN_OCCUPIED_GRID_CELLS) return IntArray(0)
    return out.copyOf(written)
}

private fun isBackgroundStructure(
    background: IntArray,
    width: Int,
    x: Int,
    y: Int,
    roi: RegionOfInterest,
): Boolean {
    val center = background[y * width + x]
    val left = if (x > roi.left) background[y * width + x - 1] else center
    val right = if (x + 1 < roi.rightExclusive) background[y * width + x + 1] else center
    val up = if (y > roi.top) background[(y - 1) * width + x] else center
    val down = if (y + 1 < roi.bottomExclusive) background[(y + 1) * width + x] else center
    return max(
        max(kotlin.math.abs(center - left), kotlin.math.abs(center - right)),
        max(kotlin.math.abs(center - up), kotlin.math.abs(center - down)),
    ) >= BACKGROUND_STRUCTURE_LUMA_DELTA
}

private fun foregroundMask(
    frame: RgbFrame,
    background: IntArray,
    roi: RegionOfInterest,
    config: RecordedHfrMotionDetectorConfig,
): BooleanArray {
    val mask = BooleanArray(frame.width * frame.height)
    for (y in roi.top until roi.bottomExclusive) {
        for (x in roi.left until roi.rightExclusive) {
            val index = y * frame.width + x
            if (kotlin.math.abs(luma(frame.argbPixels[index]) - background[index]) >= config.lumaDifferenceThreshold) {
                mask[index] = true
            }
        }
    }
    return mask
}

private fun morphologyOpen(mask: BooleanArray, width: Int, height: Int, roi: RegionOfInterest, radius: Int): BooleanArray =
    if (radius == 0) mask else dilate(erode(mask, width, height, roi, radius), width, height, roi, radius)

private fun morphologyClose(mask: BooleanArray, width: Int, height: Int, roi: RegionOfInterest, radius: Int): BooleanArray =
    if (radius == 0) mask else erode(dilate(mask, width, height, roi, radius), width, height, roi, radius)

private fun erode(mask: BooleanArray, width: Int, height: Int, roi: RegionOfInterest, radius: Int): BooleanArray {
    val output = BooleanArray(mask.size)
    for (y in roi.top until roi.bottomExclusive) {
        for (x in roi.left until roi.rightExclusive) {
            var keep = true
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val px = x + dx
                    val py = y + dy
                    if (px !in roi.left until roi.rightExclusive || py !in roi.top until roi.bottomExclusive || px !in 0 until width || py !in 0 until height || !mask[py * width + px]) {
                        keep = false
                    }
                }
            }
            output[y * width + x] = keep
        }
    }
    return output
}

private fun dilate(mask: BooleanArray, width: Int, height: Int, roi: RegionOfInterest, radius: Int): BooleanArray {
    val output = BooleanArray(mask.size)
    for (y in roi.top until roi.bottomExclusive) {
        for (x in roi.left until roi.rightExclusive) {
            if (!mask[y * width + x]) continue
            for (dy in -radius..radius) {
                for (dx in -radius..radius) {
                    val px = x + dx
                    val py = y + dy
                    if (px in roi.left until roi.rightExclusive && py in roi.top until roi.bottomExclusive && px in 0 until width && py in 0 until height) {
                        output[py * width + px] = true
                    }
                }
            }
        }
    }
    return output
}

private fun collectComponents(
    mask: BooleanArray,
    width: Int,
    height: Int,
    roi: RegionOfInterest,
    bounds: FrameProcessingBounds,
    isCancelled: () -> Boolean,
): List<MotionComponent>? {
    val visited = BooleanArray(mask.size)
    val queue = IntArray(mask.size)
    val components = mutableListOf<MotionComponent>()
    var operations = 0
    for (y in roi.top until roi.bottomExclusive) {
        for (x in roi.left until roi.rightExclusive) {
            if (isCancelled()) return null
            val start = y * width + x
            if (!mask[start] || visited[start]) continue
            if (components.size >= bounds.maxComponentsPerFrame) return null
            val component = collectComponent(start, mask, visited, queue, width, height, roi, operations, bounds.maxOperationsPerFrame)
                ?: return null
            operations = component.operations
            components += component.component
        }
    }
    return components
}

private data class ComponentCollectResult(
    val component: MotionComponent,
    val operations: Int,
)

private fun collectComponent(
    start: Int,
    mask: BooleanArray,
    visited: BooleanArray,
    queue: IntArray,
    width: Int,
    height: Int,
    roi: RegionOfInterest,
    initialOperations: Int,
    maxOperations: Int,
): ComponentCollectResult? {
    var operations = initialOperations
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
        operations += 1
        if (operations > maxOperations) return null
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
        for ((nx, ny) in arrayOf(x - 1 to y, x + 1 to y, x to y - 1, x to y + 1)) {
            operations += 1
            if (operations > maxOperations) return null
            if (nx !in roi.left until roi.rightExclusive || ny !in roi.top until roi.bottomExclusive || nx !in 0 until width || ny !in 0 until height) continue
            val neighbor = ny * width + nx
            if (!mask[neighbor] || visited[neighbor]) continue
            visited[neighbor] = true
            queue[tail++] = neighbor
        }
    }
    val bounds = PixelBounds(minX, minY, maxX, maxY)
    val compactness = area.toDouble() / (bounds.width * bounds.height).toDouble()
    return ComponentCollectResult(
        component = MotionComponent(
            areaPx = area,
            centroid = ImagePoint(sumX / area, sumY / area),
            bounds = bounds,
            compactness = compactness,
            touchesFrameEdge = minX == 0 || minY == 0 || maxX == width - 1 || maxY == height - 1,
        ),
        operations = operations,
    )
}

private fun BooleanArray.countTrue(): Int =
    count { it }

private fun List<Double>.median(): Double {
    if (isEmpty()) return 0.0
    val sorted = sorted()
    return sorted[sorted.size / 2]
}

private fun luma(argb: Int): Int {
    val red = (argb shr 16) and 0xff
    val green = (argb shr 8) and 0xff
    val blue = argb and 0xff
    return (red * 299 + green * 587 + blue * 114) / 1000
}

private class MotionDetectorResourceException(message: String) : RuntimeException(message)

private const val BACKGROUND_STRUCTURE_LUMA_DELTA = 24
private const val BACKGROUND_STRUCTURE_GRID_COLUMNS = 6
private const val BACKGROUND_STRUCTURE_GRID_ROWS = 4
private const val BACKGROUND_STRUCTURE_MIN_OCCUPIED_GRID_CELLS = 16

private fun failure(
    reason: RecordedHfrMotionDetectorReason,
    message: String,
): RecordedHfrMotionDetectionOutcome.Failure =
    RecordedHfrMotionDetectionOutcome.Failure(reason.toVisualReason(), message, reason)

private fun RecordedHfrMotionDetectorReason.toVisualReason(): VisualEstimateNoReadReason =
    when (this) {
        RecordedHfrMotionDetectorReason.NO_FOREGROUND_MOTION -> VisualEstimateNoReadReason.NO_FOREGROUND_MOTION
        RecordedHfrMotionDetectorReason.FOREGROUND_AMBIGUOUS -> VisualEstimateNoReadReason.FOREGROUND_AMBIGUOUS
        RecordedHfrMotionDetectorReason.BALL_NOT_ISOLATED -> VisualEstimateNoReadReason.BALL_NOT_ISOLATED
        RecordedHfrMotionDetectorReason.GLOBAL_CAMERA_MOTION -> VisualEstimateNoReadReason.GLOBAL_CAMERA_MOTION
        RecordedHfrMotionDetectorReason.GLOBAL_LIGHTING_CHANGE -> VisualEstimateNoReadReason.GLOBAL_LIGHTING_CHANGE
        RecordedHfrMotionDetectorReason.RESOURCE_LIMIT_EXCEEDED -> VisualEstimateNoReadReason.RESOURCE_LIMIT_EXCEEDED
    }
