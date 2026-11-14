package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanSession

/*
 * A saved scan edited from the viewer (#513): the PDF names the kept scan it was made from, a tap
 * on one of its pages opens that page in the scan's own screens, and Done puts the pages changed
 * back into the same PDF. What can be worked out without a phone is here, so it can be tested on
 * the desktop.
 */

/** The custom document property (/Info) that names the kept scan a PDF was made from. */
internal const val SCAN_LINK_KEY = "PdfCraftScan"

/** A kept scan's id as [ScanRecentStore] makes it: `scan-<millis>-<n>`. Nothing else is taken from a file. */
private val SCAN_ID = Regex("scan-[0-9]{1,19}-[0-9]{1,9}")

internal fun scanLinkIdValid(id: String?): Boolean = id != null && SCAN_ID.matches(id)

/** The kept scan [custom] (a PDF's custom properties, name to value) names; null for a PDF that is not one. */
internal fun scanLinkId(custom: List<Pair<String, String>>): String? =
    custom.firstOrNull { it.first == SCAN_LINK_KEY }?.second?.trim()?.takeIf(::scanLinkIdValid)

/** Why a PDF's page cannot be edited as its scan; null when it can. */
internal enum class ScanEditRefusal(val id: String) {
    /** The testing option is off. */
    Off("off"),
    /** The PDF names no kept scan. */
    NotAScan("not_a_scan"),
    /** The kept scan is gone (older than the last ten, deleted, or kept scans off). */
    Gone("gone"),
    /** The PDF's pages are no longer the scan's (pages added or removed in the viewer since). */
    PagesChanged("pages_changed"),
    /** Another scan is open in the scanner. */
    Busy("busy"),
}

/** Whether page edits from the viewer can go back to scan [scanPages] in a PDF of [pdfPages] pages. */
internal fun scanEditRefusal(optionOn: Boolean, linkId: String?, kept: Boolean, scanPages: Int, pdfPages: Int, scanOpen: Boolean): ScanEditRefusal? = when {
    !optionOn -> ScanEditRefusal.Off
    linkId == null -> ScanEditRefusal.NotAScan
    !kept -> ScanEditRefusal.Gone
    scanPages != pdfPages || scanPages == 0 -> ScanEditRefusal.PagesChanged
    scanOpen -> ScanEditRefusal.Busy
    else -> null
}

/**
 * One page of the scan as it was opened from the viewer: [key] says which page it is (its photo,
 * or its half of a spread), [signature] everything that makes it (photo, corners, look, turn,
 * flattening, marks), and [corners] where its borders were.
 */
internal data class ScanEditPage(val key: String, val signature: String, val corners: FloatArray) {
    override fun equals(other: Any?): Boolean = other is ScanEditPage && key == other.key && signature == other.signature && corners.contentEquals(other.corners)
    override fun hashCode(): Int = (key.hashCode() * 31 + signature.hashCode()) * 31 + corners.contentHashCode()
}

/** Everything that makes [page], as one line: two equal signatures make the same page. */
internal fun scanEditSignature(page: ScanSession.Page): String =
    ScanSession(null, null, false, "", listOf(page)).encode()

/** One step that puts the scan's pages back into the PDF (pages counted from 1). */
internal sealed interface ScanReplaceStep {
    /** PDF pages [pages] take the new file's pages [from], in order; comments on them stay. */
    data class Replace(val pages: List<Int>, val from: List<Int>) : ScanReplaceStep
    /** The new file's pages [from] go in so the first is PDF page [at]. */
    data class Insert(val at: Int, val from: List<Int>) : ScanReplaceStep
    /** PDF pages [pages] go. */
    data class Delete(val pages: List<Int>) : ScanReplaceStep
}

/**
 * How the scan's pages go back into the PDF they came from.
 *
 * [inPlace] is true when no page was added, deleted or moved: only the changed pages are replaced.
 * Otherwise the pages are put back by position: each position whose page changed is replaced,
 * pages beyond the old count are added at the end, and pages the scan no longer has go from the
 * end. [moved] are the PDF pages (from 1) now holding another page than before or whose borders,
 * turn or book half changed, so a comment on them may no longer sit over what it marked; a page
 * whose look alone changed is not among them.
 */
