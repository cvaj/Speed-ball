package com.speedball.app.measurement

import com.speedball.core.model.ImagePoint
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Positive integer frame or view dimensions used by coordinate transforms. */
data class FrameDimensions(
    val width: Int,
    val height: Int,
) {
    init {
        require(width > 0) { "Frame width must be positive." }
        require(height > 0) { "Frame height must be positive." }
    }
}

/** Normalized frame point, where (0,0) is top-left and (1,1) is bottom-right. */
data class NormalizedFramePoint(
    val x: Double,
    val y: Double,
) {
    fun isInFrame(): Boolean =
        x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0
}

/** Normalized frame rectangle, right/bottom exclusive after pixel conversion. */
data class NormalizedFrameRect(
    val left: Double,
    val top: Double,
    val right: Double,
    val bottom: Double,
) {
    fun isInFrame(): Boolean =
        listOf(left, top, right, bottom).all { it.isFinite() } &&
            left >= 0.0 &&
            top >= 0.0 &&
            right <= 1.0 &&
            bottom <= 1.0 &&
            left < right &&
            top < bottom
}

/** Preview scaling mode used to derive normalized frame taps from view taps. */
enum class PreviewScaleMode {
    FitCenter,
    CenterCrop,
    Stretch,
}

/** View-to-source mapping for a rendered camera preview. */
data class PreviewFrameTransform(
    val view: FrameDimensions,
    val source: FrameDimensions,
    val scaleMode: PreviewScaleMode = PreviewScaleMode.FitCenter,
    val sourceToViewRotationDegrees: Int = 0,
) {
    private val normalizedRotationDegrees = sourceToViewRotationDegrees.normalizedRightAngleDegrees()
    private val orientedSource = if (normalizedRotationDegrees == 90 || normalizedRotationDegrees == 270) {
        FrameDimensions(source.height, source.width)
    } else {
        source
    }
    private val scaleX: Double
    private val scaleY: Double
    private val offsetX: Double
    private val offsetY: Double

    init {
        val viewAspect = view.width.toDouble() / view.height.toDouble()
        val sourceAspect = orientedSource.width.toDouble() / orientedSource.height.toDouble()
        val scale = when (scaleMode) {
            PreviewScaleMode.FitCenter -> if (viewAspect > sourceAspect) {
                view.height.toDouble() / orientedSource.height.toDouble()
            } else {
                view.width.toDouble() / orientedSource.width.toDouble()
            }
            PreviewScaleMode.CenterCrop -> if (viewAspect > sourceAspect) {
                view.width.toDouble() / orientedSource.width.toDouble()
            } else {
                view.height.toDouble() / orientedSource.height.toDouble()
            }
            PreviewScaleMode.Stretch -> 1.0
        }
        scaleX = if (scaleMode == PreviewScaleMode.Stretch) view.width.toDouble() / orientedSource.width.toDouble() else scale
        scaleY = if (scaleMode == PreviewScaleMode.Stretch) view.height.toDouble() / orientedSource.height.toDouble() else scale
        offsetX = (view.width - orientedSource.width * scaleX) / 2.0
        offsetY = (view.height - orientedSource.height * scaleY) / 2.0
    }

    /** Converts a preview-view tap into a normalized frame point, or null when it hits letterbox. */
    fun viewPointToNormalized(xView: Double, yView: Double): NormalizedFramePoint? {
        if (!xView.isFinite() || !yView.isFinite()) return null
        val oriented = NormalizedFramePoint(
            x = ((xView - offsetX) / scaleX) / orientedSource.width.toDouble(),
            y = ((yView - offsetY) / scaleY) / orientedSource.height.toDouble(),
        )
        return oriented
            .takeIf { it.isInFrame() }
            ?.toSourceNormalized(normalizedRotationDegrees)
            ?.takeIf { it.isInFrame() }
    }

    /** Converts a normalized source-frame point into preview-view coordinates. */
    fun normalizedToViewPoint(point: NormalizedFramePoint): ImagePoint? {
        if (!point.isInFrame()) return null
        val oriented = point.toOrientedNormalized(normalizedRotationDegrees)
        return ImagePoint(
            xPx = oriented.x * orientedSource.width.toDouble() * scaleX + offsetX,
            yPx = oriented.y * orientedSource.height.toDouble() * scaleY + offsetY,
        )
    }
}

