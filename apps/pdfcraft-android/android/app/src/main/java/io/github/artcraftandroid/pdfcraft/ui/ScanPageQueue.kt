package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.ui.scan.scanApplyAutoLook
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/*
 * Every page of a scan made in the background (#465). When a page joins the scan it waits in a
 * queue and is made off the main thread, one page at a time, by the same request the scan
 * screen's preview uses. The result at preview size is written to the scan's cache folder (so
 * memory holds only small thumbnails), the pages strip shows it, and going back to the page shows
 * it at once. The page on screen goes first: its own preview makes it, and the queue gives way.
 * Testing option "Make every page in the background" (`backgroundPages`): off is the scan screen
 * as before, where only the page on screen was made.
 */

/**
 * A page made in the background or by the screen's preview (#465): what it was made from ([key]),
 * where the result is kept, its thumbnail for the pages strip, and what the page showed: its turn,
 * whether it was flattened and the kind of document it was taken for, which Save then keeps.
 */
internal class ScanMadePage(
    val key: ScanPreviewKey,
    val file: File,
    val thumb: ImageBitmap,
    /** The quarter turns clockwise the classifier gave the page (#339). */
    val quarterTurns: Int,
    /** The classifier's turn and the person's together, 0–3: the page's turn as shown. */
    val turns: Int,
    val flattened: Boolean,
    val kind: io.github.artcraftandroid.pdfcraft.core.ScanPageKind?,
    val orientation: io.github.artcraftandroid.pdfcraft.core.ScanOrientation?,
)

/**
 * Why a page is made again (#465), for the log: `new` (never made), `look` (its look, strength or
 * paper changed), `apply_all` (Apply to all changed it), `options` (a testing option or the
 * automatic-turn setting changed), or `borders` (its corners, turn, document type or Flatten page).
 */
internal fun scanQueueWhy(old: ScanPreviewKey?, now: ScanPreviewKey, applyAll: Boolean): String = when {
    old == null -> "new"
    applyAll -> "apply_all"
    now.samePage(old) -> "look"
    old.copy(lab = now.lab, autoUpright = now.autoUpright) == now -> "options"
    else -> "borders"
}

/** The page the queue makes next (#465): the one on screen when it waits, else the first waiting; null for none. */
internal fun scanQueueNext(waiting: List<Int>, shown: Int): Int? = if (shown in waiting) shown else waiting.minOrNull()

/** The scan screen's line while pages wait (#465): [ready] of [total]; null when every page is ready or there is none. */
internal fun scanPreparingCount(ready: Int, total: Int): Pair<Int, Int>? = if (total > 0 && ready < total) ready to total else null

/** A thumbnail's mark (#465): none when its page is ready, else waiting or being made. */
internal enum class ScanPageMark { Ready, Waiting, Working }

internal fun scanPageMark(ready: Boolean, working: Boolean): ScanPageMark = when {
    ready -> ScanPageMark.Ready
    working -> ScanPageMark.Working
    else -> ScanPageMark.Waiting
}

/**
 * Whether a page was made before it was first shown (#465), for a kept scan's page file: what
 * was recorded when it was first shown, else whether it was made at all (a page saved without
 * being looked at); null with the testing option off.
 */
internal fun scanMadeBeforeShown(on: Boolean, atFirstShow: Boolean?, made: Boolean): Boolean? = when {
    !on -> null
    atFirstShow != null -> atFirstShow
    else -> made
}

/** The queue of pages made in the background (#465), kept apart from [AppViewModel]. */
internal class ScanPageQueue(private val vm: AppViewModel) {
    /** Each page's latest result, by [ScanPage.key]; out of date when its key is not the page's now. */
    val made = mutableStateMapOf<String, ScanMadePage>()

    /** The page being made now ([ScanPage.key]), for its thumbnail's mark. */
    var working by mutableStateOf<String?>(null)
        private set

    /** The page on screen: it goes first. */
    var shownIndex = 0

    /** Photos whose edges are still being looked for: their pages wait until that is done. */
    private val finding = HashSet<String>()

    /** When each waiting page started to wait, and why (for `page_ready`). */
    private val waitingSince = HashMap<String, Long>()
    private val waitingWhy = HashMap<String, String>()

    /** Pages that Apply to all has just changed. */
    private val appliedAll = HashSet<String>()

    /** Whether each page had a result when it was first shown. */
    private val shownMade = HashMap<String, Boolean>()

    /** When the pages now waiting began to (for `scan_ready`); null while every page is ready. */
    private var batchStarted: Long? = null
    private var worker: Job? = null
    private var stop: io.github.artcraftandroid.pdfcraft.core.ScanCancel? = null

    private fun on(): Boolean = vm.scanLab.options.let { it.backgroundPages && it.preview }

    private fun dir(): File = File(vm.getApplication<android.app.Application>().cacheDir, "scan-made")

