package com.speedball.app.measurement

import org.junit.jupiter.api.Assertions.assertFalse
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
}
