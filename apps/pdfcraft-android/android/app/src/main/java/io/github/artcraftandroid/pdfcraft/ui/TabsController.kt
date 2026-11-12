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
import androidx.compose.ui.graphics.asAndroidBitmap
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.ui.viewer.hex
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
/** An area marked in the read-only pane (#139): [rect] in points on [page] of [doc]. */
class Snip(val doc: OpenDocument, val page: Int, val rect: FloatArray)

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
        val before = vm.document
        vm.document = doc
        vm.readingMode = tab.reading
        tab.lastUsed = android.os.SystemClock.elapsedRealtime()
        // editing moved to the other pane: the document that was in front stays in its pane
        if (splitWith === doc) splitWith = swapBack?.takeIf { it === before && it !== doc && tabs.any { t -> t.doc === it } }
        swapBack = null
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
        splitFirst = vm.document
        tab.lastUsed = android.os.SystemClock.elapsedRealtime()
        Diag.event(DiagArea.Viewer, "split_open", "doc" to vm.document?.diagNumber, "other" to doc.diagNumber, "tabs" to tabs.size)
    }

    fun closeSplit() {
        val other = splitWith ?: return
        splitWith = null
        snip = null
        snipping = false
        Diag.event(DiagArea.Viewer, "split_close", "doc" to vm.document?.diagNumber, "other" to other.diagNumber)
    }

    /**
     * Edit in the other pane (#139): its document comes to the front with the whole editing
     * viewer, and the one that was in front stays where it is, read-only. The panes keep their places.
     */
    fun swapSplit() {
        val other = splitWith ?: return
        val current = vm.document ?: return
        val tab = tabs.firstOrNull { it.doc === other } ?: return
        snip = null
        snipping = false
        Diag.event(DiagArea.Viewer, "split_swap", "doc" to current.diagNumber, "other" to other.diagNumber)
        swapBack = current
        switchTo(tab)
    }

    private var swapBack: OpenDocument? = null

    /** The document in the first pane (start or top), so panes keep their places when editing moves. */
    var splitFirst by mutableStateOf<OpenDocument?>(null)
        private set

    // ---- dragging between the panes (#139) --------------------------------------------------------

    /** Snip mode in the read-only pane: a drag marks an area to copy across. */
    var snipping by mutableStateOf(false)

    /** The area marked in the read-only pane, waiting to be dragged onto the other one. */
    var snip by mutableStateOf<Snip?>(null)

    /**
     * [snip] was dropped at [at] on the document in front: its text becomes a text box there
     * (an image of the area when it has no text), with a link back to the source page as a
     * standard GoToR action, so it also works in other readers.
     */
    fun drop(snip: Snip, at: io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint) {
        val target = vm.document ?: return
        val source = snip.doc
        this.snip = null
        if (source === target) return
        if (!target.info.allowsAnnotation) {
            vm.tell(target.info.readOnlyReason ?: vm.getApplication<android.app.Application>().getString(io.github.artcraftandroid.pdfcraft.R.string.msg_tool_blocked_security))
            return
        }
        val eng = vm.engine ?: return
        val page = target.info.pages.getOrNull(at.page) ?: return
        val started = android.os.SystemClock.elapsedRealtime()
        vm.viewModelScope.launch {
            try {
                val r = snip.rect
                val text = runCatching {
                    eng.tool("text_extract", mapOf("doc" to source.id, "pages" to listOf(snip.page + 1), "rect" to r.map { it.toDouble() }))
                        .optJSONArray("pages")?.optJSONObject(0)?.optString("text")?.trim()
                }.getOrNull().orEmpty()
                // drawings in the area (ink, shapes) go across as drawings that stay editable (#379)
                val drawn = runCatching {
                    val list = eng.tool("comment_list", mapOf("doc" to source.id, "page" to snip.page + 1)).optJSONArray("comments")
                    (0 until (list?.length() ?: 0)).mapNotNull { i ->
                        val c = list!!.optJSONObject(i) ?: return@mapNotNull null
                        if (c.optString("type") !in SplitDrop.DRAWN) return@mapNotNull null
                        val b = c.optJSONArray("rect") ?: return@mapNotNull null
                        val box = floatArrayOf(b.optDouble(0).toFloat(), b.optDouble(1).toFloat(), b.optDouble(2).toFloat(), b.optDouble(3).toFloat())
                        if (SplitDrop.inside(box, r)) c.optInt("index") to box else null
                    }
                }.getOrDefault(emptyList())
                val box: FloatArray
                val kind: String
                if (drawn.isNotEmpty()) {
                    val xfdf = eng.tool("comments_copy", mapOf("doc" to source.id, "comments" to drawn.map { listOf(snip.page + 1, it.first) })).getString("xfdf")
                    val moved = SplitDrop.movedUnion(drawn.map { it.second }, at.x, at.y)
                    eng.tool("comments_paste", mapOf("doc" to target.id, "page" to at.page + 1, "xfdf" to xfdf, "at" to listOf(at.x.toDouble(), at.y.toDouble())))
                    box = SplitDrop.place(moved[0], moved[1], moved[2] - moved[0], moved[3] - moved[1], page.width, page.height, centred = false)
                    kind = "ink"
                } else if (text.isNotEmpty()) {
                    val size = 12f
                    val (lines, w, h) = FreeTextLayout.layout(text, size, (page.width - at.x - 6f).coerceAtLeast(size * 4))
                    box = SplitDrop.place(at.x, at.y, w, h, page.width, page.height, centred = false)
                    eng.tool("comment_add", mapOf(
                        "doc" to target.id, "page" to at.page + 1, "type" to "textbox", "rect" to box.map { it.toDouble() },
                        "contents" to lines.joinToString("\n"), "color" to vm.toolPrefs.color(io.github.artcraftandroid.pdfcraft.ui.viewer.StyledTool.FreeText).hex(),
                        "font_size" to size.toDouble(), "author" to vm.commenting.author,
                    ))
                    kind = "text"
                } else {
                    val scale = SplitDrop.SNIP_SCALE
                    val tile = io.github.artcraftandroid.pdfcraft.core.TileRect(
                        (r[0] * scale).toInt().coerceAtLeast(0).toUInt(), (r[1] * scale).toInt().coerceAtLeast(0).toUInt(),
                        ((r[2] - r[0]) * scale).toInt().coerceAtLeast(1).toUInt(), ((r[3] - r[1]) * scale).toInt().coerceAtLeast(1).toUInt(),
                    )
                    val image = source.images.renderRegion(snip.page, scale, tile) ?: throw IllegalStateException("snip render failed")
                    val file = vm.tempFile("png")
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        file.outputStream().use { image.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    }
                    box = SplitDrop.place(at.x, at.y, r[2] - r[0], r[3] - r[1], page.width, page.height, centred = true)
                    try {
                        eng.tool("page_add_image", mapOf("doc" to target.id, "page" to at.page + 1, "path" to file.absolutePath, "rect" to box.map { it.toDouble() }))
                    } finally {
                        file.delete()
                    }
                    kind = "image"
                }
                // the back-link: the source file by name and its page, as a GoToR action
                val name = io.github.artcraftandroid.pdfcraft.ui.viewer.linkFileName(source.name)
                if (name != null) {
                    // the exact area it came from, as a /FitR destination (#379)
                    eng.tool("link_add", mapOf(
                        "doc" to target.id, "page" to at.page + 1, "rect" to box.map { it.toDouble() }, "file" to name, "to_page" to snip.page + 1,
                        "to_rect" to r.map { it.toDouble() }, "to_doc" to source.id,
                    ))
                }
                vm.refresh(target)
                Diag.event(DiagArea.Viewer, "split_drop", "doc" to target.diagNumber, "other" to source.diagNumber, "kind" to kind, "linked" to (name != null), "ms" to android.os.SystemClock.elapsedRealtime() - started)
                vm.tell(vm.getApplication<android.app.Application>().getString(if (name != null) io.github.artcraftandroid.pdfcraft.R.string.tabs_drop_linked else io.github.artcraftandroid.pdfcraft.R.string.tabs_drop_done))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Diag.event(DiagArea.Viewer, "split_drop", "doc" to target.diagNumber, "other" to source.diagNumber, "kind" to "failed")
                vm.tell(e.userMessage)
                vm.refresh(target)
            }
        }
    }

    /**
     * A link to page [page] (0-based) of the PDF at [uri] (#139 back-links, #334): when that
     * document is open in the other pane it scrolls there; in another tab, that tab comes to the
     * front on that page. Returns false when it isn't open, so the caller opens it.
     */
    fun followLink(uri: String, page: Int, area: List<Float>? = null): Boolean {
        val other = splitWith
        if (other != null && other.source?.toString() == uri) {
            Diag.event(DiagArea.Viewer, "split_link", "doc" to vm.document?.diagNumber, "other" to other.diagNumber, "pane" to true, "area" to (area != null))
            vm.viewModelScope.launch { showArea(other, page, area) }
            return true
        }
        val tab = tabs.firstOrNull { it.doc != null && it.key == uri } ?: return false
        val d = tab.doc ?: return false
        switchTo(tab) {
            if (vm.document === d) {
                d.controller.rememberJump(d.viewer)
                vm.viewModelScope.launch { showArea(d, page, area) }
            }
        }
        Diag.event(DiagArea.Viewer, "split_link", "doc" to vm.document?.diagNumber, "other" to d.diagNumber, "pane" to false, "area" to (area != null))
        return true
    }

    /**
     * Show [page] of [doc]; with [area] (that page's PDF user space, from a /FitR back-link, #379)
     * scrolled to the area, which flashes so the eye finds it.
     */
    internal suspend fun showArea(doc: OpenDocument, page: Int, area: List<Float>?) {
        if (page !in 0 until doc.pageCount) return
        val view = area?.let { a -> vm.engine?.let { eng -> runCatching { eng.command { userRectToView(doc.id, page.toUInt(), a) } }.getOrNull() } }
            ?.takeIf { it.size == 4 }
        if (view == null) {
            doc.viewer.goToPage(page)
            return
        }
        doc.viewer.goToPage(page, (view[1] - 24f).coerceAtLeast(0f))
        val flash = io.github.artcraftandroid.pdfcraft.ui.viewer.LinkFlash(page, view.toFloatArray())
        doc.linkFlash = flash
        kotlinx.coroutines.delay(1500)
        if (doc.linkFlash === flash) doc.linkFlash = null
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
