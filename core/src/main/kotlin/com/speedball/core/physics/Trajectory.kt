package com.speedball.core.physics

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sign

private const val MILES_PER_HOUR_PER_METER_PER_SECOND = 2.2369362920544
private const val FEET_PER_METER = 3.280839895013123
private const val MIN_COSINE = 1e-12
private const val RPM_TO_RADIANS_PER_SECOND = 2.0 * PI / 60.0

/** Physical ball dimensions used by trajectory integration. */
data class BallSpec(
    val diameterMeters: Double,
    val massKilograms: Double,
) {
    companion object {
        fun softball12Inch(): BallSpec =
            BallSpec(
                diameterMeters = 0.0955,
                massKilograms = 0.1899,
            )
    }
}

/** Air model used for quadratic drag. */
data class AirSpec(
    val densityKgPerCubicMeter: Double,
    val dragCoefficient: Double,
) {
    companion object {
        fun standardSeaLevel(): AirSpec =
            AirSpec(
                densityKgPerCubicMeter = 1.225,
                dragCoefficient = 0.40,
            )
    }
}

/**
 * Transverse spin used by the optional Magnus trajectory model.
 *
 * Positive [transverseSpinRpm] means backspin in the 2D flight plane and
 * produces upward lift for a forward-moving launch. Negative values model
 * topspin/downward Magnus force. The default is no spin, which exactly
 * preserves the drag-only trajectory model.
 */
data class MagnusSpinSpec(
    val transverseSpinRpm: Double,
    val liftCoefficientModel: MagnusLiftCoefficientModel = MagnusLiftCoefficientModel.NATHAN_BASEBALL_SPIN_PARAMETER,
) {
    companion object {
        fun none(): MagnusSpinSpec = MagnusSpinSpec(transverseSpinRpm = 0.0)

        fun assumedLevelSwingBackspin(
            transverseSpinRpm: Double = 1800.0,
        ): MagnusSpinSpec =
            MagnusSpinSpec(transverseSpinRpm = transverseSpinRpm)
    }
}

/** Experiment-backed lift-coefficient model for spinning ball estimates. */
enum class MagnusLiftCoefficientModel {
    /**
     * Nathan baseball spin-parameter model:
     * `C_L = 2.5S / (1 + 5.8S)`, where `S = R * omega / v`.
     *
     * This is an assumption-based estimate for softball until the app has
     * measured softball-specific spin evidence. It should be reported as a
     * model path, not as measured spin.
     */
    NATHAN_BASEBALL_SPIN_PARAMETER,
}

/** Launch state in SI units, with angle in degrees above horizontal. */
data class LaunchState(
    val speedMetersPerSecond: Double,
    val angleDegrees: Double,
    val launchHeightMeters: Double = 0.0,
) {
    companion object {
        fun fromMetersPerSecond(
            speedMetersPerSecond: Double,
            angleDegrees: Double,
            launchHeightMeters: Double = 0.0,
        ): LaunchState =
            LaunchState(
                speedMetersPerSecond = speedMetersPerSecond,
                angleDegrees = angleDegrees,
                launchHeightMeters = launchHeightMeters,
            )

        fun fromFeetPerSecond(
            feetPerSecond: Double,
            angleDegrees: Double,
            launchHeightMeters: Double = 0.0,
        ): LaunchState =
            fromMetersPerSecond(
                speedMetersPerSecond = feetPerSecond / FEET_PER_METER,
                angleDegrees = angleDegrees,
                launchHeightMeters = launchHeightMeters,
            )

        fun fromMilesPerHour(
            milesPerHour: Double,
            angleDegrees: Double,
            launchHeightMeters: Double = 0.0,
        ): LaunchState =
            fromMetersPerSecond(
                speedMetersPerSecond = milesPerHour / MILES_PER_HOUR_PER_METER_PER_SECOND,
                angleDegrees = angleDegrees,
                launchHeightMeters = launchHeightMeters,
            )
    }
}

/** Numeric controls for trajectory integration. */
data class TrajectoryOptions(
    val timeStepSeconds: Double,
    val maxFlightSeconds: Double,
    val gravityMetersPerSecondSquared: Double,
) {
    companion object {
        fun default(): TrajectoryOptions =
            TrajectoryOptions(
                timeStepSeconds = 0.001,
                maxFlightSeconds = 15.0,
                gravityMetersPerSecondSquared = 9.81,
            )
    }
}

/** One point on a simulated trajectory. */
data class TrajectorySample(
    val timeSeconds: Double,
    val xMeters: Double,
    val yMeters: Double,
    val vxMetersPerSecond: Double,
    val vyMetersPerSecond: Double,
)

