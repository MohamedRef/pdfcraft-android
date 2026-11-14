package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.SCAN_PREVIEW_SIDE
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import kotlinx.coroutines.withContext

/*
 * Markup on a scanned page (#495), the app's side: which page is open in the Markup screen, the
 * question asked before a change that would move the page under its marks, and the log lines.
 */

/** The Markup screen's state, kept by the view model so it outlives the screen's recompositions. */
internal class ScanMarkupState {
    /** The key of the page open in the Markup screen, or null. */
    var open by mutableStateOf<String?>(null)

    /** A change waiting for the person's answer to "Remove this page's markup?", or null. */
    var asking by mutableStateOf<ScanMarkupAsk?>(null)

    /** The tool used last in this run of the app: Markup opens on it with every tool in reach (#516). */
    var lastTool: ScanMarkupTool? = null

    /** When the Markup screen was opened, for `markup_done`'s time. */
    var openedAt = 0L

    /** How many times the eyedropper picked a colour on each page (by key) in this scan (#503), for a kept page file. */
    val eyedropper = HashMap<String, Int>()

    /** How many strokes Remove marks took back on each page (by key) in this scan (#520), for a kept page file. */
    val marksRemoved = HashMap<String, Int>()

    /** Each tool's width from the size slider in this run of the app (#520), by tool. */
    val widths = HashMap<ScanMarkupTool, Float>()
}

/** A change that would move page [index] under its marks: what it is (for the log) and what to do on Remove. */
internal class ScanMarkupAsk(val index: Int, val why: String, val then: () -> Unit)

/** The Markup screen for page [index] (#495). */
internal fun AppViewModel.openScanMarkup(index: Int) {
    val page = scanPages.getOrNull(index) ?: return
    scanMarkup.open = page.key
    scanMarkup.openedAt = android.os.SystemClock.elapsedRealtime()
    Diag.event(DiagArea.Scan, "markup_open", "index" to index + 1, "marks" to (page.marks?.counts?.total ?: 0))
}

/**
 * The Markup screen closed: with Done, page [index] keeps [marks] (none when empty); with Cancel
 * ([cancelled]) it stays as it was. [erasedShare] is the share the eraser painted on the screen's
 * last drawing of the page; [eyedropper] how many colours the eyedropper picked there and
 * [eraseFill] Erase's fill when it closed (#503).
 */
internal fun AppViewModel.finishScanMarkup(index: Int, marks: ScanMarks?, cancelled: Boolean, erasedShare: Float?, eyedropper: Int = 0, eraseFill: String = "paper", ink: Array<Pair<String, Any?>> = emptyArray()) {
    val page = scanPages.getOrNull(index)
    val kept = marks?.takeUnless { it.isEmpty }
    val changed = !cancelled && page != null && page.marks != kept
    if (changed && page != null) scanPages[index] = page.copy(marks = kept)
    if (page != null && eyedropper > 0) scanMarkup.eyedropper.merge(page.key, eyedropper, Int::plus)
    scanMarkup.open = null
    val ms = android.os.SystemClock.elapsedRealtime() - scanMarkup.openedAt
    // what Markup's ink did (#520): the engine's drawings, Undo's time, strokes taken back, long frames
    Diag.event(DiagArea.Scan, "markup_done", *scanMarkupDoneDiag(index, if (cancelled) page?.marks else kept, erasedShare, ms, cancelled, changed, eyedropper, eraseFill), *ink)
}

/** Clear markup (#495): every mark of page [index] gone; [why] is "clear" from More, or the change that asked for it. */
internal fun AppViewModel.clearScanMarks(index: Int, why: String) {
    val page = scanPages.getOrNull(index) ?: return
    if (page.marks == null) return
    scanPages[index] = page.copy(marks = null)
    Diag.event(DiagArea.Scan, "markup_clear", "index" to index + 1, "why" to why, "marks" to (page.marks.counts.total))
}

/**
 * Whether a change [why] ("borders", "flatten") to page [index] must first ask "Remove this page's
 * markup?" (#495): the page has marks and the testing option is on. When it must, the question is
 * set up, with [then] run on Remove (after the marks are removed, unless [clearFirst] is false),
 * and true is returned; else nothing happens.
 */
internal fun AppViewModel.scanMarkupAsks(index: Int, why: String, clearFirst: Boolean = true, then: () -> Unit): Boolean {
    val page = scanPages.getOrNull(index) ?: return false
    if (!scanLab.options.markup || page.marks == null || page.marks.isEmpty) return false
    scanMarkup.asking = ScanMarkupAsk(index, why) {
        // Borders clears the marks only when the corners really change (its Done does that)
        if (clearFirst) clearScanMarks(index, why)
        then()
    }
    Diag.event(DiagArea.Scan, "markup_ask", "index" to index + 1, "why" to why)
    return true
}

/** Run [action] on page [index] now, or after "Remove this page's markup?" when the page has marks (#495). */
internal fun AppViewModel.afterScanMarkupAsk(index: Int, why: String, clearFirst: Boolean = true, action: () -> Unit) {
    if (!scanMarkupAsks(index, why, clearFirst, action)) action()
}

/** New corners for page [index] from Borders or Crop: its marks go when the corners really changed (#495). */
internal fun AppViewModel.scanCornersChanging(index: Int, corners: FloatArray) {
    val page = scanPages.getOrNull(index) ?: return
    if (page.marks != null && !page.corners.contentEquals(corners)) clearScanMarks(index, "borders")
}

