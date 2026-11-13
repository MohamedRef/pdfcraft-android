package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.BackupFolder
import io.github.artcraftandroid.pdfcraft.engine.BackupNames
import io.github.artcraftandroid.pdfcraft.engine.BackupVersion
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.LocalFiles
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Backups (#145) and version history: copies of each saved document and its comments in a folder
 * the user picked, and restoring from them. Split out of [AppViewModel] (#132); [vm] gives the
 * app-wide parts it uses (messages, the busy dialog, opening a file).
 */
class BackupController internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    /** The folder backups go to (a tree the user picked), or null when backups are off. */
    var backupTree by mutableStateOf(vm.settings.appPrefs.getString("backupTree", null)?.let(Uri::parse))
        private set
    /** How many versions of each document are kept. */
    var backupKeep by mutableStateOf(vm.settings.appPrefs.getInt("backupKeep", 10))
        private set
    /** When the last backup was written, and whether the last attempt failed. */
    var backupLast by mutableStateOf(vm.settings.appPrefs.getLong("backupLast", 0L))
        private set
    var backupFailed by mutableStateOf(false)
        private set
    val backupFolderRequests = Channel<Unit>(Channel.CONFLATED)
    private val backupMutex = Mutex()
    /** Snapshots saved but not yet copied out, by document folder name: the newest wins. */
    private val pendingBackups = java.util.concurrent.ConcurrentHashMap<String, PendingBackup>()
    private class PendingBackup(val base: String, val time: Long, val pdf: File, val xfdf: File?)

    fun chooseBackupFolder() { backupFolderRequests.trySend(Unit) }

    fun onBackupFolderPicked(tree: Uri?) {
        tree ?: return
        val resolver = app.contentResolver
        val ok = runCatching { resolver.takePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }.isSuccess
        if (!ok) { vm.tell(text(R.string.backup_folder_not_writable)); return }
        backupTree?.takeIf { it != tree }?.let { old -> releaseBackupGrant(old) }
        backupTree = tree
        vm.settings.appPrefs.edit().putString("backupTree", tree.toString()).apply()
        backupFailed = false
        backupAppData()
    }

    fun turnOffBackups() {
        backupTree?.let(::releaseBackupGrant)
        backupTree = null
        backupFailed = false
        vm.settings.appPrefs.edit().remove("backupTree").apply()
        pendingBackups.values.forEach { it.pdf.delete(); it.xfdf?.delete() }
        pendingBackups.clear()
    }

    private fun releaseBackupGrant(tree: Uri) {
        // the folder stays listed under On this device when the user also added it there
        val resolver = app.contentResolver
        runCatching { resolver.releasePersistableUriPermission(tree, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION) }
    }

    fun updateBackupKeep(n: Int) {
        backupKeep = n.coerceIn(1, 100)
        vm.settings.appPrefs.edit().putInt("backupKeep", backupKeep).apply()
    }

    fun backupFolderName(): String? = backupTree?.let { LocalFiles.folderName(app, it) }

    private fun backupFolder(): BackupFolder? =
        backupTree?.let { BackupFolder(app.contentResolver, it) }

    private fun backupDir(): File = File(app.cacheDir, "backup").apply { mkdirs() }

    /**
     * After a save wrote [doc]'s changes: snapshot the saved file and its comments (XFDF) now,
     * while nothing else writes them, then copy them out in the background. Never blocks the save
     * or the app going to the background (#97); failures are told and retried at the next save.
     */
    internal suspend fun snapshotForBackup(eng: Engine, doc: OpenDocument) {
        backupTree ?: return
        if (doc.info.preparing) return
        val base = BackupNames.base(doc.info.name)
        val time = System.currentTimeMillis()
        val dir = backupDir()
        val pdf = File(dir, "${time}-${base.hashCode()}.pdf")
        val xfdf = File(dir, "${time}-${base.hashCode()}.xfdf")
        val started = System.nanoTime()
        try {
            val saved = vm.savedFile(doc, "backup")
            withContext(Dispatchers.IO) { saved.copyTo(pdf, overwrite = true) }
        } catch (e: Exception) {
            Diag.failure(DiagArea.Study, "backup_snapshot", e)
            pdf.delete()
            backupFailed = true
            return
        }
        // the comments alone; a document whose comments cannot be exported still gets its PDF copy
        val hasXfdf = runCatching { eng.tool("doc_export_data", mapOf("doc" to doc.id, "path" to xfdf.absolutePath, "what" to "comments")) }.isSuccess && xfdf.isFile
        if (!hasXfdf) xfdf.delete()
        Diag.event(
            DiagArea.Study, "backup_snapshot", "doc" to doc.diagNumber, "ms" to (System.nanoTime() - started) / 1_000_000,
            "bytes" to pdf.length(), "comments" to hasXfdf, "comment_bytes" to (if (hasXfdf) xfdf.length() else 0L), "waiting" to pendingBackups.size,
        )
        pendingBackups.put(base, PendingBackup(base, time, pdf, xfdf.takeIf { hasXfdf }))?.let { old -> old.pdf.delete(); old.xfdf?.delete() }
        flushBackups()
    }

    /** Copy every waiting snapshot to the backup folder and prune old versions. */
    fun flushBackups() {
        vm.viewModelScope.launch(Dispatchers.IO) {
            backupMutex.withLock {
                val folder = backupFolder() ?: return@withLock
                var failed = false
                for (base in pendingBackups.keys.toList()) {
                    val p = pendingBackups[base] ?: continue
                    val started = System.nanoTime()
                    var pruned = 0
                    val result = runCatching {
                        folder.write(base, BackupNames.pdfName(base, p.time), "application/pdf", p.pdf)
                        p.xfdf?.let { folder.write(base, BackupNames.xfdfName(base, p.time), "application/vnd.adobe.xfdf", it) }
                        val prune = BackupNames.toPrune(base, folder.list(base), backupKeep)
                        if (prune.isNotEmpty()) folder.delete(base, prune)
                        pruned = prune.size
                    }
                    val ok = result.isSuccess
                    result.exceptionOrNull()?.let { Diag.failure(DiagArea.Study, "backup_write", it) }
                    Diag.event(
                        DiagArea.Study, "backup_write", "ok" to ok, "ms" to (System.nanoTime() - started) / 1_000_000,
                        "bytes" to p.pdf.length(), "comments" to (p.xfdf != null), "pruned" to pruned, "keep" to backupKeep,
                        "age_ms" to System.currentTimeMillis() - p.time,
                    )
                    if (ok) {
                        // a newer snapshot may have arrived meanwhile; only this one is done
                        if (pendingBackups.remove(base, p)) { p.pdf.delete(); p.xfdf?.delete() }
                    } else failed = true
                }
                if (!failed) runCatching { writeAppData(folder) }.onFailure { Diag.failure(DiagArea.Study, "backup_app_data", it) }
                withContext(Dispatchers.Main) {
                    if (failed && !backupFailed) vm.tell(text(R.string.backup_failed), text(R.string.backup_retry)) { flushBackups() }
                    backupFailed = failed
                    if (!failed) {
                        backupLast = System.currentTimeMillis()
                        vm.settings.appPrefs.edit().putLong("backupLast", backupLast).apply()
                    }
                }
            }
        }
    }

    private fun backupAppData() {
        vm.viewModelScope.launch(Dispatchers.IO) { backupMutex.withLock { backupFolder()?.let { runCatching { writeAppData(it) } } } }
    }

    /** Library, flashcards, notebooks and study statistics as one JSON file beside the backups. */
    private fun writeAppData(folder: BackupFolder) {
        val filesDir = app.filesDir
        val out = org.json.JSONObject().put("app", "PdfCraft").put("version", 1).put("time", System.currentTimeMillis())
        for ((key, name) in listOf("library" to "library.json", "flashcards" to "flashcards.json", "notebooks" to "notebooks.json", "study" to "study.json")) {
            val f = File(filesDir, name)
            if (f.isFile) runCatching { org.json.JSONObject(f.readText()) }.getOrNull()?.let { out.put(key, it) }
        }
        val tmp = File(backupDir(), "app-data.json")
        tmp.writeText(out.toString(1))
        try {
            folder.write(BackupNames.APP_DATA_FOLDER, "app-data.json", "application/json", tmp)
        } finally {
            tmp.delete()
        }
    }

    // ---- Version history ------------------------------------------------------------------

    var backupVersions by mutableStateOf<List<BackupVersion>?>(null)
        private set

    fun loadBackupVersions(doc: OpenDocument) {
        backupVersions = null
        vm.viewModelScope.launch {
            val base = BackupNames.base(doc.info.name)
            val started = System.nanoTime()
            val found = withContext(Dispatchers.IO) {
                runCatching { backupFolder()?.let { BackupNames.versions(base, it.list(base)) } }
                    .onFailure { Diag.failure(DiagArea.Study, "backup_versions", it) }.getOrNull().orEmpty()
            }
            Diag.event(DiagArea.Study, "backup_versions", "doc" to doc.diagNumber, "versions" to found.size, "with_comments" to found.count { it.xfdf != null }, "ms" to (System.nanoTime() - started) / 1_000_000)
            backupVersions = found
        }
    }

    /** Open version [v] as a new copy in Downloads; the document and the backup stay as they are. */
    fun restoreBackupCopy(doc: OpenDocument, v: BackupVersion) {
        val folder = backupFolder() ?: return
        val base = BackupNames.base(doc.info.name)
        vm.viewModelScope.launch {
            vm.tools.busy = text(R.string.backup_restoring)
            try {
                val tmp = File(backupDir(), "restore-${System.nanoTime()}.pdf")
                val uri = withContext(Dispatchers.IO) {
                    folder.read(base, v.pdf, tmp)
                    try { vm.files.saveToDownloads(tmp, DocumentFiles.safeName(v.pdf)) } finally { tmp.delete() }
                }
                Diag.event(DiagArea.Study, "backup_restore", "what" to "copy", "ok" to true, "age_ms" to System.currentTimeMillis() - v.time, "bytes" to v.size)
                vm.tools.busy = null
                vm.open(uri)
            } catch (e: Exception) {
                Diag.failure(DiagArea.Study, "backup_restore_copy", e)
                vm.tools.busy = null
                vm.tell(e.userMessage)
            }
        }
    }

    /** Add version [v]'s comments (its XFDF) to the open document; one undo takes them away. */
    fun restoreBackupComments(doc: OpenDocument, v: BackupVersion) {
        val name = v.xfdf ?: return
        val folder = backupFolder() ?: return
        val base = BackupNames.base(doc.info.name)
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            val tmp = File(backupDir(), "restore-${System.nanoTime()}.xfdf")
            try {
                withContext(Dispatchers.IO) { folder.read(base, name, tmp) }
                eng.tool("doc_import_data", mapOf("doc" to doc.id, "path" to tmp.absolutePath))
                vm.refresh(doc)
                Diag.event(DiagArea.Study, "backup_restore", "what" to "comments", "doc" to doc.diagNumber, "ok" to true, "age_ms" to System.currentTimeMillis() - v.time)
                vm.tell(text(R.string.backup_comments_restored))
            } catch (e: Exception) {
                Diag.failure(DiagArea.Study, "backup_restore_comments", e)
                vm.tell(e.userMessage)
            } finally {
                withContext(Dispatchers.IO) { tmp.delete() }
            }
        }
    }
}
