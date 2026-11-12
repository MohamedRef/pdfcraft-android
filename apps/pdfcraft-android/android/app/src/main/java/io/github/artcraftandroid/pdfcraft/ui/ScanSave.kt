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
    else -> ScanLook.COLOUR
}

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
): io.github.artcraftandroid.pdfcraft.core.ScanPageRequest = scanTurnAsk(turns, autoUpright, lab.upright).let { ask ->
    io.github.artcraftandroid.pdfcraft.core.ScanPageRequest(
    photoPath = photoPath,
    corners = corners.toList(),
    look = scanLookOf(filter.mode),
    strength = lab.strengthFor(filter.level).toUByte(),
    outputPath = outputPath,
    snapPaper = lab.snapPaper,
    flatten = flatten,
    sideProportions = !lab.perspective,
    upright = ask.upright,
    turns = ask.turns.toUByte(),
    sharpen = lab.sharpen.coerceIn(0, 100).toUByte(),
    denoise = lab.denoise,
    preset = scanPresetAsk(preset, lab.presets),
    curvedSides = curvedSides.coerceIn(0, 4).toUByte(),
    fixedStrength = lab.strength != null,
)
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
    /** The kind of document the person chose for the page (#299). */
    val preset: String? = null,
) {
    companion object {
        fun of(page: ScanPage, filter: ScanFilter, flatten: Boolean, lab: ScanLab, autoUpright: Boolean = true) =
            ScanPreviewKey(page.file.absolutePath, page.corners.toList(), filter, flatten, lab.copy(keep = false), page.turns, autoUpright, page.preset)
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

internal fun scanShown(previews: Boolean, chosen: ScanView, haveWanted: Boolean, failed: Boolean): ScanShown = when {
    !previews || chosen == ScanView.Photo -> ScanShown(ScanView.Photo, false)
    haveWanted -> ScanShown(ScanView.Result, false)
    else -> ScanShown(ScanView.Photo, !failed)
}
