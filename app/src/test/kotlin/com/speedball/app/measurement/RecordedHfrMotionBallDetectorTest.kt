package com.speedball.app.measurement

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
            motionConfig = RecordedHfrMotionDetectorConfig(openRadiusPx = 0, closeRadiusPx = 1),
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
    fun closeBallBoundaryIsConfigurableButDefaultRejectsOversizedShortSide() {
        val frames = List(7) { index ->
            frame(width = 320, height = 240, timestampSeconds = index / 120.0) {
                disk(cx = 60 + index * 25, cy = 120, radius = 34, color = WHITE)
            }
        }
        val baseConfig = RecordedHfrMotionDetectorConfig(openRadiusPx = 0, closeRadiusPx = 1)

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
    fun savedTennisImpactWindowFailsAsBallNotIsolatedThroughProductionDetector() {
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

        val failure = assertInstanceOf(RecordedHfrMotionDetectionOutcome.Failure::class.java, outcome, outcome.toString())
        assertEquals(VisualEstimateNoReadReason.BALL_NOT_ISOLATED, failure.reason)
    }

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
        fun gray(value: Int): Int = 0xff000000.toInt() or (value shl 16) or (value shl 8) or value
    }
}
