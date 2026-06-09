package com.speedball.app.measurement

import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

class RecordedHfrMotionBallDetectorTest {
    @Test
    fun medianBackgroundIsolatesCompactMovingDiskWithoutColor() {
        val frames = List(7) { index ->
            frame(width = 80, height = 48, timestampSeconds = index / 120.0) {
                disk(cx = 12 + index * 8, cy = 24, radius = 4, color = WHITE)
            }
        }

        val outcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = detectorConfig(width = 80, height = 48),
            motionConfig = smallFixtureMotionConfig(),
        )

        val success = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Success::class.java, outcome, outcome.toString())
        assertEquals(7, success.frames.size)
        assertTrue(success.frames.zipWithNext().all { (previous, current) ->
            current.blobs.single().centroid.xPx > previous.blobs.single().centroid.xPx
        })
        assertTrue(success.frames.all { it.blobs.single().motionMetrics != null })
    }

    @Test
    fun oversizedMovingMassFailsAsBallNotIsolated() {
        val frames = List(7) { index ->
            frame(width = 160, height = 90, timestampSeconds = index / 120.0) {
                rect(left = 10 + index * 5, top = 18, width = 90, height = 46, color = WHITE)
            }
        }

        val outcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = detectorConfig(width = 160, height = 90),
            motionConfig = RecordedHfrMotionDetectorConfig(openRadiusPx = 0, closeRadiusPx = 1),
        )

        val failure = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Failure::class.java, outcome, outcome.toString())
        assertEquals(VisualEstimateNoReadReason.BALL_NOT_ISOLATED, failure.reason)
    }

    @Test
    fun productionConfigKeepsElongatedMotionBlurForTrackSelection() {
        val frames = List(7) { index ->
            frame(width = 640, height = 360, timestampSeconds = index / 120.0) {
                rect(left = 40 + index * 80, top = 140, width = 70, height = 14, color = WHITE)
            }
        }

        val outcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = detectorConfig(width = 640, height = 360),
            motionConfig = RecordedHfrMotionDetectorConfig(openRadiusPx = 0, closeRadiusPx = 0),
        )

        val success = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Success::class.java, outcome, outcome.toString())
        assertTrue(success.frames.size >= 4)
        assertTrue(success.frames.all { frame ->
            frame.blobs.any { blob -> blob.bounds.width > blob.bounds.height * 4 }
        })
        val selectedCentroids = success.frames.map { frame ->
            frame.blobs.maxBy { blob -> blob.bounds.width }.centroid
        }
        assertTrue(selectedCentroids.zipWithNext().all { (previous, current) ->
            current.xPx > previous.xPx
        })
    }

    @Test
    fun productionConfigRejectsTooThinMovingStreaksByMinimumShortSide() {
        val frames = List(7) { index ->
            frame(width = 640, height = 360, timestampSeconds = index / 120.0) {
                rect(left = 80 + index * 30, top = 140, width = 110, height = 8, color = WHITE)
            }
        }

        val outcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = detectorConfig(width = 640, height = 360),
            motionConfig = RecordedHfrMotionDetectorConfig(openRadiusPx = 0, closeRadiusPx = 0),
        )

        val failure = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Failure::class.java, outcome, outcome.toString())
        assertEquals(VisualEstimateNoReadReason.BALL_NOT_ISOLATED, failure.reason)
    }

    @Test
    fun closeBallBoundaryIsConfigurableButDefaultRejectsOversizedShortSide() {
        val frames = List(7) { index ->
            frame(width = 320, height = 240, timestampSeconds = index / 120.0) {
                disk(cx = 60 + index * 25, cy = 120, radius = 34, color = WHITE)
            }
        }
        val baseConfig = smallFixtureMotionConfig()

        val defaultOutcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = detectorConfig(width = 320, height = 240),
            motionConfig = baseConfig,
        )
        val tunedOutcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = detectorConfig(width = 320, height = 240),
            motionConfig = baseConfig.copy(
                maxCandidateShortSideFrameFraction = 0.35,
                maxCandidateAreaFrameFraction = 0.06,
            ),
        )

        assertEquals(
            VisualEstimateNoReadReason.BALL_NOT_ISOLATED,
            assertInstanceOf(RecordedHfrMotionDetectionOutcome.Failure::class.java, defaultOutcome).reason,
        )
        assertInstanceOf(RecordedHfrMotionDetectionOutcome.Success::class.java, tunedOutcome, tunedOutcome.toString())
    }

    @Test
    fun foregroundFragmentsAreRankedByColorCircularShapeBeforePathSelection() {
        val frames = List(8) { index ->
            frame(width = 160, height = 90, timestampSeconds = index / 120.0) {
                disk(cx = 14 + index * 14, cy = 45, radius = 5, color = GREEN)
                rect(left = 74 + index, top = 18, width = 54, height = 24, color = WHITE)
                repeat(8) { order ->
                    disk(
                        cx = 14 + order * 17 + index % 2,
                        cy = 10 + (order % 4) * 18,
                        radius = 3,
                        color = WHITE,
                    )
                }
            }
        }

        val outcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = detectorConfig(width = 160, height = 90),
            motionConfig = RecordedHfrMotionDetectorConfig(
                openRadiusPx = 0,
                closeRadiusPx = 1,
                minCandidateAreaPx = 60,
                minCandidateShortSidePx = 7,
                maxCandidateBlobsPerFrame = 4,
            ),
        )

        val success = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Success::class.java, outcome, outcome.toString())
        assertTrue(success.frames.size >= 4)
        assertTrue(success.frames.all { frame -> frame.blobs.size <= 4 })
        assertTrue(success.frames.zipWithNext().any { (previous, current) ->
            current.blobs.any { currentBlob ->
                previous.blobs.any { previousBlob ->
                    currentBlob.centroid.xPx - previousBlob.centroid.xPx > 6.0
                }
            }
        })
    }

    @Test
    fun colorSeedSplitsBallFromOversizedMovingForegroundParent() {
        val frames = List(7) { index ->
            frame(width = 360, height = 160, timestampSeconds = index / 120.0) {
                val ballX = 40 + index * 42
                rect(left = ballX - 10, top = 58, width = 74, height = 38, color = WHITE)
                disk(cx = ballX, cy = 78, radius = 10, color = YELLOW)
            }
        }

        val outcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = yellowDetectorConfig(width = 360, height = 160),
            motionConfig = RecordedHfrMotionDetectorConfig(
                openRadiusPx = 0,
                closeRadiusPx = 1,
                minCandidateAreaPx = 60,
                minCandidateShortSidePx = 8,
                maxCandidatePrincipalAxisRatio = 1.4,
            ),
        )

        val success = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Success::class.java, outcome, outcome.toString())
        assertTrue(success.frames.size >= 4)
        assertTrue(success.frames.all { frame -> frame.blobs.single().motionMetrics?.parentAreaRatio ?: 1.0 < 0.8 })
        assertTrue(success.frames.zipWithNext().all { (previous, current) ->
            current.blobs.single().centroid.xPx > previous.blobs.single().centroid.xPx
        })
    }

    @Test
    fun impactZoneMasksOutdoorNoiseBeforeCandidateCaps() {
        val frames = List(8) { index ->
            frame(width = 200, height = 120, timestampSeconds = index / 120.0) {
                disk(cx = 24 + index * 14, cy = 62, radius = 6, color = YELLOW)
                repeat(18) { order ->
                    rect(
                        left = 118 + (order % 6) * 12 + index % 3,
                        top = 10 + (order / 6) * 25,
                        width = 8,
                        height = 9,
                        color = WHITE,
                    )
                }
            }
        }

        val outcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = yellowDetectorConfig(width = 200, height = 120),
            motionConfig = RecordedHfrMotionDetectorConfig(
                openRadiusPx = 0,
                closeRadiusPx = 1,
                minCandidateAreaPx = 45,
                minCandidateShortSidePx = 6,
                maxCandidateBlobsPerFrame = 3,
                inclusionPolygon = PixelInclusionPolygon(
                    listOf(
                        ImagePoint(0.0, 35.0),
                        ImagePoint(115.0, 35.0),
                        ImagePoint(115.0, 88.0),
                        ImagePoint(0.0, 88.0),
                    ),
                ),
            ),
        )

        val success = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Success::class.java, outcome, outcome.toString())
        assertTrue(success.candidateBlobCount <= success.frames.size * 2)
        assertTrue(success.frames.all { frame -> frame.blobs.all { blob -> blob.centroid.xPx < 115.0 } })
    }

    @Test
    fun expectedBallSizeRejectsSmallAndLargeMovingJunk() {
        val frames = List(8) { index ->
            frame(width = 220, height = 130, timestampSeconds = index / 120.0) {
                disk(cx = 32 + index * 16, cy = 70, radius = 7, color = YELLOW)
                rect(left = 118 + index, top = 35, width = 6, height = 6, color = WHITE)
                rect(left = 150 + index, top = 76, width = 46, height = 30, color = WHITE)
            }
        }

        val outcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = yellowDetectorConfig(width = 220, height = 130),
            motionConfig = RecordedHfrMotionDetectorConfig(
                openRadiusPx = 0,
                closeRadiusPx = 1,
                minCandidateAreaPx = 20,
                minCandidateShortSidePx = 4,
                maxCandidateBlobsPerFrame = 4,
                expectedBallSizePx = ExpectedBallSizePx(widthPx = 15.0, heightPx = 15.0),
                minExpectedBallAreaRatio = 0.40,
                maxExpectedBallAreaRatio = 2.25,
                maxExpectedBallSideRatioDelta = 0.80,
            ),
        )

        val success = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Success::class.java, outcome, outcome.toString())
        assertTrue(success.frames.size >= 4)
        assertTrue(success.frames.all { frame ->
            frame.blobs.all { blob -> blob.bounds.width in 10..22 && blob.bounds.height in 10..22 }
        })
    }

    @Test
    fun productionConfigRejectsTwentyPixelSpecksAsBallCandidates() {
        val frames = List(7) { index ->
            frame(width = 640, height = 360, timestampSeconds = index / 120.0) {
                repeat(6) { order ->
                    rect(left = 80 + order * 70 + index % 2, top = 80 + order * 20, width = 5, height = 4, color = WHITE)
                }
            }
        }

        val outcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = detectorConfig(width = 640, height = 360),
            motionConfig = RecordedHfrMotionDetectorConfig(openRadiusPx = 0, closeRadiusPx = 0),
        )

        val failure = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Failure::class.java, outcome, outcome.toString())
        assertEquals(VisualEstimateNoReadReason.INSUFFICIENT_DETECTIONS, failure.reason)
        assertTrue(failure.message.contains("no moving ball blobs in the camera frame view"), failure.message)
    }

    @Test
    fun globalLightingChangeFailsBeforeCandidateSelection() {
        val frames = List(7) { index ->
            RgbFrame(
                width = 80,
                height = 48,
                argbPixels = IntArray(80 * 48) { gray(16 + index * 10) },
                timestampSeconds = index / 120.0,
            )
        }

        val outcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = detectorConfig(width = 80, height = 48),
            motionConfig = RecordedHfrMotionDetectorConfig(openRadiusPx = 0, closeRadiusPx = 0),
        )

        val failure = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Failure::class.java, outcome, outcome.toString())
        assertEquals(VisualEstimateNoReadReason.GLOBAL_LIGHTING_CHANGE, failure.reason)
    }

    @Test
    fun shiftedStaticSceneFailsAsGlobalCameraMotion() {
        val frames = List(7) { index ->
            frame(width = 96, height = 54, timestampSeconds = index / 120.0) {
                val shift = index * 3
                shiftedGrid(shiftX = shift, shiftY = 0, color = WHITE)
            }
        }

        val outcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = detectorConfig(width = 96, height = 54),
            motionConfig = RecordedHfrMotionDetectorConfig(openRadiusPx = 0, closeRadiusPx = 1),
        )

        val failure = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Failure::class.java, outcome, outcome.toString())
        assertEquals(VisualEstimateNoReadReason.GLOBAL_CAMERA_MOTION, failure.reason)
    }

    @Test
    fun savedTennisImpactWindowCanRetainBoundedCandidatesThroughProductionDetector() {
        val directory = fixtureDirectory(".interagent/tmp/field-proof-recovery/extracted-1780862777355/impact-window-frames-ppm")
        assumeTrue(directory.isDirectory, "saved impact-window PPM frames are local field-proof artifacts")
        val frames = directory.listFiles { file -> file.extension.equals("ppm", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?.mapIndexed { index, file -> file.toRgbFrame(index / 120.0) }
            ?: emptyList()
        assumeTrue(frames.size >= 7, "saved impact-window frame set must contain the tennis proof frames")

        val outcome = RecordedHfrMotionBallDetector.detect(
            frames = frames,
            detectorConfig = detectorConfig(width = frames.first().width, height = frames.first().height),
            motionConfig = RecordedHfrMotionDetectorConfig(openRadiusPx = 0, closeRadiusPx = 1),
        )

        val success = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Success::class.java, outcome, outcome.toString())
        assertTrue(success.frames.size >= 4)
        assertTrue(success.frames.all { frame -> frame.blobs.size <= RecordedHfrMotionDetectorConfig().maxCandidateBlobsPerFrame })
    }

    private fun smallFixtureMotionConfig(): RecordedHfrMotionDetectorConfig =
        RecordedHfrMotionDetectorConfig(
            openRadiusPx = 0,
            closeRadiusPx = 1,
            minCandidateAreaPx = 20,
            minCandidateShortSidePx = 4,
        )

    private fun detectorConfig(width: Int, height: Int): BlobDetectionConfig =
        BlobDetectionConfig(
            threshold = HsvThreshold(
                center = HsvColor(120.0, 1.0, 1.0),
                tolerance = HsvTolerance(5.0, 0.1, 0.1),
            ),
            roi = RegionOfInterest(0, 0, width, height),
            minAreaPx = 1,
            maxAreaPx = width * height,
            bounds = FrameProcessingBounds(
                maxWidth = width,
                maxHeight = height,
                maxPixels = width * height,
                maxFrameCount = 24,
                maxThresholdPixels = width * height,
                maxComponentsPerFrame = width * height,
                maxOperationsPerFrame = width * height * 80,
            ),
        )

    private fun yellowDetectorConfig(width: Int, height: Int): BlobDetectionConfig =
        detectorConfig(width, height).copy(
            threshold = HsvThreshold(
                center = HsvColor(60.0, 1.0, 1.0),
                tolerance = HsvTolerance(30.0, 0.5, 0.5),
            ),
        )

    private fun frame(
        width: Int,
        height: Int,
        timestampSeconds: Double,
        draw: FramePainter.() -> Unit,
    ): RgbFrame {
        val painter = FramePainter(width, height, DARK_GRAY)
        painter.draw()
        return RgbFrame(width, height, painter.pixels, timestampSeconds)
    }

    private class FramePainter(
        private val width: Int,
        private val height: Int,
        background: Int,
    ) {
        val pixels = IntArray(width * height) { background }

        fun rect(left: Int, top: Int, width: Int, height: Int, color: Int) {
            for (y in top until top + height) {
                for (x in left until left + width) {
                    set(x, y, color)
                }
            }
        }

        fun disk(cx: Int, cy: Int, radius: Int, color: Int) {
            for (y in cy - radius..cy + radius) {
                for (x in cx - radius..cx + radius) {
                    val dx = x - cx
                    val dy = y - cy
                    if (dx * dx + dy * dy <= radius * radius) set(x, y, color)
                }
            }
        }

        fun shiftedGrid(shiftX: Int, shiftY: Int, color: Int) {
            for (y in 0 until height) {
                for (x in 0 until width) {
                    val shiftedX = x - shiftX
                    val shiftedY = y - shiftY
                    if (shiftedX % 16 == 0 || shiftedY % 13 == 0) set(x, y, color)
                }
            }
        }

        private fun set(x: Int, y: Int, color: Int) {
            if (x in 0 until width && y in 0 until height) {
                pixels[y * width + x] = color
            }
        }
    }

    private fun fixtureDirectory(path: String): File {
        val direct = File(path)
        if (direct.exists()) return direct
        return File("..", path)
    }

    private fun File.toRgbFrame(timestampSeconds: Double): RgbFrame {
        val bytes = readBytes()
        var offset = 0
        fun nextToken(): String {
            while (offset < bytes.size) {
                val value = bytes[offset].toInt().and(0xff).toChar()
                if (value == '#') {
                    while (offset < bytes.size && bytes[offset].toInt().and(0xff).toChar() != '\n') offset += 1
                } else if (value.isWhitespace()) {
                    offset += 1
                } else {
                    break
                }
            }
            val start = offset
            while (offset < bytes.size && !bytes[offset].toInt().and(0xff).toChar().isWhitespace()) {
                offset += 1
            }
            return bytes.decodeToString(start, offset)
        }

        require(nextToken() == "P6") { "saved motion detector fixture must be binary PPM" }
        val width = nextToken().toInt()
        val height = nextToken().toInt()
        require(nextToken().toInt() == 255) { "saved motion detector fixture must be 8-bit RGB" }
        while (offset < bytes.size && bytes[offset].toInt().and(0xff).toChar().isWhitespace()) offset += 1
        val pixels = IntArray(width * height)
        repeat(width * height) { index ->
            val base = offset + index * 3
            val red = bytes[base].toInt().and(0xff)
            val green = bytes[base + 1].toInt().and(0xff)
            val blue = bytes[base + 2].toInt().and(0xff)
            pixels[index] = 0xff000000.toInt() or (red shl 16) or (green shl 8) or blue
        }
        return RgbFrame(
            width = width,
            height = height,
            argbPixels = pixels,
            timestampSeconds = timestampSeconds,
        )
    }

    private companion object {
        const val DARK_GRAY = 0xff202020.toInt()
        const val WHITE = 0xffffffff.toInt()
        const val GREEN = 0xff00ff00.toInt()
        const val YELLOW = 0xffffff00.toInt()
        fun gray(value: Int): Int = 0xff000000.toInt() or (value shl 16) or (value shl 8) or value
    }
}
