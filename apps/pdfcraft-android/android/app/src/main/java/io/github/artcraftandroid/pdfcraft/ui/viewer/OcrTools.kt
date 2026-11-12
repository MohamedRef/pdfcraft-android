package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.SuccessBanner
import io.github.artcraftandroid.pdfcraft.ui.ToolScreen
import io.github.artcraftandroid.pdfcraft.ui.copyIntoCache
import io.github.artcraftandroid.pdfcraft.ui.copyOut
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** One file of a batch Recognize text: its name, and once read, what came of it. */
data class OcrBatchFile(val uri: Uri, val name: String, val result: String? = null, val failed: Boolean = false)

/** Recognize text's state (#166): the options dialog, and the batch screen's files and progress. */
class OcrState {
    /** The document whose options dialog is open. */
    var optionsFor by mutableStateOf<OpenDocument?>(null)

    /** The bundled models' languages (code, name), as ocr_status lists them. */
    var languages by mutableStateOf(listOf("en" to "English"))

    val batch = mutableStateListOf<OcrBatchFile>()
    var batchFolder by mutableStateOf<Uri?>(null)
    var batchFolderName by mutableStateOf<String?>(null)
    var batchStatus by mutableStateOf<String?>(null)
    var batchOptions by mutableStateOf(false)
    var batchJob: Job? = null
    var batchRunning by mutableStateOf(false)
}

/** Unpack the models and ask whether recognition can run; false (and says so) when it can't. */
suspend fun AppViewModel.ocrReady(eng: Engine): Boolean {
    busy = text(R.string.msg_busy_preparing_ocr)
    withContext(Dispatchers.IO) { runCatching { eng.prepareOcrModels(getApplication()) } }
    busy = null
    val status = runCatching { eng.tool("ocr_status") }.getOrNull()
    if (status?.optBoolean("available", false) != true) {
        tell(text(R.string.msg_ocr_not_installed))
        return false
    }
    val langs = status.optJSONArray("languages")
    val list = (0 until (langs?.length() ?: 0)).mapNotNull { i ->
        langs?.optJSONObject(i)?.let { o -> o.optString("code").takeIf { it.isNotEmpty() }?.let { it to o.optString("name", it) } }
    }
    if (list.isNotEmpty()) ocr.languages = list
    return true
}

/** Recognize text: first its options. */
fun AppViewModel.recognizeText(doc: OpenDocument) {
    val eng = engine ?: return
    viewModelScope.launch { if (ocrReady(eng)) ocr.optionsFor = doc }
}

/**
 * Recognize text with [o]: searchable text over [doc]'s pages (one undo step), or a new document
 * of editable text the user then saves where they choose.
 */
fun AppViewModel.runOcr(doc: OpenDocument, o: OcrOptions) {
    val eng = engine ?: return
    val ctx = getApplication<Application>()
    ocr.optionsFor = null
    o.save(ctx)
    viewModelScope.launch {
        val out = if (o.editable) File(ctx.cacheDir, "out-ocr-${System.nanoTime()}.pdf") else null
        val args = o.recognizeArgs(doc.viewer.currentPage, doc.pageCount, out?.absolutePath) ?: return@launch
        busy = text(R.string.msg_busy_recognizing)
        val t0 = System.nanoTime()
        var ok = false
        try {
            if (out == null) {
                recognizeByPage(eng, doc, args)
            } else {
                val r = eng.tool("ocr_recognize", args + ("doc" to doc.id))
                busy = null
                requests.create("${doc.name.removeSuffix(".pdf")}_ocr.pdf", "application/pdf") { uri ->
                    viewModelScope.launch {
                        try {
                            if (uri != null) {
                                copyOut(ctx, out, uri)
                                tell(text(R.string.ocr_editable_saved), text(R.string.common_open)) { open(uri) }
                            }
                        } catch (e: Exception) {
                            tell(e.userMessage)
                        } finally {
                            out.delete()
                        }
                    }
                }
            }
            ok = true
        } catch (e: Exception) {
            out?.delete()
            tell(e.userMessage)
        } finally {
            busy = null
            editEvent(doc, "ocr_run", "editable" to (out != null), "pages" to doc.pageCount, "ms" to (System.nanoTime() - t0) / 1_000_000, "ok" to ok)
        }
    }
}


/**
 * Searchable text one page at a time (#200), so the busy dialog counts pages and Cancel stops at
 * the next page; the pages read by then keep their text, and one Undo takes back the whole run.
 */
