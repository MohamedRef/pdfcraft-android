package io.github.artcraftandroid.pdfcraft.engine

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import androidx.core.content.FileProvider
import io.github.artcraftandroid.pdfcraft.R
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Where the app keeps the files it works on. The engine reads and writes real paths, so a
 * document opened from another app (a `content://` URI) is copied into the app's files first, and
 * saving writes the result back to that URI when the app may write there.
 */
class DocumentFiles(private val context: Context) {
    private val resolver: ContentResolver get() = context.contentResolver

    /** Working copies of opened documents, one folder per source. */
    private val workDir = File(context.filesDir, "Documents").apply { mkdirs() }

    /** Files handed to other apps (share, print), through the FileProvider. */
    private val shareDir = File(context.cacheDir, "share").apply { mkdirs() }

    /** Files the app creates itself (combine, create, scans) before the user saves them somewhere. */
    val createdDir = File(context.filesDir, "Created").apply { mkdirs() }

    /** The working copy of [uri]: copied in (again) unless it is a file the app already owns. */
    fun workingCopy(uri: Uri): File {
        if (uri.scheme == ContentResolver.SCHEME_FILE) {
            val f = File(requireNotNull(uri.path) { context.getString(R.string.files_error_no_path) })
            if (io.github.artcraftandroid.pdfcraft.pathWithin(f.canonicalPath, context.filesDir.canonicalPath)) return f
        }
        val name = OpenTiming.measure("provider_metadata") { displayName(uri) }
        val dir = File(workDir, sha(uri.toString())).apply { mkdirs() }
        // A failed provider write may have truncated the original; prefer the recovery copy.
        if (PendingWorkingCopy.isPending(dir)) {
            return existingCopy(uri) ?: throw IOException(
                "A PDF write-back failed, but its local recovery copy is missing. Refusing to replace it from the possibly damaged original."
            )
        }
        val target = File(dir, name)
        // An unchanged source reuses the copy made last time instead of copying it in again (#210).
        val source = OpenTiming.measure("provider_stamp") { sourceStamp(uri) }
        val reused = OpenTiming.measure("working_copy_reuse_check") {
            canReuseCopy(CopyStamp.read(dir), source, target, PendingWorkingCopy.isPending(dir))
        }
        if (reused) {
            OpenTiming.measure("working_copy_reused") { dir.setLastModified(System.currentTimeMillis()) }
            return target
        }
        // the stamp describes the copy being replaced: never let it outlive a failed copy
        CopyStamp.clear(dir)
        val tmp = File(dir, ".$name.part")
        try {
            val copied = try {
                OpenTiming.measure("provider_descriptor_copy") {
                    resolver.openAssetFileDescriptor(uri, "r")?.use { asset ->
                        val size = asset.parcelFileDescriptor.statSize
                        val length = if (asset.declaredLength >= 0) asset.declaredLength else size - asset.startOffset
                        asset.createInputStream().use { input ->
                            tmp.outputStream().use { output ->
                                if (size > 0 && length > 0 && asset.startOffset >= 0 && asset.startOffset <= size && length <= size - asset.startOffset) {
                                    PdfSnapshotCopy.copyRange(input.channel, output.channel, asset.startOffset, length)
                                } else {
                                    // Pipes, remote and unknown-size assets retain bounded streaming.
                                    input.copyTo(output, PdfSnapshotCopy.BUFFER_BYTES)
                                }
                            }
                        }
                        true
                    } ?: false
                }
            } catch (e: Exception) {
                // A vendor/provider can claim a seekable asset but expose a pipe or a
                // channel that rejects positional reads. Only recoverable descriptor errors
                // fall back to a fresh, truncating provider stream. Security denial escapes.
                if (PdfSnapshotCopy.retryWithProviderStream(e)) false else throw e
            }
            if (!copied) OpenTiming.measure("provider_stream_fallback") {
                resolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { context.getString(R.string.common_file_unreadable) }
                    // Starting afresh truncates a partial descriptor transfer before retrying.
                    tmp.outputStream().use { input.copyTo(it, PdfSnapshotCopy.BUFFER_BYTES) }
                }
            }
            publishWorkingCopy(tmp, target)
        } finally {
            tmp.delete()
        }
        // the copy keeps the source's date, which the More sheet shows as when it was modified
        lastModified(uri)?.let { target.setLastModified(it) }
        // Remember what the source looked like, so the next open can reuse this copy. Only when
        // the provider reports both values and the copy has exactly the size it reported.
        if (source != null && source.usable && target.length() == source.size) {
            runCatching { CopyStamp.write(dir, CopyStamp(source, target.length(), target.lastModified())) }
        }
        // a copy under an older name (the source was renamed) would otherwise be found instead
        dir.listFiles()?.forEach { if (it.isFile && !it.name.startsWith(".") && it.name != target.name) it.delete() }
        dir.setLastModified(System.currentTimeMillis())
        return target
    }

    /** The source's size and modification date, when its provider reports both. */
    private fun sourceStamp(uri: Uri): SourceStamp? {
        val size = size(uri) ?: return null
        val modified = lastModified(uri) ?: return null
        return SourceStamp(size, modified).takeIf { it.usable }
    }

    /** Whether [dir] holds a copy the next open may reuse (see [canReuseCopy]). */
    private fun reusable(dir: File): Boolean = CopyStamp.read(dir) != null && !PendingWorkingCopy.isPending(dir)

    /**
     * Keep reusable copies within [reuseBudget], most recently used first; [open]'s stays.
     * Copies that are not reusable are left to the rules that already govern them.
     */
    private fun enforceReuseBudget(open: String?) {
        // a copy written in the last few minutes may belong to a document being opened right now
        val settled = System.currentTimeMillis() - 10 * 60 * 1000L
        val dirs = workDir.listFiles()?.filter { it.isDirectory && reusable(it) && it.lastModified() < settled }.orEmpty()
        if (dirs.isEmpty()) return
        val sizes = dirs.map { d -> KeptCopy(d.name, d.listFiles()?.sumOf { it.length() } ?: 0L, d.lastModified()) }
        val keep = copiesToKeep(sizes, reuseBudget(workDir.usableSpace), open?.let { sha(it) })
        for (d in dirs) if (d.name !in keep) d.deleteRecursively()
    }

    /** When [uri] was last modified (ms), when its provider says: a document's or a media file's date. */
    fun lastModified(uri: Uri): Long? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let { File(it).lastModified() }?.takeIf { it > 0 }
        fun column(name: String): Long? = runCatching {
            resolver.query(uri, arrayOf(name), null, null, null)?.use { c -> if (c.moveToFirst()) c.namedLong(name) else null }
        }.getOrNull()
        return column(DocumentsContract.Document.COLUMN_LAST_MODIFIED)?.takeIf { it > 0 }
            ?: column(MediaStore.MediaColumns.DATE_MODIFIED)?.takeIf { it > 0 }?.times(1000)
    }

    /**
     * Throw away the working copy of [uri], its document closed, when the app keeps access to
     * [uri]: it is copied in again the next time it opens. A file another app lent for one visit
     * keeps its copy, which is what Recent opens once Android takes that access back.
     */
    fun dropCopyIfReachable(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        val dir = File(workDir, sha(uri.toString()))
        if (PendingWorkingCopy.isPending(dir)) return
        if (resolver.persistedUriPermissions.none { it.uri == uri && it.isReadPermission }) return
        // A reusable copy stays for the next open (#210), within the space budget.
        // (pruneCopies keeps them within the space budget)
        if (reusable(dir)) return
        dir.deleteRecursively()
    }

    /**
     * Remove the working copies no longer needed: those of documents that left [recent], and of
     * those in it whose original the app can read again. The [open] document's copy stays, and so
     * does one written in the last few minutes (a document being opened right now).
     */
    fun pruneCopies(recent: Collection<String>, open: String?) {
        val readable = resolver.persistedUriPermissions.filter { it.isReadPermission }.mapTo(HashSet()) { it.uri.toString() }
        val wanted = recent.filter { it !in readable }.mapTo(HashSet()) { sha(it) }
        // reusable copies of documents still in Recent may stay, within the budget below (#210)
        val inRecent = recent.mapTo(HashSet()) { sha(it) }
        open?.let { wanted += sha(it) }
        val settled = System.currentTimeMillis() - 10 * 60 * 1000L
        workDir.listFiles()?.forEach { dir ->
            if (PendingWorkingCopy.isPending(dir) || dir.name in wanted || dir.lastModified() >= settled) return@forEach
            if (dir.name in inRecent && reusable(dir)) return@forEach
            dir.deleteRecursively()
        }
        enforceReuseBudget(open)
    }

    /** Explicit Discard from the unsaved-changes prompt. Never call from automatic cleanup. */
    /**
     * Discard [uri]'s working copy, unless a failed write-back may have damaged the original: then
     * the edited copy may be the only intact one, so it is kept (and offered again on the next
     * open). Returns whether it was discarded.
     */
    fun discardWorkingCopy(uri: Uri): Boolean {
        val dir = File(workDir, sha(uri.toString()))
        if (PendingWorkingCopy.isPending(dir) && !originalLooksIntact(uri)) return false
        PendingWorkingCopy.discard(dir)
        return true
    }

    /** Whether [uri] still starts like a PDF and, when its end can be read, ends like one. */
    private fun originalLooksIntact(uri: Uri): Boolean = runCatching {
        resolver.openAssetFileDescriptor(uri, "r")?.use { afd ->
            val length = afd.length
            java.io.FileInputStream(afd.fileDescriptor).use { input ->
                input.channel.position(afd.startOffset)
                // InputStream.readNBytes needs API 33; the app supports API 29.
                fun readUpTo(count: Int): ByteArray {
                    val buf = ByteArray(count)
                    var n = 0
                    while (n < count) {
                        val r = input.read(buf, n, count - n)
                        if (r < 0) break
                        n += r
                    }
                    return buf.copyOf(n)
                }
                val head = readUpTo(1024)
                val tail = if (length > 0 && input.channel.size() > 0) {
                    input.channel.position(afd.startOffset + (length - 2048).coerceAtLeast(0))
                    readUpTo(minOf(2048L, length).toInt())
                } else null
                pdfLooksIntact(head, tail)
            }
        } ?: false
    }.getOrDefault(false)

    /** The working copy made earlier for [uri], if there is one (for thumbnails). */
    fun existingCopy(uri: Uri): File? =
        File(workDir, sha(uri.toString())).listFiles()?.firstOrNull { it.isFile && !it.name.startsWith(".") }

    /** The file name another app gives [uri], ending in `.pdf`. */
    fun displayName(uri: Uri): String {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.namedString(OpenableColumns.DISPLAY_NAME) else null
            }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Document.pdf"
        return safeName(name)
    }

    /** The file name another app gives [uri], as it is (any extension). */
    fun displayNameRaw(uri: Uri): String {
        val name = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c -> if (c.moveToFirst()) c.namedString(OpenableColumns.DISPLAY_NAME) else null }
        }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "File"
        return name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().ifEmpty { "File" }
    }

    /** The size of [uri] in bytes, when its provider says. */
    fun size(uri: Uri): Long? {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let { File(it).length() }
        return runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c -> if (c.moveToFirst()) c.namedLong(OpenableColumns.SIZE) else null }
        }.getOrNull()
    }

    /** Whether the app may write to [uri] (a document the user picked with write access). */
    fun canWrite(uri: Uri): Boolean {
        if (uri.scheme == ContentResolver.SCHEME_FILE) return uri.path?.let { File(it).canWrite() } == true
        // a provider that says its document can't be written refuses whatever access the picker
        // granted (Android's media documents, which the picker's Recent shows: "Media is read-only")
        if (DocumentsContract.isDocumentUri(context, uri) && documentFlags(uri)?.let { it and DocumentsContract.Document.FLAG_SUPPORTS_WRITE == 0 } == true) return false
        val granted = resolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        return granted || runCatching { resolver.openFileDescriptor(uri, "wa")?.close(); true }.getOrDefault(false)
    }

    /** A document's flags (DocumentsContract.Document.FLAG_*), when its provider says. */
    private fun documentFlags(uri: Uri): Int? = runCatching {
        resolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_FLAGS), null, null, null)?.use { c -> if (c.moveToFirst()) c.namedInt(DocumentsContract.Document.COLUMN_FLAGS) else null }
    }.getOrNull()

    /** Whether the app's local working PDF still has an uncommitted provider write. */
    fun hasPendingWriteBack(uri: Uri): Boolean =
        PendingWorkingCopy.isPending(File(workDir, sha(uri.toString())))

    /** Persist a marker before asking a content provider to truncate its source. */
    fun markPendingWriteBack(uri: Uri) {
        PendingWorkingCopy.mark(File(workDir, sha(uri.toString())))
    }

    /** Clear only after a successful provider write or explicit completed Save a copy. */
    fun clearPendingWriteBack(uri: Uri) {
        PendingWorkingCopy.clear(File(workDir, sha(uri.toString())))
    }

    /** Write [file] over [uri] (truncating it). The caller keeps a durable recovery copy. */
    fun writeBack(file: File, uri: Uri) {
        val out = resolver.openOutputStream(uri, "wt") ?: error(context.getString(R.string.files_error_write))
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** Keep access to [uri] across restarts, for Recent files (when the provider allows it). */
    fun keepAccess(uri: Uri) {
        if (uri.scheme != ContentResolver.SCHEME_CONTENT) return
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
            .recoverCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
    }

    /**
     * Give back the lasting access taken by [keepAccess] for documents that left Recent, and keep
     * the rest within [MAX_DOCUMENT_GRANTS], so document grants never push the user's folder
     * grants out of Android's per-app limit (#108). Folder (tree) grants are never released here.
     */
    fun releaseUnusedAccess(recent: Collection<String>, open: String?) {
        val grants = runCatching { resolver.persistedUriPermissions }.getOrDefault(emptyList())
        if (grants.isEmpty()) return
        val release = documentGrantsToRelease(
            grants.map { UriGrant(it.uri.toString(), runCatching { DocumentsContract.isTreeUri(it.uri) }.getOrDefault(false), it.persistedTime) },
            recent = recent.toSet(),
            open = open,
            cap = MAX_DOCUMENT_GRANTS,
        )
        if (release.isEmpty()) return
        for (g in grants) {
            if (g.uri.toString() !in release) continue
            val flags = (if (g.isReadPermission) Intent.FLAG_GRANT_READ_URI_PERMISSION else 0) or
                (if (g.isWritePermission) Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0)
            if (flags != 0) runCatching { resolver.releasePersistableUriPermission(g.uri, flags) }
        }
    }

    /** A copy of [file] under [name] that other apps may read, and its URI. */
    fun shareable(file: File, name: String = file.name): Uri = shareableAll(listOf(file to safeName(name))).first()

    /**
     * Copies of [files] that other apps may read (a document, pictures of pages), each under its
     * own name, and their URIs. Copies handed out more than [CacheCleanup.SHARE_KEEP_MS] ago are
     * cleared first; newer ones stay, since the app that received them may still be reading (#105).
     * When the copies can't be made (a full phone has no room for them), the error says so in words.
     */
    fun shareableAll(files: List<Pair<File, String>>): List<Uri> {
        CacheCleanup.removeOlderThan(shareDir, System.currentTimeMillis() - CacheCleanup.SHARE_KEEP_MS, directoriesOnly = true)
        val dir = File(shareDir, System.nanoTime().toString())
        try {
            if (!dir.mkdirs()) throw IOException("can't make $dir")
            return files.map { (file, name) ->
                val copy = File(dir, name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").ifBlank { file.name })
                file.copyTo(copy, overwrite = true)
                FileProvider.getUriForFile(context, "${context.packageName}.files", copy)
            }
        } catch (e: IOException) {
            dir.deleteRecursively()
            val full = shareDir.usableSpace < files.sumOf { it.first.length() } + 1024 * 1024
            throw IOException(context.getString(if (full) R.string.files_error_share_no_space else R.string.files_error_share), e)
        }
    }

    /** A new file in the app's own space for a document it makes, named [name] (made unique). */
    fun newCreated(name: String): File {
        val base = safeName(name).removeSuffix(".pdf")
        var f = File(createdDir, "$base.pdf")
        var n = 1
        while (f.exists()) f = File(createdDir, "$base (${n++}).pdf")
        return f
    }

    /**
     * Save [file] into the phone's Downloads as [name] (made unique by the system if taken) and
     * return its URI; no storage permission is needed for the app's own downloads.
     */
    fun saveToDownloads(file: File, name: String, mime: String = "application/pdf"): Uri {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
            put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val uri = resolver.insert(collection, values) ?: error(context.getString(R.string.files_error_save_downloads))
        try {
            resolver.openOutputStream(uri).use { out ->
                requireNotNull(out) { context.getString(R.string.files_error_save_downloads) }
                file.inputStream().use { it.copyTo(out) }
            }
            resolver.update(uri, android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return uri
    }

    /** A new app-owned pending row, with cooperative cancellation and no replacement path. */
    internal fun saveBatesCopy(file: File, name: String, checkpoint: () -> Unit): Uri = publishNewBatchCopy(
        reserve = {
            val values = android.content.ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
                put(MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_DOWNLOADS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException(context.getString(R.string.files_error_save_downloads))
        },
        write = { uri ->
            resolver.openOutputStream(uri, "w").use { out ->
                if (out == null) throw IOException(context.getString(R.string.files_error_save_downloads))
                file.inputStream().use { input -> SafBoundedCopy.copy(input, out, MAX_BATES_BYTES, "PDF", checkpoint) }
            }
        },
        commit = { uri ->
            val values = android.content.ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
            if (resolver.update(uri, values, null, null) != 1) throw IOException(context.getString(R.string.files_error_save_downloads))
        },
        rollback = { uri ->
            if (resolver.delete(uri, null, null) != 1) throw IOException("Could not remove the incomplete new download.")
        },
        checkpoint = checkpoint,
    )

    /** Save an image into Pictures/PdfCraft (exported pages). */
    fun saveToPictures(file: File, name: String, mime: String): Uri {
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/PdfCraft")
        }
        val uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error(context.getString(R.string.files_error_save_image))
        resolver.openOutputStream(uri).use { out ->
            requireNotNull(out) { context.getString(R.string.files_error_save_image) }
            file.inputStream().use { it.copyTo(out) }
        }
        return uri
    }

    /** Where [uri] is, as a person would say it: a folder path, or the app that provided it. */
    fun displayLocation(uri: Uri): String = when (uri.scheme) {
        ContentResolver.SCHEME_FILE -> uri.path?.substringBeforeLast('/')?.replace("/storage/emulated/0", context.getString(R.string.files_location_internal_storage)) ?: context.getString(R.string.files_location_this_device)
        else -> {
            val auth = uri.authority.orEmpty()
            when {
                auth.contains("downloads") -> context.getString(R.string.files_location_downloads)
                auth.contains("externalstorage") -> context.getString(R.string.files_location_internal_storage) + (Uri.decode(uri.lastPathSegment ?: "").substringAfter(':', "").substringBeforeLast('/', "").let { if (it.isEmpty()) "" else "/$it" })
                auth.contains("media") -> context.getString(R.string.files_location_media)
                else -> context.packageManager.resolveContentProvider(auth, 0)?.loadLabel(context.packageManager)?.toString() ?: context.getString(R.string.files_location_another_app)
            }
        }
    }

    companion object {
        /**
         * At most this many document grants are kept. Recent holds up to [RecentFiles.MAX]; the
         * oldest Android releases allow 128 grants per app, so this leaves room for folders.
         */
        const val MAX_DOCUMENT_GRANTS = 96

        /** A file name without path separators or control characters, ending in `.pdf`. */
        fun safeName(name: String): String {
            val clean = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.').ifEmpty { "Document" }
            return if (clean.lowercase().endsWith(".pdf")) clean else "$clean.pdf"
        }

        private fun sha(s: String): String =
            MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString("") { "%02x".format(it) }
    }
}
