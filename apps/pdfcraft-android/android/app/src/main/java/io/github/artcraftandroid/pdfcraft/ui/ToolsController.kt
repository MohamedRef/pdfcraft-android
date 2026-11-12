package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import io.github.artcraftandroid.pdfcraft.engine.review
import android.content.Intent
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import io.github.artcraftandroid.pdfcraft.core.MobileException
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.EmbeddedBookmarkJson
import io.github.artcraftandroid.pdfcraft.engine.SafBoundedCopy
import io.github.artcraftandroid.pdfcraft.engine.withTemporaryBookmarkSource
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.NonCancellable

class ToolsController internal constructor(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()

    // ---- Tool screens (Compress, Combine, Protect, Create, Crop, Recognize text, Scan, Redact, Organize) ----

    /** The full-screen tool over the app, if one is open. */
    var toolScreen by mutableStateOf<ToolScreen?>(null)

    /** The Pages screen is organizing (not just showing thumbnails). */
    var organizing by mutableStateOf(false)

    fun openCompress(doc: OpenDocument) { toolScreen = ToolScreen.Compress(doc) }
    fun openCombine(doc: OpenDocument?) {
        // like the reference, the open document is the first file
        if (doc != null && vm.combineFiles.isEmpty()) {
            val uri = doc.source ?: Uri.fromFile(doc.file)
            vm.combineFiles.add(PickedFile(uri, doc.name, doc.info.fileSize.toLong()))
        }
        toolScreen = ToolScreen.Combine(doc)
    }
    fun openProtect(doc: OpenDocument) { toolScreen = ToolScreen.Protect(doc) }
    /** Create PDF starts on its own page of sources (photos, files, a scan), as the reference's. */
    fun openCreate() { toolScreen = ToolScreen.Create }
    fun openCrop(doc: OpenDocument) { toolScreen = ToolScreen.Crop(doc) }

    // ---- Tool screens, continued --------------------------------------------------------------
    /** Recognize text: its options, review and batch state (#166). */
    val ocr = io.github.artcraftandroid.pdfcraft.ui.viewer.OcrState()

    /** Undo [steps] edits of [doc] in a row (a recognition made page by page), then redraw. */
    internal fun undoSteps(doc: OpenDocument, steps: Int) {
        val eng = vm.engine ?: return
        vm.viewModelScope.launch {
            try {
                repeat(steps) { if (vm.document === doc && doc.info.undo != null) { eng.tool("edit_undo", mapOf("doc" to doc.id)); vm.refresh(doc) } }
            } catch (e: MobileException) {
                vm.tell(e.userMessage)
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

    // ---- Remove hidden information (#177) ------------------------------------------------------

    /** Categories of hidden information with their counts (`doc_hidden_info`), or null. */
    suspend fun hiddenInfo(doc: OpenDocument): List<Pair<String, Int>>? {
        val eng = vm.engine ?: return null
        return try {
            val a = eng.tool("doc_hidden_info", mapOf("doc" to doc.id)).optJSONArray("categories") ?: return emptyList()
            (0 until a.length()).map { a.getJSONObject(it).let { o -> o.optString("category") to o.optInt("count") } }
        } catch (e: MobileException) { vm.tell(e.userMessage); null }
    }

    /** Remove [categories] (all of them, Sanitize, when null); undoable until saved. */
    fun removeHidden(doc: OpenDocument, categories: List<String>?) {
        doc.commitTool?.invoke()
        vm.runTool(doc, "doc_remove_hidden", if (categories == null) emptyMap() else mapOf("categories" to categories)) {
            doc.controller.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.None
            Diag.event(DiagArea.Pages, "hidden_removed", "doc" to doc.diagNumber, "all" to (categories == null), "categories" to (categories?.size ?: -1))
            vm.tell(text(R.string.msg_hidden_removed), text(R.string.common_undo)) { vm.undo(doc) }
        }
    }

    /** Crop [page] (or every page) to [rect] (points from the page's top-left). */
    fun crop(doc: OpenDocument, page: Int?, rect: FloatArray) {
        val args = mutableMapOf<String, Any?>("rect" to rect.map { it.toDouble() })
        if (page != null) args["pages"] = listOf(page + 1)
        vm.runTool(doc, "page_set_box", args) {
            toolScreen = null
            vm.tell(text(if (page == null) R.string.msg_crop_all else R.string.msg_crop_page))
        }
    }

    /**
     * Export the PDF's embedded outline (not the reader's private, on-device bookmarks) to a
     * portable JSON tree. Rust owns the schema, nesting limit and atomic temporary write.
     * Only the finished JSON is published to Downloads; source PDF bytes are never modified.
     */
    fun exportEmbeddedBookmarks(doc: OpenDocument) = vm.job(text(R.string.sheet_export_embedded_bookmarks)) { eng ->
        val out = vm.tempFile("json")
        try {
            eng.tool("bookmark_export", mapOf("doc" to doc.id, "out" to out.absolutePath))
            require(out.isFile && out.length() > 0L) { text(R.string.sheet_export_embedded_bookmarks_failed) }
            vm.deliver(out, "${DocumentFiles.safeName(doc.title)}_bookmarks.json", "application/json")
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
    ) = vm.job(text(R.string.text_styles_creating)) { eng ->
        if (vm.document !== doc || doc.info.generation != generation || !doc.writable ||
            doc.info.signed || doc.info.readOnlyReason != null || !doc.info.allowsCopying || !doc.info.allowsAssembly) {
            vm.tell(text(R.string.text_styles_unavailable))
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
        vm.refresh(doc)
        if (vm.document === doc) doc.controller.sheet = io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerSheet.Bookmarks
        vm.tell(text(R.string.text_styles_created))
    }

    /** Ask Android SAF to pick JSON for the currently displayed PDF, not for private bookmarks. */
    val bookmarkJsonPicks = Channel<Unit>(Channel.CONFLATED)
    private var bookmarkJsonTarget: OpenDocument? = null

    fun pickEmbeddedBookmarksImport(doc: OpenDocument) {
        if (vm.document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
            vm.tell(text(R.string.sheet_import_embedded_bookmarks_unavailable))
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
        if (vm.document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
            vm.tell(text(R.string.sheet_import_embedded_bookmarks_unavailable))
            return
        }
        vm.job(text(R.string.sheet_import_embedded_bookmarks)) { eng ->
            val staged = vm.tempFile("json")
            try {
                withContext(Dispatchers.IO) {
                    val stream = app.contentResolver.openInputStream(uri)
                        ?: throw java.io.IOException(text(R.string.sheet_import_embedded_bookmarks_unreadable))
                    val copyContext = currentCoroutineContext()
                    stream.use { input ->
                        staged.outputStream().use { output ->
                            EmbeddedBookmarkJson.copyBounded(input, output) { copyContext.ensureActive() }
                        }
                    }
                }
                // The SAF provider may be slow. Recheck after staging and before every write.
                if (vm.document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
                    vm.tell(text(R.string.sheet_import_embedded_bookmarks_unavailable))
                    return@job
                }
                val result = eng.tool("bookmark_import", mapOf(
                    "doc" to doc.id, "file" to staged.absolutePath, "replace" to false,
                ))
                vm.refresh(doc) // Rebuild the outline, generation, and Undo / Redo state.
                vm.tell(text(R.string.sheet_import_embedded_bookmarks_done))
                val unmapped = result.optJSONArray("unmapped")?.length() ?: 0
                if (unmapped > 0) vm.tell(text(R.string.sheet_import_embedded_bookmarks_unmapped, unmapped))
                if (result.optBoolean("truncated", false)) {
                    vm.tell(text(R.string.sheet_import_embedded_bookmarks_truncated))
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
        if (vm.document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
            vm.tell(text(R.string.sheet_copy_embedded_bookmarks_unavailable))
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
        if (vm.document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
            vm.tell(text(R.string.sheet_copy_embedded_bookmarks_unavailable))
            return
        }
        if (uri == doc.source) {
            vm.tell(text(R.string.sheet_copy_embedded_bookmarks_same_source))
            return
        }
        vm.job(text(R.string.sheet_copy_embedded_bookmarks_busy)) { eng ->
            val source = vm.tempFile("pdf")
            try {
                withContext(Dispatchers.IO) {
                    val size = vm.files.size(uri)
                    if (size != null && size > SafBoundedCopy.MAX_BOOKMARK_SOURCE_PDF_BYTES) {
                        throw java.io.IOException(text(R.string.sheet_copy_embedded_bookmarks_too_large))
                    }
                    val stream = app.contentResolver.openInputStream(uri)
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
                if (vm.document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
                    vm.tell(text(R.string.sheet_copy_embedded_bookmarks_unavailable))
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
                        vm.tell(text(R.string.sheet_copy_embedded_bookmarks_copy_restricted))
                        return@withTemporaryBookmarkSource
                    }
                    if (!sourceInfo.hasOutline) {
                        vm.tell(text(R.string.sheet_copy_embedded_bookmarks_empty))
                        return@withTemporaryBookmarkSource
                    }
                    if (vm.document !== doc || !doc.writable || doc.info.signed || doc.info.readOnlyReason != null) {
                        vm.tell(text(R.string.sheet_copy_embedded_bookmarks_unavailable))
                        return@withTemporaryBookmarkSource
                    }
                    val result = eng.tool("bookmark_copy", mapOf("doc" to doc.id, "from" to from, "replace" to false))
                    vm.refresh(doc)
                    vm.tell(text(R.string.sheet_copy_embedded_bookmarks_done))
                    val unmapped = result.optJSONArray("unmapped")?.length() ?: 0
                    if (unmapped > 0) vm.tell(text(R.string.sheet_import_embedded_bookmarks_unmapped, unmapped))
                    if (result.optBoolean("truncated", false)) {
                        vm.tell(text(R.string.sheet_import_embedded_bookmarks_truncated))
                    }
                }
            } finally {
                source.delete()
            }
        }
    }

    /** Export [doc] as a Word, RTF, Excel or PowerPoint document. */
    fun exportDocument(doc: OpenDocument, ext: String) = vm.job(text(R.string.msg_busy_exporting_to, ext.uppercase())) { eng ->
        val out = vm.tempFile(ext)
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
        vm.deliver(out, "${doc.title}.$ext", mime)
    }

    /** Export to images: the format chosen, while its pages are being picked in the page grid. */
    var imageExport by mutableStateOf<String?>(null)

    /** The image format last exported to, offered first next time (as the reference remembers it). */
    val lastImageFormat: String get() = vm.settings.appPrefs.getString("imageFormat", "jpg") ?: "jpg"

    /** Export to images: remember the format, then pick the pages in the page grid (Save or Share there). */
    fun exportImages(doc: OpenDocument, ext: String) {
        vm.settings.appPrefs.edit().putString("imageFormat", ext).apply()
        toolScreen = null
        imageExport = ext
        organizing = true
        doc.controller.pagesOpen = true
    }

    /** Export to images: resolution (dpi) and JPEG quality, as last used. */
    var imageDpi by mutableStateOf(vm.settings.appPrefs.getInt("imageDpi", 150))
        internal set
    var imageQuality by mutableStateOf(vm.settings.appPrefs.getInt("imageQuality", 85))
        internal set

    fun updateImageSettings(dpi: Int, quality: Int) {
        imageDpi = dpi
        imageQuality = quality
        vm.settings.appPrefs.edit().putInt("imageDpi", dpi).putInt("imageQuality", quality).apply()
    }

    /**
     * Pictures of [pages] (0-based) drawn by the engine (`doc_export_images`) at [imageDpi], saved
     * (PNG and JPEG to Pictures/PdfCraft, TIFF to Downloads) or handed to the share sheet.
     */
    fun writeImages(doc: OpenDocument, ext: String, pages: List<Int>, share: Boolean) = vm.job(text(R.string.msg_busy_exporting_images)) { eng ->
        val format = when (ext) { "png" -> "png"; "tif", "tiff" -> "tiff"; else -> "jpeg" }
        val mime = when (format) { "png" -> "image/png"; "tiff" -> "image/tiff"; else -> "image/jpeg" }
        val dir = File(app.cacheDir, "images/${System.nanoTime()}").apply { mkdirs() }
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
                val uris = withContext(Dispatchers.IO) { vm.files.shareableAll(made) }
                vm.intents.trySend(Intent.createChooser(vm.sharing.sendIntent(uris, mime), null))
            } else {
                Diag.measure(DiagArea.Pages, "export_pages_save", "files" to made.size, "to_downloads" to (format == "tiff")) {
                    withContext(Dispatchers.IO) {
                        for ((f, name) in made) if (format == "tiff") vm.files.saveToDownloads(f, name, mime) else vm.files.saveToPictures(f, name, mime)
                    }
                }
                vm.tell(plural(if (format == "tiff") R.plurals.msg_images_saved_downloads else R.plurals.msg_images_saved, made.size, made.size))
            }
            if (lower > 0) vm.tell(plural(R.plurals.msg_images_lower_dpi, lower, lower))
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
    fun exportAllImages(doc: OpenDocument, skipSmall: Boolean, share: Boolean) = vm.job(text(R.string.msg_busy_exporting_images)) { eng ->
        val dir = File(app.cacheDir, "images/${System.nanoTime()}").apply { mkdirs() }
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
                vm.tell(text(R.string.msg_no_images_found))
                dir.deleteRecursively()
                return@job
            }
            if (reasons.isNotEmpty()) vm.tell(plural(R.plurals.msg_images_skipped, skipped!!.length(), skipped.length(), reasons.take(3).joinToString("; ")))
            if (share) {
                val uris = try { withContext(Dispatchers.IO) { vm.files.shareableAll(made) } } finally { dir.deleteRecursively() }
                vm.intents.trySend(Intent.createChooser(vm.sharing.sendIntent(uris, "image/*"), null))
            } else {
                vm.docTools.saveAllToFolder(made, "image/*", cleanup = dir)
            }
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Create PDF from picked files (images, text, Office documents, PDFs), combined in order, then open it. */
    internal fun createFrom(picked: List<Uri>) = vm.job(text(R.string.msg_busy_creating_pdf)) { eng ->
        // at most 12 files at once, as the reference allows
        if (picked.size > MAX_CREATE_FILES) vm.tell(plural(R.plurals.msg_create_first_files, MAX_CREATE_FILES, MAX_CREATE_FILES))
        val uris = picked.take(MAX_CREATE_FILES)
        val copies = mutableListOf<File>()
        val out = vm.tempFile("pdf")
        try {
            val paths = withContext(Dispatchers.IO) { uris.map { vm.copyIn(it).also(copies::add).absolutePath } }
            eng.tool("doc_create_multiple", mapOf("paths" to paths, "out" to out.absolutePath, "mode" to "combine"))
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            vm.dropPicked(copies)
        }
        val name = vm.files.displayNameRaw(uris.first()).substringBeforeLast('.') + ".pdf"
        val uri = withContext(Dispatchers.IO) { vm.files.saveToDownloads(out, DocumentFiles.safeName(name)).also { out.delete() } }
        vm.tell(text(R.string.msg_saved_downloads, name))
        vm.open(uri)
    }

    /** Pages [pages] (0-based) of [doc] as a new PDF in Downloads. */
    fun extractPages(doc: OpenDocument, pages: List<Int>) = vm.job(text(R.string.msg_busy_extracting)) { eng ->
        val out = vm.tempFile("pdf")
        Diag.measure(DiagArea.Pages, "extract_pages", "doc" to doc.diagNumber, "pages" to pages.size) {
            eng.tool("page_extract", mapOf("doc" to doc.id, "pages" to pages.map { it + 1 }, "out" to out.absolutePath))
        }
        vm.deliver(out, "Pages from ${doc.title}.pdf")
    }
}
