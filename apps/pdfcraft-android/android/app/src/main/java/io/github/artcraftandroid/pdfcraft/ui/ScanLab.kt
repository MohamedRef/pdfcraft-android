package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.ScanFindOptions
import io.github.artcraftandroid.pdfcraft.core.ScanFinder
import io.github.artcraftandroid.pdfcraft.ui.scan.scanTrimJson
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The scanner's testing options (#288), under Settings, About, Diagnostics. Each scanner feature
 * can be switched off or swapped for what it replaced, so a page that comes out wrong on a real
 * phone can be pinned on one of them, and the scans can be kept to be looked at afterwards.
 * The defaults are the scanner as it is meant to be used.
 */
internal data class ScanLab(
    /** Keep each scan's photos, corners, settings and results on the device (see [ScanTestStore]). */
    val keep: Boolean = false,
    /** Which detector finds the page (#290). */
    val finder: ScanFinder = ScanFinder.AUTO,
    /** Move the corners found onto the paper's edges on the full-size photo (#291). */
    val refine: Boolean = true,
    /** Work the page's proportions out from its perspective (#293), not its side lengths alone. */
    val perspective: Boolean = true,
    /** Match the page to a standard paper size when it is within 3 % of one (#293). */
    val snapPaper: Boolean = true,
    /** A strength (0–100) for every look in place of each look's own; null for the look's own. */
    val strength: Int? = null,
    /** Turn each page the right way up when the orientation classifier is sure which way that is (#339). */
    val upright: Boolean = true,
    /** How much the Clean scan filter sharpens print, 0–100; 0 for none (#299). */
    val sharpen: Int = 30,
    /** Whether the Clean scan filter smooths the grain of a page that has a lot of it (#299). */
    val denoise: Boolean = true,
    /** Show each page as it will be saved on the scan screen (#301); off shows the camera's photo, as before. */
    val preview: Boolean = true,
    /** Crop with the magnifier, side handles and snapping to the paper's edge (#292); off is the old four-handle screen. */
    val cropTools: Boolean = true,
    /**
     * In a scan of several pages, a page the orientation classifier was not sure of takes the turn
     * of its two neighbours when both are sure and agree (#339). Off saves each page by its own answer.
     */
    val settleTurns: Boolean = true,
    /**
     * Flatten every page with the network and keep the flattened page only when its lines of text
     * come out at least 10 % straighter (#302's automatic mode). Off: a page is flattened only when
     * the scan screen's switch asks for it. Off by default until real photos have shown it helps.
     */
    val flattenAuto: Boolean = false,
    /**
     * Trim a thin strip of background left along a flattened page's edges (2 % of a side at most)
     * and draw the page again without it (#302). Off keeps the flattened page as the network gives it.
     */
    val flattenTrim: Boolean = true,
    /**
     * Clean scan takes each page for a kind of document (receipt, whiteboard, book page, …) and
     * uses that kind's strength, sharpening and paper colour (#299). Off uses the filter's own
     * strength and the sharpening above, as before.
     */
    val presets: Boolean = true,
    /** In book mode, cut a spread along the gutter found in the photo (#303); off cuts every spread at its centre line. */
    val bookGutter: Boolean = true,
) {
    /** Nothing differs from the scanner as it is meant to be used (keeping scans changes no page). */
    val asDesigned: Boolean get() = copy(keep = false) == ScanLab()

    fun findOptions() = ScanFindOptions(finder, refine)

    /** The strength a look with its own [level] is applied at. */
    fun strengthFor(level: Int): Int = (strength ?: level).coerceIn(0, 100)

    /** The options as diagnostics fields: short lowercase words, booleans and numbers only. */
    fun diag(): Array<Pair<String, Any?>> = arrayOf<Pair<String, Any?>>(
        "finder" to finderId(finder), "refine" to refine, "side_proportions" to !perspective,
        "snap_paper" to snapPaper, "strength_override" to (strength ?: -1), "upright" to upright, "sharpen" to sharpen,
        "denoise" to denoise, "preview" to preview, "crop_tools" to cropTools, "flatten_auto" to flattenAuto, "flatten_trim" to flattenTrim, "settle_turns" to settleTurns, "presets" to presets, "book_gutter" to bookGutter, "keep" to keep,
    )

    /** The options as JSON, for a kept scan's page file. */
    fun json(): JSONObject = JSONObject()
        .put("finder", finderId(finder)).put("refine", refine).put("side_proportions", !perspective)
        .put("snap_paper", snapPaper).put("strength_override", strength ?: JSONObject.NULL).put("upright", upright)
        .put("sharpen", sharpen).put("denoise", denoise).put("preview", preview).put("crop_tools", cropTools).put("flatten_auto", flattenAuto).put("flatten_trim", flattenTrim).put("settle_turns", settleTurns).put("presets", presets).put("book_gutter", bookGutter)

    /** One line of text for the app's preferences. */
    fun encode(): String = listOf(
        "keep" to flag(keep), "finder" to finderId(finder), "refine" to flag(refine),
        "perspective" to flag(perspective), "snap" to flag(snapPaper), "strength" to (strength?.toString() ?: ""),
        "upright" to flag(upright), "sharpen" to sharpen.toString(), "denoise" to flag(denoise), "preview" to flag(preview), "crop" to flag(cropTools), "flatten_auto" to flag(flattenAuto), "flatten_trim" to flag(flattenTrim),
        "settle" to flag(settleTurns), "presets" to flag(presets), "gutter" to flag(bookGutter),
    ).joinToString(";") { (k, v) -> "$k=$v" }

    companion object {
        /** The strengths offered: null is each look's own. */
        val STRENGTHS: List<Int?> = listOf(null, 20, 40, 60, 80, 100)

        /** How much sharpening is offered: none, the default, and two steps more. */
        val SHARPENING: List<Int> = listOf(0, 30, 60, 100)

        private fun flag(on: Boolean) = if (on) "1" else "0"

        fun finderId(finder: ScanFinder): String = when (finder) {
            ScanFinder.AUTO -> "auto"
            ScanFinder.NETWORK -> "network"
            ScanFinder.LINES -> "lines"
            ScanFinder.OFF -> "off"
        }

        /** The options in [text]; anything missing or not understood is the default. */
        fun decode(text: String?): ScanLab {
            val values = text.orEmpty().split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val default = ScanLab()
            fun on(key: String, otherwise: Boolean) = when (values[key]) {
                "1" -> true
                "0" -> false
                else -> otherwise
            }
            return ScanLab(
                keep = on("keep", default.keep),
                finder = ScanFinder.entries.firstOrNull { finderId(it) == values["finder"] } ?: default.finder,
                refine = on("refine", default.refine),
                perspective = on("perspective", default.perspective),
                snapPaper = on("snap", default.snapPaper),
                strength = values["strength"]?.toIntOrNull()?.takeIf { it in 0..100 },
                upright = on("upright", default.upright),
                sharpen = values["sharpen"]?.toIntOrNull()?.takeIf { it in 0..100 } ?: default.sharpen,
                denoise = on("denoise", default.denoise),
                preview = on("preview", default.preview),
                cropTools = on("crop", default.cropTools),
                settleTurns = on("settle", default.settleTurns),
                flattenAuto = on("flatten_auto", default.flattenAuto),
                flattenTrim = on("flatten_trim", default.flattenTrim),
                presets = on("presets", default.presets),
                bookGutter = on("gutter", default.bookGutter),
            )
        }
    }
}

