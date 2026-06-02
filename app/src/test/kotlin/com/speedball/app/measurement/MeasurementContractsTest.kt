package com.speedball.app.measurement

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

class MeasurementContractsTest {
    private val timingProofImplementationPattern =
        Pattern.compile("""(\)\s*:\s*|implements\s+)MeasurementTimingProof\b""")

    @Test
    fun productionNoReadCarriesNoPartialSpeedValue() {
        val outcome = MeasurementPipeline.currentProductionNoRead()
        val rendered = outcome.toString()

        assertTrue(rendered.contains("UNPROVEN_TIMING"))
        assertFalse(rendered.contains("mph", ignoreCase = true))
        assertFalse(rendered.contains("angle", ignoreCase = true))
        assertFalse(rendered.contains("trajectory", ignoreCase = true))
    }

    @Test
    fun visualEstimateSuccessIsSeparateFromStrictMeasurementOutcomeAndProofToken() {
        val outcome = VisualEstimateResultFactory.successOrNoRead(
            milesPerHour = 72.5,
            launchAngleDegrees = 8.0,
            diagnostics = validEstimateDiagnostics(),
        )

        val success = assertInstanceOf(VisualEstimateOutcome.Success::class.java, outcome)
        val genericSuccess: Any = success
        assertEquals(72.5, success.milesPerHour)
        assertFalse(genericSuccess is MeasurementRunOutcome)
        assertFalse(genericSuccess is MeasurementTimingProof)
        assertTrue(success.diagnostics.assumptions.any { it.contains("calibrated image plane") })
    }

    @Test
    fun visualEstimateSuccessFactoryNoReadsInvalidResultFields() {
        assertEstimateNoRead(
            VisualEstimateResultFactory.successOrNoRead(
                milesPerHour = Double.NaN,
                launchAngleDegrees = 8.0,
                diagnostics = validEstimateDiagnostics(),
            ),
            VisualEstimateNoReadReason.NON_FINITE_RESULT,
        )
        assertEstimateNoRead(
            VisualEstimateResultFactory.successOrNoRead(
                milesPerHour = 70.0,
                launchAngleDegrees = Double.POSITIVE_INFINITY,
                diagnostics = validEstimateDiagnostics(),
            ),
            VisualEstimateNoReadReason.NON_FINITE_RESULT,
        )
        assertEstimateNoRead(
            VisualEstimateResultFactory.successOrNoRead(
                milesPerHour = 70.0,
                launchAngleDegrees = 8.0,
                diagnostics = validEstimateDiagnostics().copy(fitResidualPx = Double.NaN),
            ),
            VisualEstimateNoReadReason.EXCESSIVE_RESIDUAL,
        )
        assertEstimateNoRead(
            VisualEstimateResultFactory.successOrNoRead(
                milesPerHour = 70.0,
                launchAngleDegrees = 8.0,
                diagnostics = validEstimateDiagnostics().copy(assumptions = emptyList()),
            ),
            VisualEstimateNoReadReason.PLANAR_ASSUMPTION_VIOLATED,
        )
        assertEstimateNoRead(
            VisualEstimateResultFactory.successOrNoRead(
                milesPerHour = 70.0,
                launchAngleDegrees = 8.0,
                diagnostics = validEstimateDiagnostics().copy(
                    timingBasis = EstimateTimingBasis.VISUAL_FRAME_DELTA_INFERENCE,
                    assumptions = listOf(VisualEstimateDiagnostics.PLANAR_MOTION_ASSUMPTION),
                ),
            ),
            VisualEstimateNoReadReason.BAD_TIMESTAMPS,
        )
        assertEstimateNoRead(
            VisualEstimateResultFactory.successOrNoRead(
                milesPerHour = 70.0,
                launchAngleDegrees = 8.0,
                diagnostics = validEstimateDiagnostics().copy(
                    scaleBasis = EstimateScaleBasis.BALL_DIAMETER_SELF_CALIBRATION,
                    assumptions = listOf(VisualEstimateDiagnostics.PLANAR_MOTION_ASSUMPTION),
                ),
            ),
            VisualEstimateNoReadReason.BAD_CALIBRATION,
        )
    }

    @Test
    fun estimateTimestampModelRequiresRealStrictlyIncreasingTimestamps() {
        val success = assertInstanceOf(
            EstimateTimestampOutcome.Success::class.java,
            EstimateTimestampModel.fromRealTimestampNanos(listOf(0L, 8_333_333L, 16_666_666L, 33_333_332L)),
        )

        assertEquals(4, success.timestampsSeconds.size)
        assertEquals(3, success.gapSummary.intervalCount)
        assertEquals(0.016666666, success.gapSummary.maxGapSeconds, 1.0e-12)

        assertTimestampFailure(EstimateTimestampModel.fromRealTimestampNanos(listOf(0L, 1L, 1L)))
        assertTimestampFailure(EstimateTimestampModel.fromRealTimestampNanos(listOf(0L)))
        assertTimestampFailure(EstimateTimestampModel.fromRealTimestampNanos(listOf(0L, -1L)))
    }

    @Test
    fun estimateTimestampModelRejectsNominalCadenceForS10DirectEstimate() {
        val failure = EstimateTimestampModel.fromNominalCadence(frameCount = 4, framesPerSecond = 30.0)

        assertEquals(VisualEstimateNoReadReason.BAD_TIMESTAMPS, failure.reason)
        assertTrue(failure.message.contains("requires real per-frame timestamps"))
        assertTrue(failure.message.contains("not nominal cadence"))
    }

