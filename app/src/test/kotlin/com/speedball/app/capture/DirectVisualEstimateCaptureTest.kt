package com.speedball.app.capture

import com.speedball.app.measurement.BlobDetectionConfig
import com.speedball.app.measurement.FrameProcessingBounds
import com.speedball.app.measurement.HsvColor
import com.speedball.app.measurement.HsvThreshold
import com.speedball.app.measurement.HsvTolerance
import com.speedball.app.measurement.MeasurementCalibrationState
import com.speedball.app.measurement.RegionOfInterest
import com.speedball.app.measurement.TrackExtractionConfig
import com.speedball.app.measurement.VisualEstimateFramePipelineConfig
import com.speedball.app.measurement.VisualEstimateFrameTiming
import com.speedball.core.model.ImagePoint
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists

class DirectVisualEstimateCaptureTest {
    @Test
    fun validationRejectsBusyUnsupportedAndInvalidReadbackConfig() {
        val mode = mode()
        val config = config(mode)

        assertEquals(
            DirectTimingSourceFailure.CAPTURE_BUSY,
            validateDirectVisualEstimateStart(config, listOf(mode), BurstRecorderState.Recording)!!.reason,
        )
        assertEquals(
            DirectTimingSourceFailure.UNSUPPORTED_MODE,
            validateDirectVisualEstimateStart(config, emptyList(), BurstRecorderState.Idle)!!.reason,
        )
        assertEquals(
            DirectTimingSourceFailure.RESOURCE_LIMIT_EXCEEDED,
            validateDirectVisualEstimateStart(config.copy(readbackWidth = 0), listOf(mode), BurstRecorderState.Idle)!!.reason,
        )
        assertEquals(null, validateDirectVisualEstimateStart(config, listOf(mode), BurstRecorderState.Idle))
    }

    @Test
    fun sourceUsesLiveSurfaceTextureReadbackAndNotDecoder() {
        val source = Files.readAllBytes(sourcePath("DirectVisualEstimateCapture.kt")).toString(Charsets.UTF_8)

        assertTrue(source.contains("DirectGlReadbackResources.create"))
        assertTrue(source.contains("updateAndReadArgbFrames"))
        assertTrue(source.contains("VisualEstimateFramePipeline.estimateFromFrames"))
        assertTrue(source.contains("VisualEstimateFramePipelineConfig"))
        assertFalse(source.contains("MediaExtractor"))
        assertFalse(source.contains("MediaMetadataRetriever"))
        assertFalse(source.contains("BurstVideoDecoder"))
    }

    private fun config(mode: HighSpeedMode): DirectVisualEstimateCaptureConfig =
        DirectVisualEstimateCaptureConfig(
            mode = mode,
            calibration = MeasurementCalibrationState(
                pointA = ImagePoint(0.0, 0.0),
                pointB = ImagePoint(3.0, 0.0),
                knownDistanceFeet = 1.0,
            ),
            framePipelineConfig = VisualEstimateFramePipelineConfig(
                trackConfig = TrackExtractionConfig(
                    detectorConfig = BlobDetectionConfig(
                        threshold = HsvThreshold(
                            center = HsvColor(0.0, 1.0, 1.0),
                            tolerance = HsvTolerance(10.0, 0.2, 0.2),
                        ),
                        roi = RegionOfInterest(0, 0, 160, 90),
                        minAreaPx = 1,
                        maxAreaPx = 500,
                        bounds = FrameProcessingBounds(
                            maxWidth = 160,
                            maxHeight = 90,
                            maxPixels = 160 * 90,
                            maxFrameCount = DEFAULT_DIRECT_VISUAL_ESTIMATE_MAX_FRAMES,
                            maxThresholdPixels = 160 * 90,
                            maxComponentsPerFrame = 160 * 90,
                            maxOperationsPerFrame = 160 * 90 * 20,
                        ),
                    ),
                    maxFrameToFrameJumpPx = 160.0,
                ),
                timing = VisualEstimateFrameTiming.PreferRealTimestamps(visualFrameIntervalSeconds = 1.0 / mode.fps),
            ),
        )

    private fun mode(fps: Int = 120): HighSpeedMode =
        HighSpeedMode(
            width = 1280,
            height = 720,
            fps = fps,
            aeTargetFpsLower = fps,
            aeTargetFpsUpper = fps,
            recordSupported = true,
        )

    private fun sourcePath(fileName: String): Path {
        val appPath = Path.of("app/src/main/java/com/speedball/app/capture/$fileName")
        return if (appPath.exists()) {
            appPath
        } else {
            Path.of("src/main/java/com/speedball/app/capture/$fileName")
        }
    }
}
