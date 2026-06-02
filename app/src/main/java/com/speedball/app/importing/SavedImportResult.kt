package com.speedball.app.importing

/** Redacted saved measurement/estimate summary. */
data class SavedResultSummary(
    val id: String,
    val createdAtEpochMillis: Long,
    val sourceKind: ImportResultSourceKind,
    val evidence: ImportEvidenceSummary,
    val speedMilesPerHour: Double?,
    val launchAngleDegrees: Double?,
    val noReadReason: String?,
) {
    init {
        require(id.isNotBlank()) { "Saved result id must not be blank." }
        require(createdAtEpochMillis >= 0L) { "Saved result timestamp must be non-negative." }
        if (noReadReason != null) {
            require(speedMilesPerHour == null && launchAngleDegrees == null) {
                "No-read summaries must not carry result values."
            }
        }
    }
}

/** Small in-memory repository core used by storage implementations and tests. */
class SavedResultHistory(
    initial: List<SavedResultSummary> = emptyList(),
) {
    private val results = initial.toMutableList()

    fun save(summary: SavedResultSummary) {
        results.removeAll { it.id == summary.id }
        results += summary
        results.sortByDescending { it.createdAtEpochMillis }
    }

    fun list(): List<SavedResultSummary> = results.toList()

    fun delete(
        id: String,
        grantState: ImportContentGrantState = ImportContentGrantState(persistedReadGrantActive = false),
    ): ImportContentGrantState {
        results.removeAll { it.id == id }
        return grantState.releaseOnDelete()
    }

    fun clear(
        grantState: ImportContentGrantState = ImportContentGrantState(persistedReadGrantActive = false),
    ): ImportContentGrantState {
        results.clear()
        return grantState.releaseOnDelete()
    }
}
