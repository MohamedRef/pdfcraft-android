package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanPaper
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPage

/*
 * A scan's pages share one paper size, and a page is an ID card only from a sure outline (#759).
 * The owner's scan of 2026-11-19: of six photos of one A4 page, one came out Letter and one at a
 * ratio of 1.51, and two parts of a pile of A4 sheets came out as ID cards. The engine matches
 * each page (`aspect.rs`); what can be worked out without a phone is here.
 */

/** The papers a scan's own paper can be: those with a fixed shape. */
private val SHAPED = setOf(ScanPaper.A4, ScanPaper.A5, ScanPaper.LETTER, ScanPaper.LEGAL, ScanPaper.ID_CARD, ScanPaper.SQUARE)

/**
 * The paper most of [papers] are (#759): the papers of a scan's other pages that count, null for
 * one not made yet. At least two of those made, and more than half, must agree; null otherwise.
 */
internal fun scanSharedPaper(papers: List<ScanPaper?>): ScanPaper? {
    val made = papers.filterNotNull()
    val (paper, votes) = made.filter { it in SHAPED }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.toPair() ?: return null
    return paper.takeIf { votes >= 2 && votes * 2 > made.size }
}

/** Whether a page has an outline of its own: one was found, or the person set its corners; the whole photo it started as has none. */
internal fun scanHasOutline(corners: FloatArray, found: FloatArray?, uncropped: FloatArray?): Boolean =
    found != null || !corners.contentEquals(uncropped ?: ScanPage.WHOLE)

/** [scanHasOutline] for a page of the scan. */
internal fun scanHasOutline(page: ScanPage): Boolean = scanHasOutline(page.corners, page.found, page.uncropped)

/** Whether [page]'s paper counts towards the scan's own (#759): it has an outline and it is not unsure (#758). */
internal fun scanPaperCounts(page: ScanPage): Boolean = scanHasOutline(page) && !scanPageUnsure(page)

/**
 * The scan's own paper for [page] (#759): what most of the scan's other pages that count were made
 * at ([paperOf]), with the testing option and paper matching on; null otherwise, or for a page with
 * no outline (the whole photo keeps its own shape).
 */
internal fun scanPaperHintFor(page: ScanPage, pages: List<ScanPage>, paperOf: (ScanPage) -> ScanPaper?, lab: ScanLab): ScanPaper? {
    if (!lab.paperShared || !lab.snapPaper || !scanHasOutline(page)) return null
    return scanSharedPaper(pages.filter { it.key != page.key && scanPaperCounts(it) }.map(paperOf))
}

/** Whether a page may be matched to ID card (#759): with the testing option on, only when its outline is not [unsure] (#758). */
internal fun scanCardPaper(cardSure: Boolean, unsure: Boolean): Boolean = !cardSure || !unsure

/** [scanCardPaper] for a page of the scan. */
internal fun scanCardPaperFor(page: ScanPage, lab: ScanLab): Boolean = scanCardPaper(lab.cardSure, scanPageUnsure(page))

/** The log's fields for the rules (#759): the scan's own paper asked for ("none"), whether a card was allowed, and the paper made, when known. */
internal fun scanPaperRuleDiag(hint: ScanPaper?, card: Boolean, made: ScanPaper?): Array<Pair<String, Any?>> =
    arrayOf<Pair<String, Any?>>("paper_hint" to (hint?.name?.lowercase() ?: "none"), "card_paper" to card) +
        (made?.let { arrayOf<Pair<String, Any?>>("paper_size" to it.name.lowercase()) } ?: emptyArray())
