package com.speedball.core.physics

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class TrajectoryTest {
    @Test
    fun defaultsExposeSoftballAndEnvironmentConstants() {
        assertEquals(0.0955, BallSpec.softball12Inch().diameterMeters, 0.0)
        assertEquals(0.1899, BallSpec.softball12Inch().massKilograms, 0.0)
        assertEquals(1.225, AirSpec.standardSeaLevel().densityKgPerCubicMeter, 0.0)
        assertEquals(0.40, AirSpec.standardSeaLevel().dragCoefficient, 0.0)
        assertEquals(0.001, TrajectoryOptions.default().timeStepSeconds, 0.0)
        assertEquals(15.0, TrajectoryOptions.default().maxFlightSeconds, 0.0)
        assertEquals(9.81, TrajectoryOptions.default().gravityMetersPerSecondSquared, 0.0)
    }

    @Test
    fun launchConstructorsConvertIntoMetersPerSecond() {
        assertEquals(31.2928, LaunchState.fromMilesPerHour(70.0, 25.0).speedMetersPerSecond, 1e-4)
        assertEquals(30.48, LaunchState.fromFeetPerSecond(100.0, 25.0).speedMetersPerSecond, 1e-9)
        assertEquals(17.0, LaunchState.fromMetersPerSecond(17.0, 25.0).speedMetersPerSecond, 0.0)
    }

    @Test
    fun invalidInputsFailWithSpecificReasons() {
        assertEquals(TrajectoryFailure.INVALID_LAUNCH, assertFailure(TrajectoryPhysics.simulate(validLaunch().copy(speedMetersPerSecond = -1.0))).reason)
        assertEquals(TrajectoryFailure.INVALID_LAUNCH, assertFailure(TrajectoryPhysics.simulate(validLaunch().copy(angleDegrees = 91.0))).reason)
        assertEquals(TrajectoryFailure.INVALID_LAUNCH, assertFailure(TrajectoryPhysics.simulate(validLaunch().copy(launchHeightMeters = -0.1))).reason)
        assertEquals(TrajectoryFailure.INVALID_BALL, assertFailure(TrajectoryPhysics.simulate(validLaunch(), ball = BallSpec(0.0, 0.1899))).reason)
        assertEquals(TrajectoryFailure.INVALID_AIR, assertFailure(TrajectoryPhysics.simulate(validLaunch(), air = AirSpec(Double.NaN, 0.40))).reason)
        assertEquals(TrajectoryFailure.INVALID_OPTIONS, assertFailure(TrajectoryPhysics.simulate(validLaunch(), options = options.copy(timeStepSeconds = 0.0))).reason)
    }

    @Test
    fun failureCarriesNoPartialTrajectoryValues() {
        val failure = assertFailure(
            TrajectoryPhysics.simulate(
                launch = validLaunch(),
                options = options.copy(timeStepSeconds = 0.0),
            ),
        )

        assertFalse(failure.javaClass.declaredFields.any { it.name.contains("trajectory", ignoreCase = true) })
        assertFalse(failure.javaClass.declaredFields.any { it.name.contains("carry", ignoreCase = true) })
        assertFalse(failure.javaClass.declaredFields.any { it.name.contains("hang", ignoreCase = true) })
        assertFalse(failure.javaClass.declaredFields.any { it.name.contains("apex", ignoreCase = true) })
    }

    @Test
    fun boundaryInputsAreAccepted() {
        assertSuccess(TrajectoryPhysics.simulate(validLaunch().copy(speedMetersPerSecond = 0.0)))
        assertSuccess(TrajectoryPhysics.simulate(validLaunch(), air = AirSpec(0.0, 0.40)))
        assertSuccess(TrajectoryPhysics.simulate(validLaunch(), air = AirSpec(1.225, 0.0)))
        assertSuccess(TrajectoryPhysics.simulate(validLaunch().copy(angleDegrees = -90.0, launchHeightMeters = 1.0)))
        assertSuccess(TrajectoryPhysics.simulate(validLaunch().copy(angleDegrees = 90.0)))
        assertSuccess(TrajectoryPhysics.simulate(validLaunch().copy(launchHeightMeters = 0.0)))
    }

    @Test
    fun noDragRk4MatchesClosedFormForDragCoefficientZero() {
        val trajectory = assertSuccess(
            TrajectoryPhysics.simulate(
                launch = LaunchState.fromMilesPerHour(70.0, 25.0),
                air = AirSpec(densityKgPerCubicMeter = 1.225, dragCoefficient = 0.0),
                options = options,
            ),
        )

        assertNoDragClosedForm(trajectory)
    }

    @Test
    fun noDragRk4MatchesClosedFormForAirDensityZero() {
        val trajectory = assertSuccess(
            TrajectoryPhysics.simulate(
                launch = LaunchState.fromMilesPerHour(70.0, 25.0),
                air = AirSpec(densityKgPerCubicMeter = 0.0, dragCoefficient = 0.40),
                options = options,
            ),
        )

        assertNoDragClosedForm(trajectory)
    }

    @Test
    fun defensiveNonFiniteStateFailureIsMapped() {
        val outcome = TrajectoryPhysics.simulateFromState(
            initialState = IntegratorState(
                timeSeconds = 0.0,
                xMeters = 0.0,
                yMeters = Double.POSITIVE_INFINITY,
                vxMetersPerSecond = 1.0,
                vyMetersPerSecond = 1.0,
            ),
            ball = BallSpec.softball12Inch(),
            air = AirSpec.standardSeaLevel(),
            options = options,
        )

        assertEquals(TrajectoryFailure.NON_FINITE_STATE, assertFailure(outcome).reason)
    }

    @Test
    fun groundCrossingIsInterpolatedAndPositiveHeightUsesAbsoluteApex() {
        val trajectory = assertSuccess(
            TrajectoryPhysics.simulate(
                launch = LaunchState.fromMetersPerSecond(
                    speedMetersPerSecond = 10.0,
                    angleDegrees = 0.0,
                    launchHeightMeters = 2.0,
                ),
                air = AirSpec(densityKgPerCubicMeter = 0.0, dragCoefficient = 0.0),
                options = options,
            ),
        )
        val lastFixedStepTime = trajectory.samples.dropLast(1).last().timeSeconds

        assertEquals(0.0, trajectory.samples.last().yMeters, 1e-12)
        assertTrue(trajectory.hangTimeSeconds > lastFixedStepTime)
        assertTrue(trajectory.hangTimeSeconds - lastFixedStepTime < options.timeStepSeconds)
        assertEquals(2.0, trajectory.apexMeters, 1e-9)
        assertEquals(10.0 * kotlin.math.sqrt(2.0 * 2.0 / 9.81), trajectory.carryMeters, 0.05)
    }

    @Test
    fun shortMaxFlightFailsWithoutGroundCrossing() {
        val outcome = TrajectoryPhysics.simulate(
            launch = LaunchState.fromMilesPerHour(70.0, 25.0),
            air = AirSpec(0.0, 0.0),
            options = options.copy(maxFlightSeconds = 0.05),
        )

        assertEquals(TrajectoryFailure.NO_GROUND_INTERSECTION, assertFailure(outcome).reason)
    }

    @Test
    fun dragShortensCarryAndMoreDragShortensFurther() {
        val noDrag = assertSuccess(TrajectoryPhysics.simulate(validLaunch(), air = AirSpec(0.0, 0.0), options = options))
        val standardDrag = assertSuccess(TrajectoryPhysics.simulate(validLaunch(), air = AirSpec.standardSeaLevel(), options = options))
        val highDrag = assertSuccess(TrajectoryPhysics.simulate(validLaunch(), air = AirSpec(1.225, 0.80), options = options))

        assertTrue(standardDrag.carryMeters < noDrag.carryMeters)
        assertTrue(highDrag.carryMeters < standardDrag.carryMeters)
        assertTrue(standardDrag.samples.isNotEmpty())
        assertTrue(standardDrag.samples.all { it.timeSeconds.isFinite() && it.xMeters.isFinite() && it.yMeters.isFinite() })
    }

    @Test
    fun zeroSpeedAndLaunchAngleEdgeCasesArePinned() {
        val zeroGround = assertSuccess(TrajectoryPhysics.simulate(LaunchState.fromMetersPerSecond(0.0, 0.0), options = options))
        assertEquals(0.0, zeroGround.carryMeters, 0.0)
        assertEquals(0.0, zeroGround.hangTimeSeconds, 0.0)
        assertEquals(0.0, zeroGround.apexMeters, 0.0)

        val zeroFromHeight = assertSuccess(TrajectoryPhysics.simulate(LaunchState.fromMetersPerSecond(0.0, 0.0, launchHeightMeters = 2.0), options = options))
        assertEquals(0.0, zeroFromHeight.carryMeters, 1e-12)
        assertTrue(zeroFromHeight.hangTimeSeconds > 0.0)

        val negativeGround = assertSuccess(TrajectoryPhysics.simulate(LaunchState.fromMetersPerSecond(20.0, -10.0), options = options))
        assertEquals(0.0, negativeGround.carryMeters, 0.0)
        assertEquals(0.0, negativeGround.hangTimeSeconds, 0.0)

        val negativeFromHeight = assertSuccess(TrajectoryPhysics.simulate(LaunchState.fromMetersPerSecond(20.0, -10.0, launchHeightMeters = 2.0), options = options))
        assertTrue(negativeFromHeight.carryMeters > 0.0)
        assertTrue(negativeFromHeight.hangTimeSeconds > 0.0)

        val vertical = assertSuccess(TrajectoryPhysics.simulate(LaunchState.fromMetersPerSecond(20.0, 90.0), options = options))
        assertEquals(0.0, vertical.carryMeters, 1e-9)
        assertTrue(vertical.hangTimeSeconds > 0.0)

        assertEquals(TrajectoryFailure.INVALID_LAUNCH, assertFailure(TrajectoryPhysics.simulate(LaunchState.fromMetersPerSecond(20.0, -90.1))).reason)
        assertEquals(TrajectoryFailure.INVALID_LAUNCH, assertFailure(TrajectoryPhysics.simulate(LaunchState.fromMetersPerSecond(20.0, 90.1))).reason)
    }

    private fun assertNoDragClosedForm(trajectory: TrajectoryResult) {
        val speedMetersPerSecond = 70.0 / 2.2369362920544
        val theta = 25.0 * PI / 180.0
        val expectedRange = speedMetersPerSecond * speedMetersPerSecond * sin(2.0 * theta) / 9.81
        val expectedApex = (speedMetersPerSecond * sin(theta)) * (speedMetersPerSecond * sin(theta)) / (2.0 * 9.81)
        val expectedHang = 2.0 * speedMetersPerSecond * sin(theta) / 9.81

        assertEquals(expectedRange, trajectory.carryMeters, 0.05)
        assertEquals(expectedApex, trajectory.apexMeters, 0.05)
        assertEquals(expectedHang, trajectory.hangTimeSeconds, 0.01)
    }

    private fun validLaunch(): LaunchState =
        LaunchState.fromMilesPerHour(70.0, 25.0)

    private fun assertSuccess(outcome: TrajectoryOutcome): TrajectoryResult =
        assertInstanceOf(TrajectoryOutcome.Success::class.java, outcome).trajectory

    private fun assertFailure(outcome: TrajectoryOutcome): TrajectoryOutcome.Failure =
        assertInstanceOf(TrajectoryOutcome.Failure::class.java, outcome)

    private val options = TrajectoryOptions.default()
}
