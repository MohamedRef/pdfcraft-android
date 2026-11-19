package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.MobileException
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/*
 * Compress, Combine, Protect and the space audit (#730: moved out of AppViewModel.kt as they were).
 * Their state (compressOptions, combineFiles, …) and the shared job helpers stay in [AppViewModel].
 */

internal fun AppViewModel.updateCompressOptions(o: CompressOptions) {
    compressOptions = o
    settings.appPrefs.edit()
        .putBoolean("compress.advanced", o.enabled).putFloat("compress.targetMb", (o.targetMb ?: 0.0).toFloat())
        .putInt("compress.ppi", o.ppi).putInt("compress.quality", o.quality).putBoolean("compress.mobile", o.mobile)
        .putBoolean("compress.removeInvalidLinks", o.removeInvalidLinks).putBoolean("compress.discardFormActions", o.discardFormActions)
        .putBoolean("compress.discardTags", o.discardTags).putBoolean("compress.discardAlternateImages", o.discardAlternateImages)
        .apply()
}

/** Compress PDF at [level] (0 high, 1 medium, 2 low compression), or with Advanced [options]. */
internal fun AppViewModel.compress(doc: OpenDocument, level: Int, options: CompressOptions = compressOptions) = docJob(doc, text(R.string.msg_busy_compressing)) { eng ->
    val out = tempFile("pdf")
    // How far it is, and Cancel (#634). The engine works on a copy and writes the file last, so
    // stopping leaves the open document and its file as they were and makes no file.
    val work = io.github.artcraftandroid.pdfcraft.core.ToolJob()
    val stop = offerCancel(text(R.string.busy_cancel_compress)) { work.cancel() }
    val compressing = text(R.string.msg_busy_compressing)
    val watching = viewModelScope.launch {
        while (true) {
            val p = work.progress()
            if (p.steps > 0uL) {
                tools.busy = when (val step = compressStep(p.stage, p.done.toInt(), p.total.toInt(), p.attempt.toInt())) {
                    is CompressStep.Reading -> text(R.string.msg_busy_compressing_reading, step.page, step.pages)
                    is CompressStep.Images -> text(R.string.msg_busy_compressing_images, step.image, step.images)
                    is CompressStep.Again -> text(R.string.msg_busy_compressing_again, step.attempt)
                    CompressStep.Working -> compressing
                }
                tools.busyProgress = p.percent.toInt() to 100
            }
            kotlinx.coroutines.delay(COMPRESS_WATCH_MS)
        }
    }
    val r = try {
        Diag.measure(DiagArea.Pages, "compress_run", "doc" to doc.diagNumber, "level" to level, "advanced" to options.enabled) {
            eng.tool("doc_optimize", compressArgs(level, options) + mapOf("doc" to doc.id, "path" to out.absolutePath), work)
        }
    } catch (e: MobileException) {
        out.delete()
        if (!stop.asked) throw e
        val p = work.progress()
        Diag.event(DiagArea.Pages, "job_cancelled", "job" to "compress", "doc" to doc.diagNumber, "stage" to p.stage, "percent" to p.percent.toInt(), "attempt" to p.attempt.toInt())
        return@docJob
    } finally {
        watching.cancel()
        withdrawCancel()
        tools.busyProgress = null
    }
    tools.toolScreen = null
    val before = doc.info.fileSize.toLong()
    Diag.event(DiagArea.Pages, "compress_result", "doc" to doc.diagNumber, "before_bytes" to before, "after_bytes" to out.length(),
        "smaller" to (out.length() < before), "target_set" to r.has("target_reached"), "target_reached" to r.optBoolean("target_reached"))
    if (out.length() >= before) {
        // as the reference: nothing is saved when the file can't get any smaller
        out.delete()
        tell(text(R.string.msg_compress_no_gain))
        return@docJob
    }
    val sizes = text(R.string.msg_compressed_sizes, io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize(before), io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize(out.length()))
    val target = if (r.has("target_reached")) text(if (r.optBoolean("target_reached")) R.string.msg_compress_target_reached else R.string.msg_compress_target_missed) else null
    deliver(out, "${doc.title}_compressed.pdf", lead = listOfNotNull(sizes, target).joinToString(" "))
}

/** What takes the file's space (`doc_audit_space`): (category, bytes, percent), biggest first. */
internal suspend fun AppViewModel.auditSpace(doc: OpenDocument): List<Triple<String, Long, Double>>? {
    val eng = engine ?: return null
    return try {
        val a = eng.tool("doc_audit_space", mapOf("doc" to doc.id)).optJSONArray("categories") ?: return emptyList()
        (0 until a.length()).map { a.getJSONObject(it).let { o -> Triple(o.optString("category"), o.optLong("bytes"), o.optDouble("percent")) } }
            .sortedByDescending { it.second }
    } catch (e: MobileException) { fail(e.userMessage); null }
}

