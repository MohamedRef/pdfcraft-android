package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanFinding
import io.github.artcraftandroid.pdfcraft.ui.ScanPage

/*
 * The review of pages whose borders the app is not sure of (#783), on top of the offer of #758 and
 * the borders pass of #515: what is known of each page's borders, which pages are still to be
 * checked, what Save asks before it stores one as it is, and what the pass says and logs. What can
 * be worked out without a phone is here, so it can be tested on the desktop.
 */

/**
 * What is known of a page's borders. [id] is the word in the log, in a kept scan's page file and
 * in a draft's `review` line.
 */
enum class ScanReviewState(val id: String) {
    /** The search for the page's outline has not answered yet. */
    Pending("pending"),

    /** An outline was found at or above the detector's sure mark, and nobody moved it. */
    Sure("sure"),

    /** An outline was found under the sure mark, and nobody has looked at it. */
    Unsure("unsure"),

    /** The search found no page: the page is the whole photo, and nobody has looked at it. */
    NotFound("not_found"),

    /** The search itself failed: the page is the whole photo, and nobody has looked at it. */
    Failed("failed"),

    /** The person moved the corners or left Adjust borders on the page with Done. */
    Checked("checked"),

    /** The photo needs no search: it came already straightened (a low-light merge). */
    NoSearch("none"),

    /** A draft written before the review state was kept, or one whose search never answered: nothing is known. */
    Unknown("unknown"),
    ;

    /** The page is offered for a check, marked on the document screen and asked about before Save. */
    val needsReview: Boolean get() = this == Unsure || this == NotFound || this == Failed

    companion object {
        /** The state a draft's `review` line names; null for none or a word this build does not know. */
        fun of(id: String?): ScanReviewState? = entries.firstOrNull { it.id == id }
    }
}

/**
 * A page's review state from what the page holds: its [corners], the outline [found], the corners it
 * started with when none was ([uncropped]), the search's report ([finding]), how often Adjust
 * borders was left on it with Done ([visits]), whether its search has answered in this run of the
 * app ([searched]) and what its draft said when it was reopened ([kept], null for a page of this run).
 *
 * A page whose corners were moved or confirmed is [ScanReviewState.Checked] whatever a search says
 * later. A reopened page keeps what its draft said, and one whose draft said nothing is
 * [ScanReviewState.Unknown]: neither offered for a check nor counted as checked.
 */
internal fun scanReviewState(
    corners: FloatArray,
    found: FloatArray?,
    uncropped: FloatArray?,
    finding: ScanFinding?,
    visits: Int,
    searched: Boolean,
    kept: ScanReviewState?,
): ScanReviewState {
    if (visits > 0) return ScanReviewState.Checked
    if (finding != null) {
        // searched in this run: the same rule as #758's scanPageUnsure
        if (found == null) return if (corners.contentEquals(uncropped ?: ScanPage.WHOLE)) ScanReviewState.NotFound else ScanReviewState.Checked
        if (!corners.contentEquals(found)) return ScanReviewState.Checked
        return if (finding.confidence < SCAN_SURE_CONFIDENCE) ScanReviewState.Unsure else ScanReviewState.Sure
    }
    if (kept != null) {
        // reopened: the search's report is gone, the draft's word stands
        val start = found ?: uncropped
        return when {
            kept == ScanReviewState.Pending -> ScanReviewState.Unknown
            !kept.needsReview -> kept
            start != null && !corners.contentEquals(start) -> ScanReviewState.Checked
            else -> kept
        }
    }
    if (!searched) return ScanReviewState.Pending
    // the search answered with nothing at all: it failed, or the photo needed none
    if (uncropped == null) return ScanReviewState.NoSearch
    return if (corners.contentEquals(uncropped)) ScanReviewState.Failed else ScanReviewState.Checked
}

/** [scanReviewState] for a page of the scan; [searched] as the steps' controller knows it. */
internal fun scanPageReview(page: ScanPage, searched: Boolean): ScanReviewState =
    scanReviewState(page.corners, page.found, page.uncropped, page.finding, page.crop?.visits ?: 0, searched, page.reviewKept)

/**
 * Whether a page is offered for a check: with the review ([reviewOn]) its state says so, which adds
 * the pages of a reopened draft and those whose search failed; without it, #758's rule as before.
 */
internal fun scanPageNeedsReview(page: ScanPage, searched: Boolean, reviewOn: Boolean): Boolean =
    if (reviewOn) scanPageReview(page, searched).needsReview else scanPageUnsure(page)

