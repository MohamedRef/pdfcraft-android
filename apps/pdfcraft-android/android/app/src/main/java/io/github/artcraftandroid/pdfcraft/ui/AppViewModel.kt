package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewLayout
import io.github.artcraftandroid.pdfcraft.ui.viewer.recognizeText
import io.github.artcraftandroid.pdfcraft.ui.viewer.startCompare
import io.github.artcraftandroid.pdfcraft.ui.viewer.prepareGroupedText
import io.github.artcraftandroid.pdfcraft.ui.home.FileTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement
import androidx.compose.ui.graphics.asImageBitmap
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import io.github.artcraftandroid.pdfcraft.engine.review
import io.github.artcraftandroid.pdfcraft.engine.UserBookmark
import io.github.artcraftandroid.pdfcraft.engine.UserBookmarks
import io.github.artcraftandroid.pdfcraft.ui.viewer.NotePrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.VoicePrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.ReplyPrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.StyledTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextMarkup
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupArgs
import io.github.artcraftandroid.pdfcraft.ui.viewer.PendingMarkupPreview
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextPrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.ToolPrefs
import io.github.artcraftandroid.pdfcraft.ui.viewer.engineType
import io.github.artcraftandroid.pdfcraft.ui.viewer.hex
import android.content.ClipData
import android.content.Intent
import androidx.compose.runtime.snapshotFlow
import io.github.artcraftandroid.pdfcraft.ui.viewer.LinkFlash
import io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.ReadAloud
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.canProceedWithPdfOpen
import io.github.artcraftandroid.pdfcraft.PdfcraftApplication
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.core.MobileException
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.Diagnostics
import io.github.artcraftandroid.pdfcraft.engine.EmbeddedBookmarkJson
import io.github.artcraftandroid.pdfcraft.engine.SafBoundedCopy
import io.github.artcraftandroid.pdfcraft.engine.withTemporaryBookmarkSource
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.RecentFile
import io.github.artcraftandroid.pdfcraft.engine.RecentFiles
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageImages
import java.io.File
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A message for the snackbar, with an optional action. */
data class BatesExportResult(val name: String, val message: String, val uri: Uri?)

data class Message(val text: String, val actionLabel: String? = null, val action: (() -> Unit)? = null)

/** A document waiting for its password before it opens. */
data class PasswordRequest(
    val uri: Uri,
    val file: File,
    val wrong: Boolean,
    val fromOtherApp: Boolean = false,
    val requestId: Long = 0L,
)

/** Script alerts waiting at once, at most. */
internal const val MAX_SCRIPT_ALERTS = 20

/** The saved-state key of the scan in progress. */
private const val SCAN_STATE = "scan"

/**
 * The app's state: the engine, the open document, Home's lists, and the messages. [saved] keeps
 * what must outlive Android closing the app in the background (the scan in progress).
 */
class AppViewModel(app: Application, private val saved: SavedStateHandle) : AndroidViewModel(app) {
    private val application = app as PdfcraftApplication
    val files = DocumentFiles(app)
    val recents = RecentFiles(File(app.filesDir, "recent.json"))
    val notebooks = io.github.artcraftandroid.pdfcraft.engine.Notebooks(File(app.filesDir, "notebooks.json"))
    val notebookTemplates = io.github.artcraftandroid.pdfcraft.engine.NotebookTemplates(File(app.filesDir, "templates"))

    var engine by mutableStateOf<Engine?>(null)
        private set
    var engineProblem by mutableStateOf<String?>(null)
        private set

    /** The document in the viewer, if one is open (the tab in front, see [tabs]). */
    var document by mutableStateOf<OpenDocument?>(null)
        internal set

    /** Other documents open in tabs, and split view (#138 #139). */
    val tabs: TabsController by lazy { TabsController(this) }

    var recentList by mutableStateOf<List<RecentFile>>(emptyList())
        private set

    var passwordRequest by mutableStateOf<PasswordRequest?>(null)
        private set

    /** A document is being opened (a spinner over Home). */
    var opening by mutableStateOf(false)
        private set

    val messages = Channel<Message>(Channel.BUFFERED)

    /** System pickers the editing tools ask the activity for (a picture, files, a folder, a place to save). */
    val requests = FileRequests()

    /** Avoid overlapping onPause, close, and manual saves to a single provider URI. */
    internal val saveMutex = Mutex()

    /** Cache a bounded fraction of the actual VM heap, including on large-heap devices. */
    private val imageBudget = runtimeImageBudget(Runtime.getRuntime().maxMemory())

    init {
        // temporary files a crash or a killed process left in the cache (#105)
        viewModelScope.launch(Dispatchers.IO) { sweepCache() }
        viewModelScope.launch {
            application.engine.await().onSuccess { engine = it }.onFailure { engineProblem = it.userMessage }
            refreshRecents()
            // the signers the reader trusts, before any signature is checked
            // (a document opened from another app meanwhile is checked again)
            engine?.let { eng ->
                if (withContext(Dispatchers.IO) { signing.trustedCerts.files().isNotEmpty() }) {
                    runCatching { signing.applyTrust(eng) }
                    document?.let { signing.loadSignatures(eng, it) }
                }
            }
        }
    }

    fun tell(text: String, actionLabel: String? = null, action: (() -> Unit)? = null) {
        messages.trySend(Message(text, actionLabel, action))
    }

    fun refreshRecents() {
        viewModelScope.launch {
            val open = document?.source?.toString()
            val started = android.os.SystemClock.elapsedRealtime()
            // documents open in other tabs keep their access and working copies (#138)
            val tabUris = tabs.sources()
            val (list, places) = withContext(Dispatchers.IO) {
                val list = recents.list().also { list ->
                    val uris = list.map { it.uri }
                    // access to documents that left Recent is given back (#108), before the copies
                    // are pruned, so a document whose grant goes keeps its copy
                    runCatching { files.releaseUnusedAccess(uris + tabUris + study.library.load().items.map { it.uri }, open) }
                    // copies of documents that left Recent go too (#42)
                    runCatching { files.pruneCopies(uris, open, tabUris) }
                }
                // each row's thumbnail source lists a folder and its location may ask the package
                // manager: worked out here, never while Home composes (#94)
                list to list.associate { it.uri to (thumbPathFor(it.uri) to locationOf(it.uri)) }
            }
            recentPlaces = places
            recentList = list
            // #94: the Home list's sources and locations, worked out off the main thread
            Diag.event(DiagArea.Home, "recents", "files" to list.size, "ms" to android.os.SystemClock.elapsedRealtime() - started)
        }
    }

    /** Opening is serial: multiple ACTION_VIEW deliveries must not share a working file or engine document. */
    internal val openMutex = Mutex()
    private var lastOpenRequest = 0L
    private var openStartedAt = 0L

    /** Shared gate for initial, native and cached-copy open paths. */
    private fun isCurrentOpenRequest(requestId: Long): Boolean = canProceedWithPdfOpen(
        requestId, lastOpenRequest, readOnlyPrompt != null, resolvingReadOnly,
    )

    /** Fires when a document another app opened is closed: the activity finishes, back to that app. */
    val leave = Channel<Unit>(Channel.CONFLATED)

    /**
     * Open the PDF at [uri] (picked, shared, or from Recent), asking for its password if it has one.
     * [fromOtherApp]: another app opened it, so closing it goes back to that app rather than Home,
     * as the reference does (#47).
     */
    fun open(uri: Uri, password: String? = null, fromOtherApp: Boolean = false) {
        // a file opened from another app comes to the front, not under Settings
        settingsOpen = false
        showLicences = false
        // The same URI may already be open with unsaved edits. Never re-copy the provider
        // over its live Rust working file before those edits can be saved.
        if (document?.source == uri) {
            if (fromOtherApp) document?.fromOtherApp = true
            return
        }
        // already open in another tab (#138): bring that tab to the front instead
        tabs.tabs.firstOrNull { it.doc != null && it.doc?.source == uri }?.let { tab ->
            if (fromOtherApp) tab.doc?.fromOtherApp = true
            tabs.switchTo(tab)
            return
        }
        // Never replace the document while its unsaved write-back choice is unresolved.
        // Otherwise a rapid second Open with could bypass Save a copy / Discard.
        if (readOnlyPrompt != null || resolvingReadOnly) return
        val requestId = ++lastOpenRequest
        // Lock the entire copy/open/replace transaction. A newer external request may arrive
        // while the previous provider streams slowly; only the latest waiting one opens.
        viewModelScope.launch {
          openMutex.withLock {
            if (!isCurrentOpenRequest(requestId)) return@withLock
            val eng = engine ?: application.engine.await().getOrNull() ?: run { tell(engineProblem ?: text(R.string.msg_engine_not_started)); return@withLock }
            // An older request can become obsolete while waiting for the native engine.
            if (!isCurrentOpenRequest(requestId)) return@withLock
            openStartedAt = android.os.SystemClock.elapsedRealtime()
            diagDoc = Diag.nextDocNumber()
            // a number per open, never the file's name (#105): the lines after it are about this file
            Diag.event(DiagArea.Open, "open_start", "doc" to diagDoc, "scheme" to uri.scheme, "from_other_app" to fromOtherApp, "has_password" to (password != null))
            opening = true
            var openStage = "provider_copy"
            try {
                val file = io.github.artcraftandroid.pdfcraft.engine.OpenTiming.measure("provider_and_working_copy") { withContext(Dispatchers.IO) {
                    files.keepAccess(uri)
                    files.workingCopy(uri)
                } }
                io.github.artcraftandroid.pdfcraft.engine.OpenTiming.milestone("working_copy_ready", openStartedAt, bytes = file.length())
                // Providers may stream a cloud PDF for seconds. Do not replace a live viewer
                // with that obsolete result when the user has since opened another document.
                if (!isCurrentOpenRequest(requestId)) return@withLock
                openStage = "native_open"
                openFile(eng, uri, file, password, fromOtherApp, requestId)
            } catch (e: java.io.FileNotFoundException) {
                io.github.artcraftandroid.pdfcraft.engine.OpenTiming.failure(openStage, e)
                // Moved or deleted since it was opened, or a link a file manager lent that a
                // restart ended (#50; its provider no longer finds it): PdfCraft's own copy opens
                // if it kept one, as below; otherwise it leaves Recent.
                val copy = withContext(Dispatchers.IO) { files.existingCopy(uri) }
                // A newer intent may arrive during a slow provider/cache fallback.
                // Do not resurrect an obsolete document or alter its Recent entry.
                if (!isCurrentOpenRequest(requestId)) return@withLock
                if (copy != null) {
                    openFile(eng, uri, copy, password, fromOtherApp, requestId)
                    tell(text(R.string.msg_opened_own_copy), text(R.string.common_save_copy)) { document?.let { saveCopyAs(it) } }
                } else {
                    withContext(Dispatchers.IO) { recents.remove(setOf(uri.toString())) }
                    refreshRecents()
                    tell(text(R.string.msg_file_moved))
                }
            } catch (e: SecurityException) {
                io.github.artcraftandroid.pdfcraft.engine.OpenTiming.failure(openStage, e)
                // A file another app lent for one visit (Files, Downloads: "Open with"): Android
                // takes that access back later, so Recent can't read it again. PdfCraft's own copy
                // opens instead, read-only (changes are offered as a copy, as for any file it
                // can't write to); the original needs opening again from the app it's in.
                val copy = withContext(Dispatchers.IO) { files.existingCopy(uri) }
                // A newer intent may arrive during a slow provider/cache fallback.
                // Do not resurrect an obsolete document or alter its Recent entry.
                if (!isCurrentOpenRequest(requestId)) return@withLock
                if (copy != null) {
                    openFile(eng, uri, copy, password, fromOtherApp, requestId)
                    tell(text(R.string.msg_opened_own_copy), text(R.string.common_save_copy)) { document?.let { saveCopyAs(it) } }
                } else {
                    tell(text(R.string.msg_file_access_lost))
                }
            } catch (e: Exception) {
                io.github.artcraftandroid.pdfcraft.engine.OpenTiming.failure(openStage, e)
                // An obsolete provider error must not interrupt the newer viewer.
                if (isCurrentOpenRequest(requestId)) tell(e.userMessage)
            } finally {
                opening = false
            }
          }
        }
    }

    /** The password dialog's answer: try again with [password], or give up (null). */
    fun answerPassword(password: String?) {
        val req = passwordRequest ?: return
        if (req.requestId != lastOpenRequest) {
            passwordRequest = null
            return
        }
        passwordRequest = null
        if (password == null) return
        // OK with nothing typed: the field turns red, as a wrong password does
        if (password.isEmpty()) {
            passwordRequest = PasswordRequest(req.uri, req.file, wrong = true, fromOtherApp = req.fromOtherApp, requestId = req.requestId)
            return
        }
        val eng = engine ?: return
        viewModelScope.launch {
            openMutex.withLock {
                // A newer external intent supersedes the old password dialog.
                if (passwordRequest != null || req.requestId != lastOpenRequest) return@withLock
                openStartedAt = android.os.SystemClock.elapsedRealtime()
                opening = true
                try {
                    openFile(eng, req.uri, req.file, password, req.fromOtherApp, req.requestId)
                } catch (e: Exception) {
                    tell(e.userMessage)
                } finally {
                    opening = false
                }
            }
        }
    }

    private suspend fun openFile(eng: Engine, uri: Uri, file: File, password: String?, fromOtherApp: Boolean, requestId: Long) {
        // Covers password retry and all provider fallback paths too.
        if (!isCurrentOpenRequest(requestId)) return
        try {
            document?.let { current ->
                // the document in front stays open in its tab, saved first (#138)
                tabs.stash(eng, current)
                io.github.artcraftandroid.pdfcraft.engine.OpenTiming.milestone("previous_document_closed", openStartedAt)
                document = null
            }
            val recent = withContext(Dispatchers.IO) { recents.list().firstOrNull { it.uri == uri.toString() } }
            val progressive = file.length() >= 16L * 1024L * 1024L && recent?.reading != true && pendingTool == null
            val info = io.github.artcraftandroid.pdfcraft.engine.OpenTiming.measure("native_open") {
                eng.command {
                    if (progressive) {
                        val opened = beginOpen(file.absolutePath, password, (recent?.lastPage ?: 0).coerceAtLeast(0).toUInt(), io.github.artcraftandroid.pdfcraft.engine.OpenTiming.enabled())
                        opened.timings.forEach { io.github.artcraftandroid.pdfcraft.engine.OpenTiming.duration(it.stage, it.durationMs.toLong()) }
                        opened.document
                    } else if (io.github.artcraftandroid.pdfcraft.engine.OpenTiming.enabled()) {
                        val opened = openWithTimings(file.absolutePath, password)
                        // These are completed nested/parallel stages, not a sequential timeline.
                        opened.timings.forEach {
                            io.github.artcraftandroid.pdfcraft.engine.OpenTiming.duration(it.stage, it.durationMs.toLong())
                        }
                        opened.document
                    } else {
                        open(file.absolutePath, password)
                    }
                }
            }
            if (!isCurrentOpenRequest(requestId)) {
                eng.command { close(info.id, true) }
                return
            }
            io.github.artcraftandroid.pdfcraft.engine.OpenTiming.milestone(if (info.preparing) "native_reading_ready" else "native_ready", openStartedAt, bytes = file.length(), pages = info.pages.size)
            Diagnostics.memory(if (info.preparing) "native_reading_ready" else "native_ready")
            // This counts editing-parser repairs only; renderer fallback work has its own trace.
            if (!info.preparing && io.github.artcraftandroid.pdfcraft.engine.OpenTiming.enabled()) {
                runCatching { eng.command { repairCount(info.id) } }.getOrNull()
                    ?.let { io.github.artcraftandroid.pdfcraft.engine.OpenTiming.count("native_repairs", it.toLong()) }
            }
            val images = PageImages(eng, info.id, viewModelScope, imageBudget, openedAt = openStartedAt, compact = compactPreviews)
            val source = uri.takeIf { it.scheme == "content" || it.scheme == "file" }
            val doc = OpenDocument(info.id, info, source, file, images, password, eng, viewModelScope)
            doc.fromOtherApp = fromOtherApp
            doc.diagNumber = diagDoc
            doc.writable = withContext(Dispatchers.IO) { source == null || files.canWrite(source) }
            doc.notWrittenBack = source?.let { withContext(Dispatchers.IO) { files.hasPendingWriteBack(it) } } ?: false
            io.github.artcraftandroid.pdfcraft.engine.OpenTiming.milestone("source_access_ready", openStartedAt)
            doc.controller.nightMode = savedNightMode
            // the layout this file was left in, else the one the file asks for, else the app's (#152)
            val remembered = ViewLayout.decode(recent?.layout)
            val fromFile = if (remembered == null && recent?.layout == null && !info.preparing) fileLayout(eng, info.id) else null
            applyLayout(doc, remembered ?: fromFile, if (remembered != null) "remembered" else "file")
            // a file left in Reading Mode reopens in it, unless it was opened for a tool
            readingMode = recent?.reading == true && pendingTool == null
            document = doc
            tabs.opened(doc, doc.title)
            io.github.artcraftandroid.pdfcraft.engine.OpenTiming.milestone("viewer_model_ready", openStartedAt, pages = info.pages.size)
            if (!info.preparing) {
                viewModelScope.launch {
                    doc.images.awaitFirstCanvas()
                    if (document === doc) {
                        loadLinks(eng, doc)
                        if (doc.info.signed) signing.announceSignatures(doc)
                        startPendingTool(doc)
                    }
                }
            } else {
                completeReadingOpen(eng, doc, requestId, openStartedAt)
            }
            // a link from another PDF names the page to open on (#334)
            val linkPage = linkOpenPage?.takeIf { it.first == uri.toString() }?.second?.takeIf { it in 0 until info.pages.size }
            linkOpenPage = null
            val linkArea = linkOpenArea.takeIf { linkPage != null }
            linkOpenArea = null
            pendingPage = linkPage ?: recent?.lastPage?.takeIf { it in 0 until info.pages.size }
            // a back-link to an area (#379): scroll there and flash it once the pages are laid out
            if (linkPage != null && linkArea != null) viewModelScope.launch {
                doc.images.awaitFirstCanvas()
                if (document === doc) tabs.showArea(doc, linkPage, linkArea)
            }
            pendingSpot = recent?.takeIf { linkPage == null && pendingPage != null && it.lastX >= 0f && it.lastY >= 0f }?.let { Triple(it.lastX, it.lastY, it.lastZoom) }
            withContext(Dispatchers.IO) {
                recents.touch(RecentFile(uri.toString(), info.name, System.currentTimeMillis(), info.pages.size, file.length(), recent?.lastPage ?: 0))
            }
            refreshRecents()
        } catch (e: MobileException.NeedsPassword) {
            passwordRequest = PasswordRequest(uri, file, wrong = false, fromOtherApp = fromOtherApp, requestId = requestId)
        } catch (e: MobileException.WrongPassword) {
            passwordRequest = PasswordRequest(uri, file, wrong = true, fromOtherApp = fromOtherApp, requestId = requestId)
        } catch (e: MobileException) {
            tell(e.userMessage)
        }
    }

    /** Finish away from the serial command dispatcher, after the first canvas submission.
     * Identity checks protect close, replacement and late native results. */
    private fun completeReadingOpen(eng: Engine, doc: OpenDocument, requestId: Long, startedAt: Long) {
        viewModelScope.launch {
            try {
                doc.images.awaitFirstCanvas()
                if (document !== doc || !isCurrentOpenRequest(requestId)) return@launch
                val complete = eng.completeOpen(doc.id)
                if (document !== doc || !isCurrentOpenRequest(requestId)) return@launch
                complete.timings.forEach { io.github.artcraftandroid.pdfcraft.engine.OpenTiming.duration(it.stage, it.durationMs.toLong()) }
                io.github.artcraftandroid.pdfcraft.engine.OpenTiming.milestone("native_ready", startedAt, pages = complete.document.pages.size)
                doc.update(complete.document)
                loadLinks(eng, doc)
                io.github.artcraftandroid.pdfcraft.engine.OpenTiming.milestone("overlays_ready", startedAt, pages = doc.pageCount)
                Diagnostics.memory("overlays_ready")
                if (io.github.artcraftandroid.pdfcraft.engine.OpenTiming.enabled()) {
                    runCatching { eng.command { repairCount(doc.id) } }.getOrNull()?.let {
                        io.github.artcraftandroid.pdfcraft.engine.OpenTiming.count("native_repairs", it.toLong())
                    }
                }
                if (doc.info.signed) signing.announceSignatures(doc)
                startPendingTool(doc)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (document === doc && isCurrentOpenRequest(requestId)) {
                    tell(e.userMessage)
                    closeQuietly(eng, doc)
                    document = null
                    readingMode = false
                    tabs.closed(doc)
                }
            }
        }
    }

    override fun onCleared() {
        stopLecture()
        closeLecturePlayback()
        document?.takeIf { it.info.preparing }?.let { doc ->
            runCatching { engine?.session?.close(doc.id, true) }
            doc.images.close()
        }
        super.onCleared()
    }

    /** The page to show once the viewer has laid the document out (reopening where the reader left off). */
    var pendingPage: Int? = null

    /** With [pendingPage]: the spot (points) at the screen's top-left and the zoom it was left at. */
    var pendingSpot: Triple<Float, Float, Float>? = null

    /** Intents for the activity to start (share, open a link): the view model has no activity. */
    val intents = Channel<Intent>(Channel.BUFFERED)

    /** Print jobs for the activity. */
    val prints = Channel<PrintJob>(Channel.BUFFERED)

    /** Remove stale temporary files from the cache (#105). */
    private fun sweepCache() {
        runCatching { io.github.artcraftandroid.pdfcraft.engine.CacheCleanup.sweep(getApplication<Application>().cacheDir) }
        // update APKs already installed or superseded (#109)
        runCatching {
            val app = getApplication<Application>()
            io.github.artcraftandroid.pdfcraft.ui.home.cleanUpdateFiles(app, io.github.artcraftandroid.pdfcraft.ui.home.installedVersionCode(app), keepSha256 = null)
        }
    }

    /** A document that couldn't be saved back to where it came from, waiting for Save a copy or Discard. */
    var readOnlyPrompt by mutableStateOf<OpenDocument?>(null)
        private set

    /** Blocks new external opens while an unresolved Save a copy or Discard is in flight. */
    private var resolvingReadOnly = false

    /** Leave the viewer: changes are saved first, as the reference does (no prompt). */
    fun closeDocument() {
        closeLecturePlayback()
        val doc = document ?: return
        study.pauseStudy()
        study.studyDoc = null
        val eng = engine ?: return
        if (doc.controller.tool != ViewerTool.None) leaveTool(doc)
        viewModelScope.launch {
            openMutex.withLock {
                if (document !== doc) return@withLock
                // Opening a second document and closing the first must not race native handles.
                if (!saveAndClose(eng, doc)) {
                    readOnlyPrompt = doc
                    return@withLock
                }
                document = null
                readingMode = false
                val next = tabs.closed(doc)
                refreshRecents()
                study.indexLibrary()
                withContext(Dispatchers.IO) { sweepCache() }
                if (doc.fromOtherApp) leave.trySend(Unit) else showTab(next)
            }
        }
    }

    /** Resolve an unsaved write-back prompt without racing an incoming Open with. */
    fun answerReadOnly(saveCopy: Boolean?) {
        val doc = readOnlyPrompt ?: return
        if (saveCopy == null) {
            readOnlyPrompt = null
            return
        }
        val eng = engine ?: return
        readOnlyPrompt = null
        resolvingReadOnly = true
        if (saveCopy) {
            saveCopyAs(doc, then = {
                // The saved copy finished. Close only after excluding concurrent opens.
                viewModelScope.launch {
                    try {
                        openMutex.withLock {
                            if (document !== doc) return@withLock
                            closeQuietly(eng, doc)
                            document = null
                            val next = tabs.closed(doc)
                            refreshRecents()
                            if (doc.fromOtherApp) leave.trySend(Unit) else showTab(next)
                        }
                    } finally {
                        resolvingReadOnly = false
                    }
                }
            })
        } else {
            viewModelScope.launch {
                try {
                    openMutex.withLock {
                        if (document !== doc) return@withLock
                        // Explicit Discard, never automatic cleanup: preserve file recovery
                        // until the user confirms and the native document has closed.
                        closeQuietly(eng, doc)
                        doc.source?.let { src ->
                            val discarded = withContext(Dispatchers.IO) {
                                runCatching { files.discardWorkingCopy(src) }
                                    .onFailure { tell(it.userMessage) }
                                    .getOrDefault(false)
                            }
                            // The original may be truncated: the edited copy stays for the next open.
                            if (!discarded) tell(text(R.string.discard_kept_recovery))
                        }
                        document = null
                        val next = tabs.closed(doc)
                        refreshRecents()
                        if (doc.fromOtherApp) leave.trySend(Unit) else showTab(next)
                    }
                } finally {
                    resolvingReadOnly = false
                }
            }
        }
    }

    /** After the document in front closed: the next tab comes to the front (a parked one opens again). */
    private fun showTab(next: DocTab?) {
        next ?: return
        val d = next.doc
        if (d != null) tabs.front(next, d) else open(Uri.parse(next.key))
    }

