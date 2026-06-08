package com.speedball.app.capture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

class CompanionEncoderScratchTest {
    @TempDir
    lateinit var tempDir: Path

    @Test
    fun scratchFileIsCreatedUnderAppPrivateCacheDirectory() {
        val mode = mode()
        val file = createCompanionScratchFile(tempDir.toFile(), mode, timestampMillis = 123L)
        val parent = requireNotNull(file.parentFile)

        assertEquals(tempDir.resolve("speed-ball-companion").toFile().canonicalFile, parent.canonicalFile)
        assertEquals("companion_1280x720_120_123.mp4", file.name)
        assertTrue(parent.exists())
        assertFalse(file.name.contains('/'))
        assertFalse(file.name.contains('\\'))
    }

    @Test
    fun fileSizeLimitIsBoundedByClampedDurationAndMode() {
        val mode720 = mode(width = 1280, height = 720)
        val mode1080 = mode(width = 1920, height = 1080)

        assertEquals(3_000_000L, companionScratchFileSizeLimitBytes(mode720, durationMillis = 1))
        assertEquals(9_000_000L, companionScratchFileSizeLimitBytes(mode720, durationMillis = 3_000))
        assertEquals(40_000_000L, companionScratchFileSizeLimitBytes(mode1080, durationMillis = 99_000))
    }

    @Test
    fun cleanupDeletesExistingScratchFileAndHandlesAlreadyAbsent() {
        val file = createCompanionScratchFile(tempDir.toFile(), mode(), timestampMillis = 456L)
        Files.write(file.toPath(), "scratch".toByteArray())

        assertEquals(CompanionScratchCleanupStatus.DELETED, deleteCompanionScratchFile(file).status)
        assertFalse(file.exists())
        assertEquals(CompanionScratchCleanupStatus.ALREADY_ABSENT, deleteCompanionScratchFile(file).status)
    }

    @Test
    fun cleanupFailureIsReportedWhenFileStillExists() {
        val directory = createCompanionScratchFile(tempDir.toFile(), mode(), timestampMillis = 789L)
        assertTrue(directory.mkdirs())
        Files.write(directory.toPath().resolve("child"), "not empty".toByteArray())

        assertEquals(CompanionScratchCleanupStatus.FAILED, deleteCompanionScratchFile(directory).status)
        assertTrue(directory.exists())
    }

    @Test
    fun helperDoesNotOpenExtractorOrExposePathDiagnostics() {
        val sourcePath = listOf(
            Path.of("app/src/main/java/com/speedball/app/capture/CompanionEncoderScratch.kt"),
            Path.of("src/main/java/com/speedball/app/capture/CompanionEncoderScratch.kt"),
        ).first { Files.exists(it) }
        val source = sourcePath.readText()

        assertFalse(source.contains("MediaExtractor"))
        assertFalse(source.contains("Log."))
        assertFalse(source.contains("outputPath"))
        assertTrue(source.contains("setOutputFile(file.absolutePath)"))
    }

    private fun mode(
        width: Int = 1280,
        height: Int = 720,
        fps: Int = 120,
    ): HighSpeedMode =
        HighSpeedMode(
            width = width,
            height = height,
            fps = fps,
            aeTargetFpsLower = fps,
            aeTargetFpsUpper = fps,
            recordSupported = true,
        )
}
