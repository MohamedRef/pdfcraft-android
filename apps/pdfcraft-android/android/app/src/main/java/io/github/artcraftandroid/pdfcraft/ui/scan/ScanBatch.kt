package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.artcraftandroid.pdfcraft.ui.ScanFinding
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import kotlin.math.abs

/*
 * Borders and looks for several pages in one pass (#515): the document screen's selection opens
 * Adjust borders on the chosen pages one after the other, with a counter and a swipe between them,
 * or the looks strip with the choice going to every chosen page. What can be worked out without a
 * phone is here, so it can be tested on the desktop.
 */

/** What a pass does: `borders` or `look`, as the log says it. */
internal enum class ScanBatchWhat(val id: String) {
    Borders("borders"),
    Look("look"),
}

/**
 * One pass over the chosen pages: their keys in the scan's order, the place shown ([at], from 0),
 * when it started, the pages shown so far and those whose corners (or look) it changed.
 */
internal data class ScanBatchPass(
    val what: ScanBatchWhat,
    val keys: List<String>,
    val at: Int,
    val startedAt: Long,
    val shown: Set<String> = emptySet(),
    val changed: Set<String> = emptySet(),
    /** How many pages with markup were in the chosen ones when it opened (#495). */
    val withMarks: Int = 0,
    /** Where it opened from (#783): `selection`, `offer`, or for a review of flagged pages `unsure` or `save`. */
    val from: String = "selection",
) {
    val key: String? get() = keys.getOrNull(at)

    /** A review of the pages whose borders need a check (#783), not a pass over chosen pages. */
    val review: Boolean get() = scanBatchIsReview(from)
}

/** Where a pass goes from [pass] when the page shown is left towards [dir] (-1, +1; 0 is the tick): null when it ends. */
internal fun scanBatchStep(pass: ScanBatchPass, dir: Int): ScanBatchPass? {
    if (dir == 0) return null
    val next = pass.at + dir
    // past the last page the pass ends, as with the tick; before the first it stays on the first
    if (next >= pass.keys.size) return null
    return pass.copy(at = next.coerceAtLeast(0))
}

/**
 * The pages a pass is over: [picked] page keys in the order of [keys] (the scan's pages), without
 * those [left] out (pages whose markup the person kept).
 */
internal fun scanBatchKeys(picked: Set<String>, keys: List<String>, left: Set<String> = emptySet()): List<String> =
    keys.filter { it in picked && it !in left }

/**
 * The swipe between pages on Adjust borders, from a drag that took no handle: ([dx], [dy]) as the
 * finger moved in the photo's own coordinates, shown turned by [turns] quarters clockwise. A
 * mostly sideways move longer than [minPx] goes to the next page (+1) towards the reading
 * direction's start (a swipe to the left in a left-to-right language), or the previous one (-1).
 */
internal fun scanBatchSwipe(dx: Float, dy: Float, turns: Int, minPx: Float, rtl: Boolean): Int {
    // the move as the screen sees it: the photo is turned clockwise by turns quarters
    val (sx, sy) = when (scanQuarter(turns)) {
        1 -> -dy to dx
        2 -> -dx to -dy
        3 -> dy to -dx
        else -> dx to dy
    }
    if (abs(sx) < minPx || abs(sx) < abs(sy) * 1.5f) return 0
    val towardsStart = if (rtl) sx > 0 else sx < 0
    return if (towardsStart) 1 else -1
}

/** Whether the document screen offers "Check the borders of N pages" after an import of [imported] photos. */
internal fun scanBatchOffers(optionOn: Boolean, steps: Boolean, imported: Int): Boolean = optionOn && steps && imported >= 2

/** The detector's own mark for an outline it is sure of (`SURE` in the scan crate's `detect.rs`). */
internal const val SCAN_SURE_CONFIDENCE = 0.7

/**
 * Whether a page's borders are worth a look (#758): its search found no page, or found one with a
 * confidence under the detector's own sure mark ([SCAN_SURE_CONFIDENCE]), and since then nobody has
 * moved its corners or left Adjust borders on it with Done ([visits]). A page that was not searched
 * ([finding] null: a low-light merge comes already straightened, a restored page keeps no search) is not.
 */
