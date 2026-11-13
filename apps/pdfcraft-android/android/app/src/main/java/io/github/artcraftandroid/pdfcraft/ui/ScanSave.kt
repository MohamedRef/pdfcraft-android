package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.ScanLook
import io.github.artcraftandroid.pdfcraft.ui.scan.scanTurnAsk

/**
 * One scanned page as it goes into the PDF (#287): [path] is the page image `scanProcessPage`
 * wrote, or the camera file itself with [dpi] null (a whole photo in Original, or a page whose
 * processing failed).
 */
internal data class ScanSavedPage(val path: String, val dpi: Float?)

/**
 * Whether a page needs straightening or a look: a whole photo in Original stays as it was taken
 * (natively it is copied with its resolution written in, not decoded and encoded again).
 */
internal fun scanNeedsProcessing(whole: Boolean, mode: String): Boolean = !(whole && mode == "original")

/** The native look for a [ScanFilter.mode]. */
internal fun scanLookOf(mode: String): ScanLook = when (mode) {
    "original" -> ScanLook.ORIGINAL
    "auto" -> ScanLook.AUTO
    "greyscale" -> ScanLook.GREYSCALE
    "bw" -> ScanLook.BLACK_WHITE
    "whiteboard" -> ScanLook.WHITEBOARD
    else -> ScanLook.COLOUR
}

/** Whiteboard as it was before #301 (Auto color at 85): with its testing option off. */
private fun scanOldWhiteboard(filter: ScanFilter, lab: ScanLab) = filter == ScanFilter.Whiteboard && !lab.whiteboardLook

/** The native look's word for [filter] under the testing options, as a kept page file writes it. */
internal fun scanLookWord(filter: ScanFilter, lab: ScanLab): String = if (scanOldWhiteboard(filter, lab)) "colour" else filter.mode

/** The strength a page in [look] is made at: the testing option's, the slider's, or the look's own. */
internal fun scanLookStrength(look: io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook, lab: ScanLab): Int =
    lab.strengthFor(look.strength ?: if (scanOldWhiteboard(look.filter, lab)) OLD_WHITEBOARD_LEVEL else look.filter.level)

/** Whiteboard's strength before #301, when it was Auto color's settings. */
private const val OLD_WHITEBOARD_LEVEL = 85

/** The page that goes into the PDF: the processed image when there is one, else the camera file. */
internal fun scanSavedPage(cameraPath: String, processed: Pair<String, Float>?): ScanSavedPage =
    processed?.let { (path, dpi) -> ScanSavedPage(path, dpi) } ?: ScanSavedPage(cameraPath, null)

/**
 * The `doc_create` arguments for the scanned pages: one call, the pages in order. Every processed
 * page carries its own resolution in its file, so each PDF page gets its paper's exact size (#293)
 * and no resolution is passed. Only when a camera file had to stand in for a page that failed is
 * one resolution given to the whole document (the median of the processed pages', 72–600 dpi), so
 * that page is not several times the size of the others.
 */
internal fun scanCreateArgs(pages: List<ScanSavedPage>): Map<String, Any?> {
    val dpis = pages.mapNotNull { it.dpi }.filter { it.isFinite() }.sorted()
    val args = linkedMapOf<String, Any?>("from" to "images", "paths" to pages.map { it.path }, "name" to "Scan")
    if (dpis.isNotEmpty() && dpis.size < pages.size) {
        val mid = dpis.size / 2
        val median = if (dpis.size % 2 == 1) dpis[mid] else (dpis[mid - 1] + dpis[mid]) / 2f
        args["dpi"] = median.coerceIn(72f, 600f).toDouble()
    }
    return args
}

/**
 * The native request for one page, as Save makes it and as the scan screen's preview asks for it
 * (#301): built in one place so the preview and the saved page cannot drift apart. [outputPath]
 * is where Save writes the page; a preview writes nothing and ignores it.
 */
