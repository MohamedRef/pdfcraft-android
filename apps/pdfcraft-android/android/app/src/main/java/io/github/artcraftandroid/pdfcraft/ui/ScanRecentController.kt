package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanRecentEntry
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanRecentSetting
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanRecentStore
import io.github.artcraftandroid.pdfcraft.ui.scan.scanRecentDeleteDiag
import io.github.artcraftandroid.pdfcraft.ui.scan.scanRecentKeepDiag
import io.github.artcraftandroid.pdfcraft.ui.scan.scanRecentOpenDiag
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Recent scans (#461): the last scans saved, kept with their photos and page settings so they can
 * be opened again on the document screen and saved again. Testing option `recentScans` and the
 * switch in Settings ▸ Scanner both have to be on for a scan to be kept.
 */
internal class ScanRecentController(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()
    private val store by lazy { ScanRecentStore(File(app.filesDir, "scan-recent")) }

    /** The kept scans, newest first, as last read. */
    var list by mutableStateOf<List<ScanRecentEntry>>(emptyList())
        private set

    /** The kept scan the scan on screen was opened from; null for a new scan. */
    var openedFrom: String? = null

    /** Whether a scan saved now is kept. */
    fun on(): Boolean = vm.scanLab.options.recentScans && ScanRecentSetting.get(vm.settings.appPrefs)

    /** The id the scan being saved will be kept under: the one it was opened from, else a new one (#513). */
    fun idForSave(): String = openedFrom ?: store.newId(System.currentTimeMillis())

    /** Kept scan [id]'s page count and when it was saved; null when it is not kept (#513). Blocking. */
    fun pagesOf(id: String): Pair<Int, Long>? = runCatching { store.pagesOf(id) }.getOrNull()

    /** Record the file kept scan [id] became (#513). Blocking. */
    fun link(id: String, uri: String, pages: Int) {
        runCatching { store.link(id, uri, pages) }.onFailure { Diag.failure(DiagArea.Scan, "scan_link", it) }
    }

    /** Copy kept scan [id]'s photos into the scan's working folder and hand back its pages (#513). Blocking. */
    internal fun openCopy(id: String): io.github.artcraftandroid.pdfcraft.ui.scan.ScanRecentOpened? =
        runCatching { store.open(id, File(app.cacheDir, "camera")) }.onFailure { Diag.failure(DiagArea.Scan, "recent_open", it) }.getOrNull()

    fun refresh() {
        vm.viewModelScope.launch { list = withContext(Dispatchers.IO) { store.list() } }
    }

    /** The Recent scans list. */
    fun show() {
        refresh()
        vm.tools.toolScreen = ToolScreen.ScanRecent
    }

    /**
     * Keep a saved scan's photos and settings; the photos are moved, so the scan's own copies are
     * gone after this. [cover] is the first page as made, for the list's picture (#494). Blocking.
     * False when it could not be kept (the photos are deleted then).
     */
    fun keep(session: ScanSession, cover: File? = null, id: String? = openedFrom): Boolean {
        val started = SystemClock.elapsedRealtime()
        return try {
            val kept = store.keep(id, session, System.currentTimeMillis(), cover = cover)
            Diag.event(DiagArea.Scan, "recent_keep", *scanRecentKeepDiag(kept, session.pages.size, SystemClock.elapsedRealtime() - started))
            true
        } catch (e: Exception) {
            Diag.failure(DiagArea.Scan, "recent_keep", e)
            session.pages.forEach { File(it.path).delete() }
            false
        } finally {
            openedFrom = null
        }
    }

    /** Open kept scan [id] on the document screen; its photos are copied, so the kept scan stays as it is until it is saved again. */
    fun open(id: String) {
        if (vm.scanPages.isNotEmpty()) {
            // one scan at a time: the one in progress is not mixed with another
            vm.tell(text(R.string.scan_recent_busy))
            vm.tools.toolScreen = ToolScreen.Scan
            return
        }
        vm.viewModelScope.launch {
            val started = SystemClock.elapsedRealtime()
            val work = File(app.cacheDir, "camera")
            val opened = withContext(Dispatchers.IO) { runCatching { store.open(id, work) }.onFailure { Diag.failure(DiagArea.Scan, "recent_open", it) }.getOrNull() }
            if (opened == null || opened.session.pages.isEmpty()) {
                vm.tell(text(R.string.scan_recent_unreadable))
                refresh()
                return@launch
            }
            vm.tools.toolScreen = null
            vm.restoreScan(opened.session.encode(), opened.session).join()
            openedFrom = id
            Diag.event(DiagArea.Scan, "recent_open", *scanRecentOpenDiag(vm.scanPages.size, opened.missing, opened.savedAt, System.currentTimeMillis(), SystemClock.elapsedRealtime() - started))
            if (opened.missing > 0) vm.tell(plural(R.plurals.scan_recent_missing, opened.missing, opened.missing))
            vm.tools.toolScreen = ToolScreen.Scan
            if (vm.scanLab.options.steps) vm.scanSteps.showDocument("recent", vm.scanPages.size)
        }
    }

    fun delete(id: String) {
        vm.viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { store.delete(id) }
            if (openedFrom == id) openedFrom = null
            Diag.event(DiagArea.Scan, "recent_delete", *scanRecentDeleteDiag(1, bytes, all = false))
            list = withContext(Dispatchers.IO) { store.list() }
        }
    }

    fun deleteAll() {
        vm.viewModelScope.launch {
            val (count, bytes) = withContext(Dispatchers.IO) { store.deleteAll() }
            openedFrom = null
            Diag.event(DiagArea.Scan, "recent_delete", *scanRecentDeleteDiag(count, bytes, all = true))
            list = emptyList()
        }
    }
}
