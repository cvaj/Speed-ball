package com.speedball.app.importing

import java.io.File

/** Bounded debug retention for failed recorded-HFR field-proof attempts. */
object RecordedHfrRetentionPolicy {
    const val MAX_RETAINED_FILES = 3
    const val MAX_RETAINED_BYTES = 25L * 1024L * 1024L

    private val recordedHfrName = Regex("""speed_ball_\d+x\d+_\d+_\d+\.mp4""")

    fun displayName(file: File?): String = file?.name?.takeIf { recordedHfrName.matches(it) } ?: "n/a"

    fun cleanupSuccess(file: File?) {
        file?.takeIf { it.isFile && recordedHfrName.matches(it.name) }?.delete()
    }

    fun retainFailure(file: File?, debugBuild: Boolean): RetentionResult {
        if (file == null || !file.isFile || !recordedHfrName.matches(file.name)) {
            return RetentionResult(retained = false, displayName = displayName(file), bytes = 0L)
        }
        if (!debugBuild) {
            file.delete()
            return RetentionResult(retained = false, displayName = file.name, bytes = 0L)
        }
        enforceCaps(file.parentFile)
        return RetentionResult(retained = file.isFile, displayName = file.name, bytes = file.length())
    }

    fun enforceCaps(directory: File?) {
        if (directory == null || !directory.isDirectory) return
        val retained = directory.listFiles()
            ?.filter { it.isFile && recordedHfrName.matches(it.name) }
            ?.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
            ?.toMutableList()
            ?: return
        while (retained.size > MAX_RETAINED_FILES) {
            retained.removeAt(0).delete()
        }
        while (retained.sumOf { it.length() } > MAX_RETAINED_BYTES && retained.size > 1) {
            retained.removeAt(0).delete()
        }
    }
}

/** Result of applying the recorded-HFR debug retention policy to one MP4. */
data class RetentionResult(
    val retained: Boolean,
    val displayName: String,
    val bytes: Long,
)
