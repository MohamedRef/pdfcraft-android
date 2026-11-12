package io.github.artcraftandroid.pdfcraft.ui

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * One document tab (#138). [doc] is set while the document is open in the engine ("hot"); a
 * parked tab has none and opens again from [key], its source, when it is chosen.
 */
@Stable
class DocTab(val key: String, title: String) {
    var title by mutableStateOf(title)
    var doc by mutableStateOf<OpenDocument?>(null)
    /** Reading Mode and the tool it was left in, given back when it comes to the front again. */
    internal var reading = false
    internal var tool: ViewerTool = ViewerTool.None
    internal var lastUsed = 0L
    val parked: Boolean get() = doc == null
}

/**
 * Several documents open at once, as tabs (#138), and a second pane beside the one in front
 * (#139). [AppViewModel.document] stays the document in front, so every tool keeps acting on it;
 * the other hot documents wait here with their page, zoom and tool. A document only moves to the
 * back after its changes were saved, exactly as when the app goes to the background, and at most
 * [TabPolicy.MAX_HOT] stay open in the engine: older ones are saved and closed (parked) and open
 * again from their file when chosen. The tab list outlives the process, so after Android closes
 * the app the tabs come back (parked).
 */
class TabsController internal constructor(private val vm: AppViewModel) {
    /** The tabs, in strip order. The one whose [DocTab.doc] is [AppViewModel.document] is in front. */
    var tabs by mutableStateOf(restore())
        private set

    /** The hot document shown read-only in the second pane, or null without split view (#139). */
    var splitWith by mutableStateOf<OpenDocument?>(null)
        private set

    /** The main pane's share of the window in split view. */
    var splitFraction by mutableFloatStateOf(0.5f)

    /** The tab in front, if a document is open. */
    val active: DocTab? get() = vm.document?.let { d -> tabs.firstOrNull { it.doc === d } }

    /** Tabs other than the one in front: Home offers to go back to them. */
    val waiting: Int get() = tabs.count { it.doc == null || it.doc !== vm.document }

    /** Sources the working-copy clean-up must keep (#138): every tab's. */
    fun sources(): List<String> = tabs.map { it.key }

    /** Other hot documents the second pane can show. */
    fun splitCandidates(): List<DocTab> = tabs.filter { it.doc != null && it.doc !== vm.document }

    private fun keyOf(doc: OpenDocument): String? = doc.source?.toString()

    // ---- called by AppViewModel while it opens and closes documents ----------------------------

    /**
     * [current] is in front and another document is about to open over it: keep it open in a
     * tab instead of closing it. Its tool's ink is added and its changes saved first, as on
     * leaving the app. Runs under the open lock.
     */
    internal suspend fun stash(eng: Engine, current: OpenDocument) {
        val tab = tabFor(current)
        leaveFront(current, tab)
        vm.save(current, quiet = true, trigger = "tab_switch")
        current.images.trimToWanted()
        Diag.event(DiagArea.Viewer, "tab_stash", "doc" to current.diagNumber, "tabs" to tabs.size, "dirty" to current.info.dirty, "not_written_back" to current.notWrittenBack)
        parkExtra(eng, keep = setOf(current))
    }

    /** [doc] came to the front (opened, or reopened from a parked tab). */
    internal fun opened(doc: OpenDocument, title: String) {
        val key = keyOf(doc)
        val existing = key?.let { k -> tabs.firstOrNull { it.key == k } }
        val tab = existing ?: DocTab(key ?: "doc:${doc.id}", title).also { t ->
            val list = tabs.toMutableList()
            // right after the tab used last (the one that was in front a moment ago)
            val last = list.filter { it.doc != null }.maxByOrNull { it.lastUsed }
            val front = list.indexOf(last)
            list.add(TabPolicy.insertAt(list.size, front.takeIf { it >= 0 }), t)
            tabs = list
        }
        tab.doc = doc
        tab.title = title
        tab.lastUsed = android.os.SystemClock.elapsedRealtime()
        if (splitWith === doc) splitWith = null
        persist()
    }

    /**
     * The document in front was closed: forget its tab and say which document comes to the
     * front now (hot or parked), or null for Home.
     */
    internal fun closed(doc: OpenDocument): DocTab? {
        val index = tabs.indexOfFirst { it.doc === doc }
        if (splitWith === doc) splitWith = null
        if (index < 0) return null
        val next = TabPolicy.nextAfterClose(tabs.size, index)
        tabs = tabs.toMutableList().also { it.removeAt(index) }
        persist()
        Diag.event(DiagArea.Viewer, "tab_close", "doc" to doc.diagNumber, "tabs" to tabs.size, "background" to false)
        return next?.let { tabs.getOrNull(it) }
    }

    /** Bring [tab]'s document to the front as it was left: Reading Mode and its tool. */
    internal fun front(tab: DocTab, doc: OpenDocument) {
        vm.document = doc
        vm.readingMode = tab.reading
        tab.lastUsed = android.os.SystemClock.elapsedRealtime()
        if (splitWith === doc) splitWith = null
        if (tab.tool != ViewerTool.None && !tab.reading) vm.enterTool(doc, tab.tool)
        tab.tool = ViewerTool.None
    }

    // ---- what the tab strip, the keyboard and Home do -------------------------------------------

    /** Show [tab]'s document in front, keeping the current one open in its tab. */
    fun switchTo(tab: DocTab, then: (() -> Unit)? = null) {
        val current = vm.document
        if (tab.doc != null && tab.doc === current) { then?.invoke(); return }
        if (vm.readOnlyPrompt != null) return
        val eng = vm.engine ?: return
        val started = android.os.SystemClock.elapsedRealtime()
        val target = tab.doc
        if (target == null) {
            // parked: it opens again from its file, and opening keeps the current one in a tab
            Diag.event(DiagArea.Viewer, "tab_switch", "parked" to true, "tabs" to tabs.size)
            vm.open(Uri.parse(tab.key))
            return
        }
        vm.viewModelScope.launch {
            vm.openMutex.withLock {
                if (vm.document !== current || tab.doc !== target) return@withLock
                current?.let { stash(eng, it) }
                front(tab, target)
                Diag.event(DiagArea.Viewer, "tab_switch", "doc" to target.diagNumber, "parked" to false, "tabs" to tabs.size, "ms" to android.os.SystemClock.elapsedRealtime() - started)
            }
            then?.invoke()
        }
    }

    /** Ctrl+Tab and Ctrl+Shift+Tab. */
    fun cycle(step: Int): Boolean {
        val list = tabs
        val from = list.indexOfFirst { it.doc != null && it.doc === vm.document }
        val to = TabPolicy.cycle(list.size, from, step) ?: return false
        switchTo(list[to])
        return true
    }

    /** Ctrl+Tab, Ctrl+Shift+Tab and Ctrl+W ([ctrl] already down); null when the key isn't one of them. */
    internal fun key(keyCode: Int, shift: Boolean): Boolean? = when (keyCode) {
        android.view.KeyEvent.KEYCODE_TAB -> if (tabs.size > 1) { log("tab_key", "cycle"); cycle(if (shift) -1 else 1) } else null
        android.view.KeyEvent.KEYCODE_W -> active?.let { log("tab_key", "close"); close(it); true }
        else -> null
    }

    private fun log(event: String, action: String) {
        Diag.event(DiagArea.Viewer, event, "doc" to vm.document?.diagNumber, "action" to action, "tabs" to tabs.size)
    }

    /** Back in the viewer: with other tabs open, Home keeps them all; otherwise the document closes. */
    fun back() {
        val doc = vm.document ?: return
        if (tabs.size > 1 && !doc.fromOtherApp) openAnother() else vm.closeDocument()
    }

    /** Close [tab]: the one in front closes as Back does; another is saved and closed where it is. */
    fun close(tab: DocTab) {
        val doc = tab.doc
        when {
            doc == null -> {
                tabs = tabs - tab
                persist()
                Diag.event(DiagArea.Viewer, "tab_close", "parked" to true, "tabs" to tabs.size)
            }
            doc === vm.document -> vm.closeDocument()
            // changes the original lacks: bring it to the front so closing can offer Save a copy
            doc.notWrittenBack || doc.info.dirty -> switchTo(tab) { vm.closeDocument() }
            else -> {
                val eng = vm.engine ?: return
                vm.viewModelScope.launch {
                    vm.openMutex.withLock {
                        if (tab.doc !== doc || vm.document === doc) return@withLock
                        if (splitWith === doc) splitWith = null
                        if (vm.saveAndClose(eng, doc, reading = tab.reading, pending = null)) {
                            tabs = tabs - tab
                            persist()
                            Diag.event(DiagArea.Viewer, "tab_close", "doc" to doc.diagNumber, "tabs" to tabs.size, "background" to true)
                        }
                    }
                }
            }
        }
    }

    /** Close every tab but the one in front. */
    fun closeOthers() {
        tabs.filter { it.doc == null || it.doc !== vm.document }.forEach { close(it) }
    }

    /** Go to Home to open another document, keeping this one open in its tab. */
    fun openAnother() {
        val current = vm.document ?: return
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            vm.openMutex.withLock {
                if (vm.document !== current) return@withLock
                stash(eng, current)
                splitWith = null
                vm.document = null
                vm.readingMode = false
                Diag.event(DiagArea.Viewer, "tab_home", "doc" to current.diagNumber, "tabs" to tabs.size)
            }
            vm.refreshRecents()
        }
    }

    /** From Home: back to the tab used last. */
    fun resume() {
        val tab = tabs.filter { it.doc != null }.maxByOrNull { it.lastUsed } ?: tabs.lastOrNull() ?: return
        switchTo(tab)
    }

    // ---- split view (#139) ----------------------------------------------------------------------

    /** Show [tab]'s document beside the one in front (it opens again first if it was parked). */
    fun split(tab: DocTab) {
        val doc = tab.doc
        if (doc == null || doc === vm.document) return
        splitWith = doc
        tab.lastUsed = android.os.SystemClock.elapsedRealtime()
        Diag.event(DiagArea.Viewer, "split_open", "doc" to vm.document?.diagNumber, "other" to doc.diagNumber, "tabs" to tabs.size)
    }

    fun closeSplit() {
        val other = splitWith ?: return
        splitWith = null
        Diag.event(DiagArea.Viewer, "split_close", "doc" to vm.document?.diagNumber, "other" to other.diagNumber)
    }

    /** Swap the panes: the second pane's document comes to the front for editing. */
    fun swapSplit() {
        val other = splitWith ?: return
        val current = vm.document ?: return
        val tab = tabs.firstOrNull { it.doc === other } ?: return
        Diag.event(DiagArea.Viewer, "split_swap", "doc" to current.diagNumber, "other" to other.diagNumber)
        switchTo(tab) { if (vm.document === other && current.let { c -> tabs.any { it.doc === c } }) splitWith = current }
    }

    // ---- inside ----------------------------------------------------------------------------------

    private fun tabFor(doc: OpenDocument): DocTab =
        tabs.firstOrNull { it.doc === doc } ?: run {
            opened(doc, doc.title)
            tabs.first { it.doc === doc }
        }

    /** Leave [doc] as the front document: what the viewer held for it alone stops. */
    private fun leaveFront(doc: OpenDocument, tab: DocTab) {
        tab.reading = vm.readingMode
        tab.tool = doc.controller.tool
        vm.closeLecturePlayback()
        vm.readAloud?.stop()
        vm.study.pauseStudy()
        vm.study.studyDoc = null
        if (doc.controller.tool != ViewerTool.None) vm.leaveTool(doc)
        doc.selection.clear()
    }

    /** Keep at most [TabPolicy.MAX_HOT] documents open: save and close the least recently used. */
    private suspend fun parkExtra(eng: Engine, keep: Set<OpenDocument>) {
        val hot = tabs.filter { it.doc != null }
        val park = TabPolicy.toPark(hot, lastUsed = { it.lastUsed }, keep = { t ->
            val d = t.doc!!
            d in keep || d === vm.document || d === splitWith || d.source == null || d.notWrittenBack || d.info.dirty || d.info.preparing
        })
        for (tab in park) {
            val d = tab.doc ?: continue
            if (vm.saveAndClose(eng, d, reading = tab.reading, pending = null)) {
                tab.doc = null
                Diag.event(DiagArea.Viewer, "tab_park", "doc" to d.diagNumber, "hot" to tabs.count { it.doc != null })
            }
        }
    }

    private fun persist() {
        val saved = TabPolicy.encode(tabs.filter { it.key.startsWith("content:") || it.key.startsWith("file:") }.map { it.key to it.title })
        runCatching { vm.appPrefs.edit().putString(PREF, saved).apply() }
    }

    private fun restore(): List<DocTab> =
        runCatching { TabPolicy.decode(vm.appPrefs.getString(PREF, null)) }.getOrDefault(emptyList()).map { (uri, title) -> DocTab(uri, title) }

    private companion object {
        const val PREF = "openTabs"
    }
}