/** What the search for a page's edges came back with (#290, #291): kept with a scan kept for testing. */
data class ScanFinding(val source: String, val confidence: Double, val angle: Int, val passes: Int, val refinedSides: Int, val curvedSides: Int)

/** A page as Save made it, for a kept scan's page file. */
internal data class ScanTestResult(
    val width: Long,
    val height: Long,
    val format: String,
    val dpi: Double,
    val paper: String,
    val aspect: String,
    val focalMismatch: Boolean,
    val flattened: Boolean,
    val bendPct: Double,
    /** The quarter turns clockwise the page was given to stand upright (#339). */
    val quarterTurns: Int = 0,
    /** What the orientation classifier made of the page, or null when it was not asked. */
    val orientation: ScanTestOrientation? = null,
    /** The page's grain in grey levels, as the Clean scan filter measured it (#299); null for the other filters. */
    val noise: Double? = null,
    /** How the flattened and the straightened page compared, when flattening was checked (#302). */
    val flattenCheck: io.github.artcraftandroid.pdfcraft.core.ScanFlattenCheck? = null,
    /** The strips of background trimmed from a flattened page (#302), or null when none was looked for. */
    val trim: io.github.artcraftandroid.pdfcraft.core.ScanTrim? = null,
)

/**
 * How a saved page's turn was decided (#339): the person's own turn ([manual], null when there is
 * none), the automatic-turn setting, the turn the page was saved with ([total], 0–3 clockwise),
 * whether that turn came from its neighbours, and a short word for where it came from.
 */
