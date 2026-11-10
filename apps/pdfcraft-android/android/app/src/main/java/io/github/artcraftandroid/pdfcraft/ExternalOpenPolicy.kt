package io.github.artcraftandroid.pdfcraft

import java.util.Locale

/** Plain Kotlin policy shared by the Activity router and local JVM tests (no Android mocks). */
internal data class IncomingPdfUri(
    val scheme: String?,
    val authority: String?,
    val path: String?,
    /** A `file:` path with `..` and links resolved, when it could be resolved. */
    val canonicalPath: String? = null,
)

/** Whether [path] is [root] itself or inside it (a prefix test that respects path separators). */
internal fun pathWithin(path: String, root: String): Boolean {
    val base = root.trimEnd('/')
    return base.isNotEmpty() && (path == base || path.startsWith("$base/"))
}

/**
 * Only a readable Android provider or local file is a possible external PDF source.
 * A malicious explicit intent must never turn into a remote URL or an arbitrary URI scheme.
 * The PDF engine, not the caller's MIME label, validates the content after copying.
 */
internal fun acceptableExternalPdfUri(action: String?, uri: IncomingPdfUri, privateRoots: List<String> = emptyList()): Boolean {
    if (action !in setOf(
            "android.intent.action.VIEW",
            "android.intent.action.SEND",
            "android.intent.action.SEND_MULTIPLE",
        )
    ) return false
    return when (uri.scheme?.lowercase(Locale.ROOT)) {
        "content" -> !uri.authority.isNullOrBlank()
        // Another app must never make PdfCraft open (and on save, overwrite) its own private
        // files: a file: path inside the app's storage is refused, after resolving `..` and links.
        "file" -> {
            val path = uri.path
            if (path.isNullOrBlank() || !path.startsWith("/")) return false
            // Unresolved, a path must not hide a climb out of a public folder.
            val resolved = uri.canonicalPath ?: path.takeUnless { "/.." in it || "/./" in it } ?: return false
            privateRoots.none { pathWithin(resolved, it) } && privateRoots.none { pathWithin(path, it) }
        }
        else -> false
    }
}

/** Prefer a valid URI; broken/unsupported candidates must not hide later ClipData entries. */
internal fun firstExternalPdfCandidate(action: String?, uris: List<IncomingPdfUri>, privateRoots: List<String> = emptyList()): Int? =
    uris.indexOfFirst { acceptableExternalPdfUri(action, it, privateRoots) }.takeIf { it >= 0 }

/** Recreating an Activity must not repeat an open while its retained ViewModel is still active. */
internal fun shouldRouteInitialPdfIntent(
    savedInstanceStatePresent: Boolean,
    hasDocument: Boolean,
    opening: Boolean,
    awaitingPassword: Boolean,
): Boolean =
    !savedInstanceStatePresent || (!hasDocument && !opening && !awaitingPassword)

/** Reject stale provider recovery and preserve unresolved save/discard choices. */
internal fun canProceedWithPdfOpen(
    requestId: Long,
    latestRequestId: Long,
    awaitingSaveChoice: Boolean,
    resolvingSaveChoice: Boolean,
): Boolean = requestId == latestRequestId && !awaitingSaveChoice && !resolvingSaveChoice
