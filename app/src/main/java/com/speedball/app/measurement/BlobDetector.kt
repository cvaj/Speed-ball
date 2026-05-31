package com.speedball.app.measurement

import com.speedball.core.model.ImagePoint
import kotlin.math.hypot

/** Pixel bounds around a detected blob. */
data class PixelBounds(
    val left: Int,
    val top: Int,
    val rightInclusive: Int,
    val bottomInclusive: Int,
) {
    val width: Int get() = rightInclusive - left + 1
    val height: Int get() = bottomInclusive - top + 1
}

/** Connected-component blob selected from one frame. */
data class Blob(
    val areaPx: Int,
    val centroid: ImagePoint,
    val bounds: PixelBounds,
    val compactness: Double,
)

/** Detection settings for one ball-like colored blob. */
data class BlobDetectionConfig(
    val threshold: HsvThreshold,
    val roi: RegionOfInterest,
    val minAreaPx: Int,
    val maxAreaPx: Int,
    val minCompactness: Double = 0.0,
    val bounds: FrameProcessingBounds = FrameProcessingBounds(),
)

sealed interface BlobDetectionOutcome {
    data class Success(val blob: Blob) : BlobDetectionOutcome
    data class Failure(
        val reason: MeasurementRunFailure,
        val message: String,
        val kind: BlobDetectionFailureKind,
    ) : BlobDetectionOutcome
}

enum class BlobDetectionFailureKind {
    NO_BLOB,
    AMBIGUOUS_BLOBS,
    INVALID_INPUT,
    RESOURCE_LIMIT,
}

/** Pure Kotlin connected-components detector for a single thresholded frame. */
object BlobDetector {
    fun detect(frame: RgbFrame, config: BlobDetectionConfig): BlobDetectionOutcome {
        validateDetectorInput(frame, config)?.let {
            return BlobDetectionOutcome.Failure(it.reason, it.message, it.toBlobFailureKind())
        }

        val width = frame.width
        val height = frame.height
        val roi = config.roi.clippedTo(width, height)
            ?: return BlobDetectionOutcome.Failure(
                MeasurementRunFailure.DETECTION_FAILED,
                "ROI does not overlap the frame.",
                BlobDetectionFailureKind.INVALID_INPUT,
            )
        val totalPixels = width * height
        val thresholdMask = BooleanArray(totalPixels)
        var thresholdPixels = 0
        var operations = 0

        for (y in roi.top until roi.bottomExclusive) {
            for (x in roi.left until roi.rightExclusive) {
                operations += 1
                if (operations > config.bounds.maxOperationsPerFrame) {
                    return BlobDetectionOutcome.Failure(
                        MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                        "Per-frame operation budget was exceeded.",
                        BlobDetectionFailureKind.RESOURCE_LIMIT,
                    )
                }
                val index = y * width + x
                if (config.threshold.matches(ColorMath.argbToHsv(frame.argbPixels[index]))) {
                    thresholdMask[index] = true
                    thresholdPixels += 1
                    if (thresholdPixels > config.bounds.maxThresholdPixels) {
                        return BlobDetectionOutcome.Failure(
                            MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                            "Too many threshold-passing pixels in one frame.",
                            BlobDetectionFailureKind.RESOURCE_LIMIT,
                        )
                    }
                }
            }
        }

        val visited = BooleanArray(totalPixels)
        val queue = IntArray(totalPixels)
        val candidates = mutableListOf<Blob>()
        var componentCount = 0

        for (y in roi.top until roi.bottomExclusive) {
            for (x in roi.left until roi.rightExclusive) {
                val startIndex = y * width + x
                if (!thresholdMask[startIndex] || visited[startIndex]) {
                    continue
                }
                componentCount += 1
                if (componentCount > config.bounds.maxComponentsPerFrame) {
                    return BlobDetectionOutcome.Failure(
                        MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                        "Too many connected components in one frame.",
                        BlobDetectionFailureKind.RESOURCE_LIMIT,
                    )
                }

                operations += 1
                if (operations > config.bounds.maxOperationsPerFrame) {
                    return BlobDetectionOutcome.Failure(
                        MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                        "Per-frame operation budget was exceeded.",
                        BlobDetectionFailureKind.RESOURCE_LIMIT,
                    )
                }
                val blob = collectComponent(
                    startIndex = startIndex,
                    width = width,
                    height = height,
                    roi = roi,
                    thresholdMask = thresholdMask,
                    visited = visited,
                    queue = queue,
                    config = config,
                    initialOperations = operations,
                )
                operations = blob.operations
                if (blob.resourceFailure) {
                    return BlobDetectionOutcome.Failure(
                        MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                        "Per-frame operation budget was exceeded.",
                        BlobDetectionFailureKind.RESOURCE_LIMIT,
                    )
                }
                blob.blob?.let(candidates::add)
            }
        }

        return when (candidates.size) {
            0 -> BlobDetectionOutcome.Failure(
                MeasurementRunFailure.DETECTION_FAILED,
                "No single ball blob was detected.",
                BlobDetectionFailureKind.NO_BLOB,
            )
            1 -> BlobDetectionOutcome.Success(candidates.single())
            else -> BlobDetectionOutcome.Failure(
                MeasurementRunFailure.DETECTION_FAILED,
                "Multiple ball-like blobs were detected.",
                BlobDetectionFailureKind.AMBIGUOUS_BLOBS,
            )
        }
    }
}

private data class ComponentResult(
    val blob: Blob?,
    val operations: Int,
    val resourceFailure: Boolean = false,
)

