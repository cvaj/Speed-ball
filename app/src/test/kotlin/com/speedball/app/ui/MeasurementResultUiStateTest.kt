package com.speedball.app.ui

import com.speedball.app.measurement.MeasurementRunFailure
import com.speedball.app.measurement.MeasurementRunOutcome
import com.speedball.app.measurement.MeasurementTimingProof
import com.speedball.core.measurement.VelocityMeasurement
import com.speedball.core.physics.TrajectoryResult
import com.speedball.core.physics.TrajectorySample
import com.speedball.core.velocity.VelocityFitResult
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class MeasurementResultUiStateTest {
    @Test
    fun noReadResultContainsReasonAndActionWithoutValues() {
        val lines = measurementOutcomeUiLines(
            MeasurementRunOutcome.NoRead(
                reason = MeasurementRunFailure.BAD_CALIBRATION,
                message = "Known distance must be positive.",
            ),
        )
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("result=no-read"))
        assertTrue(joined.contains("reason=BAD_CALIBRATION"))
        assertTrue(joined.contains("action=recalibrate-distance"))
        assertNoResultValues(joined)
    }

    @Test
    fun successFixtureRendersOnlyOutcomeValues() {
        val lines = measurementOutcomeUiLines(successOutcome())
        val joined = lines.joinToString("\n")

        assertTrue(joined.contains("result=success"))
        assertTrue(joined.contains("evidence=result-ui-test"))
        assertTrue(joined.contains("detections=3"))
        assertTrue(joined.contains("mph=72.5"))
        assertTrue(joined.contains("angleDeg=12.0"))
        assertTrue(joined.contains("trajectory"))
        assertTrue(joined.contains("carryFt=328.1"))
        assertTrue(joined.contains("apexFt=39.4"))
        assertTrue(joined.contains("hangSec=3.00"))
    }

    @Test
    fun unprovenTimingNoReadContainsNoMeasurementValues() {
        val joined = measurementOutcomeUiLines(
            MeasurementRunOutcome.NoRead(
                reason = MeasurementRunFailure.UNPROVEN_TIMING,
                message = "No production frame source has proven image timestamp pairing.",
            ),
        ).joinToString("\n")

        assertTrue(joined.contains("reason=UNPROVEN_TIMING"))
        assertTrue(joined.contains("action=prove-frame-source"))
        assertNoResultValues(joined)
    }

    @Test
    fun longFailureMessagesAreCompacted() {
        val longMessage = "  Capture failed because the current input has repeated timestamp evidence.  ".repeat(8)
        val line = measurementOutcomeUiLines(
            MeasurementRunOutcome.NoRead(
                reason = MeasurementRunFailure.BAD_FRAME_SEQUENCE,
                message = longMessage,
            ),
        ).single()

        assertTrue(line.length <= 220)
        assertTrue(line.endsWith("..."))
        assertFalse(line.contains("  "))
    }

    @Test
    fun formatterDoesNotImportWorkflowReadinessInputs() {
        val source = Files.readAllBytes(sourcePath("ui/MeasurementResultUiState.kt"))
            .toString(Charsets.UTF_8)

        assertFalse(source.contains("CalibrationWorkflow"))
        assertFalse(source.contains("ColorWorkflow"))
        assertFalse(source.contains("Readiness"))
    }

    private fun assertNoResultValues(text: String) {
        assertFalse(text.contains("m" + "ph", ignoreCase = true))
        assertFalse(text.contains("ang" + "le", ignoreCase = true))
        assertFalse(text.contains("tra" + "jectory", ignoreCase = true))
        assertFalse(text.contains("car" + "ry", ignoreCase = true))
        assertFalse(text.contains("apex", ignoreCase = true))
        assertFalse(text.contains("hang", ignoreCase = true))
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
            timingProof = object : MeasurementTimingProof {
                override val evidenceLabel: String = "result-ui-test"
            },
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
