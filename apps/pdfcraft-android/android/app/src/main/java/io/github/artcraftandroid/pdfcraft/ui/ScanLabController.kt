package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import android.content.ClipData
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.Diagnostics
import io.github.artcraftandroid.pdfcraft.engine.diagReport
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.home.installedVersionCode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The scanner's testing options and the scans kept for testing (#288), under Settings, About,
 * Diagnostics. [vm] gives the app-wide parts it uses (preferences, messages, the share sheet).
 *
 * Kept scans hold the photographed documents. They stay in the app's private storage and leave
 * it only as a zip the person shares, after a dialog that says what the zip holds.
 */
internal class ScanLabController(private val vm: AppViewModel) {
    private val app: Application get() = vm.getApplication()
    private val store by lazy { ScanTestStore(File(app.filesDir, "scan-tests")) }

    /** The testing options in force. */
    var options by mutableStateOf(ScanLab.decode(vm.settings.appPrefs.getString(PREF, null)))
        private set

    /** What is kept for testing (shown on the Share row). */
    var kept by mutableStateOf(ScanTestStore.Summary(0, 0L))
        private set

    fun update(next: ScanLab) {
        if (next == options) return
        options = next
        vm.settings.appPrefs.edit().putString(PREF, next.encode()).apply()
        Diag.event(DiagArea.Scan, "test_options", *next.diag())
    }

    /** Back to the scanner as it is meant to be used; keeping scans stays as it is. */
    fun reset() = update(ScanLab(keep = options.keep))

    fun refreshKept() {
        vm.viewModelScope.launch { kept = withContext(Dispatchers.IO) { store.summary() } }
    }

    private fun versionCode(): Long = runCatching { installedVersionCode(app) }.getOrDefault(0L)

