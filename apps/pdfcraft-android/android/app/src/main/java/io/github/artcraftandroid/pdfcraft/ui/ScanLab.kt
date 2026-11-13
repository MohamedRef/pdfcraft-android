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
    /** Whether the Clean scan filter draws a grainy page's paper white from a lower level (a photo taken in dim light, #466). */
    val grainWhite: Boolean = true,
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
     * The flattening guard keeps a flattened page (the network's in the automatic mode, or one
     * flattened from its lines of text) only when at least 8 lines of text were followed on both
     * pages (#302). Off: the guard as before, which could decide on 3 or 4 lines.
     */
    val flattenLineFloor: Boolean = true,
    /**
     * Clean scan takes each page for a kind of document (receipt, whiteboard, book page, …) and
     * uses that kind's strength, sharpening and paper colour (#299). Off uses the filter's own
     * strength and the sharpening above, as before.
     */
    val presets: Boolean = true,
    /**
     * A document type also changes Clean scan's tone curve (#299): a receipt's faint print is
     * darkened (extra gamma), a whiteboard gets its S-curve and stronger marker colour. Off: a
     * type sets only strength, sharpening and paper colour.
     */
    val kindTone: Boolean = true,
    /** Photographs found on the page are sharpened as gently as the look's own objects (#299). Off: only those objects. */
    val photoSharpen: Boolean = true,
    /** Show what a document type suggests, Flatten page or Grayscale, as a chip to tap (#299). */
    val suggestions: Boolean = true,
    /**
     * A dark band down one long side makes Clean scan call a page a book page only when the band
     * runs the whole length of the side, stays narrow and fades toward the middle, as a gutter's
     * shadow does (#427). Off is the first rule, where a patch of shade or a strip of dark
     * surround along one side was enough.
     */
    val strictGutter: Boolean = true,
    /**
     * Clean scan counts a part of the page as a photograph only where little paper shows or the
     * part is smooth, so barcodes, ink stamps and soft print are not photographs (#427). Off is
     * the first rule, which read a label's barcode as a photograph and so the label as a
     * document with photographs.
     */
    val strictPhoto: Boolean = true,
    /**
     * A page is a Book page only with more than one curved side: the gutter's band, the two
     * opposite sides both curved, or a curved side in book mode (#442). One curved side (a sheet
     * held in the hand) only suggests flattening. Off is the first rule: any curved side.
     */
    val strictBook: Boolean = true,
    /**
     * A strength set on the page with the scan screen's slider counts as set by hand: the document
     * type's strength no longer replaces it, and the sharpening above scales the type's own
     * sharpening (#424). Off: the type's strength and sharpening replace them, as before.
     */
    val pageStrength: Boolean = true,
    /**
     * A page whose outline was not found, and which was not cropped, is the whole photo with the
     * table around the paper in it: Clean scan does not work its kind out from that and makes it
     * a Document at the look's own strength until it is cropped (#444). Off: its kind is worked
     * out from the whole photo, as before.
     */
    val unfoundDocument: Boolean = true,
    /**
     * The scan screen's Back (the arrow and Android's Back) asks before it throws away a scan
     * with pages, and Delete asks before it removes a page (#456). Off: both act at once, as before.
     */
    val askDiscard: Boolean = true,
    /** In book mode, cut a spread along the gutter found in the photo (#303); off cuts every spread at its centre line. */
    val bookGutter: Boolean = true,
    /**
     * When a page is flattened and the network is not installed or its page was not straighter,
     * flatten it from its lines of text instead, and keep that only when it is straighter (#303,
     * part B). Off: such a page stays straightened by its corners, as before.
     */
    val lineFlatten: Boolean = true,
    /**
     * Auto detect on the crop screen says when it finds nothing, and does not make a search that
     * already found nothing on the photo again: the next press tries the line detector (#443).
     * Off: every press makes the same search and nothing is said when it finds nothing, as before.
     */
    val cropDetectNote: Boolean = true,
    /** Each page keeps its own look and strength (#301); off, choosing a look changes every page, as before. */
    val lookPerPage: Boolean = true,
    /** The looks strip shows each look as a small picture of the page (#301); off, the looks are words, as before. */
    val lookStrip: Boolean = true,
    /** Whiteboard is Auto's light steps with an S-curve and stronger colour (#301); off, it is Auto color at 85, as before. */
    val whiteboardLook: Boolean = true,
    /** A preview no longer wanted is stopped at its next stage (#301); off, it runs to the end and is dropped, as before. */
    val previewStop: Boolean = true,
    /**
     * The camera's mode row (One page, Many pages, Book), its bar of icons (light, auto-capture,
     * grid, more), zoom, and the same places for Import, the shutter and the last page (#457).
     * Off: the camera's bars as before, where every photo is added until Done.
     */
    val cameraBar: Boolean = true,
    /**
     * The scanner's screens one job each (#455): Adjust borders straight after a photo in One page
     * mode and after an import of one photo (#458). Off: today's single scan screen, where the
     * borders are reached only from Crop.
     */
    val steps: Boolean = true,
    /** Double-tap or pinch the preview to look closer, with a sharper page once zoomed in (#301); off, the preview doesn't zoom. */
    val previewZoom: Boolean = true,
    /**
     * Clean scan and Whiteboard keep the page's light estimate with its straightened preview page,
     * so a strength or paper change only redraws the tone (#446). The page looks the same either
     * way; off, the light is estimated again for every change, as before.
     */
    val lookReuse: Boolean = true,
    /**
     * With keeping scans on, run the research detectors on each kept page and report what they see
     * (#305 moiré, #306 fingers, glare and stains) in the log and the page file. Detection only:
     * no page changes, so this does not count as a changed option.
     */
    val inspect: Boolean = false,
) {
    /** Nothing differs from the scanner as it is meant to be used (keeping scans and reporting what the detectors see change no page). */
    val asDesigned: Boolean get() = copy(keep = false, inspect = false) == ScanLab()

    fun findOptions() = ScanFindOptions(finder, refine)

    /** The strength a look with its own [level] is applied at. */
    fun strengthFor(level: Int): Int = (strength ?: level).coerceIn(0, 100)

    /** The options as diagnostics fields: short lowercase words, booleans and numbers only. */
    fun diag(): Array<Pair<String, Any?>> = arrayOf<Pair<String, Any?>>(
        "finder" to finderId(finder), "refine" to refine, "side_proportions" to !perspective,
        "snap_paper" to snapPaper, "strength_override" to (strength ?: -1), "upright" to upright, "sharpen" to sharpen,
        "denoise" to denoise, "grain_white" to grainWhite, "preview" to preview, "crop_tools" to cropTools, "crop_detect_note" to cropDetectNote, "flatten_auto" to flattenAuto, "flatten_trim" to flattenTrim, "flatten_line_floor" to flattenLineFloor, "settle_turns" to settleTurns, "presets" to presets, "kind_tone" to kindTone, "photo_sharpen" to photoSharpen, "suggestions" to suggestions, "strict_gutter" to strictGutter, "strict_photo" to strictPhoto, "strict_book" to strictBook, "book_gutter" to bookGutter, "line_flatten" to lineFlatten,
        "page_strength" to pageStrength, "unfound_document" to unfoundDocument, "ask_discard" to askDiscard, "look_per_page" to lookPerPage, "look_strip" to lookStrip,
        "whiteboard_look" to whiteboardLook, "preview_stop" to previewStop, "preview_zoom" to previewZoom, "look_reuse" to lookReuse, "camera_bar" to cameraBar, "steps" to steps, "keep" to keep, "inspect" to inspect,
    )

    /** The options as JSON, for a kept scan's page file. */
    fun json(): JSONObject = JSONObject()
        .put("finder", finderId(finder)).put("refine", refine).put("side_proportions", !perspective)
        .put("snap_paper", snapPaper).put("strength_override", strength ?: JSONObject.NULL).put("upright", upright)
        .put("sharpen", sharpen).put("denoise", denoise).put("grain_white", grainWhite).put("preview", preview).put("crop_tools", cropTools).put("crop_detect_note", cropDetectNote).put("flatten_auto", flattenAuto).put("flatten_trim", flattenTrim).put("flatten_line_floor", flattenLineFloor).put("settle_turns", settleTurns).put("presets", presets).put("kind_tone", kindTone).put("photo_sharpen", photoSharpen).put("suggestions", suggestions).put("strict_gutter", strictGutter).put("strict_photo", strictPhoto).put("strict_book", strictBook).put("book_gutter", bookGutter).put("line_flatten", lineFlatten)
        .put("page_strength", pageStrength).put("unfound_document", unfoundDocument).put("ask_discard", askDiscard).put("look_per_page", lookPerPage).put("look_strip", lookStrip)
        .put("whiteboard_look", whiteboardLook).put("preview_stop", previewStop).put("preview_zoom", previewZoom).put("look_reuse", lookReuse).put("camera_bar", cameraBar).put("steps", steps).put("inspect", inspect)

    /** One line of text for the app's preferences. */
    fun encode(): String = listOf(
        "keep" to flag(keep), "finder" to finderId(finder), "refine" to flag(refine),
        "perspective" to flag(perspective), "snap" to flag(snapPaper), "strength" to (strength?.toString() ?: ""),
        "upright" to flag(upright), "sharpen" to sharpen.toString(), "denoise" to flag(denoise), "grain_white" to flag(grainWhite), "preview" to flag(preview), "crop" to flag(cropTools), "detect_note" to flag(cropDetectNote), "flatten_auto" to flag(flattenAuto), "flatten_trim" to flag(flattenTrim), "line_floor" to flag(flattenLineFloor),
        "settle" to flag(settleTurns), "presets" to flag(presets), "kind_tone" to flag(kindTone), "photo_sharpen" to flag(photoSharpen), "suggest" to flag(suggestions), "strict_gutter" to flag(strictGutter), "strict_photo" to flag(strictPhoto), "strict_book" to flag(strictBook), "gutter" to flag(bookGutter), "line_flatten" to flag(lineFlatten),
        "page_strength" to flag(pageStrength), "unfound_doc" to flag(unfoundDocument), "ask_discard" to flag(askDiscard), "look_page" to flag(lookPerPage), "look_strip" to flag(lookStrip),
        "board" to flag(whiteboardLook), "stop" to flag(previewStop), "zoom" to flag(previewZoom), "look_reuse" to flag(lookReuse), "camera_bar" to flag(cameraBar), "steps" to flag(steps), "inspect" to flag(inspect),
    ).joinToString(";") { (k, v) -> "$k=$v" }

    companion object {
        /** The fewest lines of text followed on both pages before the flattening guard may keep a flattened page (#302). */
        const val FLATTEN_MIN_LINES = 8

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
                grainWhite = on("grain_white", default.grainWhite),
                preview = on("preview", default.preview),
                cropTools = on("crop", default.cropTools),
                cropDetectNote = on("detect_note", default.cropDetectNote),
                settleTurns = on("settle", default.settleTurns),
                flattenAuto = on("flatten_auto", default.flattenAuto),
                flattenTrim = on("flatten_trim", default.flattenTrim),
                flattenLineFloor = on("line_floor", default.flattenLineFloor),
                presets = on("presets", default.presets),
                kindTone = on("kind_tone", default.kindTone),
                photoSharpen = on("photo_sharpen", default.photoSharpen),
                suggestions = on("suggest", default.suggestions),
                strictGutter = on("strict_gutter", default.strictGutter),
                strictPhoto = on("strict_photo", default.strictPhoto),
                strictBook = on("strict_book", default.strictBook),
                bookGutter = on("gutter", default.bookGutter),
                lineFlatten = on("line_flatten", default.lineFlatten),
                pageStrength = on("page_strength", default.pageStrength),
                unfoundDocument = on("unfound_doc", default.unfoundDocument),
                askDiscard = on("ask_discard", default.askDiscard),
                lookPerPage = on("look_page", default.lookPerPage),
                lookStrip = on("look_strip", default.lookStrip),
                whiteboardLook = on("board", default.whiteboardLook),
                previewStop = on("stop", default.previewStop),
                previewZoom = on("zoom", default.previewZoom),
                lookReuse = on("look_reuse", default.lookReuse),
                cameraBar = on("camera_bar", default.cameraBar),
                steps = on("steps", default.steps),
                inspect = on("inspect", default.inspect),
            )
        }
    }
}

