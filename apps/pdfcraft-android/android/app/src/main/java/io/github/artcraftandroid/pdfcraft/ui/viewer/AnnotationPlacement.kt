package io.github.artcraftandroid.pdfcraft.ui.viewer

/**
 * The engine's standard stamps (`comment_add` type `stamp`), in the order the picker lists them.
 * Their labels are the words the stamp draws on the page, which are English in every PDF viewer.
 */
internal val STANDARD_STAMPS = listOf(
    "approved", "not approved", "draft", "final", "completed", "confidential", "for comment",
    "for public release", "not for public release", "information only", "preliminary results", "void",
    "accepted", "rejected", "initial here", "sign here", "witness",
)

/** "for public release" → "For Public Release", as the stamp reads. */
internal fun stampLabel(stamp: String): String = stamp.split(' ').joinToString(" ") { w -> w.replaceFirstChar { it.uppercase() } }

/**
 * Where a callout's text box goes for a callout pointing at ([x], [y]) on a [pageWidth]×[pageHeight]
 * page: [gap] points up and to the right of the point, flipped left or below where the page ends,
 * and held inside the page. Returns [x0, y0, x1, y1] in page points (top-left origin), or `null`
 * when the box cannot fit on the page at all.
 */
internal fun calloutBox(x: Float, y: Float, width: Float, height: Float, pageWidth: Float, pageHeight: Float, gap: Float = 36f): FloatArray? {
    if (!(x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() && pageWidth.isFinite() && pageHeight.isFinite())) return null
    if (width <= 0f || height <= 0f || width > pageWidth || height > pageHeight) return null
    var x0 = x + gap
    if (x0 + width > pageWidth) x0 = x - gap - width
    var y0 = y - gap - height
    if (y0 < 0f) y0 = y + gap
    x0 = x0.coerceIn(0f, pageWidth - width)
    y0 = y0.coerceIn(0f, pageHeight - height)
    return floatArrayOf(x0, y0, x0 + width, y0 + height)
}