/**
 * Simulated flight summary.
 *
 * `apexMeters` is the maximum absolute height. `carryMeters` and
 * `hangTimeSeconds` are interpolated to the first ground crossing at y = 0.
 */
data class TrajectoryResult(
    val samples: List<TrajectorySample>,
    val apexMeters: Double,
    val carryMeters: Double,
    val hangTimeSeconds: Double,
)

/** Reason trajectory physics cannot safely return a result. */
enum class TrajectoryFailure {
    INVALID_LAUNCH,
    INVALID_BALL,
    INVALID_AIR,
    INVALID_OPTIONS,
    NON_FINITE_STATE,
    NO_GROUND_INTERSECTION,
}

/** Explicit trajectory outcome. Failures intentionally carry no partial result. */
sealed interface TrajectoryOutcome {
    data class Success(val trajectory: TrajectoryResult) : TrajectoryOutcome
    data class Failure(val reason: TrajectoryFailure, val message: String) : TrajectoryOutcome
}

/** Quadratic-drag trajectory simulator for the core measurement pipeline. */
object TrajectoryPhysics {
    fun simulate(
        launch: LaunchState,
        ball: BallSpec = BallSpec.softball12Inch(),
        air: AirSpec = AirSpec.standardSeaLevel(),
        options: TrajectoryOptions = TrajectoryOptions.default(),
        spin: MagnusSpinSpec = MagnusSpinSpec.none(),
    ): TrajectoryOutcome {
        validateInputs(launch, ball, air, options, spin)?.let { return it }

        if (isImmediateGroundResult(launch)) {
            return TrajectoryOutcome.Success(immediateGroundTrajectory(launch.launchHeightMeters))
        }

        val angleRadians = launch.angleDegrees * PI / 180.0
        val vx0 = if (abs(cos(angleRadians)) < MIN_COSINE) 0.0 else launch.speedMetersPerSecond * cos(angleRadians)
        val vy0 = if (abs(sin(angleRadians)) < MIN_COSINE) 0.0 else launch.speedMetersPerSecond * sin(angleRadians)
        val initialState = IntegratorState(
            timeSeconds = 0.0,
            xMeters = 0.0,
            yMeters = launch.launchHeightMeters,
            vxMetersPerSecond = vx0,
            vyMetersPerSecond = vy0,
        )

        return simulateFromState(
            initialState = initialState,
            ball = ball,
            air = air,
            options = options,
            spin = spin,
        )
    }

    internal fun simulateFromState(
        initialState: IntegratorState,
        ball: BallSpec,
        air: AirSpec,
        options: TrajectoryOptions,
        spin: MagnusSpinSpec = MagnusSpinSpec.none(),
    ): TrajectoryOutcome {
        val drag = dragConstant(ball, air)
        val magnus = magnusConstant(ball, air)
        val samples = mutableListOf(initialState.toSample())
        var current = initialState

        if (!current.hasOnlyFiniteValues()) {
            return nonFiniteStateFailure()
        }

        while (current.timeSeconds < options.maxFlightSeconds) {
            val dt = minOf(options.timeStepSeconds, options.maxFlightSeconds - current.timeSeconds)
            val next = rk4Step(current, dt, drag, magnus, spin, ball, options.gravityMetersPerSecondSquared)
            if (!next.hasOnlyFiniteValues()) {
                return nonFiniteStateFailure()
            }

            if (current.yMeters > 0.0 && next.yMeters <= 0.0) {
                val finalSample = interpolateGroundCrossing(current, next)
                samples += finalSample
                return TrajectoryOutcome.Success(samples.toTrajectoryResult())
            }

            samples += next.toSample()
            current = next
        }

        return TrajectoryOutcome.Failure(
            TrajectoryFailure.NO_GROUND_INTERSECTION,
            "Trajectory did not cross the ground before max flight time.",
        )
    }
}

internal data class IntegratorState(
    val timeSeconds: Double,
    val xMeters: Double,
    val yMeters: Double,
    val vxMetersPerSecond: Double,
    val vyMetersPerSecond: Double,
) {
    fun hasOnlyFiniteValues(): Boolean =
        timeSeconds.isFinite() &&
            xMeters.isFinite() &&
            yMeters.isFinite() &&
            vxMetersPerSecond.isFinite() &&
            vyMetersPerSecond.isFinite()

    fun toSample(): TrajectorySample =
        TrajectorySample(
            timeSeconds = timeSeconds,
            xMeters = xMeters,
            yMeters = yMeters,
            vxMetersPerSecond = vxMetersPerSecond,
            vyMetersPerSecond = vyMetersPerSecond,
        )
}

