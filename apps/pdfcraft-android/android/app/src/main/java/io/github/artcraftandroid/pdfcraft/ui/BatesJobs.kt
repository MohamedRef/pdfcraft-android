package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.DocumentFiles
import io.github.artcraftandroid.pdfcraft.engine.Engine
import io.github.artcraftandroid.pdfcraft.engine.SafBoundedCopy
import io.github.artcraftandroid.pdfcraft.engine.withTemporaryBookmarkSource
import io.github.artcraftandroid.pdfcraft.text
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/*
 * Bates numbering over several PDFs (#730: moved out of AppViewModel.kt as they were). The state they
 * show stays in [AppViewModel] (`batesFiles`, `batesRunning`, …); these are its actions.
 */

internal fun AppViewModel.openBates() {
    if (batesRunning) return
    batesEpoch++
    batesPendingPick = null
    batesSelectionLoading = false
    batesFiles.clear()
    batesResults = emptyList()
    batesStatus = ""
    tools.toolScreen = ToolScreen.Bates
}

internal fun AppViewModel.closeBates() {
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

internal fun AppViewModel.pickBatesFiles() {
    if (batesRunning || batesSelectionLoading || tools.toolScreen != ToolScreen.Bates || batesPendingPick != null) return
    batesPendingPick = batesEpoch
    if (!batesPicks.trySend(Unit).isSuccess) batesPendingPick = null
}

internal fun AppViewModel.onBatesFilesPicked(uris: List<Uri>) {
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
        catch (_: Exception) { fail(text(R.string.bates_failed)) }
        finally { if (epoch == batesEpoch) batesSelectionLoading = false }
    }
}

internal fun AppViewModel.moveBatesFile(index: Int, delta: Int) {
    val target = index + delta
    if (!batesRunning && !batesSelectionLoading && index in batesFiles.indices && target in batesFiles.indices) {
        val item = batesFiles.removeAt(index)
        batesFiles.add(target, item)
        batesResults = emptyList()
    }
}

internal fun AppViewModel.removeBatesFile(index: Int) {
    if (!batesRunning && !batesSelectionLoading && index in batesFiles.indices) { batesFiles.removeAt(index); batesResults = emptyList() }
}

internal fun AppViewModel.runBates(start: String, digits: String, prefix: String, suffix: String) {
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
