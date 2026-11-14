package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.engine.Engine
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewLayout
import io.github.artcraftandroid.pdfcraft.engine.UserBookmark
import io.github.artcraftandroid.pdfcraft.engine.UserBookmarks
import io.github.artcraftandroid.pdfcraft.ui.viewer.ToolPrefs
import android.content.Intent
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool
import kotlinx.coroutines.flow.first
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.Diagnostics

class SettingsController internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    // ---- Tools' remembered settings, bookmarks, stars --------------------------------------

    val toolPrefs = ToolPrefs(app)

    /** The viewer's bottom toolbar as the user arranged it (More tools ▸ Customize toolbar). */
    val quickToolbar = io.github.artcraftandroid.pdfcraft.ui.viewer.QuickToolbarPrefs(app)
    internal val appPrefs = app.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
    var compactPreviews by mutableStateOf(appPrefs.getBoolean("compactPreviews", false))
        internal set
    fun updateCompactPreviews(on: Boolean) {
        compactPreviews = on
        appPrefs.edit().putBoolean("compactPreviews", on).apply()
    }
    /**
     * Testing option (#453): open PDFs from other apps in place, without a copy in the app's
     * storage. Off: the copy every earlier version made, to compare the two on a phone.
     */
    var openInPlace by mutableStateOf(appPrefs.getBoolean("openInPlace", true))
        internal set
    fun updateOpenInPlace(on: Boolean) {
        openInPlace = on
        appPrefs.edit().putBoolean("openInPlace", on).apply()
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Open, "open_in_place_setting", "on" to on)
    }
    /** Whether All files access is granted, so originals lent read-only are saved to directly (#491). */
    var allFilesAccess by mutableStateOf(io.github.artcraftandroid.pdfcraft.engine.AllFilesAccess.granted())
        internal set
    internal val bookmarkStore = UserBookmarks(File(app.filesDir, "bookmarks.json"))

    /** The Search tab's last searches, newest first (kept on the phone, as the reference keeps them). */
    var recentSearches by mutableStateOf(appPrefs.getString("recentSearches", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList())
        internal set

    fun addRecentSearch(q: String) {
        val t = q.trim()
        if (t.isEmpty()) return
        recentSearches = (listOf(t) + recentSearches.filterNot { it.equals(t, ignoreCase = true) }).take(10)
        appPrefs.edit().putString("recentSearches", recentSearches.joinToString("\n")).apply()
    }

    fun removeRecentSearch(q: String) {
        recentSearches = recentSearches - q
        appPrefs.edit().putString("recentSearches", recentSearches.joinToString("\n")).apply()
    }

    fun clearRecentSearches() {
        recentSearches = emptyList()
        appPrefs.edit().remove("recentSearches").apply()
    }

    /** The key a document's own data (bookmarks, stars, last page) is kept under: where it came from. */
    fun docKey(doc: OpenDocument): String = doc.source?.toString() ?: doc.file.absolutePath

    /** The reader's bookmarks of a document. */
    inner class Bookmarks {
        fun list(doc: OpenDocument) = bookmarkStore.list(docKey(doc))
        fun isBookmarked(doc: OpenDocument, page: Int) = bookmarkStore.has(docKey(doc), page)
        fun rename(doc: OpenDocument, b: UserBookmark, name: String) = bookmarkStore.rename(docKey(doc), b, name)
        fun remove(doc: OpenDocument, b: UserBookmark) = bookmarkStore.remove(docKey(doc), b)
        fun clear(doc: OpenDocument) = bookmarkStore.clear(docKey(doc))
    }

    val bookmarks = Bookmarks()

    /** Add a bookmark on the current page, or remove the one there. */
    fun toggleBookmark(doc: OpenDocument) {
        val (b, added) = bookmarkStore.toggle(docKey(doc), doc.viewer.currentPage)
        vm.tell(text(if (added) R.string.msg_bookmark_added else R.string.msg_bookmark_removed, b.name))
    }

    /** Delete all bookmarks of a document: waiting for confirmation. */
    var confirmDeleteAllBookmarks by mutableStateOf<OpenDocument?>(null)

    fun deleteAllBookmarks(doc: OpenDocument) {
        bookmarks.clear(doc)
        vm.tell(text(R.string.msg_bookmarks_cleared))
    }

    fun isStarred(doc: OpenDocument): Boolean = vm.recentList.firstOrNull { it.uri == docKey(doc) }?.starred == true

    fun toggleStar(doc: OpenDocument) {
        val key = docKey(doc)
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { vm.recents.update(key) { it.copy(starred = !it.starred) } }
            vm.refreshRecents()
        }
    }

    fun setStarred(uri: String, starred: Boolean) {
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { vm.recents.update(uri) { it.copy(starred = starred) } }
            vm.refreshRecents()
        }
    }

    // ---- View settings ----------------------------------------------------------------------

    /** Reading Mode (text reflowed to the screen) is showing. Remembered for each file, as the reference does. */
    var readingMode by mutableStateOf(false)
        internal set

    /** The page mode documents open in (View settings; the app's default, and each file's own once changed). */
    val savedPageMode: io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode
        get() = when (appPrefs.getString("pageMode", "continuous")) {
            "single" -> io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.SinglePage
            "horizontal" -> io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.Horizontal
            else -> io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.Continuous
        }

    /** The app's spread: View settings' last choice, for files that have none of their own. */
    private val savedSpread: io.github.artcraftandroid.pdfcraft.ui.viewer.Spread
        get() = when (appPrefs.getString("spread", "single")) {
            "two" -> io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.TwoUp
            "cover" -> io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.TwoUpCover
            else -> io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.Single
        }

    /** Lay [doc] out as [layout] remembered for it, or as the app's View settings when null (#152). */
    internal fun applyLayout(doc: OpenDocument, layout: ViewLayout?, source: String) {
        val l = layout ?: ViewLayout(savedPageMode, savedSpread, appPrefs.getBoolean("spreadAuto", false))
        // where the layout a file opened in came from: remembered for it, its initial view, or the app's (#152)
        if (l.mode != doc.viewer.mode) doc.viewer.switchMode(l.mode)
        doc.viewer.chooseFitEach(l.fitEach)
        // fit_each: whether each page fills the width in the continuous view, chosen or by itself, and
        // width_spread: the narrowest page over the widest, so the log shows how often it's needed (#524)
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(
            io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "layout_open", "doc" to doc.diagNumber, "source" to (if (layout == null) "app" else source),
            "mode" to l.mode, "spread" to l.spread, "auto" to l.autoSpread,
            "fit_each" to fitEachDiag(doc.viewer), "width_spread" to widthSpreadDiag(doc.viewer.widthSpread), "made_scan" to doc.viewer.madeScan,
        )
        spreadPref = l.spread
        spreadAuto = l.autoSpread
    }

    /** The layout [doc]'s initial view asks for, or null when it leaves it to the reader or can't be read. */
    internal suspend fun fileLayout(eng: io.github.artcraftandroid.pdfcraft.engine.Engine, doc: ULong): ViewLayout? = runCatching {
        val view = eng.tool("doc_initial_view", mapOf("doc" to doc.toLong())).optJSONObject("initial_view")
        ViewLayout.of(OpenLayout.fromReply(view?.optString("layout")))
    }.getOrNull()

    /** How [doc] is laid out now, to remember for it (the layout it had before presenting, if presenting). */
    internal fun currentLayout(doc: OpenDocument) =
        ViewLayout(doc.controller.modeBeforePresenting ?: doc.viewer.mode, spreadPref, spreadAuto, doc.viewer.fitEachChoice)

    /**
     * Fit every page of [doc] to the width in the continuous view, or keep them to one scale
     * (#524). Remembered for the document with its layout when it closes.
     */
    fun setFitEach(doc: OpenDocument, on: Boolean) {
        readingMode = false
        doc.viewer.chooseFitEach(on)
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(
            io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "fit_each", "doc" to doc.diagNumber, "on" to on,
            "width_spread" to widthSpreadDiag(doc.viewer.widthSpread), "made_scan" to doc.viewer.madeScan, "page" to doc.viewer.currentPage + 1,
        )
    }

    /**
     * Write [doc]'s layout into the file as its initial view, so other readers open it the same
     * way (#152). Pages in a row and automatic two pages have no PDF equivalent.
     */
    fun saveLayoutAsInitialView(doc: OpenDocument) {
        val open = currentLayout(doc).openLayout() ?: return
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "layout_initial_view", "doc" to doc.diagNumber, "layout" to open)
        vm.docTools.setInitialView(doc, mapOf("layout" to open.arg))
    }

    /** Whether [doc]'s layout can be written as its initial view. */
    fun layoutHasInitialView(doc: OpenDocument): Boolean = currentLayout(doc).openLayout() != null

    val savedNightMode: Boolean get() = appPrefs.getBoolean("nightMode", false)

    /**
     * Two pages side by side (View settings; remembered app-wide). It applies only where the
     * window is wide enough ([io.github.artcraftandroid.pdfcraft.ui.viewer.spreadFits]); a phone
     * held upright shows one page.
     */
    var spreadPref by mutableStateOf(savedSpread)
        internal set

    /** Two pages side by side when the window is wide and landscape, one otherwise (#152). */
    var spreadAuto by mutableStateOf(appPrefs.getBoolean("spreadAuto", false))
        internal set

    fun chooseSpreadAuto(on: Boolean) {
        readingMode = false
        spreadAuto = on
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "spread_auto", "on" to on)
        appPrefs.edit().putBoolean("spreadAuto", on).apply()
    }

    fun setSpread(spread: io.github.artcraftandroid.pdfcraft.ui.viewer.Spread) {
        // two pages side by side are page views: leave Reading Mode for them
        readingMode = false
        spreadPref = spread
        spreadAuto = false
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "spread", "spread" to spread)
        appPrefs.edit().putBoolean("spreadAuto", false).putString("spread", when (spread) {
            io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.TwoUp -> "two"
            io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.TwoUpCover -> "cover"
            io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.Single -> "single"
        }).apply()
    }

    fun setPageMode(doc: OpenDocument, mode: io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode) {
        // leaving Reading Mode: a canvas laid out already followed the page read; else it shows it on layout
        if (readingMode && doc.viewer.viewW > 0f) vm.pendingPage = null
        readingMode = false
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "page_mode", "doc" to doc.diagNumber, "from" to doc.viewer.mode, "to" to mode, "page" to doc.viewer.currentPage + 1)
        doc.viewer.switchMode(mode)
        appPrefs.edit().putString("pageMode", when (mode) {
            io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.SinglePage -> "single"
            io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.Horizontal -> "horizontal"
            io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.Continuous -> "continuous"
        }).apply()
    }

    fun setNightMode(doc: OpenDocument, on: Boolean) {
        doc.controller.nightMode = on
        appPrefs.edit().putBoolean("nightMode", on).apply()
        // Night mode inverts the page: on top of reading colours it would undo them
        if (on && doc.controller.readingColors != io.github.artcraftandroid.pdfcraft.ui.viewer.ReadingColors.Off) {
            setReadingColors(doc, io.github.artcraftandroid.pdfcraft.ui.viewer.ReadingColors.Off)
        }
    }

    /** The reading colours documents open with (View settings; remembered app-wide). */
    val savedReadingColors: io.github.artcraftandroid.pdfcraft.ui.viewer.ReadingColors
        get() = io.github.artcraftandroid.pdfcraft.ui.viewer.ReadingColors.fromPref(appPrefs.getString("readingColors", null))

    /** Draw [doc]'s pages in [colors] (a view setting in the engine; the file doesn't change). */
    fun setReadingColors(doc: OpenDocument, colors: io.github.artcraftandroid.pdfcraft.ui.viewer.ReadingColors) {
        appPrefs.edit().putString("readingColors", colors.engineMode).apply()
        if (colors != io.github.artcraftandroid.pdfcraft.ui.viewer.ReadingColors.Off && doc.controller.nightMode) {
            doc.controller.nightMode = false
            appPrefs.edit().putBoolean("nightMode", false).apply()
        }
        applyReadingColors(doc, colors)
    }

    /**
     * Hide or show every annotation on the pages (#155): a view setting in the engine
     * (comments_hide), so the file isn't changed or marked as edited.
     */
    fun setCommentsHidden(doc: OpenDocument, hidden: Boolean) {
        val eng = vm.engine ?: return
        if (doc.info.preparing) return
        vm.commenting.selectedComment = null
        vm.viewModelScope.launch {
            val ok = runCatching {
                io.github.artcraftandroid.pdfcraft.engine.Diag.measure(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "comments_hide", "doc" to doc.diagNumber, "hidden" to hidden) { eng.tool("comments_hide", mapOf("doc" to doc.id, "hidden" to hidden)) }
            }.onFailure { io.github.artcraftandroid.pdfcraft.engine.Diag.failure(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "comments_hide", it) }.isSuccess
            if (!ok) return@launch
            // the switch reads the engine's state; the pages draw again without waiting for an edit
            vm.refresh(doc)
            doc.images.invalidate()
        }
    }

    /** Give a newly opened (and prepared) [doc] the saved reading colours. */
    fun applySavedReadingColors(doc: OpenDocument) {
        val saved = savedReadingColors
        if (saved != doc.controller.readingColors) applyReadingColors(doc, saved)
    }

    private fun applyReadingColors(doc: OpenDocument, colors: io.github.artcraftandroid.pdfcraft.ui.viewer.ReadingColors) {
        val eng = vm.engine ?: return
        if (doc.info.preparing) return
        vm.viewModelScope.launch {
            val ok = runCatching {
                io.github.artcraftandroid.pdfcraft.engine.Diag.measure(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "reading_colors", "doc" to doc.diagNumber, "colors" to colors) { eng.tool("view_reading_colors", mapOf("doc" to doc.id, "mode" to colors.engineMode)) }
            }.onFailure { io.github.artcraftandroid.pdfcraft.engine.Diag.failure(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "reading_colors", it) }.isSuccess
            if (!ok) return@launch
            doc.controller.readingColors = colors
            // the engine's view changed: every page draws again
            vm.refresh(doc)
        }
    }

    fun openReadingMode(doc: OpenDocument) {
        if (doc.info.preparing) { vm.tell(text(R.string.document_preparing)); return }
        if (doc.controller.tool != ViewerTool.None) vm.leaveTool(doc)
        readingMode = true
    }

    /** A tool tried in Reading Mode, for the dialog that says it needs another view. */
    var readingModeBlocked by mutableStateOf<ViewerTool?>(null)

    /** Keep the screen on while reading (Preferences; released after half an hour without a touch). */
    var keepScreenOn by mutableStateOf(appPrefs.getBoolean("keepScreenOn", false))
        internal set

    fun updateKeepScreenOn(on: Boolean) {
        keepScreenOn = on
        appPrefs.edit().putBoolean("keepScreenOn", on).apply()
    }

    /** The diagnostics number of the latest open (#105: lines name a number, never the file). */
    internal var diagDoc = 0

    /**
     * About → Diagnostics: record the detailed diagnostics log (open and render timings, pen
     * latency, saves, scans, memory, failures) in app-private files so it can be shared without
     * adb. See docs/ANDROID_DIAGNOSTICS_LOG.md.
     */
    var recordDiagnostics by mutableStateOf(Diag.enabled())
        internal set

    fun updateRecordDiagnostics(on: Boolean) {
        recordDiagnostics = on
        val app = app
        vm.viewModelScope.launch(Dispatchers.IO) { Diagnostics.setOn(app, on) }
    }

    fun clearDiagnostics() {
        val app = app
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { Diagnostics.clear(app) }
            vm.tell(text(R.string.home_diagnostics_cleared))
        }
    }

    /** Share the diagnostics log as a text file; only event names, times, counts and sizes. */
    fun shareDiagnostics() {
        val app = app
        vm.viewModelScope.launch {
            // the pen latency so far (#128), as Pen settings shows it
            listOf("normal" to io.github.artcraftandroid.pdfcraft.ui.viewer.InkLatency.normal, "low_latency" to io.github.artcraftandroid.pdfcraft.ui.viewer.InkLatency.lowLatency).forEach { (path, stats) ->
                stats.summary()?.let { Diag.event(DiagArea.Ink, "latency_summary", "path" to path, "samples" to it.count, "p50_ms" to it.medianMs, "p95_ms" to it.p95Ms) }
            }
            io.github.artcraftandroid.pdfcraft.ui.viewer.PenDiag.flushPalm()
            Diagnostics.memory("share")
            val lines = withContext(Dispatchers.IO) { Diagnostics.lines(app) }
            if (lines.isEmpty()) {
                vm.tell(text(R.string.home_diagnostics_empty))
                return@launch
            }
            vm.sharing.handOut {
                val facts = Diagnostics.facts(app, vm.home.appVersion, io.github.artcraftandroid.pdfcraft.ui.home.installedVersionCode(app), io.github.artcraftandroid.pdfcraft.core.engineVersion())
                val report = io.github.artcraftandroid.pdfcraft.engine.diagReport(facts, lines)
                val file = withContext(Dispatchers.IO) {
                    File(File(app.cacheDir, "share").apply { mkdirs() }, "pdfcraft-diagnostics.txt").apply { writeText(report) }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                // the file only: a long log is too big for a message's text
                vm.sharing.sendIntent(listOf(uri), "text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, text(R.string.home_diagnostics_subject))
            }
        }
    }

    /** The viewer's keyboard shortcuts (Preferences; on by default: only a keyboard sends them, #153). */
    var keyboardShortcuts by mutableStateOf(appPrefs.getBoolean("keyboardShortcuts", true))
        internal set

    fun updateKeyboardShortcuts(on: Boolean) {
        keyboardShortcuts = on
        appPrefs.edit().putBoolean("keyboardShortcuts", on).apply()
    }

    /** The app's theme (Preferences ▸ Theme): the system's, or always light or dark. */
    var theme by mutableStateOf(runCatching { ThemeChoice.valueOf(appPrefs.getString("theme", "System") ?: "System") }.getOrDefault(ThemeChoice.System))
        internal set

    fun setThemeChoice(t: ThemeChoice) {
        theme = t
        appPrefs.edit().putString("theme", t.name).apply()
    }

    /**
     * Extra Features ▸ the status bar in documents: shown with the viewer's bars, and the page uses the
     * whole screen when they hide (off: the reference's black band where the camera is).
     */
    var statusBarInDocuments by mutableStateOf(appPrefs.getBoolean("statusBarInDocuments", false))
        internal set

    fun updateStatusBarInDocuments(on: Boolean) {
        statusBarInDocuments = on
        appPrefs.edit().putBoolean("statusBarInDocuments", on).apply()
    }
}

/** How [v] fits pages to the width, for the log (#524): `on` or `off` as chosen, else `auto_on` or `auto_off`. */
internal fun fitEachDiag(v: io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerState): String = when (v.fitEachChoice) {
    true -> "on"
    false -> "off"
    null -> if (v.fitEach) "auto_on" else "auto_off"
}

/** [spread] rounded to two places, for the log. */
internal fun widthSpreadDiag(spread: Float): Float = kotlin.math.round(spread * 100f) / 100f