private fun collectComponent(
    startIndex: Int,
    width: Int,
    height: Int,
    roi: RegionOfInterest,
    thresholdMask: BooleanArray,
    visited: BooleanArray,
    queue: IntArray,
    config: BlobDetectionConfig,
    initialOperations: Int,
): ComponentResult {
    var operations = initialOperations
    var head = 0
    var tail = 0
    queue[tail++] = startIndex
    visited[startIndex] = true
    var area = 0
    var sumX = 0.0
    var sumY = 0.0
    var minX = Int.MAX_VALUE
    var maxX = Int.MIN_VALUE
    var minY = Int.MAX_VALUE
    var maxY = Int.MIN_VALUE

    while (head < tail) {
        operations += 1
        if (operations > config.bounds.maxOperationsPerFrame) {
            return ComponentResult(
                blob = null,
                operations = operations,
                resourceFailure = true,
            )
        }
        val index = queue[head++]
        val x = index % width
        val y = index / width
        area += 1
        sumX += x.toDouble()
        sumY += y.toDouble()
        if (x < minX) minX = x
        if (x > maxX) maxX = x
        if (y < minY) minY = y
        if (y > maxY) maxY = y

        fun tryNeighbor(neighborX: Int, neighborY: Int): Boolean {
            operations += 1
            if (operations > config.bounds.maxOperationsPerFrame) {
                return false
            }
            enqueueNeighbor(neighborX, neighborY, width, height, roi, thresholdMask, visited, queue, tail)?.let { tail = it }
            return true
        }

        if (!tryNeighbor(x - 1, y) || !tryNeighbor(x + 1, y) || !tryNeighbor(x, y - 1) || !tryNeighbor(x, y + 1)) {
            return ComponentResult(
                blob = null,
                operations = operations,
                resourceFailure = true,
            )
        }
    }

    if (area < config.minAreaPx || area > config.maxAreaPx) {
        return ComponentResult(blob = null, operations = operations)
    }
    val bounds = PixelBounds(minX, minY, maxX, maxY)
    val compactness = area.toDouble() / (bounds.width * bounds.height).toDouble()
    val centroid = ImagePoint(sumX / area, sumY / area)
    if (!centroid.xPx.isFinite() || !centroid.yPx.isFinite() || !compactness.isFinite() || compactness < config.minCompactness) {
        return ComponentResult(blob = null, operations = operations)
    }
    return ComponentResult(
        blob = Blob(
            areaPx = area,
            centroid = centroid,
            bounds = bounds,
            compactness = compactness,
        ),
        operations = operations,
    )
}

private fun enqueueNeighbor(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
    roi: RegionOfInterest,
    thresholdMask: BooleanArray,
    visited: BooleanArray,
    queue: IntArray,
    tail: Int,
): Int? {
    if (x !in roi.left until roi.rightExclusive || y !in roi.top until roi.bottomExclusive) {
        return null
    }
    if (x !in 0 until width || y !in 0 until height) {
        return null
    }
    val index = y * width + x
    if (!thresholdMask[index] || visited[index]) {
        return null
    }
    visited[index] = true
    queue[tail] = index
    return tail + 1
}

private fun validateDetectorInput(
    frame: RgbFrame,
    config: BlobDetectionConfig,
): MeasurementRunOutcome.NoRead? {
    config.bounds.validate()?.let { return it }
    if (frame.width <= 0 || frame.height <= 0) {
        return MeasurementRunOutcome.NoRead(MeasurementRunFailure.BAD_FRAME_SEQUENCE, "Frame dimensions must be positive.")
    }
    if (frame.width > config.bounds.maxWidth || frame.height > config.bounds.maxHeight) {
        return MeasurementRunOutcome.NoRead(MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED, "Frame dimensions exceed processing bounds.")
    }
    val expectedPixelsLong = frame.width.toLong() * frame.height.toLong()
    if (expectedPixelsLong > config.bounds.maxPixels.toLong() || frame.argbPixels.size.toLong() != expectedPixelsLong) {
        return MeasurementRunOutcome.NoRead(MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED, "Frame pixel count exceeds processing bounds or does not match dimensions.")
    }
    if (config.minAreaPx <= 0 || config.maxAreaPx < config.minAreaPx || !config.minCompactness.isFinite() || config.minCompactness < 0.0) {
        return MeasurementRunOutcome.NoRead(MeasurementRunFailure.DETECTION_FAILED, "Blob selection gates are invalid.")
    }
    if (!frame.timestampSeconds.isFinite()) {
        return MeasurementRunOutcome.NoRead(MeasurementRunFailure.BAD_FRAME_SEQUENCE, "Frame timestamp must be finite.")
    }
    return null
}

internal fun ImagePoint.distanceTo(other: ImagePoint): Double =
    hypot(other.xPx - xPx, other.yPx - yPx)

private fun MeasurementRunOutcome.NoRead.toBlobFailureKind(): BlobDetectionFailureKind =
    when (reason) {
        MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED -> BlobDetectionFailureKind.RESOURCE_LIMIT
        MeasurementRunFailure.DETECTION_FAILED -> BlobDetectionFailureKind.INVALID_INPUT
        MeasurementRunFailure.BAD_FRAME_SEQUENCE,
        MeasurementRunFailure.UNPROVEN_TIMING,
        MeasurementRunFailure.INSUFFICIENT_DETECTIONS,
        MeasurementRunFailure.BAD_CALIBRATION,
        MeasurementRunFailure.MEASUREMENT_REJECTED -> BlobDetectionFailureKind.INVALID_INPUT
    }