    /** What [page] would be made from now: the same key as its preview. */
    fun keyOf(page: ScanPage): ScanPreviewKey {
        val lab = vm.scanLab.options
        val autoUpright = io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting.get(vm.settings.appPrefs)
        return ScanPreviewKey.of(page, io.github.artcraftandroid.pdfcraft.ui.scan.scanPageFlattens(page.flatten, vm.scanCanFlatten), lab, autoUpright, autoFlatten = vm.scanAutoFlattens(page, lab))
    }

    /** The result of [page] when it is up to date. */
    fun readyOf(page: ScanPage): ScanMadePage? = made[page.key]?.takeIf { it.key == keyOf(page) }

    /** The strip's thumbnail of [page]: its latest result (the last one while it is made again), or null for the photo. */
    fun thumbOf(page: ScanPage): ImageBitmap? = if (on()) made[page.key]?.thumb else null

    /** [page]'s thumbnail mark, or [ScanPageMark.Ready] with the option off. */
    fun mark(page: ScanPage): ScanPageMark = if (!on()) ScanPageMark.Ready else scanPageMark(readyOf(page) != null, working == page.key)

    /** "Preparing pages: n of m", or null. */
    fun preparing(pages: List<ScanPage>): Pair<Int, Int>? = if (!on()) null else scanPreparingCount(pages.count { readyOf(it) != null }, pages.size)

    fun findingStarted(photo: File) { finding += photo.path }

    fun findingDone(photo: File) {
        finding -= photo.path
        kick()
    }

    /** Apply to all changed these pages. */
    fun appliedToAll(keys: Collection<String>) { appliedAll += keys }

    /** [page] is on screen; whether it had a result already is recorded the first time. */
    fun shown(page: ScanPage) {
        if (on() && page.key !in shownMade) shownMade[page.key] = readyOf(page) != null
    }

    fun madeBeforeShown(page: ScanPage): Boolean? = scanMadeBeforeShown(on(), shownMade[page.key], made[page.key] != null)

    /** Note when a page started to wait and why; the first page to wait starts the batch. */
    private fun noteWaiting(page: ScanPage, key: ScanPreviewKey) {
        if (page.key in waitingSince) return
        waitingSince[page.key] = android.os.SystemClock.elapsedRealtime()
        waitingWhy[page.key] = scanQueueWhy(made[page.key]?.key, key, page.key in appliedAll)
        appliedAll -= page.key
        if (batchStarted == null) batchStarted = android.os.SystemClock.elapsedRealtime()
    }

    /** Start the queue if pages wait and it is not running. Main thread. */
    fun kick() {
        if (!on() || worker?.isActive == true) return
        worker = vm.viewModelScope.launch { run() }
    }

    private suspend fun run() {
        while (on()) {
            val pages = vm.scanPages.toList()
            // results of pages no longer in the scan go
            val keys = pages.mapTo(HashSet()) { it.key }
            made.keys.filter { it !in keys }.forEach { made.remove(it)?.file?.delete() }
            val wanted = vm.scanPreviewWantedKey()
            val waiting = ArrayList<Int>()
            for ((i, p) in pages.withIndex()) {
                if (p.file.path in finding) continue
                val key = keyOf(p)
                if (made[p.key]?.key == key || failed[p.key] == key) continue
                noteWaiting(p, key)
                // the screen's preview is making this one: it is stored when it comes
                if (key != wanted) waiting += i
            }
            val next = scanQueueNext(waiting, shownIndex) ?: break
            val page = pages[next]
            val key = keyOf(page)
            working = page.key
            val cancel = io.github.artcraftandroid.pdfcraft.core.ScanCancel()
            stop = cancel
            val started = android.os.SystemClock.elapsedRealtime()
            val result = withContext(vm.scanPreviewDispatcher) {
                runCatching { io.github.artcraftandroid.pdfcraft.core.scanPreviewPageWith(vm.scanPreviewRequest(page, key.flatten, key.lab, key.autoUpright, key.autoFlatten), SCAN_PREVIEW_SIDE.toUInt(), cancel) }
            }
            working = null
            // stopped for the page on screen, whose preview goes first: this page is asked again
            if (cancel.isCancelled()) continue
            val p = result.onFailure { Diag.failure(DiagArea.Scan, "scan_page_queue", it) }.getOrNull()
            if (p == null) {
                // a page that can't be made is not tried again until it changes
                made.remove(page.key)
                waitingSince.remove(page.key)
                Diag.event(DiagArea.Scan, "page_ready", "index" to next + 1, "made" to false, "ms" to android.os.SystemClock.elapsedRealtime() - started)
                markFailed(page.key, key)
                continue
            }
            store(next, page, key, p, null, android.os.SystemClock.elapsedRealtime() - started)
            // a receipt becomes Grayscale by itself, without being opened (#466)
            vm.scanApplyAutoLook(page.key, p.kind?.suggestGreyscale == true)
        }
    }

    /** Pages that could not be made, by key: not asked again until they change. */
    private val failed = HashMap<String, ScanPreviewKey>()

    private fun markFailed(pageKey: String, key: ScanPreviewKey) { failed[pageKey] = key }