    @Test
    fun visualEstimateSuccessHasNoPublicDataClassCopyEscapeHatch() {
        val source = Files.readAllBytes(sourcePath("measurement/MeasurementContracts.kt"))
            .toString(Charsets.UTF_8)
        val visualOutcomeSource = source.substringAfter("sealed interface VisualEstimateOutcome")
            .substringBefore("/** Constructs estimate outcomes")

        assertFalse(visualOutcomeSource.contains("data class Success internal constructor"))
        assertFalse(visualOutcomeSource.contains("data class Success("))
        assertTrue(visualOutcomeSource.contains("class Success internal constructor"))
    }

    @Test
    fun mainSourceContainsOnlyAllowlistedTimingProofImplementation() {
        val sourceRoot = listOf(Path.of("app/src/main/java"), Path.of("src/main/java")).first { Files.exists(it) }
        val paths = Files.walk(sourceRoot)
        val implementations = try {
            paths
                .filter { it.toString().endsWith(".kt") }
                .flatMap { path ->
                    Files.readAllLines(path).mapIndexed { index, line -> "${path}:${index + 1}:$line" }.stream()
                }
                .filter { line ->
                    timingProofImplementationPattern.matcher(line).find()
                }
                .toList()
        } finally {
            paths.close()
        }
        val offenders = implementations.filterNot { it.contains("DirectSourceMeasurementTimingProof.kt") }

        assertTrue(implementations.size == 1, "Main source must have exactly one MeasurementTimingProof implementation: $implementations")
        assertTrue(offenders.isEmpty(), "Only the direct-source proof may implement MeasurementTimingProof: $offenders")
    }

    @Test
    fun mainSourceHasNoCallerSettableDirectProofBindingFields() {
        val sourceRoot = listOf(Path.of("app/src/main/java"), Path.of("src/main/java")).first { Files.exists(it) }
        val allowlistedFiles = setOf(
            sourceRoot.resolve("com/speedball/app/capture/DirectTimingSourceProof.kt").normalize().toString(),
            sourceRoot.resolve("com/speedball/app/capture/DirectTimingSourceCapture.kt").normalize().toString(),
            sourceRoot.resolve("com/speedball/app/capture/DirectTimingSourceProofRunner.kt").normalize().toString(),
            sourceRoot.resolve("com/speedball/app/measurement/DirectSourceMeasurementTimingProof.kt").normalize().toString(),
        )
        val paths = Files.walk(sourceRoot)
        val offenders = try {
            paths
                .filter { it.toString().endsWith(".kt") }
                .flatMap { path ->
                    Files.readAllLines(path).mapIndexed { index, line -> path to "${path}:${index + 1}:$line" }.stream()
                }
                .filter { (path, line) ->
                    path.normalize().toString() !in allowlistedFiles &&
                        (
                            line.contains("DirectProofTokenEligibility(") ||
                                line.contains("fromProvenSequence(") ||
                                line.contains("fromEligibility(") ||
                                line.contains("fromProof(") ||
                                line.contains("fromVerifiedDirectProof(") ||
                                line.contains("sourceProofIdentity") ||
                                line.contains("sourceProofRunId") ||
                                line.contains("DirectSourceMeasurementInput(") ||
                                line.contains("DirectTimingSourceProofOutcome.Success(") ||
                                line.contains("DirectFrameProof(")
                            )
                }
                .map { it.second }
                .toList()
        } finally {
            paths.close()
        }

        assertTrue(offenders.isEmpty(), "Direct proof binding must not be caller-settable: $offenders")
    }

    private fun validEstimateDiagnostics(): VisualEstimateDiagnostics =
        VisualEstimateDiagnostics(
            frameCount = 4,
            detectionCount = 4,
            timingBasis = EstimateTimingBasis.REAL_PER_FRAME_TIMESTAMPS,
            timestampGapSummary = TimestampGapSummary(
                intervalCount = 3,
                minGapSeconds = 0.008,
                medianGapSeconds = 0.008,
                maxGapSeconds = 0.016,
            ),
            fitResidualPx = 1.25,
            confidence = VisualEstimateConfidence.MEDIUM,
        )

    private fun assertEstimateNoRead(
        outcome: VisualEstimateOutcome,
        reason: VisualEstimateNoReadReason,
    ) {
        val noRead = assertInstanceOf(VisualEstimateOutcome.NoRead::class.java, outcome)
        assertEquals(reason, noRead.reason)
        assertFalse(noRead.toString().contains("mph", ignoreCase = true))
    }

    private fun assertTimestampFailure(outcome: EstimateTimestampOutcome) {
        val failure = assertInstanceOf(EstimateTimestampOutcome.Failure::class.java, outcome)
        assertEquals(VisualEstimateNoReadReason.BAD_TIMESTAMPS, failure.reason)
    }

    private fun sourcePath(relativeFileName: String): Path {
        val appPath = Path.of("app/src/main/java/com/speedball/app/$relativeFileName")
        return if (Files.exists(appPath)) {
            appPath
        } else {
            Path.of("src/main/java/com/speedball/app/$relativeFileName")
        }
    }
}
