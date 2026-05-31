package com.speedball.app.measurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BlobDetectorTest {
    @Test
    fun detectsSingleBlobCentroid() {
        val frame = frameWithRedPixels(
            width = 8,
            height = 8,
            redPixels = setOf(3 to 4, 4 to 4, 3 to 5, 4 to 5),
        )

        val outcome = BlobDetector.detect(frame, defaultConfig(width = 8, height = 8))

        assertTrue(outcome is BlobDetectionOutcome.Success)
        val blob = (outcome as BlobDetectionOutcome.Success).blob
        assertEquals(4, blob.areaPx)
        assertEquals(3.5, blob.centroid.xPx, 1.0e-9)
        assertEquals(4.5, blob.centroid.yPx, 1.0e-9)
    }

    @Test
    fun rejectsNoBlobTwoBlobsOffRoiAndTinyBlob() {
        assertFailure(
            frame = frameWithRedPixels(8, 8, emptySet()),
            reason = MeasurementRunFailure.DETECTION_FAILED,
            kind = BlobDetectionFailureKind.NO_BLOB,
        )
        assertFailure(
            frame = frameWithRedPixels(8, 8, setOf(1 to 1, 6 to 6)),
            reason = MeasurementRunFailure.DETECTION_FAILED,
            kind = BlobDetectionFailureKind.AMBIGUOUS_BLOBS,
        )
        assertFailure(
            frame = frameWithRedPixels(8, 8, setOf(1 to 1, 1 to 2)),
            config = defaultConfig(width = 8, height = 8, roi = RegionOfInterest(4, 4, 8, 8)),
            reason = MeasurementRunFailure.DETECTION_FAILED,
            kind = BlobDetectionFailureKind.NO_BLOB,
        )
        assertFailure(
            frame = frameWithRedPixels(8, 8, setOf(3 to 3)),
            config = defaultConfig(width = 8, height = 8, minArea = 2),
            reason = MeasurementRunFailure.DETECTION_FAILED,
            kind = BlobDetectionFailureKind.NO_BLOB,
        )
    }

    @Test
    fun noisyBackgroundHitsComponentCapEvenWithinPixelBounds() {
        val redPixels = buildSet {
            for (y in 0 until 6) {
                for (x in 0 until 6) {
                    if ((x + y) % 2 == 0) add(x to y)
                }
            }
        }
        val config = defaultConfig(
            width = 6,
            height = 6,
            minArea = 1,
            bounds = FrameProcessingBounds(
                maxWidth = 6,
                maxHeight = 6,
                maxPixels = 36,
                maxFrameCount = 10,
                maxThresholdPixels = 36,
                maxComponentsPerFrame = 3,
                maxOperationsPerFrame = 1_000,
            ),
        )

        assertFailure(
            frame = frameWithRedPixels(6, 6, redPixels),
            config = config,
            reason = MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
            kind = BlobDetectionFailureKind.RESOURCE_LIMIT,
        )
    }

    @Test
    fun hueWraparoundWorksAtImageBorder() {
        val almostRed = 0xffff0010.toInt()
        val frame = frameWithPixels(
            width = 3,
            height = 3,
            redPixels = mapOf(0 to 0 to almostRed, 0 to 1 to almostRed),
        )
        val config = defaultConfig(width = 3, height = 3, minArea = 2).copy(
            threshold = HsvThreshold(
                center = HsvColor(359.0, 1.0, 1.0),
                tolerance = HsvTolerance(8.0, 0.2, 0.2),
            ),
        )

        val outcome = BlobDetector.detect(frame, config)

        assertTrue(outcome is BlobDetectionOutcome.Success)
    }

    private fun assertFailure(
        frame: RgbFrame,
        config: BlobDetectionConfig = defaultConfig(frame.width, frame.height),
        reason: MeasurementRunFailure,
        kind: BlobDetectionFailureKind,
    ) {
        val outcome = BlobDetector.detect(frame, config)

        assertTrue(outcome is BlobDetectionOutcome.Failure)
        val failure = outcome as BlobDetectionOutcome.Failure
        assertEquals(reason, failure.reason)
        assertEquals(kind, failure.kind)
    }
}