/**
 * Whether the review's offer takes the place of the offer after an import (#515), which covers every
 * imported page: with the review on, while a page that needs a check is left that was not dismissed.
 * Off, the import's offer comes first, as before.
 */
internal fun scanReviewOfferFirst(reviewOn: Boolean, checkOn: Boolean, batchOn: Boolean, steps: Boolean, needing: Set<String>, dismissed: Set<String>): Boolean =
    reviewOn && scanUnsureOffers(checkOn, batchOn, steps, needing, dismissed, 0)

/**
 * The pages Save PDF and Share ask about before they store them as they are: those that still need
 * a check ([needing], in the scan's order) and that the person has not already chosen to save as
 * they are ([accepted]). Empty when nothing is asked: the review or what it needs is off.
 */
internal fun scanReviewSaveAsks(reviewOn: Boolean, checkOn: Boolean, batchOn: Boolean, steps: Boolean, needing: List<String>, accepted: Set<String>): List<String> =
    if (reviewOn && checkOn && batchOn && steps) needing.filter { it !in accepted } else emptyList()

/** Whether a pass is a review of flagged pages: opened from the offer (`unsure`) or from Save's question (`save`). */
internal fun scanBatchIsReview(from: String): Boolean = from == "unsure" || from == "save"

/**
 * Whether Back on a page of a borders pass asks what to do with the corners moved on it: with the
 * review on, when they are not where the page opened ([opened]). Off, Back drops them without a word, as before.
 */
internal fun scanBatchBackAsks(reviewOn: Boolean, opened: FloatArray, corners: FloatArray): Boolean =
    reviewOn && !scanSameCorners(opened, corners)

/**
 * What became of a page of a review when it was left: `changed` or `confirmed` with Next, Previous,
 * a swipe or Done; `back_kept` and `back_dropped` when Back asked about moved corners; `back` when
 * Back left it as it was.
 */
internal fun scanReviewOutcome(left: Boolean, changed: Boolean, back: Boolean): String = when {
    back && left -> "back_kept"
    back && changed -> "back_dropped"
    back -> "back"
    changed -> "changed"
    else -> "confirmed"
}

/**
 * Whether a search's answer moves the page's corners: not once the person has confirmed them
 * ([visits]) or moved them from where the page [started]. A late answer still records what was
 * found, so Auto on Adjust borders can go to it.
 */
internal fun scanSearchMovesCorners(reviewOn: Boolean, corners: FloatArray, started: FloatArray, visits: Int): Boolean =
    !reviewOn || (visits == 0 && corners.contentEquals(started))

/** The log's `review_open` line: the pages to check, why, the first one's place in the scan (from 1) and where it opened from. */
internal fun scanReviewOpenDiag(pages: Int, notFound: Int, failed: Int, firstPage: Int, scanPages: Int, from: String): Array<Pair<String, Any?>> =
    arrayOf(
        "pages" to pages, "not_found" to notFound, "failed" to failed, "low_confidence" to (pages - notFound - failed).coerceAtLeast(0),
        "first_page" to firstPage, "scan_pages" to scanPages, "from" to from,
    )

/** The log's `review_page` line: the page's place in the review and in the scan (both from 1), what it was, and what became of it. */
internal fun scanReviewPageDiag(at: Int, of: Int, page: Int, scanPages: Int, state: ScanReviewState, outcome: String, ms: Long): Array<Pair<String, Any?>> =
    arrayOf("at" to at, "of" to of, "page" to page, "scan_pages" to scanPages, "state" to state.id, "outcome" to outcome, "ms" to ms)

/** The log's `review_done` line: the review's pages, those shown and changed, those still to check, the time and how it ended. */
internal fun scanReviewDoneDiag(pass: ScanBatchPass, left: Int, ms: Long, how: String): Array<Pair<String, Any?>> =
    arrayOf("pages" to pass.keys.size, "shown" to pass.shown.size, "changed" to pass.changed.size, "left" to left, "ms" to ms, "how" to how, "from" to pass.from)

/** The log's `review_save_ask` and `review_save_answer` lines: the pages asked about, the scan's pages and what was being made. */
internal fun scanReviewSaveDiag(pages: Int, scanPages: Int, out: String, answer: String? = null): Array<Pair<String, Any?>> =
    if (answer == null) arrayOf("pages" to pages, "scan_pages" to scanPages, "out" to out)
    else arrayOf("pages" to pages, "scan_pages" to scanPages, "out" to out, "answer" to answer)

/** Save's question about pages that still need a check (#783): the pages, and the save that waits for the answer. */
internal class ScanReviewSaveAsk(val keys: List<String>, val share: Boolean, val size: String?, val out: ScanOut)