    /** A folder for a scan about to be kept; null when keeping is off. Blocking. */
    fun begin(): File? = if (options.keep) store.newScan(SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())) else null

    /**
     * Keep page [index] of the scan in [scan]: its photo, its facts and, when it was saved, the
     * page made of it ([output], [result]). Blocking.
     */
    fun keepPage(scan: File, index: Int, page: ScanPage, saved: Boolean, look: String?, strength: Int?, flatten: Boolean, output: File?, result: ScanTestResult?, preview: ScanTestPreview? = null, turn: ScanTestTurn? = null, kind: ScanTestKind? = null, applied: ScanAppliedStrength? = null, shownKept: org.json.JSONObject? = null, discard: ScanDiscard? = null, autoDecisions: org.json.JSONObject? = null) {
        val lab = options
        val version = "${vm.home.appVersion} (${versionCode()})"
        // which models the engine accepted (#289); a loaded model is not hashed again
        val models = io.github.artcraftandroid.pdfcraft.ui.scan.scanModelsJson(runCatching { io.github.artcraftandroid.pdfcraft.core.scanModelStates() }.getOrNull())
        val capture = io.github.artcraftandroid.pdfcraft.ui.scan.ScanCaptures.of(page.file.path)?.json()
        val inspection = if (lab.inspect) inspect(index, page) else null
        store.addPage(scan, index, page.file, output) { keptOutput ->
            scanTestPageJson(version, saved, page.corners, page.found, page.finding, look, strength, flatten, lab, keptOutput, result, preview, page.crop, capture, models, turn, kind, page.book, flattenChosen = page.flatten, pageLook = page.look.json(lab.lookPerPage), inspection = inspection, applied = applied, kindSource = io.github.artcraftandroid.pdfcraft.ui.scan.scanKindSource(page, lab), madeBeforeShown = vm.pageQueue.madeBeforeShown(page), shownKept = shownKept, discard = discard, autoDecisions = autoDecisions)
        }
    }

    /**
     * Run the research detectors (#305 moiré, #306 fingers, glare and stains) on page [index] and
     * log what they see; null when they could not run. Detection only: the page is not changed.
     * Blocking.
     */
    private fun inspect(index: Int, page: ScanPage): org.json.JSONObject? =
        runCatching { io.github.artcraftandroid.pdfcraft.core.scanInspectPage(page.file.absolutePath, page.corners.toList()) }
            .onFailure { e -> Diag.failure(DiagArea.Scan, "scan_inspect_page", e) }
            .getOrNull()
            ?.let { seen ->
                Diag.event(DiagArea.Scan, "inspect", "page" to index, *io.github.artcraftandroid.pdfcraft.ui.scan.scanInspectDiag(seen))
                io.github.artcraftandroid.pdfcraft.ui.scan.scanInspectJson(seen)
            }

    /** The scan in [scan] is complete: older scans make room when too much is kept. Blocking. */
    fun end(scan: File) {
        store.finish(scan)
        store.trim(ScanTestStore.MAX_BYTES)
    }

    /** Keep a scan that is being discarded as it stands: its photos and where their corners were left. Blocking. */
    fun keepDiscarded(pages: List<ScanPage>, asked: Boolean = false) {
        val scan = begin() ?: return
        val discard = ScanDiscard(asked, pages.size)
        pages.forEachIndexed { i, page -> keepPage(scan, i, page, saved = false, look = null, strength = null, flatten = false, output = null, result = null, discard = discard) }
        end(scan)
    }

    /** Hand the kept scans out as one zip, with the diagnostics log in it. */
    fun share() {
        vm.viewModelScope.launch {
            // a folder of its own under share/: the cache sweep removes it an hour after it was handed out
            val file = File(File(app.cacheDir, "share/scan-tests"), "pdfcraft-scan-tests.zip")
            val pages = withContext(Dispatchers.IO) {
                val facts = Diagnostics.facts(app, vm.home.appVersion, versionCode(), io.github.artcraftandroid.pdfcraft.core.engineVersion())
                store.zip(file, mapOf("diagnostics.txt" to diagReport(facts, Diagnostics.lines(app)), "README.txt" to README))
            }
            if (pages == 0) {
                vm.tell(text(R.string.scan_lab_nothing_kept))
                return@launch
            }
            Diag.event(DiagArea.Scan, "tests_shared", "pages" to pages, "bytes" to file.length())
            val uri = androidx.core.content.FileProvider.getUriForFile(app, "${app.packageName}.files", file)
            val send = Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uri).setType("application/zip")
                .putExtra(Intent.EXTRA_SUBJECT, text(R.string.scan_lab_subject)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            send.clipData = ClipData.newRawUri(null, uri)
            vm.intents.trySend(Intent.createChooser(send, null))
        }
    }

    /** A picked model file is being timed (#304). */
    var timingModel by mutableStateOf(false)
        private set

    /**
     * Copy the model file at [uri] into the cache, time it (#304) and log what came back, then
     * delete the copy. Measurement only: the file is never kept or used on a page.
     */
    fun timeModel(uri: android.net.Uri) {
        if (timingModel) return
        timingModel = true
        vm.viewModelScope.launch {
            val file = File(File(app.cacheDir, "model-timing"), "model.onnx")
            val started = android.os.SystemClock.elapsedRealtime()
            try {
                val outcome = withContext(Dispatchers.IO) {
                    try {
                        copyCapped(uri, file)
                    } catch (e: java.io.IOException) {
                        // the copy into the app's folder failed (#445): too large, or the file could not be read
                        throw io.github.artcraftandroid.pdfcraft.ui.scan.ScanModelTimingFailed(io.github.artcraftandroid.pdfcraft.ui.scan.scanModelCopyReason(e), "copy", e.userMessage, -1)
                    }
                    // before the run: if a large model gets the app closed, this line is the last one
                    Diag.event(DiagArea.Scan, "model_timing_start", "bytes" to file.length())
                    Diag.sink?.flush()
                    io.github.artcraftandroid.pdfcraft.core.scanTimeModelOutcome(file.path, MODEL_RUNS, 0u)
                }
                val t = outcome.timing ?: throw io.github.artcraftandroid.pdfcraft.ui.scan.ScanModelTimingFailed(outcome.reason, "time", outcome.message, outcome.ms.toLong())
                Diag.event(DiagArea.Scan, "model_timing", *io.github.artcraftandroid.pdfcraft.ui.scan.scanModelTimingDiag(t))
                vm.tell(
                    text(
                        R.string.scan_lab_time_model_done, t.loadMs.toInt(),
                        io.github.artcraftandroid.pdfcraft.ui.scan.scanModelTimingRuns(t), io.github.artcraftandroid.pdfcraft.ui.scan.scanModelTimingPeak(t),
                    ),
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: io.github.artcraftandroid.pdfcraft.ui.scan.ScanModelTimingFailed) {
                // why it failed and how long it took, as words and numbers the log can carry (#445)
                Diag.event(DiagArea.Scan, "model_timing_failed", *io.github.artcraftandroid.pdfcraft.ui.scan.scanModelTimingFailedDiag(e, android.os.SystemClock.elapsedRealtime() - started))
                vm.tell(text(R.string.scan_lab_time_model_failed, e.shown))
            } catch (e: Exception) {
                Diag.failure(DiagArea.Scan, "scan_time_model", e)
                vm.tell(text(R.string.scan_lab_time_model_failed, e.userMessage))
            } finally {
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.IO) { file.delete() }
                timingModel = false
            }
        }
    }

    /** [uri] into [to], refused past [MODEL_MAX_BYTES] so a wrong pick does not fill the storage. */
    private fun copyCapped(uri: android.net.Uri, to: File) {
        to.parentFile?.mkdirs()
        val input = app.contentResolver.openInputStream(uri) ?: throw java.io.IOException("unreadable")
        input.use { from ->
            to.outputStream().use { out ->
                val buf = ByteArray(1 shl 16)
                var total = 0L
                while (true) {
                    val n = from.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MODEL_MAX_BYTES) throw java.io.IOException(io.github.artcraftandroid.pdfcraft.ui.scan.SCAN_MODEL_COPY_TOO_LARGE)
                    out.write(buf, 0, n)
                }
            }
        }
    }

    fun clear() {
        vm.viewModelScope.launch {
            withContext(Dispatchers.IO) { store.clear() }
            kept = ScanTestStore.Summary(0, 0L)
            Diag.event(DiagArea.Scan, "tests_cleared")
        }
    }

    private companion object {
        const val PREF = "scanLab"

        /** Runs per timing: the first warms the runtime up, the second is the one to compare. */
        const val MODEL_RUNS = 2u

        /** The largest model file copied (the native side refuses the same). */
        const val MODEL_MAX_BYTES = 600L * 1024 * 1024

        // for whoever opens the zip; not shown in the app
        val README = """
            PdfCraft scans kept for testing.

            One folder per scan, named for when it was saved or discarded. For each page:
              page-NN.jpg       the photo as the camera wrote it
              page-NN.json      the page's corners as they were left (8 shares of the upright photo: top-left,
                                top-right, bottom-right, bottom-left), what the app found by itself, the look and
                                testing options in force, the size of the page it made, and how the photo was
                                taken (capture: which camera, still size, exposure, light, focus, time); with the
                                detectors' report on, what they saw (inspect: moire, fingers, glare, stains)
              page-NN.out.jpg   the page as the app made it (.png for black and white); saved scans only
            diagnostics.txt     the diagnostics log at the time of sharing

            These files contain the scanned documents.
        """.trimIndent() + "\n"
    }
}