private suspend fun AppViewModel.recognizeByPage(eng: Engine, doc: OpenDocument, args: Map<String, Any?>) {
    @Suppress("UNCHECKED_CAST")
    val pages = (args["pages"] as? List<Int>) ?: (1..doc.pageCount).toList()
    var cancelled = false
    busyCancel = { cancelled = true }
    var recognized = 0
    try {
        for ((k, page) in pages.withIndex()) {
            if (cancelled || document !== doc) break
            busy = text(R.string.msg_busy_recognizing_page, k + 1, pages.size)
            busyProgress = k to pages.size
            val r = eng.tool("ocr_recognize", args + mapOf("doc" to doc.id, "pages" to listOf(page)))
            val read = r.optJSONArray("pages")
            if ((0 until (read?.length() ?: 0)).any { read?.optJSONObject(it)?.has("skipped") == false }) recognized++
        }
    } finally {
        busyProgress = null
        busyCancel = null
        refresh(doc)
    }
    when {
        recognized == 0 && cancelled -> tell(text(R.string.msg_ocr_cancelled))
        recognized == 0 -> tell(text(R.string.msg_ocr_nothing))
        else -> success = SuccessBanner(
            plural(if (cancelled) R.plurals.msg_ocr_stopped else R.plurals.msg_ocr_done_pages, recognized, recognized),
        ) { undoSteps(doc, recognized) }
    }
}

// ---- Review: correct the recognised words ------------------------------------------------------

/** The dictionary's words for [language], lower case. */
suspend fun AppViewModel.ocrDictionary(language: String): Set<String> {
    val eng = engine ?: return emptySet()
    val f = ocrDictionaryFile(getApplication(), language)
    if (!f.isFile) return emptySet()
    val r = runCatching { eng.tool("ocr_dictionary", mapOf("path" to f.absolutePath)) }.getOrNull() ?: return emptySet()
    val words = r.optJSONArray("words") ?: return emptySet()
    return (0 until words.length()).map { words.optString(it).lowercase() }.toSet()
}

/** Add [word] to [language]'s dictionary: later reviews don't flag it. */
suspend fun AppViewModel.addToOcrDictionary(language: String, word: String): Boolean {
    val eng = engine ?: return false
    val f = ocrDictionaryFile(getApplication(), language)
    return try {
        eng.tool("ocr_dictionary", mapOf("path" to f.absolutePath, "add" to listOf(word)))
        true
    } catch (e: Exception) {
        tell(e.userMessage)
        false
    }
}

/**
 * Read page [page] (1-based) for review, keeping the engine's review so corrections can be
 * applied ([OcrReview.review]).
 */
internal suspend fun AppViewModel.startOcrReview(doc: OpenDocument, page: Int, o: OcrOptions): JSONObject? {
    if (document !== doc || page !in 1..doc.pageCount || busy != null) return null
    val eng = engine ?: return null
    withContext(Dispatchers.IO) { runCatching { eng.prepareOcrModels(getApplication()) } }
    val status = eng.tool("ocr_status")
    if (!status.optBoolean("available", false)) throw IllegalStateException(text(R.string.msg_ocr_not_installed))
    return eng.tool("ocr_words", o.readingArgs() + mapOf("doc" to doc.id, "pages" to listOf(page), "retain_review" to true, "skip_text_pages" to false))
}

/** Apply the reviewed words of review [review], with [corrections] ((page, word) to text): one undo step. */
fun AppViewModel.applyOcrReview(doc: OpenDocument, review: Int, corrections: Map<Pair<Int, Int>, String>, onDone: () -> Unit) {
    val list = corrections.map { (k, t) -> mapOf("page" to k.first, "word" to k.second, "text" to t) }
    runTool(doc, "ocr_review_apply", mapOf("review" to review, "corrections" to list)) {
        editEvent(doc, "ocr_review_apply", "corrections" to list.size)
        success = SuccessBanner(text(R.string.ocr_review_applied)) { undo(doc) }
        onDone()
    }
}

/** Let review [review] go without applying it. */
fun AppViewModel.discardOcrReview(doc: OpenDocument, review: Int) {
    val eng = engine ?: return
    viewModelScope.launch { runCatching { eng.tool("ocr_review_apply", mapOf("doc" to doc.id, "review" to review, "discard" to true)) } }
}

// ---- Several files -----------------------------------------------------------------------------

fun AppViewModel.openOcrBatch() {
    if (ocr.batchRunning) return
    ocr.batch.clear()
    ocr.batchStatus = null
    toolScreen = ToolScreen.OcrBatch
}

