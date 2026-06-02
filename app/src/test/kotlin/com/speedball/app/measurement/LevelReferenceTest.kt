package com.speedball.app.measurement

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class LevelReferenceTest {
    @Test
    fun stableGravitySnapshotComputesRollForDisplayRotation() {
        val upright = snapshot(
            sample = GravityVectorSample(0.0, 9.81, 0.0),
            rotation = LevelReferenceDisplayRotation.ROTATION_0,
        )
        val ninety = snapshot(
            sample = GravityVectorSample(9.81, 0.0, 0.0),
            rotation = LevelReferenceDisplayRotation.ROTATION_90,
        )
        val tilted = snapshot(
            sample = GravityVectorSample(4.905, 8.495709211, 0.0),
            rotation = LevelReferenceDisplayRotation.ROTATION_0,
        )

        assertEquals(0.0, upright.rollDegrees, 1.0e-9)
        assertEquals(0.0, ninety.rollDegrees, 1.0e-9)
        assertEquals(30.0, tilted.rollDegrees, 0.001)
    }

    @Test
    fun launchAngleCorrectionSubtractsPhoneRollFromImageAngle() {
        val level = snapshot(GravityVectorSample(4.905, 8.495709211, 0.0))

        assertEquals(10.0, LevelReferenceCalculator.correctLaunchAngleDegrees(40.0, level) ?: Double.NaN, 0.001)
        assertEquals(40.0, LevelReferenceCalculator.correctLaunchAngleDegrees(40.0, null) ?: Double.NaN, 0.001)
    }

    @Test
    fun snapshotRejectsBadOrMovingInputs() {
        assertFailure(
            LevelReferenceCalculator.buildSnapshot(
                samples = List(LevelReferenceCalculator.MIN_SAMPLE_COUNT - 1) {
                    GravityVectorSample(0.0, 9.81, 0.0)
                },
                source = LevelReferenceSource.GRAVITY_SENSOR,
                displayRotation = LevelReferenceDisplayRotation.ROTATION_0,
                maxGyroMagnitudeRadPerSecond = 0.0,
                capturedAtEpochMillis = 1L,
            ),
            "at least",
        )
        assertFailure(
            LevelReferenceCalculator.buildSnapshot(
                samples = List(LevelReferenceCalculator.MIN_SAMPLE_COUNT) {
                    GravityVectorSample(0.0, 2.0, 0.0)
                },
                source = LevelReferenceSource.GRAVITY_SENSOR,
                displayRotation = LevelReferenceDisplayRotation.ROTATION_0,
                maxGyroMagnitudeRadPerSecond = 0.0,
                capturedAtEpochMillis = 1L,
            ),
            "magnitude",
        )
        assertFailure(
            LevelReferenceCalculator.buildSnapshot(
                samples = List(LevelReferenceCalculator.MIN_SAMPLE_COUNT) {
                    GravityVectorSample(0.0, 9.81, 0.0)
                },
                source = LevelReferenceSource.ACCELEROMETER_FALLBACK,
                displayRotation = LevelReferenceDisplayRotation.ROTATION_0,
                maxGyroMagnitudeRadPerSecond = 0.50,
                capturedAtEpochMillis = 1L,
            ),
            "still",
        )
    }

    private fun snapshot(
        sample: GravityVectorSample,
        rotation: LevelReferenceDisplayRotation = LevelReferenceDisplayRotation.ROTATION_0,
    ): LevelReferenceSnapshot {
        val outcome = LevelReferenceCalculator.buildSnapshot(
            samples = List(LevelReferenceCalculator.MIN_SAMPLE_COUNT) { sample },
            source = LevelReferenceSource.GRAVITY_SENSOR,
            displayRotation = rotation,
            maxGyroMagnitudeRadPerSecond = 0.0,
            capturedAtEpochMillis = 1L,
        )

        return assertInstanceOf(LevelReferenceOutcome.Success::class.java, outcome).snapshot
    }

    private fun assertFailure(
        outcome: LevelReferenceOutcome,
        expectedMessagePart: String,
    ) {
        val failure = assertInstanceOf(LevelReferenceOutcome.Failure::class.java, outcome)
        assertTrue(failure.message.contains(expectedMessagePart))
    }
}