    /** [reading] and [pending] are the front document's unless a background tab is closed (#138). */
    internal suspend fun saveAndClose(eng: Engine, doc: OpenDocument, reading: Boolean = readingMode, pending: Int? = pendingPage): Boolean {
        // the spot at the screen's top-left and the zoom, to reopen there (as the reference does)
        val v = doc.viewer
        val spot = if (pending == null && v.mode == io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.Continuous) v.screenToPage(androidx.compose.ui.geometry.Offset(0f, v.padTop), nearest = true) else null
        val zoom = v.zoom
        val layout = currentLayout(doc).encode()
        withContext(Dispatchers.IO) {
            // in Reading Mode the page canvas has no layout: the page read is the pending one
            val page = pending ?: spot?.page ?: v.currentPage
            doc.source?.let { src ->
                recents.update(src.toString()) {
                    it.copy(lastPage = page, reading = reading, lastX = spot?.x ?: -1f, lastY = spot?.y ?: -1f, lastZoom = if (spot != null) zoom else 1f, layout = layout)
                }
            }
        }
        if (!save(doc, quiet = true, trigger = "close")) return false
        // Recent shows the size the file has now, not the size it had when opened
        withContext(Dispatchers.IO) {
            doc.source?.let { src -> files.size(src)?.let { size -> recents.update(src.toString()) { it.copy(size = size) } } }
        }
        closeQuietly(eng, doc)
        return true
    }

    private suspend fun closeQuietly(eng: Engine, doc: OpenDocument) {
        runCatching { eng.command { close(doc.id, true) } }
        doc.images.close()
        doc.search.clear()
        // the working copy isn't kept when the original can be read again (#42)
        doc.source?.let { src -> withContext(Dispatchers.IO) { runCatching { files.dropCopyIfReachable(src) } } }
    }

    /** The app went to the background: save the open document, as the reference does. */
    fun onPause() {
        study.pauseStudy()
        val doc = document ?: return
        if (Diag.enabled()) Diag.event(DiagArea.Save, "pause_with_document", "doc" to doc.diagNumber, "dirty" to doc.info.dirty, "not_written_back" to doc.notWrittenBack, "preparing" to doc.info.preparing, "bytes" to doc.file.length())
        if (doc.controller.tool == ViewerTool.Draw) doc.commitTool?.invoke()
        viewModelScope.launch {
            // A paused Activity must not save a closed/replaced engine document.
            // Use the same lock as Open with and Close before touching native handles.
            openMutex.withLock {
                if (document === doc && readOnlyPrompt == null && !resolvingReadOnly) save(doc, trigger = "pause")
            }
        }
    }

    /**
     * Save [doc]'s changes: into its working file (incrementally, so earlier signatures stay
     * valid), then back over the file it came from when the app may write there. Returns
     * whether the changes reached the original; [quiet] leaves a failure's message to the caller.
     */
    suspend fun save(doc: OpenDocument, quiet: Boolean = false, trigger: String = "other"): Boolean = saveMutex.withLock {
        val eng = engine ?: return@withLock false
        // A reading stage has no edits; do not write an incompletely checked source back.
        if (doc.info.preparing) return@withLock true
        if (!doc.info.dirty && !doc.notWrittenBack) return@withLock true
        doc.saving = true
        // #97: what each save costs, and what started it (pause, close, an edit, a tool)
        val started = android.os.SystemClock.elapsedRealtime()
        var stage = "mark"
        var outcome = "saved"
        try {
            val src = doc.source
            // Mark before the working copy changes (#103): a process killed between doc_save and
            // the write-back must reopen the saved working copy, not copy the old original over it.
            markBeforeWorkingCopyChanges(doc)
            stage = "doc_save"
            if (doc.info.dirty) Diag.measure(DiagArea.Save, "doc_save", "doc" to doc.diagNumber) { eng.tool("doc_save", mapOf("doc" to doc.id)) }
            stage = "refresh"
            Diag.measure(DiagArea.Save, "refresh", "doc" to doc.diagNumber) { refresh(doc) }
            // a copy for the backup folder, even when writing back fails (#145)
            stage = "backup"
            Diag.measure(DiagArea.Save, "backup_snapshot", "doc" to doc.diagNumber) { backups.snapshotForBackup(eng, doc) }
            stage = "write_back"
            if (src != null && src.scheme == "content") {
                // The marker set above stays until the provider verifiably holds the new bytes.
                if (!doc.writable || doc.holdWriteBack) {
                    doc.notWrittenBack = true
                    outcome = if (doc.writable) "held" else "read_only"
                    return@withLock false
                }
                Diag.measure(DiagArea.Save, "write_back", "doc" to doc.diagNumber, "bytes" to doc.file.length(), "target" to "provider") {
                    withContext(Dispatchers.IO) {
                        files.writeBack(doc.file, src)
                        files.clearPendingWriteBack(src)
                    }
                }
                doc.wroteOriginal = true
            } else if (src != null && src.scheme == "file") {
                val target = File(requireNotNull(src.path))
                if (target.canonicalPath != doc.file.canonicalPath) {
                    if (doc.holdWriteBack) {
                        doc.notWrittenBack = true
                        outcome = "held"
                        return@withLock false
                    }
                    Diag.measure(DiagArea.Save, "write_back", "doc" to doc.diagNumber, "bytes" to doc.file.length(), "target" to "file") {
                        withContext(Dispatchers.IO) {
                            doc.file.copyTo(target, overwrite = true)
                            files.clearPendingWriteBack(src)
                        }
                    }
                }
                // a file edited in place: the save above already changed it
                doc.wroteOriginal = true
            }
            doc.notWrittenBack = false
            true
        } catch (e: Exception) {
            outcome = "failed"
            Diag.failure(DiagArea.Save, stage, e)
            doc.writable = false
            doc.notWrittenBack = true
            if (!quiet) tell(e.userMessage)
            false
        } finally {
            doc.saving = false
            Diag.event(DiagArea.Save, "save", "doc" to doc.diagNumber, "trigger" to trigger, "outcome" to outcome, "ms" to android.os.SystemClock.elapsedRealtime() - started)
        }
    }

    /** The top bar's save state, tapped: save now, or Save a copy when the original can't be written (#190). */
    fun saveNow(doc: OpenDocument) {
        if (doc.saving) return
        if (!doc.writable && doc.source != null) {
            saveCopyAs(doc)
            return
        }
        viewModelScope.launch {
            openMutex.withLock {
                if (document === doc && readOnlyPrompt == null && !resolvingReadOnly && save(doc, trigger = "save_now")) tell(text(R.string.save_state_saved))
            }
        }
    }

    /**
     * Persist the write-back marker for [doc]'s source before its working copy is changed, so a
     * reopen after a crash keeps the changed copy (#103). Nothing to protect when the working
     * copy is the document's own file.
     */
    internal suspend fun markBeforeWorkingCopyChanges(doc: OpenDocument) {
        val src = doc.source ?: return
        val separate = when (src.scheme) {
            "content" -> true
            "file" -> File(requireNotNull(src.path)).canonicalPath != doc.file.canonicalPath
            else -> false
        }
        if (separate) withContext(Dispatchers.IO) { files.markPendingWriteBack(src, doc.file) }
    }

    /** Read the document's state from the engine again (after an edit, undo or save). */
    suspend fun refresh(doc: OpenDocument, touchedPage: Int? = null) {
        val eng = engine ?: return
        val info = runCatching { eng.command { document(doc.id) } }.getOrNull() ?: return
        val changed = info.generation != doc.info.generation
        doc.update(info, touchedPage)
        if (changed) loadLinks(eng, doc)
    }

    private suspend fun loadLinks(eng: Engine, doc: OpenDocument) {
        if (document !== doc || doc.info.preparing) return
        val generation = doc.info.generation
        loadVisibleLinks(doc, doc.viewer.visiblePages())
        // Keep global field navigation complete, but only after the first page is available.
        loadAllWidgets(doc)
        if (document !== doc || generation != doc.info.generation) return
        io.github.artcraftandroid.pdfcraft.engine.OpenTiming.measure("signatures") { signing.loadSignatures(eng, doc) }
    }

    suspend fun loadAllWidgets(doc: OpenDocument) {
        if (document !== doc || doc.info.preparing || doc.widgetsComplete) return
        val eng = engine ?: return
        val generation = doc.info.generation
        val result = try { eng.command { widgets(doc.id) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { return }
        if (document !== doc || generation != doc.info.generation) return
        doc.widgets = result
        doc.widgetsComplete = true
    }

    private suspend fun loadVisibleOverlays(doc: OpenDocument, visible: IntRange) {
        if (document !== doc || doc.info.preparing) return
        val eng = engine ?: return
        val generation = doc.info.generation
        while (document === doc && generation == doc.info.generation) {
            val missing = missingVisibleLinkPages(visible, doc.pageCount, doc.overlayPagesLoaded)
            if (missing.isEmpty()) return
            val result = io.github.artcraftandroid.pdfcraft.engine.OpenTiming.measure("visible_overlays") {
                eng.command { pageOverlays(doc.id, missing.map { it.toUInt() }) }
            }
            if (document !== doc || generation != doc.info.generation || result.generation != generation) return
            val pages = result.pages.toSet()
            if (!doc.commentsComplete) doc.comments = (doc.comments.filter { it.page !in pages } + result.comments).sortedWith(compareBy({ it.page }, { it.index }))
            if (!doc.widgetsComplete) doc.widgets = doc.widgets.filter { it.page !in pages } + result.widgets
            doc.overlayPagesLoaded.addAll(missing)
            kotlinx.coroutines.yield()
        }
    }

    /** Resolve only the displayed PDF pages' links without blocking the opening screen. */
    suspend fun loadVisibleLinks(doc: OpenDocument, visible: IntRange) {
        if (document !== doc || doc.info.preparing) return
        val eng = engine ?: return
        val generation = doc.info.generation
        try { loadVisibleOverlays(doc, visible) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Exception) { return }
        while (document === doc && doc.info.generation == generation) {
            val missing = missingVisibleLinkPages(visible, doc.pageCount, doc.linkPagesLoaded)
            if (missing.isEmpty()) return
            val result = try {
                io.github.artcraftandroid.pdfcraft.engine.OpenTiming.measure("visible_links") {
                    eng.command { missing.associateWith { p -> links(doc.id, p.toUInt()) } }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) { return }
            if (document !== doc || doc.info.generation != generation) return
            doc.links = doc.links + result
            doc.linkPagesLoaded.addAll(result.keys)
            kotlinx.coroutines.yield()
        }
    }

    /**
     * Run engine tool [name] on [doc] (its `doc` argument is filled in), then refresh. [onDone]
     * gets the result; a refusal is told in a snackbar.
     */
    fun runTool(doc: OpenDocument, name: String, args: Map<String, Any?> = emptyMap(), onSettled: (() -> Unit)? = null, onDone: (JSONObject) -> Unit = {}) {
        val eng = engine ?: return
        viewModelScope.launch {
            try {
                val start = System.nanoTime()
                val r = eng.tool(name, args + ("doc" to doc.id))
                if (name == "comment_add") study.studyNote(doc)
                if (name == "comment_add") lectureNoted(doc)
                refresh(doc, io.github.artcraftandroid.pdfcraft.ui.viewer.pageTouchedBy(name, args))
                // organize (#165): each page edit with the pages it touched and the time to the redrawn view
                if (name.startsWith("page_") && Diag.enabled()) {
                    Diag.event(DiagArea.Pages, "page_edit", "doc" to doc.diagNumber, "tool" to name, "pages" to ((args["pages"] as? List<*>)?.size ?: -1),
                        "page_count" to doc.pageCount, "ms" to (System.nanoTime() - start) / 1_000_000)
                }
                onDone(r)
            } catch (e: MobileException) {
                tell(e.userMessage)
            } finally {
                onSettled?.invoke()
            }
        }
    }

    /**
     * Apply [groups] of same-kind edits in submission order with one refresh. Pressure-aware ink
     * may create several PDF annotations, so one refresh per short segment would stall the viewer.
     * Each group is all or nothing (see [Engine.toolBatch]); [onSettled] gets how many groups the
     * document accepted, after the refresh, so the caller keeps the rest (#73). No engine: none.
     */
    fun runToolBatch(doc: OpenDocument, name: String, groups: List<List<Map<String, Any?>>>, onSettled: (applied: Int) -> Unit = {}) {
        if (groups.isEmpty()) return onSettled(0)
        val eng = engine ?: return onSettled(0)
        val all = groups.flatten()
        viewModelScope.launch {
            var applied = 0
            try {
                // Keep all adjacent pressure widths contiguous on the engine's serial queue.
                applied = eng.toolBatch(name, groups.map { group -> group.map { it + ("doc" to doc.id) } })
            } catch (e: io.github.artcraftandroid.pdfcraft.engine.ToolBatchException) {
                applied = e.applied
                tell(e.cause.userMessage)
            } catch (e: MobileException) {
                tell(e.userMessage)
            } finally {
                // A partially accepted batch must be visible even if a later group is refused.
                try {
                    refresh(doc, io.github.artcraftandroid.pdfcraft.ui.viewer.pageTouchedByBatch(name, all))
                    if (name == "comment_add" && applied > 0) lectureNoted(doc)
                } finally {
                    onSettled(applied)
                }
            }
        }
    }

    /** Undo: inside a tool that keeps its own history (Draw) only that; else the document's last edit, named in a message. */
    fun undo(doc: OpenDocument) {
        doc.localUndo?.let { it.undo(); return }
        undoDocument(doc, announce = true)
    }

    fun redo(doc: OpenDocument) {
        doc.localUndo?.let { it.redo(); return }
        redoDocument(doc, announce = true)
    }

    /**
     * Undo the document's last edit; [announce] names it in a message. The Draw tool undoes its
     * own saved ink quietly, but names an edit made before Draw, so it is never lost unseen (#78).
     */
    fun undoDocument(doc: OpenDocument, announce: Boolean) {
        val label = doc.info.undoLabel ?: return
        runTool(doc, "edit_undo") { if (announce) tell(text(R.string.msg_undone, label)) }
    }

    fun redoDocument(doc: OpenDocument, announce: Boolean) {
        val label = doc.info.redoLabel ?: return
        runTool(doc, "edit_redo") { if (announce) tell(text(R.string.msg_redone, label)) }
    }

    /** [count] Draw strokes the document refused stay drafts (#73); say where, once Draw is left. */
    fun inkKept(doc: OpenDocument, count: Int) {
        if (count > 0 && document === doc && doc.controller.tool != ViewerTool.Draw) tell(text(R.string.msg_ink_kept))
    }

    /** Switch the viewer to [tool], unless the document forbids what it does. */
    fun enterTool(doc: OpenDocument, tool: ViewerTool) {
        val ctl = doc.controller
        if (ctl.tool == tool) {
            leaveTool(doc)
            return
        }
        // as the reference: no tool works on reflowed text; say so and stay in Reading Mode
        if (readingMode && tool != ViewerTool.None) {
            readingModeBlocked = tool
            return
        }
        val info = doc.info
        val blocked = when (tool) {
            ViewerTool.Comment, ViewerTool.Markup, ViewerTool.Draw, ViewerTool.Text, ViewerTool.Measure -> !info.allowsAnnotation
            ViewerTool.FillSign -> !info.allowsFormFilling && !info.allowsAnnotation
            ViewerTool.Edit -> info.readOnlyReason != null || !info.allowsAssembly && info.encrypted
            ViewerTool.None -> false
        }
        if (blocked) {
            tell(info.readOnlyReason ?: text(R.string.msg_tool_blocked_security))
            return
        }
        if (ctl.tool != ViewerTool.None) leaveTool(doc)
        doc.selection.clear()
        // annotating with the annotations hidden would hide what is being made: show them again
        if (tool in setOf(ViewerTool.Comment, ViewerTool.Markup, ViewerTool.Draw, ViewerTool.Text) && doc.info.commentsHidden) setCommentsHidden(doc, false)
        ctl.switchTool(tool)
        if (tool == ViewerTool.Draw && toolPrefs.snapToShape && toolPrefs.takeSnapHint()) tell(text(R.string.msg_snap_to_shape_on))
        // no name dialog before the first mark (#202): the comment box offers "Comment as" instead
    }

    /** Asking for the name comments are signed with. */
    var authorPrompt by mutableStateOf(false)

    /** The first-run tips are showing (the first document, or Help ▸ Tips) (#202). */
    var tipsOpen by mutableStateOf(false)

    /** Leave the active tool: whatever it holds (ink being drawn) is added first. */
    fun leaveTool(doc: OpenDocument) {
        doc.commitTool?.invoke()
        doc.commitTool = null
        if (doc.controller.tool == ViewerTool.Edit) {
            editor.commitInlineEdit(doc)
            editor.editSelection = null
            editor.editImage = null
            editor.imageEdit.crop = null
            if (editor.objectSelect.doc === doc) {
                editor.objectSelect.doc = null
                editor.objectSelect.objects = emptyList()
                editor.objectSelect.selected = emptySet()
            }
            editor.editMode = io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.Select
        }
        if (signing.certPlacing === doc) signing.certPlacing = null
        if (linksAndMeasure.measure?.doc === doc) linksAndMeasure.measure = null
        doc.controller.tool = ViewerTool.None
    }

    /**
     * A tap at [at]: follow the link there, if any (a page of this document straight away, a
     * web address after asking). Returns whether there was one.
     */
    fun tapLink(doc: OpenDocument, at: PagePoint?): Boolean {
        if (at == null) return false
        val link = doc.links[at.page]?.firstOrNull { l ->
            l.rect.size == 4 && at.x in l.rect[0]..l.rect[2] && at.y in l.rect[1]..l.rect[3]
        } ?: return false
        doc.linkFlash = LinkFlash(at.page, link.rect.toFloatArray())
        viewModelScope.launch {
            delay(300)
            doc.linkFlash = null
        }
        val page = link.page
        val uri = link.uri
        val file = link.file
        when {
            file != null -> openLinkedFile(doc, file, link.filePage.toInt(), link.fileRect)
            page != null -> {
                doc.controller.rememberJump(doc.viewer)
                doc.viewer.goToPage(page.toInt(), link.top)
            }
            uri != null -> {
                val parsed = Uri.parse(uri)
                when (parsed.scheme?.lowercase()) {
                    "mailto", "tel" -> intents.trySend(Intent(Intent.ACTION_VIEW, parsed))
                    else -> linkPrompt = uri
                }
            }
        }
        return true
    }

    /** The page a link from another PDF asked for, kept until that file ([first], its URI) opens (#334). */
    private var linkOpenPage: Pair<String, Int>? = null

    /** With [linkOpenPage]: the area of that page a back-link fits (#379), shown once the file opens. */
    private var linkOpenArea: List<Float>? = null

    /**
     * PDFs a link can go to: the Library's files and the recent ones (not [exclude]), by their
     * names. Reads small files: call off the main thread.
     */
    fun linkableFiles(exclude: Uri?): List<io.github.artcraftandroid.pdfcraft.ui.viewer.LinkableFile> =
        (study.library.load().items.map { io.github.artcraftandroid.pdfcraft.ui.viewer.LinkableFile(it.uri, it.name) } +
            recents.list().map { io.github.artcraftandroid.pdfcraft.ui.viewer.LinkableFile(it.uri, it.name) })
            .distinctBy { it.uri }
            .filter { it.uri != exclude?.toString() && io.github.artcraftandroid.pdfcraft.ui.viewer.linkFileName(it.name) != null }

    /** Follow a link to page [page] (0-based) of the PDF named [file]: found by name among [linkableFiles]. */
    fun openLinkedFile(doc: OpenDocument, file: String, page: Int, area: List<Float>? = null) {
        viewModelScope.launch {
            val target = withContext(Dispatchers.IO) { io.github.artcraftandroid.pdfcraft.ui.viewer.linkedFileFor(file, linkableFiles(doc.source)) }
            io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.linkFollow(doc, found = target != null, page = page)
            if (target == null) {
                tell(text(R.string.link_file_missing, io.github.artcraftandroid.pdfcraft.ui.viewer.linkFileBaseName(file)))
                return@launch
            }
            // open in the other pane or another tab (#139 back-links): go there, nothing reopens
            if (tabs.followLink(target.uri, page, area)) return@launch
            linkOpenPage = target.uri to page
            linkOpenArea = area
            open(Uri.parse(target.uri))
        }
    }

    // ---- Link authoring, Measure: see LinkMeasureController ----

    val linksAndMeasure = LinkMeasureController(this)

    // ---- Opening links, sharing, presenting, keyboard, printing, copies, read aloud --------
    /** A web address waiting for "Open web page?". */
    var linkPrompt by mutableStateOf<String?>(null)
        internal set

    fun answerLink(open: Boolean) {
        val uri = linkPrompt ?: return
        linkPrompt = null
        if (open) intents.trySend(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    }

    /** Find ended: say so when nothing was found. */
    fun whenSearchDone(doc: OpenDocument) {
        viewModelScope.launch {
            snapshotFlow { doc.search.running }.first { !it }
            if (doc.search.hits.isEmpty() && doc.search.query.isNotEmpty()) {
                val eng = engine
                // pages spread over the whole document, not just its first few: a scan can start
                // with a typed cover, and a typed document can have scanned pages further on
                val sample = io.github.artcraftandroid.pdfcraft.ui.viewer.searchTextSample(doc.pageCount)
                val imagePages = if (eng == null) 0 else sample.count { p ->
                    runCatching { withContext(eng.commands) { eng.session.glyphCount(doc.id, p.toUInt()) } }.getOrDefault(1u) == 0u
                }
                when {
                    sample.isNotEmpty() && imagePages == sample.size ->
                        tell(text(R.string.msg_search_image_only), text(R.string.common_tool_recognize_text)) { recognizeText(doc) }
                    imagePages > 0 ->
                        tell(text(R.string.msg_search_no_matches_some_images, doc.search.query), text(R.string.common_tool_recognize_text)) { recognizeText(doc) }
                    else -> tell(text(R.string.msg_search_no_matches, doc.search.query))
                }
            }
        }
    }

    /** The Share sheet's choice last used, marked next time (#205). */
    val lastShareChoice: io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice
        get() = io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice.of(appPrefs.getString("shareChoice", null))

    /**
     * Share [doc] as [choice] picks (#205): the PDF as it is, a flattened copy (comments and
     * fields baked into the pages), the page in view as its own PDF, or pictures of pages (picked
     * in the page grid). The subject is the document's title only.
     */
    fun share(doc: OpenDocument, choice: io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice = io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice.Pdf) {
        appPrefs.edit().putString("shareChoice", choice.id).apply()
        Diag.event(DiagArea.Share, "share", "doc" to doc.diagNumber, "choice" to choice.id, "page" to doc.viewer.currentPage + 1, "pages" to doc.pageCount)
        if (doc.controller.tool == ViewerTool.Draw) doc.commitTool?.invoke()
        if (choice == io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice.Images) {
            exportImages(doc, lastImageFormat)
            return
        }
        val eng = engine ?: return
        val page = doc.viewer.currentPage
        handOut {
            // the ink is queued in the engine: reading the document back waits for it, so the save has it
            refresh(doc)
            save(doc)
            val (file, name) = when (choice) {
                io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice.Flattened -> {
                    // from a second, temporary engine document: the open one keeps its comments and fields
                    val out = tempFile("pdf")
                    val copy = eng.tool("doc_open", mapOf("path" to doc.file.absolutePath, "password" to doc.password)).optLong("doc")
                    try {
                        eng.tool("doc_flatten", mapOf("doc" to copy))
                        eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true))
                    } finally {
                        runCatching { eng.tool("doc_close", mapOf("doc" to copy, "discard_changes" to true)) }
                    }
                    out to text(R.string.share_name_flattened, doc.title)
                }
                io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice.Clean -> {
                    // the saved file opened again without comments and markups; this one keeps them (#155)
                    val copy = preparedCopy(doc, unlock = false, clean = true) ?: throw IllegalStateException(text(R.string.msg_clean_copy_failed))
                    copy to doc.name
                }
                io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice.Tape -> {
                    // tape made opaque on a "Tape" layer, in a temporary copy: the open document keeps it invisible (#350)
                    val out = tempFile("pdf")
                    val start = android.os.SystemClock.elapsedRealtime()
                    val copy = eng.tool("doc_open", mapOf("path" to doc.file.absolutePath, "password" to doc.password)).optLong("doc")
                    try {
                        val n = io.github.artcraftandroid.pdfcraft.ui.viewer.showTapeIn(eng, copy.toULong())
                        eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true))
                        io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.tapeShare(doc, n, android.os.SystemClock.elapsedRealtime() - start)
                    } finally {
                        runCatching { eng.tool("doc_close", mapOf("doc" to copy, "discard_changes" to true)) }
                    }
                    out to text(R.string.share_name_tape, doc.title)
                }
                io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice.Page -> {
                    val out = tempFile("pdf")
                    eng.tool("page_extract", mapOf("doc" to doc.id, "pages" to listOf(page + 1), "out" to out.absolutePath))
                    out to text(R.string.share_name_page, doc.title, page + 1)
                }
                else -> doc.file to doc.name
            }
            Diag.event(DiagArea.Share, "share_file", "doc" to doc.diagNumber, "choice" to choice.id, "bytes" to file.length())
            val uri = try {
                withContext(Dispatchers.IO) { files.shareable(file, name) }
            } finally {
                // the share folder holds its own copy; a temporary file goes now (#105)
                if (file != doc.file) file.delete()
            }
            sendIntent(listOf(uri)).putExtra(Intent.EXTRA_SUBJECT, doc.title)
        }
    }

    /**
     * Hand files to another app through the system's chooser: [make] copies them where other apps
     * may read them and returns the intent. A failure on the way, such as a file gone or a phone
     * too full for the copy, is told in a snackbar instead of closing the app.
     */
    private fun handOut(make: suspend () -> Intent) {
        viewModelScope.launch {
            val intent = try {
                make()
            } catch (e: Exception) {
                tell(e.userMessage)
                null
            } ?: return@launch
            intents.trySend(Intent.createChooser(intent, null))
        }
    }

