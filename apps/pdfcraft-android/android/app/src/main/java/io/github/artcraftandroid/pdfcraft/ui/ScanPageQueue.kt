package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
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
 *
 * A page that is not made yet gets a first picture at once (#785): straightened by its corners in
 * its look from the photo's thumbnail, in about a tenth of a second, without flattening, the
 * classifier's turn, the document type or the finger search. It is shown where the page will be
 * (the strips, the document screen's tile, the camera's last page, the scan screen) until the page
 * itself is made, and is never the page: nothing is saved from it and no page counts as ready by
 * it. Testing option "Show each page at once in a quick version" (`firstPicture`): off is as before.
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
    /** What removing fingers did to the page (#466); null when it did not run. */
    val fingers: io.github.artcraftandroid.pdfcraft.core.ScanFingers? = null,
    /** The paper the page was matched to (#759): the scan's own paper is worked out from these. */
    val paper: io.github.artcraftandroid.pdfcraft.core.ScanPaper? = null,
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

/** How many pages the queue makes at once (#549): two with the testing option on, else one as before. */
internal fun scanQueueLanes(parallel: Boolean): Int = if (parallel) 2 else 1

/** The `scan_ready` line's fields (#465, #549): the pages, the time, the option and the most pages made at once. */
internal fun scanReadyDiag(pages: Int, ms: Long, parallel: Boolean, mostAtOnce: Int, first: ScanFirstBatch? = null): Array<Pair<String, Any?>> = arrayOf(
    "pages" to pages, "ms" to ms, "parallel" to parallel, "most_at_once" to mostAtOnce,
    // the first pictures of the batch (#785): how many were made and when the last one was up; -1 when none was
    "first_pictures" to (first?.made ?: 0), "first_ms" to (first?.ms ?: -1L),
)

/** The first pictures made while a batch of pages waited (#785): how many, and the time from the batch's start to the last one. */
internal data class ScanFirstBatch(val made: Int, val ms: Long)

/** A page's last first picture for a kept scan's page file (#785): whether it was made, the time, and what it was made from. */
internal data class ScanFirstRecord(val made: Boolean, val ms: Long, val fromCopy: Boolean, val width: Int, val height: Int, val turnKnown: Boolean) {
    fun json(): org.json.JSONObject = org.json.JSONObject().put("made", made).put("ms", ms).put("from_copy", fromCopy).put("width", width).put("height", height).put("turn_known", turnKnown)
}

/**
 * Whether a page needs a first picture (#785): it is not made from [key] yet, has no first picture
 * from [key], and making one from [key] did not fail. A first picture is only ever of the page as
 * it is now: one from another key (other corners, look, turn or options) is out of date.
 */
internal fun <K> scanNeedsFirst(key: K, made: K?, first: K?, failed: K?): Boolean = made != key && first != key && failed != key

/**
 * The time from when a page began to wait to its first picture (#785), for `page_ready`: -1 when
 * it had none, 0 when the picture was up before the wait was noted.
 */
internal fun scanFirstMs(waitingSince: Long?, firstAt: Long?): Long = if (waitingSince == null || firstAt == null) -1L else (firstAt - waitingSince).coerceAtLeast(0L)

/** The `page_first` line's fields (#785): the page, whether its first picture was made, the time, where it came from and its size. */
internal fun scanFirstDiag(index: Int, made: Boolean, ms: Long, p: io.github.artcraftandroid.pdfcraft.core.ScanFirstPage?, turnKnown: Boolean): Array<Pair<String, Any?>> = arrayOf(
    "index" to index + 1, "made" to made, "ms" to ms,
    // from the photo's thumbnail (no decoding), or the photo decoded because the thumbnail could not be used
    "from_copy" to (p?.fromSmall ?: false),
    "width" to (p?.width?.toLong() ?: 0L), "height" to (p?.height?.toLong() ?: 0L), "turns" to (p?.turns?.toInt() ?: 0),
    // the turn was the one the page was last made with, not the person's own
    "turn_known" to turnKnown,
    *(p?.timings.orEmpty().map<io.github.artcraftandroid.pdfcraft.core.ScanTiming, Pair<String, Any?>> { it.stage.removePrefix("scan_") + "_ms" to it.ms.toLong() }.toTypedArray()),
)

/** [thumb] as the engine takes a small copy of the photo (#785); null when its pixels are not four bytes each. */
internal fun scanSmallPhoto(thumb: ImageBitmap): io.github.artcraftandroid.pdfcraft.core.ScanSmallPhoto? = runCatching {
    val bmp = thumb.asAndroidBitmap()
    if (bmp.config != android.graphics.Bitmap.Config.ARGB_8888 || bmp.byteCount != bmp.width * bmp.height * 4) return@runCatching null
    val buffer = java.nio.ByteBuffer.allocate(bmp.byteCount)
    // ARGB_8888 keeps its bytes in R, G, B, A order: what the engine reads
    bmp.copyPixelsToBuffer(buffer)
    io.github.artcraftandroid.pdfcraft.core.ScanSmallPhoto(width = bmp.width.toUInt(), height = bmp.height.toUInt(), rgba = buffer.array())
}.getOrNull()

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

    /** The pages being made now ([ScanPage.key]), for their thumbnails' marks, each with its stop (#549: up to two). */
    private val working = mutableStateMapOf<String, io.github.artcraftandroid.pdfcraft.core.ScanCancel>()

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
    /** The most pages made at once in this batch (#549). */
    private var batchMostAtOnce = 0
    private var worker: Job? = null
    /** A page the queue was making is done (or stopped): the loop looks again. */
    private val finished = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)

    private fun on(): Boolean = vm.scanLab.options.let { it.backgroundPages && it.preview }

    /** Each page's first picture (#785), by [ScanPage.key]: shown until the page is made from the same key, never taken for the page. */
    private val first = mutableStateMapOf<String, ScanMadePage>()

    /** Pages whose first picture could not be made, by key: not asked again until they change. */
    private val firstFailed = HashMap<String, ScanPreviewKey>()

    /** When each waiting page's first picture was up (for `page_ready`). */
    private val firstAt = HashMap<String, Long>()

    /** Each page's last first picture, for a kept scan's page file. */
    private val firstRecords = HashMap<String, ScanFirstRecord>()

    /** [page]'s last first picture for its page file (#785); null when none was asked. */
    fun firstRecord(page: ScanPage): ScanFirstRecord? = firstRecords[page.key]

    /** The first pictures made in this batch, and when the last one was up (for `scan_ready`). */
    private var batchFirstMade = 0
    private var batchFirstAt: Long? = null
    private var firstWorker: Job? = null

    private fun firstOn(): Boolean = on() && vm.scanLab.options.firstPicture

    /**
     * [page]'s first picture (#785) while the page itself is not made: of the page as it is now
     * (the same key), else null. Once the page is made from that key, its result is shown instead.
     */
    fun firstOf(page: ScanPage): ScanMadePage? {
        if (!firstOn()) return null
        val f = first[page.key] ?: return null
        val key = keyOf(page)
        return f.takeIf { it.key == key && made[page.key]?.key != key }
    }

    private fun dir(): File = File(vm.getApplication<android.app.Application>().cacheDir, "scan-made")

    /** What [page] would be made from now: the same key as its preview. */
    fun keyOf(page: ScanPage): ScanPreviewKey {
        val lab = vm.scanLab.options
        val autoUpright = io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting.get(vm.settings.appPrefs)
        return ScanPreviewKey.of(page, io.github.artcraftandroid.pdfcraft.ui.scan.scanPageFlattens(page.flatten, vm.scanCanFlatten), lab, autoUpright, autoFlatten = vm.scanAutoFlattens(page, lab), paperHint = vm.scanPaperHint(page, lab), cardPaper = vm.scanCardPaper(page, lab))
    }

    /** The result of [page] when it is up to date. */
    fun readyOf(page: ScanPage): ScanMadePage? = made[page.key]?.takeIf { it.key == keyOf(page) }

    /** The paper [page] was last made at (#759), also while it is made again; null before it is made. */
    fun paperOf(page: ScanPage): io.github.artcraftandroid.pdfcraft.core.ScanPaper? = made[page.key]?.paper

    /** The strip's thumbnail of [page]: its latest result (the last one while it is made again), or null for the photo. */
    fun thumbOf(page: ScanPage): ImageBitmap? = if (on()) firstOf(page)?.thumb ?: made[page.key]?.thumb else null

    /**
     * What the document screen's tile of [page] draws (#494, #785): its first picture while the
     * page is not made as it is now, else its latest result; null for the photo.
     */
    fun tileOf(page: ScanPage): ScanMadePage? = if (on()) firstOf(page) ?: made[page.key] else null

    /**
     * [page]'s latest result for the document screen's tile (#494): the last one while it is made
     * again, null with the option off or before it was first made (the tile then shows the photo).
     */
    fun madeOf(page: ScanPage): ScanMadePage? = if (on()) made[page.key] else null

    /**
     * The document screen's tiles, read from the kept results at the tile's size (#494). Only tiles
     * on screen ask; at most [SCAN_TILE_CACHE_BYTES] are kept, so a 100-page scan costs no more
     * memory than a screenful. Keyed by the result's file, which is new each time a page is made.
     */
    private val tiles = object : android.util.LruCache<String, ImageBitmap>(SCAN_TILE_CACHE_BYTES) {
        override fun sizeOf(key: String, value: ImageBitmap): Int = value.width * value.height * 4
    }

    /** [m] at about [widthPx] × [heightPx] for a tile; null when it can't be read. */
    suspend fun tileImage(m: ScanMadePage, widthPx: Int, heightPx: Int): ImageBitmap? {
        val key = "${m.file.path}@${widthPx}x$heightPx"
        tiles.get(key)?.let { return it }
        val image = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(m.file.path, bounds)
                val opts = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = io.github.artcraftandroid.pdfcraft.ui.scan.scanTileSampleSize(bounds.outWidth, bounds.outHeight, widthPx, heightPx)
                }
                android.graphics.BitmapFactory.decodeFile(m.file.path, opts)?.asImageBitmap()
            }.getOrNull()
        } ?: return null
        tiles.put(key, image)
        return image
    }

    /** [page]'s thumbnail mark, or [ScanPageMark.Ready] with the option off. */
    fun mark(page: ScanPage): ScanPageMark = if (!on()) ScanPageMark.Ready else scanPageMark(readyOf(page) != null, page.key in working)

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

    /** No page is being made or waits to be made by the queue (#504): pages made at full size may go. */
    fun idle(): Boolean = working.isEmpty() && worker?.isActive != true

    /** Start the queue if pages wait and it is not running. Main thread. */
    fun kick() {
        if (!on()) return
        // the pages not made yet get their first pictures, beside the queue (#785)
        kickFirst()
        // running: the loop looks again, so a free lane takes a new page at once (#549)
        if (worker?.isActive == true) {
            finished.trySend(Unit)
            return
        }
        worker = vm.viewModelScope.launch { run() }
    }

    private suspend fun run() = kotlinx.coroutines.coroutineScope {
        while (on()) {
            val lanes = scanQueueLanes(vm.scanLab.options.parallelPages)
            val pages = vm.scanPages.toList()
            // results of pages no longer in the scan go
            val keys = pages.mapTo(HashSet()) { it.key }
            made.keys.filter { it !in keys }.forEach { made.remove(it)?.file?.delete() }
            val wanted = vm.scanPreviewWantedKey()
            val waiting = ArrayList<Int>()
            for ((i, p) in pages.withIndex()) {
                if (p.file.path in finding || p.key in working) continue
                val key = keyOf(p)
                if (made[p.key]?.key == key || failed[p.key] == key) continue
                noteWaiting(p, key)
                // the screen's preview is making this one: it is stored when it comes
                if (key != wanted) waiting += i
            }
            val next = if (working.size < lanes) scanQueueNext(waiting, shownIndex) else null
            if (next == null) {
                if (working.isEmpty()) break
                // a page being made finishes first, then the queue looks again
                finished.receive()
                continue
            }
            val page = pages[next]
            val key = keyOf(page)
            val cancel = io.github.artcraftandroid.pdfcraft.core.ScanCancel()
            working[page.key] = cancel
            batchMostAtOnce = maxOf(batchMostAtOnce, working.size)
            launch {
                try {
                    makeOne(next, page, key, cancel, lanes)
                } finally {
                    if (working[page.key] === cancel) working.remove(page.key)
                    finished.trySend(Unit)
                }
            }
        }
        // every preview is made: the pages can be made at full size for Save (#504)
        vm.fullPages.kick()
    }

    /** Start making first pictures if pages need one and none is being made (#785). Main thread. */
    private fun kickFirst() {
        if (!firstOn() || firstWorker?.isActive == true) return
        firstWorker = vm.viewModelScope.launch { runFirst() }
    }

    /** The first pictures (#785), one at a time, the page on screen first; ends when no page needs one. */
    private suspend fun runFirst() {
        while (firstOn()) {
            val pages = vm.scanPages.toList()
            // first pictures of pages no longer in the scan go
            val keys = pages.mapTo(HashSet()) { it.key }
            first.keys.filter { it !in keys }.forEach { first.remove(it)?.file?.delete() }
            val waiting = ArrayList<Int>()
            for ((i, p) in pages.withIndex()) {
                if (p.file.path in finding) continue
                val key = keyOf(p)
                // a page that can't be made gets no first picture either
                if (failed[p.key] == key) continue
                if (scanNeedsFirst(key, made[p.key]?.key, first[p.key]?.key, firstFailed[p.key])) waiting += i
            }
            val next = scanQueueNext(waiting, shownIndex) ?: break
            val page = pages[next]
            val key = keyOf(page)
            // the page waits from now, if the queue has not noted it yet
            noteWaiting(page, key)
            makeFirst(next, page, key)
        }
    }

    /** Make the first picture of page [index] ([page], from [key]) and keep it (#785). */
    private suspend fun makeFirst(index: Int, page: ScanPage, key: ScanPreviewKey) {
        val started = android.os.SystemClock.elapsedRealtime()
        // a page made before keeps the turn it was shown with: the classifier is not asked for a first picture
        val known = if (page.turns == null) made[page.key]?.turns else null
        val asked = if (known != null) page.copy(turns = known) else page
        val p = withContext(Dispatchers.Default) {
            runCatching {
                io.github.artcraftandroid.pdfcraft.core.scanFirstPage(
                    vm.scanPreviewRequest(asked, key.flatten, key.lab, key.autoUpright, key.autoFlatten, key.paperHint, key.cardPaper),
                    SCAN_FIRST_SIDE.toUInt(), scanSmallPhoto(page.thumb),
                )
            }
        }.onFailure { Diag.failure(DiagArea.Scan, "scan_first_page", it) }.getOrNull()
        val kept = p != null && keepFirst(page, key, p)
        // one that can't be made is not tried again until the page changes
        if (!kept) firstFailed[page.key] = key
        val ms = android.os.SystemClock.elapsedRealtime() - started
        firstRecords[page.key] = ScanFirstRecord(kept, ms, p?.fromSmall ?: false, p?.width?.toInt() ?: 0, p?.height?.toInt() ?: 0, known != null)
        Diag.event(DiagArea.Scan, "page_first", *scanFirstDiag(index, kept, ms, p, known != null))
    }

    /** Keep [p], the first picture of [page] from [key]: written to the cache folder, its thumbnail for the strips. Main thread. */
    private suspend fun keepFirst(page: ScanPage, key: ScanPreviewKey, p: io.github.artcraftandroid.pdfcraft.core.ScanFirstPage): Boolean {
        val density = vm.getApplication<android.app.Application>().resources.displayMetrics.density
        val thumbSide = (SCAN_QUEUE_THUMB_DP * density).toInt().coerceAtLeast(48)
        val out = File(dir(), "first-${System.nanoTime()}.jpg")
        val thumb = withContext(Dispatchers.IO) {
            runCatching {
                val full = android.graphics.Bitmap.createBitmap(p.width.toInt(), p.height.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
                // ARGB_8888 keeps its bytes in R, G, B, A order: the first picture's own
                full.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(p.rgba))
                dir().mkdirs()
                out.outputStream().use { full.compress(android.graphics.Bitmap.CompressFormat.JPEG, SCAN_QUEUE_JPEG_QUALITY, it) }
                val k = thumbSide.toFloat() / maxOf(full.width, full.height)
                android.graphics.Bitmap.createScaledBitmap(full, maxOf(1, (full.width * k).toInt()), maxOf(1, (full.height * k).toInt()), true)
            }.getOrNull()
        }
        // the page may have gone or been made while its first picture was
        if (thumb == null) return false
        if (vm.scanPages.none { it.key == page.key } || made[page.key]?.key == key) {
            out.delete()
            return true
        }
        first.put(page.key, ScanMadePage(key, out, thumb.asImageBitmap(), 0, p.turns.toInt(), false, null, null, null, p.paper))?.file?.takeIf { it != out }?.delete()
        val now = android.os.SystemClock.elapsedRealtime()
        firstAt[page.key] = now
        batchFirstMade++
        batchFirstAt = now
        return true
    }

    /**
     * Make page [index] ([page], from [key]) in the background and keep it. With one lane it runs
     * on the screen's preview thread as before; with two, on the queue's own two (#549).
     */
    private suspend fun makeOne(index: Int, page: ScanPage, key: ScanPreviewKey, cancel: io.github.artcraftandroid.pdfcraft.core.ScanCancel, lanes: Int) {
        val started = android.os.SystemClock.elapsedRealtime()
        val dispatcher = if (lanes > 1) vm.scanQueueDispatcher else vm.scanPreviewDispatcher
        val result = withContext(dispatcher) {
            runCatching { io.github.artcraftandroid.pdfcraft.core.scanPreviewPageWith(vm.scanPreviewRequest(page, key.flatten, key.lab, key.autoUpright, key.autoFlatten, key.paperHint, key.cardPaper), SCAN_PREVIEW_SIDE.toUInt(), cancel) }
        }
        // stopped for the page on screen, whose preview goes first: this page is asked again
        if (cancel.isCancelled()) return
        val p = result.onFailure { Diag.failure(DiagArea.Scan, "scan_page_queue", it) }.getOrNull()
        if (p == null) {
            // a page that can't be made is not tried again until it changes
            made.remove(page.key)
            waitingSince.remove(page.key)
            Diag.event(DiagArea.Scan, "page_ready", "index" to index + 1, "made" to false, "ms" to android.os.SystemClock.elapsedRealtime() - started)
            markFailed(page.key, key)
            return
        }
        store(index, page, key, p, null, android.os.SystemClock.elapsedRealtime() - started)
        // a receipt becomes Grayscale by itself, without being opened (#466)
        vm.scanApplyAutoLook(page.key, p.kind?.suggestGreyscale == true)
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
        if (on()) working.filterKeys { it != pageKey }.values.forEach { it.cancel() }
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
        made.put(page.key, ScanMadePage(key, out, thumb.asImageBitmap(), p.quarterTurns.toInt(), p.turns.toInt(), scanCameOutFlat(p), p.kind, p.orientation, p.fingers, p.paper))?.file?.takeIf { it != out }?.delete()
        // the page is made: its first picture goes (#785)
        first.remove(page.key)?.file?.delete()
        val now = android.os.SystemClock.elapsedRealtime()
        val since = waitingSince.remove(page.key)
        val firstMs = scanFirstMs(since, firstAt.remove(page.key))
        val why = waitingWhy.remove(page.key) ?: scanQueueWhy(null, key, false)
        Diag.event(
            DiagArea.Scan, "page_ready", "index" to index + 1, "made" to true, "ms" to ms,
            "waited_ms" to ((since?.let { now - it - ms } ?: 0L).coerceAtLeast(0L)), "why" to why,
            "by" to (if (bitmap != null) "screen" else "queue"), "reused" to p.reused,
            "quarter_turns" to p.quarterTurns.toInt(), "flattened" to p.flattened, "preset" to (p.kind?.preset ?: "none"),
            // how long after it began to wait the page's first picture was up (#785); -1 for none
            "first_ms" to firstMs,
        )
        readyCheck()
        // a page ready to be made at full size for Save (#504)
        vm.fullPages.kick()
    }

    /** When no page waits any more, one `scan_ready` line for the batch. */
    private fun readyCheck() {
        val started = batchStarted ?: return
        val pages = vm.scanPages.toList()
        if (pages.any { it.file.path in finding || (readyOf(it) == null && failed[it.key] != keyOf(it)) }) return
        batchStarted = null
        val most = batchMostAtOnce
        batchMostAtOnce = 0
        val firstBatch = batchFirstAt?.let { ScanFirstBatch(batchFirstMade, (it - started).coerceAtLeast(0L)) }
        batchFirstMade = 0
        batchFirstAt = null
        Diag.event(DiagArea.Scan, "scan_ready", *scanReadyDiag(pages.size, android.os.SystemClock.elapsedRealtime() - started, vm.scanLab.options.parallelPages, most, firstBatch))
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
        working.values.forEach { it.cancel() }
        worker?.cancel()
        worker = null
        working.clear()
        made.clear()
        firstWorker?.cancel()
        firstWorker = null
        first.clear()
        firstFailed.clear()
        firstAt.clear()
        firstRecords.clear()
        batchFirstMade = 0
        batchFirstAt = null
        tiles.evictAll()
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

/**
 * The memory the document screen's tiles may keep (#494): about 12 tiles of 156 × 208 dp (600 × 800 px read) on a
 * 3× screen. Tiles off screen are read again when scrolled back.
 */
internal const val SCAN_TILE_CACHE_BYTES = 24 * 1024 * 1024

/** The longer side, in dp, of a page's thumbnail made by the queue (#465): the strip's 72 dp; the document screen reads the kept result at its tile's size instead (#494). */
internal const val SCAN_QUEUE_THUMB_DP = 72

/** The longer side, in pixels, of a page's first picture (#785): half the preview's, about what the photo's thumbnail holds of the page. */
internal const val SCAN_FIRST_SIDE = 640

/** JPEG quality of a result kept in the cache folder (#465): it is shown at preview size only. */
internal const val SCAN_QUEUE_JPEG_QUALITY = 90
