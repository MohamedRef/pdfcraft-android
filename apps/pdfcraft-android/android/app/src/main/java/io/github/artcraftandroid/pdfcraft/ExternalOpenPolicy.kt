package io.github.artcraftandroid.pdfcraft

import java.util.Locale

/** Plain Kotlin policy shared by the Activity router and local JVM tests (no Android mocks). */
internal data class IncomingPdfUri(val scheme: String?, val authority: String?, val path: String?)

/**
 * Only a readable Android provider or local file is a possible external PDF source.
 * A malicious explicit intent must never turn into a remote URL or an arbitrary URI scheme.
 * The PDF engine, not the caller's MIME label, validates the content after copying.
 */
internal fun acceptableExternalPdfUri(action: String?, uri: IncomingPdfUri): Boolean {
    if (action !in setOf(
            "android.intent.action.VIEW",
            "android.intent.action.SEND",
            "android.intent.action.SEND_MULTIPLE",
        )
    ) return false
    return when (uri.scheme?.lowercase(Locale.ROOT)) {
        "content" -> !uri.authority.isNullOrBlank()
        "file" -> uri.path?.startsWith("/") == true && !uri.path.isNullOrBlank()
        else -> false
    }
}

/** Prefer a valid URI; broken/unsupported candidates must not hide later ClipData entries. */
internal fun firstExternalPdfCandidate(action: String?, uris: List<IncomingPdfUri>): Int? =
    uris.indexOfFirst { acceptableExternalPdfUri(action, it) }.takeIf { it >= 0 }
