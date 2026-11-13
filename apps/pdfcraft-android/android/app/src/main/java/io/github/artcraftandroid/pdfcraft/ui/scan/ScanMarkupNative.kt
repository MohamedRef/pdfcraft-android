package io.github.artcraftandroid.pdfcraft.ui.scan

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import io.github.artcraftandroid.pdfcraft.core.ScanMarkup
import io.github.artcraftandroid.pdfcraft.core.ScanStroke
import io.github.artcraftandroid.pdfcraft.core.ScanTextMark

/*
 * A page's marks (#495) as the engine takes them. The engine has no fonts, so each text box is
 * drawn here into a mask of coverage, at about the size it has on a page 3 000 pixels long; the
 * engine scales the mask into the box on the page it makes (the preview and the saved page alike).
 */

/** The longer side, in pixels, of the page a text mask is drawn for. */
internal const val SCAN_TEXT_REFERENCE_SIDE = 3000f

/** A text box drawn: its coverage, one byte a pixel, and the box's size as shares of the page's longer side. */
internal class ScanTextMask(val width: Int, val height: Int, val bytes: ByteArray, val boxWidth: Float, val boxHeight: Float)

private val masks = object : LinkedHashMap<ScanTextBox, ScanTextMask>(16, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ScanTextBox, ScanTextMask>?): Boolean = size > 64
}

/** [box] drawn into a mask; null when there is nothing to draw. Kept for the next request of the same box. */
internal fun scanTextMask(box: ScanTextBox): ScanTextMask? {
    synchronized(masks) { masks[box]?.let { return it } }
    val made = drawTextMask(box) ?: return null
    synchronized(masks) { masks[box] = made }
    return made
}

private fun drawTextMask(box: ScanTextBox): ScanTextMask? {
    if (box.text.isBlank() || !(box.size > 0f)) return null
    val fontPx = (box.size * SCAN_TEXT_REFERENCE_SIDE).coerceIn(12f, 200f)
    // pixels of the mask per share of the page's longer side
    val perShare = fontPx / box.size
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = fontPx
        typeface = Typeface.DEFAULT
        color = android.graphics.Color.BLACK
    }
    val lines = box.text.split('\n')
    val metrics = paint.fontMetrics
    val lineHeight = metrics.descent - metrics.ascent
    val pad = kotlin.math.ceil(fontPx * 0.15f)
    val w = (lines.maxOf { paint.measureText(it) } + 2 * pad).toInt().coerceIn(1, 4096)
    val h = (lineHeight * lines.size + 2 * pad).toInt().coerceIn(1, 4096)
    val bitmap = runCatching { Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8) }.getOrNull() ?: return null
    val canvas = Canvas(bitmap)
    for ((i, line) in lines.withIndex()) canvas.drawText(line, pad, pad - metrics.ascent + i * lineHeight, paint)
    // an ALPHA_8 row may be longer than the bitmap is wide: keep `w` bytes of each
    val stride = bitmap.rowBytes
    val rows = java.nio.ByteBuffer.allocate(stride * h)
    bitmap.copyPixelsToBuffer(rows)
    bitmap.recycle()
    val all = rows.array()
    val bytes = if (stride == w) all.copyOf(w * h) else ByteArray(w * h).also { out -> for (y in 0 until h) System.arraycopy(all, y * stride, out, y * w, w) }
    return ScanTextMask(w, h, bytes, w / perShare, h / perShare)
}

/** The size of [box] as shares of the page's longer side (width, height), as the engine will draw it. */
internal fun scanTextBoxSize(box: ScanTextBox): Pair<Float, Float>? = scanTextMask(box)?.let { it.boxWidth to it.boxHeight }

/** [marks] for the engine; null for none. */
internal fun scanNativeMarkup(marks: ScanMarks?): ScanMarkup? {
    if (marks == null || marks.isEmpty) return null
    return ScanMarkup(
        turns = marks.turns.mod(4).toUByte(),
        strokes = marks.strokes.map { ScanStroke(it.tool.id, (it.colour and 0xFFFFFF).toUInt(), it.width, it.points.toList()) },
        texts = marks.texts.mapNotNull { t ->
            scanTextMask(t)?.let { m -> ScanTextMark(t.x, t.y, m.boxWidth, m.boxHeight, (t.colour and 0xFFFFFF).toUInt(), m.width.toUInt(), m.height.toUInt(), m.bytes) }
        },
    )
}