internal fun scanPageRequest(
    photoPath: String,
    corners: FloatArray,
    filter: ScanFilter,
    flatten: Boolean,
    lab: ScanLab,
    outputPath: String,
    /** The person's own turn of the page (#339), or null while the classifier decides. */
    turns: Int? = null,
    /** Settings ▸ Preferences ▸ Scan: turn pages upright automatically (#339). */
    autoUpright: Boolean = true,
    /** The kind of document the person chose for the page (#299), or null for Clean scan to work it out. */
    preset: String? = null,
    /** How many sides of the page its edge detection found curved (#291): a book page's sign (#299). */
    curvedSides: Int = 0,
    /** The page's strength from the scan screen's slider (#301), or null for the look's own. */
    strength: Int? = null,
    /** The paper's colour the person chose for the page (#301). */
    paper: io.github.artcraftandroid.pdfcraft.ui.scan.ScanPaperChoice = io.github.artcraftandroid.pdfcraft.ui.scan.ScanPaperChoice.Auto,
): io.github.artcraftandroid.pdfcraft.core.ScanPageRequest = scanTurnAsk(turns, autoUpright, lab.upright).let { ask ->
    io.github.artcraftandroid.pdfcraft.core.ScanPageRequest(
    photoPath = photoPath,
    corners = corners.toList(),
    look = scanLookOf(scanLookWord(filter, lab)),
    strength = scanLookStrength(io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(filter, strength), lab).toUByte(),
    paper = paper.word,
    outputPath = outputPath,
    snapPaper = lab.snapPaper,
    // the scan screen's switch flattens the page as asked; the testing option flattens every page
    // and keeps the result only when it is straighter (#302)
    flatten = flatten || lab.flattenAuto,
    flattenCheck = !flatten && lab.flattenAuto,
    // a strip of table left along a flattened page's edges is trimmed (#302)
    flattenTrim = lab.flattenTrim,
    // the guard keeps a flattened page only on enough lines of text (#302)
    flattenMinLines = (if (lab.flattenLineFloor) ScanLab.FLATTEN_MIN_LINES else 0).toUByte(),
    // when the network gives nothing better, the page's lines of text may still flatten it (#303)
    lineFlatten = lab.lineFlatten,
    sideProportions = !lab.perspective,
    upright = ask.upright,
    turns = ask.turns.toUByte(),
    sharpen = scanSharpenAsk(lab).toUByte(),
    denoise = lab.denoise,
    preset = scanPresetAsk(preset, lab.presets),
    curvedSides = curvedSides.coerceIn(0, 4).toUByte(),
    // a book's gutter must run the whole side, stay narrow and fade inward (#427)
    strictGutter = lab.strictGutter,
    // barcodes, stamps and soft print are not photographs (#427)
    strictPhoto = lab.strictPhoto,
    fixedStrength = scanFixedStrength(lab, strength),
    // a document type's own tone, and photographs sharpened gently (#299)
    kindTone = lab.kindTone,
    photoSharpen = lab.photoSharpen,
)
}

/**
 * Whether the page's strength is "set by hand", so a document type does not replace it (#424):
 * the testing option's one strength, or (with [ScanLab.pageStrength] on) the page's own from the
 * slider. A page with no strength of its own still gets its kind's.
 */
internal fun scanFixedStrength(lab: ScanLab, pageStrength: Int?): Boolean =
    lab.strength != null || (lab.pageStrength && pageStrength != null)

/**
 * The sharpening asked of the native side (#424). Natively the kind's sharpening is scaled by it
 * (the default, 30, gives the kind's own); with [ScanLab.pageStrength] off and document types on,
 * any level but none asks for the default, which is what every level did before.
 */
internal fun scanSharpenAsk(lab: ScanLab): Int {
    val asked = lab.sharpen.coerceIn(0, 100)
    return if (!lab.pageStrength && lab.presets && asked > 0) SCAN_DEFAULT_SHARPEN else asked
}

/** [applied] as diagnostics fields (#424): the strength used, where it came from, and the option. */
internal fun scanAppliedStrengthDiag(applied: ScanAppliedStrength, lab: ScanLab): Array<Pair<String, Any?>> = arrayOf(
    "strength_applied" to applied.strength, "strength_applied_source" to applied.source,
    "strength_fixed" to applied.fixed, "page_strength" to lab.pageStrength, "sharpen_asked" to scanSharpenAsk(lab),
)

/** The native default sharpening, the level at which a document type's own is used (#424). */
internal const val SCAN_DEFAULT_SHARPEN = 30

/** The strength a page was really made at, and where it came from: "lab", "page", "kind" or "look" (#424). */
internal data class ScanAppliedStrength(val strength: Int, val source: String, val fixed: Boolean)

/**
 * The strength a page of [look] was made at (#424): [kindStrength] is the document type's (null
 * when the page had none), which wins only while the strength is not set by hand.
 */
internal fun scanAppliedStrength(look: io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook, lab: ScanLab, kindStrength: Int?): ScanAppliedStrength {
    val fixed = scanFixedStrength(lab, look.strength)
    val asked = scanLookStrength(look, lab)
    return when {
        lab.strength != null -> ScanAppliedStrength(asked, "lab", true)
        kindStrength != null && !fixed -> ScanAppliedStrength(kindStrength.coerceIn(0, 100), "kind", false)
        else -> ScanAppliedStrength(asked, look.strengthSource, fixed)
    }
}

/** The native request's document type (#299): "off" with the testing option off, the person's kind, or "auto". */
internal fun scanPresetAsk(preset: String?, presets: Boolean): String = when {
    !presets -> "off"
    preset != null && preset in SCAN_PRESETS -> preset
    else -> "auto"
}

/** The kinds of document the chip offers (#299), as the native words, in the order shown. */
internal val SCAN_PRESETS = listOf("document", "receipt", "whiteboard", "book_page", "photo_rich", "photo", "id_card", "coloured_form")