/** How a scan was thrown away (#456): whether the person was asked first, and how many pages it had. */
internal data class ScanDiscard(val asked: Boolean, val pages: Int)

/** What the search for a page's edges came back with (#290, #291): kept with a scan kept for testing. */
data class ScanFinding(
    val source: String,
    val confidence: Double,
    val angle: Int,
    val passes: Int,
    val refinedSides: Int,
    val curvedSides: Int,
    /** Two opposite sides (top and bottom, or left and right) were both found curved (#442). */
    val curvedOpposite: Boolean = false,
    /** The search that was made, so the crop screen does not make it again when it found nothing (#443). */
    val asked: ScanFindOptions? = null,
)

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
    /** What flattening from the lines of text did, when it ran (#303, part B). */
    val lineFlatten: io.github.artcraftandroid.pdfcraft.core.ScanLineFlatten? = null,
    /** How much the page's paper varies after its light is divided out, as the Clean scan filter measured it (#466); null for the other filters. */
    val paperSpread: Double? = null,
    /** The level from which the page's paper was drawn white (#466): the strength's own, or lower on a grainy page; null for the other filters. */
    val whitePoint: Double? = null,
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
    /** Share of near-white pixels before the light is evened: not glare, white paper reaches it (#427). */
    val brightShare: Double,
    /** The gutter rule in force found a gutter's dark band. */
    val gutterBand: Boolean,
    val wholePhoto: Boolean,
    /** What the first gutter rule (#299) says, whichever rule is in force (#427). */
    val gutterLoose: Boolean = false,
    /** Share of the darker long side's length along which its strip is dark, 0–1 (#427). */
    val gutterCover: Double = 0.0,
    /** What the first photo rule (#299) reads as photograph, whichever rule is in force (#427); -1 when not known. */
    val photoLoose: Double = -1.0,
    /** What the first book page rule (#299: any curved side) says, whichever rule is in force (#442). */
    val bookLoose: Boolean = false,
    /** Two opposite sides were found curved (#442). */
    val curvedOpposite: Boolean = false,
    /** The page came from book mode (#442). */
    val bookMode: Boolean = false,
    /** The kind's change to the tone curve (#299): added gamma, the S-curve, the colour factor. */
    val extraGamma: Double = 0.0,
    val sCurve: Boolean = false,
    val colourFactor: Double = 1.0,
)

