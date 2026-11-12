package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.ScanFlattenCheck
import io.github.artcraftandroid.pdfcraft.core.ScanLineFlatten
import org.json.JSONObject

/*
 * Flattening a curled page from its lines of text (#303, part B): the diagnostics fields and the
 * kept-scan page file's object. Numbers only, never document content.
 */

private fun rounded(v: Float, places: Double): Double = if (v.isFinite()) Math.round(v * places) / places else 0.0

private fun px(v: Float?): Double = v?.takeIf { it.isFinite() }?.let { Math.round(it * 100.0) / 100.0 } ?: -1.0

/**
 * The text-line fallback as diagnostics fields: whether it was allowed ([on]) and ran, why it
 * gave up (`none` when it fitted a surface), whether its page was kept and why, the fit's
 * numbers, and both pages' line measures in pixels (-1 when not measured).
 */
internal fun scanLineFlattenDiag(on: Boolean, l: ScanLineFlatten?): Array<Pair<String, Any?>> {
    val c: ScanFlattenCheck? = l?.check
    return arrayOf(
        "line_flatten" to on, "line_ran" to (l != null), "line_gave_up" to (l?.gaveUp ?: "none"),
        "line_kept" to (c?.kept ?: false), "line_why" to (c?.why ?: "none"),
        "line_lines" to (l?.lines?.toLong() ?: 0L), "line_points" to (l?.points?.toLong() ?: 0L), "line_steps" to (l?.steps?.toLong() ?: 0L),
        "line_alpha" to rounded(l?.alpha ?: 0f, 1000.0), "line_beta" to rounded(l?.beta ?: 0f, 1000.0),
        "line_rms_before" to rounded(l?.rmsBefore ?: 0f, 100.0), "line_rms_after" to rounded(l?.rmsAfter ?: 0f, 100.0),
        "line_px" to px(c?.straightenedPx), "line_flat_px" to px(c?.flattenedPx),
    )
}

/** The text-line fallback (#303, part B) for a kept scan's page file. */
internal fun scanLineFlattenJson(l: ScanLineFlatten): JSONObject = JSONObject()
    .put("gave_up", l.gaveUp ?: JSONObject.NULL)
    .put("lines", l.lines.toLong()).put("points", l.points.toLong()).put("steps", l.steps.toLong())
    .put("alpha", rounded(l.alpha, 1000.0)).put("beta", rounded(l.beta, 1000.0))
    .put("rms_before_px", rounded(l.rmsBefore, 100.0)).put("rms_after_px", rounded(l.rmsAfter, 100.0))
    .put("check", l.check?.let(::scanFlattenCheckJson) ?: JSONObject.NULL)
