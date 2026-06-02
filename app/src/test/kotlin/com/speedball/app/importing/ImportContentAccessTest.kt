package com.speedball.app.importing

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ImportContentAccessTest {
    @Test
    fun acceptsUserSelectedContentUriWithoutPersistingOriginalReference() {
        val access = assertSuccess(
            ImportContentAccess.validate(
                ImportContentSelection(
                    reference = "content://media/external/video/media/42",
                    selectedByUser = true,
                    persistableReadGrantAvailable = true,
                    retainBeyondActiveSession = false,
                ),
            ),
        )

        assertEquals("content", access.scheme)
        assertTrue(access.selectedByUser)
        assertFalse(access.persistedReadGrantActive)
        assertFalse(access.toString().contains("media/42"))
        assertFalse(access.toString().contains("content://"))
    }

    @Test
    fun rejectsRawPathsAndUnsupportedSchemes() {
        assertNoRead(
            ImportContentAccess.validate(
                ImportContentSelection(
                    reference = "/sdcard/DCIM/private.mp4",
                    selectedByUser = true,
                    persistableReadGrantAvailable = false,
                    retainBeyondActiveSession = false,
                ),
            ),
            ImportNoReadReason.UNSUPPORTED_MEDIA,
        )
        assertNoRead(
            ImportContentAccess.validate(
                ImportContentSelection(
                    reference = "file:///sdcard/DCIM/private.mp4",
                    selectedByUser = true,
                    persistableReadGrantAvailable = false,
                    retainBeyondActiveSession = false,
                ),
            ),
            ImportNoReadReason.UNSUPPORTED_MEDIA,
        )
    }

    @Test
    fun rejectsMissingUserSelectionAndMissingPersistableGrant() {
        assertNoRead(
            ImportContentAccess.validate(
                ImportContentSelection(
                    reference = "content://media/external/video/media/42",
                    selectedByUser = false,
                    persistableReadGrantAvailable = true,
                    retainBeyondActiveSession = false,
                ),
            ),
            ImportNoReadReason.PERMISSION_DENIED,
        )
        assertNoRead(
            ImportContentAccess.validate(
                ImportContentSelection(
                    reference = "content://media/external/video/media/42",
                    selectedByUser = true,
                    persistableReadGrantAvailable = false,
                    retainBeyondActiveSession = true,
                ),
            ),
            ImportNoReadReason.PERMISSION_DENIED,
        )
    }

    @Test
    fun persistedGrantReleasesWhenResultOrSessionIsDeleted() {
        val access = assertSuccess(
            ImportContentAccess.validate(
                ImportContentSelection(
                    reference = "content://media/external/video/media/42",
                    selectedByUser = true,
                    persistableReadGrantAvailable = true,
                    retainBeyondActiveSession = true,
                ),
            ),
        )

        assertTrue(access.persistedReadGrantActive)
        val released = ImportContentGrantState(access.persistedReadGrantActive).releaseOnDelete()
        assertFalse(released.persistedReadGrantActive)
    }

    private fun assertSuccess(
        result: ImportValidationResult<ImportContentAccess>,
    ): ImportContentAccess =
        assertInstanceOf(ImportValidationResult.Success::class.java, result).let {
            assertInstanceOf(ImportContentAccess::class.java, it.value)
        }

    private fun assertNoRead(
        result: ImportValidationResult<ImportContentAccess>,
        reason: ImportNoReadReason,
    ) {
        val noRead = assertInstanceOf(ImportValidationResult.NoRead::class.java, result)
        assertEquals(reason, noRead.reason)
    }
}
