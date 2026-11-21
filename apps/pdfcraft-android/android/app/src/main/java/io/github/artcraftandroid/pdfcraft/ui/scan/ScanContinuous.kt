package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONObject

/*
 * The document screen's continuous pages (#786, part of #778): after Done the scan opens on its
 * first page, each page as wide as the screen (up to a comfortable width on a tablet), one under
 * the other, with "Page n of m". Organize pages switches to the grid (#460), which keeps hold and
 * drag, selection and the batch tools, and View pages switches back to the same page. Both read
 * the same `scanPages` and the same page queue. Testing option "Open a finished scan as continuous
 * pages" (`continuousPages`): off is the grid alone, as before. What can be worked out without a
 * screen is here, so it can be tested on the desktop.
 */

/** The document screen's two views of the same pages (#786). */
internal enum class ScanDocView(val id: String) {
    /** Each page whole, one under the other. */
    Pages("pages"),

    /** The grid of #460, where pages are moved, chosen and deleted. */
    Grid("grid"),
}

/**
 * The view the document screen shows (#786): the one the person switched to, else the continuous
 * pages; the grid alone with the testing option off, as before.
 */
internal fun scanDocViewShown(chosen: ScanDocView?, continuous: Boolean): ScanDocView =
    if (!continuous) ScanDocView.Grid else chosen ?: ScanDocView.Pages

/**
 * The page both views come back to (#786): its [key] ([io.github.artcraftandroid.pdfcraft.ui.ScanPage.key],
 * which stays the page's own when others move) and the place it had, for when the key is gone.
 */
internal data class ScanDocAnchor(val key: String?, val index: Int)

/**
 * Where [anchor] is among [keys] now (#786): its page when it is still in the scan, wherever it
 * was moved to. A page retaken has a new key in the same place, and a page deleted leaves the next
 * one in its place: both are found by the place, the last page when that place is past the end.
 * 0 for a scan with no page.
 */
internal fun scanAnchorIndex(anchor: ScanDocAnchor, keys: List<String>): Int {
    if (keys.isEmpty()) return 0
    val at = anchor.key?.let { keys.indexOf(it) } ?: -1
    return if (at >= 0) at else anchor.index.coerceIn(0, keys.lastIndex)
}

/**
 * The page the continuous view opens on when the grid is left (#786): the anchor while it is in
 * sight in the grid ([visible], the indexes on screen), else the first page in sight, so the pages
 * the person scrolled the grid to are the ones shown.
 */
internal fun scanAnchorFromGrid(anchor: Int, visible: List<Int>): Int =
    if (visible.isEmpty() || anchor in visible) anchor else visible.min()

/** A page's place on the screen in the continuous view: where it starts and how long it is, in pixels along the scroll. */
internal data class ScanDocSpan(val index: Int, val start: Int, val size: Int)

/**
 * The page "Page n of m" names (#786): the one with the most of itself inside the window
 * ([viewStart] to [viewEnd]), the earlier of two with the same; the last page once the list is at
 * its end (a short last page never fills the window), unless the whole scan fits. Null with no page laid out.
 */
internal fun scanCurrentPage(items: List<ScanDocSpan>, viewStart: Int, viewEnd: Int, atStart: Boolean, atEnd: Boolean): Int? {
    if (items.isEmpty()) return null
    if (atEnd && !atStart) return items.maxOf { it.index }
    var best: ScanDocSpan? = null
    var bestShown = Int.MIN_VALUE
    for (item in items.sortedBy { it.index }) {
        val shown = minOf(item.start + item.size, viewEnd) - maxOf(item.start, viewStart)
        if (shown > bestShown) {
            best = item
            bestShown = shown
        }
    }
    return best?.index
}

/** The widest a page is drawn in the continuous view, in dp (#786): a phone's whole width, a comfortable column on a tablet. */
internal const val SCAN_DOC_PAGE_MAX_DP = 640

/**
 * A page's width over its height as it is drawn (#786), from the [width] × [height] of what is
 * shown, turned by [quarterTurns]; kept between a long receipt (1:4) and a wide strip (4:1), and
 * A4's 0.707 when the size is not known.
 */
internal fun scanDocPageAspect(width: Int, height: Int, quarterTurns: Int = 0): Float {
    if (width <= 0 || height <= 0) return SCAN_DOC_PAGE_ASPECT
    val turned = ((quarterTurns % 4) + 4) % 4 % 2 == 1
    val aspect = if (turned) height.toFloat() / width else width.toFloat() / height
    return aspect.coerceIn(0.25f, 4f)
}

internal const val SCAN_DOC_PAGE_ASPECT = 0.707f

/**
 * What a page in the continuous view says about itself (#786). Only [Ready] is the page as it will
 * be saved; every other state is said on the page, so a quick version or the photo is never taken
 * for the finished page.
 */
internal enum class ScanDocPageState(val id: String) {
    /** Made from the page as it is now. */
    Ready("ready"),

    /** Being made now: its quick version, its last result or its photo is shown meanwhile. */
    Working("working"),

    /** Waits for its turn in the queue. */
    Waiting("waiting"),

