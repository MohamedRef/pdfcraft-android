package io.github.artcraftandroid.pdfcraft.engine

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One saved version of a document in the backup folder. */
data class BackupVersion(val name: String, val time: Long, val pdf: String, val xfdf: String?, val size: Long)

/**
 * How backups are named and pruned: `<name> 2026-11-11 17.40.12.pdf` (and `.xfdf` beside it, the
 * comments alone) in a folder named after the document. Pure naming rules; no I/O.
 */
object BackupNames {
    private const val STAMP = "yyyy-MM-dd HH.mm.ss"
    /** Where the library, flashcards and notebooks JSON goes, beside the documents' folders. */
    const val APP_DATA_FOLDER = "PdfCraft app data"
    private val pattern = Regex("""^(.*) (\d{4}-\d{2}-\d{2} \d{2}\.\d{2}\.\d{2})\.(pdf|xfdf)$""")

    /** The folder and file base for [displayName]: no extension, nothing a file system refuses. */
    fun base(displayName: String): String =
        displayName.replace(Regex("(?i)\\.pdf$"), "").replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimStart('.').take(80).ifEmpty { "Document" }.let { if (it == APP_DATA_FOLDER) "${it}_" else it }

    fun stamp(time: Long): String = SimpleDateFormat(STAMP, Locale.US).format(Date(time))

    fun pdfName(base: String, time: Long) = "$base ${stamp(time)}.pdf"
    fun xfdfName(base: String, time: Long) = "$base ${stamp(time)}.xfdf"

    /** The time a backup file name carries, if it is one of [base]'s. */
    fun timeOf(base: String, fileName: String): Long? {
        val m = pattern.matchEntire(fileName) ?: return null
        if (m.groupValues[1] != base) return null
        return runCatching { SimpleDateFormat(STAMP, Locale.US).parse(m.groupValues[2])?.time }.getOrNull()
    }

    /** [base]'s versions among [files] (name to size), newest first. */
    fun versions(base: String, files: Map<String, Long>): List<BackupVersion> {
        val byTime = HashMap<Long, Pair<String?, String?>>()
        for (name in files.keys) {
            val t = timeOf(base, name) ?: continue
            val (pdf, xfdf) = byTime[t] ?: (null to null)
            byTime[t] = if (name.endsWith(".pdf")) name to xfdf else pdf to name
        }
        return byTime.mapNotNull { (t, p) -> p.first?.let { BackupVersion(it, t, it, p.second, files[it] ?: 0) } }.sortedByDescending { it.time }
    }

    /** The files to delete so only the [keep] newest versions of [base] stay (their XFDF with them). */
    fun toPrune(base: String, files: Map<String, Long>, keep: Int): List<String> {
        val all = versions(base, files)
        val kept = all.take(keep.coerceAtLeast(1)).map { it.time }.toSet()
        // orphaned XFDF files (their PDF gone) go too
        return files.keys.filter { n -> timeOf(base, n)?.let { it !in kept } == true }.sorted()
    }
}

/** The backup folder: a tree the user picked through the system's folder picker. */
class BackupFolder(private val resolver: ContentResolver, private val tree: Uri) {
    private val rootId = DocumentsContract.getTreeDocumentId(tree)

    private fun children(parentId: String): List<Triple<String, String, Long>> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_SIZE)
        return resolver.query(uri, cols, null, null, null)?.use { c ->
            buildList { while (c.moveToNext()) add(Triple(c.getString(0), c.getString(1) ?: "", if (c.isNull(2)) 0L else c.getLong(2))) }
        } ?: emptyList()
    }

    private fun dir(name: String, create: Boolean): String? {
        children(rootId).firstOrNull { it.second == name }?.let { return it.first }
        if (!create) return null
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, rootId)
        val made = DocumentsContract.createDocument(resolver, parent, DocumentsContract.Document.MIME_TYPE_DIR, name) ?: return null
        return DocumentsContract.getDocumentId(made)
    }

    /** Write [file] as [name] into folder [folder] (made when missing). */
    fun write(folder: String, name: String, mime: String, file: File) {
        val dirId = dir(folder, create = true) ?: error("cannot create the backup folder")
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, dirId)
        // a file of that name from an earlier attempt is replaced
        children(dirId).filter { it.second == name }.forEach { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) }
        val target = DocumentsContract.createDocument(resolver, parent, mime, name) ?: error("cannot create $name")
        try {
            resolver.openOutputStream(target, "wt")?.use { out -> file.inputStream().use { it.copyTo(out) } } ?: error("cannot write $name")
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(resolver, target) }
            throw e
        }
    }

    /** The files in [folder] (name to size). */
    fun list(folder: String): Map<String, Long> {
        val dirId = dir(folder, create = false) ?: return emptyMap()
        return children(dirId).associate { it.second to it.third }
    }

    fun delete(folder: String, names: Collection<String>) {
        val dirId = dir(folder, create = false) ?: return
        val wanted = names.toSet()
        children(dirId).filter { it.second in wanted }.forEach { runCatching { DocumentsContract.deleteDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, it.first)) } }
    }

    /** Copy [name] from [folder] into [out]. */
    fun read(folder: String, name: String, out: File) {
        val dirId = dir(folder, create = false) ?: error("no backup folder")
        val id = children(dirId).firstOrNull { it.second == name }?.first ?: error("no backup $name")
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, id))?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: error("cannot read $name")
    }
}