    /** A send intent for [uris], with them as its clip too so the share sheet can show them. */
    internal fun sendIntent(uris: List<Uri>, mime: String = "application/pdf"): Intent {
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.clipData = ClipData(null, arrayOf(mime), ClipData.Item(uris.first())).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        return send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Present [doc]: one page at a time, full screen, with a laser pointer (#157). */
    fun startPresenting(doc: OpenDocument) {
        val ctl = doc.controller
        if (ctl.tool != ViewerTool.None) leaveTool(doc)
        readingMode = false
        doc.selection.clear()
        doc.search.clear()
        ctl.searching = false
        ctl.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.None
        ctl.modeBeforePresenting = doc.viewer.mode
        doc.viewer.switchMode(io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.SinglePage)
        ctl.presenting = true
        ctl.presentingSince = android.os.SystemClock.uptimeMillis()
        ctl.laserStrokes = 0
        ctl.inkStrokes = 0
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "present_start", "doc" to doc.diagNumber, "page" to doc.viewer.currentPage + 1, "pages" to doc.pageCount, "mode_before" to ctl.modeBeforePresenting)
        ctl.barsVisible = false
    }

    fun stopPresenting(doc: OpenDocument) {
        val ctl = doc.controller
        if (!ctl.presenting) return
        ctl.presenting = false
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(
            io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "present_end", "doc" to doc.diagNumber, "s" to (android.os.SystemClock.uptimeMillis() - ctl.presentingSince) / 1000,
            "laser_strokes" to ctl.laserStrokes, "ink_strokes" to ctl.inkStrokes, "page" to doc.viewer.currentPage + 1,
        )
        // the pointer and temporary ink belong to the presentation
        ctl.laser.points.clear()
        ctl.tempInk.strokes.clear()
        ctl.modeBeforePresenting?.let { doc.viewer.switchMode(it) }
        ctl.modeBeforePresenting = null
        ctl.barsVisible = true
    }

    /** The keyboard shortcuts list (Ctrl+/). */
    var shortcutsHelp by mutableStateOf(false)

    /**
     * Keys in the viewer (#153): tools (P pen, H highlight, E eraser, L lasso, T text, N note, S
     * shapes), Esc back to reading, Ctrl+Z / Ctrl+Shift+Z undo and redo, Ctrl+F find, Ctrl+B
     * bookmark, Delete the selected comment, 1-9 pen presets, [ and ] pen width, Page Up/Down and
     * Space to turn pages, the arrows to scroll, Ctrl+P print, Ctrl and + or − to zoom, Ctrl+/ the
     * list. Only keys no text field took reach here.
     */
    fun viewerKey(keyCode: Int, ctrl: Boolean, shift: Boolean = false): Boolean {
        val doc = document ?: return false
        // a presentation clicker works even with the shortcuts turned off
        if (!keyboardShortcuts && !doc.controller.presenting) return false
        if (toolScreen != null) return false
        // tabs (#138): Ctrl+Tab, Ctrl+Shift+Tab and Ctrl+W, in Reading Mode too
        if (ctrl && !doc.controller.presenting) tabs.key(keyCode, shift)?.let { return it }
        if (readingMode) return false
        val v = doc.viewer
        val ctl = doc.controller
        val center = androidx.compose.ui.geometry.Offset(v.viewW / 2f, v.viewH / 2f)
        // presenting: a clicker's keys turn pages (arrows, Page Up/Down, Space, Enter) and Esc ends it
        if (ctl.presenting && !ctrl) {
            // a clicker's key while presenting (#157): the key code is a number, never text
            io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "present_key", "doc" to doc.diagNumber, "key" to keyCode, "shift" to shift)
            when (keyCode) {
                android.view.KeyEvent.KEYCODE_ESCAPE -> stopPresenting(doc)
                android.view.KeyEvent.KEYCODE_DPAD_RIGHT, android.view.KeyEvent.KEYCODE_DPAD_DOWN, android.view.KeyEvent.KEYCODE_PAGE_DOWN,
                android.view.KeyEvent.KEYCODE_ENTER, android.view.KeyEvent.KEYCODE_N -> v.turnPage(1)
                android.view.KeyEvent.KEYCODE_SPACE -> v.turnPage(if (shift) -1 else 1)
                android.view.KeyEvent.KEYCODE_DPAD_LEFT, android.view.KeyEvent.KEYCODE_DPAD_UP, android.view.KeyEvent.KEYCODE_PAGE_UP,
                android.view.KeyEvent.KEYCODE_P -> v.turnPage(-1)
                else -> return false
            }
            return true
        }
        val drawing = ctl.tool == ViewerTool.Draw
        if (!ctrl && drawing) io.github.artcraftandroid.pdfcraft.ui.viewer.presetForKey(keyCode)?.let { return toolPrefs.applyInkPreset(it) }
        val shortcut = io.github.artcraftandroid.pdfcraft.ui.viewer.viewerShortcut(keyCode, ctrl, shift) ?: return false
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "shortcut", "doc" to doc.diagNumber, "action" to shortcut, "ctrl" to ctrl, "shift" to shift, "tool" to ctl.tool)
        fun tool(t: ViewerTool) { if (ctl.tool != t) enterTool(doc, t) }
        fun draw(eraser: Boolean = false, lasso: Boolean = false, shape: io.github.artcraftandroid.pdfcraft.ui.viewer.InkShapeMode = io.github.artcraftandroid.pdfcraft.ui.viewer.InkShapeMode.Freehand) {
            tool(ViewerTool.Draw)
            if (ctl.tool != ViewerTool.Draw) return
            ctl.eraserMode = eraser
            ctl.lassoMode = lasso
            ctl.inkShapeMode = shape
        }
        when (shortcut) {
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Pen -> { draw(); toolPrefs.selectWritingPen() }
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Highlighter -> { ctl.markup = io.github.artcraftandroid.pdfcraft.ui.viewer.TextMarkup.Highlight; tool(ViewerTool.Markup) }
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Eraser -> draw(eraser = true)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Lasso -> draw(lasso = true)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Text -> tool(ViewerTool.Text)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Note -> { ctl.commentMode = io.github.artcraftandroid.pdfcraft.ui.viewer.CommentMode.Text; tool(ViewerTool.Comment) }
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Shapes -> {
                // S again goes on to the next shape: line, rectangle, oval, arrow
                val shapes = listOf(
                    io.github.artcraftandroid.pdfcraft.ui.viewer.InkShapeMode.Line, io.github.artcraftandroid.pdfcraft.ui.viewer.InkShapeMode.Rectangle,
                    io.github.artcraftandroid.pdfcraft.ui.viewer.InkShapeMode.Oval, io.github.artcraftandroid.pdfcraft.ui.viewer.InkShapeMode.Arrow,
                )
                val at = if (drawing) shapes.indexOf(ctl.inkShapeMode) else -1
                draw(shape = shapes[(at + 1) % shapes.size])
            }
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Leave -> when {
                doc.selection.selection != null -> doc.selection.clear()
                commenting.selectedComment?.first === doc -> commenting.selectedComment = null
                ctl.sheet != io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.None -> ctl.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.None
                ctl.searching -> { doc.search.clear(); ctl.searching = false }
                ctl.tool != ViewerTool.None -> leaveTool(doc)
                else -> return false
            }
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Undo -> undo(doc)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Redo -> redo(doc)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Find -> {
                doc.selection.clear()
                ctl.searching = true
                ctl.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.None
            }
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Bookmark -> toggleBookmark(doc)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Delete -> {
                val c = commenting.selectedComment?.takeIf { it.first === doc }?.second ?: return false
                commenting.deleteComment(doc, c)
            }
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Thinner, io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Thicker -> {
                if (!drawing) return false
                val dir = if (shortcut == io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Thicker) 1 else -1
                toolPrefs.updateInkWidth(io.github.artcraftandroid.pdfcraft.ui.viewer.steppedInkWidth(toolPrefs.inkWidth, dir))
            }
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.NextPage -> v.turnPage(1)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.PreviousPage -> v.turnPage(-1)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.ScrollDown -> v.setScroll(v.scrollX, v.scrollY + v.viewH * 0.1f)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.ScrollUp -> v.setScroll(v.scrollX, v.scrollY - v.viewH * 0.1f)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.ScrollRight -> v.setScroll(v.scrollX + v.viewW * 0.1f, v.scrollY)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.ScrollLeft -> v.setScroll(v.scrollX - v.viewW * 0.1f, v.scrollY)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.ZoomIn -> v.zoomStep(1, center)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.ZoomOut -> v.zoomStep(-1, center)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Print -> print(doc)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Help -> shortcutsHelp = true
        }
        return true
    }

    /** Print [doc] through Android's print framework; with [clean], without its comments and markups (#155). */
    fun print(doc: OpenDocument, clean: Boolean = false) {
        if (!doc.info.allowsPrinting) {
            tell(text(R.string.msg_print_disabled))
            return
        }
        // The ink being drawn is printed too, as Share sends it (#79).
        if (doc.controller.tool == ViewerTool.Draw) doc.commitTool?.invoke()
        viewModelScope.launch {
            // the ink is queued in the engine: reading the document back waits for it, so the save has it
            refresh(doc)
            save(doc)
            // the system's printing can't open a password-protected file: print an unlocked copy
            val prepared = if (doc.info.encrypted || clean) preparedCopy(doc, unlock = doc.info.encrypted, clean = clean) else null
            if (clean && prepared == null) {
                tell(text(R.string.msg_clean_copy_failed))
                return@launch
            }
            // a copy, not the working file the app's pause may be saving (#79); either copy is
            // deleted when the print job finishes (the unlocked one has no password, #105)
            val copy = prepared ?: printSnapshot(doc)
            prints.trySend(PrintJob(copy ?: doc.file, doc.name, deleteWhenDone = copy != null))
        }
    }

    /**
     * A copy of [doc]'s working file for the print framework (#79): it reads its file on the main
     * thread while the print screen is open, and the app's pause saves the working file then.
     */
    private suspend fun printSnapshot(doc: OpenDocument): File? = saveMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { tempFile("pdf").also { out -> doc.file.copyTo(out, overwrite = true) } }.getOrNull()
        }
    }

    /**
     * A temporary copy of [doc]'s saved file, opened separately so the open document is untouched:
     * without its password ([unlock], for printing, #105) and without its comments and markups
     * ([clean], #155). Null when that can't be done.
     */
    private suspend fun preparedCopy(doc: OpenDocument, unlock: Boolean, clean: Boolean): File? {
        val eng = engine ?: return null
        val out = File(getApplication<Application>().cacheDir, "print-${System.nanoTime()}.pdf")
        val start = System.nanoTime()
        var removed = 0
        return runCatching {
            val copy = saveMutex.withLock {
                eng.tool("doc_open", mapOf("path" to doc.file.absolutePath, "password" to doc.password)).optLong("doc")
            }
            try {
                if (unlock) eng.tool("doc_unprotect", mapOf("doc" to copy))
                if (clean) {
                    val found = eng.tool("doc_hidden_info", mapOf("doc" to copy)).optJSONArray("categories")
                    val comments = (0 until (found?.length() ?: 0)).map { found!!.getJSONObject(it) }
                        .firstOrNull { it.optString("category") == "comments" }?.optInt("count") ?: 0
                    // the tool fails when there is nothing to remove: a file without comments is already clean
                    if (comments > 0) eng.tool("doc_remove_hidden", mapOf("doc" to copy, "categories" to listOf("comments")))
                    removed = comments
                }
                eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true))
            } finally {
                runCatching { eng.tool("doc_close", mapOf("doc" to copy, "discard_changes" to true)) }
            }
            out
        }.onFailure {
            out.delete()
            io.github.artcraftandroid.pdfcraft.engine.Diag.failure(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, if (clean) "clean_copy" else "unlocked_copy", it)
        }.also { r ->
            // a copy without comments (#155) or without its password: how long it took, how many comments went, how big it is
            io.github.artcraftandroid.pdfcraft.engine.Diag.event(
                io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "prepared_copy", "doc" to doc.diagNumber, "clean" to clean, "unlock" to unlock, "ok" to r.isSuccess,
                "ms" to (System.nanoTime() - start) / 1_000_000, "comments_removed" to removed, "bytes" to (r.getOrNull()?.length() ?: 0L),
            )
        }.getOrNull()
    }

    /** Where Save a copy is asked to write (the activity opens the system's file creator with this name). */
    val saveCopyRequests = Channel<String>(Channel.BUFFERED)

    /** What a Save a copy picker was opened for: an open document, or a file row in Home. */
    private sealed class CopyRequest {
        class Document(val doc: OpenDocument, val then: (() -> Unit)?) : CopyRequest()
        class Row(val row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) : CopyRequest()
    }

    /** The single Save a copy waiting for its picker (#104). */
    private val copyRequest = PendingRequest<CopyRequest>()

    private fun startCopyRequest(request: CopyRequest, name: String) {
        copyRequest.start(request)?.let(::abandonCopyRequest)
        saveCopyRequests.trySend(name)
    }

    /**
     * [request] ends without a copy (cancelled, its picker failed to open, or displaced): an
     * unsaved document's Save a copy or Discard choice comes back instead of being lost, and new
     * opens are no longer blocked by it.
     */
    private fun abandonCopyRequest(request: CopyRequest) {
        // changes kept back while the picker was open go to the original again at the next save
        if (request is CopyRequest.Document) {
            request.doc.holdWriteBack = false
            Diag.event(DiagArea.Save, "copy_cancel", "doc" to request.doc.diagNumber, "not_written_back" to request.doc.notWrittenBack)
        }
        if (request is CopyRequest.Document && request.then != null && resolvingReadOnly) {
            readOnlyPrompt = request.doc
            resolvingReadOnly = false
        }
    }

    /** The system file creator could not be opened for the waiting Save a copy. */
    fun saveCopyLaunchFailed() {
        copyRequest.take()?.let(::abandonCopyRequest)
    }

    /** Save a copy of [doc] under a name and place the user picks. */
    fun saveCopyAs(doc: OpenDocument, then: (() -> Unit)? = null) {
        // Opening the picker pauses the app: that save must not write the original (#190).
        doc.holdWriteBack = true
        Diag.event(DiagArea.Save, "copy_start", "doc" to doc.diagNumber, "dirty" to doc.info.dirty, "wrote_original" to doc.wroteOriginal, "resolving" to (then != null))
        startCopyRequest(CopyRequest.Document(doc, then), doc.name)
    }

    /** The place chosen for Save a copy (null: cancelled). The document is saved first, then its file copied. */
    fun copyTo(target: Uri?) {
        val request = copyRequest.take()
        if (request == null) {
            // The app was closed while the picker was open, so what to copy is gone; the picker
            // has already made an empty file there. Remove it rather than leave it behind.
            if (target != null) viewModelScope.launch { removeOrphanCopy(target) }
            return
        }
        if (target == null) {
            // User cancelled Android's file picker: do not lose the unsaved recovery choice.
            abandonCopyRequest(request)
            return
        }
        when (request) {
            is CopyRequest.Row -> copyRowTo(request.row, target)
            is CopyRequest.Document -> copyDocumentTo(request.doc, request.then, target)
        }
    }

    /** Delete [target], a file the picker created for a Save a copy that no longer exists, if it is still empty. */
    private suspend fun removeOrphanCopy(target: Uri) {
        val removed = withContext(Dispatchers.IO) {
            runCatching {
                files.size(target) == 0L &&
                    android.provider.DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, target)
            }.getOrDefault(false)
        }
        tell(text(if (removed) R.string.msg_copy_request_lost else R.string.msg_copy_request_lost_kept))
    }

    private fun copyDocumentTo(doc: OpenDocument, then: (() -> Unit)?, target: Uri) {
        viewModelScope.launch {
            try {
                // The Save a copy picker, onPause and auto-save must never race over
                // the same local PDF; only the successful copy may clear the marker.
                saveMutex.withLock {
                    if (doc.info.dirty) {
                        // The working copy now holds edits its original lacks: mark it first and
                        // keep it owed to the original, as any save does (#103).
                        markBeforeWorkingCopyChanges(doc)
                        engine?.tool("doc_save", mapOf("doc" to doc.id))
                        if (doc.source != null) doc.notWrittenBack = true
                        // a file edited in place has just been written
                        doc.source?.takeIf { it.scheme == "file" && File(requireNotNull(it.path)).canonicalPath == doc.file.canonicalPath }
                            ?.let { doc.wroteOriginal = true }
                    }
                    withContext(Dispatchers.IO) {
                        files.writeBack(doc.file, target)
                        if (then != null) doc.source?.let { files.clearPendingWriteBack(it) }
                    }
                    if (then != null) doc.notWrittenBack = false
                    refresh(doc)
                }
                if (then != null) {
                    tell(text(R.string.msg_saved_copy))
                    then()
                } else if (doc.source != null) {
                    continueInCopy(doc, target)
                } else {
                    doc.holdWriteBack = false
                    tell(text(R.string.msg_saved_copy))
                }
            } catch (e: Exception) {
                doc.holdWriteBack = false
                Diag.failure(DiagArea.Save, "copy", e)
                if (then != null && resolvingReadOnly) {
                    readOnlyPrompt = doc
                    resolvingReadOnly = false
                }
                tell(e.userMessage)
            }
        }
    }

    /**
     * Save a copy is "save as" (#190): [target] now holds every change, so the viewer goes on in it
     * and [doc]'s original is left as it was opened, unless an earlier save of this visit already
     * wrote it (then the message says so).
     */
    private suspend fun continueInCopy(doc: OpenDocument, target: Uri) {
        val eng = engine ?: return
        val src = doc.source ?: return
        var left = false
        openMutex.withLock {
            if (document !== doc) return@withLock
            closeQuietly(eng, doc)
            // the working copy's changes are in the copy: the original keeps its own bytes
            withContext(Dispatchers.IO) {
                runCatching {
                    if (src.scheme == "content") files.discardWorkingCopy(src) else { files.clearPendingWriteBack(src); true }
                }.getOrDefault(false)
            }.let { discarded ->
                Diag.event(DiagArea.Save, "copy_continue", "doc" to doc.diagNumber, "wrote_original" to doc.wroteOriginal, "working_copy_discarded" to discarded, "scheme" to src.scheme)
                if (!discarded) tell(text(R.string.discard_kept_recovery))
            }
            document = null
            refreshRecents()
            left = true
        }
        if (!left) return
        open(target)
        val name = withContext(Dispatchers.IO) { runCatching { files.displayName(target) }.getOrDefault("") }
        tell(text(if (doc.wroteOriginal) R.string.msg_copy_continues_saved_before else R.string.msg_copy_continues, name))
    }

    /** Export PDF (to Word, RTF, Excel, PowerPoint, or images of its pages). */
    fun openExport(doc: OpenDocument) {
        toolScreen = ToolScreen.Export(doc)
    }

    /** Read Aloud: the player over the viewer. */
    var readAloud by mutableStateOf<ReadAloud?>(null)
        private set

    /** The reading notification follows Read aloud: shown while it reads or is paused, gone when it stops. */
    private val readAloudNotice = viewModelScope.launch {
        snapshotFlow { readAloud to (readAloud?.playing == true) }.collect { (ra, playing) ->
            val ctx = getApplication<Application>()
            if (ra == null) {
                io.github.artcraftandroid.pdfcraft.ReadAloudService.controls = null
                io.github.artcraftandroid.pdfcraft.ReadAloudService.hide(ctx)
            } else {
                io.github.artcraftandroid.pdfcraft.ReadAloudService.controls = object : io.github.artcraftandroid.pdfcraft.ReadAloudService.Controls {
                    override fun play() { readAloud?.play() }
                    override fun pause() { readAloud?.pause() }
                    override fun skip(forward: Boolean) { readAloud?.skip(forward) }
                    override fun stop() { readAloud?.stop() }
                }
                io.github.artcraftandroid.pdfcraft.ReadAloudService.show(ctx, ra.title, playing)
            }
        }
    }

    fun startReadAloud(doc: OpenDocument, fromSelection: Boolean = false) {
        if (doc.info.encrypted) {
            tell(text(R.string.msg_read_aloud_encrypted))
            return
        }
        readAloud?.stop()
        val eng = engine ?: return
        val key = docKey(doc)
        val sel = if (fromSelection) doc.selection.selection?.range else null
        if (sel != null) {
            // from the selection's first word
            readAloud = ReadAloud(getApplication(), eng, doc, viewModelScope, sel.page.toInt(), { readAloud = null }, key, startGlyph = sel.start)
            return
        }
        val saved = ReadAloud.savedPosition(getApplication(), key)?.takeIf { it.first in 0 until doc.pageCount }
        // a reading that went to the end starts over at the beginning, as the reference's does
        if (saved == ReadAloud.START_OVER) {
            readAloud = ReadAloud(getApplication(), eng, doc, viewModelScope, 0, { readAloud = null }, key)
            return
        }
        if (saved != null) {
            readAloud = ReadAloud(getApplication(), eng, doc, viewModelScope, saved.first, { readAloud = null }, key, resumeAt = saved.second to saved.third)
            tell(text(R.string.msg_read_aloud_resumed), text(R.string.msg_read_aloud_start_over)) {
                ReadAloud.forgetPosition(getApplication(), key)
                readAloud?.stop()
                ReadAloud.forgetPosition(getApplication(), key)
                readAloud = ReadAloud(getApplication(), eng, doc, viewModelScope, 0, { readAloud = null }, key)
            }
            return
        }
        readAloud = ReadAloud(getApplication(), eng, doc, viewModelScope, doc.viewer.currentPage, { readAloud = null }, key)
    }

    /**
     * While Read aloud is on, a tap on a word moves the reading there, as the reference's does. False
     * when no word is under [at] (the tap then shows or hides the bars).
     */
    suspend fun readAloudAt(doc: OpenDocument, at: io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint, slopPoints: Float): Boolean {
        val current = readAloud ?: return false
        val eng = engine ?: return false
        val word = withContext(eng.commands) { eng.session.wordAt(doc.id, at.page.toUInt(), at.x, at.y, slopPoints) } ?: return false
        if (readAloud !== current) return false
        current.stop()
        readAloud = ReadAloud(getApplication(), eng, doc, viewModelScope, word.page.toInt(), { readAloud = null }, docKey(doc), startGlyph = word.start)
        return true
    }

    // ---- Tools' remembered settings, bookmarks, stars --------------------------------------

    val toolPrefs = ToolPrefs(app)

    /** The viewer's bottom toolbar as the user arranged it (More tools ▸ Customize toolbar). */
    val quickToolbar = io.github.artcraftandroid.pdfcraft.ui.viewer.QuickToolbarPrefs(app)
    internal val appPrefs = app.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
    var compactPreviews by mutableStateOf(appPrefs.getBoolean("compactPreviews", false))
        private set
    fun updateCompactPreviews(on: Boolean) {
        compactPreviews = on
        appPrefs.edit().putBoolean("compactPreviews", on).apply()
    }
    private val bookmarkStore = UserBookmarks(File(app.filesDir, "bookmarks.json"))

    /** The Search tab's last searches, newest first (kept on the phone, as the reference keeps them). */
    var recentSearches by mutableStateOf(appPrefs.getString("recentSearches", null)?.split('\n')?.filter { it.isNotBlank() } ?: emptyList())
        private set

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
        tell(text(if (added) R.string.msg_bookmark_added else R.string.msg_bookmark_removed, b.name))
    }

    /** Delete all bookmarks of a document: waiting for confirmation. */
    var confirmDeleteAllBookmarks by mutableStateOf<OpenDocument?>(null)

    fun deleteAllBookmarks(doc: OpenDocument) {
        bookmarks.clear(doc)
        tell(text(R.string.msg_bookmarks_cleared))
    }

    fun isStarred(doc: OpenDocument): Boolean = recentList.firstOrNull { it.uri == docKey(doc) }?.starred == true

    fun toggleStar(doc: OpenDocument) {
        val key = docKey(doc)
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recents.update(key) { it.copy(starred = !it.starred) } }
            refreshRecents()
        }
    }

    fun setStarred(uri: String, starred: Boolean) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recents.update(uri) { it.copy(starred = starred) } }
            refreshRecents()
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
    private fun applyLayout(doc: OpenDocument, layout: ViewLayout?, source: String) {
        val l = layout ?: ViewLayout(savedPageMode, savedSpread, appPrefs.getBoolean("spreadAuto", false))
        // where the layout a file opened in came from: remembered for it, its initial view, or the app's (#152)
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "layout_open", "doc" to doc.diagNumber, "source" to (if (layout == null) "app" else source), "mode" to l.mode, "spread" to l.spread, "auto" to l.autoSpread)
        if (l.mode != doc.viewer.mode) doc.viewer.switchMode(l.mode)
        spreadPref = l.spread
        spreadAuto = l.autoSpread
    }

    /** The layout [doc]'s initial view asks for, or null when it leaves it to the reader or can't be read. */
    private suspend fun fileLayout(eng: io.github.artcraftandroid.pdfcraft.engine.Engine, doc: ULong): ViewLayout? = runCatching {
        val view = eng.tool("doc_initial_view", mapOf("doc" to doc.toLong())).optJSONObject("initial_view")
        ViewLayout.of(OpenLayout.fromReply(view?.optString("layout")))
    }.getOrNull()

    /** How [doc] is laid out now, to remember for it (the layout it had before presenting, if presenting). */
    private fun currentLayout(doc: OpenDocument) =
        ViewLayout(doc.controller.modeBeforePresenting ?: doc.viewer.mode, spreadPref, spreadAuto)

    /**
     * Write [doc]'s layout into the file as its initial view, so other readers open it the same
     * way (#152). Pages in a row and automatic two pages have no PDF equivalent.
     */
    fun saveLayoutAsInitialView(doc: OpenDocument) {
        val open = currentLayout(doc).openLayout() ?: return
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "layout_initial_view", "doc" to doc.diagNumber, "layout" to open)
        setInitialView(doc, mapOf("layout" to open.arg))
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
        private set

    /** Two pages side by side when the window is wide and landscape, one otherwise (#152). */
    var spreadAuto by mutableStateOf(appPrefs.getBoolean("spreadAuto", false))
        private set

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
        if (readingMode && doc.viewer.viewW > 0f) pendingPage = null
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
        val eng = engine ?: return
        if (doc.info.preparing) return
        commenting.selectedComment = null
        viewModelScope.launch {
            val ok = runCatching {
                io.github.artcraftandroid.pdfcraft.engine.Diag.measure(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "comments_hide", "doc" to doc.diagNumber, "hidden" to hidden) { eng.tool("comments_hide", mapOf("doc" to doc.id, "hidden" to hidden)) }
            }.onFailure { io.github.artcraftandroid.pdfcraft.engine.Diag.failure(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "comments_hide", it) }.isSuccess
            if (!ok) return@launch
            // the switch reads the engine's state; the pages draw again without waiting for an edit
            refresh(doc)
            doc.images.invalidate()
        }
    }

    /** Give a newly opened (and prepared) [doc] the saved reading colours. */
    fun applySavedReadingColors(doc: OpenDocument) {
        val saved = savedReadingColors
        if (saved != doc.controller.readingColors) applyReadingColors(doc, saved)
    }

    private fun applyReadingColors(doc: OpenDocument, colors: io.github.artcraftandroid.pdfcraft.ui.viewer.ReadingColors) {
        val eng = engine ?: return
        if (doc.info.preparing) return
        viewModelScope.launch {
            val ok = runCatching {
                io.github.artcraftandroid.pdfcraft.engine.Diag.measure(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "reading_colors", "doc" to doc.diagNumber, "colors" to colors) { eng.tool("view_reading_colors", mapOf("doc" to doc.id, "mode" to colors.engineMode)) }
            }.onFailure { io.github.artcraftandroid.pdfcraft.engine.Diag.failure(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "reading_colors", it) }.isSuccess
            if (!ok) return@launch
            doc.controller.readingColors = colors
            // the engine's view changed: every page draws again
            refresh(doc)
        }
    }

    fun openReadingMode(doc: OpenDocument) {
        if (doc.info.preparing) { tell(text(R.string.document_preparing)); return }
        if (doc.controller.tool != ViewerTool.None) leaveTool(doc)
        readingMode = true
    }

    /** A tool tried in Reading Mode, for the dialog that says it needs another view. */
    var readingModeBlocked by mutableStateOf<ViewerTool?>(null)

    /** Keep the screen on while reading (Preferences; released after half an hour without a touch). */
    var keepScreenOn by mutableStateOf(appPrefs.getBoolean("keepScreenOn", false))
        private set

    fun updateKeepScreenOn(on: Boolean) {
        keepScreenOn = on
        appPrefs.edit().putBoolean("keepScreenOn", on).apply()
    }

    /** The diagnostics number of the latest open (#105: lines name a number, never the file). */
    private var diagDoc = 0

    /**
     * About → Diagnostics: record the detailed diagnostics log (open and render timings, pen
     * latency, saves, scans, memory, failures) in app-private files so it can be shared without
     * adb. See docs/ANDROID_DIAGNOSTICS_LOG.md.
     */
    var recordDiagnostics by mutableStateOf(Diag.enabled())
        private set

    fun updateRecordDiagnostics(on: Boolean) {
        recordDiagnostics = on
        val app = getApplication<Application>()
        viewModelScope.launch(Dispatchers.IO) { Diagnostics.setOn(app, on) }
    }

    fun clearDiagnostics() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            withContext(Dispatchers.IO) { Diagnostics.clear(app) }
            tell(text(R.string.home_diagnostics_cleared))
        }
    }

    /** Share the diagnostics log as a text file; only event names, times, counts and sizes. */
    fun shareDiagnostics() {
        val app = getApplication<Application>()
        viewModelScope.launch {
            // the pen latency so far (#128), as Pen settings shows it
            listOf("normal" to io.github.artcraftandroid.pdfcraft.ui.viewer.InkLatency.normal, "low_latency" to io.github.artcraftandroid.pdfcraft.ui.viewer.InkLatency.lowLatency).forEach { (path, stats) ->
                stats.summary()?.let { Diag.event(DiagArea.Ink, "latency_summary", "path" to path, "samples" to it.count, "p50_ms" to it.medianMs, "p95_ms" to it.p95Ms) }
            }
            io.github.artcraftandroid.pdfcraft.ui.viewer.PenDiag.flushPalm()
            Diagnostics.memory("share")
            val lines = withContext(Dispatchers.IO) { Diagnostics.lines(app) }
            if (lines.isEmpty()) {
                tell(text(R.string.home_diagnostics_empty))
                return@launch
            }
            handOut {
                val facts = Diagnostics.facts(app, appVersion, io.github.artcraftandroid.pdfcraft.ui.home.installedVersionCode(app), io.github.artcraftandroid.pdfcraft.core.engineVersion())
                val report = io.github.artcraftandroid.pdfcraft.engine.diagReport(facts, lines)
                val file = withContext(Dispatchers.IO) {
                    File(File(app.cacheDir, "share").apply { mkdirs() }, "pdfcraft-diagnostics.txt").apply { writeText(report) }
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
                // the file only: a long log is too big for a message's text
                sendIntent(listOf(uri), "text/plain")
                    .putExtra(Intent.EXTRA_SUBJECT, text(R.string.home_diagnostics_subject))
            }
        }
    }

    /** The viewer's keyboard shortcuts (Preferences; on by default: only a keyboard sends them, #153). */
    var keyboardShortcuts by mutableStateOf(appPrefs.getBoolean("keyboardShortcuts", true))
        private set

    fun updateKeyboardShortcuts(on: Boolean) {
        keyboardShortcuts = on
        appPrefs.edit().putBoolean("keyboardShortcuts", on).apply()
    }

    /** The app's theme (Preferences ▸ Theme): the system's, or always light or dark. */
    var theme by mutableStateOf(runCatching { ThemeChoice.valueOf(appPrefs.getString("theme", "System") ?: "System") }.getOrDefault(ThemeChoice.System))
        private set

    fun setThemeChoice(t: ThemeChoice) {
        theme = t
        appPrefs.edit().putString("theme", t.name).apply()
    }

    /**
     * Extra Features ▸ the status bar in documents: shown with the viewer's bars, and the page uses the
     * whole screen when they hide (off: the reference's black band where the camera is).
     */
    var statusBarInDocuments by mutableStateOf(appPrefs.getBoolean("statusBarInDocuments", false))
        private set

    fun updateStatusBarInDocuments(on: Boolean) {
        statusBarInDocuments = on
        appPrefs.edit().putBoolean("statusBarInDocuments", on).apply()
    }

    // ---- Reading the document ---------------------------------------------------------------

    suspend fun outline(doc: OpenDocument) = engine?.let { e -> runCatching { e.command { outline(doc.id) } }.getOrNull() }.orEmpty()

    suspend fun attachments(doc: OpenDocument) = engine?.let { e -> runCatching { e.command { attachments(doc.id) } }.getOrNull() }.orEmpty()

    suspend fun comments(doc: OpenDocument): List<io.github.artcraftandroid.pdfcraft.core.CommentInfo>? {
        if (document !== doc || doc.info.preparing) return null
        if (doc.commentsComplete) return doc.comments
        val generation = doc.info.generation
        val eng = engine ?: return null
        val result = try { eng.command { comments(doc.id) } }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (e: Exception) { if (document === doc) tell(e.userMessage); return null }
        if (document !== doc || generation != doc.info.generation) return null
        doc.comments = result
        doc.commentsComplete = true
        return result
    }

    /** Open an embedded file: a PDF in the viewer, anything else in another app. */
    fun openAttachment(doc: OpenDocument, name: String) {
        val eng = engine ?: return
        viewModelScope.launch {
            try {
                val list = eng.command { attachments(doc.id) }
                val index = list.indexOfFirst { it.name == name }.takeIf { it >= 0 } ?: return@launch
                val dir = File(getApplication<Application>().cacheDir, "attachments/${System.nanoTime()}").apply { mkdirs() }
                val out = File(dir, DocumentFiles.safeName(name).let { if (name.lowercase().endsWith(".pdf")) it else name.replace(Regex("[\\\\/:*?\"<>|]"), "_") })
                eng.command { saveAttachment(doc.id, index.toUInt(), out.absolutePath) }
                if (out.name.lowercase().endsWith(".pdf")) {
                    open(Uri.fromFile(out))
                } else {
                    val type = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(out.extension.lowercase())
                    if (io.github.artcraftandroid.pdfcraft.ui.viewer.installableAttachment(out.name, type)) {
                        // An app package inside a PDF is never handed to the installer.
                        dir.deleteRecursively()
                        tell(text(R.string.attachment_install_refused, out.name))
                        return@launch
                    }
                    val uri = try {
                        withContext(Dispatchers.IO) { files.shareable(out, out.name) }
                    } finally {
                        // the other app reads the share copy; the extracted file is not needed (#105)
                        withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
                    }
                    intents.trySend(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, type ?: "*/*").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
                }
            } catch (e: Exception) {
                tell(e.userMessage)
            }
        }
    }

    // ---- Comments, voice comments, selecting a comment, review and sharing: see CommentsController ----

    val commenting = CommentsController(this)

    // ---- Tool screens (Compress, Combine, Protect, Create, Crop, Recognize text, Scan, Redact, Organize) ----

    /** The full-screen tool over the app, if one is open. */
    var toolScreen by mutableStateOf<ToolScreen?>(null)

    /** The Pages screen is organizing (not just showing thumbnails). */
    var organizing by mutableStateOf(false)

    fun openCompress(doc: OpenDocument) { toolScreen = ToolScreen.Compress(doc) }
    fun openCombine(doc: OpenDocument?) {
        // like the reference, the open document is the first file
        if (doc != null && combineFiles.isEmpty()) {
            val uri = doc.source ?: Uri.fromFile(doc.file)
            combineFiles.add(PickedFile(uri, doc.name, doc.info.fileSize.toLong()))
        }
        toolScreen = ToolScreen.Combine(doc)
    }
    fun openProtect(doc: OpenDocument) { toolScreen = ToolScreen.Protect(doc) }
    /** Create PDF starts on its own page of sources (photos, files, a scan), as the reference's. */
    fun openCreate() { toolScreen = ToolScreen.Create }
    fun openCrop(doc: OpenDocument) { toolScreen = ToolScreen.Crop(doc) }
    fun startScan() { toolScreen = ToolScreen.Scan }

    /**
     * Native 0.19.0 scanned-page cleanup. Bounded UI page scope, explicit confirm,
     * and no new provider-path writes: native edits the already-open working PDF.
     */
    internal fun enhanceScannedPages(doc: OpenDocument, plan: io.github.artcraftandroid.pdfcraft.ui.viewer.ScanEnhancePlan) {
        if (document !== doc || busy != null || !doc.writable || doc.info.signed ||
            doc.info.readOnlyReason != null || doc.pageCount == 0 ||
            !io.github.artcraftandroid.pdfcraft.ui.viewer.validScanEnhancePlan(plan, doc.pageCount)) {
            tell(text(R.string.scan_enhance_unavailable))
            return
        }
        val eng = engine ?: return
        val generation = doc.info.generation
        viewModelScope.launch {
            if (document !== doc || busy != null || doc.info.generation != generation) return@launch
            busy = text(R.string.scan_enhance_busy)
            try {
                val result = eng.tool("page_enhance_scan", mapOf(
                    "doc" to doc.id,
                    "pages" to plan.pages,
                    "level" to plan.level,
                    "edge_shadows" to plan.edgeShadows,
                    "despeckle" to plan.despeckle,
                ))
                refresh(doc)
                if (document !== doc) return@launch
                val done = result.optJSONArray("pages_cleaned")?.length() ?: 0
                if (done > 0) {
                    success = SuccessBanner(text(R.string.scan_enhance_finished, done)) { undo(doc) }
                } else {
                    tell(text(R.string.scan_enhance_no_changes))
                }
            } catch (e: MobileException) {
                if (document === doc) tell(e.userMessage)
            } finally {
                busy = null
            }
        }
    }

    /** Recognize text: its options, review and batch state (#166). */
    val ocr = io.github.artcraftandroid.pdfcraft.ui.viewer.OcrState()

    /** Undo [steps] edits of [doc] in a row (a recognition made page by page), then redraw. */
    internal fun undoSteps(doc: OpenDocument, steps: Int) {
        val eng = engine ?: return
        viewModelScope.launch {
            try {
                repeat(steps) { if (document === doc && doc.info.undo != null) { eng.tool("edit_undo", mapOf("doc" to doc.id)); refresh(doc) } }
            } catch (e: MobileException) {
                tell(e.userMessage)
            }
        }
    }

    /** The busy dialog's count (done, total) when the job knows it, for a determinate bar (#200). */
    var busyProgress by mutableStateOf<Pair<Int, Int>?>(null)

    /** The busy dialog's Cancel, when the job can stop safely (between pages). */
    var busyCancel by mutableStateOf<(() -> Unit)?>(null)

    /** Redact: marks, the box being drawn, the look, and the checks after applying (#163). */
    val redact = io.github.artcraftandroid.pdfcraft.ui.viewer.RedactState()
    /** Compare files: the older document and the differences found (#171). */
    val compare = io.github.artcraftandroid.pdfcraft.ui.viewer.CompareState()
    /** Accessibility: the last check, figures and auto-tag preview (#182). */
    val accessibility = io.github.artcraftandroid.pdfcraft.ui.viewer.AccessibilityState()
    /** Prepare form: the document being prepared, the kind to place and the selected field (#164). */
    val prepareForm = io.github.artcraftandroid.pdfcraft.ui.viewer.PrepareFormState()

    /** A finished job's green banner over the viewer's bar, with Undo. */
    var success by mutableStateOf<SuccessBanner?>(null)

    /** A long-running job's message (a progress dialog over everything), or null. */
    var busy by mutableStateOf<String?>(null)

    // ---- Fill & Sign, Digital signatures: see SigningController ----

    val signing = SigningController(this)

    // ---- Edit PDF, Forms, Form auto-suggestions: see EditController ----

    val editor = EditController(this)

    // ---- Tool jobs: each runs on the phone and saves its result into Downloads ---------------

    /** File pickers for the activity: what the files are for, the types, and whether several may be picked. */
    val filePicks = Channel<FilePickKind>(Channel.BUFFERED)

    /** Photos for Scan: the activity has the camera app write each into this file. */
    val cameraShots = Channel<File>(Channel.BUFFERED)

    /** The files gathered for Combine, in order. */
    val combineFiles = androidx.compose.runtime.mutableStateListOf<PickedFile>()

    // A batch owns only fresh cache files and new Downloads rows; source URIs are read-only.
    val batesFiles = androidx.compose.runtime.mutableStateListOf<PickedFile>()
    val batesPicks = Channel<Unit>(Channel.BUFFERED)
    var batesRunning by mutableStateOf(false)
        private set
    var batesSelectionLoading by mutableStateOf(false)
        private set
    var batesStatus by mutableStateOf("")
        private set
    var batesResults by mutableStateOf<List<BatesExportResult>>(emptyList())
        private set
    private var batesEpoch = 0L
    private var batesPendingPick: Long? = null
    private var batesTask: kotlinx.coroutines.Job? = null

    fun openBates() {
        if (batesRunning) return
        batesEpoch++
        batesPendingPick = null
        batesSelectionLoading = false
        batesFiles.clear()
        batesResults = emptyList()
        batesStatus = ""
        toolScreen = ToolScreen.Bates
    }

    fun closeBates() {
        if (batesRunning) {
            batesStatus = text(R.string.bates_cancelling)
            batesTask?.cancel()
        } else {
            batesEpoch++
            batesPendingPick = null
            batesSelectionLoading = false
            batesFiles.clear()
            toolScreen = null
        }
    }

    fun pickBatesFiles() {
        if (batesRunning || batesSelectionLoading || toolScreen != ToolScreen.Bates || batesPendingPick != null) return
        batesPendingPick = batesEpoch
        if (!batesPicks.trySend(Unit).isSuccess) batesPendingPick = null
    }

    fun onBatesFilesPicked(uris: List<Uri>) {
        val epoch = batesPendingPick ?: return
        batesPendingPick = null
        if (uris.isEmpty() || toolScreen != ToolScreen.Bates || batesRunning || epoch != batesEpoch) return
        if (uris.size > io.github.artcraftandroid.pdfcraft.engine.MAX_BATES_FILES) { tell(text(R.string.bates_limits)); return }
        val remaining = io.github.artcraftandroid.pdfcraft.engine.MAX_BATES_FILES - batesFiles.size
        val selected = uris.distinct().filter { u -> batesFiles.none { it.uri == u } }
        if (selected.size > remaining) { tell(text(R.string.bates_limits)); return }
        batesSelectionLoading = true
        viewModelScope.launch {
            try {
                val picked = withContext(Dispatchers.IO) {
                    selected.map { PickedFile(it, files.displayNameRaw(it).take(180), files.size(it)) }
                }
                if (epoch == batesEpoch && toolScreen == ToolScreen.Bates && !batesRunning) {
                    // Another pending metadata read must not bypass the count/duplicate limits.
                    val additions = picked.filter { p -> batesFiles.none { it.uri == p.uri } }
                    if (batesFiles.size + additions.size <= io.github.artcraftandroid.pdfcraft.engine.MAX_BATES_FILES) {
                        batesFiles.addAll(additions)
                        batesResults = emptyList()
                    } else tell(text(R.string.bates_limits))
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { tell(text(R.string.bates_failed)) }
            finally { if (epoch == batesEpoch) batesSelectionLoading = false }
        }
    }

    fun moveBatesFile(index: Int, delta: Int) {
        val target = index + delta
        if (!batesRunning && !batesSelectionLoading && index in batesFiles.indices && target in batesFiles.indices) {
            val item = batesFiles.removeAt(index)
            batesFiles.add(target, item)
            batesResults = emptyList()
        }
    }

    fun removeBatesFile(index: Int) {
        if (!batesRunning && !batesSelectionLoading && index in batesFiles.indices) { batesFiles.removeAt(index); batesResults = emptyList() }
    }

    fun runBates(start: String, digits: String, prefix: String, suffix: String) {
        val app = getApplication<Application>()
        val settings = io.github.artcraftandroid.pdfcraft.engine.BatesSettings.parse(start, digits, prefix, suffix) ?: return
        val eng = engine ?: return
        if (batesRunning || batesSelectionLoading || toolScreen != ToolScreen.Bates || batesFiles.size !in 1..io.github.artcraftandroid.pdfcraft.engine.MAX_BATES_FILES) return
        val picked = batesFiles.toList()
        batesEpoch++ // Ignore stale picker/metadata callbacks for this frozen batch.
        batesPendingPick = null
        batesRunning = true
        batesResults = emptyList()
        batesTask = viewModelScope.launch {
            val context = currentCoroutineContext()
            val results = mutableListOf<BatesExportResult>()
            var directory: File? = null
            var preparing: PickedFile? = null
            var finalStatus = text(R.string.bates_failed)
            try {
                val dir = withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) {
                    context.ensureActive()
                    File(app.cacheDir, "bates-${java.util.UUID.randomUUID()}").also { check(it.mkdir()); directory = it }
                }
                context.ensureActive()
                var bytes = 0L
                val inputs = mutableListOf<Map<String, String>>()
                for ((i, file) in picked.withIndex()) {
                    preparing = file
                    context.ensureActive()
                    batesStatus = text(R.string.bates_reading, i + 1, picked.size)
                    val input = File(dir, "input-${i + 1}.pdf")
                    val output = File(dir, "output-${i + 1}.pdf")
                    bytes += withContext(Dispatchers.IO) {
                        val remaining = io.github.artcraftandroid.pdfcraft.engine.MAX_BATES_BYTES - bytes
                        require(remaining > 0 && (file.size == null || file.size <= remaining)) { text(R.string.bates_limits) }
                        app.contentResolver.openInputStream(file.uri).use { source ->
                            requireNotNull(source) { text(R.string.bates_failed) }
                            input.outputStream().use { target ->
                                SafBoundedCopy.copy(source, target, remaining, "PDF") { context.ensureActive() }
                            }
                        }
                    }
                    withTemporaryBookmarkSource(
                        open = { eng.command { open(input.absolutePath, null).id.toLong() } },
                        close = { id -> eng.command { close(id.toULong(), true) } },
                    ) { id ->
                        val info = eng.command { document(id.toULong()) }
                        require(!info.signed && info.readOnlyReason == null) { text(R.string.bates_source_refused) }
                    }
                    inputs.add(mapOf("input" to input.absolutePath, "output" to output.absolutePath))
                }
                preparing = null
                batesStatus = text(R.string.bates_numbering)
                // Ownership lasts until the blocking native operation returns, including cancellation.
                val report = withContext(kotlinx.coroutines.NonCancellable) {
                    eng.command {
                        context.ensureActive()
                        JSONObject(execute("doc_bates_multi", Engine.toJson(mapOf(
                            "files" to inputs, "start_number" to settings.start, "digits" to settings.digits,
                            "prefix" to settings.prefix, "suffix" to settings.suffix,
                        )).toString()))
                    }
                }
                context.ensureActive()
                val rows = report.getJSONArray("files")
                require(rows.length() == picked.size) { text(R.string.bates_failed) }
                val intervals = (0 until rows.length()).map { i ->
                    val row = rows.getJSONObject(i)
                    require(row.getString("input") == inputs[i].getValue("input") && row.getString("output") == inputs[i].getValue("output"))
                    require(row.getString("status") in setOf("ok", "error"))
                    io.github.artcraftandroid.pdfcraft.engine.BatesInterval(row.getLong("pages"), row.getLong("first"), row.getLong("last"))
                }
                io.github.artcraftandroid.pdfcraft.engine.validateBatesIntervals(intervals, picked.size, settings.start)
                for (i in picked.indices) {
                    context.ensureActive()
                    val row = rows.getJSONObject(i)
                    if (row.getString("status") != "ok") {
                        val message = row.optString("error", text(R.string.bates_failed)).replace(dir.absolutePath, "").take(240)
                        results.add(BatesExportResult(picked[i].name, message, null))
                        continue
                    }
                    batesStatus = text(R.string.bates_exporting, i + 1, picked.size)
                    try {
                        val interval = intervals[i]
                        val first = settings.prefix + interval.first.toString().padStart(settings.digits, '0') + settings.suffix
                        val last = settings.prefix + interval.last.toString().padStart(settings.digits, '0') + settings.suffix
                        val savedMessage = text(R.string.bates_saved_range, first, last)
                        // Store the committed URI before returning to a possibly cancelled caller.
                        io.github.artcraftandroid.pdfcraft.engine.rememberBatesPublication(publish = {
                            context.ensureActive()
                            val output = File(inputs[i].getValue("output"))
                            require(output.isFile && output.length() in 1..io.github.artcraftandroid.pdfcraft.engine.MAX_BATES_BYTES)
                            val base = DocumentFiles.safeName(picked[i].name).substringBeforeLast('.')
                            val bounded = StringBuilder().apply { base.codePoints().limit(48).forEach { appendCodePoint(it) } }.toString()
                            val uri = files.saveBatesCopy(output, "${bounded}_bates.pdf") { context.ensureActive() }
                            BatesExportResult(picked[i].name, savedMessage, uri)
                        }, remember = { results.add(it) })
                    } catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { results.add(BatesExportResult(picked[i].name, text(R.string.bates_export_failed), null)) }
                }
                finalStatus = text(R.string.bates_finished, results.count { it.uri != null }, results.count { it.uri == null })
            } catch (_: kotlinx.coroutines.CancellationException) {
                finalStatus = text(R.string.bates_cancelled, results.count { it.uri != null })
            } catch (_: Exception) {
                preparing?.let { results.add(BatesExportResult(it.name, text(R.string.bates_failed), null)) }
                finalStatus = text(R.string.bates_failed)
            } finally {
                withContext(kotlinx.coroutines.NonCancellable) {
                    withContext(Dispatchers.IO) { directory?.deleteRecursively() }
                    batesResults = results.toList()
                    batesStatus = finalStatus
                    batesRunning = false
                    batesTask = null
                }
            }
        }
    }

    /** The pages scanned so far. */
    val scanPages = androidx.compose.runtime.mutableStateListOf<ScanPage>()

    /** The scan's file name (without .pdf), set when its first page is taken and changeable before saving. */
    var scanName by mutableStateOf("")

    /** Whether this build can flatten curled pages (#302): its flattening model is installed. */
    var scanCanFlatten by mutableStateOf(false)
        private set
    internal var shotFile: File? = null
    private var retakeIndex: Int? = null

    // A scan Android closed the app in the middle of, while the camera app was in front, carries on
    // when the photo comes back (#38): what it needs is kept in the saved state, read when it's saved.
    private var scanRestoring: String? = null
    private val scanRestore: kotlinx.coroutines.Job? = saved.get<android.os.Bundle>(SCAN_STATE)?.getString(SCAN_STATE)?.let { text ->
        ScanSession.decode(text)?.let { restoreScan(text, it) }
    }

    init {
        saved.setSavedStateProvider(SCAN_STATE) {
            android.os.Bundle().apply { putString(SCAN_STATE, scanRestoring ?: scanSession()?.encode()) }
        }
    }

    /** The scan in progress (or the Add image photo being taken); null when there is none. */
    private fun scanSession(): ScanSession? {
        if (shotFile == null && scanPages.isEmpty()) return null
        return ScanSession(shotFile?.path, retakeIndex, editor.imageShot, scanName, scanPages.map { ScanSession.Page(it.file.path, it.corners, it.found) })
    }

    private fun restoreScan(text: String, session: ScanSession): kotlinx.coroutines.Job {
        scanRestoring = text
        shotFile = session.shot?.let(::File)
        retakeIndex = session.retake
        editor.imageShot = session.forImage
        scanName = session.name
        return viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                session.pages.mapNotNull { p -> File(p.path).let { f -> scanThumb(f)?.let { ScanPage(f, it, p.corners, p.found) } } }
            }
            scanPages.addAll(pages)
            scanRestoring = null
            // back on the Scan screen with its pages (with none, it would open the camera itself)
            if (pages.isNotEmpty() && toolScreen == null) toolScreen = ToolScreen.Scan
        }
    }

    /** A scanned photo, small and upright, for the Scan screen; null when it can't be read. */
    private fun scanThumb(f: File): androidx.compose.ui.graphics.ImageBitmap? {
        val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = 4 }
        return android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)?.let { rotateForExif(f, it) }?.asImageBitmap()
    }

    /** Scan's pages go into this document at this (1-based) position instead of a new PDF. */
    var insertScanAt: Pair<OpenDocument, Int>? = null
    private var insertFileAt: Pair<OpenDocument, Int>? = null

    fun pickFiles(kind: FilePickKind) {
        filePicks.trySend(kind)
    }

    /** Files picked for [kind]. */
    fun onFilesPicked(kind: FilePickKind, uris: List<Uri>) {
        if (uris.isEmpty()) return
        when (kind) {
            FilePickKind.Combine -> viewModelScope.launch {
                val picked = withContext(Dispatchers.IO) { uris.map { u -> PickedFile(u, files.displayNameRaw(u), files.size(u)) } }
                combineFiles.addAll(picked.filter { p -> combineFiles.none { it.uri == p.uri } })
                if (toolScreen !is ToolScreen.Combine) toolScreen = ToolScreen.Combine(null)
            }
            FilePickKind.Create -> createFrom(uris)
            FilePickKind.Insert -> {
                val (doc, at) = insertFileAt ?: return
                insertFileAt = null
                viewModelScope.launch {
                    val f = withContext(Dispatchers.IO) { copyIn(uris.first()) }
                    // the picked copy goes once the pages are in (#105)
                    runTool(doc, "page_insert_file", mapOf("path" to f.absolutePath, "at" to at), onSettled = { dropPicked(listOf(f)) })
                }
            }
            FilePickKind.Open -> open(uris.first())
            FilePickKind.MarkImage -> viewModelScope.launch {
                val f = withContext(Dispatchers.IO) { runCatching { copyIn(uris.first()) }.getOrNull() }
                if (f == null) { tell(text(R.string.msg_that_file_unreadable)); return@launch }
                clearMarkImage()
                markImage = f
            }
            FilePickKind.Attach -> {
                val doc = attachTo ?: return
                attachTo = null
                viewModelScope.launch {
                    val uri = uris.first()
                    val f = withContext(Dispatchers.IO) { runCatching { copyIn(uri) }.getOrNull() }
                    if (f == null) tell(text(R.string.msg_that_file_unreadable))
                    else attachPrompt = AttachPrompt(doc, f, f.name)
                }
            }
            FilePickKind.Replace -> {
                val (doc, pages) = replaceTarget ?: return
                replaceTarget = null
                viewModelScope.launch {
                    val f = withContext(Dispatchers.IO) { runCatching { copyIn(uris.first()) }.getOrNull() }
                    if (f == null) tell(text(R.string.msg_that_file_unreadable))
                    else replacePrompt = ReplacePrompt(doc, pages, f)
                }
            }
            FilePickKind.Library -> study.onLibraryPicked(uris)
            FilePickKind.DigitalId -> viewModelScope.launch {
                val uri = uris.first()
                val picked = withContext(Dispatchers.IO) { runCatching { copyIn(uri) }.getOrNull() }
                if (picked == null) tell(text(R.string.msg_that_file_unreadable))
                else signing.idImport = io.github.artcraftandroid.pdfcraft.ui.viewer.IdImport(picked, picked.name, wrong = false)
            }
        }
    }

    fun pickInsertFile(doc: OpenDocument, at: Int) {
        insertFileAt = doc to at
        pickFiles(FilePickKind.Insert)
    }

    /** Delete picked copies made by [copyIn] (each in its own folder). */
    private fun dropPicked(picked: List<File>) {
        viewModelScope.launch(NonCancellable + Dispatchers.IO) { picked.forEach { it.parentFile?.deleteRecursively() } }
    }

    /** A copy of a picked file in the app's cache, under its own name. */
    private fun copyIn(uri: Uri): File {
        val dir = File(getApplication<Application>().cacheDir, "picked/${System.nanoTime()}").apply { mkdirs() }
        val out = File(dir, files.displayNameRaw(uri))
        getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { text(R.string.msg_named_file_unreadable, out.name) }
            out.outputStream().use { input.copyTo(it) }
        }
        return out
    }

    /** Run [block] with the busy dialog showing [message]; failures are told in a snackbar. */
    internal fun job(message: String, block: suspend (Engine) -> Unit) {
        val eng = engine ?: return
        viewModelScope.launch {
            busy = message
            try {
                block(eng)
            } catch (e: Exception) {
                tell(e.userMessage)
            } finally {
                busy = null
                busyProgress = null
            }
        }
    }

    /** Put [file] into Downloads as [name] and say so, with Open. */
    internal suspend fun deliver(file: File, name: String, mime: String = "application/pdf", lead: String? = null) {
        val bytes = file.length()
        val uri = try {
            Diag.measure(DiagArea.Pages, "save_to_downloads", "bytes" to bytes, "pdf" to (mime == "application/pdf")) {
                withContext(Dispatchers.IO) { files.saveToDownloads(file, name, mime).also { file.delete() } }
            }
        } catch (e: Exception) {
            Diag.failure(DiagArea.Pages, "save_to_downloads", e)
            throw e
        }
        val size = withContext(Dispatchers.IO) { files.size(uri) }
        val saved = (size?.let { text(R.string.msg_saved_downloads_size, name, io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize(it)) } ?: text(R.string.msg_saved_downloads, name))
            .let { if (lead != null) "$lead $it" else it }
        if (mime == "application/pdf") tell(saved, text(R.string.common_open)) { open(uri) }
        else tell(saved, text(R.string.common_open)) {
            intents.trySend(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        }
    }

    internal fun tempFile(ext: String) = File(getApplication<Application>().cacheDir, "out-${System.nanoTime()}.$ext")

    /** Compress ▸ Advanced, as last used (remembered). */
    var compressOptions by mutableStateOf(loadCompressOptions())
        private set

    private fun loadCompressOptions(): CompressOptions = CompressOptions(
        enabled = appPrefs.getBoolean("compress.advanced", false),
        targetMb = appPrefs.getFloat("compress.targetMb", 0f).toDouble().takeIf { it > 0 },
        ppi = appPrefs.getInt("compress.ppi", 150),
        quality = appPrefs.getInt("compress.quality", 60),
        mobile = appPrefs.getBoolean("compress.mobile", false),
        removeInvalidLinks = appPrefs.getBoolean("compress.removeInvalidLinks", true),
        discardFormActions = appPrefs.getBoolean("compress.discardFormActions", false),
        discardTags = appPrefs.getBoolean("compress.discardTags", false),
        discardAlternateImages = appPrefs.getBoolean("compress.discardAlternateImages", true),
    )

    fun updateCompressOptions(o: CompressOptions) {
        compressOptions = o
        appPrefs.edit()
            .putBoolean("compress.advanced", o.enabled).putFloat("compress.targetMb", (o.targetMb ?: 0.0).toFloat())
            .putInt("compress.ppi", o.ppi).putInt("compress.quality", o.quality).putBoolean("compress.mobile", o.mobile)
            .putBoolean("compress.removeInvalidLinks", o.removeInvalidLinks).putBoolean("compress.discardFormActions", o.discardFormActions)
            .putBoolean("compress.discardTags", o.discardTags).putBoolean("compress.discardAlternateImages", o.discardAlternateImages)
            .apply()
    }

    /** Compress PDF at [level] (0 high, 1 medium, 2 low compression), or with Advanced [options]. */
    fun compress(doc: OpenDocument, level: Int, options: CompressOptions = compressOptions) = job(text(R.string.msg_busy_compressing)) { eng ->
        val out = tempFile("pdf")
        val r = Diag.measure(DiagArea.Pages, "compress_run", "doc" to doc.diagNumber, "level" to level, "advanced" to options.enabled) {
            eng.tool("doc_optimize", compressArgs(level, options) + mapOf("doc" to doc.id, "path" to out.absolutePath))
        }
        toolScreen = null
        val before = doc.info.fileSize.toLong()
        Diag.event(DiagArea.Pages, "compress_result", "doc" to doc.diagNumber, "before_bytes" to before, "after_bytes" to out.length(),
            "smaller" to (out.length() < before), "target_set" to r.has("target_reached"), "target_reached" to r.optBoolean("target_reached"))
        if (out.length() >= before) {
            // as the reference: nothing is saved when the file can't get any smaller
            out.delete()
            tell(text(R.string.msg_compress_no_gain))
            return@job
        }
        val sizes = text(R.string.msg_compressed_sizes, io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize(before), io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize(out.length()))
        val target = if (r.has("target_reached")) text(if (r.optBoolean("target_reached")) R.string.msg_compress_target_reached else R.string.msg_compress_target_missed) else null
        deliver(out, "${doc.title}_compressed.pdf", lead = listOfNotNull(sizes, target).joinToString(" "))
    }

    /** What takes the file's space (`doc_audit_space`): (category, bytes, percent), biggest first. */
    suspend fun auditSpace(doc: OpenDocument): List<Triple<String, Long, Double>>? {
        val eng = engine ?: return null
        return try {
            val a = eng.tool("doc_audit_space", mapOf("doc" to doc.id)).optJSONArray("categories") ?: return emptyList()
            (0 until a.length()).map { a.getJSONObject(it).let { o -> Triple(o.optString("category"), o.optLong("bytes"), o.optDouble("percent")) } }
                .sortedByDescending { it.second }
        } catch (e: MobileException) { tell(e.userMessage); null }
    }

    /** Combine the gathered files, in order, into [name].pdf. */
    fun combine(name: String) = job(text(R.string.msg_busy_combining)) { eng ->
        val copies = mutableListOf<File>()
        val out = tempFile("pdf")
        try {
            val paths = withContext(Dispatchers.IO) { combineFiles.map { copyIn(it.uri).also(copies::add).absolutePath } }
            eng.tool("doc_combine", mapOf("paths" to paths, "out" to out.absolutePath))
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            dropPicked(copies)
        }
        toolScreen = null
        combineFiles.clear()
        deliver(out, DocumentFiles.safeName(name))
    }

    /**
     * A protected copy of [doc] with [password] as its open password. The copy is made from a
     * second, temporary engine document, so the open one keeps its file and history.
     */
    fun protect(doc: OpenDocument, protection: Map<String, Any?>) = job(text(R.string.msg_busy_setting_password)) { eng ->
        save(doc)
        val out = tempFile("pdf")
        val copy = eng.tool("doc_open", mapOf("path" to doc.file.absolutePath, "password" to doc.password)).optLong("doc")
        try {
            Diag.measure(DiagArea.Pages, "protect_copy", "doc" to doc.diagNumber, "settings" to protection.size) {
                eng.tool("doc_protect", protection + ("doc" to copy))
                eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true))
            }
        } finally {
            runCatching { eng.tool("doc_close", mapOf("doc" to copy, "discard_changes" to true)) }
        }
        toolScreen = null
        deliver(out, "${doc.title}_protected.pdf")
    }

    /**
     * An unprotected copy of [doc] (`doc_unprotect` on a second, temporary engine document, as
     * [protect] does), in Downloads. The document must have been opened with its permissions
     * password, or have none; the engine says so otherwise.
     */
    fun unprotect(doc: OpenDocument) = job(text(R.string.msg_busy_removing_security)) { eng ->
        save(doc)
        val out = tempFile("pdf")
        val copy = eng.tool("doc_open", mapOf("path" to doc.file.absolutePath, "password" to doc.password)).optLong("doc")
        try {
            Diag.measure(DiagArea.Pages, "unprotect_copy", "doc" to doc.diagNumber, "encrypted" to doc.info.encrypted) {
                eng.tool("doc_unprotect", mapOf("doc" to copy))
                eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true))
            }
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            runCatching { eng.tool("doc_close", mapOf("doc" to copy, "discard_changes" to true)) }
        }
        toolScreen = null
        deliver(out, "${doc.title}_unprotected.pdf")
    }

    // ---- Remove hidden information (#177) ------------------------------------------------------

    /** Categories of hidden information with their counts (`doc_hidden_info`), or null. */
    suspend fun hiddenInfo(doc: OpenDocument): List<Pair<String, Int>>? {
        val eng = engine ?: return null
        return try {
            val a = eng.tool("doc_hidden_info", mapOf("doc" to doc.id)).optJSONArray("categories") ?: return emptyList()
            (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("category") to o.optInt("count") } }
        } catch (e: MobileException) { tell(e.userMessage); null }
    }

    /** Remove [categories] (all of them, Sanitize, when null); undoable until saved. */
    fun removeHidden(doc: OpenDocument, categories: List<String>?) {
        doc.commitTool?.invoke()
        runTool(doc, "doc_remove_hidden", if (categories == null) emptyMap() else mapOf("categories" to categories)) {
            doc.controller.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.None
            Diag.event(DiagArea.Pages, "hidden_removed", "doc" to doc.diagNumber, "all" to (categories == null), "categories" to (categories?.size ?: -1))
            tell(text(R.string.msg_hidden_removed), text(R.string.common_undo)) { undo(doc) }
        }
    }

    /** Crop [page] (or every page) to [rect] (points from the page's top-left). */
    fun crop(doc: OpenDocument, page: Int?, rect: FloatArray) {
        val args = mutableMapOf<String, Any?>("rect" to rect.map { it.toDouble() })
        if (page != null) args["pages"] = listOf(page + 1)
        runTool(doc, "page_set_box", args) {
            toolScreen = null
            tell(text(if (page == null) R.string.msg_crop_all else R.string.msg_crop_page))
        }
    }

    /**
     * Export the PDF's embedded outline (not the reader's private, on-device bookmarks) to a
     * portable JSON tree. Rust owns the schema, nesting limit and atomic temporary write.
     * Only the finished JSON is published to Downloads; source PDF bytes are never modified.
     */
    fun exportEmbeddedBookmarks(doc: OpenDocument) = job(text(R.string.sheet_export_embedded_bookmarks)) { eng ->
        val out = tempFile("json")
        try {
            eng.tool("bookmark_export", mapOf("doc" to doc.id, "out" to out.absolutePath))
            require(out.isFile && out.length() > 0L) { text(R.string.sheet_export_embedded_bookmarks_failed) }
            deliver(out, "${DocumentFiles.safeName(doc.title)}_bookmarks.json", "application/json")
        } finally {
            // deliver() removes the temp file on success; an engine/provider error must do so too.
            out.delete()
        }
    }

    /** Append heading-derived contents with the native engine; reject stale style analyses. */
    internal fun createStyleBookmarks(
        doc: OpenDocument,
        generation: ULong,
        styles: List<io.github.artcraftandroid.pdfcraft.engine.PdfTextStyle>,
    ) = job(text(R.string.text_styles_creating)) { eng ->
        if (document !== doc || doc.info.generation != generation || !doc.writable ||
            doc.info.signed || doc.info.readOnlyReason != null || !doc.info.allowsCopying || !doc.info.allowsAssembly) {
            tell(text(R.string.text_styles_unavailable))
            return@job
        }
        val levels = io.github.artcraftandroid.pdfcraft.engine.headingStyleArguments(styles)
        eng.command {
            // Check and mutate in one serialized native command: a queued double tap or another
            // edit cannot apply a second tree from the same stale analysis.
            val current = document(doc.id)
            require(current.generation == generation && !current.signed && current.readOnlyReason == null &&
                current.allowsCopying && current.allowsAssembly) { text(R.string.text_styles_unavailable) }
            execute("bookmark_from_text_styles", Engine.toJson(mapOf("doc" to doc.id, "levels" to levels)).toString())
        }
        refresh(doc)
        if (document === doc) doc.controller.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.Bookmarks
        tell(text(R.string.text_styles_created))
    }

    /** Ask Android SAF to pick JSON for the currently displayed PDF, not for private bookmarks. */
    val bookmarkJsonPicks = Channel<Unit>(Channel.CONFLATED)
    private var bookmarkJsonTarget: OpenDocument? = null

    fun pickEmbeddedBookmarksImport(doc: OpenDocument) {
        if (document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
            tell(text(R.string.sheet_import_embedded_bookmarks_unavailable))
            return
        }
        bookmarkJsonTarget = doc
        bookmarkJsonPicks.trySend(Unit)
    }

    /** SAF picker result, including cancellation. A changed/closed target is never edited. */
    fun onBookmarkJsonPicked(uri: Uri?) {
        val doc = bookmarkJsonTarget
        bookmarkJsonTarget = null
        if (uri == null || doc == null) return
        if (document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
            tell(text(R.string.sheet_import_embedded_bookmarks_unavailable))
            return
        }
        job(text(R.string.sheet_import_embedded_bookmarks)) { eng ->
            val staged = tempFile("json")
            try {
                withContext(Dispatchers.IO) {
                    val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException(text(R.string.sheet_import_embedded_bookmarks_unreadable))
                    val copyContext = currentCoroutineContext()
                    stream.use { input ->
                        staged.outputStream().use { output ->
                            EmbeddedBookmarkJson.copyBounded(input, output) { copyContext.ensureActive() }
                        }
                    }
                }
                // The SAF provider may be slow. Recheck after staging and before every write.
                if (document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
                    tell(text(R.string.sheet_import_embedded_bookmarks_unavailable))
                    return@job
                }
                val result = eng.tool("bookmark_import", mapOf(
                    "doc" to doc.id, "file" to staged.absolutePath, "replace" to false,
                ))
                refresh(doc) // Rebuild the outline, generation, and Undo / Redo state.
                tell(text(R.string.sheet_import_embedded_bookmarks_done))
                val unmapped = result.optJSONArray("unmapped")?.length() ?: 0
                if (unmapped > 0) tell(text(R.string.sheet_import_embedded_bookmarks_unmapped, unmapped))
                if (result.optBoolean("truncated", false)) {
                    tell(text(R.string.sheet_import_embedded_bookmarks_truncated))
                }
            } finally {
                // Failure, cancellation, bad JSON and no-storage paths must not persist source data.
                staged.delete()
            }
        }
    }

    /** Copy another PDF's actual embedded outline, never the reader's private bookmark store. */
    val bookmarkPdfPicks = Channel<Unit>(Channel.CONFLATED)
    private var bookmarkCopyTarget: OpenDocument? = null

    fun pickEmbeddedBookmarksCopy(doc: OpenDocument) {
        if (document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
            tell(text(R.string.sheet_copy_embedded_bookmarks_unavailable))
            return
        }
        bookmarkCopyTarget = doc
        bookmarkPdfPicks.trySend(Unit)
    }

    /** SAF result can be null on cancel; a stale/closed target is never edited. */
    fun onBookmarkPdfPicked(uri: Uri?) {
        val doc = bookmarkCopyTarget
        bookmarkCopyTarget = null
        if (uri == null || doc == null) return
        if (document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
            tell(text(R.string.sheet_copy_embedded_bookmarks_unavailable))
            return
        }
        if (uri == doc.source) {
            tell(text(R.string.sheet_copy_embedded_bookmarks_same_source))
            return
        }
        job(text(R.string.sheet_copy_embedded_bookmarks_busy)) { eng ->
            val source = tempFile("pdf")
            try {
                withContext(Dispatchers.IO) {
                    val size = files.size(uri)
                    if (size != null && size > SafBoundedCopy.MAX_BOOKMARK_SOURCE_PDF_BYTES) {
                        throw java.io.IOException(text(R.string.sheet_copy_embedded_bookmarks_too_large))
                    }
                    val stream = getApplication<Application>().contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException(text(R.string.sheet_copy_embedded_bookmarks_unreadable))
                    val copyContext = currentCoroutineContext()
                    stream.use { input ->
                        source.outputStream().use { output ->
                            SafBoundedCopy.copy(input, output, SafBoundedCopy.MAX_BOOKMARK_SOURCE_PDF_BYTES, "PDF") {
                                copyContext.ensureActive()
                            }
                        }
                    }
                }
                if (document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
                    tell(text(R.string.sheet_copy_embedded_bookmarks_unavailable))
                    return@job
                }

                // A second native document is opened read-only. The originating SAF URI is never
                // passed to Rust, and the temporary source is closed and removed in all outcomes.
                withTemporaryBookmarkSource(
                    open = {
                        val opened = eng.tool("doc_open", mapOf("path" to source.absolutePath))
                        opened.optLong("doc", 0L).also {
                            if (it <= 0L) throw java.io.IOException(text(R.string.sheet_copy_embedded_bookmarks_unreadable))
                        }
                    },
                    close = { from ->
                        runCatching { eng.tool("doc_close", mapOf("doc" to from, "discard_changes" to true)) }
                        Unit
                    },
                ) { from ->
                    val sourceInfo = eng.command { document(from.toULong()) }
                    if (!sourceInfo.allowsCopying) {
                        tell(text(R.string.sheet_copy_embedded_bookmarks_copy_restricted))
                        return@withTemporaryBookmarkSource
                    }
                    if (!sourceInfo.hasOutline) {
                        tell(text(R.string.sheet_copy_embedded_bookmarks_empty))
                        return@withTemporaryBookmarkSource
                    }
                    if (document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
                        tell(text(R.string.sheet_copy_embedded_bookmarks_unavailable))
                        return@withTemporaryBookmarkSource
                    }
                    val result = eng.tool("bookmark_copy", mapOf("doc" to doc.id, "from" to from, "replace" to false))
                    refresh(doc)
                    tell(text(R.string.sheet_copy_embedded_bookmarks_done))
                    val unmapped = result.optJSONArray("unmapped")?.length() ?: 0
                    if (unmapped > 0) tell(text(R.string.sheet_import_embedded_bookmarks_unmapped, unmapped))
                    if (result.optBoolean("truncated", false)) {
                        tell(text(R.string.sheet_import_embedded_bookmarks_truncated))
                    }
                }
            } finally {
                source.delete()
            }
        }
    }

    /** Export [doc] as a Word, RTF, Excel or PowerPoint document. */
    fun exportDocument(doc: OpenDocument, ext: String) = job(text(R.string.msg_busy_exporting_to, ext.uppercase())) { eng ->
        val out = tempFile(ext)
        if (ext == "txt") eng.tool("doc_export_text", mapOf("doc" to doc.id, "path" to out.absolutePath))
        else eng.tool("doc_export_office", mapOf("doc" to doc.id, "path" to out.absolutePath))
        toolScreen = null
        val mime = when (ext) {
            "txt" -> "text/plain"
            "md" -> "text/markdown"
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "rtf" -> "application/rtf"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "text/html"
        }
        deliver(out, "${doc.title}.$ext", mime)
    }

    /** Export to images: the format chosen, while its pages are being picked in the page grid. */
    var imageExport by mutableStateOf<String?>(null)

    /** The image format last exported to, offered first next time (as the reference remembers it). */
    val lastImageFormat: String get() = appPrefs.getString("imageFormat", "jpg") ?: "jpg"

    /** Export to images: remember the format, then pick the pages in the page grid (Save or Share there). */
    fun exportImages(doc: OpenDocument, ext: String) {
        appPrefs.edit().putString("imageFormat", ext).apply()
        toolScreen = null
        imageExport = ext
        organizing = true
        doc.controller.pagesOpen = true
    }

    /** Export to images: resolution (dpi) and JPEG quality, as last used. */
    var imageDpi by mutableStateOf(appPrefs.getInt("imageDpi", 150))
        private set
    var imageQuality by mutableStateOf(appPrefs.getInt("imageQuality", 85))
        private set

    fun updateImageSettings(dpi: Int, quality: Int) {
        imageDpi = dpi
        imageQuality = quality
        appPrefs.edit().putInt("imageDpi", dpi).putInt("imageQuality", quality).apply()
    }

    /**
     * Pictures of [pages] (0-based) drawn by the engine (`doc_export_images`) at [imageDpi], saved
     * (PNG and JPEG to Pictures/PdfCraft, TIFF to Downloads) or handed to the share sheet.
     */
    fun writeImages(doc: OpenDocument, ext: String, pages: List<Int>, share: Boolean) = job(text(R.string.msg_busy_exporting_images)) { eng ->
        val format = when (ext) { "png" -> "png"; "tif", "tiff" -> "tiff"; else -> "jpeg" }
        val mime = when (format) { "png" -> "image/png"; "tiff" -> "image/tiff"; else -> "image/jpeg" }
        val dir = File(getApplication<Application>().cacheDir, "images/${System.nanoTime()}").apply { mkdirs() }
        try {
            val r = Diag.measure(DiagArea.Pages, "export_pages_render", "doc" to doc.diagNumber, "format" to format, "dpi" to imageDpi, "pages" to pages.size) {
                eng.tool("doc_export_images", mapOf(
                    "doc" to doc.id, "folder" to dir.absolutePath, "format" to format, "dpi" to imageDpi, "quality" to imageQuality,
                    "pages" to pages.map { it + 1 },
                ))
            }
            val made = exportedFiles(r).map { f -> f to "${doc.title}_${f.name.substringAfterLast("_page_")}" }
            if (Diag.enabled()) Diag.event(DiagArea.Pages, "export_pages_result", "doc" to doc.diagNumber, "files" to made.size,
                "bytes" to made.sumOf { it.first.length() }, "lower_dpi" to (r.optJSONArray("lower_dpi")?.length() ?: 0), "share" to share)
            imageExport = null
            organizing = false
            doc.controller.pagesOpen = false
            val lower = r.optJSONArray("lower_dpi")?.length() ?: 0
            if (share) {
                val uris = withContext(Dispatchers.IO) { files.shareableAll(made) }
                intents.trySend(Intent.createChooser(sendIntent(uris, mime), null))
            } else {
                Diag.measure(DiagArea.Pages, "export_pages_save", "files" to made.size, "to_downloads" to (format == "tiff")) {
                    withContext(Dispatchers.IO) {
                        for ((f, name) in made) if (format == "tiff") files.saveToDownloads(f, name, mime) else files.saveToPictures(f, name, mime)
                    }
                }
                tell(plural(if (format == "tiff") R.plurals.msg_images_saved_downloads else R.plurals.msg_images_saved, made.size, made.size))
            }
            if (lower > 0) tell(plural(R.plurals.msg_images_lower_dpi, lower, lower))
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
        }
    }

    /** The files an export reports in `files` (paths, or objects with a `path`), in its order. */
    private fun exportedFiles(r: JSONObject): List<File> {
        val a = r.optJSONArray("files") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> (a.optJSONObject(i)?.optString("path") ?: a.optString(i)).takeIf { it.isNotEmpty() }?.let(::File) }.filter { it.isFile }
    }

    /**
     * Pictures in the PDF: every image the pages use, once each (`doc_export_all_images`), saved
     * into a folder the person picks or shared; images under 64 px are skipped when [skipSmall].
     */
    fun exportAllImages(doc: OpenDocument, skipSmall: Boolean, share: Boolean) = job(text(R.string.msg_busy_exporting_images)) { eng ->
        val dir = File(getApplication<Application>().cacheDir, "images/${System.nanoTime()}").apply { mkdirs() }
        try {
            val r = Diag.measure(DiagArea.Pages, "export_all_images", "doc" to doc.diagNumber, "skip_small" to skipSmall) {
                eng.tool("doc_export_all_images", mapOf("doc" to doc.id, "folder" to dir.absolutePath, "min_size" to if (skipSmall) 64 else 0))
            }
            val made = exportedFiles(r).map { it to it.name }
            val skipped = r.optJSONArray("skipped")
            if (Diag.enabled()) Diag.event(DiagArea.Pages, "export_all_images_result", "doc" to doc.diagNumber, "files" to made.size,
                "bytes" to made.sumOf { it.first.length() }, "skipped" to (skipped?.length() ?: 0), "share" to share)
            val reasons = (0 until (skipped?.length() ?: 0)).mapNotNull { skipped!!.optJSONObject(it)?.optString("reason")?.takeIf(String::isNotBlank) }.distinct()
            toolScreen = null
            if (made.isEmpty()) {
                tell(text(R.string.msg_no_images_found))
                dir.deleteRecursively()
                return@job
            }
            if (reasons.isNotEmpty()) tell(plural(R.plurals.msg_images_skipped, skipped!!.length(), skipped.length(), reasons.take(3).joinToString("; ")))
            if (share) {
                val uris = try { withContext(Dispatchers.IO) { files.shareableAll(made) } } finally { dir.deleteRecursively() }
                intents.trySend(Intent.createChooser(sendIntent(uris, "image/*"), null))
            } else {
                saveAllToFolder(made, "image/*", cleanup = dir)
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Create PDF from picked files (images, text, Office documents, PDFs), combined in order, then open it. */
    private fun createFrom(picked: List<Uri>) = job(text(R.string.msg_busy_creating_pdf)) { eng ->
        // at most 12 files at once, as the reference allows
        if (picked.size > MAX_CREATE_FILES) tell(plural(R.plurals.msg_create_first_files, MAX_CREATE_FILES, MAX_CREATE_FILES))
        val uris = picked.take(MAX_CREATE_FILES)
        val copies = mutableListOf<File>()
        val out = tempFile("pdf")
        try {
            val paths = withContext(Dispatchers.IO) { uris.map { copyIn(it).also(copies::add).absolutePath } }
            eng.tool("doc_create_multiple", mapOf("paths" to paths, "out" to out.absolutePath, "mode" to "combine"))
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            dropPicked(copies)
        }
        val name = files.displayNameRaw(uris.first()).substringBeforeLast('.') + ".pdf"
        val uri = withContext(Dispatchers.IO) { files.saveToDownloads(out, DocumentFiles.safeName(name)).also { out.delete() } }
        tell(text(R.string.msg_saved_downloads, name))
        open(uri)
    }

    /** Pages [pages] (0-based) of [doc] as a new PDF in Downloads. */
    fun extractPages(doc: OpenDocument, pages: List<Int>) = job(text(R.string.msg_busy_extracting)) { eng ->
        val out = tempFile("pdf")
        Diag.measure(DiagArea.Pages, "extract_pages", "doc" to doc.diagNumber, "pages" to pages.size) {
            eng.tool("page_extract", mapOf("doc" to doc.id, "pages" to pages.map { it + 1 }, "out" to out.absolutePath))
        }
        deliver(out, "Pages from ${doc.title}.pdf")
    }

    // ---- Flashcards, Library, Study sessions, Study digest, Notebooks: see StudyController ----

    val study = StudyController(this)

    // ---- Organize: duplicate, replace, reverse, split, page labels (#165) ---------------------

    /** Copies of [pages] (0-based), placed right after the last of them; one undo step. */
    fun duplicatePages(doc: OpenDocument, pages: List<Int>) {
        if (pages.isEmpty()) return
        runTool(doc, "page_duplicate", mapOf("pages" to pages.map { it + 1 })) {
            tell(plural(R.plurals.msg_pages_duplicated, pages.size, pages.size))
        }
    }

    /** Reverse the order of [pages] (0-based) among their positions, or of every page when fewer than two are picked. */
    fun reversePages(doc: OpenDocument, pages: List<Int>) {
        val args = if (pages.size >= 2) mapOf("pages" to pages.map { it + 1 }) else emptyMap()
        runTool(doc, "page_reverse", args) { tell(text(R.string.msg_pages_reversed)) }
    }

    /** Label pages [from]..[to] (0-based, inclusive) as the viewer's page indicator then shows them. */
    fun labelPages(doc: OpenDocument, from: Int, to: Int, style: String, prefix: String, start: Int) {
        runTool(doc, "page_number", mapOf(
            "from" to from + 1, "to" to to + 1, "style" to style, "prefix" to prefix, "start" to start.coerceAtLeast(1),
        )) { tell(text(R.string.msg_pages_labelled)) }
    }

    /** Replace with: the pages being replaced, then the picked PDF and its first page to use. */
    var replacePrompt by mutableStateOf<ReplacePrompt?>(null)
    private var replaceTarget: Pair<OpenDocument, List<Int>>? = null

    /** Pick a PDF whose pages take the place of [pages] (0-based). */
    fun pickReplaceFile(doc: OpenDocument, pages: List<Int>) {
        if (pages.isEmpty()) return
        replaceTarget = doc to pages
        pickFiles(FilePickKind.Replace)
    }

    /** Replace [ReplacePrompt.pages] with the picked file's pages from [firstPage] (1-based) on. */
    fun replacePages(prompt: ReplacePrompt, firstPage: Int) {
        replacePrompt = null
        val from = (0 until prompt.pages.size).map { firstPage.coerceAtLeast(1) + it }
        runTool(prompt.doc, "page_replace", mapOf(
            "pages" to prompt.pages.map { it + 1 }, "path" to prompt.file.absolutePath, "from_pages" to from,
        ), onSettled = { dropPicked(listOf(prompt.file)) }) {
            tell(plural(R.plurals.msg_pages_replaced, prompt.pages.size, prompt.pages.size))
        }
    }

    fun cancelReplace() {
        replacePrompt?.let { dropPicked(listOf(it.file)) }
        replacePrompt = null
    }

    /** The Split sheet, open over the page grid. */
    var splitOpen by mutableStateOf(false)

    /**
     * Split [doc] by [mode] into parts, then save them to a folder the person picks (or share them).
     * Parts keep the engine's names: `<name>-partK.pdf`, or the bookmark titles.
     */
    fun split(doc: OpenDocument, mode: SplitMode, share: Boolean) = job(text(R.string.msg_busy_splitting)) { eng ->
        val dir = File(getApplication<Application>().cacheDir, "split/${System.nanoTime()}").apply { mkdirs() }
        try {
            val args = mutableMapOf<String, Any?>("doc" to doc.id, "out_dir" to dir.absolutePath)
            args += mode.args()
            val r = Diag.measure(DiagArea.Pages, "split", "doc" to doc.diagNumber, "share" to share) { eng.tool("doc_split", args) }
            val list = r.optJSONArray("files")
            val parts = (0 until (list?.length() ?: 0)).map { File(list!!.getJSONObject(it).getString("path")) }.filter { it.isFile }
            if (Diag.enabled()) Diag.event(DiagArea.Pages, "split_result", "doc" to doc.diagNumber, "files" to parts.size, "bytes" to parts.sumOf { it.length() })
            if (parts.isEmpty()) {
                tell(text(R.string.msg_split_nothing))
                dir.deleteRecursively()
                return@job
            }
            splitOpen = false
            val named = parts.map { it to it.name }
            if (share) {
                val uris = try { withContext(Dispatchers.IO) { files.shareableAll(named) } } finally { dir.deleteRecursively() }
                intents.trySend(Intent.createChooser(sendIntent(uris), null))
            } else {
                saveAllToFolder(named, "application/pdf", cleanup = dir)
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    // ---- Layers, attachments and the PDF's own bookmarks (#174, #175, #176) ------------------

    suspend fun layers(doc: OpenDocument) = engine?.let { e -> runCatching { e.command { layers(doc.id) } }.getOrNull() }.orEmpty()

    /**
     * Show or hide layer [position] (1-based, as [layers] lists them). A view setting: the file is
     * not changed, so only the pages are drawn again. [onDone] runs either way.
     */
    fun setLayer(doc: OpenDocument, position: Int, visible: Boolean, onDone: () -> Unit) {
        val eng = engine ?: return
        viewModelScope.launch {
            try {
                Diag.measure(DiagArea.Pages, "layer_set", "doc" to doc.diagNumber, "visible" to visible) {
                    eng.tool("layer_set", mapOf("doc" to doc.id, "layer" to position, "visible" to visible))
                }
                doc.images.invalidate()
            } catch (e: MobileException) {
                tell(e.userMessage)
            } finally {
                onDone()
            }
        }
    }

    /** Add file: the picked file, waiting for its description. */
    var attachPrompt by mutableStateOf<AttachPrompt?>(null)
    private var attachTo: OpenDocument? = null

    fun pickAttachment(doc: OpenDocument) {
        attachTo = doc
        pickFiles(FilePickKind.Attach)
    }

    /** Attach [AttachPrompt.file] to the document with [description] (may be blank); one undo step. */
    fun attach(prompt: AttachPrompt, description: String) {
        attachPrompt = null
        val args = mutableMapOf<String, Any?>("path" to prompt.file.absolutePath, "name" to prompt.name)
        if (description.isNotBlank()) args["description"] = description.trim()
        runTool(prompt.doc, "attachment_add", args, onSettled = { dropPicked(listOf(prompt.file)) }) {
            tell(text(R.string.msg_attached, prompt.name), text(R.string.common_undo)) { undo(prompt.doc) }
        }
    }

    fun cancelAttach() {
        attachPrompt?.let { dropPicked(listOf(it.file)) }
        attachPrompt = null
    }

    /** Remove the document-level attachment [name]; Undo in the snackbar brings it back. */
    fun deleteAttachment(doc: OpenDocument, name: String) {
        runTool(doc, "attachment_delete", mapOf("name" to name)) {
            tell(text(R.string.msg_attachment_removed, name), text(R.string.common_undo)) { undo(doc) }
        }
    }

    /** Every embedded bookmark with its path and style (`bookmark_list`, page by page; at most [cap]). */
    suspend fun outlineRows(doc: OpenDocument, cap: Int = 5000): List<OutlineRow>? {
        val eng = engine ?: return null
        return try {
            val out = mutableListOf<OutlineRow>()
            var offset = 0
            while (out.size < cap) {
                val r = eng.tool("bookmark_list", mapOf("doc" to doc.id, "offset" to offset, "limit" to 1000))
                fun walk(a: org.json.JSONArray?) {
                    if (a == null) return
                    for (i in 0 until a.length()) {
                        val o = a.getJSONObject(i)
                        val path = o.getJSONArray("path").let { p -> (0 until p.length()).map { p.getInt(it) } }
                        val color = o.optJSONArray("color")?.let { c -> (0 until c.length()).map { c.getDouble(it).toFloat() } }
                        val page = if (o.isNull("page")) null else o.optInt("page", 0).takeIf { it > 0 }?.minus(1)
                        out += OutlineRow(path, o.optString("title"), page, color, o.optBoolean("bold"), o.optBoolean("italic"))
                        walk(o.optJSONArray("children"))
                    }
                }
                walk(r.optJSONArray("bookmarks"))
                if (r.isNull("next")) break
                offset = r.optInt("next", -1).takeIf { it > offset } ?: break
            }
            out
        } catch (e: MobileException) {
            tell(e.userMessage)
            null
        }
    }

    /** Run bookmark tool [name] on the bookmark at [path] (with [args]); one undo step each. */
    fun editBookmark(doc: OpenDocument, name: String, path: List<Int>?, args: Map<String, Any?> = emptyMap()) {
        runTool(doc, name, (if (path != null) mapOf("path" to path) else emptyMap<String, Any?>()) + args)
    }

    // ---- Document properties, word count, flatten (#183) -------------------------------------

    /** Write metadata [changes] (`doc_set_info` arguments), one refresh for all. */
    fun setDocInfo(doc: OpenDocument, changes: List<Map<String, Any?>>) {
        if (changes.isEmpty()) return
        // each entry its own group: one undo step per changed entry, one refresh for all
        runToolBatch(doc, "doc_set_info", changes.map { listOf(it) }) { applied ->
            if (applied == changes.size) tell(text(R.string.msg_properties_saved))
        }
    }

    /** How the document opens (`doc_initial_view` with no change), or null when it can't be read. */
    suspend fun initialView(doc: OpenDocument): JSONObject? {
        val eng = engine ?: return null
        return try { eng.tool("doc_initial_view", mapOf("doc" to doc.id)).optJSONObject("initial_view") }
        catch (e: MobileException) { tell(e.userMessage); null }
    }

    fun setInitialView(doc: OpenDocument, args: Map<String, Any?>) {
        if (args.isEmpty()) return
        runTool(doc, "doc_initial_view", args) { tell(text(R.string.msg_initial_view_saved)) }
    }

    /** Words and characters on every page (`text_word_count`); the message says why when it can't. */
    suspend fun wordCount(doc: OpenDocument): JSONObject? {
        val eng = engine ?: return null
        return try { eng.tool("text_word_count", mapOf("doc" to doc.id)).optJSONObject("total") }
        catch (e: MobileException) { tell(e.userMessage); null }
    }

    fun wordCountLabel(words: Long, characters: Long): String {
        val f = java.text.NumberFormat.getIntegerInstance()
        return text(R.string.properties_word_count_value, f.format(words), f.format(characters))
    }

    /** Flatten: the document asking for confirmation, while the dialog shows. */
    var flattenPrompt by mutableStateOf<OpenDocument?>(null)

    fun flatten(doc: OpenDocument, comments: Boolean, fields: Boolean) {
        flattenPrompt = null
        if (!comments && !fields) return
        doc.commitTool?.invoke()
        runTool(doc, "doc_flatten", mapOf("comments" to comments, "fields" to fields)) {
            Diag.event(DiagArea.Pages, "flattened", "doc" to doc.diagNumber, "comments" to comments, "fields" to fields)
            tell(text(R.string.msg_flattened), text(R.string.common_undo)) { undo(doc) }
        }
    }

    /** Check & prepare: PDF/A, print readiness and thin lines (#181). */
    fun openStandards(doc: OpenDocument) {
        doc.commitTool?.invoke()
        toolScreen = ToolScreen.Standards(doc)
    }

    /** A read-only report from [tool] (null, with the reason told, when it fails). */
    suspend fun report(doc: OpenDocument, tool: String, args: Map<String, Any?> = emptyMap()): JSONObject? {
        val eng = engine ?: return null
        return try { eng.tool(tool, args + ("doc" to doc.id)) } catch (e: MobileException) { tell(e.userMessage); null }
    }

    /** Save as PDF/A at [level] (`pdfa_convert`, one undo step); [onDone] gets what still breaks the rules. */
    fun convertToPdfa(doc: OpenDocument, level: String, onDone: (PdfaReport) -> Unit) {
        runTool(doc, "pdfa_convert", mapOf("level" to level)) { r ->
            val report = parsePdfaReport(r)
            Diag.event(DiagArea.Pages, "pdfa_convert", "doc" to doc.diagNumber, "level" to "pdfa${level.lowercase()}", "compliant" to report.compliant, "issues_left" to report.issues.size)
            onDone(report)
            tell(text(if (report.compliant) R.string.std_msg_pdfa_done else R.string.std_msg_pdfa_partly), text(R.string.common_undo)) { undo(doc) }
        }
    }

    /** Remove the PDF/A identification (`standards_remove_id`); one undo step. */
    fun removePdfaId(doc: OpenDocument, onDone: () -> Unit) {
        runTool(doc, "standards_remove_id", mapOf("standard" to "pdfa")) {
            onDone()
            tell(text(R.string.std_msg_pdfa_id_removed), text(R.string.common_undo)) { undo(doc) }
        }
    }

    /** Fix thin lines (`doc_fix_hairlines`): says how many strokes changed; one undo step when any did. */
    fun fixHairlines(doc: OpenDocument, fix: HairlineFix, pages: List<Int>?) {
        runTool(doc, "doc_fix_hairlines", fix.args(pages)) { r ->
            val n = r.optInt("fixed")
            Diag.event(DiagArea.Pages, "fix_hairlines", "doc" to doc.diagNumber, "fixed" to n, "pages" to r.optInt("pages"), "all_pages" to (pages == null))
            if (n == 0) tell(text(R.string.std_msg_no_hairlines))
            else tell(plural(R.plurals.std_msg_hairlines_fixed, n, n, r.optInt("pages")), text(R.string.common_undo)) { undo(doc) }
        }
    }

    /** Print options (#185): lay the pages out with `doc_print` before the system print dialog. */
    fun openPrintOptions(doc: OpenDocument) {
        if (!doc.info.allowsPrinting) {
            tell(text(R.string.msg_print_disabled))
            return
        }
        toolScreen = ToolScreen.PrintOptions(doc)
    }

    /**
     * Print [doc] with [options]: unchanged options print as [print] always has; otherwise the
     * print-ready PDF `doc_print` makes goes to the print dialog, or with [save] to Downloads.
     */
    fun printWithOptions(doc: OpenDocument, options: PrintOptions, save: Boolean) {
        if (options.isDefault && !save) {
            toolScreen = null
            print(doc)
            return
        }
        if (doc.controller.tool == ViewerTool.Draw) doc.commitTool?.invoke()
        job(text(R.string.print_busy)) { eng ->
            val dir = File(getApplication<Application>().cacheDir, "print/${System.nanoTime()}").apply { mkdirs() }
            val out = File(dir, "${doc.title}-print.pdf")
            try {
                val r = Diag.measure(DiagArea.Pages, "print_layout", "doc" to doc.diagNumber, "layout" to options.layout, "per_sheet" to options.perSheet,
                    "subset" to options.subset, "reverse" to options.reverse, "page_range" to options.pages.isNotBlank(), "save" to save) {
                    eng.tool("doc_print", options.args(out.absolutePath) + ("doc" to doc.id))
                }
                Diag.event(DiagArea.Pages, "print_layout_result", "doc" to doc.diagNumber, "sheets" to r.optInt("sheets"), "bytes" to out.length())
                toolScreen = null
                if (save) {
                    deliver(out, out.name, "application/pdf")
                    dir.deleteRecursively()
                } else {
                    // the print job deletes it when it finishes
                    prints.trySend(PrintJob(out, doc.name, deleteWhenDone = true))
                }
            } catch (e: Exception) {
                dir.deleteRecursively()
                throw e
            }
        }
    }

    /** Page marks: headers and footers, watermarks and backgrounds. */
    fun openPageMarks(doc: OpenDocument) {
        doc.commitTool?.invoke()
        toolScreen = ToolScreen.PageMarks(doc)
    }

    /** The marks [doc] already has, for Update and Remove (empty when they can't be read). */
    suspend fun pageMarks(doc: OpenDocument): Set<MarkKind> = engine?.let { e ->
        runCatching { e.command { pageMarks(doc.id) } }.getOrNull()
    }.orEmpty().mapNotNull { k -> MarkKind.entries.firstOrNull { it.key == k } }.toSet()

    /** The picture a watermark or background is to use, picked with [pickMarkImage]. */
    var markImage by mutableStateOf<File?>(null)
        private set

    fun pickMarkImage() = pickFiles(FilePickKind.MarkImage)

    /** Forget the picked picture (and delete its copy). */
    fun clearMarkImage() {
        markImage?.let { dropPicked(listOf(it)) }
        markImage = null
    }

    /** Add (or, with [replace], update) a mark of [kind] with [args]; one undo step. */
    fun applyMark(doc: OpenDocument, kind: MarkKind, args: Map<String, Any?>, replace: Boolean) {
        runTool(doc, kind.tool, args) {
            Diag.event(DiagArea.Pages, "mark_applied", "doc" to doc.diagNumber, "kind" to kind, "replace" to replace,
                "picture" to args.containsKey("file"), "pages" to ((args["pages"] as? List<*>)?.size ?: -1))
            toolScreen = null
            clearMarkImage()
            val msg = when (kind) {
                MarkKind.HeaderFooter -> if (replace) R.string.marks_msg_hf_updated else R.string.marks_msg_hf_added
                MarkKind.Watermark -> if (replace) R.string.marks_msg_wm_updated else R.string.marks_msg_wm_added
                MarkKind.Background -> if (replace) R.string.marks_msg_bg_updated else R.string.marks_msg_bg_added
            }
            tell(text(msg), text(R.string.common_undo)) { undo(doc) }
        }
    }

    /** Remove every mark of [kind]; one undo step. */
    fun removeMarks(doc: OpenDocument, kind: MarkKind, onDone: () -> Unit) {
        runTool(doc, "doc_remove_marks", mapOf("kind" to kind.key)) { r ->
            onDone()
            val warn = r.optJSONArray("warnings")?.length() ?: 0
            Diag.event(DiagArea.Pages, "marks_removed", "doc" to doc.diagNumber, "kind" to kind, "warnings" to warn)
            tell(text(if (warn > 0) R.string.marks_msg_removed_with_warnings else R.string.marks_msg_removed), text(R.string.common_undo)) { undo(doc) }
        }
    }

    /** Files waiting for the person to pick the folder they go into, and what to delete afterwards. */
    private var folderOutput: FolderOutput? = null
    val outputFolderRequests = Channel<Unit>(Channel.CONFLATED)

    /** Ask for a folder (Storage Access Framework), then write [named] files into it. */
    internal fun saveAllToFolder(named: List<Pair<File, String>>, mime: String, cleanup: File? = null) {
        folderOutput?.let { old -> viewModelScope.launch(NonCancellable + Dispatchers.IO) { old.discard() } }
        folderOutput = FolderOutput(named, mime, cleanup)
        outputFolderRequests.trySend(Unit)
    }

    /** The folder picked for [folderOutput] (null: cancelled, the files are dropped). */
    fun onOutputFolderPicked(tree: Uri?) {
        val out = folderOutput ?: return
        folderOutput = null
        if (tree == null) {
            viewModelScope.launch(NonCancellable + Dispatchers.IO) { out.discard() }
            return
        }
        job(text(R.string.msg_busy_saving_files)) {
            try {
                val saved = Diag.measure(DiagArea.Pages, "save_to_folder", "files" to out.files.size) {
                    withContext(Dispatchers.IO) { files.saveAllToTree(tree, out.files, out.mime) }
                }
                Diag.event(DiagArea.Pages, "save_to_folder_result", "files" to out.files.size, "saved" to saved)
                tell(plural(R.plurals.msg_files_saved_folder, saved, saved))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { out.discard() }
            }
        }
    }

    // ---- Scan ---------------------------------------------------------------------------------

    fun takeScanPhoto(retake: Int? = null) {
        val dir = File(getApplication<Application>().cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "scan-${System.nanoTime()}.jpg")
        shotFile = f
        retakeIndex = retake
        cameraShots.trySend(f)
    }

    fun retakeScan(index: Int) = takeScanPhoto(index)

    /** The camera app came back: [ok] when it took the photo. */
    fun onScanPhoto(ok: Boolean) {
        val f = shotFile ?: return
        shotFile = null
        if (editor.imageShot) {
            editor.imageShot = false
            // after Android closed the app, the document the picture was for isn't open any more
            if (!ok || !f.isFile || f.length() == 0L || editor.imageFor == null) {
                f.delete()
                editor.imageFor = null
                editor.imageAt = null
                return
            }
            // turned upright as the camera meant it (the page keeps no orientation tag), then placed
            viewModelScope.launch {
                val upright = withContext(Dispatchers.IO) {
                    runCatching {
                        val bmp = android.graphics.BitmapFactory.decodeFile(f.absolutePath)?.let { rotateForExif(f, it) } ?: return@runCatching f
                        val out = File(f.parentFile, "upright-${f.name}")
                        out.outputStream().use { bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
                        out
                    }.getOrDefault(f)
                }
                editor.addImage(Uri.fromFile(upright))
            }
            return
        }
        if (!ok || !f.isFile || f.length() == 0L) {
            viewModelScope.launch {
                scanRestore?.join()
                if (scanPages.isEmpty()) toolScreen = null
            }
            return
        }
        viewModelScope.launch {
            // the pages taken before Android closed the app come first
            scanRestore?.join()
            val thumb = withContext(Dispatchers.IO) { scanThumb(f) } ?: return@launch
            val page = ScanPage(f, thumb)
            val r = retakeIndex
            retakeIndex = null
            if (r != null && r in scanPages.indices) scanPages[r] = page else scanPages.add(page)
            if (scanName.isBlank()) scanName = "Scan " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
            if (toolScreen != ToolScreen.Scan) toolScreen = ToolScreen.Scan
            // the document's edges in the photo, shown on the page and moved in Crop; the whole
            // photo is kept when none are found
            // (the engine may still be starting when the app was closed and opened again)
            val findStarted = android.os.SystemClock.elapsedRealtime()
            val eng = engine ?: application.engine.await().getOrNull()
            // One native call decodes the photo once, has the detector network find the page on a
            // small copy (#290) and moves each side onto the paper's edge at full size (#291). The
            // network's model is unpacked from the app's assets the first time; without it the
            // line detector is asked instead. The testing options (#288) can name the detector
            // and leave the closer look out.
            val lab = scanLab.options
            val doc = eng?.let {
                withContext(Dispatchers.IO) { runCatching { it.prepareOcrModels(getApplication()) } }
                withContext(Dispatchers.Default) { runCatching { io.github.artcraftandroid.pdfcraft.core.scanFindDocumentWith(f.absolutePath, lab.findOptions()) } }
                    .onFailure { e -> Diag.failure(DiagArea.Scan, "scan_find_document", e) }.getOrNull()
            }
            val edges = doc?.takeIf { it.found }?.corners?.takeIf { it.size == 8 }?.toFloatArray()
            // the models are unpacked by now: whether the flattening one is among them (#302)
            if (!scanCanFlatten && eng != null) {
                scanCanFlatten = withContext(Dispatchers.Default) { runCatching { io.github.artcraftandroid.pdfcraft.core.scanCanFlatten() }.getOrDefault(false) }
            }
            Diag.event(
                DiagArea.Scan, "photo", "retake" to (r != null), "bytes" to f.length(), "edges_found" to (edges != null),
                "find_ms" to android.os.SystemClock.elapsedRealtime() - findStarted,
                "source" to (doc?.source?.name?.lowercase() ?: "failed"), "confidence" to (doc?.confidence?.toDouble() ?: 0.0),
                "angle" to (doc?.angle?.toInt() ?: 0), "passes" to (doc?.passes?.toInt() ?: 0),
                *(doc?.timings.orEmpty().map<io.github.artcraftandroid.pdfcraft.core.ScanTiming, Pair<String, Any?>> { it.stage.removePrefix("scan_") + "_ms" to it.ms.toLong() }.toTypedArray()),
                "refined_sides" to (doc?.refined?.count { it } ?: 0), "curved_sides" to (doc?.curved?.count { it } ?: 0),
                "finder" to ScanLab.finderId(lab.finder), "refine" to lab.refine,
            )
            val finding = doc?.let { d ->
                ScanFinding(d.source.name.lowercase(), d.confidence.toDouble(), d.angle.toInt(), d.passes.toInt(), d.refined.count { it }, d.curved.count { it })
            }
            val at = scanPages.indexOfFirst { it.file == f }
            if (at >= 0) scanPages[at] = if (edges != null) scanPages[at].copy(corners = edges, found = edges, finding = finding) else scanPages[at].copy(finding = finding)
        }
    }

    private fun rotateForExif(f: File, bmp: android.graphics.Bitmap): android.graphics.Bitmap {
        val deg = runCatching {
            when (androidx.exifinterface.media.ExifInterface(f.absolutePath).getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)) {
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                androidx.exifinterface.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        }.getOrDefault(0f)
        if (deg == 0f) return bmp
        val m = android.graphics.Matrix().apply { postRotate(deg) }
        return android.graphics.Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
    }

    fun discardScan() {
        val discarded = scanPages.toList()
        if (scanLab.options.keep && discarded.isNotEmpty()) {
            // kept for testing first (#288): a scan given up on is often the one worth looking at
            viewModelScope.launch {
                withContext(Dispatchers.IO) {
                    scanLab.keepDiscarded(discarded)
                    discarded.forEach { it.file.delete() }
                }
                scanLab.refreshKept()
            }
        } else {
            discarded.forEach { it.file.delete() }
        }
        scanPageDir().listFiles()?.forEach { it.delete() }
        scanPages.clear()
        scanName = ""
        insertScanAt = null
        toolScreen = null
    }

    /** Where Save writes each scanned page's straightened image (removed when the scan ends). */
    private fun scanPageDir() = File(getApplication<Application>().cacheDir, "scan")

    // ---- Scan: the page as it will be saved (#301) ---------------------------------------------

    /** The latest page preview made for the scan screen, and the request it answers. */
    internal class ScanPreviewShown(val key: ScanPreviewKey, val image: androidx.compose.ui.graphics.ImageBitmap, val quarterTurns: Int, val flattened: Boolean)

    internal var scanPreview by mutableStateOf<ScanPreviewShown?>(null)
        private set

    /** The request whose preview could not be made (the screen then shows the photo, without waiting). */
    internal var scanPreviewFailed by mutableStateOf<ScanPreviewKey?>(null)
        private set

    /** Previews run one at a time, off the main thread and away from saving; the latest asked for wins. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scanPreviewDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var scanPreviewJob: kotlinx.coroutines.Job? = null
    /** The request asked for last: a preview of anything else is out of date when it arrives. */
    private var scanPreviewWanted: ScanPreviewKey? = null

    /** What the last preview of each photo was, for a kept scan's page file. */
    private val scanPreviewStats = java.util.concurrent.ConcurrentHashMap<String, ScanTestPreview>()

    /**
     * Make the preview of [page] with [filter] and [flatten] (#301), replacing any still waiting.
     * The native call can't be stopped once it starts, so an older one finishes and its result is
     * dropped when a newer request has come in meanwhile.
     */
    internal fun requestScanPreview(index: Int, page: ScanPage, filter: ScanFilter, flatten: Boolean) {
        val lab = scanLab.options
        val key = ScanPreviewKey.of(page, filter, flatten, lab)
        if (scanPreview?.key == key || scanPreviewWanted == key) return
        scanPreviewWanted = key
        scanPreviewJob?.cancel()
        scanPreviewJob = viewModelScope.launch(scanPreviewDispatcher) {
            val request = scanPageRequest(page.file.absolutePath, page.corners, filter, flatten, lab, "")
            val started = android.os.SystemClock.elapsedRealtime()
            val made = runCatching { io.github.artcraftandroid.pdfcraft.core.scanPreviewPage(request, SCAN_PREVIEW_SIDE.toUInt()) }
            val ms = android.os.SystemClock.elapsedRealtime() - started
            val p = made.getOrNull()
            val image = p?.let {
                runCatching {
                    val bmp = android.graphics.Bitmap.createBitmap(it.width.toInt(), it.height.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
                    // ARGB_8888 keeps its bytes in R, G, B, A order: the preview's own
                    bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(it.rgba))
                    bmp.asImageBitmap()
                }.getOrNull()
            }
            made.exceptionOrNull()?.let { Diag.failure(DiagArea.Scan, "scan_preview_page", it) }
            // what the preview took and whether it reused the straightened page (#301)
            Diag.event(
                DiagArea.Scan, "preview", "index" to index + 1, "filter" to filter, "flatten" to flatten, "made" to (image != null),
                "reused" to (p?.reused ?: false), "ms" to ms,
                *(p?.timings.orEmpty().map<io.github.artcraftandroid.pdfcraft.core.ScanTiming, Pair<String, Any?>> { it.stage.removePrefix("scan_") + "_ms" to it.ms.toLong() }.toTypedArray()),
                "width" to (p?.width?.toLong() ?: 0L), "height" to (p?.height?.toLong() ?: 0L),
                "quarter_turns" to (p?.quarterTurns?.toInt() ?: 0), "flattened" to (p?.flattened ?: false),
            )
            withContext(Dispatchers.Main) {
                // a newer request may have come in while this one ran: only the wanted one is shown
                if (scanPreviewWanted != key) return@withContext
                if (p != null && image != null) {
                    scanPreview = ScanPreviewShown(key, image, p.quarterTurns.toInt(), p.flattened)
                    scanPreviewStats[page.file.absolutePath] = ScanTestPreview(true, p.reused, ms, p.width.toInt(), p.height.toInt(), filter.mode)
                } else {
                    scanPreviewFailed = key
                }
            }
        }
    }

    /** A page's last preview for a kept scan, or "none shown" (#301). */
    private fun scanPreviewRecord(page: ScanPage): ScanTestPreview =
        scanPreviewStats[page.file.absolutePath] ?: ScanTestPreview(false, false, 0, 0, 0, null)

    /** The scan is over: forget the previews and the straightened pages kept for them. */
    private fun releaseScanPreviews() {
        scanPreviewJob?.cancel()
        scanPreviewJob = null
        scanPreviewWanted = null
        scanPreview = null
        scanPreviewFailed = null
        scanPreviewStats.clear()
        runCatching { io.github.artcraftandroid.pdfcraft.core.scanReleasePreviews() }
    }

    /**
     * Save the scanned pages as a PDF with [filter]'s look (or insert them where Organize asked).
     * Each photo is decoded once, straightened to the page's true proportions (#293), given the
     * look and encoded once natively (#287); a page that can't be processed goes in as the camera
     * took it. With [flatten], a curled, sagging or folded page is drawn flat by the flattening
     * network first (#302); that is the person's choice, never made for them.
     */
    fun saveScan(filter: ScanFilter, flatten: Boolean = false) = job(text(R.string.msg_busy_saving_scan)) { eng ->
        val pages = scanPages.toList()
        val scanStarted = android.os.SystemClock.elapsedRealtime()
        val dir = scanPageDir().apply { mkdirs() }
        val saved = ArrayList<ScanSavedPage>(pages.size)
        // the testing options in force (#288), and where this scan is kept when scans are kept
        val lab = scanLab.options
        val strength = lab.strengthFor(filter.level)
        val keepIn = withContext(Dispatchers.IO) { scanLab.begin() }
        for ((i, page) in pages.withIndex()) {
            // the Scan gates (#286): found edges, whether they were moved and how far (in % of the
            // photo's diagonal, so the thumbnail's proportions are enough)
            val stats = scanPageStats(page.corners, page.found, ScanPage.WHOLE, page.thumb.width.toDouble(), page.thumb.height.toDouble())
            // a whole photo in Original stays the camera's own file: natively it only gets its
            // resolution written in, so its PDF page has a size like the others (#293)
            val untouched = !flatten && !scanNeedsProcessing(page.corners.contentEquals(ScanPage.WHOLE), filter.mode)
            busy = text(R.string.msg_busy_cleaning_page, i + 1, pages.size)
            // the same request the screen's preview was made from (#301)
            val request = scanPageRequest(page.file.absolutePath, page.corners, filter, flatten, lab, File(dir, "page-$i").absolutePath)
            val enhanceStarted = android.os.SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.Default) { runCatching { io.github.artcraftandroid.pdfcraft.core.scanProcessPage(request) } }
            result.onFailure { Diag.failure(DiagArea.Scan, "scan_process_page", it) }
            val r = result.getOrNull()
            // decode, straighten, look and encode times (#287), the page's pixels, and how its
            // proportions were worked out and which paper it was matched to (#293)
            Diag.event(
                DiagArea.Scan, "page", "index" to i + 1, "edges_found" to stats.found, "corners_moved" to stats.touched, "max_move_pct" to stats.maxMovePct,
                "filter" to filter, "enhanced" to (r != null && !untouched), "enhance_ms" to android.os.SystemClock.elapsedRealtime() - enhanceStarted,
                *(r?.timings.orEmpty().map<io.github.artcraftandroid.pdfcraft.core.ScanTiming, Pair<String, Any?>> { it.stage.removePrefix("scan_") + "_ms" to it.ms.toLong() }.toTypedArray()),
                "out_width" to (r?.width?.toLong() ?: 0L), "out_height" to (r?.height?.toLong() ?: 0L),
                "out_format" to (r?.format?.name?.lowercase() ?: "none"), "dpi" to (r?.dpi?.toDouble() ?: 0.0),
                "paper" to (r?.paper?.name?.lowercase() ?: "none"), "aspect" to (r?.aspectSource?.name?.lowercase() ?: "none"),
                "focal_mismatch" to (r?.focalMismatch ?: false),
                "flatten" to flatten, "flattened" to (r?.flattened ?: false), "bend_pct" to ((r?.bend ?: 0f) * 100.0),
                "strength" to strength, "side_proportions" to !lab.perspective, "snap_paper" to lab.snapPaper,
                // which way up the page was found to be and whether it was turned (#339)
                "upright" to lab.upright, "quarter_turns" to (r?.quarterTurns?.toInt() ?: 0), "upright_sure" to (r?.orientation?.sure ?: false),
                "upright_confidence" to (r?.orientation?.confidence?.toDouble() ?: 0.0), "upright_agreement" to (r?.orientation?.agreement?.toDouble() ?: 0.0),
                "upright_tiles" to (r?.orientation?.tiles?.toInt() ?: 0),
                // sharpening and smoothing asked of the Clean scan filter, and the grain it measured (#299)
                "sharpen" to lab.sharpen, "denoise" to lab.denoise, "noise_sigma" to (r?.noise?.toDouble() ?: -1.0),
            )
            if (keepIn != null) {
                val made = r?.let {
                    ScanTestResult(
                        it.width.toLong(), it.height.toLong(), it.format.name.lowercase(), it.dpi.toDouble(), it.paper.name.lowercase(),
                        it.aspectSource.name.lowercase(), it.focalMismatch, it.flattened, it.bend * 100.0, it.quarterTurns.toInt(),
                        it.orientation?.let { o -> ScanTestOrientation(o.quarterTurns.toInt(), o.confidence.toDouble(), o.agreement.toDouble(), o.tiles.toInt(), o.sure) },
                        it.noise?.toDouble(),
                    )
                }
                withContext(Dispatchers.IO) { scanLab.keepPage(keepIn, i, page, true, filter.mode, strength, flatten, r?.path?.let(::File), made, scanPreviewRecord(page)) }
            }
            saved += scanSavedPage(page.file.absolutePath, r?.let { it.path to it.dpi })
        }
        if (keepIn != null) {
            withContext(Dispatchers.IO) { scanLab.end(keepIn) }
            scanLab.refreshKept()
        }
        val created = try {
            eng.tool("doc_create", scanCreateArgs(saved))
        } finally {
            // the PDF holds the pages now
            dir.listFiles()?.forEach { it.delete() }
        }
        val id = created.optLong("doc")
        try {
            if (Diag.enabled()) {
                // the straightened pages' proportions (A4 is 1.414; the gate is within 2%)
                eng.tool("doc_info", mapOf("doc" to id)).optJSONArray("pages")?.let { out ->
                    for (i in 0 until out.length()) {
                        val p = out.optJSONObject(i) ?: continue
                        val (pw, ph) = p.optDouble("width") to p.optDouble("height")
                        if (pw > 0 && ph > 0) Diag.event(DiagArea.Scan, "page_size", "index" to i + 1, "width_pt" to pw, "height_pt" to ph, "ratio" to maxOf(pw, ph) / minOf(pw, ph))
                    }
                }
            }
            // the scan's words become text, searchable and selectable, when text recognition is installed
            withContext(Dispatchers.IO) { runCatching { eng.prepareOcrModels(getApplication()) } }
            if (runCatching { eng.tool("ocr_status") }.getOrNull()?.optBoolean("available", false) == true) {
                busy = text(R.string.msg_busy_recognizing)
                runCatching { Diag.measure(DiagArea.Scan, "ocr", "pages" to pages.size) { eng.tool("ocr_recognize", mapOf("doc" to id)) } }
            }
            val out = tempFile("pdf")
            eng.tool("doc_save", mapOf("doc" to id, "path" to out.absolutePath, "full" to true))
            Diag.event(
                DiagArea.Scan, "saved", "pages" to pages.size, "filter" to filter, "bytes" to out.length(),
                "untouched_pages" to pages.count { it.found != null && it.corners.contentEquals(it.found) },
                "found_pages" to pages.count { it.found != null }, "ms" to android.os.SystemClock.elapsedRealtime() - scanStarted,
            )
            val target = insertScanAt
            insertScanAt = null
            val chosen = scanName.trim()
            scanPages.forEach { it.file.delete() }
            releaseScanPreviews()
            scanPages.clear()
            scanName = ""
            toolScreen = null
            if (target != null) {
                runTool(target.first, "page_insert_file", mapOf("path" to out.absolutePath, "at" to target.second))
            } else {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
                val name = DocumentFiles.safeName(chosen.ifEmpty { "Scan $stamp" })
                val uri = withContext(Dispatchers.IO) { files.saveToDownloads(out, name).also { out.delete() } }
                tell(text(R.string.msg_saved_downloads, name))
                open(uri)
            }
        } finally {
            runCatching { eng.tool("doc_close", mapOf("doc" to id, "discard_changes" to true)) }
        }
    }

    // ---- Home, Files, Search -----------------------------------------------------------------

    var homeTab by mutableStateOf(io.github.artcraftandroid.pdfcraft.ui.home.HomeTab.Home)
    var settingsOpen by mutableStateOf(false)

    /** The Settings page to open on (root, about, prefs, help). */
    var settingsStartPage = "root"
        private set

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
    private var gridViewState by mutableStateOf(appPrefs.getBoolean("gridView", false))
    var gridView: Boolean
        get() = gridViewState
        set(v) {
            gridViewState = v
            appPrefs.edit().putBoolean("gridView", v).apply()
        }

    /** Starred: 0 by date, 1 by name. */
    private var starSortState by mutableStateOf(appPrefs.getInt("starSort", 0))
    var starSort: Int
        get() = starSortState
        set(v) {
            starSortState = v
            appPrefs.edit().putInt("starSort", v).apply()
        }

    /** Bumped when a preference shown on screen changes outside Compose state. */
    var prefsChanged by mutableStateOf(0)

    val thumbnails = io.github.artcraftandroid.pdfcraft.engine.Thumbnails(app)

    var localFiles by mutableStateOf<List<io.github.artcraftandroid.pdfcraft.engine.LocalPdf>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var hasFileAccess by mutableStateOf(io.github.artcraftandroid.pdfcraft.engine.LocalFiles.hasAccess(app))
        private set
    private var lastScan = 0L

    /** The app's version, as the About page shows it. */
    val appVersion: String = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    /** Look for the PDFs on the phone (at most every half minute unless [force]d). */
    fun scanLocal(force: Boolean = false) {
        hasFileAccess = io.github.artcraftandroid.pdfcraft.engine.LocalFiles.hasAccess(getApplication())
        val now = System.currentTimeMillis()
        if (!force && now - lastScan < 30_000 && localFiles.isNotEmpty()) return
        lastScan = now
        viewModelScope.launch {
            scanning = true
            localFiles = withContext(Dispatchers.IO) { io.github.artcraftandroid.pdfcraft.engine.LocalFiles.scan(getApplication()) }
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
        val app = getApplication<Application>()
        return io.github.artcraftandroid.pdfcraft.engine.LocalFiles.folders(app).map { it to io.github.artcraftandroid.pdfcraft.engine.LocalFiles.folderName(app, it) }
    }

    /** A folder chosen in the picker: list its PDFs from now on. */
    fun addLocalFolder(tree: Uri) {
        io.github.artcraftandroid.pdfcraft.engine.LocalFiles.addFolder(getApplication(), tree)
        hasFileAccess = io.github.artcraftandroid.pdfcraft.engine.LocalFiles.hasAccess(getApplication())
        scanLocal(force = true)
    }

    /** Stop listing [tree]. */
    fun removeLocalFolder(tree: Uri) {
        io.github.artcraftandroid.pdfcraft.engine.LocalFiles.removeFolder(getApplication(), tree)
        hasFileAccess = io.github.artcraftandroid.pdfcraft.engine.LocalFiles.hasAccess(getApplication())
        scanLocal(force = true)
    }

    /** Back from the access page (or the app resumed): look again. */
    fun onResumed() {
        val had = hasFileAccess
        hasFileAccess = io.github.artcraftandroid.pdfcraft.engine.LocalFiles.hasAccess(getApplication())
        if (hasFileAccess && !had) scanLocal(force = true)
        refreshRecents()
        study.refreshStudy()
        study.refreshLibrary()
        study.indexLibrary()
        study.refreshDue()
        study.focusTimer = io.github.artcraftandroid.pdfcraft.FocusTimerAlarm.load(getApplication())
    }

    /** What [uri]'s thumbnail is drawn from: the file itself, the app's working copy, or the document. */
    private fun thumbPathFor(uri: String): String? {
        val u = Uri.parse(uri)
        return when (u.scheme) {
            "file" -> u.path
            else -> files.existingCopy(u)?.absolutePath ?: uri
        }
    }

    /** Each recent file's thumbnail source and location, found with the list off the main thread (#94). */
    private var recentPlaces by mutableStateOf<Map<String, Pair<String?, String>>>(emptyMap())

    fun recentRows(): List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow> = recentList.map { r ->
        val (thumb, location) = recentPlaces[r.uri] ?: (null to text(R.string.files_location_this_device))
        io.github.artcraftandroid.pdfcraft.ui.home.FileRow(r.uri, r.name, thumb, location, r.opened, r.size, r.starred) { open(Uri.parse(r.uri)) }
    }

    fun starredRows(): List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow> = recentRows().filter { it.starred }.let { l ->
        if (starSort == 1) l.sortedBy { it.name.lowercase() } else l.sortedByDescending { it.date }
    }

    fun localRows(): List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow> {
        // a file opened from another app is known by a different address than the scan's path,
        // so stars are matched by the file itself (name and size)
        val stars = recentList.filter { it.starred }.flatMap { listOf(it.uri, sameFile(it.name, it.size)) }.toSet()
        return localFiles.map { f ->
            val key = f.uri.toString()
            io.github.artcraftandroid.pdfcraft.ui.home.FileRow(key, f.name, f.path ?: key, f.folder, f.modified, f.size, key in stars || sameFile(f.name, f.size) in stars) { open(f.uri) }
        }
    }

    private fun sameFile(name: String, size: Long) = "$name|$size"

    /** Files whose names contain [query]: the phone's and Recent's, each file once. */
    fun searchFiles(query: String): List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow> {
        val q = query.trim().lowercase()
        val all = localRows() + recentRows()
        return all.filter { q in it.name.lowercase() }.distinctBy { it.key }.distinctBy { sameFile(it.name, it.size) }
    }

    private fun locationOf(uri: String): String = runCatching { files.displayLocation(Uri.parse(uri)) }.getOrDefault(text(R.string.files_location_this_device))

    fun clearRecents() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                // starred files stay: the stars live with them
                val keep = recents.list().filter { it.starred }
                recents.clear()
                keep.reversed().forEach { recents.touch(it) }
            }
            refreshRecents()
        }
    }

    fun removeRecent(key: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recents.remove(setOf(key)) }
            refreshRecents()
        }
    }

    /** What to do with the next PDF opened from a tool tile (Home, Tools). */
    internal var pendingTool: io.github.artcraftandroid.pdfcraft.ui.home.FileTool? = null

    /** A tool that starts by choosing a PDF: pick one, open it, then start the tool. */
    fun toolForFile(tool: io.github.artcraftandroid.pdfcraft.ui.home.FileTool) {
        pendingTool = tool
        pickFiles(FilePickKind.Open)
    }

    /** Open [row] and start [tool] on it. */
    fun openFor(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow, tool: io.github.artcraftandroid.pdfcraft.ui.home.FileTool) {
        pendingTool = tool
        row.open()
    }

    /** A document just opened: start the tool it was opened for, if any. */
    private fun startPendingTool(doc: OpenDocument) {
        val t = pendingTool ?: return
        pendingTool = null
        when (t) {
            FileTool.Edit -> enterTool(doc, ViewerTool.Edit)
            FileTool.ReadAloud -> startReadAloud(doc)
            FileTool.FillSign -> enterTool(doc, ViewerTool.FillSign)
            FileTool.Comment -> enterTool(doc, ViewerTool.Comment)
            FileTool.Organize -> { organizing = true; doc.controller.pagesOpen = true }
            FileTool.Compress -> openCompress(doc)
            FileTool.Export -> openExport(doc)
            FileTool.Protect -> openProtect(doc)
            FileTool.Crop -> openCrop(doc)
            FileTool.Ocr -> recognizeText(doc)
            FileTool.Draw -> enterTool(doc, ViewerTool.Draw)
            FileTool.Split -> { organizing = true; doc.controller.pagesOpen = true; splitOpen = true }
            FileTool.PageMarks -> openPageMarks(doc)
            FileTool.Compare -> startCompare(doc)
        }
    }

    /** A file row's local file, for sharing and the like: the file itself, or a fresh copy. */
    private suspend fun localFileOf(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow): File? = withContext(Dispatchers.IO) {
        val u = Uri.parse(row.key)
        runCatching { if (u.scheme == "file") File(requireNotNull(u.path)) else files.workingCopy(u) }.getOrNull()
    }

    /** Files ticked in Home's lists (a long press starts it), keyed by where they are. */
    val selectedFiles = androidx.compose.runtime.mutableStateMapOf<String, io.github.artcraftandroid.pdfcraft.ui.home.FileRow>()

    fun toggleSelected(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        if (selectedFiles.remove(row.key) == null) selectedFiles[row.key] = row
    }

    /** Several PDFs to the share sheet at once. */
    fun shareFiles(rows: List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow>) {
        handOut {
            val local = rows.mapNotNull { r -> localFileOf(r)?.let { it to r.name } }
            if (local.isEmpty()) error(text(R.string.msg_files_unreadable))
            sendIntent(withContext(Dispatchers.IO) { files.shareableAll(local) })
        }
    }

    /** Combine the ticked files, in the order shown. */
    fun combineSelected(rows: List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow>) {
        combineFiles.clear()
        rows.take(12).forEach { combineFiles.add(PickedFile(Uri.parse(it.key), it.name, it.size)) }
        toolScreen = ToolScreen.Combine(null)
    }

    fun removeRecents(rows: List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow>) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recents.remove(rows.map { it.key }.toSet()) }
            refreshRecents()
        }
    }

    fun shareFile(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        handOut {
            val f = localFileOf(row) ?: error(text(R.string.common_file_unreadable))
            sendIntent(listOf(withContext(Dispatchers.IO) { files.shareable(f, row.name) }))
        }
    }

    fun printFile(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        viewModelScope.launch {
            val f = localFileOf(row) ?: return@launch tell(text(R.string.common_file_unreadable))
            prints.trySend(PrintJob(f, row.name, deleteWhenDone = false))
        }
    }

    fun openWith(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        handOut {
            val f = localFileOf(row) ?: error(text(R.string.common_file_unreadable))
            val uri = withContext(Dispatchers.IO) { files.shareable(f, row.name) }
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/pdf").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    fun renameFile(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow, newName: String) {
        viewModelScope.launch {
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
                            recents.remove(setOf(row.key))
                            recents.touch(RecentFile(Uri.fromFile(target).toString(), name, System.currentTimeMillis(), 0, target.length()))
                            bookmarkStore.move(row.key, Uri.fromFile(target).toString())
                        }
                        // what the tools saved to Downloads: MediaStore entries keep their address when renamed
                        u.authority == android.provider.MediaStore.AUTHORITY -> {
                            val values = android.content.ContentValues().apply { put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name) }
                            require(getApplication<Application>().contentResolver.update(u, values, null, null) > 0) { text(R.string.msg_rename_not_here) }
                            recents.update(row.key) { it.copy(name = name) }
                        }
                        else -> {
                            val renamed = android.provider.DocumentsContract.renameDocument(getApplication<Application>().contentResolver, u, name)
                                ?: error(text(R.string.msg_rename_not_here))
                            recents.remove(setOf(row.key))
                            recents.touch(RecentFile(renamed.toString(), name, System.currentTimeMillis(), 0, files.size(renamed) ?: 0))
                            bookmarkStore.move(row.key, renamed.toString())
                        }
                    }
                }
            }
            ok.onFailure { tell(it.userMessage) }
            refreshRecents()
            scanLocal(force = true)
        }
    }

    fun duplicateFile(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        viewModelScope.launch {
            val f = localFileOf(row) ?: return@launch tell(text(R.string.common_file_unreadable))
            val copy = withContext(Dispatchers.IO) { File(getApplication<Application>().cacheDir, "dup-${System.nanoTime()}.pdf").also { f.copyTo(it, overwrite = true) } }
            val base = row.name.removeSuffix(".pdf")
            val uri = withContext(Dispatchers.IO) { files.saveToDownloads(copy, "$base copy.pdf").also { copy.delete() } }
            tell(text(R.string.msg_duplicated, "$base copy.pdf"), text(R.string.common_open)) { open(uri) }
            scanLocal(force = true)
        }
    }

    fun saveFileCopy(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        startCopyRequest(CopyRequest.Row(row), row.name)
    }

    /** The place chosen for a file row's Save a copy. */
    private fun copyRowTo(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow, target: Uri) {
        viewModelScope.launch {
            val f = localFileOf(row) ?: return@launch tell(text(R.string.common_file_unreadable))
            runCatching { withContext(Dispatchers.IO) { files.writeBack(f, target) } }
                .onSuccess { tell(text(R.string.msg_saved_copy)) }.onFailure { tell(it.userMessage) }
        }
    }

    fun deleteFile(row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) {
        viewModelScope.launch {
            val u = Uri.parse(row.key)
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    when {
                        u.scheme == "file" -> require(File(requireNotNull(u.path)).delete()) { text(R.string.msg_delete_failed) }
                        u.authority == android.provider.MediaStore.AUTHORITY ->
                            require(getApplication<Application>().contentResolver.delete(u, null, null) > 0) { text(R.string.msg_delete_failed) }
                        else -> require(android.provider.DocumentsContract.deleteDocument(getApplication<Application>().contentResolver, u)) { text(R.string.msg_delete_failed) }
                    }
                    recents.remove(setOf(row.key))
                }
            }
            ok.onFailure { tell(it.userMessage) }.onSuccess { tell(text(R.string.msg_file_deleted, row.name)) }
            refreshRecents()
            scanLocal(force = true)
        }
    }

    /** Photos: pick pictures and make them into a PDF (Create PDF from the photo picker). */
    val photoPicks = Channel<Unit>(Channel.CONFLATED)

    fun pickPhotosToPdf() {
        photoPicks.trySend(Unit)
    }

    fun onPhotosPicked(uris: List<Uri>) {
        if (uris.isNotEmpty()) onFilesPicked(FilePickKind.Create, uris)
    }

    // ---- Note margins (#146) ----

    /** Which pages have a note margin (one per page), from the engine. */
    suspend fun pageMargins(doc: OpenDocument): List<Boolean> {
        val eng = engine ?: return emptyList()
        return runCatching {
            val r = eng.tool("page_margin", mapOf("doc" to doc.id))
            val a = r.getJSONArray("margins")
            List(a.length()) { a.getBoolean(it) }
        }.getOrDefault(emptyList())
    }

    /**
     * Room for notes on [side] of [pages] (0-based; every page when empty), [fraction] of the
     * first page's width (or height, below). One undo step; the content keeps its place.
     */
    fun addNoteMargin(doc: OpenDocument, pages: List<Int>, side: MarginSide, fraction: Float) {
        if (!doc.writable) { tell(text(R.string.margin_read_only)); return }
        val first = doc.info.pages.getOrNull(pages.firstOrNull() ?: 0) ?: return
        val right = if (side != MarginSide.Bottom) (first.width * fraction).toDouble() else 0.0
        val bottom = if (side != MarginSide.Right) (first.height * fraction).toDouble() else 0.0
        val args = buildMap<String, Any?> {
            put("add", listOf(0.0, bottom, right, 0.0))
            if (pages.isNotEmpty()) put("pages", pages.map { it + 1 })
        }
        runTool(doc, "page_margin", args) { r ->
            Diag.event(
                DiagArea.Study, "margin_add", "doc" to doc.diagNumber, "side" to side, "size_pct" to (fraction * 100).toInt(),
                "pages" to (if (pages.isEmpty()) doc.pageCount else pages.size), "with_margin" to r.optJSONArray("margins")?.let { a -> (0 until a.length()).count { a.optBoolean(it) } },
            )
            tell(text(R.string.margin_added))
        }
    }

    /** Take off the note margins of [pages] (every page when empty), back to the original size. */
    fun removeNoteMargins(doc: OpenDocument, pages: List<Int>) {
        if (!doc.writable) { tell(text(R.string.margin_read_only)); return }
        val args = buildMap<String, Any?> {
            put("remove", true)
            if (pages.isNotEmpty()) put("pages", pages.map { it + 1 })
        }
        runTool(doc, "page_margin", args) { r ->
            Diag.event(
                DiagArea.Study, "margin_remove", "doc" to doc.diagNumber, "pages" to (if (pages.isEmpty()) doc.pageCount else pages.size),
                "changed" to r.optBoolean("changed", true),
            )
            tell(text(R.string.margin_removed))
        }
    }

    // ---- Handwriting to text (#151) ----

    /**
     * Read the handwriting on page [page] (0-based): the ink comments with no text of their own
     * are pasted on a blank page of the same size, read by the engine's OCR there (so printed
     * text under them is not read too), and each gets the words that lie in it as its text,
     * which the comment list, search across documents and other PDF readers show.
     */
    fun recognizeHandwriting(doc: OpenDocument, page: Int) {
        val eng = engine ?: return
        if (!doc.writable) { tell(text(R.string.margin_read_only)); return }
        doc.commitTool?.invoke()
        viewModelScope.launch {
            busy = text(R.string.msg_busy_preparing_ocr)
            withContext(Dispatchers.IO) { runCatching { eng.prepareOcrModels(getApplication()) } }
            val status = runCatching { eng.tool("ocr_status") }.getOrNull()
            if (status?.optBoolean("available", false) != true) {
                Diag.event(DiagArea.Study, "handwriting", "doc" to doc.diagNumber, "outcome" to "ocr_missing")
                busy = null
                tell(text(R.string.msg_ocr_not_installed))
                return@launch
            }
            busy = text(R.string.handwriting_busy)
            val blank = tempFile("pdf")
            var copy: Long? = null
            try {
                val inks = io.github.artcraftandroid.pdfcraft.engine.Handwriting.inksWithoutText(eng.tool("comment_list", mapOf("doc" to doc.id, "page" to page + 1)))
                if (inks.isEmpty()) {
                    Diag.event(DiagArea.Study, "handwriting", "doc" to doc.diagNumber, "outcome" to "no_new_ink")
                    tell(text(R.string.handwriting_none)); return@launch
                }
                val xfdf = eng.tool("comments_copy", mapOf("doc" to doc.id, "comments" to inks.map { listOf(page + 1, it.index) })).getString("xfdf")
                val size = doc.info.pages[page]
                withContext(Dispatchers.IO) { blank.writeBytes(io.github.artcraftandroid.pdfcraft.engine.Handwriting.blankPdf(size.width.toDouble(), size.height.toDouble())) }
                copy = eng.tool("doc_open", mapOf("path" to blank.absolutePath)).optLong("doc")
                // centred where they were, so their boxes on the blank page are the ones listed
                val cx = (inks.minOf { it.x0 } + inks.maxOf { it.x1 }) / 2
                val cy = (inks.minOf { it.y0 } + inks.maxOf { it.y1 }) / 2
                eng.tool("comments_paste", mapOf("doc" to copy, "page" to 1, "xfdf" to xfdf, "at" to listOf(cx, cy)))
                eng.tool("doc_save", mapOf("doc" to copy))
                val ocrStart = System.nanoTime()
                val read = eng.tool("ocr_words", mapOf("doc" to copy, "pages" to listOf(1), "force_ocr" to true, "auto_rotate" to true))
                val ocrMs = (System.nanoTime() - ocrStart) / 1_000_000
                val words = io.github.artcraftandroid.pdfcraft.engine.Handwriting.words(read)
                val found = io.github.artcraftandroid.pdfcraft.engine.Handwriting.assign(inks, words)
                Diag.event(
                    DiagArea.Study, "handwriting", "doc" to doc.diagNumber, "outcome" to (if (found.isEmpty()) "nothing_read" else "read"),
                    "inks" to inks.size, "words" to words.size, "low_confidence" to words.count { (it.confidence ?: 100f) <= io.github.artcraftandroid.pdfcraft.engine.Handwriting.MIN_CONFIDENCE },
                    "inks_with_text" to found.size, "ocr_ms" to ocrMs,
                )
                if (found.isEmpty()) { tell(text(R.string.handwriting_nothing_read)); return@launch }
                for ((index, words) in found) eng.tool("comment_edit", mapOf("doc" to doc.id, "page" to page + 1, "index" to index, "contents" to words))
                refresh(doc)
                success = SuccessBanner(plural(R.plurals.handwriting_done, found.size, found.size), null)
            } catch (e: Exception) {
                Diag.failure(DiagArea.Study, "handwriting", e)
                tell(e.userMessage)
            } finally {
                copy?.let { c -> runCatching { eng.tool("doc_close", mapOf("doc" to c, "discard_changes" to true)) } }
                withContext(Dispatchers.IO) { blank.delete() }
                busy = null
            }
        }
    }

    // ---- Lecture recording (#142) ----

    val lectures = io.github.artcraftandroid.pdfcraft.engine.Lectures(File(getApplication<Application>().filesDir, "lectures"))

    /** A recording in progress beside one document (by URI, so it goes on across pages and reopening). */
    class LectureSession(
        val uri: String, val title: String, val id: String, val file: File, val started: Long,
        val recorder: android.media.MediaRecorder, val clock: io.github.artcraftandroid.pdfcraft.engine.RecordingClock,
    ) {
        var marks: List<io.github.artcraftandroid.pdfcraft.engine.LectureMark> = emptyList()
        val known = HashSet<String>()
    }

    var lectureRecording by mutableStateOf<LectureSession?>(null)
        private set
    var lecturePaused by mutableStateOf(false)
        private set
    internal var pendingLecture: OpenDocument? = null

    /** Start recording the lecture beside [doc]: comments written meanwhile remember when. */
    fun startLecture(doc: OpenDocument) {
        val app = getApplication<Application>()
        val uri = doc.source?.toString() ?: run { tell(text(R.string.lecture_needs_saved)); return }
        if (lectureRecording != null) return
        if (app.filesDir.usableSpace < 100L * 1024 * 1024) { tell(text(R.string.msg_voice_no_storage)); return }
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            pendingLecture = doc
            commenting.micRequests.trySend(Unit)
            return
        }
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            val (id, file) = withContext(Dispatchers.IO) { lectures.newFile(now) }
            @Suppress("DEPRECATION")
            val recorder = if (android.os.Build.VERSION.SDK_INT >= 31) android.media.MediaRecorder(app) else android.media.MediaRecorder()
            try {
                recorder.setAudioSource(android.media.MediaRecorder.AudioSource.MIC)
                recorder.setOutputFormat(android.media.MediaRecorder.OutputFormat.MPEG_4)
                recorder.setAudioEncoder(android.media.MediaRecorder.AudioEncoder.AAC)
                recorder.setAudioChannels(1)
                recorder.setAudioSamplingRate(44_100)
                recorder.setAudioEncodingBitRate(64_000)
                recorder.setOutputFile(file.absolutePath)
                recorder.prepare()
                recorder.start()
            } catch (e: Exception) {
                Diag.failure(DiagArea.Study, "lecture_start", e)
                recorder.release()
                file.delete()
                tell(text(R.string.lecture_cannot_record))
                return@launch
            }
            val clock = io.github.artcraftandroid.pdfcraft.engine.RecordingClock { android.os.SystemClock.elapsedRealtime() }
            clock.start()
            val session = LectureSession(uri, doc.title, id, file, now, recorder, clock)
            // the comments already there are not marked as written during the lecture
            comments(doc)?.mapNotNullTo(session.known) { it.name }
            Diag.event(DiagArea.Study, "lecture_start", "doc" to doc.diagNumber, "comments_before" to session.known.size, "free_mb" to app.filesDir.usableSpace / (1024 * 1024))
            lectureRecording = session
            lecturePaused = false
            withContext(Dispatchers.IO) { lectures.put(uri, io.github.artcraftandroid.pdfcraft.engine.Lecture(id, now, 0, emptyList())) }
            io.github.artcraftandroid.pdfcraft.LectureRecordService.onStop = { viewModelScope.launch { stopLecture() } }
            io.github.artcraftandroid.pdfcraft.LectureRecordService.show(app, doc.title, now)
        }
    }

    fun pauseLecture() {
        val s = lectureRecording ?: return
        if (lecturePaused) return
        runCatching { s.recorder.pause() }.onSuccess { s.clock.pause(); lecturePaused = true }
            .also { Diag.event(DiagArea.Study, "lecture_pause", "ok" to it.isSuccess, "at_ms" to s.clock.elapsed()) }
    }

    fun resumeLecture() {
        val s = lectureRecording ?: return
        if (!lecturePaused) return
        runCatching { s.recorder.resume() }.also { Diag.event(DiagArea.Study, "lecture_resume", "ok" to it.isSuccess, "at_ms" to s.clock.elapsed()) }.onSuccess {
            s.clock.start()
            lecturePaused = false
            io.github.artcraftandroid.pdfcraft.LectureRecordService.show(getApplication(), s.title, System.currentTimeMillis() - s.clock.elapsed())
        }
    }

    /** Stop and keep the recording with the times its comments were written. */
    fun stopLecture() {
        val s = lectureRecording ?: return
        lectureRecording = null
        lecturePaused = false
        io.github.artcraftandroid.pdfcraft.LectureRecordService.onStop = null
        io.github.artcraftandroid.pdfcraft.LectureRecordService.hide(getApplication())
        val duration = s.clock.elapsed().also { s.clock.pause() }
        // stop() fails when nothing was recorded: then nothing is kept
        val ok = runCatching { s.recorder.stop() }.isSuccess
        s.recorder.release()
        viewModelScope.launch(Dispatchers.IO) {
            val bytes = s.file.length()
            Diag.event(
                DiagArea.Study, "lecture_stop", "ok" to ok, "duration_ms" to duration, "bytes" to bytes, "marks" to s.marks.size,
                "kbps" to (if (duration > 0) bytes * 8 / duration else 0L), "wall_ms" to System.currentTimeMillis() - s.started,
            )
            if (ok && s.file.length() > 0) {
                lectures.put(s.uri, io.github.artcraftandroid.pdfcraft.engine.Lecture(s.id, s.started, duration, s.marks))
                withContext(Dispatchers.Main) { tell(text(R.string.lecture_saved, io.github.artcraftandroid.pdfcraft.ui.viewer.timerClock(duration))) }
            } else {
                lectures.delete(s.uri, s.id)
                withContext(Dispatchers.Main) { tell(text(R.string.lecture_cannot_record)) }
            }
        }
    }

    /** A comment was added to [doc]: if a lecture is being recorded beside it, remember when. */
    private fun lectureNoted(doc: OpenDocument) {
        val s = lectureRecording ?: return
        if (doc.source?.toString() != s.uri) return
        val at = s.clock.elapsed()
        viewModelScope.launch {
            val added = comments(doc)?.filter { c -> c.name?.let { it !in s.known } == true && c.replyTo == null }.orEmpty()
            if (added.isEmpty()) return@launch
            added.forEach { s.known += it.name!! }
            Diag.event(DiagArea.Study, "lecture_mark", "doc" to doc.diagNumber, "at_ms" to at, "added" to added.size, "marks" to s.marks.size + added.size)
            s.marks = s.marks + added.map { io.github.artcraftandroid.pdfcraft.engine.LectureMark(it.name!!, it.page.toInt(), at) }
            if (lectureRecording === s) withContext(Dispatchers.IO) {
                lectures.put(s.uri, io.github.artcraftandroid.pdfcraft.engine.Lecture(s.id, s.started, at, s.marks))
            }
        }
    }

    // Playback

    class LecturePlayback(val uri: String, val lecture: io.github.artcraftandroid.pdfcraft.engine.Lecture, val player: android.media.MediaPlayer)

    var lecturePlayback by mutableStateOf<LecturePlayback?>(null)
        private set
    var lecturePlaying by mutableStateOf(false)
        private set
    var lectureSpeed by mutableStateOf(1f)
        private set

    fun lecturesOf(doc: OpenDocument): List<io.github.artcraftandroid.pdfcraft.engine.Lecture> =
        doc.source?.toString()?.let { lectures.list(it) }.orEmpty()

    /** Play [l] from [fromMs]. */
    fun playLecture(doc: OpenDocument, l: io.github.artcraftandroid.pdfcraft.engine.Lecture, fromMs: Long = 0) {
        val uri = doc.source?.toString() ?: return
        val current = lecturePlayback
        if (current != null && current.lecture.id == l.id) {
            seekLecture(fromMs)
            if (!lecturePlaying) toggleLecturePlay()
            return
        }
        closeLecturePlayback()
        val player = android.media.MediaPlayer()
        try {
            player.setDataSource(lectures.audio(l).absolutePath)
            player.prepare()
        } catch (e: Exception) {
            Diag.failure(DiagArea.Study, "lecture_play", e)
            player.release()
            tell(text(R.string.lecture_cannot_play))
            return
        }
        Diag.event(DiagArea.Study, "lecture_play", "doc" to doc.diagNumber, "from_ms" to fromMs, "duration_ms" to player.duration, "marks" to l.marks.size)
        player.setOnCompletionListener { lecturePlaying = false }
        lecturePlayback = LecturePlayback(uri, l, player)
        applySpeed(player)
        player.seekTo(fromMs.toInt())
        player.start()
        lecturePlaying = true
    }

    fun toggleLecturePlay() {
        val p = lecturePlayback ?: return
        if (p.player.isPlaying) { p.player.pause(); lecturePlaying = false } else { p.player.start(); applySpeed(p.player); lecturePlaying = true }
    }

    fun seekLecture(ms: Long) {
        val p = lecturePlayback ?: return
        p.player.seekTo(ms.coerceIn(0, p.player.duration.toLong().coerceAtLeast(0)).toInt())
    }

    fun skipLecture(deltaMs: Long) {
        val p = lecturePlayback ?: return
        seekLecture(p.player.currentPosition + deltaMs)
    }

    fun lecturePosition(): Long = lecturePlayback?.let { runCatching { it.player.currentPosition.toLong() }.getOrDefault(0) } ?: 0

    fun cycleLectureSpeed() {
        val speeds = listOf(0.75f, 1f, 1.25f, 1.5f, 2f)
        lectureSpeed = speeds[(speeds.indexOf(lectureSpeed) + 1) % speeds.size]
        Diag.event(DiagArea.Study, "lecture_speed", "speed" to lectureSpeed)
        lecturePlayback?.player?.let(::applySpeed)
    }

    private fun applySpeed(player: android.media.MediaPlayer) {
        if (!player.isPlaying) return
        runCatching { player.playbackParams = player.playbackParams.setSpeed(lectureSpeed) }
    }

    fun closeLecturePlayback() {
        lecturePlayback?.player?.let { runCatching { it.stop() }; it.release() }
        lecturePlayback = null
        lecturePlaying = false
    }

    fun deleteLecture(doc: OpenDocument, l: io.github.artcraftandroid.pdfcraft.engine.Lecture) {
        val uri = doc.source?.toString() ?: return
        if (lecturePlayback?.lecture?.id == l.id) closeLecturePlayback()
        viewModelScope.launch(Dispatchers.IO) { lectures.delete(uri, l.id) }
    }

    /** In playback, tapping a comment written during the lecture plays from just before it. */
    internal fun lectureSeekToComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo) {
        val p = lecturePlayback ?: return
        if (doc.source?.toString() != p.uri) return
        val name = c.name ?: return
        val (_, at) = io.github.artcraftandroid.pdfcraft.engine.Lectures.seekFor(listOf(p.lecture), name)
            ?: run { Diag.event(DiagArea.Study, "lecture_seek_comment", "doc" to doc.diagNumber, "found" to false); return }
        Diag.event(DiagArea.Study, "lecture_seek_comment", "doc" to doc.diagNumber, "found" to true, "at_ms" to at)
        seekLecture(at)
        if (!lecturePlaying) toggleLecturePlay()
    }

    // ---- Backups (#145) and version history: see BackupController ----

    val backups = BackupController(this)

    // ---- The scanner's testing options and scans kept for testing (#288): see ScanLabController ----

    internal val scanLab = ScanLabController(this)
}

