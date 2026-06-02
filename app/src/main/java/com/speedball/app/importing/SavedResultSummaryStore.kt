package com.speedball.app.importing

import java.io.File
import java.util.Properties

/** App-private persisted store for redacted saved-result summaries. */
class SavedResultSummaryStore(
    private val file: File,
) {
    fun load(): ImportValidationResult<SavedResultHistory> {
        if (!file.exists()) return ImportValidationResult.Success(SavedResultHistory())
        return try {
            val properties = Properties()
            file.inputStream().use(properties::load)
            val count = properties.getProperty(KEY_COUNT)?.toIntOrNull()
                ?: return corrupt("Saved result store is missing its count.")
            val summaries = (0 until count).map { index -> properties.readSummary(index) }
            ImportValidationResult.Success(SavedResultHistory(summaries))
        } catch (_: RuntimeException) {
            corrupt("Saved result store is corrupt.")
        }
    }

    fun save(summaries: List<SavedResultSummary>): ImportValidationResult<Unit> {
        return try {
            val properties = Properties()
            properties[KEY_COUNT] = summaries.size.toString()
            summaries.forEachIndexed { index, summary ->
                properties.writeSummary(index, summary)
            }
            file.parentFile?.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.outputStream().use { output ->
                properties.store(output, "Speed-ball saved result summaries")
            }
            if (!temp.renameTo(file)) {
                temp.copyTo(file, overwrite = true)
                temp.delete()
            }
            ImportValidationResult.Success(Unit)
        } catch (_: RuntimeException) {
            ImportValidationResult.NoRead(
                reason = ImportNoReadReason.INVALID_METADATA,
                message = "Saved result store could not be written.",
            )
        }
    }

    private fun Properties.readSummary(index: Int): SavedResultSummary =
        SavedResultSummary(
            id = required(index, "id"),
            createdAtEpochMillis = required(index, "createdAt").toLongStrict(),
            sourceKind = ImportResultSourceKind.valueOf(required(index, "sourceKind")),
            evidence = ImportEvidenceSummary(
                sourceKind = ImportResultSourceKind.valueOf(required(index, "evidenceSourceKind")),
                timingBasis = ImportTimingBasis.valueOf(required(index, "timingBasis")),
                frameCount = required(index, "frameCount").toIntStrict(),
                detectionCount = required(index, "detectionCount").toIntStrict(),
                assumptions = getProperty(key(index, "assumptions")).orEmpty()
                    .split(ASSUMPTION_SEPARATOR)
                    .filter { it.isNotBlank() },
            ),
            speedMilesPerHour = optionalDouble(index, "speedMph"),
            launchAngleDegrees = optionalDouble(index, "angleDeg"),
            noReadReason = getProperty(key(index, "noReadReason"))?.takeIf { it.isNotBlank() },
        )

    private fun Properties.writeSummary(index: Int, summary: SavedResultSummary) {
        put(key(index, "id"), summary.id)
        put(key(index, "createdAt"), summary.createdAtEpochMillis.toString())
        put(key(index, "sourceKind"), summary.sourceKind.name)
        put(key(index, "evidenceSourceKind"), summary.evidence.sourceKind.name)
        put(key(index, "timingBasis"), summary.evidence.timingBasis.name)
        put(key(index, "frameCount"), summary.evidence.frameCount.toString())
        put(key(index, "detectionCount"), summary.evidence.detectionCount.toString())
        put(key(index, "assumptions"), summary.evidence.assumptions.joinToString(ASSUMPTION_SEPARATOR))
        put(key(index, "speedMph"), summary.speedMilesPerHour?.toString().orEmpty())
        put(key(index, "angleDeg"), summary.launchAngleDegrees?.toString().orEmpty())
        put(key(index, "noReadReason"), summary.noReadReason.orEmpty())
    }

    private fun Properties.required(index: Int, field: String): String =
        getProperty(key(index, field)) ?: throw IllegalArgumentException("Missing $field")

    private fun Properties.optionalDouble(index: Int, field: String): Double? =
        getProperty(key(index, field))
            ?.takeIf { it.isNotBlank() }
            ?.toDoubleStrict()

    private fun String.toIntStrict(): Int =
        toIntOrNull() ?: throw IllegalArgumentException("Expected integer.")

    private fun String.toLongStrict(): Long =
        toLongOrNull() ?: throw IllegalArgumentException("Expected long.")

    private fun String.toDoubleStrict(): Double {
        val parsed = toDoubleOrNull() ?: throw IllegalArgumentException("Expected double.")
        require(parsed.isFinite()) { "Expected finite double." }
        return parsed
    }

    private fun corrupt(message: String): ImportValidationResult.NoRead =
        ImportValidationResult.NoRead(
            reason = ImportNoReadReason.INVALID_METADATA,
            message = message,
        )

    private companion object {
        const val KEY_COUNT = "count"
        const val ASSUMPTION_SEPARATOR = "\u001f"

        fun key(index: Int, field: String): String =
            "result.$index.$field"
    }
}