/** The orientation classifier's answer for a page (#339): the turn it would give, and how sure it was. */
internal data class ScanTestOrientation(val quarterTurns: Int, val confidence: Double, val agreement: Double, val tiles: Int, val sure: Boolean)

/**
 * The last preview the scan screen showed of a page before it was saved (#301): whether one was
 * shown at all, whether its straightened page was reused, how long it took and its size. A page
 * saved without one (the option off, or saved before the preview came) has [shown] false.
 * [lightReused] says Clean scan or Whiteboard found the page's light estimate kept from an earlier
 * look, and [lookParts] holds the look's parts that ran, in milliseconds (#446).
 */
internal data class ScanTestPreview(
    val shown: Boolean,
    val reused: Boolean,
    val ms: Long,
    val width: Int,
    val height: Int,
    val look: String?,
    val lightReused: Boolean = false,
    val lookParts: Map<String, Long> = emptyMap(),
)

/** The name, without an extension, of page [index] (from 0) of a kept scan. */
internal fun scanTestName(index: Int): String = String.format(Locale.US, "page-%02d", index + 1)

private fun shares(values: FloatArray): JSONArray = JSONArray().also { out ->
    // five decimals of a share is a twentieth of a pixel on a 50-megapixel photo
    for (v in values) out.put(if (v.isFinite()) Math.round(v * 100000.0) / 100000.0 else 0.0)
}

