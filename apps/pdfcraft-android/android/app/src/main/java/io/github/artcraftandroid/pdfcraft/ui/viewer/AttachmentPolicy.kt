package io.github.artcraftandroid.pdfcraft.ui.viewer

import java.util.Locale

/** App packages and code an attachment must never hand to another app (the installer). */
private val INSTALLABLE_EXTENSIONS = setOf("apk", "apks", "apkm", "xapk", "aab", "dex", "jar")
private const val PACKAGE_ARCHIVE = "application/vnd.android.package-archive"

/**
 * Whether an embedded file named [name] (MIME [type] from its extension, if known) is an Android
 * app package or code. A malicious PDF must not get Android's installer started from PdfCraft,
 * which may hold the "install unknown apps" permission for its own updater.
 */
internal fun installableAttachment(name: String, type: String?): Boolean {
    val lower = name.lowercase(Locale.ROOT).trimEnd('.', ' ')
    val ext = lower.substringAfterLast('.', "")
    return ext in INSTALLABLE_EXTENSIONS || type?.lowercase(Locale.ROOT) == PACKAGE_ARCHIVE
}
