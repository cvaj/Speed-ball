package com.speedball.app.measurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

class RecordedHfrPhysicalBallDetectorTest {
    @Test
    fun temporalPersistenceIsolatesMovingBallFromStaticSameColorBackground() {
        val frames = List(7) { index ->
            frame(width = 80, height = 48, timestamp = index / 120.0) {
                rect(0, 10, 28, 38)
                disk(cx = 34 + index * 5, cy = 24, radius = 5)
            }
        }

        val result = detect(frames)

        val success = assertInstanceOf(RecordedHfrPhysicalDetectionOutcome.Success::class.java, result)
        assertTrue(success.frames.size >= 4, success.toString())
        val centers = success.frames.map { it.blobs.single().centroid.xPx }
        assertTrue(centers.zipWithNext().all { (a, b) -> b > a }, centers.toString())
        assertTrue(centers.all { it > 25.0 }, "static background must not dominate centroid: $centers")
    }

    @Test
    fun fragmentedMovingBallMergesIntoOnePhysicalCandidatePerFrame() {
        val frames = List(5) { index ->
            frame(width = 80, height = 48, timestamp = index / 120.0) {
                disk(cx = 20 + index * 7, cy = 24, radius = 3)
                disk(cx = 25 + index * 7, cy = 24, radius = 3)
            }
        }

        val result = detect(frames)

        val success = assertInstanceOf(RecordedHfrPhysicalDetectionOutcome.Success::class.java, result)
        assertTrue(success.frames.all { it.blobs.size == 1 })
    }

    @Test
    fun compactBoxFailsRoundnessWhileDiskPasses() {
        val boxFrames = List(5) { index ->
            frame(width = 80, height = 48, timestamp = index / 120.0) {
                rect(20 + index * 5, 18, 31 + index * 5, 29)
            }
        }
        val diskFrames = List(5) { index ->
            frame(width = 80, height = 48, timestamp = index / 120.0) {
                disk(cx = 25 + index * 5, cy = 24, radius = 6)
            }
        }

        val box = detect(boxFrames)
        val disk = detect(diskFrames)

        val failure = assertInstanceOf(RecordedHfrPhysicalDetectionOutcome.Failure::class.java, box)
        assertEquals(RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED, failure.detectorReason)
        assertInstanceOf(RecordedHfrPhysicalDetectionOutcome.Success::class.java, disk)
    }

    @Test
    fun tiltedCapsulePassesOrientationInvariantShapeGate() {
        val frames = List(7) { index ->
            frame(width = 96, height = 64, timestamp = index / 120.0) {
                tiltedCapsule(cx = 24 + index * 6, cy = 22 + index * 2, length = 22, radius = 5)
            }
        }

        val result = detect(frames)

        val success = assertInstanceOf(RecordedHfrPhysicalDetectionOutcome.Success::class.java, result, result.toString())
        assertTrue(success.frames.all { it.blobs.single().physicalMetrics?.principalAxisRatio ?: 0.0 > 1.0 })
    }

    @Test
    fun singleFrameBackgroundJoltFailsGlobalMotionBeforeFakeCandidate() {
        val frames = List(5) { index ->
            frame(width = 80, height = 48, timestamp = index / 120.0) {
                val shift = if (index == 2) 6 else 0
                rect(20 + shift, 4, 22 + shift, 44)
                rect(48 + shift, 4, 50 + shift, 44)
            }
        }

        val result = detect(frames)

        val failure = assertInstanceOf(RecordedHfrPhysicalDetectionOutcome.Failure::class.java, result)
        assertEquals(RecordedHfrPhysicalDetectorReason.GLOBAL_MOTION_AMBIGUOUS, failure.detectorReason)
    }

    @Test
    fun staticSameColorBackgroundFailsAsBackgroundSeparationAmbiguous() {
        val frames = List(5) { index ->
            frame(width = 80, height = 48, timestamp = index / 120.0) {
                rect(0, 0, 70, 42)
            }
        }

        val result = detect(frames)

        val failure = assertInstanceOf(RecordedHfrPhysicalDetectionOutcome.Failure::class.java, result)
        assertEquals(RecordedHfrPhysicalDetectorReason.BACKGROUND_SEPARATION_AMBIGUOUS, failure.detectorReason)
    }

