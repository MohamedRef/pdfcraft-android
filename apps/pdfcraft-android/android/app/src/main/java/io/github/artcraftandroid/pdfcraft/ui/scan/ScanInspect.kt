package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanInspection
import io.github.artcraftandroid.pdfcraft.core.ScanRegions
import org.json.JSONObject

/*
 * What the research detectors saw on a kept page (#305 moiré, #306 fingers, glare and stains),
 * for the diagnostics log's `inspect` line and the page file's `inspect` key. Detection only:
 * the detectors' thresholds were set on drawn pages, so these are for comparing with what the
 * person saw, never for a decision in the app.
 */

private fun round(v: Float?, places: Double): Double? = v?.takeIf { it.isFinite() }?.let { Math.round(it * places) / places }

/** The inspection as diagnostics fields: short lowercase words, booleans and numbers only (-1 for not known). */
internal fun scanInspectDiag(i: ScanInspection): Array<Pair<String, Any?>> {
    fun regions(name: String, r: ScanRegions?): Array<Pair<String, Any?>> = arrayOf(
        name to (r?.count?.toLong() ?: -1L),
        "${name}_over_print" to (r?.overPrint?.toLong() ?: -1L),
        "${name}_area" to (round(r?.area, 10000.0) ?: -1.0),
    )
    return arrayOf(
        "width" to i.width.toLong(), "height" to i.height.toLong(),
        "moire" to i.moire, "moire_period" to (round(i.moirePeriod, 100.0) ?: -1.0),
        "moire_tiles" to i.moireTiles.toLong(), "moire_busy_tiles" to i.moireBusyTiles.toLong(), "moire_periodic_tiles" to i.moirePeriodicTiles.toLong(),
        *regions("fingers", i.fingers), *regions("glare", i.glare), *regions("stains", i.stains),
        "flagged" to i.flagged.toLong(),
        *i.timings.map { it.stage.removePrefix("scan_") + "_ms" to it.ms.toLong() }.toTypedArray(),
    )
}

/** The inspection for a kept scan's page file. */
internal fun scanInspectJson(i: ScanInspection): JSONObject {
    fun regions(r: ScanRegions?): Any = r?.let {
        JSONObject().put("count", it.count.toLong()).put("over_print", it.overPrint.toLong()).put("area", round(it.area, 10000.0) ?: 0.0)
    } ?: JSONObject.NULL
    val timings = JSONObject()
    for (t in i.timings) timings.put(t.stage, t.ms.toLong())
    return JSONObject()
        .put("width", i.width.toLong()).put("height", i.height.toLong())
        .put(
            "moire",
            JSONObject().put("kind", i.moire).put("period", round(i.moirePeriod, 100.0) ?: JSONObject.NULL)
                .put("tiles", i.moireTiles.toLong()).put("busy_tiles", i.moireBusyTiles.toLong()).put("periodic_tiles", i.moirePeriodicTiles.toLong()),
        )
        .put("fingers", regions(i.fingers)).put("glare", regions(i.glare)).put("stains", regions(i.stains))
        .put("flagged", i.flagged.toLong())
        .put("timings_ms", timings)
}
