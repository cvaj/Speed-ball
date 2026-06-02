package com.speedball.app.measurement

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/** Source sensor used to establish the static phone level reference. */
enum class LevelReferenceSource {
    GRAVITY_SENSOR,
    ACCELEROMETER_FALLBACK,
}

/** Display rotation at the moment the static gravity snapshot was captured. */
enum class LevelReferenceDisplayRotation {
    ROTATION_0,
    ROTATION_90,
    ROTATION_180,
    ROTATION_270,
}

/** One gravity-vector sample in Android device coordinates. */
data class GravityVectorSample(
    val xMetersPerSecondSquared: Double,
    val yMetersPerSecondSquared: Double,
    val zMetersPerSecondSquared: Double,
) {
    fun magnitude(): Double =
        sqrt(
            xMetersPerSecondSquared * xMetersPerSecondSquared +
                yMetersPerSecondSquared * yMetersPerSecondSquared +
                zMetersPerSecondSquared * zMetersPerSecondSquared,
        )

    fun hasOnlyFiniteValues(): Boolean =
        xMetersPerSecondSquared.isFinite() &&
            yMetersPerSecondSquared.isFinite() &&
            zMetersPerSecondSquared.isFinite()
}

/**
 * Static phone-level reference captured at setup time.
 *
 * The camera is assumed to stay fixed after this snapshot.
 *
 * Sign convention used by [LevelReferenceCalculator]:
 *
 * - Android gravity samples are remapped into display coordinates at capture
 *   time.
 * - `rollDegrees = atan2(displayGravity.x, displayGravity.y)`, so a display
 *   whose "up" axis is aligned to gravity has zero roll.
 * - Core velocity reports launch angle in the image math convention used by
 *   the fit: x-positive to the right, y-up after the image y-down coordinate is
 *   converted by the measurement fit.
 * - The current correction subtracts roll from that image-space angle.
 *
 * The subtraction is intentionally disclosed as provisional until the named
 * physical validation gate checks a known phone roll against a real horizon and
 * known-horizontal motion.
 */
data class LevelReferenceSnapshot(
    val rollDegrees: Double,
    val pitchDegrees: Double?,
    val sampleCount: Int,
    val source: LevelReferenceSource,
    val displayRotation: LevelReferenceDisplayRotation,
    val maxGyroMagnitudeRadPerSecond: Double?,
    val capturedAtEpochMillis: Long,
) {
    fun hasOnlyFiniteValues(): Boolean =
        rollDegrees.isFinite() &&
            pitchDegrees?.isFinite() != false &&
            sampleCount > 0 &&
            maxGyroMagnitudeRadPerSecond?.isFinite() != false &&
            capturedAtEpochMillis > 0L
}

/** Validation outcome for setup-time level capture. */
sealed interface LevelReferenceOutcome {
    data class Success(val snapshot: LevelReferenceSnapshot) : LevelReferenceOutcome
    data class Failure(val message: String) : LevelReferenceOutcome
}

/** Pure level-reference math shared by Android capture and JVM tests. */
object LevelReferenceCalculator {
    const val MIN_SAMPLE_COUNT: Int = 12
    const val MAX_STABLE_GYRO_MAGNITUDE_RAD_PER_SECOND: Double = 0.25
    private const val MIN_GRAVITY_MAGNITUDE = 6.0
    private const val MAX_GRAVITY_MAGNITUDE = 13.0
    private const val RADIANS_TO_DEGREES = 180.0 / Math.PI

    fun buildSnapshot(
        samples: List<GravityVectorSample>,
        source: LevelReferenceSource,
        displayRotation: LevelReferenceDisplayRotation,
        maxGyroMagnitudeRadPerSecond: Double?,
        capturedAtEpochMillis: Long,
    ): LevelReferenceOutcome {
        if (samples.size < MIN_SAMPLE_COUNT) {
            return LevelReferenceOutcome.Failure("Level reference needs at least $MIN_SAMPLE_COUNT stable gravity samples.")
        }
        if (samples.any { !it.hasOnlyFiniteValues() }) {
            return LevelReferenceOutcome.Failure("Level reference gravity samples must be finite.")
        }
        if (samples.any { it.magnitude() !in MIN_GRAVITY_MAGNITUDE..MAX_GRAVITY_MAGNITUDE }) {
            return LevelReferenceOutcome.Failure("Level reference gravity magnitude is outside the expected still-phone range.")
        }
        if (maxGyroMagnitudeRadPerSecond?.let { !it.isFinite() || it > MAX_STABLE_GYRO_MAGNITUDE_RAD_PER_SECOND } == true) {
            return LevelReferenceOutcome.Failure("Phone must be still while capturing level.")
        }
        val average = GravityVectorSample(
            xMetersPerSecondSquared = samples.map { it.xMetersPerSecondSquared }.average(),
            yMetersPerSecondSquared = samples.map { it.yMetersPerSecondSquared }.average(),
            zMetersPerSecondSquared = samples.map { it.zMetersPerSecondSquared }.average(),
        )
        val displayGravity = average.toDisplayCoordinates(displayRotation)
        val roll = normalizeSignedDegrees(atan2(displayGravity.x, displayGravity.y) * RADIANS_TO_DEGREES)
        val pitch = normalizeSignedDegrees(atan2(displayGravity.z, displayGravity.y) * RADIANS_TO_DEGREES)
        val snapshot = LevelReferenceSnapshot(
            rollDegrees = roll,
            pitchDegrees = pitch,
            sampleCount = samples.size,
            source = source,
            displayRotation = displayRotation,
            maxGyroMagnitudeRadPerSecond = maxGyroMagnitudeRadPerSecond,
            capturedAtEpochMillis = capturedAtEpochMillis,
        )
        if (!snapshot.hasOnlyFiniteValues()) {
            return LevelReferenceOutcome.Failure("Level reference produced non-finite values.")
        }
        return LevelReferenceOutcome.Success(snapshot)
    }

    fun correctLaunchAngleDegrees(
        imageLaunchAngleDegrees: Double?,
        levelReference: LevelReferenceSnapshot?,
    ): Double? {
        val angle = imageLaunchAngleDegrees ?: return null
        if (!angle.isFinite()) return angle
        val roll = levelReference?.rollDegrees ?: return angle
        return normalizeSignedDegrees(angle - roll)
    }

    private fun GravityVectorSample.toDisplayCoordinates(rotation: LevelReferenceDisplayRotation): DisplayGravity =
        when (rotation) {
            LevelReferenceDisplayRotation.ROTATION_0 -> DisplayGravity(xMetersPerSecondSquared, yMetersPerSecondSquared, zMetersPerSecondSquared)
            LevelReferenceDisplayRotation.ROTATION_90 -> DisplayGravity(-yMetersPerSecondSquared, xMetersPerSecondSquared, zMetersPerSecondSquared)
            LevelReferenceDisplayRotation.ROTATION_180 -> DisplayGravity(-xMetersPerSecondSquared, -yMetersPerSecondSquared, zMetersPerSecondSquared)
            LevelReferenceDisplayRotation.ROTATION_270 -> DisplayGravity(yMetersPerSecondSquared, -xMetersPerSecondSquared, zMetersPerSecondSquared)
        }

    private fun normalizeSignedDegrees(value: Double): Double {
        if (!value.isFinite()) return value
        var normalized = value
        while (normalized <= -180.0) normalized += 360.0
        while (normalized > 180.0) normalized -= 360.0
        return if (abs(normalized) < 1.0e-12) 0.0 else normalized
    }

    private data class DisplayGravity(
        val x: Double,
        val y: Double,
        val z: Double,
    )
}
