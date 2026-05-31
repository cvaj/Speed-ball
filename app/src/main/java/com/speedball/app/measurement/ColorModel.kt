package com.speedball.app.measurement

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** HSV sample with hue in degrees and saturation/value in 0..1. */
data class HsvColor(
    val hueDegrees: Double,
    val saturation: Double,
    val value: Double,
)

/** Inclusive HSV tolerance, clamped to physically meaningful ranges. */
data class HsvTolerance(
    val hueDegrees: Double,
    val saturation: Double,
    val value: Double,
) {
    fun clamped(): HsvTolerance =
        HsvTolerance(
            hueDegrees = hueDegrees.coerceIn(0.0, 180.0),
            saturation = saturation.coerceIn(0.0, 1.0),
            value = value.coerceIn(0.0, 1.0),
        )
}

/** HSV threshold around a sampled ball color. */
data class HsvThreshold(
    val center: HsvColor,
    val tolerance: HsvTolerance,
) {
    private val clampedTolerance = tolerance.clamped()

    fun matches(color: HsvColor): Boolean =
        color.hasOnlyFiniteValues() &&
            center.hasOnlyFiniteValues() &&
            circularHueDistance(center.hueDegrees, color.hueDegrees) <= clampedTolerance.hueDegrees &&
            abs(center.saturation - color.saturation) <= clampedTolerance.saturation &&
            abs(center.value - color.value) <= clampedTolerance.value
}

/** Integer ROI in frame coordinates, right/bottom exclusive. */
data class RegionOfInterest(
    val left: Int,
    val top: Int,
    val rightExclusive: Int,
    val bottomExclusive: Int,
) {
    fun clippedTo(width: Int, height: Int): RegionOfInterest? {
        val clipped = RegionOfInterest(
            left = left.coerceIn(0, width),
            top = top.coerceIn(0, height),
            rightExclusive = rightExclusive.coerceIn(0, width),
            bottomExclusive = bottomExclusive.coerceIn(0, height),
        )
        return clipped.takeIf { it.left < it.rightExclusive && it.top < it.bottomExclusive }
    }
}

/** CPU and memory guardrails for pure Kotlin frame processing. */
data class FrameProcessingBounds(
    val maxWidth: Int = 1920,
    val maxHeight: Int = 1080,
    val maxPixels: Int = 2_073_600,
    val maxFrameCount: Int = 240,
    val maxThresholdPixels: Int = 80_000,
    val maxComponentsPerFrame: Int = 512,
    val maxOperationsPerFrame: Int = 4_000_000,
) {
    fun validate(): MeasurementRunOutcome.NoRead? {
        if (maxWidth <= 0 || maxHeight <= 0 || maxPixels <= 0 || maxFrameCount <= 0) {
            return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                "Frame-processing bounds must be positive.",
            )
        }
        if (maxThresholdPixels <= 0 || maxComponentsPerFrame <= 0 || maxOperationsPerFrame <= 0) {
            return MeasurementRunOutcome.NoRead(
                MeasurementRunFailure.RESOURCE_LIMIT_EXCEEDED,
                "Component-processing bounds must be positive.",
            )
        }
        return null
    }
}

/** Pure Kotlin color conversion helpers. */
object ColorMath {
    fun argbToHsv(argb: Int): HsvColor {
        val red = ((argb shr 16) and 0xff) / 255.0
        val green = ((argb shr 8) and 0xff) / 255.0
        val blue = (argb and 0xff) / 255.0
        val maxChannel = max(red, max(green, blue))
        val minChannel = min(red, min(green, blue))
        val delta = maxChannel - minChannel
        val hue = when {
            delta == 0.0 -> 0.0
            maxChannel == red -> 60.0 * ((((green - blue) / delta) + 6.0) % 6.0)
            maxChannel == green -> 60.0 * (((blue - red) / delta) + 2.0)
            else -> 60.0 * (((red - green) / delta) + 4.0)
        }
        val saturation = if (maxChannel == 0.0) 0.0 else delta / maxChannel
        return HsvColor(hueDegrees = hue, saturation = saturation, value = maxChannel)
    }
}

internal fun HsvColor.hasOnlyFiniteValues(): Boolean =
    hueDegrees.isFinite() && saturation.isFinite() && value.isFinite()

internal fun circularHueDistance(aDegrees: Double, bDegrees: Double): Double {
    val a = aDegrees.normalizedHue()
    val b = bDegrees.normalizedHue()
    val direct = abs(a - b)
    return min(direct, 360.0 - direct)
}

private fun Double.normalizedHue(): Double =
    ((this % 360.0) + 360.0) % 360.0