    @Test
    fun colorIncoherentCandidateFailsWithColorReason() {
        val frames = List(5) { index ->
            frame(width = 80, height = 48, timestamp = index / 120.0) {
                variedValueDisk(cx = 24 + index * 5, cy = 24, radius = 6)
            }
        }

        val result = detect(
            frames = frames,
            detectorConfig = config(
                width = 80,
                height = 48,
                threshold = HsvThreshold(
                    center = HsvColor(120.0, 1.0, 0.75),
                    tolerance = HsvTolerance(10.0, 0.2, 0.40),
                ),
            ),
            physicalConfig = RecordedHfrPhysicalDetectorConfig(maxValueStdDev = 0.05),
        )

        val failure = assertInstanceOf(RecordedHfrPhysicalDetectionOutcome.Failure::class.java, result)
        assertEquals(RecordedHfrPhysicalDetectorReason.COLOR_INCOHERENT, failure.detectorReason)
    }

    @Test
    fun componentCapFailsLoudBeforeShapeFallback() {
        val frames = List(5) { index ->
            frame(width = 40, height = 28, timestamp = index / 120.0) {
                checkerboard(offset = index % 2)
            }
        }

        val result = detect(
            frames = frames,
            detectorConfig = config(
                width = 40,
                height = 28,
                bounds = FrameProcessingBounds(
                    maxWidth = 40,
                    maxHeight = 28,
                    maxPixels = 40 * 28,
                    maxFrameCount = 32,
                    maxThresholdPixels = 40 * 28,
                    maxComponentsPerFrame = 3,
                    maxOperationsPerFrame = 40 * 28 * 40,
                ),
            ),
        )

        val failure = assertInstanceOf(RecordedHfrPhysicalDetectionOutcome.Failure::class.java, result)
        assertEquals(RecordedHfrPhysicalDetectorReason.RESOURCE_LIMIT_EXCEEDED, failure.detectorReason)
    }

    @Test
    fun invalidPhysicalDetectorConfigFailsLoud() {
        val frames = List(5) { index ->
            frame(width = 80, height = 48, timestamp = index / 120.0) {
                disk(cx = 20 + index * 5, cy = 24, radius = 6)
            }
        }

        val result = detect(
            frames = frames,
            physicalConfig = RecordedHfrPhysicalDetectorConfig(
                persistentFraction = 0.0,
            ),
        )

        val failure = assertInstanceOf(RecordedHfrPhysicalDetectionOutcome.Failure::class.java, result)
        assertEquals(RecordedHfrPhysicalDetectorReason.RESOURCE_LIMIT_EXCEEDED, failure.detectorReason)
    }

    @Test
    fun frameMaskRetainsHsvOnlyForThresholdPixels() {
        val frame = frame(width = 80, height = 48, timestamp = 0.0) {
            disk(cx = 20, cy = 24, radius = 3)
        }

        val outcome = RecordedHfrPhysicalBallDetector.buildFrameMask(
            frame = frame,
            compactPosition = 0,
            originalFrameIndex = 0,
            presentationTimestampNanos = 0L,
            detectorConfig = config(width = 80, height = 48),
        )

        val mask = assertInstanceOf(RecordedHfrPhysicalFrameMaskOutcome.Success::class.java, outcome).value
        assertEquals(mask.thresholdPixelCount, mask.thresholdPixelIndexes.size)
        assertEquals(mask.thresholdPixelCount, mask.hueDegrees.size)
        assertEquals(mask.thresholdPixelCount, mask.saturation.size)
        assertEquals(mask.thresholdPixelCount, mask.value.size)
        assertTrue(mask.thresholdPixelCount < frame.width * frame.height / 20)
    }

    @Test
    fun detectorNoReadsWhenTooFewPhysicalCandidateFramesSurvive() {
        val frames = List(5) { index ->
            frame(width = 80, height = 48, timestamp = index / 120.0) {
                if (index < 3) {
                    disk(cx = 20 + index * 6, cy = 24, radius = 6)
                } else {
                    rect(20 + index * 6, 18, 31 + index * 6, 29)
                }
            }
        }

        val result = detect(frames)

        val failure = assertInstanceOf(RecordedHfrPhysicalDetectionOutcome.Failure::class.java, result)
        assertEquals(VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS, failure.reason)
    }