/** The longer side, in pixels, of the page preview the scan screen asks for (#301). */
internal const val SCAN_PREVIEW_SIDE = 1280

/**
 * What a page preview shows (#301): the photo (by path), its corners, the look, flattening and the
 * testing options. A preview whose key is not the one wanted now is out of date.
 */
internal data class ScanPreviewKey(
    val photoPath: String,
    val corners: List<Float>,
    val filter: ScanFilter,
    val flatten: Boolean,
    val lab: ScanLab,
    /** The person's turn of the page and the automatic-turn setting (#339): either changes the page shown. */
    val turns: Int? = null,
    val autoUpright: Boolean = true,
    /** The kind of document asked for the page (#299): the person's, or Document for a page whose outline was not found (#444). */
    val preset: String? = null,
    /** The page's strength from the slider (#301), null for the look's own. */
    val strength: Int? = null,
    /** The paper's colour chosen for the page (#301). */
    val paper: io.github.artcraftandroid.pdfcraft.ui.scan.ScanPaperChoice = io.github.artcraftandroid.pdfcraft.ui.scan.ScanPaperChoice.Auto,
) {
    /** The look this preview shows, as the page keeps it. */
    val look: io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook get() = io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(filter, strength, paper)

    /**
     * Whether this preview and [other] show the same page apart from its look: while the preview
     * of a new look or strength is made, the old one stays on screen instead of the photo (#301).
     */
    fun samePage(other: ScanPreviewKey?): Boolean = other != null && copy(filter = other.filter, strength = other.strength, paper = other.paper) == other

    companion object {
        /** The preview of [page] in [look] (by default the page's own). */
        fun of(page: ScanPage, flatten: Boolean, lab: ScanLab, autoUpright: Boolean = true, look: io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook = page.look) =
            ScanPreviewKey(page.file.absolutePath, page.corners.toList(), look.filter, flatten, lab.copy(keep = false, inspect = false), page.turns, autoUpright, io.github.artcraftandroid.pdfcraft.ui.scan.scanPresetFor(page, lab), look.strength, look.paper)
    }
}

/** What the scan screen shows in its main area (#301). */
internal enum class ScanView {
    /** The page as it will be saved. */
    Result,
    /** The camera's photo with the outline of the part kept. */
    Photo,
}

/**
 * Which view the scan screen shows: the photo whenever previews are off, the person chose it, or
 * there is no preview of what is asked for yet (with [waiting] true while one is being made).
 */
internal data class ScanShown(val view: ScanView, val waiting: Boolean)

internal fun scanShown(previews: Boolean, chosen: ScanView, haveWanted: Boolean, failed: Boolean, haveSamePage: Boolean = false): ScanShown = when {
    !previews || chosen == ScanView.Photo -> ScanShown(ScanView.Photo, false)
    haveWanted -> ScanShown(ScanView.Result, false)
    // the same page in its previous look stays up while the new look is made (#301), not the photo
    haveSamePage && !failed -> ScanShown(ScanView.Result, true)
    else -> ScanShown(ScanView.Photo, !failed)
}

/**
 * A flattening check (#302) as diagnostics fields: whether one was made, whether the flattened
 * page was kept and why, and both pages' line measures in pixels (-1 when not measured).
 */
internal fun scanFlattenCheckDiag(c: io.github.artcraftandroid.pdfcraft.core.ScanFlattenCheck?): Array<Pair<String, Any?>> = arrayOf(
    "flatten_checked" to (c != null), "flatten_kept" to (c?.kept ?: false), "flatten_why" to (c?.why ?: "none"),
    "straight_px" to (c?.straightenedPx?.let { Math.round(it * 10.0) / 10.0 } ?: -1.0),
    "straight_flat_px" to (c?.flattenedPx?.let { Math.round(it * 10.0) / 10.0 } ?: -1.0),
    "straight_lines" to (c?.straightenedLines?.toLong() ?: 0L), "straight_flat_lines" to (c?.flattenedLines?.toLong() ?: 0L),
)

/**
 * How long a preview of the same page in another look or strength waits before it starts (#301),
 * so a slider moved quickly makes the value it stops on rather than every value on the way.
 */
internal const val SCAN_PREVIEW_SETTLE_MS = 30L

/** The longer side, in dp, of a tile in the scan screen's looks strip (#301). */
internal const val SCAN_TILE_SIDE_DP = 96

/** The longer side, in pixels, of the sharper page made once the preview is zoomed in (#301). */
internal const val SCAN_DETAIL_SIDE = 2560

/** How far the preview zooms: a double-tap goes to [SCAN_ZOOM_TAP], a pinch up to [SCAN_ZOOM_MAX]. */
internal const val SCAN_ZOOM_TAP = 2.5f
internal const val SCAN_ZOOM_MAX = 5f

/** From this zoom on, the sharper page is asked for. */
internal const val SCAN_ZOOM_DETAIL = 1.5f