private data class Derivative(
    val dx: Double,
    val dy: Double,
    val dvx: Double,
    val dvy: Double,
)

private fun validateInputs(
    launch: LaunchState,
    ball: BallSpec,
    air: AirSpec,
    options: TrajectoryOptions,
    spin: MagnusSpinSpec,
): TrajectoryOutcome.Failure? {
    if (!launch.speedMetersPerSecond.isFinite() || launch.speedMetersPerSecond < 0.0) {
        return TrajectoryOutcome.Failure(TrajectoryFailure.INVALID_LAUNCH, "Launch speed must be finite and non-negative.")
    }
    if (!launch.angleDegrees.isFinite() || launch.angleDegrees < -90.0 || launch.angleDegrees > 90.0) {
        return TrajectoryOutcome.Failure(TrajectoryFailure.INVALID_LAUNCH, "Launch angle must be finite and between -90 and 90 degrees.")
    }
    if (!launch.launchHeightMeters.isFinite() || launch.launchHeightMeters < 0.0) {
        return TrajectoryOutcome.Failure(TrajectoryFailure.INVALID_LAUNCH, "Launch height must be finite and non-negative.")
    }
    if (!ball.diameterMeters.isFinite() || ball.diameterMeters <= 0.0 || !ball.massKilograms.isFinite() || ball.massKilograms <= 0.0) {
        return TrajectoryOutcome.Failure(TrajectoryFailure.INVALID_BALL, "Ball diameter and mass must be finite and positive.")
    }
    if (!air.densityKgPerCubicMeter.isFinite() || air.densityKgPerCubicMeter < 0.0) {
        return TrajectoryOutcome.Failure(TrajectoryFailure.INVALID_AIR, "Air density must be finite and non-negative.")
    }
    if (!air.dragCoefficient.isFinite() || air.dragCoefficient < 0.0) {
        return TrajectoryOutcome.Failure(TrajectoryFailure.INVALID_AIR, "Drag coefficient must be finite and non-negative.")
    }
    if (!options.timeStepSeconds.isFinite() || options.timeStepSeconds <= 0.0) {
        return TrajectoryOutcome.Failure(TrajectoryFailure.INVALID_OPTIONS, "Time step must be finite and positive.")
    }
    if (!options.maxFlightSeconds.isFinite() || options.maxFlightSeconds <= 0.0) {
        return TrajectoryOutcome.Failure(TrajectoryFailure.INVALID_OPTIONS, "Max flight time must be finite and positive.")
    }
    if (!options.gravityMetersPerSecondSquared.isFinite() || options.gravityMetersPerSecondSquared <= 0.0) {
        return TrajectoryOutcome.Failure(TrajectoryFailure.INVALID_OPTIONS, "Gravity must be finite and positive.")
    }
    if (!spin.transverseSpinRpm.isFinite()) {
        return TrajectoryOutcome.Failure(TrajectoryFailure.INVALID_AIR, "Magnus spin rate must be finite.")
    }
    return null
}

private fun isImmediateGroundResult(launch: LaunchState): Boolean =
    launch.launchHeightMeters == 0.0 && (launch.speedMetersPerSecond == 0.0 || launch.angleDegrees <= 0.0)

private fun immediateGroundTrajectory(launchHeightMeters: Double): TrajectoryResult {
    val sample = TrajectorySample(
        timeSeconds = 0.0,
        xMeters = 0.0,
        yMeters = launchHeightMeters,
        vxMetersPerSecond = 0.0,
        vyMetersPerSecond = 0.0,
    )
    return TrajectoryResult(
        samples = listOf(sample),
        apexMeters = launchHeightMeters,
        carryMeters = 0.0,
        hangTimeSeconds = 0.0,
    )
}

private fun dragConstant(ball: BallSpec, air: AirSpec): Double {
    val radius = ball.diameterMeters / 2.0
    val area = PI * radius * radius
    return 0.5 * air.densityKgPerCubicMeter * air.dragCoefficient * area / ball.massKilograms
}

private fun magnusConstant(ball: BallSpec, air: AirSpec): Double {
    val radius = ball.diameterMeters / 2.0
    val area = PI * radius * radius
    return 0.5 * air.densityKgPerCubicMeter * area / ball.massKilograms
}

