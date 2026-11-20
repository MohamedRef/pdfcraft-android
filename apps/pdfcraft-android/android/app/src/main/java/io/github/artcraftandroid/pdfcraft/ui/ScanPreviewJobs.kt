package io.github.artcraftandroid.pdfcraft.ui

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.ui.scan.scanApplyAutoLook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/*
 * The scan screen's previews: the page as it will be saved, its closer look and tiles, made and released (#730: moved out of AppViewModel.kt as they were).
 * Their state (scanPreview, scanDetail, scanTiles, the dispatchers) stays in [AppViewModel].
 */

/** The preview asked for last (the background queue leaves that page to it, #465). */
internal fun AppViewModel.scanPreviewWantedKey(): ScanPreviewKey? = scanPreviewWanted

/** A preview for the screen is being made now (#504: pages made at full size wait for it). */
internal fun AppViewModel.scanPreviewRunning(): Boolean = scanPreviewJob?.isActive == true

/**
 * Make the preview of [page] in its look with [flatten] (#301), replacing any still waiting.
 * One already running is told to stop at its next stage (testing option on); a result that
 * comes anyway is dropped when a newer request has come in meanwhile.
 */
internal fun AppViewModel.requestScanPreview(index: Int, page: ScanPage, flatten: Boolean) {
    val lab = scanLab.options
    val autoUpright = io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting.get(settings.appPrefs)
    val autoFlatten = scanAutoFlattens(page, lab)
    val key = ScanPreviewKey.of(page, flatten, lab, autoUpright, autoFlatten = autoFlatten, paperHint = scanPaperHint(page, lab), cardPaper = scanCardPaper(page, lab))
    pageQueue.shown(page)
    if (scanPreview?.key == key || scanPreviewWanted == key) return
    scanPreviewWanted = key
    scanPreviewJob?.cancel()
    // the page on screen goes first: a page the queue is making now stops (#465)
    pageQueue.yieldTo(page.key)
    // the native preview still running for an older request stops at its next stage (#301)
    if (lab.previewStop) scanPreviewStop?.cancel()
    val stop = io.github.artcraftandroid.pdfcraft.core.ScanCancel()
    scanPreviewStop = stop
    val look = page.look
    val filter = look.filter
    // the same page in another look or strength: the slider sends many in a row, and only the
    // one it settles on is worth making (#301); a new page starts at once
    val settle = key.samePage(scanPreview?.key)
    scanPreviewJob = viewModelScope.launch(scanPreviewDispatcher) {
        // made already in the background (#465): shown from what was kept, without the engine
        val keptStarted = android.os.SystemClock.elapsedRealtime()
        pageQueue.shownImage(page, key)?.let { (m, image) ->
            Diag.event(DiagArea.Scan, "preview_kept", "index" to index + 1, "ms" to android.os.SystemClock.elapsedRealtime() - keptStarted)
            withContext(Dispatchers.Main) {
                if (scanPreviewWanted != key) return@withContext
                scanPreview = ScanPreviewShown(key, image, m.quarterTurns, m.flattened, m.kind, m.fingers)
                scanPreviewStats[page.key] = ScanTestPreview(true, true, android.os.SystemClock.elapsedRealtime() - keptStarted, image.width, image.height, filter.mode)
                requestScanTiles(index, page, flatten)
            }
            return@launch
        }
        if (settle) delay(SCAN_PREVIEW_SETTLE_MS)
        val request = scanPreviewRequest(page, flatten, lab, autoUpright, autoFlatten, key.paperHint, key.cardPaper)
        val started = android.os.SystemClock.elapsedRealtime()
        val made = runCatching { io.github.artcraftandroid.pdfcraft.core.scanPreviewPageWith(request, SCAN_PREVIEW_SIDE.toUInt(), stop) }
        val ms = android.os.SystemClock.elapsedRealtime() - started
        if (made.isFailure && stop.isCancelled()) {
            // stopped for a newer request: not a failure, and nothing to show
            Diag.event(DiagArea.Scan, "preview_stopped", "index" to index + 1, "ms" to ms)
            return@launch
        }
        val p = made.getOrNull()
        val bitmap = p?.let { scanPreviewBitmap(it) }
        val image = bitmap?.asImageBitmap()
        made.exceptionOrNull()?.let { Diag.failure(DiagArea.Scan, "scan_preview_page", it) }
        // what the preview took and whether it reused the straightened page (#301)
        Diag.event(
            DiagArea.Scan, "preview", "index" to index + 1, "filter" to filter,
            // the scan's own paper and the card rule it was asked with, and the paper it came out at (#759)
            *io.github.artcraftandroid.pdfcraft.ui.scan.scanPaperRuleDiag(request.paperHint, request.cardPaper, p?.paper),
            // the page's own look and strength (#301)
            "look" to io.github.artcraftandroid.pdfcraft.ui.scan.scanFilterId(filter), "strength" to scanLookStrength(look, lab), "paper" to look.paper.word, "whiteboard_look" to lab.whiteboardLook,
            "strength_source" to (if (lab.strength != null) "lab" else look.strengthSource), "settled" to settle,
            // the strength the page was really made at, and whether the slider's counts as set by hand (#424)
            *scanAppliedStrengthDiag(scanAppliedStrength(look, lab, p?.kind?.strength?.toInt()), lab),
            "flatten" to flatten, "made" to (image != null),
            "reused" to (p?.reused ?: false), "ms" to ms,
            // the look's light estimate kept from an earlier look of the page; its parts are the preview_look_*_ms fields (#446)
            "look_reuse" to lab.lookReuse, "light_reused" to (p?.lightReused ?: false),
            *(p?.timings.orEmpty().map<io.github.artcraftandroid.pdfcraft.core.ScanTiming, Pair<String, Any?>> { it.stage.removePrefix("scan_") + "_ms" to it.ms.toLong() }.toTypedArray()),
            "width" to (p?.width?.toLong() ?: 0L), "height" to (p?.height?.toLong() ?: 0L),
            "quarter_turns" to (p?.quarterTurns?.toInt() ?: 0), "flattened" to (p?.flattened ?: false),
            // flattened and straightened compared (#302)
            "flatten_auto" to lab.flattenAuto, *scanFlattenCheckDiag(p?.flattenCheck),
            // a page already flat left unflattened (#549)
            "skip_flat" to lab.skipFlat, "flatten_skipped_flat" to (p?.flattenSkippedFlat ?: false),
            // the automatic look decided the page's flattening (#466)
            "flatten_decided" to autoFlatten,
            // fingers at the page's edge removed after Clean scan (#466)
            *io.github.artcraftandroid.pdfcraft.ui.scan.scanFingersDiag(io.github.artcraftandroid.pdfcraft.ui.scan.scanRemovesFingers(lab, page.auto), io.github.artcraftandroid.pdfcraft.ui.scan.ScanFingerCounts.of(p?.fingers)),
            // the person's own choice for the page, and the strip of table trimmed from a flattened one (#302)
            "flatten_page" to io.github.artcraftandroid.pdfcraft.ui.scan.scanFlattenChoiceId(page.flatten),
            "flatten_trim" to lab.flattenTrim, "flatten_line_floor" to lab.flattenLineFloor, *io.github.artcraftandroid.pdfcraft.ui.scan.scanTrimDiag(p?.trim),
            // flattened from its lines of text when the network gave nothing better (#303)
            *scanLineFlattenDiag(lab.lineFlatten, p?.lineFlatten),
            // the kind of document Clean scan took the page for (#299)
            "preset" to (p?.kind?.preset ?: "none"), "preset_chosen" to (p?.kind?.chosen ?: false),
            // where the kind came from: not worked out for a page whose outline was not found (#444)
            "kind_source" to io.github.artcraftandroid.pdfcraft.ui.scan.scanKindSource(page, lab),
            // the marks made by hand drawn on the page (#495): counts, the share erased, the time
            *io.github.artcraftandroid.pdfcraft.ui.scan.scanMarksDiag(lab.markup, io.github.artcraftandroid.pdfcraft.ui.scan.scanMarksFor(page, lab), p?.markup?.erasedShare, p?.markup?.ms?.toLong()),
        )
        withContext(Dispatchers.Main) {
            // a newer request may have come in while this one ran: only the wanted one is shown
            if (scanPreviewWanted != key) return@withContext
            if (p != null && image != null) {
                scanPreview = ScanPreviewShown(key, image, p.quarterTurns.toInt(), scanCameOutFlat(p), p.kind, p.fingers)
                scanPreviewStats[page.key] = ScanTestPreview(
                    true, p.reused, ms, p.width.toInt(), p.height.toInt(), filter.mode,
                    lightReused = p.lightReused, lookParts = scanLookParts(p.timings),
                )
                // the strip and the comparison follow the page shown (#301)
                requestScanTiles(index, page, flatten)
            } else {
                scanPreviewFailed = key
            }
        }
        // the page on screen is ready too: kept for the strip and for coming back (#465)
        withContext(Dispatchers.Main) {
            if (p != null && bitmap != null) pageQueue.store(index, page, key, p, bitmap, ms) else pageQueue.failedOnScreen(page, key)
            // a receipt becomes Grayscale by itself (#466)
            if (p != null) scanApplyAutoLook(page.key, p.kind?.suggestGreyscale == true)
            pageQueue.kick()
        }
    }
}

