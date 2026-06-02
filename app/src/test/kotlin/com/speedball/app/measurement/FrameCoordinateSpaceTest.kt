package com.speedball.app.measurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class FrameCoordinateSpaceTest {
    @Test
    fun sourceToReadbackPreservesAnisotropicScale() {
        val transform = DetectionReadbackTransform(
            source = FrameDimensions(1280, 720),
            readback = FrameDimensions(160, 90),
        )

        val point = transform.normalizedToReadbackPoint(NormalizedFramePoint(0.5, 0.5))
        val roi = transform.normalizedToReadbackRoi(NormalizedFrameRect(0.25, 0.25, 0.75, 0.75))

        assertEquals(80.0, point?.xPx ?: Double.NaN, 1.0e-9)
        assertEquals(45.0, point?.yPx ?: Double.NaN, 1.0e-9)
        assertEquals(RegionOfInterest(40, 22, 120, 68), roi)
    }

    @Test
    fun nonSquareReadbackDoesNotCollapseHorizontalAndVerticalScale() {
        val transform = DetectionReadbackTransform(
            source = FrameDimensions(1280, 720),
            readback = FrameDimensions(200, 90),
        )

        val sourcePoint = transform.sourcePointToReadback(com.speedball.core.model.ImagePoint(640.0, 360.0))

        assertEquals(0.15625, transform.scaleX, 1.0e-9)
        assertEquals(0.125, transform.scaleY, 1.0e-9)
        assertEquals(100.0, sourcePoint?.xPx ?: Double.NaN, 1.0e-9)
        assertEquals(45.0, sourcePoint?.yPx ?: Double.NaN, 1.0e-9)
    }

    @Test
    fun fitCenterRejectsLetterboxTaps() {
        val transform = PreviewFrameTransform(
            view = FrameDimensions(400, 400),
            source = FrameDimensions(1280, 720),
            scaleMode = PreviewScaleMode.FitCenter,
        )

        assertNull(transform.viewPointToNormalized(200.0, 20.0))
        val center = transform.viewPointToNormalized(200.0, 200.0)
        assertEquals(0.5, center?.x ?: Double.NaN, 1.0e-9)
        assertEquals(0.5, center?.y ?: Double.NaN, 1.0e-9)
    }

    @Test
    fun fitCenterRoundTripsNormalizedPointThroughPillarboxedView() {
        val transform = PreviewFrameTransform(
            view = FrameDimensions(2280, 1080),
            source = FrameDimensions(1280, 720),
            scaleMode = PreviewScaleMode.FitCenter,
        )

        assertNull(transform.viewPointToNormalized(100.0, 540.0))
        val firstMarker = transform.viewPointToNormalized(660.0, 540.0)
        val center = transform.viewPointToNormalized(1140.0, 540.0)
        val secondMarker = transform.viewPointToNormalized(1620.0, 540.0)
        val renderedCenter = transform.normalizedToViewPoint(NormalizedFramePoint(0.5, 0.5))

        assertEquals(0.25, firstMarker?.x ?: Double.NaN, 1.0e-9)
        assertEquals(0.5, firstMarker?.y ?: Double.NaN, 1.0e-9)
        assertEquals(0.5, center?.x ?: Double.NaN, 1.0e-9)
        assertEquals(0.75, secondMarker?.x ?: Double.NaN, 1.0e-9)
        assertEquals(1140.0, renderedCenter?.xPx ?: Double.NaN, 1.0e-9)
        assertEquals(540.0, renderedCenter?.yPx ?: Double.NaN, 1.0e-9)
    }

    @Test
    fun fitCenterRoundTripsNinetyDegreeRotatedPreview() {
        val transform = PreviewFrameTransform(
            view = FrameDimensions(2280, 1080),
            source = FrameDimensions(1280, 720),
            scaleMode = PreviewScaleMode.FitCenter,
            sourceToViewRotationDegrees = 90,
        )

        assertNull(transform.viewPointToNormalized(100.0, 540.0))
        val center = transform.viewPointToNormalized(1140.0, 540.0)
        val sourceTopLeft = transform.normalizedToViewPoint(NormalizedFramePoint(0.0, 0.0))
        val sourceBottomRight = transform.normalizedToViewPoint(NormalizedFramePoint(1.0, 1.0))
        val roundTrip = transform.viewPointToNormalized(
            sourceTopLeft?.xPx ?: Double.NaN,
            sourceTopLeft?.yPx ?: Double.NaN,
        )

        assertEquals(0.5, center?.x ?: Double.NaN, 1.0e-9)
        assertEquals(0.5, center?.y ?: Double.NaN, 1.0e-9)
        assertEquals(1443.75, sourceTopLeft?.xPx ?: Double.NaN, 1.0e-9)
        assertEquals(0.0, sourceTopLeft?.yPx ?: Double.NaN, 1.0e-9)
        assertEquals(836.25, sourceBottomRight?.xPx ?: Double.NaN, 1.0e-9)
        assertEquals(1080.0, sourceBottomRight?.yPx ?: Double.NaN, 1.0e-9)
        assertEquals(0.0, roundTrip?.x ?: Double.NaN, 1.0e-9)
        assertEquals(0.0, roundTrip?.y ?: Double.NaN, 1.0e-9)
    }

    @Test
    fun centerCropMapsVisibleViewThroughCroppedSource() {
        val transform = PreviewFrameTransform(
            view = FrameDimensions(400, 400),
            source = FrameDimensions(1280, 720),
            scaleMode = PreviewScaleMode.CenterCrop,
        )

        val leftView = transform.viewPointToNormalized(0.0, 200.0)
        val center = transform.viewPointToNormalized(200.0, 200.0)

        assertEquals(0.21875, leftView?.x ?: Double.NaN, 1.0e-9)
        assertEquals(0.5, leftView?.y ?: Double.NaN, 1.0e-9)
        assertEquals(0.5, center?.x ?: Double.NaN, 1.0e-9)
        assertEquals(0.5, center?.y ?: Double.NaN, 1.0e-9)
    }

    @Test
    fun outOfFrameRoiReturnsNull() {
        val transform = DetectionReadbackTransform(
            source = FrameDimensions(1280, 720),
            readback = FrameDimensions(160, 90),
        )

        assertNull(transform.normalizedToReadbackRoi(NormalizedFrameRect(0.2, 0.2, 1.2, 0.4)))
        assertNull(normalizedRectFromPoints(NormalizedFramePoint(0.1, 0.1), NormalizedFramePoint(0.1, 0.2)))
    }
}