internal data class ScanTestTurn(val manual: Int?, val auto: Boolean, val total: Int, val followed: Boolean, val source: String)

/**
 * The kind of document Clean scan took a saved page for (#299), its settings and what the page
 * measured as: the native `ScanPageKind`, for a kept scan's page file.
 */
internal data class ScanTestKind(
    val preset: String,
    val chosen: Boolean,
    val strength: Int,
    val sharpen: Int,
    val keepPaper: Boolean,
    val suggestFlatten: Boolean,
    val suggestGreyscale: Boolean,
    val ratio: Double,
    val inkShare: Double,
    val colourInkShare: Double,
    val photoShare: Double,
    /** -1 when the page has no ink. */
    val strokeMm: Double,
    val glareShare: Double,
    val gutterBand: Boolean,
    val wholePhoto: Boolean,
)

/** The orientation classifier's answer for a page (#339): the turn it would give, and how sure it was. */
internal data class ScanTestOrientation(val quarterTurns: Int, val confidence: Double, val agreement: Double, val tiles: Int, val sure: Boolean)

/**
 * The last preview the scan screen showed of a page before it was saved (#301): whether one was
 * shown at all, whether its straightened page was reused, how long it took and its size. A page
 * saved without one (the option off, or saved before the preview came) has [shown] false.
 */
internal data class ScanTestPreview(val shown: Boolean, val reused: Boolean, val ms: Long, val width: Int, val height: Int, val look: String?)

/** The name, without an extension, of page [index] (from 0) of a kept scan. */
internal fun scanTestName(index: Int): String = String.format(Locale.US, "page-%02d", index + 1)

private fun shares(values: FloatArray): JSONArray = JSONArray().also { out ->
    // five decimals of a share is a twentieth of a pixel on a 50-megapixel photo
    for (v in values) out.put(if (v.isFinite()) Math.round(v * 100000.0) / 100000.0 else 0.0)
}

/**
 * A kept page's facts as JSON. The first five keys are the scan test set's own (#288): `corners`
 * are the page's corners as the person left them, as 8 shares of the upright photo, which become
 * ground truth once someone has checked them. The rest says how the app got there: what it found
 * by itself, the look and options in force, and the page it made ([output] is its file's name).
 */
