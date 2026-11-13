package io.github.artcraftandroid.pdfcraft.ui.scan

import org.json.JSONObject

/**
 * What Save keeps from the page as it was shown (#465), so the PDF is what was seen: the page's
 * turn when the classifier gave it ([turns], 0–3; null when the person turned the page, whose own
 * turn is used anyway) and the document type Clean scan took it for ([preset]; null when the
 * person or the app chose the type, or the page had none).
 */
internal data class ScanShownDecisions(val turns: Int?, val preset: String?) {
    /** For a kept scan's page file. */
    fun json(): JSONObject = JSONObject().put("turns", turns ?: JSONObject.NULL).put("preset", preset ?: JSONObject.NULL)
}

/**
 * The decisions Save keeps from a page made in the background (#465): [manual] is the person's own
 * turn, [shownTurns] the turn the page was shown with, [kindSource] where its document type came
 * from (see `scanKindSource`) and [shownPreset] the type it was shown as. Null when nothing is kept.
 */
internal fun scanShownDecisions(manual: Int?, shownTurns: Int, kindSource: String, shownPreset: String?): ScanShownDecisions? {
    val turns = if (manual == null) scanQuarter(shownTurns) else null
    val preset = shownPreset?.takeIf { kindSource == "measured" }
    return if (turns == null && preset == null) null else ScanShownDecisions(turns, preset)
}
