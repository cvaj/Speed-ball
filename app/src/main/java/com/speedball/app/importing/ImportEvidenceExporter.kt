package com.speedball.app.importing

/** Formats redacted evidence for share/export surfaces. */
object ImportEvidenceExporter {
    fun format(summary: SavedResultSummary): String =
        buildList {
            add("source=${summary.sourceKind}")
            add("timing=${summary.evidence.timingBasis}")
            add("frames=${summary.evidence.frameCount}")
            add("detections=${summary.evidence.detectionCount}")
            summary.speedMilesPerHour?.let { add("speedMph=${it.formatEvidence(1)}") }
            summary.launchAngleDegrees?.let { add("angleDeg=${it.formatEvidence(1)}") }
            summary.noReadReason?.let { add("noReadReason=${it.compactEvidence()}") }
            summary.evidence.assumptions.forEach { add("assumption=${it.compactEvidence()}") }
        }.joinToString(separator = "\n")

    private fun Double.formatEvidence(decimals: Int): String =
        "%.${decimals}f".format(this)

    private fun String.compactEvidence(): String =
        replace(Regex("\\s+"), " ").take(180)
}