internal fun scanPageUnsure(corners: FloatArray, found: FloatArray?, uncropped: FloatArray?, finding: ScanFinding?, visits: Int): Boolean {
    if (finding == null || visits > 0) return false
    // not found: unsure while the corners are still those the page started with
    if (found == null) return corners.contentEquals(uncropped ?: ScanPage.WHOLE)
    return finding.confidence < SCAN_SURE_CONFIDENCE && corners.contentEquals(found)
}

/** [scanPageUnsure] for a page of the scan. */
internal fun scanPageUnsure(page: ScanPage): Boolean = scanPageUnsure(page.corners, page.found, page.uncropped, page.finding, page.crop?.visits ?: 0)

/**
 * Whether the document screen offers to check the [unsure] pages (#758): with the option, the passes
 * over several pages (#515) and the screens in steps on, while an unsure page is left that the person
 * didn't dismiss, and the offer after an import ([importOffer], which covers every imported page) isn't shown.
 */
internal fun scanUnsureOffers(optionOn: Boolean, batchOn: Boolean, steps: Boolean, unsure: Set<String>, dismissed: Set<String>, importOffer: Int): Boolean =
    optionOn && batchOn && steps && importOffer == 0 && (unsure - dismissed).isNotEmpty()

/** The log's `unsure_offer` line: the pages offered, how many of them had no page found, and the scan's pages. */
internal fun scanUnsureOfferDiag(pages: Int, notFound: Int, scanPages: Int): Array<Pair<String, Any?>> =
    arrayOf("pages" to pages, "not_found" to notFound, "low_confidence" to pages - notFound, "scan_pages" to scanPages, "sure" to SCAN_SURE_CONFIDENCE)

/** The log's `batch_open` line. */
internal fun scanBatchOpenDiag(what: ScanBatchWhat, pages: Int, from: String, withMarks: Int): Array<Pair<String, Any?>> =
    arrayOf("what" to what.id, "pages" to pages, "from" to from, "with_markup" to withMarks)

/** The log's `batch_done` line: how many pages, how many were shown and changed, and the time per page shown. */
internal fun scanBatchDoneDiag(pass: ScanBatchPass, ms: Long, how: String): Array<Pair<String, Any?>> =
    arrayOf(
        "what" to pass.what.id, "pages" to pass.keys.size, "shown" to pass.shown.size, "changed" to pass.changed.size,
        "ms" to ms, "per_page_ms" to (if (pass.shown.isEmpty()) 0L else ms / pass.shown.size), "how" to how,
    )

/** The question before a pass over pages with markup (#495): asked once for the pass. */
internal class ScanBatchMarksAsk(val what: ScanBatchWhat, val keys: List<String>, val marked: Set<String>, val from: String)

/** A pass's state, kept by the view model. */
internal class ScanBatchState {
    /** The borders pass open, or null. */
    var borders by mutableStateOf<ScanBatchPass?>(null)

    /** The look pass open on Enhance, or null. */
    var look by mutableStateOf<ScanBatchPass?>(null)

    /** How many photos the last import brought, for the document screen's offer; 0 when none or once used or dismissed. */
    var offer by mutableStateOf(0)

    /** The scan's page keys before that import: the pass goes over the others. */
    var offerBefore: Set<String> = emptySet()

    /** "Remove the markup on N pages?" before a borders pass, or null. */
    var asking by mutableStateOf<ScanBatchMarksAsk?>(null)

    /** The unsure pages whose offer the person closed (#758): it comes back only for others. */
    var unsureDismissed by mutableStateOf<Set<String>>(emptySet())

    /** "N pages may have the wrong borders" before Save PDF or Share (#783), or null. */
    var saveAsk by mutableStateOf<ScanReviewSaveAsk?>(null)

    /** The pages the person chose to save as they are (#783): Save does not ask about them again. They stay marked. */
    var saveAccepted: Set<String> = emptySet()
}
