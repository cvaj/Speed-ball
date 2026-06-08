package com.speedball.app.importing

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class RecordedHfrRetentionPolicyTest {
    @TempDir
    lateinit var tempDir: File

    @Test
    fun debugFailureRetainsMatchingRecordedHfrFile() {
        val file = recordedFile("speed_ball_1280x720_120_100.mp4", bytes = 1024)

        val result = RecordedHfrRetentionPolicy.retainFailure(file, debugBuild = true)

        assertTrue(result.retained)
        assertTrue(file.isFile)
        assertTrue(result.displayName == file.name)
    }

    @Test
    fun releaseFailureDeletesMatchingRecordedHfrFile() {
        val file = recordedFile("speed_ball_1280x720_120_100.mp4", bytes = 1024)

        val result = RecordedHfrRetentionPolicy.retainFailure(file, debugBuild = false)

        assertFalse(result.retained)
        assertFalse(file.exists())
    }

    @Test
    fun successCleanupDeletesOnlyMatchingRecordedHfrFile() {
        val recorded = recordedFile("speed_ball_1280x720_120_100.mp4", bytes = 1)
        val unrelated = recordedFile("user_video.mp4", bytes = 1)

        RecordedHfrRetentionPolicy.cleanupSuccess(recorded)
        RecordedHfrRetentionPolicy.cleanupSuccess(unrelated)

        assertFalse(recorded.exists())
        assertTrue(unrelated.exists())
    }

    @Test
    fun capCleanupDeletesOldestMatchingFilesAndIgnoresUnrelatedFiles() {
        val oldest = recordedFile("speed_ball_1280x720_120_100.mp4", bytes = 1).apply { setLastModified(100) }
        val middle = recordedFile("speed_ball_1280x720_120_200.mp4", bytes = 1).apply { setLastModified(200) }
        val newer = recordedFile("speed_ball_1280x720_120_300.mp4", bytes = 1).apply { setLastModified(300) }
        val newest = recordedFile("speed_ball_1280x720_120_400.mp4", bytes = 1).apply { setLastModified(400) }
        val unrelated = recordedFile("user_video.mp4", bytes = 1).apply { setLastModified(50) }

        RecordedHfrRetentionPolicy.enforceCaps(tempDir)

        assertFalse(oldest.exists())
        assertTrue(middle.exists())
        assertTrue(newer.exists())
        assertTrue(newest.exists())
        assertTrue(unrelated.exists())
    }

    private fun recordedFile(name: String, bytes: Int): File =
        File(tempDir, name).apply {
            writeBytes(ByteArray(bytes) { 1 })
        }
}
