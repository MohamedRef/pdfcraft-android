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
                list to list.associate { it.uri to (home.thumbPathFor(it.uri) to home.locationOf(it.uri)) }
            }
            home.recentPlaces = places
            recentList = list
            // #94: the Home list's sources and locations, worked out off the main thread
            Diag.event(DiagArea.Home, "recents", "files" to list.size, "ms" to android.os.SystemClock.elapsedRealtime() - started)
        }
    }

    /** Opening is serial: multiple ACTION_VIEW deliveries must not share a working file or engine document. */
    internal val openMutex = Mutex()
    private var lastOpenRequest = 0L
    /** The file the latest open request is for, until that request finishes (#439). */
    private var openingUri: String? = null
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
        home.settingsOpen = false
        home.showLicences = false
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
        // the same file is already opening: let that open finish (#439)
        if (io.github.artcraftandroid.pdfcraft.isRepeatOpen(openingUri, uri.toString(), fromOtherApp, password)) return
        val requestId = ++lastOpenRequest
        openingUri = uri.toString()
        // Lock the entire copy/open/replace transaction. A newer external request may arrive
        // while the previous provider streams slowly; only the latest waiting one opens.
        viewModelScope.launch {
          try {
          openMutex.withLock {
            if (!isCurrentOpenRequest(requestId)) return@withLock
            val eng = engine ?: application.engine.await().getOrNull() ?: run { tell(engineProblem ?: text(R.string.msg_engine_not_started)); return@withLock }
            // An older request can become obsolete while waiting for the native engine.
            if (!isCurrentOpenRequest(requestId)) return@withLock
            openStartedAt = android.os.SystemClock.elapsedRealtime()
            settings.diagDoc = Diag.nextDocNumber()
            // a number per open, never the file's name (#105): the lines after it are about this file
            Diag.event(DiagArea.Open, "open_start", "doc" to settings.diagDoc, "scheme" to uri.scheme, "from_other_app" to fromOtherApp, "has_password" to (password != null))
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
                    tell(text(R.string.msg_opened_own_copy), text(R.string.common_save_copy)) { document?.let { sharing.saveCopyAs(it) } }
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
                    tell(text(R.string.msg_opened_own_copy), text(R.string.common_save_copy)) { document?.let { sharing.saveCopyAs(it) } }
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
          } finally {
              if (lastOpenRequest == requestId) openingUri = null
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
            val progressive = file.length() >= 16L * 1024L * 1024L && recent?.reading != true && home.pendingTool == null
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
            val images = PageImages(eng, info.id, viewModelScope, imageBudget, openedAt = openStartedAt, compact = settings.compactPreviews)
            val source = uri.takeIf { it.scheme == "content" || it.scheme == "file" }
            val doc = OpenDocument(info.id, info, source, file, images, password, eng, viewModelScope)
            doc.fromOtherApp = fromOtherApp
            doc.diagNumber = settings.diagDoc
            doc.writable = withContext(Dispatchers.IO) { source == null || files.canWrite(source) }
            doc.notWrittenBack = source?.let { withContext(Dispatchers.IO) { files.hasPendingWriteBack(it) } } ?: false
            io.github.artcraftandroid.pdfcraft.engine.OpenTiming.milestone("source_access_ready", openStartedAt)
            doc.controller.nightMode = settings.savedNightMode
            // the layout this file was left in, else the one the file asks for, else the app's (#152)
            val remembered = ViewLayout.decode(recent?.layout)
            val fromFile = if (remembered == null && recent?.layout == null && !info.preparing) settings.fileLayout(eng, info.id) else null
            settings.applyLayout(doc, remembered ?: fromFile, if (remembered != null) "remembered" else "file")
            // a file left in Reading Mode reopens in it, unless it was opened for a tool
            settings.readingMode = recent?.reading == true && home.pendingTool == null
            document = doc
            tabs.opened(doc, doc.title)
            io.github.artcraftandroid.pdfcraft.engine.OpenTiming.milestone("viewer_model_ready", openStartedAt, pages = info.pages.size)
            if (!info.preparing) {
                viewModelScope.launch {
                    doc.images.awaitFirstCanvas()
                    if (document === doc) {
                        loadLinks(eng, doc)
                        if (doc.info.signed) signing.announceSignatures(doc)
                        home.startPendingTool(doc)
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
                home.startPendingTool(doc)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (document === doc && isCurrentOpenRequest(requestId)) {
                    tell(e.userMessage)
                    closeQuietly(eng, doc)
                    document = null
                    settings.readingMode = false
                    tabs.closed(doc)
                }
            }
        }
    }

    override fun onCleared() {
        noteTaking.stopLecture()
        noteTaking.closeLecturePlayback()
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
        internal set

    /** Blocks new external opens while an unresolved Save a copy or Discard is in flight. */
    internal var resolvingReadOnly = false

    /** Leave the viewer: changes are saved first, as the reference does (no prompt). */
    fun closeDocument() {
        noteTaking.closeLecturePlayback()
        val doc = document ?: return
        // Close pressed again while this close is still saving: one save is enough (#440)
        if (closingDoc === doc) return
        study.pauseStudy()
        study.studyDoc = null
        val eng = engine ?: return
        if (doc.controller.tool != ViewerTool.None) leaveTool(doc)
        closingDoc = doc
        viewModelScope.launch {
          try {
            openMutex.withLock {
                if (document !== doc) return@withLock
                // Opening a second document and closing the first must not race native handles.
                if (!saveAndClose(eng, doc)) {
                    readOnlyPrompt = doc
                    return@withLock
                }
                document = null
                settings.readingMode = false
                val next = tabs.closed(doc)
                refreshRecents()
                study.indexLibrary()
                withContext(Dispatchers.IO) { sweepCache() }
                if (doc.fromOtherApp) leave.trySend(Unit) else showTab(next)
            }
          } finally {
              if (closingDoc === doc) closingDoc = null
          }
        }
    }

    /** The document whose Close is saving now; a repeated Close waits for it (#440). */
    private var closingDoc: OpenDocument? = null

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
            sharing.saveCopyAs(doc, then = {
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
    internal suspend fun saveAndClose(eng: Engine, doc: OpenDocument, reading: Boolean = settings.readingMode, pending: Int? = pendingPage): Boolean {
        // the spot at the screen's top-left and the zoom, to reopen there (as the reference does)
        val v = doc.viewer
        val spot = if (pending == null && v.mode == io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.Continuous) v.screenToPage(androidx.compose.ui.geometry.Offset(0f, v.padTop), nearest = true) else null
        val zoom = v.zoom
        val layout = settings.currentLayout(doc).encode()
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

    internal suspend fun closeQuietly(eng: Engine, doc: OpenDocument) {
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
            sharing.saveCopyAs(doc)
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
                if (name == "comment_add") noteTaking.lectureNoted(doc)
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
                    if (name == "comment_add" && applied > 0) noteTaking.lectureNoted(doc)
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
        if (settings.readingMode && tool != ViewerTool.None) {
            settings.readingModeBlocked = tool
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
        if (tool in setOf(ViewerTool.Comment, ViewerTool.Markup, ViewerTool.Draw, ViewerTool.Text) && doc.info.commentsHidden) settings.setCommentsHidden(doc, false)
        ctl.switchTool(tool)
        if (tool == ViewerTool.Draw && settings.toolPrefs.snapToShape && settings.toolPrefs.takeSnapHint()) tell(text(R.string.msg_snap_to_shape_on))
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
                    else -> sharing.linkPrompt = uri
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

    // ---- Opening links: see SharingController ----

    val sharing = SharingController(this)

    // ---- Tools' remembered, View settings: see SettingsController ----

    val settings = SettingsController(this)

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

    // ---- Tool screens (, Tool screens, continued, Remove hidden: see ToolsController ----

    val tools = ToolsController(this)

    // ---- Scan and scanned-page cleanup: stay here with the scanner work ----------------------
    fun startScan() { tools.toolScreen = ToolScreen.Scan }

    /**
     * Native 0.19.0 scanned-page cleanup. Bounded UI page scope, explicit confirm,
     * and no new provider-path writes: native edits the already-open working PDF.
     */
    internal fun enhanceScannedPages(doc: OpenDocument, plan: io.github.artcraftandroid.pdfcraft.ui.viewer.ScanEnhancePlan) {
        if (document !== doc || tools.busy != null || !doc.writable || doc.info.signed ||
            doc.info.readOnlyReason != null || doc.pageCount == 0 ||
            !io.github.artcraftandroid.pdfcraft.ui.viewer.validScanEnhancePlan(plan, doc.pageCount)) {
            tell(text(R.string.scan_enhance_unavailable))
            return
        }
        val eng = engine ?: return
        val generation = doc.info.generation
        viewModelScope.launch {
            if (document !== doc || tools.busy != null || doc.info.generation != generation) return@launch
            tools.busy = text(R.string.scan_enhance_busy)
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
                    tools.success = SuccessBanner(text(R.string.scan_enhance_finished, done)) { undo(doc) }
                } else {
                    tell(text(R.string.scan_enhance_no_changes))
                }
            } catch (e: MobileException) {
                if (document === doc) tell(e.userMessage)
            } finally {
                tools.busy = null
            }
        }
    }

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
        tools.toolScreen = ToolScreen.Bates
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
            tools.toolScreen = null
        }
    }

    fun pickBatesFiles() {
        if (batesRunning || batesSelectionLoading || tools.toolScreen != ToolScreen.Bates || batesPendingPick != null) return
        batesPendingPick = batesEpoch
        if (!batesPicks.trySend(Unit).isSuccess) batesPendingPick = null
    }

    fun onBatesFilesPicked(uris: List<Uri>) {
        val epoch = batesPendingPick ?: return
        batesPendingPick = null
        if (uris.isEmpty() || tools.toolScreen != ToolScreen.Bates || batesRunning || epoch != batesEpoch) return
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
                if (epoch == batesEpoch && tools.toolScreen == ToolScreen.Bates && !batesRunning) {
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
        if (batesRunning || batesSelectionLoading || tools.toolScreen != ToolScreen.Bates || batesFiles.size !in 1..io.github.artcraftandroid.pdfcraft.engine.MAX_BATES_FILES) return
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

    /** Whether the photo being taken replaces a page (Retake): the in-app camera then takes just the one (#294). */
    internal val scanRetaking: Boolean get() = retakeIndex != null

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
        return ScanSession(shotFile?.path, retakeIndex, editor.imageShot, scanName, scanPages.map { ScanSession.Page(it.file.path, it.corners, it.found, it.turns, it.preset, it.book?.encode(), it.look, it.flatten) })
    }

    private fun restoreScan(text: String, session: ScanSession): kotlinx.coroutines.Job {
        scanRestoring = text
        shotFile = session.shot?.let(::File)
        retakeIndex = session.retake
        editor.imageShot = session.forImage
        scanName = session.name
        return viewModelScope.launch {
            val pages = withContext(Dispatchers.IO) {
                // the two halves of a book spread share a photo, so each photo's thumbnail is made once
                val thumbs = HashMap<String, androidx.compose.ui.graphics.ImageBitmap?>()
                session.pages.mapNotNull { p -> File(p.path).let { f -> thumbs.getOrPut(p.path) { scanThumb(f) }?.let { ScanPage(f, it, p.corners, p.found, turns = p.turns, preset = p.preset, book = io.github.artcraftandroid.pdfcraft.ui.scan.ScanBookHalf.decode(p.book), flatten = p.flatten ?: true.takeIf { session.flatten }, look = p.look ?: io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(), uncropped = io.github.artcraftandroid.pdfcraft.ui.scan.scanRestoredUncropped(p.corners, p.found, io.github.artcraftandroid.pdfcraft.ui.scan.ScanBookHalf.decode(p.book), io.github.artcraftandroid.pdfcraft.ui.scan.ScanCaptures.of(p.path)?.straightened == true)) } } }
            }
            scanPages.addAll(pages)
            scanRestoring = null
            // back on the Scan screen with its pages (with none, it would open the camera itself)
            if (pages.isNotEmpty() && tools.toolScreen == null) tools.toolScreen = ToolScreen.Scan
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
                if (tools.toolScreen !is ToolScreen.Combine) tools.toolScreen = ToolScreen.Combine(null)
            }
            FilePickKind.Create -> tools.createFrom(uris)
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
                docTools.clearMarkImage()
                docTools.markImage = f
            }
            FilePickKind.Attach -> {
                val doc = docTools.attachTo ?: return
                docTools.attachTo = null
                viewModelScope.launch {
                    val uri = uris.first()
                    val f = withContext(Dispatchers.IO) { runCatching { copyIn(uri) }.getOrNull() }
                    if (f == null) tell(text(R.string.msg_that_file_unreadable))
                    else docTools.attachPrompt = AttachPrompt(doc, f, f.name)
                }
            }
            FilePickKind.Replace -> {
                val (doc, pages) = docTools.replaceTarget ?: return
                docTools.replaceTarget = null
                viewModelScope.launch {
                    val f = withContext(Dispatchers.IO) { runCatching { copyIn(uris.first()) }.getOrNull() }
                    if (f == null) tell(text(R.string.msg_that_file_unreadable))
                    else docTools.replacePrompt = ReplacePrompt(doc, pages, f)
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
    internal fun dropPicked(picked: List<File>) {
        viewModelScope.launch(NonCancellable + Dispatchers.IO) { picked.forEach { it.parentFile?.deleteRecursively() } }
    }

    /** A copy of a picked file in the app's cache, under its own name. */
    internal fun copyIn(uri: Uri): File {
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
            tools.busy = message
            try {
                block(eng)
            } catch (e: Exception) {
                tell(e.userMessage)
            } finally {
                tools.busy = null
                tools.busyProgress = null
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
        enabled = settings.appPrefs.getBoolean("compress.advanced", false),
        targetMb = settings.appPrefs.getFloat("compress.targetMb", 0f).toDouble().takeIf { it > 0 },
        ppi = settings.appPrefs.getInt("compress.ppi", 150),
        quality = settings.appPrefs.getInt("compress.quality", 60),
        mobile = settings.appPrefs.getBoolean("compress.mobile", false),
        removeInvalidLinks = settings.appPrefs.getBoolean("compress.removeInvalidLinks", true),
        discardFormActions = settings.appPrefs.getBoolean("compress.discardFormActions", false),
        discardTags = settings.appPrefs.getBoolean("compress.discardTags", false),
        discardAlternateImages = settings.appPrefs.getBoolean("compress.discardAlternateImages", true),
    )

    fun updateCompressOptions(o: CompressOptions) {
        compressOptions = o
        settings.appPrefs.edit()
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
        tools.toolScreen = null
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
        tools.toolScreen = null
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
        tools.toolScreen = null
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
        tools.toolScreen = null
        deliver(out, "${doc.title}_unprotected.pdf")
    }

    // ---- Flashcards, Library, Study sessions, Study digest, Notebooks: see StudyController ----

    val study = StudyController(this)

    // ---- Organize:, Layers, attachments, Document properties: see DocumentToolsController ----

    val docTools = DocumentToolsController(this)

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
                if (scanPages.isEmpty()) tools.toolScreen = null
            }
            return
        }
        viewModelScope.launch {
            // the pages taken before Android closed the app come first
            scanRestore?.join()
            val thumb = withContext(Dispatchers.IO) { scanThumb(f) } ?: return@launch
            val r = retakeIndex
            // the Flatten page choice and the look of the page it replaces, or of the page before it
            // (#302, #301): a retaken page keeps them, a new one takes the last chosen
            val page = ScanPage(
                f, thumb,
                flatten = io.github.artcraftandroid.pdfcraft.ui.scan.scanNewPageFlatten(scanPages.map { it.flatten }, r),
                look = io.github.artcraftandroid.pdfcraft.ui.scan.scanLookForNewPage(scanPages.map { it.look }, r),
            )
            retakeIndex = null
            // a retaken half of a book spread takes its other half with it (#303)
            replaceScanPages(r, listOf(page))
            if (scanName.isBlank()) scanName = "Scan " + java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
            if (tools.toolScreen != ToolScreen.Scan) tools.toolScreen = ToolScreen.Scan
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
            // a low-light merge (#297) gave a page already straightened: its edges are the whole picture
            val straightened = io.github.artcraftandroid.pdfcraft.ui.scan.ScanCaptures.of(f.path)?.straightened == true
            val doc = eng?.takeUnless { straightened }?.let {
                withContext(Dispatchers.IO) { runCatching { it.prepareOcrModels(getApplication()) } }
                withContext(Dispatchers.Default) { runCatching { io.github.artcraftandroid.pdfcraft.core.scanFindDocumentWith(f.absolutePath, lab.findOptions()) } }
                    .onFailure { e -> Diag.failure(DiagArea.Scan, "scan_find_document", e) }.getOrNull()
            }
            val edges = doc?.takeIf { it.found }?.corners?.takeIf { it.size == 8 }?.toFloatArray()
            // book mode (#303): the outline (or the whole photo when none was found) cut along the gutter
            val book = scanBook.on
            val bookOrder = scanBook.order
            val bookStarted = android.os.SystemClock.elapsedRealtime()
            val cut = if (book && eng != null) splitScanSpread(f, edges ?: ScanPage.WHOLE, lab.bookGutter) else null
            val bookMs = android.os.SystemClock.elapsedRealtime() - bookStarted
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
                "refined_sides" to (doc?.refined?.count { it } ?: 0), "curved_sides" to (doc?.curved?.count { it } ?: 0), "curved_opposite" to scanCurvedOpposite(doc?.curved.orEmpty()),
                "finder" to ScanLab.finderId(lab.finder), "refine" to lab.refine, "straightened" to straightened,
                *io.github.artcraftandroid.pdfcraft.ui.scan.scanBookDiag(book, lab.bookGutter, bookOrder, cut, bookMs),
            )
            val finding = doc?.let { d ->
                ScanFinding(d.source.name.lowercase(), d.confidence.toDouble(), d.angle.toInt(), d.passes.toInt(), d.refined.count { it }, d.curved.count { it }, scanCurvedOpposite(d.curved), lab.findOptions())
            }
            val at = scanPages.indexOfFirst { it.file == f }
            if (at >= 0) {
                // a page whose outline was not found remembers the corners it started with (#444)
                val one = if (edges != null) scanPages[at].copy(corners = edges, found = edges, finding = finding)
                else scanPages[at].let { it.copy(finding = finding, uncropped = io.github.artcraftandroid.pdfcraft.ui.scan.scanUncropped(it.corners, null, straightened)) }
                // in book mode the page becomes the spread's two halves, in the order chosen (#303)
                val halves = io.github.artcraftandroid.pdfcraft.ui.scan.scanBookPages(one.corners, cut, bookOrder).map { (corners, half) ->
                    // a half's own outline is what was found for it, so a corner moved later is measured from there
                    if (half == null) one else one.copy(corners = corners, found = one.found?.let { corners }, uncropped = one.uncropped?.let { corners }, book = half)
                }
                scanPages.removeAt(at)
                scanPages.addAll(at, halves)
            }
        }
    }

    /** Book mode (#303): whether photos are cut into a spread's two pages, and their order. */
    internal val scanBook by lazy {
        io.github.artcraftandroid.pdfcraft.ui.scan.ScanBookController(settings.appPrefs) {
            getApplication<Application>().resources.configuration.layoutDirection == android.view.View.LAYOUT_DIRECTION_RTL
        }
    }

    /** The outline [corners] of the photo [f] cut along the book's gutter (#303); null when that failed. */
    private suspend fun splitScanSpread(f: File, corners: FloatArray, lookForGutter: Boolean): io.github.artcraftandroid.pdfcraft.ui.scan.ScanBookCut? =
        withContext(Dispatchers.Default) {
            runCatching { io.github.artcraftandroid.pdfcraft.core.scanSplitSpread(f.absolutePath, corners.toList(), lookForGutter) }
                .onFailure { e -> Diag.failure(DiagArea.Scan, "scan_split_spread", e) }.getOrNull()
        }?.let {
            io.github.artcraftandroid.pdfcraft.ui.scan.ScanBookCut(it.split, it.left.toFloatArray(), it.right.toFloatArray(), it.gutterFound, it.gutterTop, it.gutterBottom, it.confidence)
        }

    /**
     * Put [replacement] in place of page [at] (null or out of range: after the last page). A half
     * of a book spread goes with its other half, and a photo no page uses any more is deleted (#303).
     */
    private fun replaceScanPages(at: Int?, replacement: List<ScanPage>) {
        val before = scanPages.toList()
        val after = io.github.artcraftandroid.pdfcraft.ui.scan.scanReplacePage(before, at, replacement) { a, b -> a.file == b.file }
        scanPages.clear()
        scanPages.addAll(after)
        deleteUnusedScanFiles(before, after)
    }

    /** Remove page [index] from the scan; its photo is deleted unless the spread's other half still uses it (#303). */
    fun deleteScanPage(index: Int) {
        if (index !in scanPages.indices) return
        val before = scanPages.toList()
        scanPages.removeAt(index)
        deleteUnusedScanFiles(before, scanPages.toList())
    }

    private fun deleteUnusedScanFiles(before: List<ScanPage>, after: List<ScanPage>) {
        val unused = io.github.artcraftandroid.pdfcraft.ui.scan.scanUnusedFiles(before.map { it.file.path }, after.map { it.file.path })
        unused.forEach { File(it).delete() }
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
        // the previews and the strip's tiles of a scan given up on (#301)
        releaseScanPreviews()
        insertScanAt = null
        tools.toolScreen = null
    }

    /** Where Save writes each scanned page's straightened image (removed when the scan ends). */
    private fun scanPageDir() = File(getApplication<Application>().cacheDir, "scan")

    // ---- Scan: the page as it will be saved (#301) ---------------------------------------------

    /** The latest page preview made for the scan screen, and the request it answers. */
    internal class ScanPreviewShown(
        val key: ScanPreviewKey,
        val image: androidx.compose.ui.graphics.ImageBitmap,
        val quarterTurns: Int,
        val flattened: Boolean,
        /** The kind of document Clean scan took the page for (#299); null for the other filters or with the option off. */
        val kind: io.github.artcraftandroid.pdfcraft.core.ScanPageKind? = null,
    )

    internal var scanPreview by mutableStateOf<ScanPreviewShown?>(null)
        private set

    /** The request whose preview could not be made (the screen then shows the photo, without waiting). */
    internal var scanPreviewFailed by mutableStateOf<ScanPreviewKey?>(null)
        private set

    /** Previews run one at a time, off the main thread and away from saving; the latest asked for wins. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scanPreviewDispatcher = Dispatchers.Default.limitedParallelism(1)
    private var scanPreviewJob: kotlinx.coroutines.Job? = null
    /** Tells the native preview running now to stop when a newer one is asked for (#301). */
    private var scanPreviewStop: io.github.artcraftandroid.pdfcraft.core.ScanCancel? = null
    /** The request asked for last: a preview of anything else is out of date when it arrives. */
    private var scanPreviewWanted: ScanPreviewKey? = null

    /** What the last preview of each photo was, for a kept scan's page file. */
    private val scanPreviewStats = java.util.concurrent.ConcurrentHashMap<String, ScanTestPreview>()

    /**
     * Make the preview of [page] in its look with [flatten] (#301), replacing any still waiting.
     * One already running is told to stop at its next stage (testing option on); a result that
     * comes anyway is dropped when a newer request has come in meanwhile.
     */
    internal fun requestScanPreview(index: Int, page: ScanPage, flatten: Boolean) {
        val lab = scanLab.options
        val autoUpright = io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting.get(settings.appPrefs)
        val key = ScanPreviewKey.of(page, flatten, lab, autoUpright)
        if (scanPreview?.key == key || scanPreviewWanted == key) return
        scanPreviewWanted = key
        scanPreviewJob?.cancel()
        // the native preview still running for an older request stops at its next stage (#301)
        if (lab.previewStop) scanPreviewStop?.cancel()
        val stop = io.github.artcraftandroid.pdfcraft.core.ScanCancel()
        scanPreviewStop = stop
        val look = page.look
        val filter = look.filter
        // the same page in another look or strength: the slider sends many in a row, and only the
        // one it settles on is worth making (#301); a new page starts at once
        val settle = key.samePage(scanPreview?.key)
        scanPreviewJob = viewModelScope.launch(scanPreviewDispatcher) {
            if (settle) delay(SCAN_PREVIEW_SETTLE_MS)
            val request = scanPageRequest(page.file.absolutePath, page.corners, filter, flatten, lab, "", page.turns, autoUpright, io.github.artcraftandroid.pdfcraft.ui.scan.scanPresetFor(page, lab), page.finding?.curvedSides ?: 0, page.finding?.curvedOpposite ?: false, page.book != null, look.strength, look.paper)
            val started = android.os.SystemClock.elapsedRealtime()
            val made = runCatching { io.github.artcraftandroid.pdfcraft.core.scanPreviewPageWith(request, SCAN_PREVIEW_SIDE.toUInt(), stop) }
            val ms = android.os.SystemClock.elapsedRealtime() - started
            if (made.isFailure && stop.isCancelled()) {
                // stopped for a newer request: not a failure, and nothing to show
                Diag.event(DiagArea.Scan, "preview_stopped", "index" to index + 1, "ms" to ms)
                return@launch
            }
            val p = made.getOrNull()
            val image = p?.let { scanPreviewBitmap(it)?.asImageBitmap() }
            made.exceptionOrNull()?.let { Diag.failure(DiagArea.Scan, "scan_preview_page", it) }
            // what the preview took and whether it reused the straightened page (#301)
            Diag.event(
                DiagArea.Scan, "preview", "index" to index + 1, "filter" to filter,
                // the page's own look and strength (#301)
                "look" to io.github.artcraftandroid.pdfcraft.ui.scan.scanFilterId(filter), "strength" to scanLookStrength(look, lab), "paper" to look.paper.word, "whiteboard_look" to lab.whiteboardLook,
                "strength_source" to (if (lab.strength != null) "lab" else look.strengthSource), "settled" to settle,
                // the strength the page was really made at, and whether the slider's counts as set by hand (#424)
                *scanAppliedStrengthDiag(scanAppliedStrength(look, lab, p?.kind?.strength?.toInt()), lab),
                "flatten" to flatten, "made" to (image != null),
                "reused" to (p?.reused ?: false), "ms" to ms,
                *(p?.timings.orEmpty().map<io.github.artcraftandroid.pdfcraft.core.ScanTiming, Pair<String, Any?>> { it.stage.removePrefix("scan_") + "_ms" to it.ms.toLong() }.toTypedArray()),
                "width" to (p?.width?.toLong() ?: 0L), "height" to (p?.height?.toLong() ?: 0L),
                "quarter_turns" to (p?.quarterTurns?.toInt() ?: 0), "flattened" to (p?.flattened ?: false),
                // flattened and straightened compared (#302)
                "flatten_auto" to lab.flattenAuto, *scanFlattenCheckDiag(p?.flattenCheck),
                // the person's own choice for the page, and the strip of table trimmed from a flattened one (#302)
                "flatten_page" to io.github.artcraftandroid.pdfcraft.ui.scan.scanFlattenChoiceId(page.flatten),
                "flatten_trim" to lab.flattenTrim, "flatten_line_floor" to lab.flattenLineFloor, *io.github.artcraftandroid.pdfcraft.ui.scan.scanTrimDiag(p?.trim),
                // flattened from its lines of text when the network gave nothing better (#303)
                *scanLineFlattenDiag(lab.lineFlatten, p?.lineFlatten),
                // the kind of document Clean scan took the page for (#299)
                "preset" to (p?.kind?.preset ?: "none"), "preset_chosen" to (p?.kind?.chosen ?: false),
                // where the kind came from: not worked out for a page whose outline was not found (#444)
                "kind_source" to io.github.artcraftandroid.pdfcraft.ui.scan.scanKindSource(page, lab),
            )
            withContext(Dispatchers.Main) {
                // a newer request may have come in while this one ran: only the wanted one is shown
                if (scanPreviewWanted != key) return@withContext
                if (p != null && image != null) {
                    scanPreview = ScanPreviewShown(key, image, p.quarterTurns.toInt(), p.flattened, p.kind)
                    scanPreviewStats[page.key] = ScanTestPreview(true, p.reused, ms, p.width.toInt(), p.height.toInt(), filter.mode)
                    // the strip and the comparison follow the page shown (#301)
                    requestScanTiles(index, page, flatten)
                } else {
                    scanPreviewFailed = key
                }
            }
        }
    }

    /** A native preview as a bitmap; null when it could not be made into one. */
    private fun scanPreviewBitmap(p: io.github.artcraftandroid.pdfcraft.core.ScanPreview): android.graphics.Bitmap? = runCatching {
        val bmp = android.graphics.Bitmap.createBitmap(p.width.toInt(), p.height.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
        // ARGB_8888 keeps its bytes in R, G, B, A order: the preview's own
        bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(p.rgba))
        bmp
    }.getOrNull()

    // ---- Scan: the looks strip and the comparison with the original (#301) --------------------

    /** Each look of the page shown, small, for the looks strip; the look the page has shows its own preview instead. */
    internal val scanTiles = androidx.compose.runtime.mutableStateMapOf<ScanFilter, androidx.compose.ui.graphics.ImageBitmap>()

    /** The page (in the Original look) the tiles and [scanCompare] are of; anything else is out of date. */
    internal var scanTilesFor by mutableStateOf<ScanPreviewKey?>(null)
        private set

    /** The page in the Original look at full preview size, shown while a finger is held on the preview. */
    internal var scanCompare by mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        private set

    private var scanTilesJob: kotlinx.coroutines.Job? = null
    private var scanTilesStop: io.github.artcraftandroid.pdfcraft.core.ScanCancel? = null

    // ---- Scan: a sharper page while zoomed in (#301) -------------------------------------------

    /** The page shown at [SCAN_DETAIL_SIDE], for looking closer; the key says which page and look it is. */
    internal var scanDetail by mutableStateOf<Pair<ScanPreviewKey, androidx.compose.ui.graphics.ImageBitmap>?>(null)
        private set
    private var scanDetailJob: kotlinx.coroutines.Job? = null
    private var scanDetailWanted: ScanPreviewKey? = null

    /**
     * Make [page] in its look at [SCAN_DETAIL_SIDE] once the person has zoomed in (#301), on the
     * preview's thread, so the text can be read at 100 %. It is a second straightened page, kept
     * natively beside the preview's.
     */
    internal fun requestScanDetail(index: Int, page: ScanPage, flatten: Boolean) {
        val lab = scanLab.options
        val autoUpright = io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting.get(settings.appPrefs)
        val key = ScanPreviewKey.of(page, flatten, lab, autoUpright)
        if (scanDetail?.first == key || scanDetailWanted == key) return
        scanDetailWanted = key
        scanDetailJob?.cancel()
        val look = page.look
        scanDetailJob = viewModelScope.launch(scanPreviewDispatcher) {
            val request = scanPageRequest(page.file.absolutePath, page.corners, look.filter, flatten, lab, "", page.turns, autoUpright, io.github.artcraftandroid.pdfcraft.ui.scan.scanPresetFor(page, lab), page.finding?.curvedSides ?: 0, page.finding?.curvedOpposite ?: false, page.book != null, look.strength, look.paper)
            val started = android.os.SystemClock.elapsedRealtime()
            val made = runCatching { io.github.artcraftandroid.pdfcraft.core.scanPreviewPage(request, SCAN_DETAIL_SIDE.toUInt()) }
            made.exceptionOrNull()?.let { Diag.failure(DiagArea.Scan, "scan_preview_detail", it) }
            val p = made.getOrNull()
            val image = p?.let { scanPreviewBitmap(it)?.asImageBitmap() }
            Diag.event(
                DiagArea.Scan, "preview_detail", "index" to index + 1, "made" to (image != null), "reused" to (p?.reused ?: false),
                "ms" to android.os.SystemClock.elapsedRealtime() - started, "width" to (p?.width?.toLong() ?: 0L), "height" to (p?.height?.toLong() ?: 0L),
            )
            withContext(Dispatchers.Main) {
                if (scanDetailWanted == key && image != null) scanDetail = key to image
            }
        }
    }

    /**
     * Make what the strip and the comparison need for [page]: Original first (for comparing), then
     * the other looks when the strip shows previews. Each is made at the main preview's size, so the
     * straightened page kept natively serves them all and each costs one look; it then shrinks to
     * a tile. They share the preview's thread and give way to it between looks.
     */
    internal fun requestScanTiles(index: Int, page: ScanPage, flatten: Boolean) {
        val lab = scanLab.options
        val autoUpright = io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting.get(settings.appPrefs)
        val base = ScanPreviewKey.of(page, flatten, lab, autoUpright, io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(ScanFilter.Original))
        if (base != scanTilesFor) {
            scanTilesJob?.cancel()
            if (lab.previewStop) scanTilesStop?.cancel()
            scanTilesJob = null
            scanTiles.clear()
            scanCompare = null
            scanTilesFor = base
        }
        val order = if (lab.lookStrip) io.github.artcraftandroid.pdfcraft.ui.scan.scanTileOrder(page.look.filter) else listOf(ScanFilter.Original)
        val todo = order.filter { it !in scanTiles || (it == ScanFilter.Original && scanCompare == null) }
        if (todo.isEmpty() || scanTilesJob?.isActive == true) return
        val stop = io.github.artcraftandroid.pdfcraft.core.ScanCancel()
        scanTilesStop = stop
        val tileSide = (SCAN_TILE_SIDE_DP * getApplication<Application>().resources.displayMetrics.density).toInt().coerceAtLeast(48)
        scanTilesJob = viewModelScope.launch(scanPreviewDispatcher) {
            val started = android.os.SystemClock.elapsedRealtime()
            var made = 0
            var reused = 0
            var slowest = 0L
            for (f in todo) {
                // a page preview asked for meanwhile goes first
                kotlinx.coroutines.yield()
                val request = scanPageRequest(page.file.absolutePath, page.corners, f, flatten, lab, "", page.turns, autoUpright, io.github.artcraftandroid.pdfcraft.ui.scan.scanPresetFor(page, lab), page.finding?.curvedSides ?: 0, page.finding?.curvedOpposite ?: false, page.book != null)
                val one = android.os.SystemClock.elapsedRealtime()
                val p = runCatching { io.github.artcraftandroid.pdfcraft.core.scanPreviewPageWith(request, SCAN_PREVIEW_SIDE.toUInt(), stop) }
                    .onFailure { if (!stop.isCancelled()) Diag.failure(DiagArea.Scan, "scan_look_tile", it) }.getOrNull() ?: continue
                slowest = maxOf(slowest, android.os.SystemClock.elapsedRealtime() - one)
                if (p.reused) reused++
                val full = scanPreviewBitmap(p) ?: continue
                val k = tileSide.toFloat() / maxOf(full.width, full.height)
                val tile = runCatching { android.graphics.Bitmap.createScaledBitmap(full, maxOf(1, (full.width * k).toInt()), maxOf(1, (full.height * k).toInt()), true) }.getOrNull() ?: continue
                made++
                withContext(Dispatchers.Main) {
                    if (scanTilesFor != base) return@withContext
                    scanTiles[f] = tile.asImageBitmap()
                    if (f == ScanFilter.Original) scanCompare = full.asImageBitmap()
                }
            }
            // how long the strip took and whether the straightened page served every look (#301)
            Diag.event(
                DiagArea.Scan, "looks_strip", "index" to index + 1, "look_strip" to lab.lookStrip, "asked" to todo.size, "made" to made,
                "reused" to reused, "ms" to android.os.SystemClock.elapsedRealtime() - started, "slowest_ms" to slowest,
            )
        }
    }

    /** A page's last preview for a kept scan, or "none shown" (#301). */
    private fun scanPreviewRecord(page: ScanPage): ScanTestPreview =
        scanPreviewStats[page.key] ?: ScanTestPreview(false, false, 0, 0, 0, null)

    /** The scan is over: forget the previews and the straightened pages kept for them. */
    private fun releaseScanPreviews() {
        scanPreviewJob?.cancel()
        scanPreviewJob = null
        scanPreviewWanted = null
        scanPreview = null
        scanPreviewFailed = null
        scanPreviewStats.clear()
        scanTilesJob?.cancel()
        scanTilesJob = null
        scanPreviewStop?.cancel()
        scanTilesStop?.cancel()
        scanTiles.clear()
        scanTilesFor = null
        scanCompare = null
        scanDetailJob?.cancel()
        scanDetailJob = null
        scanDetailWanted = null
        scanDetail = null
        runCatching { io.github.artcraftandroid.pdfcraft.core.scanReleasePreviews() }
    }

    /**
     * Save the scanned pages as a PDF, each with its own look (#301), or insert them where Organize asked.
     * Each photo is decoded once, straightened to the page's true proportions (#293), given the
     * look and encoded once natively (#287); a page that can't be processed goes in as the camera
     * took it. A page whose Flatten page switch is on is drawn flat by the flattening network
     * first (#302); that is the person's choice for each page, never made for them. Cancel stops between
     * pages and leaves the scan as it was (#200, #301).
     */
    fun saveScan() = job(text(R.string.msg_busy_saving_scan)) { eng ->
        val pages = scanPages.toList()
        val scanStarted = android.os.SystemClock.elapsedRealtime()
        val dir = scanPageDir().apply { mkdirs() }
        val saved = ArrayList<ScanSavedPage>(pages.size)
        // the testing options in force (#288), and where this scan is kept when scans are kept
        val lab = scanLab.options
        // Cancel in the busy dialog stops before the next page is made (#200)
        var cancelled = false
        tools.busyCancel = { cancelled = true }
        val keepIn = try {
            withContext(Dispatchers.IO) { scanLab.begin() }
        } catch (e: Exception) {
            tools.busyCancel = null
            throw e
        }
        // which way up (#339): the ordinary setting, and each page's own turn when the person gave one
        val autoUpright = io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting.get(settings.appPrefs)
        // each page made once, the same request the screen's preview was made from (#301)
        val made = arrayOfNulls<io.github.artcraftandroid.pdfcraft.core.ScanPageResult>(pages.size)
        val madeMs = LongArray(pages.size)
        suspend fun make(i: Int, turns: Int?) {
            val page = pages[i]
            tools.busy = text(R.string.msg_busy_cleaning_page, i + 1, pages.size)
            val flatten = io.github.artcraftandroid.pdfcraft.ui.scan.scanPageFlattens(page.flatten, scanCanFlatten)
            tools.busyProgress = i to pages.size
            val request = scanPageRequest(page.file.absolutePath, page.corners, page.look.filter, flatten, lab, File(dir, "page-$i").absolutePath, turns, autoUpright, io.github.artcraftandroid.pdfcraft.ui.scan.scanPresetFor(page, lab), page.finding?.curvedSides ?: 0, page.finding?.curvedOpposite ?: false, page.book != null, page.look.strength, page.look.paper)
            val started = android.os.SystemClock.elapsedRealtime()
            val result = withContext(Dispatchers.Default) { runCatching { io.github.artcraftandroid.pdfcraft.core.scanProcessPage(request) } }
            result.onFailure { Diag.failure(DiagArea.Scan, "scan_process_page", it) }
            madeMs[i] += android.os.SystemClock.elapsedRealtime() - started
            result.getOrNull()?.let { made[i] = it }
        }
        try {
            for (i in pages.indices) {
                if (cancelled) break
                make(i, pages[i].turns)
            }
        } finally {
            // from here the pages go into the PDF, which can't be stopped half-way
            tools.busyCancel = null
        }
        if (cancelled) {
            // nothing is saved: the pages made so far go, the scan stays on screen as it was
            val madeCount = made.count { it != null }
            withContext(Dispatchers.IO) {
                dir.listFiles()?.forEach { it.delete() }
                keepIn?.let { scanLab.end(it) }
            }
            Diag.event(DiagArea.Scan, "save_cancelled", "pages" to pages.size, "made" to madeCount, "ms" to android.os.SystemClock.elapsedRealtime() - scanStarted)
            tell(text(R.string.msg_scan_save_cancelled))
            return@job
        }
        tools.busyProgress = null
        // what the classifier said of each page, before the multi-page rule makes any page again
        val found = made.map { it?.orientation }
        val classifierTurns = made.map { it?.quarterTurns?.toInt() ?: 0 }
        // the multi-page rule (#339): an unsure page between two sure neighbours that agree takes
        // their turn, and is made again with it
        val settled = if (lab.settleTurns && pages.size >= 3) {
            runCatching { io.github.artcraftandroid.pdfcraft.core.scanSettleTurns(found) }.getOrNull()
                ?.map { io.github.artcraftandroid.pdfcraft.ui.scan.ScanSettled(it.quarterTurns.toInt(), it.followed) }
        } else null
        val redo = settled?.let { io.github.artcraftandroid.pdfcraft.ui.scan.scanTurnsToRedo(it, made.map { r -> r?.turns?.toInt() }, lab.settleTurns) }.orEmpty()
        for ((i, turn) in redo) make(i, turn)
        for ((i, page) in pages.withIndex()) {
            // the Scan gates (#286): found edges, whether they were moved and how far (in % of the
            // photo's diagonal, so the thumbnail's proportions are enough)
            val stats = scanPageStats(page.corners, page.found, ScanPage.WHOLE, page.thumb.width.toDouble(), page.thumb.height.toDouble())
            // a whole photo in Original stays the camera's own file: natively it only gets its
            // resolution written in, so its PDF page has a size like the others (#293)
            val flatten = io.github.artcraftandroid.pdfcraft.ui.scan.scanPageFlattens(page.flatten, scanCanFlatten)
            val filter = page.look.filter
            val strength = scanLookStrength(page.look, lab)
            val untouched = !(flatten || lab.flattenAuto) && !scanNeedsProcessing(page.corners.contentEquals(ScanPage.WHOLE), filter.mode)
            val r = made[i]
            val followed = i in redo
            val turnSource = io.github.artcraftandroid.pdfcraft.ui.scan.scanTurnSource(page.turns, followed, page.turns == null && autoUpright && lab.upright)
            // decode, straighten, look and encode times (#287), the page's pixels, and how its
            // proportions were worked out and which paper it was matched to (#293)
            Diag.event(
                DiagArea.Scan, "page", "index" to i + 1, "book_half" to (page.book?.side ?: "none"), "edges_found" to stats.found, "corners_moved" to stats.touched, "max_move_pct" to stats.maxMovePct,
                "filter" to filter, "enhanced" to (r != null && !untouched), "enhance_ms" to madeMs[i],
                // the page's own look and where its strength came from (#301)
                "look" to io.github.artcraftandroid.pdfcraft.ui.scan.scanFilterId(filter), "look_per_page" to lab.lookPerPage,
                "paper" to page.look.paper.word, "whiteboard_look" to lab.whiteboardLook,
                "strength_source" to (if (lab.strength != null) "lab" else page.look.strengthSource),
                "look_differs" to io.github.artcraftandroid.pdfcraft.ui.scan.scanLookDiffers(pages.map { it.look }, i),
                *(r?.timings.orEmpty().map<io.github.artcraftandroid.pdfcraft.core.ScanTiming, Pair<String, Any?>> { it.stage.removePrefix("scan_") + "_ms" to it.ms.toLong() }.toTypedArray()),
                "out_width" to (r?.width?.toLong() ?: 0L), "out_height" to (r?.height?.toLong() ?: 0L),
                "out_format" to (r?.format?.name?.lowercase() ?: "none"), "dpi" to (r?.dpi?.toDouble() ?: 0.0),
                "paper" to (r?.paper?.name?.lowercase() ?: "none"), "aspect" to (r?.aspectSource?.name?.lowercase() ?: "none"),
                "focal_mismatch" to (r?.focalMismatch ?: false),
                "flatten" to flatten, "flattened" to (r?.flattened ?: false), "bend_pct" to ((r?.bend ?: 0f) * 100.0),
                "flatten_auto" to lab.flattenAuto, *scanFlattenCheckDiag(r?.flattenCheck),
                // the person's own choice for the page, and the strip of table trimmed from a flattened one (#302)
                "flatten_page" to io.github.artcraftandroid.pdfcraft.ui.scan.scanFlattenChoiceId(page.flatten),
                "flatten_trim" to lab.flattenTrim, "flatten_line_floor" to lab.flattenLineFloor, *io.github.artcraftandroid.pdfcraft.ui.scan.scanTrimDiag(r?.trim),
                *scanLineFlattenDiag(lab.lineFlatten, r?.lineFlatten),
                "strength" to strength, "side_proportions" to !lab.perspective, "snap_paper" to lab.snapPaper,
                // the strength the page was really made at, and whether the slider's counts as set by hand (#424)
                *scanAppliedStrengthDiag(scanAppliedStrength(page.look, lab, r?.kind?.strength?.toInt()), lab),
                // which way up the page was found to be and whether it was turned (#339)
                "upright" to lab.upright, "quarter_turns" to classifierTurns[i], "upright_sure" to (found[i]?.sure ?: false),
                "upright_confidence" to (found[i]?.confidence?.toDouble() ?: 0.0), "upright_agreement" to (found[i]?.agreement?.toDouble() ?: 0.0),
                "upright_tiles" to (found[i]?.tiles?.toInt() ?: 0),
                // the turn the page was saved with and where it came from: the person, its neighbours or the classifier
                "auto_upright" to autoUpright, "turns_manual" to (page.turns ?: -1), "turns" to (r?.turns?.toInt() ?: 0),
                "turn_source" to turnSource, "settle_turns" to lab.settleTurns, "followed" to followed,
                // sharpening and smoothing asked of the Clean scan filter, and the grain it measured (#299)
                "sharpen" to lab.sharpen, "denoise" to lab.denoise, "noise_sigma" to (r?.noise?.toDouble() ?: -1.0),
                // the kind of document Clean scan took the page for, its settings and what it read (#299)
                "presets" to lab.presets, "preset" to (r?.kind?.preset ?: "none"), "preset_chosen" to (r?.kind?.chosen ?: false),
                // where the kind came from: not worked out for a page whose outline was not found (#444)
                "kind_source" to io.github.artcraftandroid.pdfcraft.ui.scan.scanKindSource(page, lab), "unfound_document" to lab.unfoundDocument,
                "preset_strength" to (r?.kind?.strength?.toInt() ?: -1), "preset_sharpen" to (r?.kind?.sharpen?.toInt() ?: -1),
                "keep_paper" to (r?.kind?.keepPaper ?: false), "suggest_flatten" to (r?.kind?.suggestFlatten ?: false),
                "suggest_greyscale" to (r?.kind?.suggestGreyscale ?: false), "page_ratio" to (r?.kind?.ratio?.toDouble() ?: 0.0),
                "ink_share" to (r?.kind?.inkShare?.toDouble() ?: -1.0), "colour_ink_share" to (r?.kind?.colourInkShare?.toDouble() ?: -1.0),
                "photo_share" to (r?.kind?.photoShare?.toDouble() ?: -1.0), "stroke_mm" to (r?.kind?.strokeMm?.toDouble() ?: -1.0),
                "bright_share" to (r?.kind?.brightShare?.toDouble() ?: -1.0), "gutter_band" to (r?.kind?.gutterBand ?: false),
                // the book-page gutter rule (#427): the option, what the first rule says, and how much of the side was dark
                "strict_gutter" to lab.strictGutter, "gutter_loose" to (r?.kind?.gutterLoose ?: false),
                "gutter_cover" to (r?.kind?.gutterCover?.toDouble() ?: -1.0),
                // the photo rule (#427): the option, and what the first rule reads as photograph
                "strict_photo" to lab.strictPhoto, "photo_loose" to (r?.kind?.photoLoose?.toDouble() ?: -1.0),
                // the book page rule (#442): the option, what the first rule says, and its inputs
                "strict_book" to lab.strictBook, "book_loose" to (r?.kind?.bookLoose ?: false),
                "curved_opposite" to (page.finding?.curvedOpposite ?: false), "book_mode" to (page.book != null),
                "whole_photo" to (r?.kind?.wholePhoto ?: false),
                // the kind's own tone and whether it was used, and the photo mask in sharpening (#299)
                "kind_tone" to lab.kindTone, "kind_gamma" to (r?.kind?.extraGamma?.toDouble() ?: 0.0),
                "kind_s_curve" to (r?.kind?.sCurve ?: false), "kind_colour" to (r?.kind?.colourFactor?.toDouble() ?: 1.0),
                "photo_sharpen" to lab.photoSharpen, "suggestions" to lab.suggestions,
            )
            if (keepIn != null) {
                val kept = r?.let {
                    ScanTestResult(
                        it.width.toLong(), it.height.toLong(), it.format.name.lowercase(), it.dpi.toDouble(), it.paper.name.lowercase(),
                        it.aspectSource.name.lowercase(), it.focalMismatch, it.flattened, it.bend * 100.0, classifierTurns[i],
                        found[i]?.let { o -> ScanTestOrientation(o.quarterTurns.toInt(), o.confidence.toDouble(), o.agreement.toDouble(), o.tiles.toInt(), o.sure) },
                        it.noise?.toDouble(),
                        it.flattenCheck,
                        it.trim,
                        it.lineFlatten,
                    )
                }
                val turn = ScanTestTurn(page.turns, autoUpright, r?.turns?.toInt() ?: 0, followed, turnSource)
                val kind = r?.kind?.let { k ->
                    ScanTestKind(
                        k.preset, k.chosen, k.strength.toInt(), k.sharpen.toInt(), k.keepPaper, k.suggestFlatten, k.suggestGreyscale, k.ratio.toDouble(),
                        k.inkShare.toDouble(), k.colourInkShare.toDouble(), k.photoShare.toDouble(), k.strokeMm.toDouble(), k.brightShare.toDouble(), k.gutterBand, k.wholePhoto,
                        k.gutterLoose, k.gutterCover.toDouble(), k.photoLoose.toDouble(), k.bookLoose, k.curvedOpposite, k.bookMode,
                        k.extraGamma.toDouble(), k.sCurve, k.colourFactor.toDouble(),
                    )
                }
                withContext(Dispatchers.IO) { scanLab.keepPage(keepIn, i, page, true, scanLookWord(filter, lab), strength, flatten, r?.path?.let(::File), kept, scanPreviewRecord(page), turn, kind, r?.let { scanAppliedStrength(page.look, lab, it.kind?.strength?.toInt()) }) }
            }
            saved += scanSavedPage(page.file.absolutePath, r?.let { it.path to it.dpi })
        }
        if (keepIn != null) {
            withContext(Dispatchers.IO) { scanLab.end(keepIn) }
            scanLab.refreshKept()
        }
        // pages whose processing failed went in as the camera took them; the message counts them (#301)
        val unenhanced = io.github.artcraftandroid.pdfcraft.ui.scan.scanUnenhancedCount(made.map { it != null })
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
                tools.busy = text(R.string.msg_busy_recognizing)
                runCatching { Diag.measure(DiagArea.Scan, "ocr", "pages" to pages.size) { eng.tool("ocr_recognize", mapOf("doc" to id)) } }
            }
            val out = tempFile("pdf")
            eng.tool("doc_save", mapOf("doc" to id, "path" to out.absolutePath, "full" to true))
            Diag.event(
                DiagArea.Scan, "saved", "pages" to pages.size, "filter" to pages.first().look.filter, "bytes" to out.length(),
                // how many pages have a look of their own, and how many went in as photographed (#301)
                "looks_mixed" to io.github.artcraftandroid.pdfcraft.ui.scan.scanLooksMixed(pages.map { it.look }), "unenhanced" to unenhanced,
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
            tools.toolScreen = null
            val unenhancedNote = if (unenhanced > 0) " " + text(R.string.msg_scan_unenhanced, unenhanced) else ""
            if (target != null) {
                if (unenhanced > 0) tell(unenhancedNote.trim())
                runTool(target.first, "page_insert_file", mapOf("path" to out.absolutePath, "at" to target.second))
            } else {
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm.ss", java.util.Locale.US).format(java.util.Date())
                val name = DocumentFiles.safeName(chosen.ifEmpty { "Scan $stamp" })
                val uri = withContext(Dispatchers.IO) { files.saveToDownloads(out, name).also { out.delete() } }
                tell(text(R.string.msg_saved_downloads, name) + unenhancedNote)
                open(uri)
            }
        } finally {
            runCatching { eng.tool("doc_close", mapOf("doc" to id, "discard_changes" to true)) }
        }
    }

    // ---- Home, Files: see HomeController ----

    val home = HomeController(this)

    // ---- Note margins, Handwriting to text, Lecture recording: see NoteTakingController ----

    val noteTaking = NoteTakingController(this)

    // ---- Backups (#145) and version history: see BackupController ----

    val backups = BackupController(this)

    // ---- The scanner's testing options and scans kept for testing (#288): see ScanLabController ----

    internal val scanLab = ScanLabController(this)
}

/** The most files Create PDF converts at once. */
internal const val MAX_CREATE_FILES = 12

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
