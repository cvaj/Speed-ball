package com.speedball.app.measurement

import com.speedball.core.measurement.VelocityMeasurement
import com.speedball.core.physics.TrajectoryResult
import com.speedball.core.physics.TrajectorySample
import com.speedball.core.velocity.VelocityFitResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class MeasurementWorkflowStateTest {
    @Test
    fun missingCalibrationCannotBecomeSuccess() {
        val state = MeasurementWorkflowState()
            .reduce(MeasurementWorkflowEvent.ColorSampleSelected)
            .reduce(MeasurementWorkflowEvent.BoundSourceProofAvailable)
            .reduce(MeasurementWorkflowEvent.StartCapture)

        assertNoRead(state, MeasurementRunFailure.BAD_CALIBRATION)
    }

    @Test
    fun missingColorSampleCannotBecomeSuccess() {
        val state = MeasurementWorkflowState()
            .reduce(MeasurementWorkflowEvent.CalibrationSelected)
            .reduce(MeasurementWorkflowEvent.BoundSourceProofAvailable)
            .reduce(MeasurementWorkflowEvent.StartCapture)

        assertNoRead(state, MeasurementRunFailure.DETECTION_FAILED)
    }

    @Test
    fun unprovenSourceCannotBecomeSuccess() {
        val state = MeasurementWorkflowState()
            .reduce(MeasurementWorkflowEvent.CalibrationSelected)
            .reduce(MeasurementWorkflowEvent.ColorSampleSelected)
            .reduce(MeasurementWorkflowEvent.StartCapture)

        assertNoRead(state, MeasurementRunFailure.UNPROVEN_TIMING)
    }

    @Test
    fun developerProofDiagnosticsDoNotTransitionToProductionSuccess() {
        val state = MeasurementWorkflowState()
            .reduce(MeasurementWorkflowEvent.CalibrationSelected)
            .reduce(MeasurementWorkflowEvent.ColorSampleSelected)
            .reduce(MeasurementWorkflowEvent.DeveloperProofDiagnosticReceived)
            .reduce(MeasurementWorkflowEvent.StartCapture)

        assertNoRead(state, MeasurementRunFailure.UNPROVEN_TIMING)
    }

    @Test
    fun clearingCalibrationOrColorAfterSuccessReturnsToNoRead() {
        val successState = readyState()
            .reduce(MeasurementWorkflowEvent.PipelineMeasurementCompleted(successOutcome()))

        assertInstanceOf(MeasurementRunOutcome.Success::class.java, successState.result)

        val missingCalibration = successState.reduce(MeasurementWorkflowEvent.CalibrationCleared)
        val missingColor = successState.reduce(MeasurementWorkflowEvent.ColorSampleCleared)

        assertNoRead(missingCalibration, MeasurementRunFailure.BAD_CALIBRATION)
        assertNoRead(missingColor, MeasurementRunFailure.DETECTION_FAILED)
    }

    @Test
    fun onlyPipelineOutcomeEventCanCarrySuccess() {
        val armed = readyState()
            .reduce(MeasurementWorkflowEvent.ArmCapture)
            .reduce(MeasurementWorkflowEvent.StartCapture)

        assertInstanceOf(MeasurementRunOutcome.NoRead::class.java, armed.result)

        val measured = armed.reduce(MeasurementWorkflowEvent.PipelineMeasurementCompleted(successOutcome()))

        assertInstanceOf(MeasurementRunOutcome.Success::class.java, measured.result)
    }

    @Test
    fun mainSourceAddsNoSyntheticOrFakeTimingProof() {
        val sourceRoot = listOf(Path.of("app/src/main/java"), Path.of("src/main/java")).first { Files.exists(it) }
        val paths = Files.walk(sourceRoot)
        val offenders = try {
            paths
                .filter { it.toString().endsWith(".kt") }
                .flatMap { path ->
                    Files.readAllLines(path).mapIndexed { index, line -> "${path}:${index + 1}:$line" }.stream()
                }
                .filter { line ->
                    line.contains("SyntheticMeasurementTimingProof") ||
                        line.contains("SyntheticWorkflowTimingProof") ||
                        line.contains("FakeMeasurementTimingProof") ||
                        line.contains("FakeWorkflowTimingProof")
                }
                .toList()
        } finally {
            paths.close()
        }

        assertTrue(offenders.isEmpty(), "Main source must not add synthetic/fake timing proof: $offenders")
    }

    @Test
    fun workflowStateDoesNotExposeLocalMeasurementValueFields() {
        val source = Files.readAllBytes(sourcePath("measurement/MeasurementWorkflowState.kt"))
            .toString(Charsets.UTF_8)

        listOf(
            "isSuccess",
            "milesPerHour",
            "mph",
            "speed",
            "angle",
            "trajectory",
            "carry",
            "apex",
            "hangTime",
        ).forEach { forbidden ->
            assertFalse(source.contains("val $forbidden"), "Workflow state must not expose local result field '$forbidden'.")
        }
        assertTrue(source.contains("val result: MeasurementRunOutcome"))
    }

    private fun readyState(): MeasurementWorkflowState =
        MeasurementWorkflowState()
            .reduce(MeasurementWorkflowEvent.CalibrationSelected)
            .reduce(MeasurementWorkflowEvent.ColorSampleSelected)
            .reduce(MeasurementWorkflowEvent.BoundSourceProofAvailable)

    private fun assertNoRead(state: MeasurementWorkflowState, reason: MeasurementRunFailure) {
        val noRead = assertInstanceOf(MeasurementRunOutcome.NoRead::class.java, state.result)
        assertEquals(reason, noRead.reason)
        assertFalse(noRead.toString().contains("mph", ignoreCase = true))
        assertFalse(noRead.toString().contains("angle", ignoreCase = true))
        assertFalse(noRead.toString().contains("trajectory", ignoreCase = true))
    }

    private fun successOutcome(): MeasurementRunOutcome.Success =
        MeasurementRunOutcome.Success(
            measurement = VelocityMeasurement(
                fit = VelocityFitResult(
                    xInterceptPx = 0.0,
                    yInterceptPx = 0.0,
                    vxPxPerSecond = 10.0,
                    vyPxPerSecond = -2.0,
                    speedPxPerSecond = 10.2,
                    launchAngleDegrees = 12.0,
                    rSquaredX = 1.0,
                    rSquaredY = 1.0,
                    rmsResidualPx = 0.1,
                    usedOriginalIndices = listOf(0, 1, 2),
                ),
                pixelsPerFoot = 2.0,
                feetPerSecond = 100.0,
                milesPerHour = 72.5,
                launchAngleDegrees = 12.0,
            ),
            trajectory = TrajectoryResult(
                samples = listOf(TrajectorySample(0.0, 0.0, 0.0, 0.0, 0.0)),
                apexMeters = 12.0,
                carryMeters = 100.0,
                hangTimeSeconds = 3.0,
            ),
            detectionCount = 3,
            timingProof = SyntheticMeasurementTimingProof(),
        )

    private fun sourcePath(relativeFileName: String): Path {
        val appPath = Path.of("app/src/main/java/com/speedball/app/$relativeFileName")
        return if (Files.exists(appPath)) {
            appPath
        } else {
            Path.of("src/main/java/com/speedball/app/$relativeFileName")
        }
    }
}
