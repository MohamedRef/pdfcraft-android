package io.github.artcraftandroid.pdfcraft.ui

import kotlin.math.hypot

/**
 * What the Scan gates (#286) measure about one page, from its corners alone (no pixels):
 * whether edges were found, whether the user moved them, how far (as a share of the photo's
 * diagonal: the "median corner error" of milestone 1). The page's proportions are logged from
 * the saved page itself.
 */
internal data class ScanPageStats(
    val found: Boolean,
    val touched: Boolean,
    /** Largest corner move from the found edges, in % of the photo's diagonal; 0 when untouched or none found. */
    val maxMovePct: Double,
)

/**
 * [corners] and [found] are x,y pairs (top-left, top-right, bottom-right, bottom-left) as shares
 * of the photo's [w] × [h]; [found] is null when edge detection found nothing.
 */
internal fun scanPageStats(corners: FloatArray, found: FloatArray?, whole: FloatArray, w: Double, h: Double): ScanPageStats {
    val reference = found ?: whole
    val touched = !corners.contentEquals(reference)
    val diagonal = hypot(w, h).takeIf { it > 0 } ?: 1.0
    var move = 0.0
    if (found != null && touched) {
        for (k in 0 until 4) {
            val d = hypot((corners[2 * k] - found[2 * k]) * w, (corners[2 * k + 1] - found[2 * k + 1]) * h)
            if (d > move) move = d
        }
    }
    return ScanPageStats(found != null, touched, move / diagonal * 100)
}
