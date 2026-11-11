package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size

/**
 * The zoom writing box (#130): a rectangle on [page] (page points, top-left origin) shown
 * magnified in a docked pad. [lineStart] is where a new line begins on this page.
 */
internal data class ZoomBox(val page: Int, val x: Float, val y: Float, val width: Float, val height: Float, val lineStart: Float) {
    /** Pad pixels per page point for a pad of [pad] size. */
    fun magnification(pad: Size): Float = if (width > 0f) pad.width / width else 1f

    /** A pad position as a page point. */
    fun toPage(padPoint: Offset, pad: Size): Offset {
        val m = magnification(pad)
        return Offset(x + padPoint.x / m, y + padPoint.y / m)
    }

    /** Where the page's top-left lands in the pad. */
    fun pageOriginInPad(pad: Size): Offset {
        val m = magnification(pad)
        return Offset(-x * m, -y * m)
    }

    /** Whether a lift at page x [px] is in the right-hand advance zone (the last 20 % of the box). */
    fun inAdvanceZone(px: Float): Boolean = px >= x + width * 0.8f

    /** Moved along the line by 60 % of its width; past the page's right margin, to the next line. */
    fun advanced(pageWidth: Float, pageHeight: Float, pageCount: Int): ZoomBox {
        val nx = x + width * 0.6f
        return if (nx + width <= pageWidth - margin(pageWidth) + 0.5f) copy(x = nx) else nextLine(pageWidth, pageHeight, pageCount)
    }

    /** The start of the next line (down by 90 % of the box height); past the bottom, the next page's top. */
    fun nextLine(pageWidth: Float, pageHeight: Float, pageCount: Int): ZoomBox {
        val ny = y + height * 0.9f
        if (ny + height <= pageHeight) return copy(x = lineStart.coerceIn(0f, (pageWidth - width).coerceAtLeast(0f)), y = ny)
        if (page + 1 >= pageCount) return this
        return copy(page = page + 1, x = lineStart, y = margin(pageHeight))
    }

    /** Moved by [dx], [dy] page points, kept on its page. */
    fun movedBy(dx: Float, dy: Float, pageWidth: Float, pageHeight: Float): ZoomBox = copy(
        x = (x + dx).coerceIn(0f, (pageWidth - width).coerceAtLeast(0f)),
        y = (y + dy).coerceIn(0f, (pageHeight - height).coerceAtLeast(0f)),
    )

    companion object {
        private fun margin(extent: Float) = extent * 0.06f

        /** A box a third of the page wide, shaped like the pad, starting at the left margin at [atY]. */
        fun start(page: Int, pageWidth: Float, pageHeight: Float, atY: Float, pad: Size): ZoomBox {
            val w = pageWidth / 3f
            val h = if (pad.width > 0f) w * pad.height / pad.width else w / 3f
            val left = margin(pageWidth)
            return ZoomBox(page, left, atY.coerceIn(0f, (pageHeight - h).coerceAtLeast(0f)), w, h, left)
        }
    }
}