/** The most files Create PDF converts at once. */
private const val MAX_CREATE_FILES = 12

/** A green banner saying a job worked, with the way to undo it. */
class SuccessBanner(val text: String, val undo: (() -> Unit)?)

/** Recognize text waiting for its pages: [page] is the one in view (0-based). */

/** A file for the activity to print; [deleteWhenDone]: a temporary copy (an unlocked one) the job deletes (#105). */
data class PrintJob(val file: File, val name: String, val deleteWhenDone: Boolean)

/** XFDF, as Acrobat registers it: what shared comment files are sent as. */
const val XFDF_MIME = "application/vnd.adobe.xfdf"

/** The largest XFDF or FDF file imported (a PDF to take comments from may be larger). */
internal const val MAX_COMMENT_FILE_BYTES = 64L * 1024 * 1024

/** What Export notes writes. */
enum class DigestFormat(@androidx.annotation.StringRes val label: Int) {
    Markdown(R.string.digest_markdown),
    MarkdownImages(R.string.digest_markdown_images),
    Csv(R.string.digest_csv),
    Pdf(R.string.digest_pdf),
}

/** Files past this size are not copied for the library index. */
internal const val MAX_INDEX_BYTES = 300L * 1024 * 1024

/** A flashcard being written: its deck (document URI), page, sides, and its id when editing one. */
data class CardDraft(val deck: String, val deckName: String, val page: Int, val front: String, val back: String, val id: String?)

/** A review session: the cards still due in it, in order, and how many were reviewed. */
class ReviewSession(val deck: String?, val deckName: String) {
    val queue = androidx.compose.runtime.mutableStateListOf<io.github.artcraftandroid.pdfcraft.engine.Flashcard>()
    var all by mutableStateOf<List<io.github.artcraftandroid.pdfcraft.engine.Flashcard>>(emptyList())
    var reviewed by mutableStateOf(0)
}

/** Where a note margin goes. */
enum class MarginSide { Right, Bottom, Both }
