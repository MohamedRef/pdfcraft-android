package io.github.artcraftandroid.pdfcraft.ui

/** What a permissions password restricts (Acrobat's Restrict editing and printing). */
data class Restrictions(
    val printing: Boolean = true,
    val changes: Boolean = false,
    val copy: Boolean = false,
    val accessibility: Boolean = true,
)

/** A password the app sets: 6 to 128 printable ASCII characters. */
fun passwordOk(pw: String): Boolean = pw.length in 6..128 && pw.all { it in '!'..'~' }

/**
 * The `doc_protect` arguments for an open password and/or a permissions password with
 * [restrictions], or null when the combination is not valid: at least one password, each one
 * valid, and the two different (else the permissions password could not be told apart).
 */
fun protectArgs(open: String, permissions: String?, restrictions: Restrictions): Map<String, Any?>? {
    if (open.isEmpty() && permissions.isNullOrEmpty()) return null
    if (open.isNotEmpty() && !passwordOk(open)) return null
    if (!permissions.isNullOrEmpty() && (!passwordOk(permissions) || permissions == open)) return null
    return buildMap {
        if (open.isNotEmpty()) put("open_password", open)
        if (!permissions.isNullOrEmpty()) {
            put("permissions_password", permissions)
            put("printing", if (restrictions.printing) "high" else "none")
            put("changes", if (restrictions.changes) "any-except-extract" else "none")
            put("copy", restrictions.copy)
            put("accessibility", restrictions.accessibility)
        }
    }
}

/** Remove Hidden Information categories, in the engine's order. */
val HIDDEN_CATEGORIES = listOf(
    "metadata", "attachments", "comments", "form-fields", "hidden-text", "hidden-layers", "bookmarks", "links-actions-scripts", "private-data",
)