internal fun scanTestPageJson(
    app: String,
    saved: Boolean,
    corners: FloatArray,
    found: FloatArray?,
    finding: ScanFinding?,
    look: String?,
    strength: Int?,
    flatten: Boolean,
    lab: ScanLab,
    output: String?,
    result: ScanTestResult?,
    preview: ScanTestPreview? = null,
    crop: io.github.artcraftandroid.pdfcraft.ui.scan.ScanCropStats? = null,
    /** How the photo was taken (#294): which camera and with what; null when not known. */
    capture: JSONObject? = null,
    models: JSONObject? = null,
    turn: ScanTestTurn? = null,
    kind: ScanTestKind? = null,
    /** Which half of a book spread the page is, and how the spread was cut (#303); null for a page of its own. */
    book: io.github.artcraftandroid.pdfcraft.ui.scan.ScanBookHalf? = null,
    /** The person's Flatten page choice for the page (#302); null when there is none. */
    flattenChosen: Boolean? = null,
): String {
    val page = JSONObject()
        .put("corners", shares(corners)).put("kind", "other").put("paper", "unknown").put("curved", false).put("notes", "")
        .put("app", app).put("outcome", if (saved) "saved" else "discarded")
        .put("found", found?.let(::shares) ?: JSONObject.NULL)
        .put(
            "finding",
            finding?.let {
                JSONObject().put("source", it.source).put("confidence", Math.round(it.confidence * 1000.0) / 1000.0).put("angle", it.angle)
                    .put("passes", it.passes).put("refined_sides", it.refinedSides).put("curved_sides", it.curvedSides)
            } ?: JSONObject.NULL,
        )
        .put("look", look ?: JSONObject.NULL).put("strength", strength ?: JSONObject.NULL).put("flatten", flatten)
        // the person's own Flatten page choice for this page (#302): true, false or null for none
        .put("flatten_chosen", flattenChosen ?: JSONObject.NULL)
        .put("options", lab.json())
        .put(
            "document_type",
            kind?.let {
                fun r(v: Double, places: Double = 1000.0) = if (v.isFinite()) Math.round(v * places) / places else 0.0
                JSONObject().put("preset", it.preset).put("chosen", it.chosen).put("strength", it.strength).put("sharpen", it.sharpen)
                    .put("keep_paper", it.keepPaper).put("suggest_flatten", it.suggestFlatten).put("suggest_greyscale", it.suggestGreyscale)
                    .put("ratio", r(it.ratio)).put("ink_share", r(it.inkShare)).put("colour_ink_share", r(it.colourInkShare))
                    .put("photo_share", r(it.photoShare)).put("stroke_mm", r(it.strokeMm)).put("glare_share", r(it.glareShare))
                    .put("gutter_band", it.gutterBand).put("whole_photo", it.wholePhoto)
            } ?: JSONObject.NULL,
        )
        .put(
            "turns",
            turn?.let {
                JSONObject().put("manual", it.manual ?: JSONObject.NULL).put("auto", it.auto).put("total", it.total)
                    .put("followed", it.followed).put("source", it.source)
            } ?: JSONObject.NULL,
        )
        .put(
            "preview",
            preview?.let {
                JSONObject().put("shown", it.shown).put("reused", it.reused).put("ms", it.ms).put("width", it.width).put("height", it.height)
                    .put("look", it.look ?: JSONObject.NULL)
            } ?: JSONObject.NULL,
        )
        .put("crop", crop?.json() ?: JSONObject.NULL)
        .put("capture", capture ?: JSONObject.NULL)
        .put("models", models ?: JSONObject.NULL)
        .put("book", book?.json() ?: JSONObject.NULL)
    page.put(
        "result",
        result?.let {
            JSONObject().put("file", output ?: JSONObject.NULL).put("width", it.width).put("height", it.height).put("format", it.format)
                .put("dpi", if (it.dpi.isFinite()) it.dpi else 0.0).put("paper", it.paper).put("aspect", it.aspect)
                .put("focal_mismatch", it.focalMismatch).put("flattened", it.flattened).put("bend_pct", if (it.bendPct.isFinite()) Math.round(it.bendPct * 1000.0) / 1000.0 else 0.0)
                .put("quarter_turns", it.quarterTurns)
                .put("noise", it.noise?.takeIf { n -> n.isFinite() }?.let { n -> Math.round(n * 100.0) / 100.0 } ?: JSONObject.NULL)
                .put("flatten_check", it.flattenCheck?.let(::scanFlattenCheckJson) ?: JSONObject.NULL)
                .put("flatten_trim", it.trim?.let(::scanTrimJson) ?: JSONObject.NULL)
                .put(
                    "orientation",
                    it.orientation?.let { o ->
                        JSONObject().put("quarter_turns", o.quarterTurns).put("confidence", if (o.confidence.isFinite()) Math.round(o.confidence * 1000.0) / 1000.0 else 0.0)
                            .put("agreement", if (o.agreement.isFinite()) Math.round(o.agreement * 1000.0) / 1000.0 else 0.0).put("tiles", o.tiles).put("sure", o.sure)
                    } ?: JSONObject.NULL,
                )
        } ?: JSONObject.NULL,
    )
    return page.toString(1)
}

/** A flattening check (#302) for a kept scan's page file. */
internal fun scanFlattenCheckJson(c: io.github.artcraftandroid.pdfcraft.core.ScanFlattenCheck): JSONObject {
    fun px(v: Float?): Any = v?.takeIf { it.isFinite() }?.let { Math.round(it * 100.0) / 100.0 } ?: JSONObject.NULL
    return JSONObject().put("kept", c.kept).put("why", c.why)
        .put("straightened_px", px(c.straightenedPx)).put("flattened_px", px(c.flattenedPx))
        .put("straightened_lines", c.straightenedLines.toLong()).put("flattened_lines", c.flattenedLines.toLong())
}
