package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPage

/**
 * Where a page's kind of document comes from (#444), as the short word the log and a kept scan's
 * page file show:
 * - "off": document types are off (a testing option);
 * - "chosen": the person picked the kind on the chip;
 * - "no_outline": the page's outline was not found and the page was not cropped, so its corners
 *   are the whole photo (or a half of it in book mode) with the table and surroundings in it.
 *   Its kind is not worked out: it is a Document, at the look's own strength, until it is cropped;
 * - "measured": worked out from the page as usual.
 */
internal fun scanKindSource(page: ScanPage, lab: ScanLab): String = scanKindSource(page.preset, page.outlineMissing, lab)

internal fun scanKindSource(chosen: String?, outlineMissing: Boolean, lab: ScanLab): String = when {
    !lab.presets -> "off"
    chosen != null -> "chosen"
    outlineMissing && lab.unfoundDocument -> "no_outline"
    else -> "measured"
}

/**
 * The kind asked of the native side for [page] (#444): the person's choice, Document for a page
 * whose outline was not found and which was not cropped, or null for Clean scan to work it out.
 */
internal fun scanPresetFor(page: ScanPage, lab: ScanLab): String? = scanPresetFor(page.preset, page.outlineMissing, lab)

internal fun scanPresetFor(chosen: String?, outlineMissing: Boolean, lab: ScanLab): String? =
    if (scanKindSource(chosen, outlineMissing, lab) == "no_outline") SCAN_UNMEASURED_PRESET else chosen

/** The kind a page whose outline was not found is given until it is cropped (#444). */
internal const val SCAN_UNMEASURED_PRESET = "document"

/**
 * The corners a page starts with when its outline was not found (#444): [corners] (the whole
 * photo, or a book half of it), or null when an outline was found or the photo is already the
 * page (a low-light merge comes out straightened, #297).
 */
internal fun scanUncropped(corners: FloatArray, found: FloatArray?, straightened: Boolean): FloatArray? =
    corners.takeIf { found == null && !straightened }

/**
 * A restored page's [ScanPage.uncropped] (#444). The session file does not keep it, so a page of
 * its own whose outline was not found and whose corners are still the whole photo counts as not
 * cropped. A book half restored this way is measured as usual.
 */
internal fun scanRestoredUncropped(corners: FloatArray, found: FloatArray?, book: ScanBookHalf?, straightened: Boolean): FloatArray? =
    ScanPage.WHOLE.takeIf { found == null && book == null && !straightened && corners.contentEquals(ScanPage.WHOLE) }

/** The page's outline was not found and it still has the corners it started with (#444). */
internal val ScanPage.outlineMissing: Boolean get() = scanOutlineMissing(uncropped, corners)

internal fun scanOutlineMissing(uncropped: FloatArray?, corners: FloatArray): Boolean = uncropped?.contentEquals(corners) == true