    /** Could not be made: Try again asks once more. */
    Failed("failed"),

    /** Pages are not made in the background at all (a testing option): the photo as taken. */
    Photo("photo"),
}

/**
 * A page's state (#786) from the page queue: [ready] is the queue's own check that the result was
 * made from the page as it is now (`readyOf`), so a result of other corners, another look or a
 * photo since retaken is never ready.
 */
internal fun scanDocPageState(background: Boolean, ready: Boolean, working: Boolean, failed: Boolean): ScanDocPageState = when {
    !background -> ScanDocPageState.Photo
    ready -> ScanDocPageState.Ready
    failed -> ScanDocPageState.Failed
    working -> ScanDocPageState.Working
    else -> ScanDocPageState.Waiting
}

/**
 * Whether a page in the continuous view offers Check for its own borders (#786, #758): an unsure
 * page, with the unsure check and the passes over several pages on.
 */
internal fun scanDocPageChecks(unsure: Boolean, checkUnsure: Boolean, batchBorders: Boolean): Boolean = unsure && checkUnsure && batchBorders

/** The `doc_view` line (#786): the view switched to and from, the page it was on (from 1), the scan's pages, and what switched it (`button`, `select`). */
internal fun scanDocViewDiag(to: ScanDocView, from: ScanDocView, anchor: Int, pages: Int, by: String): Array<Pair<String, Any?>> =
    arrayOf("to" to to.id, "from" to from.id, "page" to anchor + 1, "pages" to pages, "by" to by)

/**
 * The `doc_page_ready` line (#786): the page the document screen opened on (from 1), the time from
 * the screen opening to that page shown as made (0 when it already was), and what it showed when
 * the screen opened.
 */
internal fun scanDocPageReadyDiag(page: Int, pages: Int, ms: Long, view: ScanDocView, openedAs: ScanDocPageState): Array<Pair<String, Any?>> =
    arrayOf("page" to page + 1, "pages" to pages, "ms" to ms.coerceAtLeast(0L), "view" to view.id, "opened_as" to openedAs.id)

/** The `doc_pages_ready` line (#786): the time from the screen opening until no page waited any more, and how many could not be made. */
internal fun scanDocAllReadyDiag(pages: Int, failed: Int, ms: Long, view: ScanDocView): Array<Pair<String, Any?>> =
    arrayOf("pages" to pages, "failed" to failed, "ms" to ms.coerceAtLeast(0L), "view" to view.id)

/** What a page showed when the continuous view first drew it, and when it was made (#786), for a kept scan's page file. */
internal data class ScanDocPageRecord(val shownAs: String, val shownAt: Long, val readyAt: Long?) {
    /** From first drawn to made: 0 when it was made already, -1 when it never was while it was in sight. */
    val readyMs: Long get() = readyAt?.let { (it - shownAt).coerceAtLeast(0L) } ?: -1L
}

/**
 * The document screen's view between its visits (#786): the screen leaves the composition while a
 * page is open in Enhance or Adjust borders, so the view, the page to come back to and what each
 * page showed are kept here, with the steps' state, and go when the scan ends.
 */
internal class ScanDocViewState {
    /** The view the person switched to; null until they do. */
    var chosen by mutableStateOf<ScanDocView?>(null)
        private set

    /** The page both views come back to. */
    var anchor = ScanDocAnchor(null, 0)

    /** What opened the document screen last (`camera`, `import`, `enhance`, `borders`, `recent`), for the log. */
    var route = "none"

    /** How many times the view was switched in this scan. */
    var toggles = 0
        private set

    private val records = HashMap<String, ScanDocPageRecord>()

    /** The person switched to [view]. */
    fun show(view: ScanDocView) {
        if (chosen == view) return
        chosen = view
        toggles++
    }

    /** The continuous view drew the page [key] in [state] at [now] (ms): only the first time counts. */
    fun pageShown(key: String, state: ScanDocPageState, now: Long) {
        if (key !in records) records[key] = ScanDocPageRecord(state.id, now, if (state == ScanDocPageState.Ready) now else null)
    }

    /** The page [key] is made, at [now] (ms): the first time after it was drawn counts. */
    fun pageReady(key: String, now: Long) {
        val record = records[key] ?: return
        if (record.readyAt == null) records[key] = record.copy(readyAt = now)
    }

    fun record(key: String): ScanDocPageRecord? = records[key]

    /**
     * The page file's `document_view` for the page [key] (#786): the view the scan was left in, how
     * it was reached and how often it was switched, whether the continuous view drew this page, as
     * what, and how long until it was made. Null with the testing option off.
     */
    fun json(key: String, continuous: Boolean): JSONObject? {
        if (!continuous) return null
        val record = records[key]
        return JSONObject()
            .put("view", scanDocViewShown(chosen, continuous).id).put("route", route).put("toggles", toggles)
            .put("shown", record != null).put("shown_as", record?.shownAs ?: JSONObject.NULL).put("ready_ms", record?.readyMs ?: -1L)
    }

    /** The scan ended. */
    fun reset() {
        chosen = null
        anchor = ScanDocAnchor(null, 0)
        route = "none"
        toggles = 0
        records.clear()
    }
}