/**
 * A kept page's facts as JSON. The first five keys are the scan test set's own (#288): `corners`
 * are the page's corners as the person left them, as 8 shares of the upright photo, which become
 * ground truth once someone has checked them and set `checked` to true (#425; `scan-eval` leaves
 * the page out until then). The rest says how the app got there: what it found
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
    /** The page's own look and strength as the scan screen left them (#301); null for a page discarded or from before it. */
    pageLook: JSONObject? = null,
    /** What the research detectors saw on the page (#305, #306); null when they were not asked. */
    inspection: JSONObject? = null,
    /** The strength the page was really made at and where it came from (#424); null when not known. */
    applied: ScanAppliedStrength? = null,
    /** Where the page's kind of document came from (#444): "off", "chosen", "no_outline" or "measured"; null when not known. */
    kindSource: String? = null,
    /** How a discarded scan was given up (#456): `asked` and `pages`; null for a saved page. */
    discard: ScanDiscard? = null,
): String {
    val page = JSONObject()
        .put("corners", shares(corners)).put("kind", "other").put("paper", "unknown").put("curved", false).put("notes", "")
        // #425: the corners are ground truth only once someone has looked at them and set this to true
        .put("checked", false)
        .put("app", app).put("outcome", if (saved) "saved" else "discarded")
        .put("found", found?.let(::shares) ?: JSONObject.NULL)
        .put(
            "finding",
            finding?.let {
                JSONObject().put("source", it.source).put("confidence", Math.round(it.confidence * 1000.0) / 1000.0).put("angle", it.angle)
                    .put("passes", it.passes).put("refined_sides", it.refinedSides).put("curved_sides", it.curvedSides)
                    .put("curved_opposite", it.curvedOpposite)
            } ?: JSONObject.NULL,
        )
        .put("look", look ?: JSONObject.NULL).put("strength", strength ?: JSONObject.NULL).put("flatten", flatten)
        // the person's own Flatten page choice for this page (#302): true, false or null for none
        .put("flatten_chosen", flattenChosen ?: JSONObject.NULL)
        .put("options", lab.json())
        // #444: "no_outline" means the kind was not worked out because the page is the whole photo
        .put("document_type_source", kindSource ?: JSONObject.NULL)
        .put(
            "document_type",
            kind?.let {
                fun r(v: Double, places: Double = 1000.0) = if (v.isFinite()) Math.round(v * places) / places else 0.0
                JSONObject().put("preset", it.preset).put("chosen", it.chosen).put("strength", it.strength).put("sharpen", it.sharpen)
                    .put("keep_paper", it.keepPaper).put("suggest_flatten", it.suggestFlatten).put("suggest_greyscale", it.suggestGreyscale)
                    .put("ratio", r(it.ratio)).put("ink_share", r(it.inkShare)).put("colour_ink_share", r(it.colourInkShare))
                    .put("photo_share", r(it.photoShare)).put("stroke_mm", r(it.strokeMm)).put("bright_share", r(it.brightShare))
                    .put("gutter_band", it.gutterBand).put("gutter_loose", it.gutterLoose).put("gutter_cover", r(it.gutterCover)).put("photo_loose", r(it.photoLoose)).put("whole_photo", it.wholePhoto)
                    .put("book_loose", it.bookLoose).put("curved_opposite", it.curvedOpposite).put("book_mode", it.bookMode)
                    .put("extra_gamma", r(it.extraGamma)).put("s_curve", it.sCurve).put("colour_factor", r(it.colourFactor))
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
                    // the look's light estimate kept from an earlier look, and the parts that ran (#446)
                    .put("light_reused", it.lightReused)
                    .put("look_parts_ms", JSONObject().also { parts -> it.lookParts.forEach { (stage, ms) -> parts.put(stage, ms) } })
            } ?: JSONObject.NULL,
        )
        .put("crop", crop?.json() ?: JSONObject.NULL)
        .put("capture", capture ?: JSONObject.NULL)
        .put("models", models ?: JSONObject.NULL)
        .put("book", book?.json() ?: JSONObject.NULL)
        .put("page_look", pageLook ?: JSONObject.NULL)
        .put("inspect", inspection ?: JSONObject.NULL)
        // #456: whether the person was asked before the scan was thrown away, and how many pages it had
        .put("discard", discard?.let { JSONObject().put("asked", it.asked).put("pages", it.pages) } ?: JSONObject.NULL)
        .put("strength_applied", applied?.let { JSONObject().put("strength", it.strength).put("source", it.source).put("fixed", it.fixed) } ?: JSONObject.NULL)
    page.put(
        "result",
        result?.let {
            JSONObject().put("file", output ?: JSONObject.NULL).put("width", it.width).put("height", it.height).put("format", it.format)
                .put("dpi", if (it.dpi.isFinite()) it.dpi else 0.0).put("paper", it.paper).put("aspect", it.aspect)
                .put("focal_mismatch", it.focalMismatch).put("flattened", it.flattened).put("bend_pct", if (it.bendPct.isFinite()) Math.round(it.bendPct * 1000.0) / 1000.0 else 0.0)
                .put("quarter_turns", it.quarterTurns)
                .put("noise", it.noise?.takeIf { n -> n.isFinite() }?.let { n -> Math.round(n * 100.0) / 100.0 } ?: JSONObject.NULL)
                .put("paper_spread", it.paperSpread?.takeIf { n -> n.isFinite() }?.let { n -> Math.round(n * 10000.0) / 10000.0 } ?: JSONObject.NULL)
                .put("white_point", it.whitePoint?.takeIf { n -> n.isFinite() }?.let { n -> Math.round(n * 1000.0) / 1000.0 } ?: JSONObject.NULL)
                .put("flatten_check", it.flattenCheck?.let(::scanFlattenCheckJson) ?: JSONObject.NULL)
                .put("flatten_trim", it.trim?.let(::scanTrimJson) ?: JSONObject.NULL)
                .put("line_flatten", it.lineFlatten?.let(::scanLineFlattenJson) ?: JSONObject.NULL)
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
