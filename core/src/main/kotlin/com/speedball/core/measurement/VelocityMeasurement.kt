package com.speedball.core.measurement

import com.speedball.core.model.Detection
import com.speedball.core.units.Units
import com.speedball.core.velocity.VelocityFit
import com.speedball.core.velocity.VelocityFitOptions
import com.speedball.core.velocity.VelocityFitOutcome
import com.speedball.core.velocity.VelocityFitResult

/** Actionable reason a measurement cannot safely report a speed. */
enum class MeasurementFailure {
    INSUFFICIENT_DETECTIONS,
    BAD_TIMESTAMP,
    INVALID_DETECTION,
    INVALID_CALIBRATION,
    INVALID_OPTIONS,
    NON_FINITE_FIT,
    EXCESSIVE_RESIDUAL,
}

/** Tunable numeric gates for velocity measurement. */
data class MeasurementOptions(
    val maxRmsResidualPx: Double,
    val minTimeSpreadSecondsSquared: Double,
    val maxOutlierPasses: Int = 0,
    val minOutlierRmsImprovementPx: Double = 0.0,
) {
    fun toVelocityFitOptions(): VelocityFitOptions =
        VelocityFitOptions(
            minTimeSpreadSecondsSquared = minTimeSpreadSecondsSquared,
            maxOutlierPasses = maxOutlierPasses,
            minOutlierRmsImprovementPx = minOutlierRmsImprovementPx,
        )
}

/** Successful speed and launch-angle measurement derived from fitted detections. */
data class VelocityMeasurement(
    val fit: VelocityFitResult,
    val pixelsPerFoot: Double,
    val feetPerSecond: Double,
    val milesPerHour: Double,
    val launchAngleDegrees: Double,
)

/** Explicit measurement outcome. Failures intentionally carry no partial speed. */
sealed interface MeasurementOutcome {
    data class Success(val measurement: VelocityMeasurement) : MeasurementOutcome
    data class Failure(val reason: MeasurementFailure, val message: String) : MeasurementOutcome
}

/** Public fail-loud measurement API for converting detections into speed. */
object VelocityMeasurementCalculator {
    fun measure(
        detections: List<Detection>,
        pixelsPerFoot: Double,
        options: MeasurementOptions,
    ): MeasurementOutcome {
        validateMeasurementInputs(pixelsPerFoot, options)?.let { return it }

        return when (val fitOutcome = VelocityFit.fit(detections, options.toVelocityFitOptions())) {
            is VelocityFitOutcome.Failure -> MeasurementOutcome.Failure(fitOutcome.reason, fitOutcome.message)
            is VelocityFitOutcome.Success -> measurementFromFit(
                fit = fitOutcome.fit,
                pixelsPerFoot = pixelsPerFoot,
                maxRmsResidualPx = options.maxRmsResidualPx,
            )
        }
    }
}

internal fun measurementFromFit(
    fit: VelocityFitResult,
    pixelsPerFoot: Double,
    maxRmsResidualPx: Double,
): MeasurementOutcome {
    if (!fit.hasOnlyFiniteValues()) {
        return MeasurementOutcome.Failure(
            MeasurementFailure.NON_FINITE_FIT,
            "Fit produced a non-finite derived value.",
        )
    }
    if (fit.rmsResidualPx > maxRmsResidualPx) {
        return MeasurementOutcome.Failure(
            MeasurementFailure.EXCESSIVE_RESIDUAL,
            "Fit residual is too high.",
        )
    }
    val feetPerSecond = Units.pixelsPerSecondToFeetPerSecond(fit.speedPxPerSecond, pixelsPerFoot)
        ?: return MeasurementOutcome.Failure(
            MeasurementFailure.INVALID_CALIBRATION,
            "Pixels-per-foot calibration must be finite and positive.",
        )
    val milesPerHour = Units.feetPerSecondToMilesPerHour(feetPerSecond)
        ?: return MeasurementOutcome.Failure(
            MeasurementFailure.NON_FINITE_FIT,
            "Measurement produced a non-finite speed.",
        )
    if (!milesPerHour.isFinite() || !fit.launchAngleDegrees.isFinite()) {
        return MeasurementOutcome.Failure(
            MeasurementFailure.NON_FINITE_FIT,
            "Measurement produced a non-finite speed or angle.",
        )
    }
    return MeasurementOutcome.Success(
        VelocityMeasurement(
            fit = fit,
            pixelsPerFoot = pixelsPerFoot,
            feetPerSecond = feetPerSecond,
            milesPerHour = milesPerHour,
            launchAngleDegrees = fit.launchAngleDegrees,
        ),
    )
}

private fun validateMeasurementInputs(
    pixelsPerFoot: Double,
    options: MeasurementOptions,
): MeasurementOutcome.Failure? {
    if (!pixelsPerFoot.isFinite() || pixelsPerFoot <= 0.0) {
        return MeasurementOutcome.Failure(
            MeasurementFailure.INVALID_CALIBRATION,
            "Pixels-per-foot calibration must be finite and positive.",
        )
    }
    if (!options.maxRmsResidualPx.isFinite() || options.maxRmsResidualPx <= 0.0) {
        return MeasurementOutcome.Failure(
            MeasurementFailure.INVALID_OPTIONS,
            "Maximum RMS residual must be finite and positive.",
        )
    }
    if (!options.minTimeSpreadSecondsSquared.isFinite() || options.minTimeSpreadSecondsSquared <= 0.0) {
        return MeasurementOutcome.Failure(
            MeasurementFailure.INVALID_OPTIONS,
            "Minimum time spread must be finite and positive.",
        )
    }
    if (options.maxOutlierPasses < 0) {
        return MeasurementOutcome.Failure(
            MeasurementFailure.INVALID_OPTIONS,
            "Maximum outlier passes must be non-negative.",
        )
    }
    if (!options.minOutlierRmsImprovementPx.isFinite() || options.minOutlierRmsImprovementPx < 0.0) {
        return MeasurementOutcome.Failure(
            MeasurementFailure.INVALID_OPTIONS,
            "Minimum outlier RMS improvement must be finite and non-negative.",
        )
    }
    return null
}
