package io.github.artcraftandroid.pdfcraft.engine

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import java.io.File

/**
 * Saving into the original file when another app lent it for reading only (#491): with Android's
 * "All files access", which the person turns on in system settings, a document's real file in
 * shared storage is written directly, as PDF readers such as Xodo do. Off by default; nothing
 * here changes the behavior until it is granted.
 */
object AllFilesAccess {
    /**
     * Whether the person has granted All files access to PdfCraft. False when shared storage
     * can't be asked (no primary storage volume: the check then fails inside Android).
     */
    fun granted(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R &&
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /** The system settings page where the person grants it for PdfCraft. */
    fun settingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${context.packageName}"))

    /** Whether this Android version offers All files access (Android 11 and later). */
    val offered: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R

    /** Open the system page for it: PdfCraft's own entry, or the list of apps where that is missing. */
    fun openSettings(context: Context) {
        if (!offered) return
        runCatching { context.startActivity(settingsIntent(context)) }
            .recoverCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }
    }

    /**
     * The file in shared storage that [uri] names, when it can be told: the document id of the
     * system's storage and Downloads providers, the path a file manager's own provider puts in
     * its address, or the `_data` column most providers still report. Only an existing regular
     * file under /storage of [size] bytes (when known) is returned ([usable]).
     */
    fun realFile(context: Context, uri: Uri, size: Long?): File? =
        candidates(context, uri, query = true).asSequence().map(::File).firstOrNull { usable(it, size) }

    /**
     * The paths [uri] may name, most certain first. Without [query] only what its address says,
     * which needs no access to the document (a file manager's one-visit grant may be gone).
     */
    fun candidates(context: Context, uri: Uri, query: Boolean): List<String> {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return emptyList()
        return buildList {
            documentPath(context, uri, query)?.let(::add)
            fileProviderPath(uri.pathSegments)?.let(::add)
            if (query) dataColumn(context, uri)?.let(::add)
        }.distinct()
    }

    /** Whether [file] is an existing regular file in shared storage of [size] bytes (when known). */
    fun usable(file: File, size: Long?): Boolean = runCatching {
        val c = file.canonicalFile
        c.path.startsWith("/storage/") && c.isFile && (size == null || size <= 0 || c.length() == size)
    }.getOrDefault(false)

    /** The path in a storage or Downloads document id (`primary:Download/a.pdf`, `raw:/storage/…`). */
    private fun documentPath(context: Context, uri: Uri, query: Boolean): String? {
        if (!runCatching { DocumentsContract.isDocumentUri(context, uri) }.getOrDefault(false)) return null
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return null
        return when (uri.authority) {
            "com.android.externalstorage.documents" -> volumePath(id)
            "com.android.providers.downloads.documents" -> id.removePrefix("raw:").takeIf { id.startsWith("raw:") }
                ?: id.removePrefix("msf:").toLongOrNull()?.takeIf { query }?.let { mediaData(context, MediaStore.Files.getContentUri("external"), it) }
            "com.android.providers.media.documents" -> id.substringAfter(':').toLongOrNull()?.takeIf { query }?.let { mediaData(context, MediaStore.Files.getContentUri("external"), it) }
            else -> null
        }
    }

    /** `primary:Download/a.pdf` → /storage/emulated/0/Download/a.pdf; `1A2B-3C4D:x.pdf` → /storage/1A2B-3C4D/x.pdf. */
    internal fun volumePath(id: String): String? {
        val volume = id.substringBefore(':', "").ifEmpty { return null }
        val rest = id.substringAfter(':')
        val root = if (volume == "primary") "/storage/emulated/0" else "/storage/$volume"
        return if (rest.isEmpty()) root else "$root/$rest"
    }

    /**
     * A file manager's provider that puts the path in its address, as Samsung My Files does
     * (`content://com.sec.android.app.myfiles.FileProvider/device_storage/0/Download/a.pdf`).
     */
    internal fun fileProviderPath(segments: List<String>): String? {
        val at = segments.indexOf("device_storage")
        if (at >= 0 && segments.size > at + 2 && segments[at + 1].all(Char::isDigit)) return "/storage/emulated/" + segments.drop(at + 1).joinToString("/")
        val storage = segments.indexOf("storage")
        if (storage >= 0 && segments.size > storage + 2) return "/" + segments.drop(storage).joinToString("/")
        return null
    }

    private fun dataColumn(context: Context, uri: Uri): String? = runCatching {
        @Suppress("DEPRECATION")
        context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    }.getOrNull()

    private fun mediaData(context: Context, collection: Uri, id: Long): String? =
        dataColumn(context, android.content.ContentUris.withAppendedId(collection, id))
}