    @Test
    fun selectedTrackRejectsPcaAxisRatioSwing() {
        val selected = List(4) { index ->
            selectedBlob(
                frameIndex = index,
                area = 64,
                bounds = PixelBounds(10 + index * 6, 20, 17 + index * 6, 27),
                principalAxisRatio = if (index == 2) 4.5 else 1.2,
            )
        }

        val failure = RecordedHfrPhysicalBallDetector.validateSelectedTrack(selected, RecordedHfrPhysicalDetectorConfig())

        assertEquals(RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED, failure?.detectorReason)
    }

    @Test
    fun selectedTrackRejectsAdjacentColorJump() {
        val selected = List(4) { index ->
            selectedBlob(
                frameIndex = index,
                area = 64,
                bounds = PixelBounds(10 + index * 6, 20, 17 + index * 6, 27),
                meanHueDegrees = if (index == 2) 150.0 else 120.0,
            )
        }

        val failure = RecordedHfrPhysicalBallDetector.validateSelectedTrack(selected, RecordedHfrPhysicalDetectorConfig())

        assertEquals(RecordedHfrPhysicalDetectorReason.COLOR_INCOHERENT, failure?.detectorReason)
    }

    @Test
    fun selectedTrackRejectsInteriorAreaCollapse() {
        val selected = listOf(
            selectedBlob(frameIndex = 0, area = 64, bounds = PixelBounds(10, 20, 17, 27)),
            selectedBlob(frameIndex = 1, area = 64, bounds = PixelBounds(16, 20, 23, 27)),
            selectedBlob(frameIndex = 2, area = 16, bounds = PixelBounds(22, 20, 25, 23)),
            selectedBlob(frameIndex = 3, area = 64, bounds = PixelBounds(28, 20, 35, 27)),
        )

        val failure = RecordedHfrPhysicalBallDetector.validateSelectedTrack(selected, RecordedHfrPhysicalDetectorConfig())

        assertEquals(RecordedHfrPhysicalDetectorReason.SHAPE_REJECTED, failure?.detectorReason)
    }

    @Test
    fun selectedTrackExemptsEdgeTouchingEntryAndExitFromSizeCollapse() {
        val selected = listOf(
            selectedBlob(frameIndex = 0, area = 18, bounds = PixelBounds(0, 20, 2, 25), touchesFrameEdge = true),
            selectedBlob(frameIndex = 1, area = 64, bounds = PixelBounds(10, 20, 17, 27)),
            selectedBlob(frameIndex = 2, area = 64, bounds = PixelBounds(16, 20, 23, 27)),
            selectedBlob(frameIndex = 3, area = 64, bounds = PixelBounds(22, 20, 29, 27)),
            selectedBlob(frameIndex = 4, area = 18, bounds = PixelBounds(79, 20, 79, 27), touchesFrameEdge = true),
        )

        val failure = RecordedHfrPhysicalBallDetector.validateSelectedTrack(selected, RecordedHfrPhysicalDetectorConfig())

        assertEquals(null, failure)
    }

    private fun detect(
        frames: List<RgbFrame>,
        detectorConfig: BlobDetectionConfig = config(frames.first().width, frames.first().height),
        physicalConfig: RecordedHfrPhysicalDetectorConfig = RecordedHfrPhysicalDetectorConfig(),
    ): RecordedHfrPhysicalDetectionOutcome {
        val masks = frames.mapIndexed { index, frame ->
            val outcome = RecordedHfrPhysicalBallDetector.buildFrameMask(
                frame = frame,
                compactPosition = index,
                originalFrameIndex = index,
                presentationTimestampNanos = index * 8_333_333L,
                detectorConfig = detectorConfig,
            )
            assertInstanceOf(RecordedHfrPhysicalFrameMaskOutcome.Success::class.java, outcome).value
        }
        return RecordedHfrPhysicalBallDetector.detect(
            masks = masks,
            detectorConfig = detectorConfig,
            physicalConfig = physicalConfig,
        )
    }