fun AppViewModel.addOcrBatchFiles() {
    if (ocr.batchRunning) return
    requests.open(arrayOf("application/pdf"), many = true) { uris ->
        for (u in uris.distinct()) if (ocr.batch.none { it.uri == u }) ocr.batch.add(OcrBatchFile(u, files.displayNameRaw(u).take(180)))
    }
}

fun AppViewModel.chooseOcrBatchFolder() {
    requests.folder { uri ->
        if (uri == null) return@folder
        ocr.batchFolder = uri
        // "primary:Documents/Scans" reads "Documents/Scans"
        ocr.batchFolderName = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri).substringAfter(':') }.getOrNull() ?: uri.lastPathSegment
    }
}

/**
 * Recognize text in every file of the batch with [o], one file at a time (progress, and Cancel
 * between files), each result written into the chosen folder as `<name>_ocr.pdf`; each file's
 * row then says how many words were read, that it was skipped, or why it failed.
 */
fun AppViewModel.runOcrBatch(o: OcrOptions) {
    val eng = engine ?: return
    val folder = ocr.batchFolder ?: return
    if (ocr.batchRunning || ocr.batch.isEmpty()) return
    val ctx = getApplication<Application>()
    o.save(ctx)
    ocr.batchOptions = false
    ocr.batchRunning = true
    for (i in ocr.batch.indices) ocr.batch[i] = ocr.batch[i].copy(result = null, failed = false)
    ocr.batchJob = viewModelScope.launch {
        if (!ocrReady(eng)) {
            ocr.batchRunning = false
            return@launch
        }
        val dir = File(ctx.cacheDir, "out-ocrbatch-${System.nanoTime()}").apply { mkdirs() }
        val outDir = File(dir, "out").apply { mkdirs() }
        try {
            for (i in ocr.batch.indices) {
                ensureActive()
                val f = ocr.batch[i]
                ocr.batchStatus = text(R.string.ocr_batch_progress, i + 1, ocr.batch.size, f.name)
                val row = try {
                    val input = copyIntoCache(ctx, f.uri, "ocrbatch-in")
                    val named = File(dir, DocumentFiles.safeName(f.name)).also { input.renameTo(it) }
                    // the engine call runs to its end; Cancel stops before the next file
                    val r = withContext(NonCancellable) {
                        eng.tool("ocr_recognize_files", o.readingArgs() + mapOf("paths" to listOf(named.absolutePath), "folder" to outDir.absolutePath, "skip_text_files" to !o.redo))
                    }
                    named.delete()
                    val file = r.optJSONArray("files")?.optJSONObject(0)
                    val output = file?.optString("output")?.takeIf { it.isNotEmpty() }?.let(::File)
                    when {
                        file == null -> f.copy(result = text(R.string.ocr_batch_failed), failed = true)
                        file.has("error") -> f.copy(result = file.optString("error").replace(dir.absolutePath, "").take(240), failed = true)
                        output == null || !output.isFile -> f.copy(result = text(R.string.ocr_batch_skipped))
                        else -> {
                            writeIntoFolder(folder, output, DocumentFiles.safeName(f.name).removeSuffix(".pdf") + "_ocr.pdf")
                            output.delete()
                            f.copy(result = text(R.string.ocr_batch_done, file.optInt("words")))
                        }
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    f.copy(result = e.userMessage.replace(dir.absolutePath, "").take(240), failed = true)
                }
                ocr.batch[i] = row
            }
            ocr.batchStatus = text(R.string.ocr_batch_finished)
        } catch (e: kotlinx.coroutines.CancellationException) {
            ocr.batchStatus = text(R.string.ocr_batch_cancelled)
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { dir.deleteRecursively() }
            ocr.batchRunning = false
            if (Diag.enabled()) Diag.event(DiagArea.Edit, "ocr_batch", "files" to ocr.batch.size, "failed" to ocr.batch.count { it.failed }, "finished" to ocr.batch.count { it.result != null && !it.failed })
        }
    }
}

fun AppViewModel.cancelOcrBatch() {
    ocr.batchJob?.cancel()
}

/** Write [file] into the folder [tree] (a document tree the user chose) as [name]. */
private suspend fun AppViewModel.writeIntoFolder(tree: Uri, file: File, name: String) = withContext(Dispatchers.IO) {
    val ctx = getApplication<Application>()
    val parent = android.provider.DocumentsContract.buildDocumentUriUsingTree(tree, android.provider.DocumentsContract.getTreeDocumentId(tree))
    val target = android.provider.DocumentsContract.createDocument(ctx.contentResolver, parent, "application/pdf", name)
        ?: error(text(R.string.ocr_batch_folder_gone))
    copyOut(ctx, file, target)
}