/** Combine the gathered files, in order, into [name].pdf. */
internal fun AppViewModel.combine(name: String) = job(text(R.string.msg_busy_combining)) { eng ->
    val copies = mutableListOf<File>()
    val out = tempFile("pdf")
    try {
        // the files are read in one by one, counted, and Cancel stops before the next (#614)
        val stop = offerCancel(text(R.string.busy_cancel_combine))
        val paths = copyInCounted(combineFiles.map { it.uri }, copies, stop)
        if (paths == null) {
            Diag.event(DiagArea.Pages, "job_cancelled", "job" to "combine", "copied" to copies.size, "files" to combineFiles.size)
            out.delete()
            return@job
        }
        withdrawCancel()
        tools.busyProgress = null
        val bytes = copies.sumOf { it.length() }
        tools.busyNote = busyLargeFileNote(bytes)
        // the combining itself, step by step, with Cancel (#634): the file is written last
        watchedTool(eng, null, "combine", "doc_combine", mapOf("paths" to paths, "out" to out.absolutePath),
            text(R.string.busy_cancel_combine_step), ::combineLine, bytes = bytes, pages = 0) ?: run {
            Diag.event(DiagArea.Pages, "job_cancelled", "job" to "combine", "copied" to copies.size, "files" to combineFiles.size)
            out.delete()
            return@job
        }
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
internal fun AppViewModel.protect(doc: OpenDocument, protection: Map<String, Any?>) = docJob(doc, text(R.string.msg_busy_setting_password)) { eng ->
    save(doc)
    val out = tempFile("pdf")
    // Three steps, said in the dialog, with Cancel between them (#634). Saving the protected
    // copy encrypts and writes the whole file: the engine tells how far it is, so the bar
    // moves through it and Cancel stops inside it, with no file written (#646).
    val saving = io.github.artcraftandroid.pdfcraft.core.ToolJob()
    val stop = offerCancel(text(R.string.busy_cancel_protect)) { saving.cancel() }
    val started = System.nanoTime()
    val bytes = doc.info.fileSize.toLong()
    var at = "opening"
    fun step(stage: String, n: Int, res: Int): Boolean {
        if (stop.asked) return false
        at = stage
        tools.busy = text(res)
        tools.busyProgress = protectBar(n, 0)
        return true
    }
    var copy: Long? = null
    var outcome = "failed"
    try {
        if (!step("opening", 0, R.string.msg_busy_protect_opening)) { outcome = "stopped"; return@docJob }
        copy = eng.tool("doc_open", mapOf("path" to savedFile(doc, "protect").absolutePath, "password" to doc.password)).optLong("doc")
        Diag.measure(DiagArea.Pages, "protect_copy", "doc" to doc.diagNumber, "settings" to protection.size) {
            if (step("protecting", 1, R.string.msg_busy_protect_protecting)) eng.tool("doc_protect", protection + ("doc" to copy))
            if (step("saving", 2, R.string.msg_busy_protect_saving)) {
                val watching = viewModelScope.launch {
                    while (true) {
                        val p = saving.progress()
                        if (p.steps > 0uL) tools.busyProgress = protectBar(2, p.percent.toInt())
                        kotlinx.coroutines.delay(JOB_WATCH_MS)
                    }
                }
                try {
                    eng.tool("doc_save", mapOf("doc" to copy, "path" to out.absolutePath, "full" to true), saving)
                } catch (e: MobileException) {
                    // stopped inside the save: the engine wrote nothing
                    if (!stop.asked) throw e
                } finally {
                    watching.cancel()
                }
            }
        }
        outcome = if (stop.asked) "stopped" else "finished"
    } finally {
        withdrawCancel()
        tools.busyProgress = null
        copy?.let { c -> runCatching { eng.tool("doc_close", mapOf("doc" to c, "discard_changes" to true)) } }
        if (outcome != "finished") out.delete()
        jobEnd("protect", doc, outcome, started, bytes, doc.pageCount, at, if (outcome == "finished") 3 else listOf("opening", "protecting", "saving").indexOf(at), 3,
            if (outcome == "finished") 100 else saving.progress().percent.toInt())
    }
    if (outcome != "finished") return@docJob
    tools.toolScreen = null
    deliver(out, "${doc.title}_protected.pdf")
}
