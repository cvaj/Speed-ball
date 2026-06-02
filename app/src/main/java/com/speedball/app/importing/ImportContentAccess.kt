package com.speedball.app.importing

import java.net.URI
import java.net.URISyntaxException

/** User-selected import reference before Android opens the content stream. */
data class ImportContentSelection(
    val reference: String,
    val selectedByUser: Boolean,
    val persistableReadGrantAvailable: Boolean,
    val retainBeyondActiveSession: Boolean,
)

/**
 * Redacted access description for imported content.
 *
 * The original content URI or filesystem path is never stored in this object.
 */
class ImportContentAccess private constructor(
    val scheme: String,
    val selectedByUser: Boolean,
    val persistedReadGrantActive: Boolean,
) {
    companion object {
        fun validate(selection: ImportContentSelection): ImportValidationResult<ImportContentAccess> {
            if (!selection.selectedByUser) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.PERMISSION_DENIED,
                    message = "Import requires a user-selected video.",
                )
            }
            val scheme = parseScheme(selection.reference)
                ?: return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.UNSUPPORTED_MEDIA,
                    message = "Import requires a content URI from the Android picker.",
                )
            if (scheme != "content") {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.UNSUPPORTED_MEDIA,
                    message = "Import does not accept raw file paths or unsupported URI schemes.",
                )
            }
            if (selection.retainBeyondActiveSession && !selection.persistableReadGrantAvailable) {
                return ImportValidationResult.NoRead(
                    reason = ImportNoReadReason.PERMISSION_DENIED,
                    message = "Import picker did not grant persistent read access.",
                )
            }
            return ImportValidationResult.Success(
                ImportContentAccess(
                    scheme = scheme,
                    selectedByUser = true,
                    persistedReadGrantActive = selection.retainBeyondActiveSession,
                ),
            )
        }

        private fun parseScheme(reference: String): String? =
            try {
                URI(reference).scheme?.lowercase()
            } catch (_: URISyntaxException) {
                null
            }
    }

    override fun equals(other: Any?): Boolean =
        this === other ||
            other is ImportContentAccess &&
                scheme == other.scheme &&
                selectedByUser == other.selectedByUser &&
                persistedReadGrantActive == other.persistedReadGrantActive

    override fun hashCode(): Int {
        var result = scheme.hashCode()
        result = 31 * result + selectedByUser.hashCode()
        result = 31 * result + persistedReadGrantActive.hashCode()
        return result
    }

    override fun toString(): String =
        "ImportContentAccess(scheme=$scheme, selectedByUser=$selectedByUser, " +
            "persistedReadGrantActive=$persistedReadGrantActive)"
}

/** Lifecycle for persisted import grants after a saved result or session ends. */
data class ImportContentGrantState(
    val persistedReadGrantActive: Boolean,
) {
    fun releaseOnDelete(): ImportContentGrantState =
        copy(persistedReadGrantActive = false)
}