private fun rk4Step(
    state: IntegratorState,
    dt: Double,
    drag: Double,
    magnus: Double,
    spin: MagnusSpinSpec,
    ball: BallSpec,
    gravity: Double,
): IntegratorState {
    val k1 = derivative(state, drag, magnus, spin, ball, gravity)
    val k2 = derivative(state.offset(k1, dt / 2.0), drag, magnus, spin, ball, gravity)
    val k3 = derivative(state.offset(k2, dt / 2.0), drag, magnus, spin, ball, gravity)
    val k4 = derivative(state.offset(k3, dt), drag, magnus, spin, ball, gravity)
    return IntegratorState(
        timeSeconds = state.timeSeconds + dt,
        xMeters = state.xMeters + dt / 6.0 * (k1.dx + 2.0 * k2.dx + 2.0 * k3.dx + k4.dx),
        yMeters = state.yMeters + dt / 6.0 * (k1.dy + 2.0 * k2.dy + 2.0 * k3.dy + k4.dy),
        vxMetersPerSecond = state.vxMetersPerSecond + dt / 6.0 * (k1.dvx + 2.0 * k2.dvx + 2.0 * k3.dvx + k4.dvx),
        vyMetersPerSecond = state.vyMetersPerSecond + dt / 6.0 * (k1.dvy + 2.0 * k2.dvy + 2.0 * k3.dvy + k4.dvy),
    )
}

private fun IntegratorState.offset(derivative: Derivative, dt: Double): IntegratorState =
    IntegratorState(
        timeSeconds = timeSeconds + dt,
        xMeters = xMeters + derivative.dx * dt,
        yMeters = yMeters + derivative.dy * dt,
        vxMetersPerSecond = vxMetersPerSecond + derivative.dvx * dt,
        vyMetersPerSecond = vyMetersPerSecond + derivative.dvy * dt,
    )

private fun derivative(
    state: IntegratorState,
    drag: Double,
    magnus: Double,
    spin: MagnusSpinSpec,
    ball: BallSpec,
    gravity: Double,
): Derivative {
    val speed = hypot(state.vxMetersPerSecond, state.vyMetersPerSecond)
    val liftCoefficient = liftCoefficient(speed, ball, spin)
    val spinDirection = spin.transverseSpinRpm.sign
    val liftX = if (speed <= 0.0 || spinDirection == 0.0) {
        0.0
    } else {
        -spinDirection * magnus * liftCoefficient * speed * state.vyMetersPerSecond
    }
    val liftY = if (speed <= 0.0 || spinDirection == 0.0) {
        0.0
    } else {
        spinDirection * magnus * liftCoefficient * speed * state.vxMetersPerSecond
    }
    return Derivative(
        dx = state.vxMetersPerSecond,
        dy = state.vyMetersPerSecond,
        dvx = -drag * speed * state.vxMetersPerSecond + liftX,
        dvy = -gravity - drag * speed * state.vyMetersPerSecond + liftY,
    )
}

private fun liftCoefficient(
    speedMetersPerSecond: Double,
    ball: BallSpec,
    spin: MagnusSpinSpec,
): Double {
    if (spin.transverseSpinRpm == 0.0 || speedMetersPerSecond <= 0.0) return 0.0
    val radius = ball.diameterMeters / 2.0
    val omega = abs(spin.transverseSpinRpm) * RPM_TO_RADIANS_PER_SECOND
    val spinParameter = radius * omega / speedMetersPerSecond
    return when (spin.liftCoefficientModel) {
        MagnusLiftCoefficientModel.NATHAN_BASEBALL_SPIN_PARAMETER ->
            2.5 * spinParameter / (1.0 + 5.8 * spinParameter)
    }
}

private fun interpolateGroundCrossing(
    previous: IntegratorState,
    next: IntegratorState,
): TrajectorySample {
    val fraction = previous.yMeters / (previous.yMeters - next.yMeters)
    return TrajectorySample(
        timeSeconds = interpolate(previous.timeSeconds, next.timeSeconds, fraction),
        xMeters = interpolate(previous.xMeters, next.xMeters, fraction),
        yMeters = 0.0,
        vxMetersPerSecond = interpolate(previous.vxMetersPerSecond, next.vxMetersPerSecond, fraction),
        vyMetersPerSecond = interpolate(previous.vyMetersPerSecond, next.vyMetersPerSecond, fraction),
    )
}

private fun interpolate(start: Double, end: Double, fraction: Double): Double =
    start + (end - start) * fraction

private fun List<TrajectorySample>.toTrajectoryResult(): TrajectoryResult =
    TrajectoryResult(
        samples = this,
        apexMeters = maxOf { it.yMeters },
        carryMeters = last().xMeters,
        hangTimeSeconds = last().timeSeconds,
    )

private fun nonFiniteStateFailure(): TrajectoryOutcome.Failure =
    TrajectoryOutcome.Failure(
        TrajectoryFailure.NON_FINITE_STATE,
        "Trajectory integration produced a non-finite state.",
    )
