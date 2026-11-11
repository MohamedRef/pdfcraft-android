package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.recognizeText
import io.github.artcraftandroid.pdfcraft.ui.home.FileTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement
import androidx.compose.ui.graphics.asImageBitmap
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import io.github.artcraftandroid.pdfcraft.engine.UserBookmark
import io.github.artcraftandroid.pdfcraft.engine.UserBookmarks
import io.github.artcraftandroid.pdfcraft.ui.viewer.NotePrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.VoicePrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.ReplyPrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.StyledTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextMarkup
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
private const val MAX_SCRIPT_ALERTS = 20

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

    /** The document in the viewer, if one is open. */
    var document by mutableStateOf<OpenDocument?>(null)
        private set

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
    private val saveMutex = Mutex()

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
                if (withContext(Dispatchers.IO) { trustedCerts.files().isNotEmpty() }) {
                    runCatching { applyTrust(eng) }
                    document?.let { loadSignatures(eng, it) }
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
            recentList = withContext(Dispatchers.IO) {
                recents.list().also { list ->
                    val uris = list.map { it.uri }
                    // access to documents that left Recent is given back (#108), before the copies
                    // are pruned, so a document whose grant goes keeps its copy
                    runCatching { files.releaseUnusedAccess(uris, open) }
                    // copies of documents that left Recent go too (#42)
                    runCatching { files.pruneCopies(uris, open) }
                }
            }
        }
    }

    /** Opening is serial: multiple ACTION_VIEW deliveries must not share a working file or engine document. */
    private val openMutex = Mutex()
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
                    tell(text(R.string.msg_opened_own_copy))
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
                    tell(text(R.string.msg_opened_own_copy))
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
                val previousSaved = saveAndClose(eng, current)
                io.github.artcraftandroid.pdfcraft.engine.OpenTiming.milestone("previous_document_closed", openStartedAt)
                if (!previousSaved) {
                    readOnlyPrompt = current
                    return
                }
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
            // This counts editing-parser repairs only; renderer fallback work has its own trace.
            if (!info.preparing && io.github.artcraftandroid.pdfcraft.engine.OpenTiming.enabled()) {
                runCatching { eng.command { repairCount(info.id) } }.getOrNull()
                    ?.let { io.github.artcraftandroid.pdfcraft.engine.OpenTiming.count("native_repairs", it.toLong()) }
            }
            val images = PageImages(eng, info.id, viewModelScope, imageBudget, openedAt = openStartedAt, compact = compactPreviews)
            val source = uri.takeIf { it.scheme == "content" || it.scheme == "file" }
            val doc = OpenDocument(info.id, info, source, file, images, password, eng, viewModelScope)
            doc.fromOtherApp = fromOtherApp
            doc.writable = withContext(Dispatchers.IO) { source == null || files.canWrite(source) }
            doc.notWrittenBack = source?.let { withContext(Dispatchers.IO) { files.hasPendingWriteBack(it) } } ?: false
            io.github.artcraftandroid.pdfcraft.engine.OpenTiming.milestone("source_access_ready", openStartedAt)
            doc.controller.nightMode = savedNightMode
            if (savedPageMode != doc.viewer.mode) doc.viewer.switchMode(savedPageMode)
            // a file left in Reading Mode reopens in it, unless it was opened for a tool
            readingMode = recent?.reading == true && pendingTool == null
            document = doc
            io.github.artcraftandroid.pdfcraft.engine.OpenTiming.milestone("viewer_model_ready", openStartedAt, pages = info.pages.size)
            if (!info.preparing) {
                viewModelScope.launch {
                    doc.images.awaitFirstCanvas()
                    if (document === doc) {
                        loadLinks(eng, doc)
                        if (doc.info.signed) announceSignatures(doc)
                        startPendingTool(doc)
                    }
                }
            } else {
                completeReadingOpen(eng, doc, requestId, openStartedAt)
            }
            pendingPage = recent?.lastPage?.takeIf { it in 0 until info.pages.size }
            pendingSpot = recent?.takeIf { pendingPage != null && it.lastX >= 0f && it.lastY >= 0f }?.let { Triple(it.lastX, it.lastY, it.lastZoom) }
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
                if (io.github.artcraftandroid.pdfcraft.engine.OpenTiming.enabled()) {
                    runCatching { eng.command { repairCount(doc.id) } }.getOrNull()?.let {
                        io.github.artcraftandroid.pdfcraft.engine.OpenTiming.count("native_repairs", it.toLong())
                    }
                }
                if (doc.info.signed) announceSignatures(doc)
                startPendingTool(doc)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                if (document === doc && isCurrentOpenRequest(requestId)) {
                    tell(e.userMessage)
                    closeQuietly(eng, doc)
                    document = null
                    readingMode = false
                }
            }
        }
    }

    override fun onCleared() {
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
        val doc = document ?: return
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
                refreshRecents()
                withContext(Dispatchers.IO) { sweepCache() }
                if (doc.fromOtherApp) leave.trySend(Unit)
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
                            refreshRecents()
                            if (doc.fromOtherApp) leave.trySend(Unit)
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
                        refreshRecents()
                        if (doc.fromOtherApp) leave.trySend(Unit)
                    }
                } finally {
                    resolvingReadOnly = false
                }
            }
        }
    }

    private suspend fun saveAndClose(eng: Engine, doc: OpenDocument): Boolean {
        val reading = readingMode
        val pending = pendingPage
        // the spot at the screen's top-left and the zoom, to reopen there (as the reference does)
        val v = doc.viewer
        val spot = if (pending == null && v.mode == io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.Continuous) v.screenToPage(androidx.compose.ui.geometry.Offset(0f, v.padTop), nearest = true) else null
        val zoom = v.zoom
        withContext(Dispatchers.IO) {
            // in Reading Mode the page canvas has no layout: the page read is the pending one
            val page = pending ?: spot?.page ?: v.currentPage
            doc.source?.let { src ->
                recents.update(src.toString()) {
                    it.copy(lastPage = page, reading = reading, lastX = spot?.x ?: -1f, lastY = spot?.y ?: -1f, lastZoom = if (spot != null) zoom else 1f)
                }
            }
        }
        if (!save(doc, quiet = true)) return false
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
        val doc = document ?: return
        if (doc.controller.tool == ViewerTool.Draw) doc.commitTool?.invoke()
        viewModelScope.launch {
            // A paused Activity must not save a closed/replaced engine document.
            // Use the same lock as Open with and Close before touching native handles.
            openMutex.withLock {
                if (document === doc && readOnlyPrompt == null && !resolvingReadOnly) save(doc)
            }
        }
    }

    /**
     * Save [doc]'s changes: into its working file (incrementally, so earlier signatures stay
     * valid), then back over the file it came from when the app may write there. Returns
     * whether the changes reached the original; [quiet] leaves a failure's message to the caller.
     */
    suspend fun save(doc: OpenDocument, quiet: Boolean = false): Boolean = saveMutex.withLock {
        val eng = engine ?: return@withLock false
        // A reading stage has no edits; do not write an incompletely checked source back.
        if (doc.info.preparing) return@withLock true
        if (!doc.info.dirty && !doc.notWrittenBack) return@withLock true
        try {
            val src = doc.source
            // Mark before the working copy changes (#103): a process killed between doc_save and
            // the write-back must reopen the saved working copy, not copy the old original over it.
            markBeforeWorkingCopyChanges(doc)
            if (doc.info.dirty) eng.tool("doc_save", mapOf("doc" to doc.id))
            refresh(doc)
            if (src != null && src.scheme == "content") {
                // The marker set above stays until the provider verifiably holds the new bytes.
                if (!doc.writable) {
                    doc.notWrittenBack = true
                    return@withLock false
                }
                withContext(Dispatchers.IO) {
                    files.writeBack(doc.file, src)
                    files.clearPendingWriteBack(src)
                }
            } else if (src != null && src.scheme == "file") {
                val target = File(requireNotNull(src.path))
                if (target.canonicalPath != doc.file.canonicalPath) withContext(Dispatchers.IO) {
                    doc.file.copyTo(target, overwrite = true)
                    files.clearPendingWriteBack(src)
                }
            }
            doc.notWrittenBack = false
            true
        } catch (e: Exception) {
            doc.writable = false
            doc.notWrittenBack = true
            if (!quiet) tell(e.userMessage)
            false
        }
    }

    /**
     * Persist the write-back marker for [doc]'s source before its working copy is changed, so a
     * reopen after a crash keeps the changed copy (#103). Nothing to protect when the working
     * copy is the document's own file.
     */
    private suspend fun markBeforeWorkingCopyChanges(doc: OpenDocument) {
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
        io.github.artcraftandroid.pdfcraft.engine.OpenTiming.measure("signatures") { loadSignatures(eng, doc) }
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
                val r = eng.tool(name, args + ("doc" to doc.id))
                refresh(doc, io.github.artcraftandroid.pdfcraft.ui.viewer.pageTouchedBy(name, args))
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
            ViewerTool.Comment, ViewerTool.Markup, ViewerTool.Draw, ViewerTool.Text -> !info.allowsAnnotation
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
        ctl.switchTool(tool)
        if (tool == ViewerTool.Draw && toolPrefs.snapToShape && toolPrefs.takeSnapHint()) tell(text(R.string.msg_snap_to_shape_on))
        // the first time the reader comments without a name, ask for one (once)
        if (tool in setOf(ViewerTool.Comment, ViewerTool.Markup, ViewerTool.Draw, ViewerTool.Text) && toolPrefs.author.isBlank() && !toolPrefs.authorAsked) {
            toolPrefs.authorAsked = true
            authorPrompt = true
        }
    }

    /** Asking for the name comments are signed with. */
    var authorPrompt by mutableStateOf(false)

    /** Leave the active tool: whatever it holds (ink being drawn) is added first. */
    fun leaveTool(doc: OpenDocument) {
        doc.commitTool?.invoke()
        doc.commitTool = null
        if (doc.controller.tool == ViewerTool.Edit) {
            commitInlineEdit(doc)
            editSelection = null
            editImage = null
            imageEdit.crop = null
            editMode = io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.Select
        }
        if (certPlacing === doc) certPlacing = null
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
        when {
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

    /** A web address waiting for "Open web page?". */
    var linkPrompt by mutableStateOf<String?>(null)
        private set

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

    /** Share a copy of [doc] through the system's share sheet (saved first, with the ink being drawn). */
    fun share(doc: OpenDocument) {
        if (doc.controller.tool == ViewerTool.Draw) doc.commitTool?.invoke()
        handOut {
            // the ink is queued in the engine: reading the document back waits for it, so the save has it
            refresh(doc)
            save(doc)
            val uri = withContext(Dispatchers.IO) { files.shareable(doc.file, doc.name) }
            sendIntent(listOf(uri)).putExtra(Intent.EXTRA_SUBJECT, text(R.string.msg_share_subject, doc.title))
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
    private fun sendIntent(uris: List<Uri>, mime: String = "application/pdf"): Intent {
        val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.first())
        else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        send.clipData = ClipData(null, arrayOf(mime), ClipData.Item(uris.first())).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        return send.setType(mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
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
        if (!keyboardShortcuts) return false
        val doc = document ?: return false
        if (toolScreen != null || readingMode) return false
        val v = doc.viewer
        val ctl = doc.controller
        val center = androidx.compose.ui.geometry.Offset(v.viewW / 2f, v.viewH / 2f)
        val drawing = ctl.tool == ViewerTool.Draw
        if (!ctrl && drawing) io.github.artcraftandroid.pdfcraft.ui.viewer.presetForKey(keyCode)?.let { return toolPrefs.applyInkPreset(it) }
        val shortcut = io.github.artcraftandroid.pdfcraft.ui.viewer.viewerShortcut(keyCode, ctrl, shift) ?: return false
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
                selectedComment?.first === doc -> selectedComment = null
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
                val c = selectedComment?.takeIf { it.first === doc }?.second ?: return false
                deleteComment(doc, c)
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

    /** Print [doc] through Android's print framework. */
    fun print(doc: OpenDocument) {
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
            val unlocked = if (doc.info.encrypted) unlockedCopy(doc) else null
            // a copy, not the working file the app's pause may be saving (#79); either copy is
            // deleted when the print job finishes (the unlocked one has no password, #105)
            val copy = unlocked ?: printSnapshot(doc)
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

    /** A temporary copy of [doc] without its password, for printing; null if the security can't be removed. */
    private suspend fun unlockedCopy(doc: OpenDocument): File? {
        val eng = engine ?: return null
        val out = File(getApplication<Application>().cacheDir, "print-${System.nanoTime()}.pdf")
        return runCatching {
            val copy = eng.tool("doc_open", mapOf("path" to doc.file.absolutePath, "password" to doc.password)).optLong("doc")
            try {
                eng.tool("doc_unprotect", mapOf("doc" to copy))
                eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true))
            } finally {
                runCatching { eng.tool("doc_close", mapOf("doc" to copy, "discard_changes" to true)) }
            }
            out
        }.onFailure { out.delete() }.getOrNull()
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
                    }
                    withContext(Dispatchers.IO) {
                        files.writeBack(doc.file, target)
                        if (then != null) doc.source?.let { files.clearPendingWriteBack(it) }
                    }
                    if (then != null) doc.notWrittenBack = false
                    refresh(doc)
                }
                tell(text(R.string.msg_saved_copy))
                then?.invoke()
            } catch (e: Exception) {
                if (then != null && resolvingReadOnly) {
                    readOnlyPrompt = doc
                    resolvingReadOnly = false
                }
                tell(e.userMessage)
            }
        }
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
    private val appPrefs = app.getSharedPreferences("app", android.content.Context.MODE_PRIVATE)
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
        private set

    /** The page mode documents open in (View settings; remembered app-wide). */
    val savedPageMode: io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode
        get() = if (appPrefs.getString("pageMode", "continuous") == "single") io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.SinglePage
        else io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.Continuous

    val savedNightMode: Boolean get() = appPrefs.getBoolean("nightMode", false)

    /**
     * Two pages side by side (View settings; remembered app-wide). It applies only where the
     * window is wide enough ([io.github.artcraftandroid.pdfcraft.ui.viewer.spreadFits]); a phone
     * held upright shows one page.
     */
    var spreadPref by mutableStateOf(
        when (appPrefs.getString("spread", "single")) {
            "two" -> io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.TwoUp
            "cover" -> io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.TwoUpCover
            else -> io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.Single
        },
    )
        private set

    fun setSpread(spread: io.github.artcraftandroid.pdfcraft.ui.viewer.Spread) {
        // two pages side by side are page views: leave Reading Mode for them
        readingMode = false
        spreadPref = spread
        appPrefs.edit().putString("spread", when (spread) {
            io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.TwoUp -> "two"
            io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.TwoUpCover -> "cover"
            io.github.artcraftandroid.pdfcraft.ui.viewer.Spread.Single -> "single"
        }).apply()
    }

    fun setPageMode(doc: OpenDocument, mode: io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode) {
        // leaving Reading Mode: a canvas laid out already followed the page read; else it shows it on layout
        if (readingMode && doc.viewer.viewW > 0f) pendingPage = null
        readingMode = false
        doc.viewer.switchMode(mode)
        appPrefs.edit().putString("pageMode", if (mode == io.github.artcraftandroid.pdfcraft.ui.viewer.PageMode.SinglePage) "single" else "continuous").apply()
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

    /** Give a newly opened (and prepared) [doc] the saved reading colours. */
    fun applySavedReadingColors(doc: OpenDocument) {
        val saved = savedReadingColors
        if (saved != doc.controller.readingColors) applyReadingColors(doc, saved)
    }

    private fun applyReadingColors(doc: OpenDocument, colors: io.github.artcraftandroid.pdfcraft.ui.viewer.ReadingColors) {
        val eng = engine ?: return
        if (doc.info.preparing) return
        viewModelScope.launch {
            val ok = runCatching { eng.tool("view_reading_colors", mapOf("doc" to doc.id, "mode" to colors.engineMode)) }.isSuccess
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

    /** About → Diagnostics: record open timings in memory so they can be shared without adb. */
    var recordOpenTimings by mutableStateOf(appPrefs.getBoolean("recordOpenTimings", false).also(::applyOpenTimings))
        private set

    fun updateRecordOpenTimings(on: Boolean) {
        recordOpenTimings = on
        appPrefs.edit().putBoolean("recordOpenTimings", on).apply()
        applyOpenTimings(on)
    }

    private fun applyOpenTimings(on: Boolean) {
        val timing = io.github.artcraftandroid.pdfcraft.engine.OpenTiming
        // turning it off forgets what was recorded
        timing.recorder = if (on) timing.recorder ?: io.github.artcraftandroid.pdfcraft.engine.TimingRecorder() else null
    }

    /** Share the recorded open timings as a text file; only stage names, times and counts. */
    fun shareOpenTimings() {
        val recorder = io.github.artcraftandroid.pdfcraft.engine.OpenTiming.recorder
        val lines = recorder?.snapshot().orEmpty()
        if (lines.isEmpty()) {
            tell(text(R.string.home_open_timings_empty))
            return
        }
        handOut {
            val app = getApplication<Application>()
            val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val mem = android.app.ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
            val facts = listOf(
                "app" to "$appVersion (${io.github.artcraftandroid.pdfcraft.ui.home.installedVersionCode(app)})",
                "engine" to io.github.artcraftandroid.pdfcraft.core.engineVersion(),
                "device" to "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}",
                "android" to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
                "memory_class_mb" to "${am?.memoryClass ?: 0} (large ${am?.largeMemoryClass ?: 0})",
                "device_ram_mb" to "${mem.totalMem / (1024 * 1024)}",
                "max_heap_mb" to "${Runtime.getRuntime().maxMemory() / (1024 * 1024)}",
            )
            val report = io.github.artcraftandroid.pdfcraft.engine.timingReport(facts, lines, recorder?.droppedCount() ?: 0)
            val file = withContext(Dispatchers.IO) {
                File(File(app.cacheDir, "share").apply { mkdirs() }, "pdfcraft-open-timings.txt").apply { writeText(report) }
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            sendIntent(listOf(uri), "text/plain")
                .putExtra(Intent.EXTRA_SUBJECT, text(R.string.home_open_timings_subject))
                .putExtra(Intent.EXTRA_TEXT, report)
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

    // ---- Comments ----------------------------------------------------------------------------

    /** A note being written (Comment tool, or Add comment on a selection). */
    var notePrompt by mutableStateOf<NotePrompt?>(null)

    /** Free text being typed (Text tool). */
    var textPrompt by mutableStateOf<TextPrompt?>(null)

    /** A reply being written to a comment. */
    var replyPrompt by mutableStateOf<ReplyPrompt?>(null)

    private val author: String? get() = toolPrefs.author.ifBlank { null }

    /** A pending selection-menu annotation, rendered until the native refresh settles. */
    var pendingMarkupPreview by mutableStateOf<PendingMarkupPreview?>(null)
        private set

    /** Same annotation path as the drawing tool, with a temporary visual bridge. */
    fun addSelectedMarkup(
        doc: OpenDocument, markup: TextMarkup, page: Int, rects: List<FloatArray>,
        color: androidx.compose.ui.graphics.Color, opacity: Float,
    ) {
        if (document !== doc || !doc.info.allowsAnnotation || rects.isEmpty()) return
        val preview = PendingMarkupPreview(doc, page, rects.map { it.copyOf() }, markup, color, opacity)
        pendingMarkupPreview = preview
        addMarkup(doc, markup, page, rects, color, opacity, onSettled = {
            if (pendingMarkupPreview === preview) pendingMarkupPreview = null
        })
    }

    /** Mark text with [markup]: [rects] (points, one per line) on [page]. */
    fun addMarkup(doc: OpenDocument, markup: TextMarkup, page: Int, rects: List<FloatArray>, color: androidx.compose.ui.graphics.Color, opacity: Float, contents: String? = null, onSettled: (() -> Unit)? = null) {
        val quads = rects.map { r -> listOf(r[0], r[1], r[2], r[1], r[0], r[3], r[2], r[3]).map { it.toDouble() } }
        runTool(
            doc, "comment_add",
            mapOf(
                "page" to page + 1, "type" to markup.engineType, "quads" to quads,
                "color" to color.hex(), "opacity" to opacity.toDouble(), "author" to author, "contents" to contents,
            ),
            onSettled = onSettled,
        )
    }

    /** Post a note at [at] (or, for a selection, a highlight carrying the note). */
    fun postNote(prompt: NotePrompt, text: String) {
        notePrompt = null
        val doc = prompt.doc
        val sel = prompt.selection
        if (sel != null) {
            addMarkup(doc, TextMarkup.Highlight, sel.range.page.toInt(), sel.rects, toolPrefs.color(StyledTool.Highlight), toolPrefs.opacity(StyledTool.Highlight), text)
            doc.selection.clear()
            return
        }
        val at = prompt.at ?: return
        runTool(
            doc, "comment_add",
            mapOf(
                "page" to at.page + 1, "type" to "note", "at" to listOf(at.x.toDouble(), at.y.toDouble()),
                "contents" to text, "color" to toolPrefs.color(StyledTool.Note).hex(), "author" to author,
            ),
        )
    }

    /** Place typed text at the prompt's point, in the Text tool's colour and size. */
    fun postText(prompt: TextPrompt, text: String) {
        textPrompt = null
        if (text.isBlank()) return
        val at = prompt.at
        val size = toolPrefs.textSize
        val page = prompt.doc.info.pages.getOrNull(at.page) ?: return
        // as the reference: lines that wrap where the page ends become real line breaks, and the box
        // is as wide as its longest line (measured as the engine draws it, so it doesn't wrap again)
        if (prompt.callout) {
            // A callout's box sits beside the point it marks, so it may use most of the page width.
            val (lines, width, height) = FreeTextLayout.layout(text, size, (page.width * 0.6f).coerceAtLeast(size * 4))
            val box = io.github.artcraftandroid.pdfcraft.ui.viewer.calloutBox(at.x, at.y, width + 4f, height + 4f, page.width, page.height)
            if (box != null) {
                runTool(
                    prompt.doc, "comment_add",
                    mapOf(
                        "page" to at.page + 1, "type" to "callout", "rect" to box.map { it.toDouble() },
                        "to" to listOf(at.x.toDouble(), at.y.toDouble()), "contents" to lines.joinToString("\n"),
                        "color" to toolPrefs.color(StyledTool.FreeText).hex(), "font_size" to size.toDouble(), "author" to author,
                    ),
                )
                return
            }
        }
        val (lines, width, height) = FreeTextLayout.layout(text, size, (page.width - at.x - 6f).coerceAtLeast(size * 4))
        val wrapped = lines.joinToString("\n")
        val x1 = (at.x + width).coerceAtMost(page.width)
        val y1 = (at.y + height).coerceAtMost(page.height)
        runTool(
            prompt.doc, "comment_add",
            mapOf(
                "page" to at.page + 1, "type" to "textbox", "rect" to listOf(at.x, at.y, x1, y1).map { it.toDouble() },
                "contents" to wrapped, "color" to toolPrefs.color(StyledTool.FreeText).hex(), "font_size" to size.toDouble(), "author" to author,
            ),
        )
    }

    // ---- Voice comments ----------------------------------------------------------------------

    /** A voice comment being recorded where the page was tapped. */
    var voicePrompt by mutableStateOf<VoicePrompt?>(null)

    /** Asks the activity for the microphone. */
    val micRequests = Channel<Unit>(Channel.CONFLATED)

    /** The microphone was refused before: explain, and offer the app's settings. */
    var micRationale by mutableStateOf(false)
    private var pendingVoice: VoicePrompt? = null

    /** Record a voice comment at [at], once the microphone is allowed. */
    fun startVoice(doc: OpenDocument, at: PagePoint) {
        val app = getApplication<Application>()
        if (app.cacheDir.usableSpace < 60L * 1024 * 1024) {
            tell(text(R.string.msg_voice_no_storage))
            return
        }
        val prompt = VoicePrompt(doc, at)
        if (androidx.core.content.ContextCompat.checkSelfPermission(app, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            voicePrompt = prompt
        } else {
            pendingVoice = prompt
            micRequests.trySend(Unit)
        }
    }

    /** The answer to the microphone request. */
    fun onMicPermission(granted: Boolean) {
        val p = pendingVoice
        pendingVoice = null
        if (granted && p != null) voicePrompt = p else if (!granted) tell(text(R.string.msg_voice_no_mic))
    }

    fun dismissMicRationale(openSettings: Boolean) {
        micRationale = false
        pendingVoice = null
        if (openSettings) {
            val app = getApplication<Application>()
            intents.trySend(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** A cache file for a new recording. */
    fun voiceFile(): File = File(getApplication<Application>().cacheDir, "voice-${System.nanoTime()}.wav")

    /** Post [file] (a WAV recording) as a voice comment at the prompt's point. */
    fun postVoice(prompt: VoicePrompt, file: File) {
        voicePrompt = null
        val at = prompt.at
        runTool(
            prompt.doc, "comment_add",
            mapOf(
                "page" to at.page + 1, "type" to "sound", "at" to listOf(at.x.toDouble(), at.y.toDouble()),
                "path" to file.absolutePath, "color" to toolPrefs.color(StyledTool.Note).hex(), "author" to author,
            ),
            // The recording is in the PDF or was refused: either way the cache copy goes.
            onSettled = { file.delete() },
        )
    }

    /** Voice comment [c]'s recording as a WAV file to play, or null when it can't be read. */
    suspend fun voiceRecording(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo): File? {
        val eng = engine ?: return null
        // one file per playback: the player deletes it once it has loaded it (#105)
        val f = File(getApplication<Application>().cacheDir, "voice-play-${System.nanoTime()}.wav")
        return withContext(eng.commands) {
            runCatching { eng.session.saveSound(doc.id, c.page, c.index, f.absolutePath); f }.onFailure { f.delete() }.getOrNull()
        }
    }

    fun postReply(prompt: ReplyPrompt, text: String) {
        replyPrompt = null
        if (text.isBlank()) return
        runTool(prompt.doc, "comment_reply", mapOf("page" to prompt.comment.page.toInt() + 1, "index" to prompt.comment.index.toInt() + 1, "text" to text, "author" to author))
    }

    /** Put [text] on the clipboard (Android 13 and later confirm it themselves). */
    fun copyText(text: String) {
        val cm = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
        // the parameter `text` hides the text() function here: called by its full name
        cm?.setPrimaryClip(android.content.ClipData.newPlainText(io.github.artcraftandroid.pdfcraft.text(R.string.msg_clip_comment), text))
        if (android.os.Build.VERSION.SDK_INT < 33) tell(io.github.artcraftandroid.pdfcraft.text(R.string.msg_copied))
    }

    /** Copy the selected text (when the document allows copying). */
    fun copySelection(doc: OpenDocument) {
        if (!doc.info.allowsCopying) {
            tell(text(R.string.msg_copy_not_allowed))
            return
        }
        viewModelScope.launch {
            val text = doc.selection.text() ?: return@launch
            val cm = getApplication<Application>().getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText(io.github.artcraftandroid.pdfcraft.text(R.string.msg_clip_pdf_text), text))
            doc.selection.clear()
            if (android.os.Build.VERSION.SDK_INT < 33) tell(io.github.artcraftandroid.pdfcraft.text(R.string.msg_copied))
        }
    }

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

    /** A finished job's green banner over the viewer's bar, with Undo. */
    var success by mutableStateOf<SuccessBanner?>(null)

    /** A long-running job's message (a progress dialog over everything), or null. */
    var busy by mutableStateOf<String?>(null)

    // ---- Fill & Sign -------------------------------------------------------------------------

    /** The element a tap places. */
    var fillElement by mutableStateOf(io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement.Text)

    /** Fill & Sign text waiting to be typed. */
    var fillTextPrompt by mutableStateOf<TextPrompt?>(null)

    val signatures = io.github.artcraftandroid.pdfcraft.ui.viewer.Signatures(app.filesDir)

    /** Bumped when a saved signature is cleared or replaced, so the panel redraws. */
    var signaturesChanged by mutableStateOf(0)

    /** A signature or initials being made. */
    var signaturePad by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.SignaturePad?>(null)

    /** A signature taken from an image (Image tab), before Done. */
    var importedSignature by mutableStateOf<android.graphics.Bitmap?>(null)

    /** Place [element] at [at] on its page. */
    fun placeFill(doc: OpenDocument, element: io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement, at: PagePoint, text: String? = null) {
        if (element == FillElement.Box) {
            // a rounded box around what was tapped, in the Fill & Sign colour: drawn as one ink stroke
            val r = toolPrefs.fillTextSize
            runTool(doc, "comment_add", mapOf(
                "page" to at.page + 1, "type" to "ink",
                "strokes" to listOf(roundedBox(at.x, at.y, r * 4f, r * 2f, r * 0.6f)),
                "color" to androidx.compose.ui.graphics.Color(toolPrefs.fillColor).hex(), "width" to 1.5, "author" to author,
            ))
            return
        }
        val args = mutableMapOf<String, Any?>(
            "page" to at.page + 1, "type" to element.engineType, "at" to listOf(at.x.toDouble(), at.y.toDouble()), "author" to author,
        )
        when (element) {
            FillElement.Text -> args["text"] = text ?: return
            FillElement.Signature, FillElement.Initials -> args["path"] = signatures.fileFor(element == FillElement.Initials).absolutePath
            else -> Unit
        }
        runTool(doc, "fill_sign_add", args)
    }

    /** The outline of a [w] x [h] box with corners of radius [r], centred on ([cx], [cy]), as stroke points. */
    private fun roundedBox(cx: Float, cy: Float, w: Float, h: Float, r: Float): List<List<Double>> {
        val pts = mutableListOf<List<Double>>()
        val l = cx - w / 2f
        val t = cy - h / 2f
        val corners = listOf(Triple(l + w - r, t + r, -90f), Triple(l + w - r, t + h - r, 0f), Triple(l + r, t + h - r, 90f), Triple(l + r, t + r, 180f))
        for ((x, y, start) in corners) {
            for (k in 0..6) {
                val a = Math.toRadians((start + k * 15f).toDouble())
                pts += listOf(x + r * Math.cos(a), y + r * Math.sin(a))
            }
        }
        pts += pts.first()
        return pts
    }

    fun postFillText(prompt: TextPrompt, text: String) {
        fillTextPrompt = null
        if (text.isBlank()) return
        placeFill(prompt.doc, io.github.artcraftandroid.pdfcraft.ui.viewer.FillElement.Text, prompt.at, text)
    }

    /** A signature or initials was made: save it (if asked) and place it where the page was tapped. */
    fun finishSignature(pad: io.github.artcraftandroid.pdfcraft.ui.viewer.SignaturePad, bitmap: android.graphics.Bitmap, save: Boolean) {
        signaturePad = null
        importedSignature = null
        viewModelScope.launch {
            val target = if (save) signatures.fileFor(pad.initials) else File(getApplication<Application>().cacheDir, "sign-${System.nanoTime()}.png")
            withContext(Dispatchers.IO) { target.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
            signaturesChanged++
                fillElement = if (pad.initials) FillElement.Initials else FillElement.Signature
            val doc = pad.doc
            val at = pad.at
            if (doc != null && at != null) {
                runTool(doc, "fill_sign_add", mapOf(
                    "page" to at.page + 1, "type" to if (pad.initials) "initials" else "signature",
                    "at" to listOf(at.x.toDouble(), at.y.toDouble()), "path" to target.absolutePath, "author" to author,
                ))
            } else {
                tell(text(if (pad.initials) R.string.msg_place_initials else R.string.msg_place_signature))
            }
        }
    }

    /** An image chosen for a signature: made transparent where it is near white, then shown on the pad. */
    fun importSignatureImage(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            importedSignature = withContext(Dispatchers.IO) {
                runCatching {
                    val src = getApplication<Application>().contentResolver.openInputStream(uri).use { android.graphics.BitmapFactory.decodeStream(it) } ?: return@runCatching null
                    val scale = minOf(1f, 1200f / maxOf(src.width, src.height))
                    val bmp = android.graphics.Bitmap.createScaledBitmap(src, (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
                        .copy(android.graphics.Bitmap.Config.ARGB_8888, true)
                    val px = IntArray(bmp.width * bmp.height)
                    bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    for (i in px.indices) {
                        val p = px[i]
                        val l = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
                        // paper becomes transparent, ink keeps its strength
                        val a = ((235 - l) * 255 / 120).coerceIn(0, 255)
                        px[i] = (a shl 24) or (p and 0xFFFFFF)
                    }
                    bmp.setPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                    bmp
                }.getOrNull()
            }
            if (importedSignature == null) tell(text(R.string.msg_image_unreadable))
        }
    }

    // ---- Digital signatures: signing with a digital ID, and what a document's signatures say ----

    /** The digital IDs the reader added or made (lazy: the engine's start may need them first). */
    val digitalIds by lazy { io.github.artcraftandroid.pdfcraft.engine.DigitalIds(app.filesDir) }

    /** The signers the reader chose to trust, given to the engine when it starts. */
    val trustedCerts by lazy { io.github.artcraftandroid.pdfcraft.engine.TrustedCertificates(app.filesDir) }

    /** The document a digital signature's box is being drawn on (Sign ▸ Sign with a digital ID). */
    var certPlacing by mutableStateOf<OpenDocument?>(null)

    /** Sign with a digital ID, open for where the signature goes. */
    var certSign by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget?>(null)

    /** Why the last signing failed (a wrong password), shown on the Sign screen. */
    var certSignError by mutableStateOf<String?>(null)

    /** The digital ID chosen on the Sign screen. */
    var chosenId by mutableStateOf<io.github.artcraftandroid.pdfcraft.engine.DigitalId?>(null)

    /** A picked digital ID file, waiting for its password. */
    var idImport by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.IdImport?>(null)
        private set

    /** Create a digital ID is open. */
    var idCreate by mutableStateOf(false)

    /** An empty signature field was tapped: draw a signature there, or sign it with a digital ID. */
    var signFieldChoice by mutableStateOf<Pair<OpenDocument, io.github.artcraftandroid.pdfcraft.core.WidgetInfo>?>(null)

    /** Draw the box for a new digital signature on [doc], in Fill & Sign. */
    fun startCertPlacing(doc: OpenDocument) {
        if (!doc.info.readOnlyReason.isNullOrEmpty()) {
            tell(text(R.string.msg_sign_read_only))
            return
        }
        if (doc.controller.tool != ViewerTool.FillSign) enterTool(doc, ViewerTool.FillSign)
        doc.controller.signPanel = false
        selectedFill = null
        certPlacing = doc
    }

    /** Sign with no box on the page (an invisible signature, on the current page): on to choosing the ID. */
    fun signInvisibly(doc: OpenDocument) {
        certPlacing = null
        openCertSign(io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget(doc, null, doc.viewer.currentPage, null))
    }

    /**
     * View signed version: the revision signature [s] signs, saved as its own file and opened as a
     * separate document, so what was signed can be seen without the later changes.
     */
    fun openSignedVersion(doc: OpenDocument, s: io.github.artcraftandroid.pdfcraft.ui.viewer.SignatureEntry) {
        val eng = engine ?: return
        viewModelScope.launch {
            var copy: Int? = null
            try {
                val list = eng.tool("doc_revisions", mapOf("doc" to doc.id)).optJSONArray("revisions")
                val revisions = (0 until (list?.length() ?: 0)).mapNotNull { i ->
                    val o = list?.optJSONObject(i) ?: return@mapNotNull null
                    val by = o.optJSONArray("signed_by")
                    o.optInt("revision") to (0 until (by?.length() ?: 0)).map { by!!.optString(it) }
                }
                val revision = io.github.artcraftandroid.pdfcraft.ui.viewer.signedRevision(s.field, revisions)
                if (revision == null) {
                    tell(text(R.string.sign_no_signed_version))
                    return@launch
                }
                copy = eng.tool("doc_open_revision", mapOf("doc" to doc.id, "revision" to revision)).optInt("doc")
                val dir = File(getApplication<Application>().cacheDir, "attachments/${System.nanoTime()}").apply { mkdirs() }
                val out = File(dir, DocumentFiles.safeName(text(R.string.sign_signed_version_name, doc.name.removeSuffix(".pdf"), s.field) + ".pdf"))
                eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true))
                open(Uri.fromFile(out))
            } catch (e: Exception) {
                tell(e.userMessage)
            } finally {
                copy?.let { c -> runCatching { eng.tool("doc_close", mapOf("doc" to c, "discard_changes" to true)) } }
            }
        }
    }

    /** The box was drawn: on to choosing the ID. */
    fun placeCert(doc: OpenDocument, page: Int, rect: FloatArray) {
        certPlacing = null
        openCertSign(io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget(doc, null, page, rect))
    }

    fun signFieldWithId(doc: OpenDocument, w: io.github.artcraftandroid.pdfcraft.core.WidgetInfo) {
        openCertSign(io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget(doc, w.field, w.page.toInt(), null))
    }

    /** A drawn (Fill & Sign) signature in signature field [w], as a tap on it did before digital IDs. */
    fun drawSignatureInField(doc: OpenDocument, w: io.github.artcraftandroid.pdfcraft.core.WidgetInfo) {
        signaturePad = io.github.artcraftandroid.pdfcraft.ui.viewer.SignaturePad(false, doc, PagePoint(w.page.toInt(), (w.rect[0] + w.rect[2]) / 2f - 40f, (w.rect[1] + w.rect[3]) / 2f - 10f))
    }

    private fun openCertSign(t: io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget) {
        certSignError = null
        val ids = digitalIds.all
        if (chosenId !in ids) chosenId = ids.lastOrNull()
        certSign = t
    }

    fun pickDigitalId() = pickFiles(FilePickKind.DigitalId)

    /** The digital ID file's password: add it (when the password opens it), or give up (null). */
    fun answerIdImport(password: String?) {
        val imp = idImport ?: return
        idImport = null
        if (password == null) {
            imp.file.delete()
            return
        }
        viewModelScope.launch {
            try {
                val info = withContext(Dispatchers.IO) { io.github.artcraftandroid.pdfcraft.core.digitalIdInfo(imp.file.absolutePath, password) }
                val problem = info.problem
                if (problem != null) {
                    imp.file.delete()
                    tell(text(R.string.msg_id_cannot_sign, problem))
                    return@launch
                }
                val id = withContext(Dispatchers.IO) { digitalIds.add(imp.file, info) }
                imp.file.delete()
                chosenId = id
                tell(text(R.string.msg_id_added, id.name))
            } catch (e: MobileException.WrongPassword) {
                idImport = io.github.artcraftandroid.pdfcraft.ui.viewer.IdImport(imp.file, imp.name, wrong = true)
            } catch (e: MobileException) {
                imp.file.delete()
                tell(text(R.string.msg_id_unusable, e.userMessage))
            }
        }
    }

    /** Make a self-signed digital ID (2048-bit RSA, valid 5 years) and choose it. */
    fun createDigitalId(name: String, email: String, organization: String, password: String) {
        val eng = engine ?: return
        viewModelScope.launch {
            busy = text(R.string.msg_busy_creating_id)
            val tmp = File(getApplication<Application>().cacheDir, "new-id-${System.nanoTime()}.p12")
            try {
                eng.tool("sign_id_create", mapOf(
                    "name" to name.trim(), "email" to email.trim().ifEmpty { null }, "organization" to organization.trim().ifEmpty { null },
                    "password" to password, "path" to tmp.absolutePath,
                ))
                val info = withContext(Dispatchers.IO) { io.github.artcraftandroid.pdfcraft.core.digitalIdInfo(tmp.absolutePath, password) }
                chosenId = withContext(Dispatchers.IO) { digitalIds.add(tmp, info) }
                idCreate = false
                tell(text(R.string.msg_id_created))
            } catch (e: MobileException) {
                tell(toolMessage(e, "sign_id_create"))
            } finally {
                tmp.delete()
                busy = null
            }
        }
    }

    fun removeDigitalId(id: io.github.artcraftandroid.pdfcraft.engine.DigitalId) {
        digitalIds.remove(id)
        if (chosenId == id) chosenId = digitalIds.all.lastOrNull()
    }

    /**
     * Sign [t]'s document with [id]: the engine signs it as it is now (with any changes not yet
     * saved) and the signed file replaces the working copy, then goes back where it came from.
     */
    fun signWithId(
        t: io.github.artcraftandroid.pdfcraft.ui.viewer.CertTarget,
        id: io.github.artcraftandroid.pdfcraft.engine.DigitalId,
        password: String,
        look: io.github.artcraftandroid.pdfcraft.ui.viewer.CertLook,
        reason: String,
        location: String,
        options: io.github.artcraftandroid.pdfcraft.ui.viewer.CertOptions = io.github.artcraftandroid.pdfcraft.ui.viewer.CertOptions(),
    ) {
        val eng = engine ?: return
        val doc = t.doc
        viewModelScope.launch {
            busy = text(R.string.msg_busy_signing)
            try {
                val args = mutableMapOf<String, Any?>(
                    "doc" to doc.id, "id" to id.file.absolutePath, "password" to password, "out" to doc.file.absolutePath,
                    "reason" to reason.trim().ifEmpty { null }, "location" to location.trim().ifEmpty { null },
                )
                args += io.github.artcraftandroid.pdfcraft.ui.viewer.certPlacementArgs(t.field, t.page, t.rect, options)
                // an invisible signature draws nothing, so it takes no picture
                if ("field" in args || "rect" in args) when (look) {
                    io.github.artcraftandroid.pdfcraft.ui.viewer.CertLook.Name -> Unit
                    io.github.artcraftandroid.pdfcraft.ui.viewer.CertLook.Typed -> args["graphic"] = mapOf("text" to id.name)
                    io.github.artcraftandroid.pdfcraft.ui.viewer.CertLook.Drawn -> args["graphic"] = mapOf("image" to signatures.signature.absolutePath)
                }
                // The signer rewrites the working copy: never while a save streams it to the
                // provider, and only after the marker that keeps it on a crash (#103).
                saveMutex.withLock {
                    val src = doc.source
                    val wasPending = src != null && withContext(Dispatchers.IO) { files.hasPendingWriteBack(src) }
                    val before = doc.file.length() to doc.file.lastModified()
                    markBeforeWorkingCopyChanges(doc)
                    try {
                        eng.tool("sign_document", args)
                    } catch (e: Exception) {
                        // A refused signature (wrong password) leaves the copy as it was: drop the
                        // marker this attempt added. A changed copy keeps it and is saved later.
                        val unchanged = (doc.file.length() to doc.file.lastModified()) == before
                        if (src != null && !wasPending && unchanged) {
                            withContext(Dispatchers.IO) { runCatching { files.clearPendingWriteBack(src) } }
                        } else {
                            doc.notWrittenBack = true
                        }
                        throw e
                    }
                    doc.notWrittenBack = true
                }
                certSign = null
                certSignError = null
                if (doc.controller.tool == ViewerTool.FillSign) leaveTool(doc)
                refresh(doc)
                // the working copy is signed; the original gets it now, or a copy does
                if (save(doc, quiet = true)) {
                    tell(text(R.string.msg_signed_saved))
                } else {
                    tell(text(R.string.msg_signed_not_saved, doc.name), text(R.string.common_save_copy)) { saveCopyAs(doc) }
                }
            } catch (e: MobileException) {
                val m = toolMessage(e, "sign_document")
                certSignError = if (m.contains("password is incorrect", ignoreCase = true)) text(R.string.common_wrong_password) else m
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // e.g. the recovery marker could not be written: nothing was signed
                tell(e.userMessage)
            } finally {
                busy = null
            }
        }
    }

    /** A tool's refusal without the tool's name in front, as a sentence. */
    private fun toolMessage(e: Throwable, tool: String): String =
        e.userMessage.removePrefix("$tool: ").replaceFirstChar { it.uppercase() }.let { if (it.lastOrNull()?.isLetterOrDigit() == true) "$it." else it }

    /** What the document's signatures say (only asked for when it has signatures or signature fields). */
    private suspend fun loadSignatures(eng: Engine, doc: OpenDocument) {
        if (!doc.info.signed && doc.info.fields == 0u) {
            doc.signatures = emptyList()
            return
        }
        val generation = doc.info.generation
        val result = try { io.github.artcraftandroid.pdfcraft.ui.viewer.parseSignatures(eng.tool("sign_list", mapOf("doc" to doc.id))) }
        catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { return }
        if (document === doc && generation == doc.info.generation) doc.signatures = result
    }

    /** On opening a signed document: whether its signatures hold, with the details a tap away. */
    private fun announceSignatures(doc: OpenDocument) {
        val signed = doc.signatures.filter { it.signed }
        if (signed.isEmpty()) return
        val one = signed.singleOrNull()
        // the local `text` hides the text() function here: called by its full name
        val text = when {
            signed.any { it.status == "invalid" } -> io.github.artcraftandroid.pdfcraft.text(if (one != null) R.string.msg_sig_invalid else R.string.msg_sig_some_invalid)
            signed.all { it.status == "valid" } -> if (one != null) {
                one.signer?.let { io.github.artcraftandroid.pdfcraft.text(R.string.msg_sig_valid, it) } ?: io.github.artcraftandroid.pdfcraft.text(R.string.msg_sig_valid_unknown)
            } else {
                plural(R.plurals.msg_sig_all_valid, signed.size, signed.size)
            }
            one != null -> one.signer?.let { io.github.artcraftandroid.pdfcraft.text(R.string.msg_sig_unverified, it) } ?: io.github.artcraftandroid.pdfcraft.text(R.string.msg_sig_unverified_unknown)
            else -> io.github.artcraftandroid.pdfcraft.text(R.string.msg_sig_some_unverified)
        }
        tell(text, io.github.artcraftandroid.pdfcraft.text(R.string.msg_sig_details)) { doc.controller.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.Signatures }
    }

    /** Show signature [s] on its page, flashing its box. */
    fun showSignature(doc: OpenDocument, s: io.github.artcraftandroid.pdfcraft.ui.viewer.SignatureEntry) {
        val page = s.page ?: return
        val rect = s.rect ?: return
        doc.viewer.goToPage(page, (rect[1] - 60f).coerceAtLeast(0f))
        viewModelScope.launch {
            delay(250)
            doc.linkFlash = LinkFlash(page, rect)
            delay(600)
            doc.linkFlash = null
        }
    }

    /** Trust the certificate that made signature [s], on this device: signatures by it then validate. */
    fun trustSigner(doc: OpenDocument, s: io.github.artcraftandroid.pdfcraft.ui.viewer.SignatureEntry) {
        val eng = engine ?: return
        val sha = s.cert?.sha256 ?: return
        viewModelScope.launch {
            try {
                val der = eng.command { signerCertificate(doc.id, s.field) } ?: return@launch
                withContext(Dispatchers.IO) { trustedCerts.add(der, sha) }
                applyTrust(eng)
                loadSignatures(eng, doc)
                tell(s.signer?.let { text(R.string.msg_signer_trusted, it) } ?: text(R.string.msg_signer_trusted_unknown))
            } catch (e: MobileException) {
                tell(e.userMessage)
            }
        }
    }

    fun untrustSigner(doc: OpenDocument, s: io.github.artcraftandroid.pdfcraft.ui.viewer.SignatureEntry) {
        val eng = engine ?: return
        val sha = s.cert?.sha256 ?: return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { trustedCerts.remove(sha) }
            runCatching { applyTrust(eng) }
            loadSignatures(eng, doc)
        }
    }

    /** Give the engine the trusted signers' certificates (it revalidates open documents). */
    private suspend fun applyTrust(eng: Engine) {
        val paths = withContext(Dispatchers.IO) { trustedCerts.files().map { it.absolutePath } }
        eng.tool("sign_trust", mapOf("clear" to true, "paths" to paths))
    }

    // ---- Edit PDF ----------------------------------------------------------------------------

    var editMode by mutableStateOf(io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.Select)

    /** Each page's paragraphs (0-based page), read when Edit PDF shows the page. */
    val paragraphs = androidx.compose.runtime.mutableStateMapOf<Int, List<io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph>>()
    private var paragraphsGen = -1L

    /** The paragraph tapped once (outlined), and the one open in the editor. */
    var editSelection by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph?>(null)
    var editing by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph?>(null)

    suspend fun loadParagraphs(doc: OpenDocument, page: Int) {
        val eng = engine ?: return
        if (paragraphsGen != doc.info.generation.toLong()) {
            paragraphs.clear()
            paragraphsGen = doc.info.generation.toLong()
        }
        if (paragraphs.containsKey(page)) return
        val r = runCatching { eng.tool("text_paragraphs", mapOf("doc" to doc.id, "page" to page + 1)) }.getOrNull() ?: return
        val arr = r.optJSONArray("paragraphs") ?: return
        paragraphs[page] = (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val rect = o.optJSONArray("rect") ?: return@mapNotNull null
            val font = o.optString("font")
            io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph(
                page = page, number = o.optInt("paragraph"), text = o.optString("text"),
                box = FloatArray(4) { k -> rect.optDouble(k).toFloat() }, font = font, size = o.optDouble("size", 12.0).toFloat(),
                bold = if (o.has("bold")) o.optBoolean("bold") else font.contains("Bold", true) || font.contains("Black", true) || font.contains("Heavy", true),
                italic = if (o.has("italic")) o.optBoolean("italic") else font.contains("Italic", true) || font.contains("Oblique", true),
                color = o.optString("color").takeIf { it.startsWith("#") },
            )
        }
        editSelection?.takeIf { it.page == page }?.let { sel -> editSelection = paragraphs[page]?.firstOrNull { it.number == sel.number } }
        editing?.takeIf { it.page == page }?.let { sel -> editing = paragraphs[page]?.firstOrNull { it.number == sel.number } }
    }

    /** A tap in Edit PDF: finish typing, place new text, or select the paragraph or picture under it. */
    fun editTap(doc: OpenDocument, at: PagePoint?) {
        editing?.let { commitInlineEdit(doc) }
        editImage = null
        imageEdit.crop = null
        if (at == null) {
            editSelection = null
            return
        }
        if (editMode == io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.AddText) {
            editMode = io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.Select
            editSelection = null
            addTextPrompt = TextPrompt(doc, at)
            return
        }
        if (editMode == io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.AddImage) {
            editMode = io.github.artcraftandroid.pdfcraft.ui.viewer.EditMode.Select
            editSelection = null
            addImageAt(doc, at)
            return
        }
        val hit = paragraphs[at.page].orEmpty().firstOrNull { p -> at.x in (p.box[0] - 4)..(p.box[2] + 4) && at.y in (p.box[1] - 4)..(p.box[3] + 4) }
        editSelection = hit
        if (hit == null) selectImageAt(doc, at)
    }

    /** The picture selected in Edit PDF. */
    var editImage by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.EditImage?>(null)

    /** The picture tools' state: a crop being framed, the Rotate dialog (#161). */
    val imageEdit = io.github.artcraftandroid.pdfcraft.ui.viewer.ImageEditState()

    /** Formatting the engine doesn't report back, kept per paragraph (page to number) while editing. */
    val editFont = androidx.compose.runtime.mutableStateMapOf<Pair<Int, Int>, String>()
    val editAlign = androidx.compose.runtime.mutableStateMapOf<Pair<Int, Int>, String>()
    val editUnderline = androidx.compose.runtime.mutableStateMapOf<Pair<Int, Int>, Boolean>()

    /** Typing in a paragraph: whether it opened with everything selected, and the text so far. */
    var editSelectAll = false
    var editDraft: String? = null
    var editTextSelection: Pair<Int, Int>? = null
    var editComposing by mutableStateOf(false)

    fun startInlineEdit(para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph, selectAll: Boolean) {
        editSelectAll = selectAll
        editDraft = para.text
        editComposing = false
        editTextSelection = if (selectAll) 0 to para.text.length else para.text.length to para.text.length
        editSelection = para
        editing = para
    }

    /** Write the paragraph being typed in into the page (when it changed) and stop typing. */
    fun commitInlineEdit(doc: OpenDocument) {
        val para = editing ?: return
        val text = editDraft ?: para.text
        editing = null
        editDraft = null
        editTextSelection = null
        editComposing = false
        if (text != para.text) runTool(doc, "text_edit", mapOf("page" to para.page + 1, "paragraph" to para.number, "text" to text))
    }

    private fun paraArgs(para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph) = mapOf<String, Any?>("page" to para.page + 1, "paragraph" to para.number)

    /** Make [para] a list of [style] (or plain again), with any typing so far. */
    fun listParagraph(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph, style: io.github.artcraftandroid.pdfcraft.ui.viewer.ListStyle) {
        val typing = editing != null && editing?.number == para.number && editing?.page == para.page
        val base = if (typing) editDraft ?: para.text else para.text
        val text = io.github.artcraftandroid.pdfcraft.ui.viewer.ListStyle.apply(base, style)
        if (text == base) return
        if (typing) editDraft = text
        runTool(doc, "text_edit", paraArgs(para) + ("text" to text))
    }

    /** A formatting change to [para], applied to the page at once (with any typing so far). */
    fun styleParagraph(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph, style: Map<String, Any?>) {
        val typed = editDraft?.takeIf { editing != null && it != para.text }
        runTool(doc, "text_edit", paraArgs(para) + style + (if (typed != null) mapOf("text" to typed) else emptyMap()))
    }

    /** Unicode script toggle for selected text or a paragraph; no uncommitted draft is lost. */
    fun scriptParagraph(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph, script: String) {
        val typing = editing
        if (script !in setOf("superscript", "subscript") || (typing != null &&
            (typing.number != para.number || typing.page != para.page || editDraft != para.text || editComposing)) || document !== doc ||
            !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
            tell(text(R.string.edit_script_unavailable))
            return
        }
        val range = if (typing != null) {
            val (anchor, focus) = editTextSelection ?: (0 to 0)
            try { io.github.artcraftandroid.pdfcraft.engine.nativeUtf8Range(para.text, anchor, focus) }
            catch (_: IllegalArgumentException) { tell(text(R.string.edit_script_unavailable)); return }
        } else null
        // Only an unchanged draft reaches here, so dismissing the inline editor loses no typing.
        editing = null
        editDraft = null
        editTextSelection = null
        editComposing = false
        val generation = doc.info.generation
        job(text(R.string.edit_script_applying)) { eng ->
            if (document !== doc || editing != null || !doc.writable) {
                tell(text(R.string.edit_script_unavailable))
                return@job
            }
            val raw = eng.command {
                val current = document(doc.id)
                require(current.generation == generation && !current.signed && current.readOnlyReason == null) {
                    text(R.string.edit_script_unavailable)
                }
                if (range != null) {
                    val listed = JSONObject(execute("text_paragraphs", Engine.toJson(
                        mapOf("doc" to doc.id, "page" to para.page + 1)).toString())).getJSONArray("paragraphs")
                    val row = (0 until listed.length()).map { listed.getJSONObject(it) }
                        .firstOrNull { it.getInt("paragraph") == para.number }
                    // A range is valid only against the exact native editor text, not display
                    // text normalized differently by PDF extraction or a stale local draft.
                    require(row != null && row.optString("editor_text", row.optString("text")) == para.text) {
                        text(R.string.edit_script_unavailable)
                    }
                }
                execute("text_script", Engine.toJson(paraArgs(para) + mapOf("doc" to doc.id, "script" to script) +
                    (range?.let { mapOf("range" to it) } ?: emptyMap())).toString())
            }
            refresh(doc)
            val warnings = JSONObject(raw).optJSONArray("warnings")
            for (i in 0 until minOf(warnings?.length() ?: 0, 3)) tell(warnings!!.getString(i).take(500))
        }
    }

    /** Move [para] by ([dx], [dy]) points (right and down on screen). */
    fun moveParagraph(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph, dx: Float, dy: Float) {
        runTool(doc, "text_edit", paraArgs(para) + mapOf("dx" to dx.toDouble(), "dy" to -dy.toDouble()))
    }

    /** Rewrap [para] to [width] points, moving it [shift] points right first (dragged from the left). */
    fun resizeParagraph(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph, width: Float, shift: Float) {
        val args = paraArgs(para) + ("width" to width.toDouble()) + (if (shift != 0f) mapOf("dx" to shift.toDouble()) else emptyMap())
        runTool(doc, "text_edit", args)
    }

    fun deleteParagraph(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph) {
        editSelection = null
        editing = null
        runTool(doc, "text_edit", paraArgs(para) + ("text" to ""))
    }

    /** The page's colour just outside [para], for typing over it. */
    suspend fun paperColor(doc: OpenDocument, para: io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph): androidx.compose.ui.graphics.Color? {
        val eng = engine ?: return null
        val x = (para.box[0] - 4f).coerceAtLeast(0f).toInt().toUInt()
        val y = (para.box[1] - 4f).coerceAtLeast(0f).toInt().toUInt()
        val img = runCatching {
            withContext(eng.thumbnails) { eng.session.renderPage(doc.id, para.page.toUInt(), 1f, io.github.artcraftandroid.pdfcraft.core.TileRect(x, y, 2u, 2u)) }
        }.getOrNull() ?: return null
        val px = img.rgba
        if (px.size < 4) return null
        return androidx.compose.ui.graphics.Color(px[0].toInt() and 0xff, px[1].toInt() and 0xff, px[2].toInt() and 0xff)
    }

    private fun selectImageAt(doc: OpenDocument, at: PagePoint) {
        viewModelScope.launch { imageUnder(doc, at)?.let { editImage = it } }
    }

    /** The picture drawn under [at], topmost first, if any. */
    private suspend fun imageUnder(doc: OpenDocument, at: PagePoint): io.github.artcraftandroid.pdfcraft.ui.viewer.EditImage? {
        val eng = engine ?: return null
        val r = runCatching { eng.tool("page_images", mapOf("doc" to doc.id, "page" to at.page + 1)) }.getOrNull() ?: return null
        val arr = r.optJSONArray("images") ?: return null
        for (i in arr.length() - 1 downTo 0) {
            val o = arr.optJSONObject(i) ?: continue
            val rect = (o.optJSONArray("visible_rect") ?: o.optJSONArray("rect")) ?: continue
            val box = FloatArray(4) { k -> rect.optDouble(k).toFloat() }
            if (at.x in box[0]..box[2] && at.y in box[1]..box[3]) {
                return io.github.artcraftandroid.pdfcraft.ui.viewer.editImageFrom(at.page, o)
            }
        }
        return null
    }

    /** The menu a long press on an empty part of a page opens. */
    var pageMenu by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.PageMenu?>(null)

    /**
     * A long press where no word is: on a picture, Edit PDF with the picture selected (the
     * reference's picture menu leads to editing it); elsewhere, the page menu at that point.
     */
    fun longPressPage(doc: OpenDocument, at: PagePoint, screen: androidx.compose.ui.geometry.Offset) {
        val eng = engine ?: return
        viewModelScope.launch {
            val img = imageUnder(doc, at)
            if (img != null) {
                enterTool(doc, io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool.Edit)
                if (doc.controller.tool == io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool.Edit) editImage = img
                return@launch
            }
            val glyphs = runCatching { withContext(eng.commands) { eng.session.glyphCount(doc.id, at.page.toUInt()) } }.getOrDefault(1u)
            pageMenu = io.github.artcraftandroid.pdfcraft.ui.viewer.PageMenu(doc, at, screen, pageHasText = glyphs > 0u)
        }
    }

    /** Put [img] in [rect] (points): a move or a resize. */
    fun placeImage(doc: OpenDocument, img: io.github.artcraftandroid.pdfcraft.ui.viewer.EditImage, rect: FloatArray) {
        editImage = img.copy(rect = rect)
        runTool(doc, "image_edit", mapOf("page" to img.page + 1, "image" to img.number, "action" to "move", "rect" to rect.map { it.toDouble() }))
    }

    fun turnImage(doc: OpenDocument, img: io.github.artcraftandroid.pdfcraft.ui.viewer.EditImage, quarters: Int) {
        runTool(doc, "image_edit", mapOf("page" to img.page + 1, "image" to img.number, "action" to "rotate", "quarters" to quarters)) {
            // the turned picture keeps its centre; read its new box
            selectImageAt(doc, PagePoint(img.page, (img.rect[0] + img.rect[2]) / 2f, (img.rect[1] + img.rect[3]) / 2f))
        }
    }

    fun deleteImage(doc: OpenDocument, img: io.github.artcraftandroid.pdfcraft.ui.viewer.EditImage) {
        editImage = null
        runTool(doc, "image_edit", mapOf("page" to img.page + 1, "image" to img.number, "action" to "delete"))
    }

    /** New page text being typed in Edit PDF (Add text). */
    var addTextPrompt by mutableStateOf<TextPrompt?>(null)

    fun postAddText(prompt: TextPrompt, text: String) {
        addTextPrompt = null
        if (text.isBlank()) return
        runTool(prompt.doc, "page_add_text", mapOf(
            "page" to prompt.at.page + 1, "text" to text, "at" to listOf(prompt.at.x.toDouble(), prompt.at.y.toDouble()),
            "size" to 12.0, "font" to "helvetica",
        ))
    }

    /** Add image: the activity picks a picture, then [addImage] places it on the current page. */
    val imagePicks = Channel<OpenDocument>(Channel.BUFFERED)
    private var imageFor: OpenDocument? = null

    /** Where the picture being picked goes (centred there); null: the current page as before. */
    private var imageAt: PagePoint? = null

    fun pickEditImage(doc: OpenDocument) {
        imageFor = doc
        imagePicks.trySend(doc)
    }

    /** Add image at a tapped point (Edit PDF's Add image mode, or the page menu): first, from where. */
    fun addImageAt(doc: OpenDocument, at: PagePoint) {
        imageSource = doc to at
    }

    /** Asking where the picture comes from (photos or the camera), as the reference's menu does. */
    var imageSource by mutableStateOf<Pair<OpenDocument, PagePoint>?>(null)

    fun addImageFrom(camera: Boolean) {
        val (doc, at) = imageSource ?: return
        imageSource = null
        imageAt = at
        if (!camera) {
            pickEditImage(doc)
            return
        }
        imageFor = doc
        val dir = File(getApplication<Application>().cacheDir, "camera").apply { mkdirs() }
        val f = File(dir, "image-${System.nanoTime()}.jpg")
        shotFile = f
        imageShot = true
        cameraShots.trySend(f)
    }

    /** The camera's photo is for Add image, not a scan. */
    private var imageShot = false

    fun addImage(uri: Uri?) {
        val doc = imageFor ?: return
        imageFor = null
        if (uri == null) return
        viewModelScope.launch {
            try {
                val name = files.displayName(uri).removeSuffix(".pdf")
                val f = File(getApplication<Application>().cacheDir, "image-${System.nanoTime()}-${name}")
                withContext(Dispatchers.IO) {
                    getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                        requireNotNull(input) { text(R.string.msg_picture_unreadable) }
                        f.outputStream().use { input.copyTo(it) }
                    }
                }
                if (name.lowercase().endsWith(".gif")) {
                    tell(text(R.string.msg_gif_not_supported))
                    return@launch
                }
                val eng = engine ?: return@launch
                val at = imageAt
                imageAt = null
                val args = mutableMapOf<String, Any?>("doc" to doc.id, "page" to (at?.page ?: doc.viewer.currentPage) + 1, "path" to f.absolutePath)
                // centred on the tapped point, a third of the page wide, kept on the page
                val page = at?.let { doc.info.pages.getOrNull(it.page) }
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
                if (at != null && page != null && bounds.outWidth > 0 && bounds.outHeight > 0) {
                    var w = page.width / 3f
                    var h = w * bounds.outHeight / bounds.outWidth
                    if (h > page.height / 2f) {
                        h = page.height / 2f
                        w = h * bounds.outWidth / bounds.outHeight
                    }
                    val x0 = (at.x - w / 2f).coerceIn(0f, (page.width - w).coerceAtLeast(0f))
                    val y0 = (at.y - h / 2f).coerceIn(0f, (page.height - h).coerceAtLeast(0f))
                    args["rect"] = listOf(x0, y0, x0 + w, y0 + h).map { it.toDouble() }
                }
                eng.tool("page_add_image", args)
                refresh(doc)
            } catch (e: Exception) {
                tell(e.userMessage)
            }
        }
    }

    // ---- Forms -------------------------------------------------------------------------------

    /** The field being filled in. */
    var fieldEdit by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.FieldEdit?>(null)

    /** The top bar's Reset and arrows while a text field is filled. */
    var fieldActions by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.FieldActions?>(null)

    /**
     * A tap at [at]: fill the form field there, if any (a check box or radio button toggles at
     * once). Returns whether there was one.
     */
    fun tapField(doc: OpenDocument, at: PagePoint?): Boolean {
        if (at == null || doc.widgets.isEmpty()) return false
        val w = doc.widgets.firstOrNull { w ->
            w.page.toInt() == at.page && w.rect.size == 4 && at.x in w.rect[0]..w.rect[2] && at.y in w.rect[1]..w.rect[3]
        } ?: return false
        if (w.readOnly) return true
        if (!doc.info.allowsFormFilling) {
            tell(text(R.string.msg_tool_blocked_security))
            return true
        }
        when (w.kind) {
            "checkbox" -> fillField(doc, w.field, !w.on)
            "radio" -> w.export?.let { fillField(doc, w.field, it) }
            "text", "combo", "list" -> fieldEdit = io.github.artcraftandroid.pdfcraft.ui.viewer.FieldEdit(doc, w)
            // a signed field shows its signature's details; an empty one, a drawn or a digital signature
            "signature" -> if (doc.signatures.any { it.field == w.field && it.signed }) {
                doc.controller.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.Signatures
            } else {
                signFieldChoice = doc to w
            }
            "button" -> pressButton(doc, w)
            else -> return false
        }
        return true
    }

    /**
     * A form button: does what its release action says, as far as a reader does on a phone:
     * clear the form (or the fields it names), open a web address after asking, go to a page, or
     * run its script. Sending the form online isn't done.
     */
    private fun pressButton(doc: OpenDocument, w: io.github.artcraftandroid.pdfcraft.core.WidgetInfo) {
        val eng = engine ?: return
        viewModelScope.launch {
            val r = runCatching { eng.tool("form_actions", mapOf("doc" to doc.id, "field" to w.field)) }.getOrNull() ?: return@launch
            val acts = r.optJSONArray("actions") ?: return@launch
            val all = (0 until acts.length()).mapNotNull { acts.optJSONObject(it) }
            val act = all.firstOrNull { it.optString("trigger") == "mouse_up" } ?: all.firstOrNull() ?: return@launch
            when {
                act.has("reset") -> {
                    val names = act.optJSONArray("reset")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()
                    runTool(doc, "form_reset", if (names.isEmpty()) emptyMap() else mapOf("fields" to names)) { tell(text(R.string.msg_form_cleared)) }
                }
                act.has("url") -> linkPrompt = act.optString("url")
                act.has("page") -> doc.viewer.goToPage((act.optInt("page") - 1).coerceIn(0, doc.pageCount - 1))
                act.has("submit") -> tell(text(R.string.msg_button_submit))
                act.has("javascript") -> runTool(doc, "js_run", mapOf("field" to w.field, "script" to act.optString("javascript"))) { scriptOutput(doc, it) }
            }
        }
    }

    fun fillField(doc: OpenDocument, field: String, value: Any) {
        val eng = engine ?: return
        viewModelScope.launch {
            try {
                val r = eng.tool("form_fill", mapOf("doc" to doc.id, "values" to mapOf(field to value)))
                refresh(doc)
                scriptOutput(doc, r)
            } catch (e: MobileException) {
                // the field refused the value (its format, or its own script's check): said in a
                // dialog, in the form's own words
                showScriptAlert(e.userMessage)
            }
        }
    }

    /** What a form's scripts said (their alerts), shown one at a time. */
    val scriptAlerts = androidx.compose.runtime.mutableStateListOf<String>()

    private fun showScriptAlert(message: String) {
        // a script that alerts in a loop can't queue dialogs without end
        if (message.isNotBlank() && scriptAlerts.size < MAX_SCRIPT_ALERTS) scriptAlerts.add(message)
    }

    fun dismissScriptAlert() {
        if (scriptAlerts.isNotEmpty()) scriptAlerts.removeAt(0)
    }

    /**
     * What a form's scripts said and asked of the viewer, from a tool's result: alerts are shown, a
     * page is shown, a web address asks first and printing prints; sending the form online isn't done.
     */
    private fun scriptOutput(doc: OpenDocument, r: JSONObject) {
        // a script that failed: kept out of sight, as the reference keeps it, but in the log
        r.optString("error").takeIf { it.isNotEmpty() && it != "null" }?.let { android.util.Log.w("PdfCraft", "form script: $it") }
        r.optJSONArray("alerts")?.let { a -> for (i in 0 until a.length()) showScriptAlert(a.optString(i)) }
        val requests = r.optJSONArray("requests") ?: return
        for (i in 0 until requests.length()) {
            val q = requests.optJSONObject(i) ?: continue
            when {
                q.has("page") -> doc.viewer.goToPage((q.optInt("page") - 1).coerceIn(0, doc.pageCount - 1))
                q.has("url") -> linkPrompt = q.optString("url")
                q.has("print") -> print(doc)
                q.has("submit") -> tell(text(R.string.msg_button_submit))
            }
        }
    }

    /** Move to the next (or previous) fillable text or choice field in reading order. */
    fun nextField(doc: OpenDocument, from: io.github.artcraftandroid.pdfcraft.core.WidgetInfo, forward: Boolean) {
        viewModelScope.launch {
            loadAllWidgets(doc)
            if (document !== doc || !doc.widgetsComplete) return@launch
            val fillable = doc.widgets.filter { !it.readOnly && (it.kind == "text" || it.kind == "combo" || it.kind == "list") }
            val i = fillable.indexOfFirst { it.field == from.field && it.page == from.page && it.rect == from.rect }
            val next = fillable.getOrNull(if (forward) i + 1 else i - 1)
            fieldEdit = next?.let {
                if (it.page.toInt() != doc.viewer.currentPage) doc.viewer.goToPage(it.page.toInt(), (it.rect[1] - 60f).coerceAtLeast(0f))
                io.github.artcraftandroid.pdfcraft.ui.viewer.FieldEdit(doc, it)
            }
        }
    }

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
    private var shotFile: File? = null
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
        return ScanSession(shotFile?.path, retakeIndex, imageShot, scanName, scanPages.map { ScanSession.Page(it.file.path, it.corners, it.found) })
    }

    private fun restoreScan(text: String, session: ScanSession): kotlinx.coroutines.Job {
        scanRestoring = text
        shotFile = session.shot?.let(::File)
        retakeIndex = session.retake
        imageShot = session.forImage
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
            FilePickKind.DigitalId -> viewModelScope.launch {
                val uri = uris.first()
                val picked = withContext(Dispatchers.IO) { runCatching { copyIn(uri) }.getOrNull() }
                if (picked == null) tell(text(R.string.msg_that_file_unreadable))
                else idImport = io.github.artcraftandroid.pdfcraft.ui.viewer.IdImport(picked, picked.name, wrong = false)
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
    private fun job(message: String, block: suspend (Engine) -> Unit) {
        val eng = engine ?: return
        viewModelScope.launch {
            busy = message
            try {
                block(eng)
            } catch (e: Exception) {
                tell(e.userMessage)
            } finally {
                busy = null
            }
        }
    }

    /** Put [file] into Downloads as [name] and say so, with Open. */
    private suspend fun deliver(file: File, name: String, mime: String = "application/pdf") {
        val uri = withContext(Dispatchers.IO) { files.saveToDownloads(file, name, mime).also { file.delete() } }
        val size = withContext(Dispatchers.IO) { files.size(uri) }
        val saved = size?.let { text(R.string.msg_saved_downloads_size, name, io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize(it)) } ?: text(R.string.msg_saved_downloads, name)
        if (mime == "application/pdf") tell(saved, text(R.string.common_open)) { open(uri) }
        else tell(saved, text(R.string.common_open)) {
            intents.trySend(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), null))
        }
    }

    private fun tempFile(ext: String) = File(getApplication<Application>().cacheDir, "out-${System.nanoTime()}.$ext")

    /** Compress PDF at [level] (0 high, 1 medium, 2 low compression). */
    fun compress(doc: OpenDocument, level: Int) = job(text(R.string.msg_busy_compressing)) { eng ->
        val (ppi, above, quality) = when (level) {
            0 -> Triple(72, 108, 40)
            2 -> Triple(300, 450, 80)
            else -> Triple(150, 225, 60)
        }
        val images = mapOf("downsample" to true, "ppi" to ppi, "above_ppi" to above, "compression" to "jpeg", "quality" to quality)
        val out = tempFile("pdf")
        eng.tool("doc_optimize", mapOf(
            "doc" to doc.id, "path" to out.absolutePath, "color" to images, "gray" to images,
            "discard_thumbnails" to true, "remove_unreferenced_dests" to true,
        ))
        toolScreen = null
        if (out.length() >= doc.info.fileSize.toLong()) {
            // as the reference: nothing is saved when the file can't get any smaller
            out.delete()
            tell(text(R.string.msg_compress_no_gain))
            return@job
        }
        deliver(out, "${doc.title}_compressed.pdf")
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
    fun protect(doc: OpenDocument, password: String) = job(text(R.string.msg_busy_setting_password)) { eng ->
        save(doc)
        val out = tempFile("pdf")
        val copy = eng.tool("doc_open", mapOf("path" to doc.file.absolutePath, "password" to doc.password)).optLong("doc")
        try {
            eng.tool("doc_protect", mapOf("doc" to copy, "open_password" to password))
            eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true))
        } finally {
            runCatching { eng.tool("doc_close", mapOf("doc" to copy, "discard_changes" to true)) }
        }
        toolScreen = null
        deliver(out, "${doc.title}_protected.pdf")
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
        eng.tool("doc_export_office", mapOf("doc" to doc.id, "path" to out.absolutePath))
        toolScreen = null
        val mime = when (ext) {
            "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            "rtf" -> "application/rtf"
            "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            else -> "text/html"
        }
        deliver(out, "${doc.title}.$ext", mime)
    }

    /** Every page of [doc] as an image (200 dpi) in Pictures/PdfCraft. */
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

    /** Pictures of [pages] (0-based) at 200 dpi, saved to Pictures/PdfCraft or handed to the share sheet. */
    fun writeImages(doc: OpenDocument, ext: String, pages: List<Int>, share: Boolean) = job(text(R.string.msg_busy_exporting_images)) { eng ->
        val mime = if (ext == "png") "image/png" else "image/jpeg"
        val made = mutableListOf<Pair<File, String>>()
        for ((n, i) in pages.withIndex()) {
            busy = text(R.string.msg_busy_exporting_page, n + 1, pages.size)
            val img = withContext(eng.renders) { eng.session.renderPage(doc.id, i.toUInt(), 200f / 72f, null) }
            val out = tempFile(ext)
            withContext(Dispatchers.IO) {
                val bmp = img.toBitmap()
                // JPEG has no transparency: put the page on white
                val flat = if (ext == "png") bmp else android.graphics.Bitmap.createBitmap(bmp.width, bmp.height, android.graphics.Bitmap.Config.ARGB_8888).also {
                    val c = android.graphics.Canvas(it)
                    c.drawColor(android.graphics.Color.WHITE)
                    c.drawBitmap(bmp, 0f, 0f, null)
                }
                out.outputStream().use { flat.compress(if (ext == "png") android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            }
            made += out to "${doc.title}_${i + 1}.$ext"
        }
        imageExport = null
        organizing = false
        doc.controller.pagesOpen = false
        if (share) {
            val uris = try {
                withContext(Dispatchers.IO) { files.shareableAll(made) }
            } finally {
                made.forEach { it.first.delete() }
            }
            intents.trySend(Intent.createChooser(sendIntent(uris, mime), null))
        } else {
            withContext(Dispatchers.IO) {
                for ((f, name) in made) {
                    files.saveToPictures(f, name, mime)
                    f.delete()
                }
            }
            tell(plural(R.plurals.msg_images_saved, made.size, made.size))
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
        eng.tool("page_extract", mapOf("doc" to doc.id, "pages" to pages.map { it + 1 }, "out" to out.absolutePath))
        deliver(out, "Pages from ${doc.title}.pdf")
    }

    // ---- Notebooks ----------------------------------------------------------------------------

    /** Home's New notebook sheet is showing. */
    var newNotebookOpen by mutableStateOf(false)

    /** The pages the user saved as templates (file names), refreshed off the main thread. */
    var userTemplates by mutableStateOf<List<String>>(emptyList())
        private set

    fun refreshUserTemplates() {
        viewModelScope.launch { userTemplates = withContext(Dispatchers.IO) { notebookTemplates.list() } }
    }

    /** A one-page PDF of [page] in the cache, for inserting (the caller deletes it). */
    private suspend fun notebookPageFile(page: io.github.artcraftandroid.pdfcraft.engine.NotebookPage): File? = withContext(Dispatchers.IO) {
        when (page) {
            is io.github.artcraftandroid.pdfcraft.engine.NotebookPage.Builtin ->
                tempFile("pdf").apply { writeBytes(io.github.artcraftandroid.pdfcraft.engine.NotebookPdf.write(page.template)) }
            is io.github.artcraftandroid.pdfcraft.engine.NotebookPage.User ->
                notebookTemplates.file(page.fileName)?.let { src -> tempFile("pdf").also { src.copyTo(it, overwrite = true) } }
        }
    }

    /** Make a notebook of [page] called [title] in Downloads and open it in Draw mode. */
    fun createNotebook(page: io.github.artcraftandroid.pdfcraft.engine.NotebookPage, title: String) {
        newNotebookOpen = false
        viewModelScope.launch {
            busy = text(R.string.notebook_busy_creating)
            try {
                val out = notebookPageFile(page) ?: run { tell(text(R.string.notebook_template_missing)); return@launch }
                val stamp = java.text.SimpleDateFormat("yyyy-MM-dd HH.mm", java.util.Locale.US).format(java.util.Date())
                val name = DocumentFiles.safeName(title.trim().ifEmpty { text(R.string.notebook_default_name, stamp) })
                val uri = withContext(Dispatchers.IO) { files.saveToDownloads(out, name).also { out.delete() } }
                withContext(Dispatchers.IO) { notebooks.put(uri.toString(), page) }
                pendingTool = io.github.artcraftandroid.pdfcraft.ui.home.FileTool.Draw
                open(uri)
            } catch (e: Exception) {
                tell(e.userMessage)
            } finally {
                busy = null
            }
        }
    }

    /** What [doc]'s pages look like, if it was made as a notebook here. */
    suspend fun notebookPageOf(doc: OpenDocument): io.github.artcraftandroid.pdfcraft.engine.NotebookPage? {
        val key = doc.source?.toString() ?: return null
        return withContext(Dispatchers.IO) { notebooks.get(key) }
    }

    /**
     * Insert a page of [page] so it becomes page [at] (1-based; default: after the last), then
     * show it. One undo step removes it. Without a template the page is blank, sized like its
     * neighbour.
     */
    fun insertNotebookPage(doc: OpenDocument, page: io.github.artcraftandroid.pdfcraft.engine.NotebookPage?, at: Int = doc.pageCount + 1) {
        if (!doc.writable || !doc.info.allowsAnnotation) { tell(text(R.string.notebook_cannot_add_page)); return }
        viewModelScope.launch {
            val chosen = page ?: notebookPageOf(doc)
            val show = { doc.viewer.goToPage(at - 1) }
            if (chosen == null) {
                runTool(doc, "page_insert_blank", mapOf("at" to at)) { show() }
                return@launch
            }
            val file = notebookPageFile(chosen) ?: run { tell(text(R.string.notebook_template_missing)); return@launch }
            runTool(doc, "page_insert_file", mapOf("path" to file.absolutePath, "at" to at), onSettled = { file.delete() }) { show() }
        }
    }

    /** Save page [index] (0-based) of [doc] as a template called [title] for new notebooks and pages. */
    fun savePageAsTemplate(doc: OpenDocument, index: Int, title: String) = job(text(R.string.notebook_busy_saving_template)) { eng ->
        val out = withContext(Dispatchers.IO) { notebookTemplates.newFile(title) }
        try {
            eng.tool("page_extract", mapOf("doc" to doc.id, "pages" to listOf(index + 1), "out" to out.absolutePath))
        } catch (e: Exception) {
            out.delete()
            throw e
        }
        refreshUserTemplates()
        tell(text(R.string.notebook_template_saved, out.nameWithoutExtension))
    }

    fun deleteUserTemplate(name: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { notebookTemplates.delete(name) }
            refreshUserTemplates()
        }
    }

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
            val r = eng.tool("doc_split", args)
            val list = r.optJSONArray("files")
            val parts = (0 until (list?.length() ?: 0)).map { File(list!!.getJSONObject(it).getString("path")) }.filter { it.isFile }
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
                eng.tool("layer_set", mapOf("doc" to doc.id, "layer" to position, "visible" to visible))
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
                val saved = withContext(Dispatchers.IO) { files.saveAllToTree(tree, out.files, out.mime) }
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
        if (imageShot) {
            imageShot = false
            // after Android closed the app, the document the picture was for isn't open any more
            if (!ok || !f.isFile || f.length() == 0L || imageFor == null) {
                f.delete()
                imageFor = null
                imageAt = null
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
                addImage(Uri.fromFile(upright))
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
            val found = (engine ?: application.engine.await().getOrNull())?.let { eng -> runCatching { eng.tool("photo_find_document", mapOf("path" to f.absolutePath)) }.getOrNull() }
                ?.takeIf { it.optBoolean("found") }?.optJSONArray("corners")?.takeIf { it.length() == 8 }
                ?.let { a -> FloatArray(8) { a.optDouble(it).toFloat() } }
            val at = scanPages.indexOfFirst { it.file == f }
            if (found != null && at >= 0) scanPages[at] = scanPages[at].copy(corners = found, found = found)
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
        scanPages.forEach { it.file.delete() }
        scanPages.clear()
        scanName = ""
        insertScanAt = null
        toolScreen = null
    }

    /** Save the scanned pages as a PDF with [filter]'s look (or insert them where Organize asked). */
    fun saveScan(filter: ScanFilter) = job(text(R.string.msg_busy_saving_scan)) { eng ->
        val pages = scanPages.toList()
        val paths = pages.map { it.file.absolutePath }
        val created = eng.tool("doc_create", mapOf("from" to "images", "paths" to paths, "name" to "Scan"))
        val id = created.optLong("doc")
        try {
            // each page keeps the part inside its corners, straightened, with the look; a whole
            // photo in Original stays as it was taken
            val sizes = eng.tool("doc_info", mapOf("doc" to id)).optJSONArray("pages")
            for ((i, page) in pages.withIndex()) {
                if (page.corners.contentEquals(ScanPage.WHOLE) && filter == ScanFilter.Original) continue
                val size = sizes?.optJSONObject(i) ?: continue
                val (w, h) = size.optDouble("width") to size.optDouble("height")
                val corners = page.corners.mapIndexed { k, v -> v * if (k % 2 == 0) w else h }
                busy = text(R.string.msg_busy_cleaning_page, i + 1, pages.size)
                runCatching { eng.tool("page_enhance_camera", mapOf("doc" to id, "page" to i + 1, "corners" to corners, "mode" to filter.mode, "level" to filter.level)) }
            }
            // the scan's words become text, searchable and selectable, when text recognition is installed
            withContext(Dispatchers.IO) { runCatching { eng.prepareOcrModels(getApplication()) } }
            if (runCatching { eng.tool("ocr_status") }.getOrNull()?.optBoolean("available", false) == true) {
                busy = text(R.string.msg_busy_recognizing)
                runCatching { eng.tool("ocr_recognize", mapOf("doc" to id)) }
            }
            val out = tempFile("pdf")
            eng.tool("doc_save", mapOf("doc" to id, "path" to out.absolutePath, "full" to true))
            val target = insertScanAt
            insertScanAt = null
            val chosen = scanName.trim()
            scanPages.forEach { it.file.delete() }
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
    }

    /** What [uri]'s thumbnail is drawn from: the file itself, the app's working copy, or the document. */
    private fun thumbPathFor(uri: String): String? {
        val u = Uri.parse(uri)
        return when (u.scheme) {
            "file" -> u.path
            else -> files.existingCopy(u)?.absolutePath ?: uri
        }
    }

    fun recentRows(): List<io.github.artcraftandroid.pdfcraft.ui.home.FileRow> = recentList.map { r ->
        io.github.artcraftandroid.pdfcraft.ui.home.FileRow(r.uri, r.name, thumbPathFor(r.uri), locationOf(r.uri), r.opened, r.size, r.starred) { open(Uri.parse(r.uri)) }
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
    private var pendingTool: io.github.artcraftandroid.pdfcraft.ui.home.FileTool? = null

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

    // ---- Form auto-suggestions -----------------------------------------------------------------

    var formSuggestions by mutableStateOf(appPrefs.getBoolean("formSuggestions", true))
        private set

    fun updateFormSuggestions(on: Boolean) {
        formSuggestions = on
        appPrefs.edit().putBoolean("formSuggestions", on).apply()
    }

    /** Words typed into form fields before, newest first. */
    fun suggestionsFor(prefix: String): List<String> {
        if (!formSuggestions || prefix.isBlank()) return emptyList()
        val all = appPrefs.getString("suggestions", "").orEmpty().split('\n').filter { it.isNotBlank() }
        return all.filter { it.startsWith(prefix, ignoreCase = true) && !it.equals(prefix, ignoreCase = true) }.take(3)
    }

    fun rememberSuggestion(value: String) {
        if (!formSuggestions || value.isBlank() || value.length > 200) return
        val all = appPrefs.getString("suggestions", "").orEmpty().split('\n').filter { it.isNotBlank() && it != value }
        appPrefs.edit().putString("suggestions", (listOf(value) + all).take(200).joinToString("\n")).apply()
    }

    fun clearFormSuggestions() {
        appPrefs.edit().remove("suggestions").apply()
        tell(text(R.string.msg_suggestions_cleared))
    }

    // ---- Selecting a comment ------------------------------------------------------------------

    /** The comment tapped: its panel shows over the viewer. */
    var selectedComment by mutableStateOf<Pair<OpenDocument, io.github.artcraftandroid.pdfcraft.core.CommentInfo>?>(null)

    /** A tap at [at]: select the comment there (the top one), if any. */
    fun tapComment(doc: OpenDocument, at: PagePoint?): Boolean {
        if (at == null) return false
        val hit = doc.comments.lastOrNull { c ->
            c.page.toInt() == at.page && c.replyTo == null && c.state == null && c.rect.size == 4 &&
                at.x in (c.rect[0] - 4)..(c.rect[2] + 4) && at.y in (c.rect[1] - 4)..(c.rect[3] + 4)
        } ?: return false
        selectedComment = doc to hit
        return true
    }

    private fun commentArgs(c: io.github.artcraftandroid.pdfcraft.core.CommentInfo) = mapOf("page" to c.page.toInt() + 1, "index" to c.index.toInt() + 1)

    fun deleteComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo) {
        selectedComment = null
        runTool(doc, "comment_delete", commentArgs(c))
    }

    fun recolorComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, color: androidx.compose.ui.graphics.Color) {
        runTool(doc, "comment_edit", commentArgs(c) + ("color" to color.hex())) { reselect(doc, c) }
    }

    /** Change a comment's opacity or line width (one undo step each). */
    fun styleComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, style: Map<String, Any?>) {
        runTool(doc, "comment_edit", commentArgs(c) + style) { reselect(doc, c) }
    }

    fun editCommentText(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, text: String) {
        runTool(doc, "comment_edit", commentArgs(c) + ("contents" to text)) { reselect(doc, c) }
    }

    fun moveComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, dx: Float, dy: Float) {
        runTool(doc, "comment_edit", commentArgs(c) + ("move" to listOf(dx.toDouble(), dy.toDouble()))) { reselect(doc, c) }
    }

    /** Put a comment in a new rectangle (points, top-left origin): a resize. */
    fun resizeComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, rect: List<Float>) {
        runTool(doc, "comment_edit", commentArgs(c) + ("rect" to rect.map { it.toDouble() })) { reselect(doc, c) }
    }

    /** Move or resize a Fill & Sign element, keeping it selected. */
    fun moveFill(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, dx: Float, dy: Float) {
        runTool(doc, "comment_edit", commentArgs(c) + ("move" to listOf(dx.toDouble(), dy.toDouble()))) {
            selectedFill = doc.comments.firstOrNull { it.page == c.page && it.index == c.index }
        }
    }

    fun resizeFill(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, rect: List<Float>) {
        runTool(doc, "comment_edit", commentArgs(c) + ("rect" to rect.map { it.toDouble() })) {
            selectedFill = doc.comments.firstOrNull { it.page == c.page && it.index == c.index }
        }
    }

    /** After an edit the comment is read again (same page and position). */
    private fun reselect(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo) {
        val again = doc.comments.firstOrNull { it.page == c.page && it.index == c.index }
        selectedComment = again?.let { doc to it }
    }

    /** The Fill & Sign element tapped (its menu shows over it). */
    var selectedFill by mutableStateOf<io.github.artcraftandroid.pdfcraft.core.CommentInfo?>(null)

    /** Make a Fill & Sign element [factor] times bigger, about its top-left corner. */
    fun scaleFill(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, factor: Float) {
        if (c.rect.size < 4) return
        val w = (c.rect[2] - c.rect[0]) * factor
        val h = (c.rect[3] - c.rect[1]) * factor
        if (w < 3f || h < 3f || w > 2000f || h > 2000f) return
        val rect = listOf(c.rect[0], c.rect[1], c.rect[0] + w, c.rect[1] + h).map { it.toDouble() }
        runTool(doc, "comment_edit", commentArgs(c) + ("rect" to rect)) {
            selectedFill = doc.comments.firstOrNull { it.page == c.page && it.index == c.index }
        }
    }

    /**
     * Page [page]'s text reflowed into paragraphs from its text layer: lines are joined until one
     * ends a sentence well short of the line width, or a heading-like short line stands alone.
     */
    suspend fun reflowParagraphs(doc: OpenDocument, page: Int): List<io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph> {
        val eng = engine ?: return emptyList()
        val text = runCatching { eng.command { pagePlainText(doc.id, page.toUInt()) } }.getOrDefault("")
        val lines = text.lines().map { it.trim() }
        if (lines.none { it.isNotEmpty() }) return emptyList()
        val longest = lines.maxOf { it.length }.coerceAtLeast(1)
        val out = mutableListOf<io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph>()
        val current = StringBuilder()
        fun flush() {
            if (current.isNotBlank()) {
                out += io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph(page, out.size + 1, current.toString().trim(), floatArrayOf(0f, 0f, 0f, 0f), "", 12f, false, false, null)
            }
            current.clear()
        }
        for (line in lines) {
            if (line.isEmpty()) {
                flush()
                continue
            }
            // a hyphen at a line's end joins the word across it
            if (current.endsWith("-")) current.setLength(current.length - 1) else if (current.isNotEmpty()) current.append(' ')
            current.append(line)
            val short = line.length < longest * 0.6
            if (short && (line.last() in ".!?:" || line.length < longest * 0.35)) flush()
        }
        flush()
        return out
    }
}

/** The most files Create PDF converts at once. */
private const val MAX_CREATE_FILES = 12

/** A green banner saying a job worked, with the way to undo it. */
class SuccessBanner(val text: String, val undo: (() -> Unit)?)

/** A file for the activity to print; [deleteWhenDone]: a temporary copy (an unlocked one) the job deletes (#105). */
data class PrintJob(val file: File, val name: String, val deleteWhenDone: Boolean)