internal data class ScanReplacePlan(val steps: List<ScanReplaceStep>, val inPlace: Boolean, val changed: List<Int>, val moved: List<Int>) {
    /** Nothing changed: the PDF stays as it is. */
    val empty: Boolean get() = steps.isEmpty()
}

/** The plan from the pages as opened ([before], the PDF's pages in order) to the pages at Done ([after]). */
internal fun scanReplacePlan(before: List<ScanEditPage>, after: List<ScanEditPage>): ScanReplacePlan {
    val inPlace = before.map { it.key } == after.map { it.key }
    val common = minOf(before.size, after.size)
    val replaced = (0 until common).filter { before[it] != after[it] }
    // the page at a position is another one, or its borders moved: what was drawn there may not fit
    val moved = replaced.filter { before[it].key != after[it].key || !before[it].corners.contentEquals(after[it].corners) || scanGeometry(before[it].signature) != scanGeometry(after[it].signature) }
    val steps = ArrayList<ScanReplaceStep>()
    if (replaced.isNotEmpty()) steps += ScanReplaceStep.Replace(replaced.map { it + 1 }, replaced.map { it + 1 })
    if (after.size > before.size) steps += ScanReplaceStep.Insert(before.size + 1, (before.size + 1..after.size).toList())
    if (before.size > after.size) steps += ScanReplaceStep.Delete((after.size + 1..before.size).toList())
    return ScanReplacePlan(steps, inPlace, replaced.map { it + 1 }, moved.map { it + 1 })
}

/**
 * The parts of a page's line that place it on the photo (corners, turn, book half, flattening),
 * without the look, the marks or the automatic look's chips: a change here moves what is on the page.
 */
private fun scanGeometry(signature: String): String =
    // page, path, corners, found, turns, preset, book, flatten: what was found and the document
    // type do not move the page
    signature.lineSequence().firstOrNull { it.startsWith("page\t") }.orEmpty().split('\t')
        .filterIndexed { i, _ -> i != 3 && i != 5 }.joinToString("\t")

/** The comments to ask about before Done: those on [moved] pages ([commentPages] are each comment's page, from 1). */
internal fun scanCommentsOnMoved(commentPages: List<Int>, moved: List<Int>): Int {
    val set = moved.toSet()
    return commentPages.count { it in set }
}

/** The log's line for a scan saved (#513): whether the PDF names its kept scan, and its pages. */
internal fun scanLinkDiag(linked: Boolean, pages: Int): Array<Pair<String, Any?>> =
    arrayOf("linked" to linked, "pages" to pages)

/** The log's line for a page opened from the viewer in the scan's screens (#513); [index] from 1. */
internal fun scanViewerEditDiag(index: Int, source: String, savedAt: Long?, now: Long): Array<Pair<String, Any?>> =
    arrayOf("index" to index, "source" to source, "age_h" to (savedAt?.let { (now - it).coerceAtLeast(0L) / 3_600_000L } ?: -1L))

/** The log's line for the pages put back into the PDF (#513). */
internal fun scanReplaceDiag(plan: ScanReplacePlan, bytesBefore: Long, bytesAfter: Long, commentsOnPages: Int, commentsRemoved: Boolean, ms: Long): Array<Pair<String, Any?>> =
    arrayOf(
        "pages" to plan.changed.size, "in_place" to plan.inPlace,
        "added" to plan.steps.filterIsInstance<ScanReplaceStep.Insert>().sumOf { it.from.size },
        "deleted" to plan.steps.filterIsInstance<ScanReplaceStep.Delete>().sumOf { it.pages.size },
        "bytes_before" to bytesBefore, "bytes_after" to bytesAfter,
        "comments_on_pages" to commentsOnPages, "comments_removed" to commentsRemoved, "ms" to ms,
    )
