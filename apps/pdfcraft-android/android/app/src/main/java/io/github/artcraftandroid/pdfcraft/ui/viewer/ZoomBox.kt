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

    /**
     * The part of the page to render for a sharp pad background (#130): the box's pixels at the
     * pad's own magnification, clipped to the page, or null when nothing of the page is in the box.
     * [scale] is pixels per point (capped at [maxScale]); [x], [y], [w], [h] are in page pixels at it.
     */
    fun padRender(pad: Size, pageWidth: Float, pageHeight: Float, maxScale: Float = MAX_PAD_SCALE): PadRender? {
        if (pad.width <= 0f || pad.height <= 0f || width <= 0f) return null
        val scale = magnification(pad).coerceAtMost(maxScale)
        val pw = kotlin.math.ceil(pageWidth * scale).toInt()
        val ph = kotlin.math.ceil(pageHeight * scale).toInt()
        val x0 = kotlin.math.floor(x * scale).toInt().coerceIn(0, pw)
        val y0 = kotlin.math.floor(y * scale).toInt().coerceIn(0, ph)
        val x1 = kotlin.math.ceil((x + pad.width / magnification(pad)) * scale).toInt().coerceIn(0, pw)
        val y1 = kotlin.math.ceil((y + pad.height / magnification(pad)) * scale).toInt().coerceIn(0, ph)
        if (x1 <= x0 || y1 <= y0) return null
        return PadRender(page, scale, x0, y0, x1 - x0, y1 - y0)
    }

    companion object {
        /** No pad background renders finer than this many pixels per point. */
        const val MAX_PAD_SCALE = 24f

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

/** A region of [page] rendered at [scale] pixels per point, at ([x], [y]) in the page's pixels. */
internal data class PadRender(val page: Int, val scale: Float, val x: Int, val y: Int, val w: Int, val h: Int)
