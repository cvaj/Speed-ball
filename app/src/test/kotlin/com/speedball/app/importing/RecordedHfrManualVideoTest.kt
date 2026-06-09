package com.speedball.app.importing

import com.speedball.app.measurement.BlobDetectionConfig
import com.speedball.app.measurement.CandidateReductionBudget
import com.speedball.app.measurement.EstimateTimingBasis
import com.speedball.app.measurement.FrameProcessingBounds
import com.speedball.app.measurement.HsvColor
import com.speedball.app.measurement.HsvThreshold
import com.speedball.app.measurement.HsvTolerance
import com.speedball.app.measurement.MeasurementCalibrationState
import com.speedball.app.measurement.MinimumSpeedGatePolicy
import com.speedball.app.measurement.RecordedHfrMotionDetectorConfig
import com.speedball.app.measurement.RegionOfInterest
import com.speedball.app.measurement.TrackExtractionConfig
import com.speedball.app.measurement.VisualEstimateFramePipelineConfig
import com.speedball.app.measurement.VisualEstimateOutcome
import com.speedball.app.measurement.VisualEstimatePipelineConfig
import com.speedball.core.model.ImagePoint
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

class RecordedHfrManualVideoTest {
    @Test
    fun manualExtractedWindowRunsThroughRecordedHfrMotionDetector() {
        val framesDir = System.getenv("SPEEDBALL_MANUAL_VIDEO_FRAMES_DIR")?.let(::File)
        assumeTrue(framesDir?.isDirectory == true, "Set SPEEDBALL_MANUAL_VIDEO_FRAMES_DIR to an extracted frame directory.")
        val frameOffset = System.getenv("SPEEDBALL_MANUAL_VIDEO_FRAME_OFFSET")?.toIntOrNull() ?: 0
        val pixelsPerFoot = System.getenv("SPEEDBALL_MANUAL_VIDEO_PIXELS_PER_FOOT")?.toDoubleOrNull() ?: 80.0
        val frameFiles = framesDir!!.listFiles { file -> file.isFile && file.extension.equals("ppm", ignoreCase = true) }
            ?.sortedBy { it.name }
            ?: emptyList()
        assumeTrue(frameFiles.isNotEmpty(), "Manual video frame directory is empty.")
        val decodedFrames = frameFiles.mapIndexed { index, file ->
            val ppm = readPpm(file)
            ImportVideoFrame(
                frameIndex = frameOffset + index,
                presentationTimestampNanos = ((frameOffset + index) * 8_333_333L),
                width = ppm.width,
                height = ppm.height,
                argbPixels = ppm.argbPixels,
            )
        }

        val width = decodedFrames.first().width
        val height = decodedFrames.first().height
        val result = RecordedHfrStreamingEstimate.estimate(
            source = ManualFrameSource(decodedFrames),
            config = manualConfig(
                width = width,
                height = height,
                frameCount = decodedFrames.size,
                pixelsPerFoot = pixelsPerFoot,
            ),
        )

        println(
            "manualVideo scanned=${result.scannedFrameCount} candidates=${result.retainedCandidateFrameCount} " +
                "candidateBlobs=${result.candidateBlobCount} selected=${result.selectedSampleCount} " +
                "sourceValidity=${result.sourceValidity.verdict} outcome=${result.outcome}",
        )
        println(
            result.detectorTrace.frames.joinToString(separator = "\n", prefix = "manualVideoCandidates\n") { frame ->
                val candidates = frame.candidates.joinToString { blob ->
                    "x=${"%.1f".format(blob.centroid.xPx)},y=${"%.1f".format(blob.centroid.yPx)},area=${blob.areaPx}"
                }
                val selected = frame.selectedBlob?.let { blob ->
                    " selected=x=${"%.1f".format(blob.centroid.xPx)},y=${"%.1f".format(blob.centroid.yPx)},area=${blob.areaPx}"
                } ?: ""
                "compact=${frame.frameIndex} original=${frame.originalFrameIndex} t=${"%.6f".format(frame.timestampSeconds)} candidates=[$candidates]$selected"
            },
        )
        assertEquals("PASS", result.sourceValidity.verdict)
        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, result.outcome, result.outcome.toString())
        assertEquals(EstimateTimingBasis.RECORDED_CONTAINER_PRESENTATION_TIMESTAMPS, success.diagnostics.timingBasis)
        assertTrue(result.retainedCandidateFrameCount >= 4, "candidate frames=${result.retainedCandidateFrameCount}")
        assertTrue(success.milesPerHour > 0.0, "mph=${success.milesPerHour}")
    }

    private class ManualFrameSource(
        private val frames: List<ImportVideoFrame>,
    ) : ImportFrameSource, ImportFrameSourceScanLimitTerminal {
        private var index = 0

        override fun nextFrame(): ImportVideoFrame? = frames.getOrNull(index++)

        override fun isTerminalAtScannedFrameCount(scannedFrameCount: Int): Boolean =
            scannedFrameCount >= frames.size

        override fun close() = Unit
    }

    private data class PpmFrame(
        val width: Int,
        val height: Int,
        val argbPixels: IntArray,
    )

    private fun readPpm(file: File): PpmFrame {
        val bytes = file.readBytes()
        var offset = 0
        fun nextToken(): String {
            while (offset < bytes.size && bytes[offset].toInt().toChar().isWhitespace()) offset += 1
            val start = offset
            while (offset < bytes.size && !bytes[offset].toInt().toChar().isWhitespace()) offset += 1
            return bytes.decodeToString(start, offset)
        }
        require(nextToken() == "P6") { "manual video fixture must be binary PPM" }
        val width = nextToken().toInt()
        val height = nextToken().toInt()
        require(nextToken().toInt() == 255) { "manual video fixture must be 8-bit RGB" }
        while (offset < bytes.size && bytes[offset].toInt().toChar().isWhitespace()) offset += 1
        val expectedBytes = width * height * 3
        require(bytes.size - offset >= expectedBytes) { "manual video fixture pixel payload is truncated" }
        val pixels = IntArray(width * height)
        for (pixelIndex in pixels.indices) {
            val base = offset + pixelIndex * 3
            val red = bytes[base].toInt() and 0xff
            val green = bytes[base + 1].toInt() and 0xff
            val blue = bytes[base + 2].toInt() and 0xff
            pixels[pixelIndex] = (0xff shl 24) or (red shl 16) or (green shl 8) or blue
        }
        return PpmFrame(width, height, pixels)
    }

    private fun manualConfig(
        width: Int,
        height: Int,
        frameCount: Int,
        pixelsPerFoot: Double,
    ): RecordedHfrStreamingEstimateConfig {
        val pixelCount = width * height
        return RecordedHfrStreamingEstimateConfig(
            frameIntervalSeconds = 1.0 / 120.0,
            calibration = MeasurementCalibrationState(
                pointA = ImagePoint(0.0, 0.0),
                pointB = ImagePoint(pixelsPerFoot, 0.0),
                knownDistanceFeet = 1.0,
            ),
            framePipelineConfig = VisualEstimateFramePipelineConfig(
                trackConfig = TrackExtractionConfig(
                    detectorConfig = BlobDetectionConfig(
                        threshold = HsvThreshold(
                            center = HsvColor(60.0, 1.0, 1.0),
                            tolerance = HsvTolerance(35.0, 0.5, 0.5),
                        ),
                        roi = RegionOfInterest(0, 0, width, height),
                        minAreaPx = 250,
                        maxAreaPx = pixelCount / 4,
                        minCompactness = 0.0,
                        bounds = FrameProcessingBounds(
                            maxWidth = width,
                            maxHeight = height,
                            maxPixels = pixelCount,
                            maxFrameCount = frameCount,
                            maxThresholdPixels = pixelCount,
                            maxComponentsPerFrame = pixelCount,
                            maxOperationsPerFrame = pixelCount * 80,
                        ),
                    ),
                    maxFrameToFrameJumpPx = width.toDouble(),
                    maxInteriorMisses = 1,
                    allowDirectionalCandidateSelection = true,
                    maxCandidateTimestampGapSpreadRatio = 2.5,
                    candidateReductionBudget = CandidateReductionBudget(
                        maxBlobsPerFrame = 32,
                        maxTotalCandidateBlobs = 150,
                        maxRansacCandidates = 90,
                        maxRansacPairHypotheses = 4_096,
                        ransacCancellationCheckInterval = 8,
                    ),
                ),
                estimateConfig = VisualEstimatePipelineConfig(
                    maxEstimateRmsResidualPx = 24.0,
                    maxOutlierPasses = 0,
                    maxRejectedOutlierCount = 4,
                    allowApparentScaleChangeEstimate = true,
                    requireFittedPathProgression = false,
                    minEstimateMilesPerHour = null,
                    minimumSpeedGatePolicy = MinimumSpeedGatePolicy.DISABLED,
                ),
            ),
            maxScannedFrames = frameCount,
            maxRetainedCandidateFrames = frameCount,
            maxProofFrames = 8,
            proofThumbnailMaxWidth = 96,
            proofThumbnailMaxHeight = 54,
            timingMode = RecordedHfrStreamingTimingMode.CONTAINER_PTS_DELTAS,
            motionDetectorConfig = RecordedHfrMotionDetectorConfig(
                maxCandidatePrincipalAxisRatio = 2.00,
            ),
        )
    }
}