/** Normalizes a preview rotation to one of Camera2's right-angle display transforms. */
fun Int.normalizedRightAngleDegrees(): Int {
    val normalized = ((this % 360) + 360) % 360
    require(normalized % 90 == 0) { "Preview rotation must be a right angle." }
    return normalized
}

/** Converts a source-normalized point into the normalized coordinates of the rotated preview image. */
fun NormalizedFramePoint.toOrientedNormalized(rotationDegrees: Int): NormalizedFramePoint =
    when (rotationDegrees.normalizedRightAngleDegrees()) {
        0 -> this
        90 -> NormalizedFramePoint(x = 1.0 - y, y = x)
        180 -> NormalizedFramePoint(x = 1.0 - x, y = 1.0 - y)
        270 -> NormalizedFramePoint(x = y, y = 1.0 - x)
        else -> error("unreachable")
    }

/** Converts a rotated-preview normalized point back into source-frame normalized coordinates. */
fun NormalizedFramePoint.toSourceNormalized(rotationDegrees: Int): NormalizedFramePoint =
    when (rotationDegrees.normalizedRightAngleDegrees()) {
        0 -> this
        90 -> NormalizedFramePoint(x = y, y = 1.0 - x)
        180 -> NormalizedFramePoint(x = 1.0 - x, y = 1.0 - y)
        270 -> NormalizedFramePoint(x = 1.0 - y, y = x)
        else -> error("unreachable")
    }

/** Source-frame to active detection/readback-space mapping. */
data class DetectionReadbackTransform(
    val source: FrameDimensions,
    val readback: FrameDimensions,
) {
    val scaleX: Double = readback.width.toDouble() / source.width.toDouble()
    val scaleY: Double = readback.height.toDouble() / source.height.toDouble()

    fun normalizedToSourcePoint(point: NormalizedFramePoint): ImagePoint? =
        point.takeIf { it.isInFrame() }?.let {
            ImagePoint(
                xPx = it.x * source.width.toDouble(),
                yPx = it.y * source.height.toDouble(),
            )
        }

    fun normalizedToReadbackPoint(point: NormalizedFramePoint): ImagePoint? =
        point.takeIf { it.isInFrame() }?.let {
            ImagePoint(
                xPx = it.x * readback.width.toDouble(),
                yPx = it.y * readback.height.toDouble(),
            )
        }

    fun normalizedToReadbackRoi(rect: NormalizedFrameRect): RegionOfInterest? {
        if (!rect.isInFrame()) return null
        val roi = RegionOfInterest(
            left = floor(rect.left * readback.width).toInt(),
            top = floor(rect.top * readback.height).toInt(),
            rightExclusive = ceil(rect.right * readback.width).toInt(),
            bottomExclusive = ceil(rect.bottom * readback.height).toInt(),
        )
        return roi.clippedTo(readback.width, readback.height)
    }

    fun sourcePointToReadback(point: ImagePoint): ImagePoint? {
        if (!point.xPx.isFinite() || !point.yPx.isFinite()) return null
        return ImagePoint(xPx = point.xPx * scaleX, yPx = point.yPx * scaleY)
    }
}

fun normalizedRectFromPoints(
    first: NormalizedFramePoint,
    second: NormalizedFramePoint,
): NormalizedFrameRect? {
    if (!first.isInFrame() || !second.isInFrame()) return null
    val left = min(first.x, second.x)
    val right = max(first.x, second.x)
    val top = min(first.y, second.y)
    val bottom = max(first.y, second.y)
    return NormalizedFrameRect(left, top, right, bottom).takeIf { it.isInFrame() }
}
