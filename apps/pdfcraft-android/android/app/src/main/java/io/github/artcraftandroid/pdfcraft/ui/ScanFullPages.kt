package io.github.artcraftandroid.pdfcraft.ui

import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Every page of a scan made at full size before Save (#504). Once a page's preview is ready
 * (#465), and while no preview is being made and the camera is closed, the page is made by the
 * native request Save would make for it, one page at a time, the page on screen first. The result
 * is kept in the cache folder until the scan ends. Save, Share and Save to gallery take a page
 * whose request is unchanged and make only the others. A change to a page (its borders, look,
 * turn, marks or a testing option) changes its request, so it is made again.
 * Testing option "Make full-size pages before Save" (`fullPagesAhead`): off is Save as before.
 */

/** A page made at full size: the request it was made from (with no output path) and what came out. */
internal class ScanFullPage(
    val request: io.github.artcraftandroid.pdfcraft.core.ScanPageRequest,
    val previewKey: ScanPreviewKey,
    val result: io.github.artcraftandroid.pdfcraft.core.ScanPageResult,
)

/** The request with no output path: two requests that agree on this make the same page. */
internal fun io.github.artcraftandroid.pdfcraft.core.ScanPageRequest.withoutOutput() = copy(outputPath = "")

/** The pages made at full size ahead of Save (#504), kept apart from [AppViewModel]. */
internal class ScanFullPages(private val vm: AppViewModel) {
    /** Each page's latest full-size result, by [ScanPage.key]. Main thread. */
    private val made = HashMap<String, ScanFullPage>()

    /** Pages that could not be made, by key and request: not asked again until they change. */
    private val failed = HashMap<String, io.github.artcraftandroid.pdfcraft.core.ScanPageRequest>()

    /** Held by the pass while it makes a page, and by Save, Share and Save to gallery while they run. */
    private val lock = Mutex()

    /** Save, Share or Save to gallery is running: the pass waits. */
    private var held = false

    /** The camera is open: the pass waits, so the camera has the phone to itself. */
    private var cameraOpen = false

    private var worker: Job? = null

    private fun on(): Boolean = vm.scanLab.options.let { it.fullPagesAhead && it.backgroundPages && it.preview }

    private fun dir(): File = File(vm.getApplication<android.app.Application>().cacheDir, "scan-full")

    /** A new file name for a page made at full size. */
    fun newFile(): File = File(dir().apply { mkdirs() }, "page-${System.nanoTime()}")

    /** The camera opened or closed. Main thread. */
    fun camera(open: Boolean) {
        if (open == cameraOpen) return
        cameraOpen = open
        if (!open) kick()
    }

    /** [page]'s full-size result when it was made from [request] (its output path aside); null otherwise. */
    fun take(page: ScanPage, request: io.github.artcraftandroid.pdfcraft.core.ScanPageRequest): io.github.artcraftandroid.pdfcraft.core.ScanPageResult? =
        made[page.key]?.takeIf { it.request == request.withoutOutput() && File(it.result.path).isFile }?.result

    /**
     * Keep [result], made for [page] from [request], in place of the page's earlier one. With [why]
     * (a page made ahead of Save) one `full_page_ready` line goes to the log for page [index].
     */
    fun keep(index: Int, page: ScanPage, request: io.github.artcraftandroid.pdfcraft.core.ScanPageRequest, result: io.github.artcraftandroid.pdfcraft.core.ScanPageResult, ms: Long, why: String?) {
        val old = made.put(page.key, ScanFullPage(request.withoutOutput(), vm.pageQueue.keyOf(page), result))
        old?.result?.path?.takeIf { it != result.path }?.let { File(it).delete() }
        failed.remove(page.key)
        if (why != null) {
            Diag.event(DiagArea.Scan, "full_page_ready", *io.github.artcraftandroid.pdfcraft.ui.scan.scanFullReadyDiag(index, ms, File(result.path).length(), why))
        }
    }

    /**
     * Run [block] with the pass stopped: a page it is making now is finished first (and kept), and
     * no other is started until [block] is done. Main thread.
     */
    suspend fun <T> exclusive(block: suspend () -> T): T {
        held = true
        try {
            return lock.withLock { block() }
        } finally {
            held = false
            kick()
        }
    }

    /** Start the pass if it is not running. Main thread. */
    fun kick() {
        if (!on() || held || cameraOpen || worker?.isActive == true) return
        worker = vm.viewModelScope.launch { run() }
    }

    private suspend fun run() {
        while (on() && !held && !cameraOpen) {
            // previews first: the page on screen and the pages strip are what the person sees
            if (!vm.pageQueue.idle() || vm.scanPreviewRunning()) {
                delay(WAIT_MS)
                continue
            }
            val pages = vm.scanPages.toList()
            if (pages.isEmpty()) break
            // results of pages no longer in the scan go
            val keys = pages.mapTo(HashSet()) { it.key }
            made.keys.filter { it !in keys }.forEach { made.remove(it)?.result?.path?.let { p -> File(p).delete() } }
            val lab = vm.scanLab.options
            val autoUpright = io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting.get(vm.settings.appPrefs)
            val wanted = HashMap<Int, io.github.artcraftandroid.pdfcraft.core.ScanPageRequest>()
            for ((i, p) in pages.withIndex()) {
                // made only from what the page shows: its preview must be ready
                if (vm.pageQueue.readyOf(p) == null) continue
                val shown = vm.scanShownOf(p, lab)
                val request = vm.scanSaveRequest(p, "", p.turns ?: shown?.turns, shown, lab, autoUpright)
                if (made[p.key]?.request == request || failed[p.key] == request) continue
                wanted[i] = request
            }
            val next = scanQueueNext(wanted.keys.toList(), vm.pageQueue.shownIndex) ?: break
            val page = pages[next]
            val out = newFile()
            val request = wanted.getValue(next).copy(outputPath = out.absolutePath)
            val previewKey = vm.pageQueue.keyOf(page)
            val why = io.github.artcraftandroid.pdfcraft.ui.scan.scanFullWhy(made[page.key]?.previewKey, previewKey)
            val started = android.os.SystemClock.elapsedRealtime()
            val result = lock.withLock {
                // Save came first: it makes what it needs itself
                if (held) return
                withContext(Dispatchers.Default) { runCatching { io.github.artcraftandroid.pdfcraft.core.scanProcessPage(request) } }
            }
            val ms = android.os.SystemClock.elapsedRealtime() - started
            val r = result.onFailure { Diag.failure(DiagArea.Scan, "scan_full_page", it) }.getOrNull()
            when {
                r == null -> {
                    failed[page.key] = request.withoutOutput()
                    out.delete()
                }
                // the page went, or changed while it was made: the next round sees it
                vm.scanPages.none { it.key == page.key } -> File(r.path).delete()
                else -> keep(next, page, request, r, ms, why)
            }
        }
    }

    /** The scan is over: stop, and forget every page. */
    fun clear() {
        worker?.cancel()
        worker = null
        made.clear()
        failed.clear()
        dir().listFiles()?.forEach { it.delete() }
    }

    private companion object {
        /** How long the pass waits before it looks again while previews are being made. */
        const val WAIT_MS = 300L
    }
}
