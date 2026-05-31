package com.speedball.app.measurement

internal const val RED_ARGB: Int = -65_536
internal const val BLACK_ARGB: Int = -16_777_216

internal fun defaultConfig(
    width: Int,
    height: Int,
    roi: RegionOfInterest = RegionOfInterest(0, 0, width, height),
    minArea: Int = 1,
    maxArea: Int = width * height,
    bounds: FrameProcessingBounds = FrameProcessingBounds(
        maxWidth = width,
        maxHeight = height,
        maxPixels = width * height,
        maxFrameCount = 20,
        maxThresholdPixels = width * height,
        maxComponentsPerFrame = width * height,
        maxOperationsPerFrame = width * height * 20,
    ),
): BlobDetectionConfig =
    BlobDetectionConfig(
        threshold = HsvThreshold(
            center = HsvColor(0.0, 1.0, 1.0),
            tolerance = HsvTolerance(8.0, 0.1, 0.1),
        ),
        roi = roi,
        minAreaPx = minArea,
        maxAreaPx = maxArea,
        bounds = bounds,
    )

internal fun frameWithRedPixels(
    width: Int,
    height: Int,
    redPixels: Set<Pair<Int, Int>>,
    timestampSeconds: Double = 0.0,
): RgbFrame =
    frameWithPixels(
        width = width,
        height = height,
        redPixels = redPixels.associateWith { RED_ARGB },
        timestampSeconds = timestampSeconds,
    )

internal fun frameWithPixels(
    width: Int,
    height: Int,
    redPixels: Map<Pair<Int, Int>, Int>,
    timestampSeconds: Double = 0.0,
): RgbFrame {
    val pixels = IntArray(width * height) { BLACK_ARGB }
    redPixels.forEach { (point, color) ->
        val (x, y) = point
        pixels[y * width + x] = color
    }
    return RgbFrame(width = width, height = height, argbPixels = pixels, timestampSeconds = timestampSeconds)
}