    private fun selectedBlob(
        frameIndex: Int,
        area: Int,
        bounds: PixelBounds,
        principalAxisRatio: Double = 1.2,
        meanHueDegrees: Double = 120.0,
        touchesFrameEdge: Boolean = false,
    ): Pair<VisualEstimateCandidateFrame, Blob> {
        val blob = Blob(
            areaPx = area,
            centroid = com.speedball.core.model.ImagePoint((bounds.left + bounds.rightInclusive) / 2.0, (bounds.top + bounds.bottomInclusive) / 2.0),
            bounds = bounds,
            compactness = area.toDouble() / (bounds.width * bounds.height).toDouble(),
            physicalMetrics = PhysicalBallCandidateMetrics(
                solidity = 0.9,
                principalAxisRatio = principalAxisRatio,
                roundness = 0.9,
                capsuleScore = 0.8,
                meanHueDegrees = meanHueDegrees,
                meanSaturation = 0.9,
                meanValue = 0.9,
                saturationStdDev = 0.02,
                valueStdDev = 0.02,
                touchesFrameEdge = touchesFrameEdge,
            ),
        )
        return VisualEstimateCandidateFrame(
            compactPosition = frameIndex,
            originalFrameIndex = frameIndex,
            timestampSeconds = frameIndex / 120.0,
            width = 80,
            height = 48,
            blobs = listOf(blob),
        ) to blob
    }

    private fun config(
        width: Int,
        height: Int,
        threshold: HsvThreshold = HsvThreshold(
            center = HsvColor(120.0, 1.0, 1.0),
            tolerance = HsvTolerance(10.0, 0.1, 0.1),
        ),
        bounds: FrameProcessingBounds = FrameProcessingBounds(
            maxWidth = width,
            maxHeight = height,
            maxPixels = width * height,
            maxFrameCount = 32,
            maxThresholdPixels = width * height,
            maxComponentsPerFrame = width * height,
            maxOperationsPerFrame = width * height * 40,
        ),
    ): BlobDetectionConfig =
        BlobDetectionConfig(
            threshold = threshold,
            roi = RegionOfInterest(0, 0, width, height),
            minAreaPx = 1,
            maxAreaPx = width * height,
            bounds = bounds,
        )

    private fun frame(width: Int, height: Int, timestamp: Double, draw: FramePainter.() -> Unit): RgbFrame {
        val pixels = IntArray(width * height) { BLACK }
        FramePainter(width, height, pixels).draw()
        return RgbFrame(width, height, pixels, timestamp)
    }

    private class FramePainter(
        private val width: Int,
        private val height: Int,
        private val pixels: IntArray,
    ) {
        fun rect(left: Int, top: Int, rightInclusive: Int, bottomInclusive: Int) {
            for (y in top..bottomInclusive) {
                for (x in left..rightInclusive) set(x, y)
            }
        }

        fun disk(cx: Int, cy: Int, radius: Int) {
            for (y in cy - radius..cy + radius) {
                for (x in cx - radius..cx + radius) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= radius * radius) set(x, y)
                }
            }
        }

        fun variedValueDisk(cx: Int, cy: Int, radius: Int) {
            for (y in cy - radius..cy + radius) {
                for (x in cx - radius..cx + radius) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= radius * radius) {
                        set(x, y, if ((x + y) % 2 == 0) GREEN else DARK_GREEN)
                    }
                }
            }
        }

        fun checkerboard(offset: Int) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    if ((x + y + offset) % 2 == 0) set(x, y)
                }
            }
        }

        fun tiltedCapsule(cx: Int, cy: Int, length: Int, radius: Int) {
            for (step in -length / 2..length / 2) {
                val x = cx + step
                val y = cy + (step * 0.35).roundToInt()
                disk(x, y, radius)
            }
        }

        private fun set(x: Int, y: Int) {
            if (x in 0 until width && y in 0 until height) {
                pixels[y * width + x] = GREEN
            }
        }

        private fun set(x: Int, y: Int, color: Int) {
            if (x in 0 until width && y in 0 until height) {
                pixels[y * width + x] = color
            }
        }
    }

    private companion object {
        const val BLACK = 0xff000000.toInt()
        const val GREEN = 0xff00ff00.toInt()
        const val DARK_GREEN = 0xff008000.toInt()
    }
}
