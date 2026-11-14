package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.content.Intent
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerTool
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import io.github.artcraftandroid.pdfcraft.core.MobileException
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import kotlinx.coroutines.NonCancellable

class DocumentToolsController internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    // ---- Organize: duplicate, replace, reverse, split, page labels (#165) ---------------------

    /** Copies of [pages] (0-based), placed right after the last of them; one undo step. */
    fun duplicatePages(doc: OpenDocument, pages: List<Int>) {
        if (pages.isEmpty()) return
        vm.runTool(doc, "page_duplicate", mapOf("pages" to pages.map { it + 1 })) {
            vm.tell(plural(R.plurals.msg_pages_duplicated, pages.size, pages.size))
        }
    }

    /** Reverse the order of [pages] (0-based) among their positions, or of every page when fewer than two are picked. */
    fun reversePages(doc: OpenDocument, pages: List<Int>) {
        val args = if (pages.size >= 2) mapOf("pages" to pages.map { it + 1 }) else emptyMap()
        vm.runTool(doc, "page_reverse", args) { vm.tell(text(R.string.msg_pages_reversed)) }
    }

    /** Label pages [from]..[to] (0-based, inclusive) as the viewer's page indicator then shows them. */
    fun labelPages(doc: OpenDocument, from: Int, to: Int, style: String, prefix: String, start: Int) {
        vm.runTool(doc, "page_number", mapOf(
            "from" to from + 1, "to" to to + 1, "style" to style, "prefix" to prefix, "start" to start.coerceAtLeast(1),
        )) { vm.tell(text(R.string.msg_pages_labelled)) }
    }

    /** Replace with: the pages being replaced, then the picked PDF and its first page to use. */
    var replacePrompt by mutableStateOf<ReplacePrompt?>(null)
    internal var replaceTarget: Pair<OpenDocument, List<Int>>? = null

    /** Pick a PDF whose pages take the place of [pages] (0-based). */
    fun pickReplaceFile(doc: OpenDocument, pages: List<Int>) {
        if (pages.isEmpty()) return
        replaceTarget = doc to pages
        vm.pickFiles(FilePickKind.Replace)
    }

    /** Replace [ReplacePrompt.pages] with the picked file's pages from [firstPage] (1-based) on. */
    fun replacePages(prompt: ReplacePrompt, firstPage: Int) {
        replacePrompt = null
        val from = (0 until prompt.pages.size).map { firstPage.coerceAtLeast(1) + it }
        vm.runTool(prompt.doc, "page_replace", mapOf(
            "pages" to prompt.pages.map { it + 1 }, "path" to prompt.file.absolutePath, "from_pages" to from,
        ), onSettled = { vm.dropPicked(listOf(prompt.file)) }) {
            vm.tell(plural(R.plurals.msg_pages_replaced, prompt.pages.size, prompt.pages.size))
        }
    }

    fun cancelReplace() {
        replacePrompt?.let { vm.dropPicked(listOf(it.file)) }
        replacePrompt = null
    }

    /** The Split sheet, open over the page grid. */
    var splitOpen by mutableStateOf(false)

    /**
     * Split [doc] by [mode] into parts, then save them to a folder the person picks (or share them).
     * Parts keep the engine's names: `<name>-partK.pdf`, or the bookmark titles.
     */
    fun split(doc: OpenDocument, mode: SplitMode, share: Boolean) = vm.job(text(R.string.msg_busy_splitting)) { eng ->
        val dir = File(app.cacheDir, "split/${System.nanoTime()}").apply { mkdirs() }
        try {
            val args = mutableMapOf<String, Any?>("doc" to doc.id, "out_dir" to dir.absolutePath)
            args += mode.args()
            val r = Diag.measure(DiagArea.Pages, "split", "doc" to doc.diagNumber, "share" to share) { eng.tool("doc_split", args) }
            val list = r.optJSONArray("files")
            val parts = (0 until (list?.length() ?: 0)).map { File(list!!.getJSONObject(it).getString("path")) }.filter { it.isFile }
            if (Diag.enabled()) Diag.event(DiagArea.Pages, "split_result", "doc" to doc.diagNumber, "files" to parts.size, "bytes" to parts.sumOf { it.length() })
            if (parts.isEmpty()) {
                vm.tell(text(R.string.msg_split_nothing))
                dir.deleteRecursively()
                return@job
            }
            splitOpen = false
            val named = parts.map { it to it.name }
            if (share) {
                val uris = try { withContext(Dispatchers.IO) { vm.files.shareableAll(named) } } finally { dir.deleteRecursively() }
                vm.intents.trySend(Intent.createChooser(vm.sharing.sendIntent(uris), null))
            } else {
                saveAllToFolder(named, "application/pdf", cleanup = dir)
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    // ---- Layers, attachments and the PDF's own bookmarks (#174, #175, #176) ------------------

    suspend fun layers(doc: OpenDocument) = vm.engine?.let { e -> runCatching { e.command { layers(doc.id) } }.getOrNull() }.orEmpty()

    /**
     * Show or hide layer [position] (1-based, as [layers] lists them). A view setting: the file is
     * not changed, so only the pages are drawn again. [onDone] runs either way.
     */
    fun setLayer(doc: OpenDocument, position: Int, visible: Boolean, onDone: () -> Unit) {
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            try {
                Diag.measure(DiagArea.Pages, "layer_set", "doc" to doc.diagNumber, "visible" to visible) {
                    eng.tool("layer_set", mapOf("doc" to doc.id, "layer" to position, "visible" to visible))
                }
                doc.images.invalidate()
            } catch (e: MobileException) {
                vm.tell(e.userMessage)
            } finally {
                onDone()
            }
        }
    }

    /** Add file: the picked file, waiting for its description. */
    var attachPrompt by mutableStateOf<AttachPrompt?>(null)
    internal var attachTo: OpenDocument? = null

    fun pickAttachment(doc: OpenDocument) {
        attachTo = doc
        vm.pickFiles(FilePickKind.Attach)
    }

    /** Attach [AttachPrompt.file] to the document with [description] (may be blank); one undo step. */
    fun attach(prompt: AttachPrompt, description: String) {
        attachPrompt = null
        val args = mutableMapOf<String, Any?>("path" to prompt.file.absolutePath, "name" to prompt.name)
        if (description.isNotBlank()) args["description"] = description.trim()
        vm.runTool(prompt.doc, "attachment_add", args, onSettled = { vm.dropPicked(listOf(prompt.file)) }) {
            vm.tell(text(R.string.msg_attached, prompt.name), text(R.string.common_undo)) { vm.undo(prompt.doc) }
        }
    }

    fun cancelAttach() {
        attachPrompt?.let { vm.dropPicked(listOf(it.file)) }
        attachPrompt = null
    }

    /** Remove the document-level attachment [name]; Undo in the snackbar brings it back. */
    fun deleteAttachment(doc: OpenDocument, name: String) {
        vm.runTool(doc, "attachment_delete", mapOf("name" to name)) {
            vm.tell(text(R.string.msg_attachment_removed, name), text(R.string.common_undo)) { vm.undo(doc) }
        }
    }

    /** Every embedded bookmark with its path and style (`bookmark_list`, page by page; at most [cap]). */
    suspend fun outlineRows(doc: OpenDocument, cap: Int = 5000): List<OutlineRow>? {
        val eng = vm.engine ?: return null
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
            vm.tell(e.userMessage)
            null
        }
    }

    /** Run bookmark tool [name] on the bookmark at [path] (with [args]); one undo step each. */
    fun editBookmark(doc: OpenDocument, name: String, path: List<Int>?, args: Map<String, Any?> = emptyMap()) {
        vm.runTool(doc, name, (if (path != null) mapOf("path" to path) else emptyMap<String, Any?>()) + args)
    }

    // ---- Document properties, word count, flatten (#183) -------------------------------------

    /** Write metadata [changes] (`doc_set_info` arguments), one refresh for all. */
    fun setDocInfo(doc: OpenDocument, changes: List<Map<String, Any?>>) {
        if (changes.isEmpty()) return
        // each entry its own group: one undo step per changed entry, one refresh for all
        vm.runToolBatch(doc, "doc_set_info", changes.map { listOf(it) }) { applied ->
            if (applied == changes.size) vm.tell(text(R.string.msg_properties_saved))
        }
    }

    /** How the document opens (`doc_initial_view` with no change), or null when it can't be read. */
    suspend fun initialView(doc: OpenDocument): JSONObject? {
        val eng = vm.engine ?: return null
        return try { eng.tool("doc_initial_view", mapOf("doc" to doc.id)).optJSONObject("initial_view") }
        catch (e: MobileException) { vm.tell(e.userMessage); null }
    }

    fun setInitialView(doc: OpenDocument, args: Map<String, Any?>) {
        if (args.isEmpty()) return
        vm.runTool(doc, "doc_initial_view", args) { vm.tell(text(R.string.msg_initial_view_saved)) }
    }

    /** Words and characters on every page (`text_word_count`); the message says why when it can't. */
    suspend fun wordCount(doc: OpenDocument): JSONObject? {
        val eng = vm.engine ?: return null
        return try { eng.tool("text_word_count", mapOf("doc" to doc.id)).optJSONObject("total") }
        catch (e: MobileException) { vm.tell(e.userMessage); null }
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
        vm.runTool(doc, "doc_flatten", mapOf("comments" to comments, "fields" to fields)) {
            Diag.event(DiagArea.Pages, "flattened", "doc" to doc.diagNumber, "comments" to comments, "fields" to fields)
            vm.tell(text(R.string.msg_flattened), text(R.string.common_undo)) { vm.undo(doc) }
        }
    }

    /** Check & prepare: PDF/A, print readiness and thin lines (#181). */
    fun openStandards(doc: OpenDocument) {
        doc.commitTool?.invoke()
        vm.tools.toolScreen = ToolScreen.Standards(doc)
    }

    /** A read-only report from [tool] (null, with the reason told, when it fails). */
    suspend fun report(doc: OpenDocument, tool: String, args: Map<String, Any?> = emptyMap()): JSONObject? {
        val eng = vm.engine ?: return null
        return try { eng.tool(tool, args + ("doc" to doc.id)) } catch (e: MobileException) { vm.tell(e.userMessage); null }
    }

    /** Save as PDF/A at [level] (`pdfa_convert`, one undo step); [onDone] gets what still breaks the rules. */
    fun convertToPdfa(doc: OpenDocument, level: String, onDone: (PdfaReport) -> Unit) {
        vm.runTool(doc, "pdfa_convert", mapOf("level" to level)) { r ->
            val report = parsePdfaReport(r)
            Diag.event(DiagArea.Pages, "pdfa_convert", "doc" to doc.diagNumber, "level" to "pdfa${level.lowercase()}", "compliant" to report.compliant, "issues_left" to report.issues.size)
            onDone(report)
            vm.tell(text(if (report.compliant) R.string.std_msg_pdfa_done else R.string.std_msg_pdfa_partly), text(R.string.common_undo)) { vm.undo(doc) }
        }
    }

    /** Remove the PDF/A identification (`standards_remove_id`); one undo step. */
    fun removePdfaId(doc: OpenDocument, onDone: () -> Unit) {
        vm.runTool(doc, "standards_remove_id", mapOf("standard" to "pdfa")) {
            onDone()
            vm.tell(text(R.string.std_msg_pdfa_id_removed), text(R.string.common_undo)) { vm.undo(doc) }
        }
    }

    /** Fix thin lines (`doc_fix_hairlines`): says how many strokes changed; one undo step when any did. */
    fun fixHairlines(doc: OpenDocument, fix: HairlineFix, pages: List<Int>?) {
        vm.runTool(doc, "doc_fix_hairlines", fix.args(pages)) { r ->
            val n = r.optInt("fixed")
            Diag.event(DiagArea.Pages, "fix_hairlines", "doc" to doc.diagNumber, "fixed" to n, "pages" to r.optInt("pages"), "all_pages" to (pages == null))
            if (n == 0) vm.tell(text(R.string.std_msg_no_hairlines))
            else vm.tell(plural(R.plurals.std_msg_hairlines_fixed, n, n, r.optInt("pages")), text(R.string.common_undo)) { vm.undo(doc) }
        }
    }

    /** Print options (#185): lay the pages out with `doc_print` before the system print dialog. */
    fun openPrintOptions(doc: OpenDocument) {
        if (!doc.info.allowsPrinting) {
            vm.tell(text(R.string.msg_print_disabled))
            return
        }
        vm.tools.toolScreen = ToolScreen.PrintOptions(doc)
    }

    /**
     * Print [doc] with [options]: unchanged options print as [print] always has; otherwise the
     * print-ready PDF `doc_print` makes goes to the print dialog, or with [save] to Downloads.
     */
    fun printWithOptions(doc: OpenDocument, options: PrintOptions, save: Boolean) {
        if (options.isDefault && !save) {
            vm.tools.toolScreen = null
            vm.sharing.print(doc)
            return
        }
        // only the comments switched off: the clean copy ⋮'s "Print without comments" printed (#155, #526)
        if (options == PrintOptions(comments = false) && !save) {
            vm.tools.toolScreen = null
            vm.sharing.print(doc, clean = true)
            return
        }
        if (doc.controller.tool == ViewerTool.Draw) doc.commitTool?.invoke()
        vm.job(text(R.string.print_busy)) { eng ->
            val dir = File(app.cacheDir, "print/${System.nanoTime()}").apply { mkdirs() }
            val out = File(dir, "${doc.title}-print.pdf")
            try {
                val r = Diag.measure(DiagArea.Pages, "print_layout", "doc" to doc.diagNumber, "layout" to options.layout, "per_sheet" to options.perSheet,
                    "subset" to options.subset, "reverse" to options.reverse, "page_range" to options.pages.isNotBlank(), "save" to save) {
                    eng.tool("doc_print", options.args(out.absolutePath) + ("doc" to doc.id))
                }
                Diag.event(DiagArea.Pages, "print_layout_result", "doc" to doc.diagNumber, "sheets" to r.optInt("sheets"), "bytes" to out.length())
                vm.tools.toolScreen = null
                if (save) {
                    vm.deliver(out, out.name, "application/pdf")
                    dir.deleteRecursively()
                } else {
                    // the print job deletes it when it finishes
                    vm.prints.trySend(PrintJob(out, doc.name, deleteWhenDone = true))
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
        vm.tools.toolScreen = ToolScreen.PageMarks(doc)
    }

    /** The marks [doc] already has, for Update and Remove (empty when they can't be read). */
    suspend fun pageMarks(doc: OpenDocument): Set<MarkKind> = vm.engine?.let { e ->
        runCatching { e.command { pageMarks(doc.id) } }.getOrNull()
    }.orEmpty().mapNotNull { k -> MarkKind.entries.firstOrNull { it.key == k } }.toSet()

    /** The picture a watermark or background is to use, picked with [pickMarkImage]. */
    var markImage by mutableStateOf<File?>(null)
        internal set

    fun pickMarkImage() = vm.pickFiles(FilePickKind.MarkImage)

    /** Forget the picked picture (and delete its copy). */
    fun clearMarkImage() {
        markImage?.let { vm.dropPicked(listOf(it)) }
        markImage = null
    }

    /** Add (or, with [replace], update) a mark of [kind] with [args]; one undo step. */
    fun applyMark(doc: OpenDocument, kind: MarkKind, args: Map<String, Any?>, replace: Boolean) {
        vm.runTool(doc, kind.tool, args) {
            Diag.event(DiagArea.Pages, "mark_applied", "doc" to doc.diagNumber, "kind" to kind, "replace" to replace,
                "picture" to args.containsKey("file"), "pages" to ((args["pages"] as? List<*>)?.size ?: -1))
            vm.tools.toolScreen = null
            clearMarkImage()
            val msg = when (kind) {
                MarkKind.HeaderFooter -> if (replace) R.string.marks_msg_hf_updated else R.string.marks_msg_hf_added
                MarkKind.Watermark -> if (replace) R.string.marks_msg_wm_updated else R.string.marks_msg_wm_added
                MarkKind.Background -> if (replace) R.string.marks_msg_bg_updated else R.string.marks_msg_bg_added
            }
            vm.tell(text(msg), text(R.string.common_undo)) { vm.undo(doc) }
        }
    }

    /** Remove every mark of [kind]; one undo step. */
    fun removeMarks(doc: OpenDocument, kind: MarkKind, onDone: () -> Unit) {
        vm.runTool(doc, "doc_remove_marks", mapOf("kind" to kind.key)) { r ->
            onDone()
            val warn = r.optJSONArray("warnings")?.length() ?: 0
            Diag.event(DiagArea.Pages, "marks_removed", "doc" to doc.diagNumber, "kind" to kind, "warnings" to warn)
            vm.tell(text(if (warn > 0) R.string.marks_msg_removed_with_warnings else R.string.marks_msg_removed), text(R.string.common_undo)) { vm.undo(doc) }
        }
    }

    /**
     * Page [page] (0-based) of [doc] with a mark drawn by the engine (#314): the page is copied out
     * (`page_extract`), opened on its own, marked with [tool] and [args] and drawn at about
     * [maxSide] pixels on its longer side, so the preview is the engine's own result, PDF pictures
     * included. The document is not touched. Null when it can't be made. Runs to the end once
     * started, so the copy is always closed and deleted.
     */
    suspend fun markPreview(doc: OpenDocument, kind: MarkKind, args: Map<String, Any?>, page: Int, maxSide: Float): android.graphics.Bitmap? {
        val eng = vm.engine ?: return null
        val size = doc.info.pages.getOrNull(page) ?: return null
        return withContext(NonCancellable) {
            val dir = File(app.cacheDir, "mark-preview").apply { mkdirs() }
            val file = File(dir, "page-${System.nanoTime()}.pdf")
            var copy: Long? = null
            val start = System.nanoTime()
            try {
                eng.tool("page_extract", mapOf("doc" to doc.id, "pages" to listOf(page + 1), "out" to file.absolutePath))
                copy = eng.tool("doc_open", mapOf("path" to file.absolutePath)).optLong("doc")
                eng.tool(kind.tool, args + ("doc" to copy))
                val scale = maxSide / maxOf(size.width, size.height).coerceAtLeast(1f)
                val id = copy.toULong()
                withContext(eng.thumbnails) { eng.session.renderPage(id, 0u, scale, null).toBitmap() }.also {
                    Diag.event(DiagArea.Pages, "mark_preview", "doc" to doc.diagNumber, "kind" to kind, "picture" to args.containsKey("file"), "ms" to (System.nanoTime() - start) / 1_000_000)
                }
            } catch (e: Exception) {
                Diag.failure(DiagArea.Pages, "mark_preview", e)
                null
            } finally {
                copy?.let { c -> runCatching { eng.tool("doc_close", mapOf("doc" to c, "discard_changes" to true)) } }
                file.delete()
            }
        }
    }

    /** Files waiting for the person to pick the folder they go into, and what to delete afterwards. */
    private var folderOutput: FolderOutput? = null
    val outputFolderRequests = Channel<Unit>(Channel.CONFLATED)

    /** Ask for a folder (Storage Access Framework), then write [named] files into it. */
    internal fun saveAllToFolder(named: List<Pair<File, String>>, mime: String, cleanup: File? = null) {
        folderOutput?.let { old -> vm.viewModelScope.launch(NonCancellable + Dispatchers.IO) { old.discard() } }
        folderOutput = FolderOutput(named, mime, cleanup)
        outputFolderRequests.trySend(Unit)
    }

    /** The folder picked for [folderOutput] (null: cancelled, the files are dropped). */
    fun onOutputFolderPicked(tree: Uri?) {
        val out = folderOutput ?: return
        folderOutput = null
        if (tree == null) {
            vm.viewModelScope.launch(NonCancellable + Dispatchers.IO) { out.discard() }
            return
        }
        vm.job(text(R.string.msg_busy_saving_files)) {
            try {
                val saved = Diag.measure(DiagArea.Pages, "save_to_folder", "files" to out.files.size) {
                    withContext(Dispatchers.IO) { vm.files.saveAllToTree(tree, out.files, out.mime) }
                }
                Diag.event(DiagArea.Pages, "save_to_folder_result", "files" to out.files.size, "saved" to saved)
                vm.tell(plural(R.plurals.msg_files_saved_folder, saved, saved))
            } finally {
                withContext(NonCancellable + Dispatchers.IO) { out.discard() }
            }
        }
    }
}
