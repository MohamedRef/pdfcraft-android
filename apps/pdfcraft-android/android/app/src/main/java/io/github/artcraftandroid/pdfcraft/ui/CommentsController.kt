package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.MobileException
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.SafBoundedCopy
import io.github.artcraftandroid.pdfcraft.engine.review
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.viewer.NotePrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.PagePoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.PendingMarkupPreview
import io.github.artcraftandroid.pdfcraft.ui.viewer.ReplyPrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.StyledTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextMarkup
import io.github.artcraftandroid.pdfcraft.ui.viewer.TextPrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.VoicePrompt
import io.github.artcraftandroid.pdfcraft.ui.viewer.hex
import io.github.artcraftandroid.pdfcraft.ui.viewer.markupArgs
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

class CommentsController internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    // ---- Comments ----------------------------------------------------------------------------

    /** A note being written (Comment tool, or Add comment on a selection). */
    var notePrompt by mutableStateOf<NotePrompt?>(null)

    /** Free text being typed (Text tool). */
    var textPrompt by mutableStateOf<TextPrompt?>(null)

    /** A reply being written to a comment. */
    var replyPrompt by mutableStateOf<ReplyPrompt?>(null)

    internal val author: String? get() = vm.toolPrefs.author.ifBlank { null }

    /** A pending selection-menu annotation, rendered until the native refresh settles. */
    var pendingMarkupPreview by mutableStateOf<PendingMarkupPreview?>(null)
        private set

    /** Same annotation path as the drawing tool, with a temporary visual bridge. */
    fun addSelectedMarkup(
        doc: OpenDocument, markup: TextMarkup, page: Int, rects: List<FloatArray>,
        color: androidx.compose.ui.graphics.Color, opacity: Float,
    ) {
        if (vm.document !== doc || !doc.info.allowsAnnotation || rects.isEmpty()) return
        val preview = PendingMarkupPreview(doc, page, rects.map { it.copyOf() }, markup, color, opacity)
        pendingMarkupPreview = preview
        addMarkup(doc, markup, page, rects, color, opacity, onSettled = {
            if (pendingMarkupPreview === preview) pendingMarkupPreview = null
        }, onDone = { r ->
            // the new markup stays in hand: recolour it, add a note to it or delete it (#197)
            val added = r.optJSONObject("comment")
            val index = added?.optInt("index", 0) ?: 0
            if (index > 0 && vm.document === doc) {
                val bounds = floatArrayOf(rects.minOf { it[0] }, rects.minOf { it[1] }, rects.maxOf { it[2] }, rects.maxOf { it[3] })
                markupFollowUp = io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupFollowUp(doc, page, index - 1, bounds, color)
            }
        })
    }

    /** The text markup just added from the selection menu, with its follow-up bar (#197). */
    var markupFollowUp by mutableStateOf<io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupFollowUp?>(null)

    private fun followUpArgs(f: io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupFollowUp) = mapOf("page" to f.page + 1, "index" to f.index + 1)

    /** Give the new markup another colour (the bar stays, showing it). */
    fun recolorFollowUp(f: io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupFollowUp, color: androidx.compose.ui.graphics.Color) {
        markupFollowUp = f.copy(color = color)
        io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.followUp(f.doc, "recolor")
        vm.runTool(f.doc, "comment_edit", followUpArgs(f) + ("color" to color.hex()))
    }

    /** Write a note on the new markup: its text, as a highlight carrying a note has. */
    fun noteFollowUp(f: io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupFollowUp) {
        markupFollowUp = null
        io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.followUp(f.doc, "note")
        notePrompt = NotePrompt(f.doc, null, edit = f)
    }

    fun deleteFollowUp(f: io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupFollowUp) {
        markupFollowUp = null
        io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.followUp(f.doc, "delete")
        vm.runTool(f.doc, "comment_delete", followUpArgs(f))
    }

    /**
     * Replace Text (#170): strike the selection through and suggest [replacement] for it, as
     * Acrobat's Replace Text does (a StrikeOut grouped with a Caret carrying the new text).
     */
    fun replaceSelection(doc: OpenDocument, page: Int, rects: List<FloatArray>, replacement: String) {
        if (vm.document !== doc || !doc.info.allowsAnnotation || rects.isEmpty() || replacement.isBlank()) return
        val quads = rects.map { r -> listOf(r[0], r[1], r[2], r[1], r[0], r[3], r[2], r[3]).map { it.toDouble() } }
        io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.textEdit(doc, "replace", rects.size)
        vm.runTool(doc, "comment_add", mapOf(
            "page" to page + 1, "type" to "replace", "quads" to quads, "contents" to replacement,
            "color" to vm.toolPrefs.color(StyledTool.Strikethrough).hex(), "author" to author,
        ))
    }

    /** Insert Text (#170): a caret after the selection's last character, carrying [inserted]. */
    fun insertAfterSelection(doc: OpenDocument, page: Int, rects: List<FloatArray>, inserted: String) {
        val last = rects.lastOrNull() ?: return
        if (vm.document !== doc || !doc.info.allowsAnnotation || inserted.isBlank()) return
        io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.textEdit(doc, "insert", rects.size)
        vm.runTool(doc, "comment_add", mapOf(
            "page" to page + 1, "type" to "caret", "at" to listOf(last[2].toDouble(), last[3].toDouble()), "contents" to inserted,
            "color" to vm.toolPrefs.color(StyledTool.Note).hex(), "author" to author,
        ))
    }

    /** Mark text with [markup]: [rects] (points, one per line) on [page]. */
    fun addMarkup(doc: OpenDocument, markup: TextMarkup, page: Int, rects: List<FloatArray>, color: androidx.compose.ui.graphics.Color, opacity: Float, contents: String? = null, onSettled: (() -> Unit)? = null, onDone: (JSONObject) -> Unit = {}) {
        vm.runTool(doc, "comment_add", markupArgs(markup, page, rects, color, opacity, author, contents), onSettled = onSettled, onDone = onDone)
    }

    /** Post a note at [at] (or, for a selection, a highlight carrying the note). */
    fun postNote(prompt: NotePrompt, text: String) {
        notePrompt = null
        val doc = prompt.doc
        prompt.edit?.let { f ->
            if (text.isNotBlank()) vm.runTool(doc, "comment_edit", followUpArgs(f) + ("contents" to text))
            return
        }
        prompt.replace?.let { sel ->
            replaceSelection(doc, sel.range.page.toInt(), sel.rects, text)
            doc.selection.clear()
            return
        }
        prompt.insert?.let { sel ->
            insertAfterSelection(doc, sel.range.page.toInt(), sel.rects, text)
            doc.selection.clear()
            return
        }
        val sel = prompt.selection
        if (sel != null) {
            addMarkup(doc, TextMarkup.Highlight, sel.range.page.toInt(), sel.rects, vm.toolPrefs.color(StyledTool.Highlight), vm.toolPrefs.opacity(StyledTool.Highlight), text)
            doc.selection.clear()
            return
        }
        val at = prompt.at ?: return
        vm.runTool(
            doc, "comment_add",
            mapOf(
                "page" to at.page + 1, "type" to "note", "at" to listOf(at.x.toDouble(), at.y.toDouble()),
                "contents" to text, "color" to vm.toolPrefs.color(StyledTool.Note).hex(), "author" to author,
            ),
        )
    }

    /** Place typed text at the prompt's point, in the Text tool's colour and size. */
    fun postText(prompt: TextPrompt, text: String) {
        textPrompt = null
        if (text.isBlank()) return
        val at = prompt.at
        val size = vm.toolPrefs.textSize
        val page = prompt.doc.info.pages.getOrNull(at.page) ?: return
        // as the reference: lines that wrap where the page ends become real line breaks, and the box
        // is as wide as its longest line (measured as the engine draws it, so it doesn't wrap again)
        if (prompt.callout) {
            // A callout's box sits beside the point it marks, so it may use most of the page width.
            val (lines, width, height) = FreeTextLayout.layout(text, size, (page.width * 0.6f).coerceAtLeast(size * 4))
            val box = io.github.artcraftandroid.pdfcraft.ui.viewer.calloutBox(at.x, at.y, width + 4f, height + 4f, page.width, page.height)
            if (box != null) {
                vm.runTool(
                    prompt.doc, "comment_add",
                    mapOf(
                        "page" to at.page + 1, "type" to "callout", "rect" to box.map { it.toDouble() },
                        "to" to listOf(at.x.toDouble(), at.y.toDouble()), "contents" to lines.joinToString("\n"),
                        "color" to vm.toolPrefs.color(StyledTool.FreeText).hex(), "font_size" to size.toDouble(), "author" to author,
                    ),
                )
                return
            }
        }
        val (lines, width, height) = FreeTextLayout.layout(text, size, (page.width - at.x - 6f).coerceAtLeast(size * 4))
        val wrapped = lines.joinToString("\n")
        val x1 = (at.x + width).coerceAtMost(page.width)
        val y1 = (at.y + height).coerceAtMost(page.height)
        vm.runTool(
            prompt.doc, "comment_add",
            mapOf(
                "page" to at.page + 1, "type" to "textbox", "rect" to listOf(at.x, at.y, x1, y1).map { it.toDouble() },
                "contents" to wrapped, "color" to vm.toolPrefs.color(StyledTool.FreeText).hex(), "font_size" to size.toDouble(), "author" to author,
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
        val app = app
        if (app.cacheDir.usableSpace < 60L * 1024 * 1024) {
            vm.tell(text(R.string.msg_voice_no_storage))
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
        vm.pendingLecture?.let { d ->
            vm.pendingLecture = null
            Diag.event(DiagArea.Study, "lecture_mic", "granted" to granted)
            if (granted) vm.startLecture(d) else vm.tell(text(R.string.msg_voice_no_mic))
            return
        }
        val p = pendingVoice
        pendingVoice = null
        if (granted && p != null) voicePrompt = p else if (!granted) vm.tell(text(R.string.msg_voice_no_mic))
    }

    fun dismissMicRationale(openSettings: Boolean) {
        micRationale = false
        pendingVoice = null
        if (openSettings) {
            val app = app
            vm.intents.trySend(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", app.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** A cache file for a new recording. */
    fun voiceFile(): File = File(app.cacheDir, "voice-${System.nanoTime()}.wav")

    /** Post [file] (a WAV recording) as a voice comment at the prompt's point. */
    fun postVoice(prompt: VoicePrompt, file: File) {
        voicePrompt = null
        val at = prompt.at
        vm.runTool(
            prompt.doc, "comment_add",
            mapOf(
                "page" to at.page + 1, "type" to "sound", "at" to listOf(at.x.toDouble(), at.y.toDouble()),
                "path" to file.absolutePath, "color" to vm.toolPrefs.color(StyledTool.Note).hex(), "author" to author,
            ),
            // The recording is in the PDF or was refused: either way the cache copy goes.
            onSettled = { file.delete() },
        )
    }

    /** Voice comment [c]'s recording as a WAV file to play, or null when it can't be read. */
    suspend fun voiceRecording(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo): File? {
        val eng = vm.engine ?: return null
        // one file per playback: the player deletes it once it has loaded it (#105)
        val f = File(app.cacheDir, "voice-play-${System.nanoTime()}.wav")
        return withContext(eng.commands) {
            runCatching { eng.session.saveSound(doc.id, c.page, c.index, f.absolutePath); f }.onFailure { f.delete() }.getOrNull()
        }
    }

    fun postReply(prompt: ReplyPrompt, text: String) {
        replyPrompt = null
        if (text.isBlank()) return
        vm.runTool(prompt.doc, "comment_reply", mapOf("page" to prompt.comment.page.toInt() + 1, "index" to prompt.comment.index.toInt() + 1, "text" to text, "author" to author))
    }

    /** Put [text] on the clipboard (Android 13 and later confirm it themselves). */
    fun copyText(text: String) {
        val cm = app.getSystemService(android.content.ClipboardManager::class.java)
        // the parameter `text` hides the text() function here: called by its full name
        cm?.setPrimaryClip(android.content.ClipData.newPlainText(io.github.artcraftandroid.pdfcraft.text(R.string.msg_clip_comment), text))
        if (android.os.Build.VERSION.SDK_INT < 33) vm.tell(io.github.artcraftandroid.pdfcraft.text(R.string.msg_copied))
    }

    /** Copy the selected text (when the document allows copying). */
    fun copySelection(doc: OpenDocument) {
        if (!doc.info.allowsCopying) {
            vm.tell(text(R.string.msg_copy_not_allowed))
            return
        }
        vm.viewModelScope.launch {
            val text = doc.selection.text() ?: return@launch
            val cm = app.getSystemService(android.content.ClipboardManager::class.java)
            cm?.setPrimaryClip(android.content.ClipData.newPlainText(io.github.artcraftandroid.pdfcraft.text(R.string.msg_clip_pdf_text), text))
            doc.selection.clear()
            if (android.os.Build.VERSION.SDK_INT < 33) vm.tell(io.github.artcraftandroid.pdfcraft.text(R.string.msg_copied))
        }
    }

    // ---- Selecting a comment ------------------------------------------------------------------

    /** The comment tapped: its panel shows over the viewer. */
    var selectedComment by mutableStateOf<Pair<OpenDocument, io.github.artcraftandroid.pdfcraft.core.CommentInfo>?>(null)

    /** A tap at [at]: select the comment there (the top one), if any. */
    fun tapComment(doc: OpenDocument, at: PagePoint?): Boolean {
        if (at == null) return false
        val hit = doc.comments.lastOrNull { c ->
            // A comment the PDF hides (Hidden/NoView) is not drawn, so it cannot be tapped (#115).
            c.page.toInt() == at.page && !c.hidden && c.replyTo == null && c.state == null && c.rect.size == 4 &&
                at.x in (c.rect[0] - 4)..(c.rect[2] + 4) && at.y in (c.rect[1] - 4)..(c.rect[3] + 4)
        } ?: return false
        selectedComment = doc to hit
        vm.lectureSeekToComment(doc, hit)
        return true
    }

    private fun commentArgs(c: io.github.artcraftandroid.pdfcraft.core.CommentInfo) = mapOf("page" to c.page.toInt() + 1, "index" to c.index.toInt() + 1)

    fun deleteComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo) {
        selectedComment = null
        vm.runTool(doc, "comment_delete", commentArgs(c))
    }

    // ---- Comments: review, sharing and import (#169, #158) ----------------------------------

    /** The saved author name (what Mine in the Comments list filters by), or null. */
    val commentAuthor: String? get() = author

    /**
     * Each comment's review status, by (0-based page, 0-based index), as the engine works it out
     * (the latest status reply, by reference). Empty when nobody set one.
     */
    suspend fun commentStatuses(doc: OpenDocument): Map<Pair<Int, Int>, String> {
        val eng = vm.engine ?: return emptyMap()
        if (doc.comments.none { it.state != null }) return emptyMap()
        val r = runCatching { eng.tool("comment_list", mapOf("doc" to doc.id)) }.getOrNull() ?: return emptyMap()
        return io.github.artcraftandroid.pdfcraft.ui.viewer.parseCommentStatuses(r)
    }

    /** Set comment [c]'s review status (`none`, `accepted`, `rejected`, `cancelled`, `completed`). */
    fun setCommentStatus(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, status: String) {
        vm.runTool(doc, "comment_set_status", commentArgs(c) + mapOf("status" to status, "author" to author)) { io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.commentStatus(doc, status) }
    }

    /** Lock or unlock comment [c]: a locked one can't be moved, restyled or deleted. */
    fun lockComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, locked: Boolean) {
        vm.runTool(doc, "comment_lock", commentArgs(c) + ("locked" to locked)) {
            io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.commentLock(doc, locked)
            vm.tell(text(if (locked) R.string.comments_locked_msg else R.string.comments_unlocked_msg))
        }
    }

    /**
     * The comments of [doc] as an XFDF file, handed to the share sheet ([share]) or saved into
     * Downloads: what a classmate imports into their copy of the same PDF.
     */
    fun exportComments(doc: OpenDocument, share: Boolean) {
        if (doc.comments.none { !it.fillSign } && doc.commentsComplete) {
            vm.tell(text(R.string.comments_export_none))
            return
        }
        vm.job(text(R.string.comments_exporting)) { eng ->
            val out = vm.tempFile("xfdf")
            try {
                val start = android.os.SystemClock.elapsedRealtime()
                eng.tool("doc_export_data", mapOf("doc" to doc.id, "path" to out.absolutePath, "what" to "comments"))
                io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.commentsExport(doc, share, out.length(), android.os.SystemClock.elapsedRealtime() - start)
                val name = "${doc.title}.xfdf"
                if (share) {
                    val uri = withContext(Dispatchers.IO) { vm.files.shareable(out, name) }
                    vm.intents.trySend(Intent.createChooser(vm.sendIntent(listOf(uri), XFDF_MIME).putExtra(Intent.EXTRA_SUBJECT, text(R.string.comments_share_subject, doc.title)), null))
                } else {
                    vm.deliver(out, name, XFDF_MIME)
                }
            } finally {
                out.delete()
            }
        }
    }

    /** Asks the activity for comments to import: XFDF, FDF or another PDF. */
    val commentImportPicks = Channel<Unit>(Channel.CONFLATED)
    private var commentImportTarget: OpenDocument? = null

    fun pickCommentsImport(doc: OpenDocument) {
        if (!canImportComments(doc)) return
        commentImportTarget = doc
        commentImportPicks.trySend(Unit)
    }

    private fun canImportComments(doc: OpenDocument): Boolean {
        if (vm.document !== doc || !doc.info.allowsAnnotation || doc.info.readOnlyReason != null) {
            vm.tell(text(R.string.comments_import_unavailable))
            return false
        }
        return true
    }

    /** The picker's answer (null when cancelled). */
    fun onCommentsPicked(uri: Uri?) {
        val doc = commentImportTarget
        commentImportTarget = null
        if (uri == null || doc == null) return
        importComments(doc, uri)
    }

    /**
     * Comments shared to the app (an `.xfdf` or `.fdf` from a messaging app) go into the document
     * open now; with none open, the reader is asked to open the PDF they belong to first.
     */
    fun receiveComments(uri: Uri) {
        val doc = vm.document
        if (doc == null) {
            vm.tell(text(R.string.comments_import_open_pdf_first))
            return
        }
        importComments(doc, uri)
    }

    /**
     * Import the comments in [uri] into [doc], each onto the page with the same number. A
     * comment already there under the same name is replaced, so the same file imported twice
     * doesn't add a second set. One undo step.
     */
    private fun importComments(doc: OpenDocument, uri: Uri) {
        if (!canImportComments(doc)) return
        vm.job(text(R.string.comments_importing)) { eng ->
            val name = withContext(Dispatchers.IO) { runCatching { vm.files.displayNameRaw(uri) }.getOrDefault("comments") }
            val ext = name.substringAfterLast('.', "").lowercase().takeIf { it in setOf("xfdf", "fdf", "pdf", "xml") } ?: "xfdf"
            val staged = vm.tempFile(ext)
            try {
                withContext(Dispatchers.IO) {
                    val stream = app.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException(text(R.string.msg_that_file_unreadable))
                    val ctx = currentCoroutineContext()
                    val limit = if (ext == "pdf") SafBoundedCopy.MAX_BOOKMARK_SOURCE_PDF_BYTES else MAX_COMMENT_FILE_BYTES
                    stream.use { input -> staged.outputStream().use { SafBoundedCopy.copy(input, it, limit, "Comments") { ctx.ensureActive() } } }
                }
                if (vm.document !== doc) return@job
                val before = commentCount(doc)
                val start = android.os.SystemClock.elapsedRealtime()
                eng.tool("doc_import_data", mapOf("doc" to doc.id, "path" to staged.absolutePath))
                vm.refresh(doc)
                val after = vm.comments(doc)?.count { it.replyTo == null && it.state == null && !it.fillSign }
                val added = if (after != null && before != null) after - before else null
                io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.commentsImport(doc, ext, staged.length(), added, android.os.SystemClock.elapsedRealtime() - start)
                vm.tell(
                    when {
                        added == null -> text(R.string.comments_imported)
                        added > 0 -> plural(R.plurals.comments_imported_count, added, added)
                        else -> text(R.string.comments_imported_none_new)
                    },
                )
            } finally {
                staged.delete()
            }
        }
    }

    private suspend fun commentCount(doc: OpenDocument): Int? = vm.comments(doc)?.count { it.replyTo == null && it.state == null && !it.fillSign }

    /** A PDF summarising every comment, sorted by [sort] (`page`, `author`, `date`, `type`), into Downloads with Open. */
    fun summarizeComments(doc: OpenDocument, sort: String) = vm.job(text(R.string.comments_summarizing)) { eng ->
        val out = vm.tempFile("pdf")
        try {
            val start = android.os.SystemClock.elapsedRealtime()
            eng.tool("comments_summarize", mapOf("doc" to doc.id, "sort" to sort, "out" to out.absolutePath, "open" to false))
            io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.commentsSummary(doc, sort, android.os.SystemClock.elapsedRealtime() - start)
            vm.deliver(out, text(R.string.comments_summary_file, doc.title), "application/pdf")
        } finally {
            out.delete()
        }
    }

    /**
     * Comments copied from one document to paste into another (#169): each page's XFDF, by its
     * 1-based page number. Kept in memory only, never on the system clipboard.
     */
    var copiedComments by mutableStateOf<Map<Int, String>>(emptyMap())
        private set

    /** Copy [list] (comments, not replies) with their replies, page by page. */
    fun copyComments(doc: OpenDocument, list: List<io.github.artcraftandroid.pdfcraft.core.CommentInfo>) {
        val eng = vm.engine ?: return
        val byPage = list.filter { it.replyTo == null && it.state == null && it.subtype != "Sound" && it.subtype != "FileAttachment" }
            .groupBy { it.page.toInt() + 1 }
        if (byPage.isEmpty()) {
            vm.tell(text(R.string.comments_copy_none))
            return
        }
        vm.viewModelScope.launch {
            try {
                val out = mutableMapOf<Int, String>()
                for ((page, cs) in byPage) {
                    val r = eng.tool("comments_copy", mapOf("doc" to doc.id, "comments" to cs.map { listOf(page, it.index.toInt() + 1) }))
                    val xfdf = r.optString("xfdf").ifEmpty { r.optString("text") }
                    if (xfdf.isNotEmpty()) out[page] = xfdf
                }
                copiedComments = out
                val n = byPage.values.sumOf { it.size }
                io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.commentsCopy(doc, n, out.size)
                vm.tell(plural(R.plurals.comments_copied, n, n))
            } catch (e: MobileException) {
                vm.tell(e.userMessage)
            }
        }
    }

    /** Paste the copied comments into [doc], each onto the page with the number it had (one undo step per page). */
    fun pasteComments(doc: OpenDocument) {
        val copied = copiedComments
        if (copied.isEmpty()) return
        if (!canImportComments(doc)) return
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            var skipped = 0
            try {
                for ((page, xfdf) in copied.toSortedMap()) {
                    if (page > doc.pageCount) { skipped++; continue }
                    eng.tool("comments_paste", mapOf("doc" to doc.id, "page" to page, "xfdf" to xfdf))
                }
            } catch (e: MobileException) {
                vm.tell(e.userMessage)
            } finally {
                vm.refresh(doc)
            }
            io.github.artcraftandroid.pdfcraft.ui.viewer.MarkupDiag.commentsPaste(doc, copied.size - skipped, skipped)
            vm.tell(if (skipped > 0) text(R.string.comments_pasted_some, skipped) else text(R.string.comments_pasted))
        }
    }

    fun recolorComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, color: androidx.compose.ui.graphics.Color) {
        vm.runTool(doc, "comment_edit", commentArgs(c) + ("color" to color.hex())) { reselect(doc, c) }
    }

    /** Change a comment's opacity or line width (one undo step each). */
    /** [reselect] false: the panel is closing or moving on, so the selection is left as it is. */
    fun styleComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, style: Map<String, Any?>, reselect: Boolean = true) {
        if (reselect) vm.runTool(doc, "comment_edit", commentArgs(c) + style) { reselect(doc, c) }
        else vm.runTool(doc, "comment_edit", commentArgs(c) + style)
    }

    fun editCommentText(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, text: String) {
        vm.runTool(doc, "comment_edit", commentArgs(c) + ("contents" to text)) { reselect(doc, c) }
    }

    fun moveComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, dx: Float, dy: Float) {
        vm.runTool(doc, "comment_edit", commentArgs(c) + ("move" to listOf(dx.toDouble(), dy.toDouble()))) { reselect(doc, c) }
    }

    /** Put a comment in a new rectangle (points, top-left origin): a resize. */
    fun resizeComment(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, rect: List<Float>) {
        vm.runTool(doc, "comment_edit", commentArgs(c) + ("rect" to rect.map { it.toDouble() })) { reselect(doc, c) }
    }

    /** Move or resize a Fill & Sign element, keeping it selected. */
    fun moveFill(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, dx: Float, dy: Float) {
        vm.runTool(doc, "comment_edit", commentArgs(c) + ("move" to listOf(dx.toDouble(), dy.toDouble()))) {
            selectedFill = doc.comments.firstOrNull { it.page == c.page && it.index == c.index }
        }
    }

    fun resizeFill(doc: OpenDocument, c: io.github.artcraftandroid.pdfcraft.core.CommentInfo, rect: List<Float>) {
        vm.runTool(doc, "comment_edit", commentArgs(c) + ("rect" to rect.map { it.toDouble() })) {
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
        vm.runTool(doc, "comment_edit", commentArgs(c) + ("rect" to rect)) {
            selectedFill = doc.comments.firstOrNull { it.page == c.page && it.index == c.index }
        }
    }

    /**
     * Page [page]'s text reflowed into paragraphs from its text layer: lines are joined until one
     * ends a sentence well short of the line width, or a heading-like short line stands alone.
     */
    suspend fun reflowParagraphs(doc: OpenDocument, page: Int): List<io.github.artcraftandroid.pdfcraft.ui.viewer.Paragraph> {
        val eng = vm.engine ?: return emptyList()
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
