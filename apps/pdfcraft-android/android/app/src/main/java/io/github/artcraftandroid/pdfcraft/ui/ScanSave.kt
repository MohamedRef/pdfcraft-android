package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.ScanLook

/**
 * One scanned page as it goes into the PDF (#287): [path] is the page image `scanProcessPage`
 * wrote, or the camera file itself with [dpi] null (a whole photo in Original, or a page whose
 * processing failed).
 */
internal data class ScanSavedPage(val path: String, val dpi: Float?)

/** Whether a page needs straightening or a look: a whole photo in Original stays as it was taken. */
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
 * The `doc_create` arguments for the scanned pages: one call, the pages in order, and one
 * resolution for the document (the median of the processed pages', 72–600 dpi). With no processed
 * page each image keeps its own resolution, as before.
 */
internal fun scanCreateArgs(pages: List<ScanSavedPage>): Map<String, Any?> {
    val dpis = pages.mapNotNull { it.dpi }.filter { it.isFinite() }.sorted()
    val args = linkedMapOf<String, Any?>("from" to "images", "paths" to pages.map { it.path }, "name" to "Scan")
    if (dpis.isNotEmpty()) {
        val mid = dpis.size / 2
        val median = if (dpis.size % 2 == 1) dpis[mid] else (dpis[mid - 1] + dpis[mid]) / 2f
        args["dpi"] = median.coerceIn(72f, 600f).toDouble()
    }
    return args
}