/** The Markup screen's page drawn by the engine with [marks] (their text boxes left to the screen); null when it could not be made. */
internal class ScanMarkupDrawn(val image: ImageBitmap, val strokes: List<ScanStrokeMark>, val erasedShare: Float?, val turns: Int)

/**
 * [page] made as the preview is, with [marks]' strokes drawn and no text boxes (the screen draws
 * those itself, so they can be moved). On the preview's thread, so it waits for one running there.
 */
internal suspend fun AppViewModel.scanMarkupDrawn(page: ScanPage, marks: ScanMarks): ScanMarkupDrawn? {
    val lab = scanLab.options
    val autoUpright = ScanUprightSetting.get(settings.appPrefs)
    val strokesOnly = marks.copy(texts = emptyList())
    val request = scanPreviewRequest(page.copy(marks = strokesOnly), scanPageFlattens(page.flatten, scanCanFlatten), lab, autoUpright, scanAutoFlattens(page, lab))
    return withContext(scanPreviewDispatcher) {
        val made = runCatching { io.github.artcraftandroid.pdfcraft.core.scanPreviewPage(request, SCAN_PREVIEW_SIDE.toUInt()) }
        made.exceptionOrNull()?.let { Diag.failure(DiagArea.Scan, "scan_markup_preview", it) }
        val p = made.getOrNull() ?: return@withContext null
        val bitmap = scanPreviewBitmap(p) ?: return@withContext null
        ScanMarkupDrawn(bitmap.asImageBitmap(), strokesOnly.strokes, p.markup?.erasedShare, p.turns.toInt())
    }
}

/**
 * A page drawn by the engine once without its strokes (#520): Markup's paper fills are drawn on
 * its [pixels] (ARGB, [width] × [height]) with no photo read and no look made again.
 */
internal class ScanMarkupBase(val drawn: ScanMarkupDrawn, val pixels: IntArray, val rgba: ByteArray, val width: Int, val height: Int)

/** [page] drawn by the engine with no strokes, for Markup's paper fills to be drawn on (#520); null when it could not be made. */
internal suspend fun AppViewModel.scanMarkupBase(page: ScanPage): ScanMarkupBase? {
    val lab = scanLab.options
    val autoUpright = ScanUprightSetting.get(settings.appPrefs)
    val request = scanPreviewRequest(page.copy(marks = null), scanPageFlattens(page.flatten, scanCanFlatten), lab, autoUpright, scanAutoFlattens(page, lab))
    return withContext(scanPreviewDispatcher) {
        val made = runCatching { io.github.artcraftandroid.pdfcraft.core.scanPreviewPage(request, SCAN_PREVIEW_SIDE.toUInt()) }
        made.exceptionOrNull()?.let { Diag.failure(DiagArea.Scan, "scan_markup_preview", it) }
        val p = made.getOrNull() ?: return@withContext null
        val bitmap = scanPreviewBitmap(p) ?: return@withContext null
        val w = p.width.toInt()
        val h = p.height.toInt()
        val pixels = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        ScanMarkupBase(ScanMarkupDrawn(bitmap.asImageBitmap(), emptyList(), null, p.turns.toInt()), pixels, p.rgba, w, h)
    }
}

/**
 * Markup's paper fills drawn on a page made once (#520): one at a time, off the preview thread
 * the page queue uses; a new drawing stops the one before it (the latest wins), and one not yet
 * started when its screen moved on is never started.
 */
internal class ScanMarkupPatcher {
    private val thread = kotlinx.coroutines.Dispatchers.Default.limitedParallelism(1)
    @Volatile private var last: io.github.artcraftandroid.pdfcraft.core.ScanCancel? = null

    /** [base] with [marks]' strokes drawn; null when the drawing failed or was stopped. */
    suspend fun draw(base: ScanMarkupBase, marks: ScanMarks): ScanMarkupDrawn? {
        val native = scanNativeMarkup(marks.copy(texts = emptyList())) ?: return base.drawn
        val cancel = io.github.artcraftandroid.pdfcraft.core.ScanCancel()
        last?.cancel()
        last = cancel
        return withContext(thread) {
            if (cancel.isCancelled()) return@withContext null
            val made = runCatching { io.github.artcraftandroid.pdfcraft.core.scanMarkupDraw(base.rgba, base.width.toUInt(), base.height.toUInt(), base.drawn.turns.mod(4).toUByte(), native, cancel) }
            made.exceptionOrNull()?.takeUnless { it.message?.contains("cancelled") == true }?.let { Diag.failure(DiagArea.Scan, "scan_markup_patch", it) }
            if (made.isFailure) return@withContext null
            val patch = made.getOrNull() ?: return@withContext ScanMarkupDrawn(base.drawn.image, marks.strokes, 0f, base.drawn.turns)
            val pixels = scanLayPatch(base.pixels, base.width, patch.x.toInt(), patch.y.toInt(), patch.width.toInt(), patch.height.toInt(), patch.rgba)
            val bitmap = android.graphics.Bitmap.createBitmap(pixels, base.width, base.height, android.graphics.Bitmap.Config.ARGB_8888)
            ScanMarkupDrawn(bitmap.asImageBitmap(), marks.strokes, patch.applied.erasedShare, base.drawn.turns)
        }
    }
}