/** A native preview as a bitmap; null when it could not be made into one. */
internal fun AppViewModel.scanPreviewBitmap(p: io.github.artcraftandroid.pdfcraft.core.ScanPreview): android.graphics.Bitmap? = runCatching {
    val bmp = android.graphics.Bitmap.createBitmap(p.width.toInt(), p.height.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
    // ARGB_8888 keeps its bytes in R, G, B, A order: the preview's own
    bmp.copyPixelsFromBuffer(java.nio.ByteBuffer.wrap(p.rgba))
    bmp
}.getOrNull()

/**
 * Make [page] in its look at [SCAN_DETAIL_SIDE] once the person has zoomed in (#301), on the
 * preview's thread, so the text can be read at 100 %. It is a second straightened page, kept
 * natively beside the preview's.
 */
internal fun AppViewModel.requestScanDetail(index: Int, page: ScanPage, flatten: Boolean) {
    val lab = scanLab.options
    val autoUpright = io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting.get(settings.appPrefs)
    val autoFlatten = scanAutoFlattens(page, lab)
    val key = ScanPreviewKey.of(page, flatten, lab, autoUpright, autoFlatten = autoFlatten, paperHint = scanPaperHint(page, lab), cardPaper = scanCardPaper(page, lab))
    if (scanDetail?.first == key || scanDetailWanted == key) return
    scanDetailWanted = key
    scanDetailJob?.cancel()
    val look = page.look
    scanDetailJob = viewModelScope.launch(scanPreviewDispatcher) {
        val request = scanPageRequest(page.file.absolutePath, page.corners, look.filter, flatten, lab, "", page.turns, autoUpright, io.github.artcraftandroid.pdfcraft.ui.scan.scanPresetFor(page, lab), page.finding?.curvedSides ?: 0, page.finding?.curvedOpposite ?: false, page.book != null, look.strength, look.paper, autoFlatten, io.github.artcraftandroid.pdfcraft.ui.scan.scanRemovesFingers(lab, page.auto), markup = io.github.artcraftandroid.pdfcraft.ui.scan.scanNativeMarkup(io.github.artcraftandroid.pdfcraft.ui.scan.scanMarksFor(page, lab)))
        val started = android.os.SystemClock.elapsedRealtime()
        val made = runCatching { io.github.artcraftandroid.pdfcraft.core.scanPreviewPage(request, SCAN_DETAIL_SIDE.toUInt()) }
        made.exceptionOrNull()?.let { Diag.failure(DiagArea.Scan, "scan_preview_detail", it) }
        val p = made.getOrNull()
        val image = p?.let { scanPreviewBitmap(it)?.asImageBitmap() }
        Diag.event(
            DiagArea.Scan, "preview_detail", "index" to index + 1, "made" to (image != null), "reused" to (p?.reused ?: false),
            "ms" to android.os.SystemClock.elapsedRealtime() - started, "width" to (p?.width?.toLong() ?: 0L), "height" to (p?.height?.toLong() ?: 0L),
        )
        withContext(Dispatchers.Main) {
            if (scanDetailWanted == key && image != null) scanDetail = key to image
        }
    }
}

/**
 * Make what the strip and the comparison need for [page]: Original first (for comparing), then
 * the other looks when the strip shows previews. Each is made at the main preview's size, so the
 * straightened page kept natively serves them all and each costs one look; it then shrinks to
 * a tile. They share the preview's thread and give way to it between looks.
 */
internal fun AppViewModel.requestScanTiles(index: Int, page: ScanPage, flatten: Boolean) {
    val lab = scanLab.options
    val autoUpright = io.github.artcraftandroid.pdfcraft.ui.scan.ScanUprightSetting.get(settings.appPrefs)
    val autoFlatten = scanAutoFlattens(page, lab)
    val base = ScanPreviewKey.of(page, flatten, lab, autoUpright, io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(ScanFilter.Original), autoFlatten, scanPaperHint(page, lab), scanCardPaper(page, lab))
        // the strip shows the looks, not the marks (#495): drawing marks does not make the tiles again
        .copy(marks = null)
    if (base != scanTilesFor) {
        scanTilesJob?.cancel()
        if (lab.previewStop) scanTilesStop?.cancel()
        scanTilesJob = null
        scanTiles.clear()
        scanCompare = null
        scanTilesFor = base
    }
    val order = if (lab.lookStrip) io.github.artcraftandroid.pdfcraft.ui.scan.scanTileOrder(page.look.filter) else listOf(ScanFilter.Original)
    val todo = order.filter { it !in scanTiles || (it == ScanFilter.Original && scanCompare == null) }
    if (todo.isEmpty() || scanTilesJob?.isActive == true) return
    val stop = io.github.artcraftandroid.pdfcraft.core.ScanCancel()
    scanTilesStop = stop
    val tileSide = (SCAN_TILE_SIDE_DP * getApplication<Application>().resources.displayMetrics.density).toInt().coerceAtLeast(48)
    scanTilesJob = viewModelScope.launch(scanPreviewDispatcher) {
        val started = android.os.SystemClock.elapsedRealtime()
        var made = 0
        var reused = 0
        var slowest = 0L
        for (f in todo) {
            // a page preview asked for meanwhile goes first
            kotlinx.coroutines.yield()
            val request = scanPageRequest(page.file.absolutePath, page.corners, f, flatten, lab, "", page.turns, autoUpright, io.github.artcraftandroid.pdfcraft.ui.scan.scanPresetFor(page, lab), page.finding?.curvedSides ?: 0, page.finding?.curvedOpposite ?: false, page.book != null, autoFlatten = autoFlatten, removeFingers = io.github.artcraftandroid.pdfcraft.ui.scan.scanRemovesFingers(lab, page.auto))
            val one = android.os.SystemClock.elapsedRealtime()
            val p = runCatching { io.github.artcraftandroid.pdfcraft.core.scanPreviewPageWith(request, SCAN_PREVIEW_SIDE.toUInt(), stop) }
                .onFailure { if (!stop.isCancelled()) Diag.failure(DiagArea.Scan, "scan_look_tile", it) }.getOrNull() ?: continue
            slowest = maxOf(slowest, android.os.SystemClock.elapsedRealtime() - one)
            if (p.reused) reused++
            val full = scanPreviewBitmap(p) ?: continue
            val k = tileSide.toFloat() / maxOf(full.width, full.height)
            val tile = runCatching { android.graphics.Bitmap.createScaledBitmap(full, maxOf(1, (full.width * k).toInt()), maxOf(1, (full.height * k).toInt()), true) }.getOrNull() ?: continue
            made++
            withContext(Dispatchers.Main) {
                if (scanTilesFor != base) return@withContext
                scanTiles[f] = tile.asImageBitmap()
                if (f == ScanFilter.Original) scanCompare = full.asImageBitmap()
            }
        }
        // how long the strip took and whether the straightened page served every look (#301)
        Diag.event(
            DiagArea.Scan, "looks_strip", "index" to index + 1, "look_strip" to lab.lookStrip, "asked" to todo.size, "made" to made,
            "reused" to reused, "ms" to android.os.SystemClock.elapsedRealtime() - started, "slowest_ms" to slowest,
        )
    }
}

/** A page's last preview for a kept scan, or "none shown" (#301). */
internal fun AppViewModel.scanPreviewRecord(page: ScanPage): ScanTestPreview =
    scanPreviewStats[page.key] ?: ScanTestPreview(false, false, 0, 0, 0, null)

/** The scan is over: forget the previews and the straightened pages kept for them. */
internal fun AppViewModel.releaseScanPreviews() {
    scanPreviewJob?.cancel()
    scanPreviewJob = null
    scanPreviewWanted = null
    scanPreview = null
    scanPreviewFailed = null
    scanPreviewStats.clear()
    scanTilesJob?.cancel()
    scanTilesJob = null
    scanPreviewStop?.cancel()
    scanTilesStop?.cancel()
    scanTiles.clear()
    scanTilesFor = null
    scanCompare = null
    scanDetailJob?.cancel()
    scanDetailJob = null
    scanDetailWanted = null
    scanDetail = null
    // the pages made in the background and their files (#465)
    pageQueue.clear()
    // and the pages made at full size for Save (#504)
    fullPages.clear()
    runCatching { io.github.artcraftandroid.pdfcraft.core.scanReleasePreviews() }
}
