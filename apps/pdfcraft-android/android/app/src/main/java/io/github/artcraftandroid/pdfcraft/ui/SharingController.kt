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
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextMarkup
import android.content.ClipData
import android.content.Intent
import androidx.compose.runtime.snapshotFlow
import io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.ReadAloud
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.first
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea

class SharingController internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    // ---- Opening links, sharing, presenting, keyboard, printing, copies, read aloud --------
    /** A web address waiting for "Open web page?". */
    var linkPrompt by mutableStateOf<String?>(null)
        internal set

    fun answerLink(open: Boolean) {
        val uri = linkPrompt ?: return
        linkPrompt = null
        if (open) vm.intents.trySend(Intent(Intent.ACTION_VIEW, Uri.parse(uri)))
    }

    /** Find ended: say so when nothing was found. */
    fun whenSearchDone(doc: OpenDocument) {
        vm.viewModelScope.launch {
            snapshotFlow { doc.search.running }.first { !it }
            if (doc.search.hits.isEmpty() && doc.search.query.isNotEmpty()) {
                val eng = vm.engine
                // pages spread over the whole document, not just its first few: a scan can start
                // with a typed cover, and a typed document can have scanned pages further on
                val sample = io.github.artcraftandroid.pdfcraft.ui.viewer.searchTextSample(doc.pageCount)
                val imagePages = if (eng == null) 0 else sample.count { p ->
                    runCatching { withContext(eng.commands) { eng.session.glyphCount(doc.id, p.toUInt()) } }.getOrDefault(1u) == 0u
                }
                when {
                    sample.isNotEmpty() && imagePages == sample.size ->
                        vm.tell(text(R.string.msg_search_image_only), text(R.string.common_tool_recognize_text)) { vm.recognizeText(doc) }
                    imagePages > 0 ->
                        vm.tell(text(R.string.msg_search_no_matches_some_images, doc.search.query), text(R.string.common_tool_recognize_text)) { vm.recognizeText(doc) }
                    else -> vm.tell(text(R.string.msg_search_no_matches, doc.search.query))
                }
            }
        }
    }

    /** The Share sheet's choice last used, marked next time (#205). */
    val lastShareChoice: io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice
        get() = io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice.of(vm.settings.appPrefs.getString("shareChoice", null))

    /**
     * Share [doc] as [choice] picks (#205): the PDF as it is, a flattened copy (comments and
     * fields baked into the pages), the page in view as its own PDF, or pictures of pages (picked
     * in the page grid). The subject is the document's title only.
     */
    fun share(doc: OpenDocument, choice: io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice = io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice.Pdf) {
        vm.settings.appPrefs.edit().putString("shareChoice", choice.id).apply()
        Diag.event(DiagArea.Share, "share", "doc" to doc.diagNumber, "choice" to choice.id, "page" to doc.viewer.currentPage + 1, "pages" to doc.pageCount)
        if (doc.controller.tool == ViewerTool.Draw) doc.commitTool?.invoke()
        if (choice == io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice.Images) {
            vm.tools.exportImages(doc, vm.tools.lastImageFormat)
            return
        }
        val eng = vm.engine ?: return
        val page = doc.viewer.currentPage
        handOut {
            // the ink is queued in the engine: reading the document back waits for it, so the save has it
            vm.refresh(doc)
            vm.save(doc)
            val (file, name) = when (choice) {
                io.github.artcraftandroid.pdfcraft.ui.viewer.ShareChoice.Flattened -> {
                    // from a second, temporary engine document: the open one keeps its comments and fields
                    val out = vm.tempFile("pdf")
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
                    val out = vm.tempFile("pdf")
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
                    val out = vm.tempFile("pdf")
                    eng.tool("page_extract", mapOf("doc" to doc.id, "pages" to listOf(page + 1), "out" to out.absolutePath))
                    out to text(R.string.share_name_page, doc.title, page + 1)
                }
                else -> doc.file to doc.name
            }
            Diag.event(DiagArea.Share, "share_file", "doc" to doc.diagNumber, "choice" to choice.id, "bytes" to file.length())
            val uri = try {
                withContext(Dispatchers.IO) { vm.files.shareable(file, name) }
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
    internal fun handOut(make: suspend () -> Intent) {
        vm.viewModelScope.launch {
            val intent = try {
                make()
            } catch (e: Exception) {
                vm.tell(e.userMessage)
                null
            } ?: return@launch
            vm.intents.trySend(Intent.createChooser(intent, null))
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
        if (ctl.tool != ViewerTool.None) vm.leaveTool(doc)
        vm.settings.readingMode = false
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
        val doc = vm.document ?: return false
        // a presentation clicker works even with the shortcuts turned off
        if (!vm.settings.keyboardShortcuts && !doc.controller.presenting) return false
        if (vm.tools.toolScreen != null) return false
        // tabs (#138): Ctrl+Tab, Ctrl+Shift+Tab and Ctrl+W, in Reading Mode too
        if (ctrl && !doc.controller.presenting) vm.tabs.key(keyCode, shift)?.let { return it }
        if (vm.settings.readingMode) return false
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
        if (!ctrl && drawing) io.github.artcraftandroid.pdfcraft.ui.viewer.presetForKey(keyCode)?.let { return vm.settings.toolPrefs.applyInkPreset(it) }
        val shortcut = io.github.artcraftandroid.pdfcraft.ui.viewer.viewerShortcut(keyCode, ctrl, shift) ?: return false
        io.github.artcraftandroid.pdfcraft.engine.Diag.event(io.github.artcraftandroid.pdfcraft.engine.DiagArea.Viewer, "shortcut", "doc" to doc.diagNumber, "action" to shortcut, "ctrl" to ctrl, "shift" to shift, "tool" to ctl.tool)
        fun tool(t: ViewerTool) { if (ctl.tool != t) vm.enterTool(doc, t) }
        fun draw(eraser: Boolean = false, lasso: Boolean = false, shape: io.github.artcraftandroid.pdfcraft.ui.viewer.InkShapeMode = io.github.artcraftandroid.pdfcraft.ui.viewer.InkShapeMode.Freehand) {
            tool(ViewerTool.Draw)
            if (ctl.tool != ViewerTool.Draw) return
            ctl.eraserMode = eraser
            ctl.lassoMode = lasso
            ctl.inkShapeMode = shape
        }
        when (shortcut) {
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Pen -> { draw(); vm.settings.toolPrefs.selectWritingPen() }
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
                vm.commenting.selectedComment?.first === doc -> vm.commenting.selectedComment = null
                ctl.sheet != io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.None -> ctl.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.None
                ctl.searching -> { doc.search.clear(); ctl.searching = false }
                ctl.tool != ViewerTool.None -> vm.leaveTool(doc)
                else -> return false
            }
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Undo -> vm.undo(doc)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Redo -> vm.redo(doc)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Find -> {
                doc.selection.clear()
                ctl.searching = true
                ctl.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.None
            }
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Bookmark -> vm.settings.toggleBookmark(doc)
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Delete -> {
                val c = vm.commenting.selectedComment?.takeIf { it.first === doc }?.second ?: return false
                vm.commenting.deleteComment(doc, c)
            }
            io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Thinner, io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Thicker -> {
                if (!drawing) return false
                val dir = if (shortcut == io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerShortcut.Thicker) 1 else -1
                vm.settings.toolPrefs.updateInkWidth(io.github.artcraftandroid.pdfcraft.ui.viewer.steppedInkWidth(vm.settings.toolPrefs.inkWidth, dir))
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
            vm.tell(text(R.string.msg_print_disabled))
            return
        }
        // The ink being drawn is printed too, as Share sends it (#79).
        if (doc.controller.tool == ViewerTool.Draw) doc.commitTool?.invoke()
        vm.viewModelScope.launch {
            // the ink is queued in the engine: reading the document back waits for it, so the save has it
            vm.refresh(doc)
            vm.save(doc)
            // the system's printing can't open a password-protected file: print an unlocked copy
            val prepared = if (doc.info.encrypted || clean) preparedCopy(doc, unlock = doc.info.encrypted, clean = clean) else null
            if (clean && prepared == null) {
                vm.tell(text(R.string.msg_clean_copy_failed))
                return@launch
            }
            // a copy, not the working file the app's pause may be saving (#79); either copy is
            // deleted when the print job finishes (the unlocked one has no password, #105)
            val copy = prepared ?: printSnapshot(doc)
            vm.prints.trySend(PrintJob(copy ?: doc.file, doc.name, deleteWhenDone = copy != null))
        }
    }

    /**
     * A copy of [doc]'s working file for the print framework (#79): it reads its file on the main
     * thread while the print screen is open, and the app's pause saves the working file then.
     */
    private suspend fun printSnapshot(doc: OpenDocument): File? = vm.saveMutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { vm.tempFile("pdf").also { out -> doc.file.copyTo(out, overwrite = true) } }.getOrNull()
        }
    }

    /**
     * A temporary copy of [doc]'s saved file, opened separately so the open document is untouched:
     * without its password ([unlock], for printing, #105) and without its comments and markups
     * ([clean], #155). Null when that can't be done.
     */
    private suspend fun preparedCopy(doc: OpenDocument, unlock: Boolean, clean: Boolean): File? {
        val eng = vm.engine ?: return null
        val out = File(app.cacheDir, "print-${System.nanoTime()}.pdf")
        val start = System.nanoTime()
        var removed = 0
        return runCatching {
            val copy = vm.saveMutex.withLock {
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
    internal sealed class CopyRequest {
        class Document(val doc: OpenDocument, val then: (() -> Unit)?) : CopyRequest()
        class Row(val row: io.github.artcraftandroid.pdfcraft.ui.home.FileRow) : CopyRequest()
    }

    /** The single Save a copy waiting for its picker (#104). */
    private val copyRequest = PendingRequest<CopyRequest>()

    internal fun startCopyRequest(request: CopyRequest, name: String) {
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
        if (request is CopyRequest.Document && request.then != null && vm.resolvingReadOnly) {
            vm.readOnlyPrompt = request.doc
            vm.resolvingReadOnly = false
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
            if (target != null) vm.viewModelScope.launch { removeOrphanCopy(target) }
            return
        }
        if (target == null) {
            // User cancelled Android's file picker: do not lose the unsaved recovery choice.
            abandonCopyRequest(request)
            return
        }
        when (request) {
            is CopyRequest.Row -> vm.home.copyRowTo(request.row, target)
            is CopyRequest.Document -> copyDocumentTo(request.doc, request.then, target)
        }
    }

    /** Delete [target], a file the picker created for a Save a copy that no longer exists, if it is still empty. */
    private suspend fun removeOrphanCopy(target: Uri) {
        val removed = withContext(Dispatchers.IO) {
            runCatching {
                vm.files.size(target) == 0L &&
                    android.provider.DocumentsContract.deleteDocument(app.contentResolver, target)
            }.getOrDefault(false)
        }
        vm.tell(text(if (removed) R.string.msg_copy_request_lost else R.string.msg_copy_request_lost_kept))
    }

    private fun copyDocumentTo(doc: OpenDocument, then: (() -> Unit)?, target: Uri) {
        vm.viewModelScope.launch {
            try {
                // The Save a copy picker, onPause and auto-save must never race over
                // the same local PDF; only the successful copy may clear the marker.
                vm.saveMutex.withLock {
                    if (doc.info.dirty) {
                        // The working copy now holds edits its original lacks: mark it first and
                        // keep it owed to the original, as any save does (#103).
                        vm.markBeforeWorkingCopyChanges(doc)
                        vm.engine?.tool("doc_save", mapOf("doc" to doc.id))
                        if (doc.source != null) doc.notWrittenBack = true
                        // a file edited in place has just been written
                        doc.source?.takeIf { it.scheme == "file" && File(requireNotNull(it.path)).canonicalPath == doc.file.canonicalPath }
                            ?.let { doc.wroteOriginal = true }
                    }
                    withContext(Dispatchers.IO) {
                        vm.files.writeBack(doc.file, target)
                        if (then != null) doc.source?.let { vm.files.clearPendingWriteBack(it) }
                    }
                    if (then != null) doc.notWrittenBack = false
                    vm.refresh(doc)
                }
                if (then != null) {
                    vm.tell(text(R.string.msg_saved_copy))
                    then()
                } else if (doc.source != null) {
                    continueInCopy(doc, target)
                } else {
                    doc.holdWriteBack = false
                    vm.tell(text(R.string.msg_saved_copy))
                }
            } catch (e: Exception) {
                doc.holdWriteBack = false
                Diag.failure(DiagArea.Save, "copy", e)
                if (then != null && vm.resolvingReadOnly) {
                    vm.readOnlyPrompt = doc
                    vm.resolvingReadOnly = false
                }
                vm.tell(e.userMessage)
            }
        }
    }

    /**
     * Save a copy is "save as" (#190): [target] now holds every change, so the viewer goes on in it
     * and [doc]'s original is left as it was opened, unless an earlier save of this visit already
     * wrote it (then the message says so).
     */
    private suspend fun continueInCopy(doc: OpenDocument, target: Uri) {
        val eng = vm.engine ?: return
        val src = doc.source ?: return
        var left = false
        vm.openMutex.withLock {
            if (vm.document !== doc) return@withLock
            vm.closeQuietly(eng, doc)
            // the working copy's changes are in the copy: the original keeps its own bytes
            withContext(Dispatchers.IO) {
                runCatching {
                    if (src.scheme == "content") vm.files.discardWorkingCopy(src) else { vm.files.clearPendingWriteBack(src); true }
                }.getOrDefault(false)
            }.let { discarded ->
                Diag.event(DiagArea.Save, "copy_continue", "doc" to doc.diagNumber, "wrote_original" to doc.wroteOriginal, "working_copy_discarded" to discarded, "scheme" to src.scheme)
                if (!discarded) vm.tell(text(R.string.discard_kept_recovery))
            }
            vm.document = null
            vm.refreshRecents()
            left = true
        }
        if (!left) return
        vm.open(target)
        val name = withContext(Dispatchers.IO) { runCatching { vm.files.displayName(target) }.getOrDefault("") }
        vm.tell(text(if (doc.wroteOriginal) R.string.msg_copy_continues_saved_before else R.string.msg_copy_continues, name))
    }

    /** Export PDF (to Word, RTF, Excel, PowerPoint, or images of its pages). */
    fun openExport(doc: OpenDocument) {
        vm.tools.toolScreen = ToolScreen.Export(doc)
    }

    /** Read Aloud: the player over the viewer. */
    var readAloud by mutableStateOf<ReadAloud?>(null)
        internal set

    /** The reading notification follows Read aloud: shown while it reads or is paused, gone when it stops. */
    private val readAloudNotice = vm.viewModelScope.launch {
        snapshotFlow { readAloud to (readAloud?.playing == true) }.collect { (ra, playing) ->
            val ctx = app
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
            vm.tell(text(R.string.msg_read_aloud_encrypted))
            return
        }
        readAloud?.stop()
        val eng = vm.engine ?: return
        val key = vm.settings.docKey(doc)
        val sel = if (fromSelection) doc.selection.selection?.range else null
        if (sel != null) {
            // from the selection's first word
            readAloud = ReadAloud(app, eng, doc, vm.viewModelScope, sel.page.toInt(), { readAloud = null }, key, startGlyph = sel.start)
            return
        }
        val saved = ReadAloud.savedPosition(app, key)?.takeIf { it.first in 0 until doc.pageCount }
        // a reading that went to the end starts over at the beginning, as the reference's does
        if (saved == ReadAloud.START_OVER) {
            readAloud = ReadAloud(app, eng, doc, vm.viewModelScope, 0, { readAloud = null }, key)
            return
        }
        if (saved != null) {
            readAloud = ReadAloud(app, eng, doc, vm.viewModelScope, saved.first, { readAloud = null }, key, resumeAt = saved.second to saved.third)
            vm.tell(text(R.string.msg_read_aloud_resumed), text(R.string.msg_read_aloud_start_over)) {
                ReadAloud.forgetPosition(app, key)
                readAloud?.stop()
                ReadAloud.forgetPosition(app, key)
                readAloud = ReadAloud(app, eng, doc, vm.viewModelScope, 0, { readAloud = null }, key)
            }
            return
        }
        readAloud = ReadAloud(app, eng, doc, vm.viewModelScope, doc.viewer.currentPage, { readAloud = null }, key)
    }

    /**
     * While Read aloud is on, a tap on a word moves the reading there, as the reference's does. False
     * when no word is under [at] (the tap then shows or hides the bars).
     */
    suspend fun readAloudAt(doc: OpenDocument, at: io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint, slopPoints: Float): Boolean {
        val current = readAloud ?: return false
        val eng = vm.engine ?: return false
        val word = withContext(eng.commands) { eng.session.wordAt(doc.id, at.page.toUInt(), at.x, at.y, slopPoints) } ?: return false
        if (readAloud !== current) return false
        current.stop()
        readAloud = ReadAloud(app, eng, doc, vm.viewModelScope, word.page.toInt(), { readAloud = null }, vm.settings.docKey(doc), startGlyph = word.start)
        return true
    }
}