    /** The screen's preview of [page] from [key] failed: the queue does not try it again until it changes. */
    fun failedOnScreen(page: ScanPage, key: ScanPreviewKey) {
        if (!on()) return
        markFailed(page.key, key)
        waitingSince.remove(page.key)
        readyCheck()
    }

    /**
     * The screen asks for [pageKey]'s preview: a page the queue is making for another page now is
     * stopped at its next stage, so the page on screen goes first (#465). Its straightened page
     * is kept natively when it got that far, and it is made again afterwards.
     */
    fun yieldTo(pageKey: String) {
        if (on() && working != null && working != pageKey) stop?.cancel()
    }

    /**
     * Keep [p], made for [page] (page [index]) from [key]: written at preview size to the cache
     * folder, its thumbnail for the strip, and a `page_ready` line. [bitmap] is the preview's own
     * when the screen made it. Main thread.
     */
    suspend fun store(index: Int, page: ScanPage, key: ScanPreviewKey, p: io.github.artcraftandroid.pdfcraft.core.ScanPreview, bitmap: android.graphics.Bitmap?, ms: Long) {
        if (!on()) return
        val density = vm.getApplication<android.app.Application>().resources.displayMetrics.density
        val thumbSide = (SCAN_QUEUE_THUMB_DP * density).toInt().coerceAtLeast(48)
        val out = File(dir(), "page-${System.nanoTime()}.jpg")
        val thumb = withContext(Dispatchers.IO) {
            runCatching {
                val full = bitmap ?: vm.scanPreviewBitmap(p) ?: return@runCatching null
                dir().mkdirs()
                out.outputStream().use { full.compress(android.graphics.Bitmap.CompressFormat.JPEG, SCAN_QUEUE_JPEG_QUALITY, it) }
                val k = thumbSide.toFloat() / maxOf(full.width, full.height)
                android.graphics.Bitmap.createScaledBitmap(full, maxOf(1, (full.width * k).toInt()), maxOf(1, (full.height * k).toInt()), true)
            }.getOrNull()
        } ?: return
        // the page may have gone or changed while it was made
        if (vm.scanPages.none { it.key == page.key }) {
            out.delete()
            return
        }
        made.put(page.key, ScanMadePage(key, out, thumb.asImageBitmap(), p.quarterTurns.toInt(), p.turns.toInt(), scanCameOutFlat(p), p.kind, p.orientation))?.file?.takeIf { it != out }?.delete()
        val now = android.os.SystemClock.elapsedRealtime()
        val since = waitingSince.remove(page.key)
        val why = waitingWhy.remove(page.key) ?: scanQueueWhy(null, key, false)
        Diag.event(
            DiagArea.Scan, "page_ready", "index" to index + 1, "made" to true, "ms" to ms,
            "waited_ms" to ((since?.let { now - it - ms } ?: 0L).coerceAtLeast(0L)), "why" to why,
            "by" to (if (bitmap != null) "screen" else "queue"), "reused" to p.reused,
            "quarter_turns" to p.quarterTurns.toInt(), "flattened" to p.flattened, "preset" to (p.kind?.preset ?: "none"),
        )
        readyCheck()
    }

    /** When no page waits any more, one `scan_ready` line for the batch. */
    private fun readyCheck() {
        val started = batchStarted ?: return
        val pages = vm.scanPages.toList()
        if (pages.any { it.file.path in finding || (readyOf(it) == null && failed[it.key] != keyOf(it)) }) return
        batchStarted = null
        Diag.event(DiagArea.Scan, "scan_ready", "pages" to pages.size, "ms" to android.os.SystemClock.elapsedRealtime() - started)
    }

    /** Decode [page]'s kept result for the screen; null when it is not up to date or can't be read. */
    suspend fun shownImage(page: ScanPage, key: ScanPreviewKey): Pair<ScanMadePage, ImageBitmap>? {
        if (!on()) return null
        val m = made[page.key]?.takeIf { it.key == key } ?: return null
        val image = withContext(Dispatchers.IO) { runCatching { android.graphics.BitmapFactory.decodeFile(m.file.path)?.asImageBitmap() }.getOrNull() } ?: return null
        return m to image
    }

    /** The scan is over: stop, and forget every result. */
    fun clear() {
        stop?.cancel()
        worker?.cancel()
        worker = null
        working = null
        made.clear()
        finding.clear()
        waitingSince.clear()
        waitingWhy.clear()
        appliedAll.clear()
        shownMade.clear()
        failed.clear()
        batchStarted = null
        dir().listFiles()?.forEach { it.delete() }
    }
}

/** Whether a preview came out flattened (#466): by the network, or from its lines of text (#303). */
internal fun scanCameOutFlat(p: io.github.artcraftandroid.pdfcraft.core.ScanPreview): Boolean = p.flattened || p.lineFlatten?.check?.kept == true

/** The longer side, in dp, of a page's thumbnail made by the queue (#465): the strip's 72 dp. */
internal const val SCAN_QUEUE_THUMB_DP = 72

/** JPEG quality of a result kept in the cache folder (#465): it is shown at preview size only. */
internal const val SCAN_QUEUE_JPEG_QUALITY = 90
