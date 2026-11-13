package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanTrim
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import org.json.JSONObject

/*
 * Flatten page, one page at a time (#302). The switch on the scan screen flattens the page shown;
 * each page keeps its own choice. A page taken afterwards starts with the choice of the page
 * before it (a retake, with the choice of the page it replaces), so a run of book pages needs one
 * tap, not one per page.
 */

/** Whether a page with the Flatten page [choice] is flattened when it is made: chosen, and the model is installed. */
internal fun scanPageFlattens(choice: Boolean?, canFlatten: Boolean): Boolean = canFlatten && choice == true

/**
 * The choice a new page starts with, given the scan's pages' [choices] in order: that of the page
 * it replaces ([retake]), else of the last page; null when there is none.
 */
internal fun scanNewPageFlatten(choices: List<Boolean?>, retake: Int?): Boolean? =
    if (retake != null && retake in choices.indices) choices[retake] else choices.lastOrNull()

/** The person's choice for a page as a short word for the log: on, off, or none when the page has none. */
internal fun scanFlattenChoiceId(flatten: Boolean?): String = when (flatten) {
    true -> "on"
    false -> "off"
    null -> "none"
}

/** The scan screen's switch: flatten the page at [index], or stop flattening it (#302). */
internal fun AppViewModel.toggleScanFlatten(index: Int) {
    // a page with marks asks first: flattening moves the page under them (#495)
    if (scanMarkupAsks(index, "flatten") { toggleScanFlatten(index) }) return
    val page = scanPages.getOrNull(index) ?: return
    val on = page.flatten != true
    scanPages[index] = page.copy(flatten = on)
    Diag.event(DiagArea.Scan, "flatten_page", "index" to index + 1, "flatten" to on)
}

/** A trim of four shares (0–0.02) as percent of the page's side, two decimals; 0 for anything that is not a number. */
private fun pct(v: Float): Double = if (v.isFinite()) Math.round(v * 10000.0) / 100.0 else 0.0

/**
 * The border trim of a flattened page (#302) as diagnostics fields: whether one was looked for,
 * whether anything was cut, and how much of each side, in percent (-1 when none was looked for).
 */
internal fun scanTrimDiag(t: ScanTrim?): Array<Pair<String, Any?>> = arrayOf(
    "trim_looked" to (t != null),
    "trimmed" to (t != null && (t.left > 0f || t.top > 0f || t.right > 0f || t.bottom > 0f)),
    "trim_left_pct" to (t?.let { pct(it.left) } ?: -1.0), "trim_top_pct" to (t?.let { pct(it.top) } ?: -1.0),
    "trim_right_pct" to (t?.let { pct(it.right) } ?: -1.0), "trim_bottom_pct" to (t?.let { pct(it.bottom) } ?: -1.0),
)

/** The border trim (#302) for a kept scan's page file: each side in percent. */
internal fun scanTrimJson(t: ScanTrim): JSONObject =
    JSONObject().put("left_pct", pct(t.left)).put("top_pct", pct(t.top)).put("right_pct", pct(t.right)).put("bottom_pct", pct(t.bottom))
