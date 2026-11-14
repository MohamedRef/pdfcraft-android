package io.github.artcraftandroid.pdfcraft.ui.scan

import android.os.SystemClock
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel

/*
 * The passes over several pages (#515) as the view model runs them: opened from the document
 * screen's selection or its offer after an import, one page after another, and closed once.
 */

/**
 * A pass [what] over the pages [picked] (keys) from [from] (`selection`, `offer`). A borders pass
 * over pages with markup asks once first (#495). Returns the index of the first page for a look
 * pass, which opens Enhance there; null otherwise or when nothing opened.
 */
internal fun AppViewModel.startScanBatch(what: ScanBatchWhat, picked: Set<String>, from: String): Int? {
    if (!scanLab.options.batchBorders) return null
    val keys = scanBatchKeys(picked, scanPages.map { it.key })
    if (keys.isEmpty()) return null
    if (from == "offer") scanBatch.offer = 0
    if (what == ScanBatchWhat.Borders && scanLab.options.markup) {
        val marked = scanPages.filter { it.key in keys && it.marks != null && !it.marks.isEmpty }.map { it.key }.toSet()
        if (marked.isNotEmpty()) {
            scanBatch.asking = ScanBatchMarksAsk(what, keys, marked, from)
            Diag.event(DiagArea.Scan, "markup_ask", "why" to "batch_borders", "pages" to marked.size)
            return null
        }
    }
    return openScanBatch(what, keys, from, 0)
}

/** The answer to the markup question before a borders pass: [remove] keeps those pages in the pass (their markup goes where the borders change), Keep leaves them out. */
internal fun AppViewModel.answerScanBatchMarks(remove: Boolean) {
    val ask = scanBatch.asking ?: return
    scanBatch.asking = null
    if (!remove) Diag.event(DiagArea.Scan, "markup_kept", "why" to "batch_borders", "pages" to ask.marked.size)
    val keys = if (remove) ask.keys else ask.keys.filter { it !in ask.marked }
    if (keys.isEmpty()) return
    openScanBatch(ask.what, keys, ask.from, if (remove) ask.marked.size else 0)
}

private fun AppViewModel.openScanBatch(what: ScanBatchWhat, keys: List<String>, from: String, withMarks: Int): Int? {
    val pass = ScanBatchPass(what, keys, 0, SystemClock.elapsedRealtime(), shown = setOf(keys[0]), withMarks = withMarks)
    Diag.event(DiagArea.Scan, "batch_open", *scanBatchOpenDiag(what, keys.size, from, withMarks))
    return when (what) {
        ScanBatchWhat.Borders -> {
            scanBatch.borders = pass
            null
        }
        ScanBatchWhat.Look -> {
            scanBatch.look = pass
            scanPages.indexOfFirst { it.key == keys[0] }.takeIf { it >= 0 }
        }
    }
}

/**
 * The borders pass leaves the page shown towards [dir] (-1, +1, or 0 for the tick), with its
 * corners [q] and what was done on it. The page keeps them; past the last page or with the tick
 * the pass ends.
 */
internal fun AppViewModel.leaveScanBatchPage(dir: Int, q: FloatArray, stats: ScanCropStats) {
    val pass = scanBatch.borders ?: return
    val index = scanPages.indexOfFirst { it.key == pass.key }
    var now = pass
    if (index >= 0) {
        val page = scanPages[index]
        val changed = !scanSameCorners(page.corners, q)
        scanCornersChanging(index, q)
        val after = scanPages[index]
        scanPages[index] = after.copy(corners = q, crop = after.crop?.plus(stats) ?: stats)
        if (changed) now = now.copy(changed = now.changed + page.key)
    }
    val next = scanBatchStep(now, dir)
    if (next == null) {
        endScanBatch(now, "done")
        return
    }
    scanBatch.borders = next.copy(shown = next.shown + (next.key ?: ""))
}

/** The borders pass given up with Back: what was done on the pages already left stays. */
internal fun AppViewModel.cancelScanBatch() {
    val pass = scanBatch.borders ?: return
    endScanBatch(pass, "back")
}

/** A page of the borders pass is gone (deleted, or the scan changed): the pass ends. */
internal fun AppViewModel.scanBatchLost() {
    scanBatch.borders?.let { endScanBatch(it, "lost") }
}

private fun AppViewModel.endScanBatch(pass: ScanBatchPass, how: String) {
    when (pass.what) {
        ScanBatchWhat.Borders -> scanBatch.borders = null
        ScanBatchWhat.Look -> scanBatch.look = null
    }
    Diag.event(DiagArea.Scan, "batch_done", *scanBatchDoneDiag(pass, SystemClock.elapsedRealtime() - pass.startedAt, how))
}

/** The look pass ends: Enhance was left. */
internal fun AppViewModel.endScanLookBatch(how: String) {
    scanBatch.look?.let { endScanBatch(it, how) }
}

/**
 * [look] was chosen on page [index] during a look pass: every page of the pass takes it (the
 * pages not chosen stay as they were). Returns whether a pass took it.
 */
internal fun AppViewModel.scanBatchTakesLook(index: Int, look: ScanPageLook): Boolean {
    val pass = scanBatch.look ?: return false
    val key = scanPages.getOrNull(index)?.key ?: return false
    if (key !in pass.keys) return false
    val changed = HashSet<String>()
    for (i in scanPages.indices) {
        val p = scanPages[i]
        if (p.key !in pass.keys || p.look == look) continue
        scanPages[i] = if (p.auto.greyApplied) p.copy(look = look, auto = p.auto.copy(greyApplied = false, undone = p.auto.undone + ScanAutoMarks.GREYSCALE)) else p.copy(look = look)
        changed += p.key
    }
    scanBatch.look = pass.copy(changed = pass.changed + changed, shown = pass.shown + key)
    return true
}
