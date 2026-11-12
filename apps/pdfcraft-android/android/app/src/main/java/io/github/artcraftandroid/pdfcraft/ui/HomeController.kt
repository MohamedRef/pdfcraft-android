package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.artcraftandroid.pdfcraft.ui.viewer.recognizeText
import io.github.artcraftandroid.pdfcraft.ui.viewer.startCompare
import io.github.artcraftandroid.pdfcraft.ui.home.FileTool
import android.content.Intent
import io.github.artcraftandroid.pdfcraft.ui.viewer.ReadAloud
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.RecentFile

class HomeController internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    // ---- Home, Files, Search -----------------------------------------------------------------

    var homeTab by mutableStateOf(io.github.artcraftandroid.pdfcraft.ui.home.HomeTab.Home)
    var settingsOpen by mutableStateOf(false)

    /** The Settings page to open on (root, about, prefs, help). */
    var settingsStartPage = "root"
        internal set

    /** Open Settings at [page]: Home's update card opens About (#193). */
    fun openSettings(page: String = "root", show: Boolean = true) {
        settingsStartPage = page
        if (show) settingsOpen = true
    }

    /** The file whose "..." sheet is open, and whether it was opened from Recent. */
    var fileSheet by mutableStateOf<Pair<io.github.artcraftandroid.pdfcraft.ui.home.FileRow, Boolean>?>(null)
    var showLicences by mutableStateOf(false)
    var filesShowLocal by mutableStateOf(false)

    /** Lists as a grid instead of rows (remembered). */
    private var gridViewState by mutableStateOf(vm.settings.appPrefs.getBoolean("gridView", false))
    var gridView: Boolean
        get() = gridViewState
        set(v) {
            gridViewState = v
            vm.settings.appPrefs.edit().putBoolean("gridView", v).apply()
        }

    /** Starred: 0 by date, 1 by name. */
    private var starSortState by mutableStateOf(vm.settings.appPrefs.getInt("starSort", 0))
    var starSort: Int
        get() = starSortState
        set(v) {
            starSortState = v
            vm.settings.appPrefs.edit().putInt("starSort", v).apply()
        }

    /** Bumped when a preference shown on screen changes outside Compose state. */
    var prefsChanged by mutableStateOf(0)

    val thumbnails = io.github.artcraftandroid.pdfcraft.engine.Thumbnails(app)

    var localFiles by mutableStateOf<List<io.github.artcraftandroid.pdfcraft.engine.LocalPdf>>(emptyList())
        internal set
    var scanning by mutableStateOf(false)
        internal set
    var hasFileAccess by mutableStateOf(io.github.artcraftandroid.pdfcraft.engine.LocalFiles.hasAccess(app))
        internal set
    private var lastScan = 0L

    /** The app's version, as the About page shows it. */
    val appVersion: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Look for the PDFs on the phone (at most every half minute unless [force]d). */
    fun scanLocal(force: Boolean = false) {
        hasFileAccess = io.github.artcraftandroid.pdfcraft.engine.LocalFiles.hasAccess(app)
        val now = System.currentTimeMillis()
        if (!force && now - lastScan < 30_000 && localFiles.isNotEmpty()) return
        lastScan = now
        vm.viewModelScope.launch {
            scanning = true
            localFiles = withContext(Dispatchers.IO) { io.github.artcraftandroid.pdfcraft.engine.LocalFiles.scan(app) }
            scanning = false
        }
    }

    /** Adding a folder to On this device: the activity opens the system's folder picker. */
    val accessRequests = Channel<Unit>(Channel.CONFLATED)

    fun requestFileAccess() {
        accessRequests.trySend(Unit)
    }

    /** The folders On this device lists, with their names. */
    fun localFolders(): List<Pair<Uri, String>> {
        val app = app
        return io.github.artcraftandroid.pdfcraft.engine.LocalFiles.folders(app).map { it to io.github.artcraftandroid.pdfcraft.engine.LocalFiles.folderName(app, it) }
    }

    /** A folder chosen in the picker: list its PDFs from now on. */
    fun addLocalFolder(tree: Uri) {
        io.github.artcraftandroid.pdfcraft.engine.LocalFiles.addFolder(app, tree)
        hasFileAccess = io.github.artcraftandroid.pdfcraft.engine.LocalFiles.hasAccess(app)
        scanLocal(force = true)
    }

    /** Stop listing [tree]. */
    fun removeLocalFolder(tree: Uri) {
        io.github.artcraftandroid.pdfcraft.engine.LocalFiles.removeFolder(app, tree)
        hasFileAccess = io.github.artcraftandroid.pdfcraft.engine.LocalFiles.hasAccess(app)
        scanLocal(force = true)
    }

    /** Back from the access page (or the app resumed): look again. */
    fun onResumed() {
        val had = hasFileAccess
        hasFileAccess = io.github.artcraftandroid.pdfcraft.engine.LocalFiles.hasAccess(app)
        if (hasFileAccess && !had) scanLocal(force = true)
        vm.refreshRecents()
        vm.study.refreshStudy()
        vm.study.refreshLibrary()
        vm.study.indexLibrary()
        vm.study.refreshDue()
        vm.study.focusTimer = io.github.artcraftandroid.pdfcraft.FocusTimerAlarm.load(app)
    }

    /** What [uri]'s thumbnail is drawn from: the file itself, the app's working copy, or the document. */
    internal fun thumbPathFor(uri: String): String? {
        val u = Uri.parse(uri)
        return when (u.scheme) {
            "file" -> u.path
            else -> vm.files.existingCopy(u)?.absolutePath ?: uri
        }
    }

    /** Each recent file's thumbnail source and location, found with the list off the main thread (#94). */
    internal var recentPlaces by mutableStateOf<Map<String, Pair<String?, String>>>(emptyMap())

    fun recentRows(): List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow> = vm.recentList.map { r ->
        val (thumb, location) = recentPlaces[r.uri] ?: (null to text(R.string.files_location_this_device))
        io.github.artcraftandroid.pdfcraft.ui.home.FileRow(r.uri, r.name, thumb, location, r.opened, r.size, r.starred) { vm.open(Uri.parse(r.uri)) }
    }

    fun starredRows(): List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow> = recentRows().filter { it.starred }.let { l ->
        if (starSort == 1) l.sortedBy { it.name.lowercase() } else l.sortedByDescending { it.date }
    }

    fun localRows(): List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow> {
        // a file opened from another app is known by a different address than the scan's path,
        // so stars are matched by the file itself (name and size)
        val stars = vm.recentList.filter { it.starred }.flatMap { listOf(it.uri, sameFile(it.name, it.size)) }.toSet()
        return localFiles.map { f ->
            val key = f.uri.toString()
            io.github.artcraftandroid.pdfcraft.ui.home.FileRow(key, f.name, f.path ?: key, f.folder, f.modified, f.size, key in stars || sameFile(f.name, f.size) in stars) { vm.open(f.uri) }
        }
    }

    private fun sameFile(name: String, size: Long) = "$name|$size"

    /** Files whose names contain [query]: the phone's and Recent's, each file once. */
    fun searchFiles(query: String): List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow> {
        val q = query.trim().lowercase()
        val all = localRows() + recentRows()
        return all.filter { q in it.name.lowercase() }.distinctBy { it.key }.distinctBy { sameFile(it.name, it.size) }
    }

    internal fun locationOf(uri: String): String = runCatching { vm.files.displayLocation(Uri.parse(uri)) }.getOrDefault(text(R.string.files_location_this_device))

    fun clearRecents() {
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // starred files stay: the stars live with them
                val keep = vm.recents.list().filter { it.starred }
                vm.recents.clear()
                keep.reversed().forEach { vm.recents.touch(it) }
            }
            vm.refreshRecents()
        }
    }

    fun removeRecent(key: String) {
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { vm.recents.remove(setOf(key)) }
            vm.refreshRecents()
        }
    }

    /** What to do with the next PDF opened from a tool tile (Home, Tools). */
    internal var pendingTool: io.github.artcraftandroid.pdfcraft.ui.home.FileTool? = null

    /** A tool that starts by choosing a PDF: pick one, open it, then start the tool. */
    fun toolForFile(tool: io.github.artcraftandroid.pdfcraft.ui.home.FileTool) {
        pendingTool = tool
        vm.pickFiles(FilePickKind.Open)
    }

    /** Open [row] and start [tool] on it. */
    fun openFor(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow, tool: io.github.artcraftandroid.pdfcraft.ui.home.FileTool) {
        pendingTool = tool
        row.open()
    }

    /** A document just opened: start the tool it was opened for, if any. */
    internal fun startPendingTool(doc: OpenDocument) {
        val t = pendingTool ?: return
        pendingTool = null
        when (t) {
            FileTool.Edit -> vm.enterTool(doc, ViewerTool.Edit)
            FileTool.ReadAloud -> vm.sharing.startReadAloud(doc)
            FileTool.FillSign -> vm.enterTool(doc, ViewerTool.FillSign)
            FileTool.Comment -> vm.enterTool(doc, ViewerTool.Comment)
            FileTool.Organize -> { vm.tools.organizing = true; doc.controller.pagesOpen = true }
            FileTool.Compress -> vm.tools.openCompress(doc)
            FileTool.Export -> vm.sharing.openExport(doc)
            FileTool.Protect -> vm.tools.openProtect(doc)
            FileTool.Crop -> vm.tools.openCrop(doc)
            FileTool.Ocr -> vm.recognizeText(doc)
            FileTool.Draw -> vm.enterTool(doc, ViewerTool.Draw)
            FileTool.Split -> { vm.tools.organizing = true; doc.controller.pagesOpen = true; vm.docTools.splitOpen = true }
            FileTool.PageMarks -> vm.docTools.openPageMarks(doc)
            FileTool.Compare -> vm.startCompare(doc)
        }
    }

    /** A file row's local file, for sharing and the like: the file itself, or a fresh copy. */
    private suspend fun localFileOf(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow): File? = withContext(Dispatchers.IO) {
        val u = Uri.parse(row.key)
        runCatching { if (u.scheme == "file") File(requireNotNull(u.path)) else vm.files.workingCopy(u) }.getOrNull()
    }

    /** Files ticked in Home's lists (a long press starts it), keyed by where they are. */
    val selectedFiles = androidx.compose.runtime.mutableStateMapOf<String, io.github.artcraftandroid.pdfcraft.ui.home.FileRow>()

    fun toggleSelected(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        if (selectedFiles.remove(row.key) == null) selectedFiles[row.key] = row
    }

    /** Several PDFs to the share sheet at once. */
    fun shareFiles(rows: List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow>) {
        vm.sharing.handOut {
            val local = rows.mapNotNull { r -> localFileOf(r)?.let { it to r.name } }
            if (local.isEmpty()) error(text(R.string.msg_files_unreadable))
            vm.sharing.sendIntent(withContext(Dispatchers.IO) { vm.files.shareableAll(local) })
        }
    }

    /** Combine the ticked files, in the order shown. */
    fun combineSelected(rows: List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow>) {
        vm.combineFiles.clear()
        rows.take(12).forEach { vm.combineFiles.add(PickedFile(Uri.parse(it.key), it.name, it.size)) }
        vm.tools.toolScreen = ToolScreen.Combine(null)
    }

    fun removeRecents(rows: List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow>) {
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { vm.recents.remove(rows.map { it.key }.toSet()) }
            vm.refreshRecents()
        }
    }

    fun shareFile(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        vm.sharing.handOut {
            val f = localFileOf(row) ?: error(text(R.string.common_file_unreadable))
            vm.sharing.sendIntent(listOf(withContext(Dispatchers.IO) { vm.files.shareable(f, row.name) }))
        }
    }

    fun printFile(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        vm.viewModelScope.launch {
            val f = localFileOf(row) ?: return@launch vm.tell(text(R.string.common_file_unreadable))
            vm.prints.trySend(PrintJob(f, row.name, deleteWhenDone = false))
        }
    }

    fun openWith(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        vm.sharing.handOut {
            val f = localFileOf(row) ?: error(text(R.string.common_file_unreadable))
            val uri = withContext(Dispatchers.IO) { vm.files.shareable(f, row.name) }
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun renameFile(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow, newName: String) {
        vm.viewModelScope.launch {
            val name = DocumentFiles.safeName(newName.trim())
            val u = Uri.parse(row.key)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        u.scheme == "file" -> {
                            val f = File(requireNotNull(u.path))
                            val target = File(f.parentFile, name)
                            require(!target.exists()) { text(R.string.msg_rename_exists, name) }
                            require(f.renameTo(target)) { text(R.string.msg_rename_failed) }
                            vm.recents.remove(setOf(row.key))
                            vm.recents.touch(RecentFile(Uri.fromFile(target).toString(), name, System.currentTimeMillis(), 0, target.length()))
                            vm.settings.bookmarkStore.move(row.key, Uri.fromFile(target).toString())
                        }
                        // what the tools saved to Downloads: MediaStore entries keep their address when renamed
                        u.authority == android.provider.MediaStore.AUTHORITY -> {
                            val values = android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name) }
                            require(app.contentResolver.update(u, values, null, null) > 0) { text(R.string.msg_rename_not_here) }
                            vm.recents.update(row.key) { it.copy(name = name) }
                        }
                        else -> {
                            val renamed = android.provider.DocumentsContract.renameDocument(app.contentResolver, u, name)
                                ?: error(text(R.string.msg_rename_not_here))
                            vm.recents.remove(setOf(row.key))
                            vm.recents.touch(RecentFile(renamed.toString(), name, System.currentTimeMillis(), 0, vm.files.size(renamed) ?: 0))
                            vm.settings.bookmarkStore.move(row.key, renamed.toString())
                        }
                    }
                }
            }
            ok.onFailure { vm.tell(it.userMessage) }
            vm.refreshRecents()
            scanLocal(force = true)
        }
    }

    fun duplicateFile(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        vm.viewModelScope.launch {
            val f = localFileOf(row) ?: return@launch vm.tell(text(R.string.common_file_unreadable))
            val copy = withContext(Dispatchers.IO) { File(app.cacheDir, "dup-${System.nanoTime()}.pdf").also { f.copyTo(it, overwrite = true) } }
            val base = row.name.removeSuffix(".pdf")
            val uri = withContext(Dispatchers.IO) { vm.files.saveToDownloads(copy, "$base copy.pdf").also { copy.delete() } }
            vm.tell(text(R.string.msg_duplicated, "$base copy.pdf"), text(R.string.common_open)) { vm.open(uri) }
            scanLocal(force = true)
        }
    }

    fun saveFileCopy(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        vm.sharing.startCopyRequest(SharingController.CopyRequest.Row(row), row.name)
    }

    /** The place chosen for a file row's Save a copy. */
    internal fun copyRowTo(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow, target: Uri) {
        vm.viewModelScope.launch {
            val f = localFileOf(row) ?: return@launch vm.tell(text(R.string.common_file_unreadable))
            runCatching { withContext(Dispatchers.IO) { vm.files.writeBack(f, target) } }
                .onSuccess { vm.tell(text(R.string.msg_saved_copy)) }.onFailure { vm.tell(it.userMessage) }
        }
    }

    fun deleteFile(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        vm.viewModelScope.launch {
            val u = Uri.parse(row.key)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        u.scheme == "file" -> require(File(requireNotNull(u.path)).delete()) { text(R.string.msg_delete_failed) }
                        u.authority == android.provider.MediaStore.AUTHORITY ->
                            require(app.contentResolver.delete(u, null, null) > 0) { text(R.string.msg_delete_failed) }
                        else -> require(android.provider.DocumentsContract.deleteDocument(app.contentResolver, u)) { text(R.string.msg_delete_failed) }
                    }
                    vm.recents.remove(setOf(row.key))
                }
            }
            ok.onFailure { vm.tell(it.userMessage) }.onSuccess { vm.tell(text(R.string.msg_file_deleted, row.name)) }
            vm.refreshRecents()
            scanLocal(force = true)
        }
    }

    /** Photos: pick pictures and make them into a PDF (Create PDF from the photo picker). */
    val photoPicks = Channel<Unit>(Channel.CONFLATED)

    fun pickPhotosToPdf() {
        photoPicks.trySend(Unit)
    }

    fun onPhotosPicked(uris: List<Uri>) {
        if (uris.isNotEmpty()) vm.onFilesPicked(FilePickKind.Create, uris)
    }
}
