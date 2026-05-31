package com.speedball.app.measurement

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.regex.Pattern

class MeasurementContractsTest {
    private val timingProofImplementationPattern =
        Pattern.compile("""(:|implements)\s*MeasurementTimingProof\b""")

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
    fun mainSourceContainsNoTimingProofImplementation() {
        val sourceRoot = listOf(Path.of("app/src/main/java"), Path.of("src/main/java")).first { Files.exists(it) }
        val paths = Files.walk(sourceRoot)
        val offenders = try {
            paths
                .filter { it.toString().endsWith(".kt") }
                .flatMap { path ->
                    Files.readAllLines(path).mapIndexed { index, line -> "${path}:${index + 1}:$line" }.stream()
                }
                .filter { line ->
                    val source = line.substringAfterLast(":")
                    timingProofImplementationPattern.matcher(source).find()
                }
                .toList()
        } finally {
            paths.close()
        }

        assertTrue(offenders.isEmpty(), "Main source must not implement MeasurementTimingProof: $offenders")
    }
}
