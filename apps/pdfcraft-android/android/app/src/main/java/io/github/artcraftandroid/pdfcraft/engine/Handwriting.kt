package io.github.artcraftandroid.pdfcraft.engine

import java.io.ByteArrayOutputStream
import java.util.Locale
import org.json.JSONObject

/** An ink comment to read: its 1-based index on the page and its box (points, top-left origin). */
data class InkBox(val index: Int, val x0: Float, val y0: Float, val x1: Float, val y1: Float) {
    val area: Float get() = (x1 - x0) * (y1 - y0)
    fun contains(x: Float, y: Float, pad: Float = 2f) = x >= x0 - pad && x <= x1 + pad && y >= y0 - pad && y <= y1 + pad
}

/** A word the recogniser read, with its box (points, top-left origin). */
data class InkWord(val text: String, val x: Float, val y: Float, val w: Float, val h: Float, val confidence: Float?) {
    val cx: Float get() = x + w / 2
    val cy: Float get() = y + h / 2
}

/**
 * Handwriting to text (#151): the ink alone is pasted on a blank page of the same size and read
 * by the engine's OCR, so printed text under it is not read too; each word goes to the ink
 * comment it lies in, and that comment's text becomes the words in reading order.
 */
object Handwriting {
    /** Words read at or under this confidence are dropped as noise. */
    const val MIN_CONFIDENCE = 30f

    /** A one-page PDF with nothing on it, [w] × [h] points. */
    fun blankPdf(w: Double, h: Double): ByteArray {
        require(w in 1.0..14_400.0 && h in 1.0..14_400.0)
        val fmt = { v: Double -> String.format(Locale.US, "%.2f", v) }
        val objects = listOf(
            "<< /Type /Catalog /Pages 2 0 R >>",
            "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
            "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${fmt(w)} ${fmt(h)}] /Resources << >> >>",
        )
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.5\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = objects.mapIndexed { i, body ->
            val at = out.size()
            out.write("${i + 1} 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
            at
        }
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append(String.format(Locale.US, "%010d 00000 n \n", o))
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    /** The ink comments of a `comment_list` result with no text of their own yet. */
    fun inksWithoutText(list: JSONObject): List<InkBox> {
        val a = list.optJSONArray("comments") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val c = a.optJSONObject(i) ?: return@mapNotNull null
            if (c.optString("type") != "Ink") return@mapNotNull null
            if (!c.isNull("contents") && c.optString("contents").isNotBlank()) return@mapNotNull null
            val r = c.optJSONArray("rect") ?: return@mapNotNull null
            InkBox(c.optInt("index"), r.optDouble(0).toFloat(), r.optDouble(1).toFloat(), r.optDouble(2).toFloat(), r.optDouble(3).toFloat())
        }
    }

    /** The words of page 1 of an `ocr_words` result. */
    fun words(result: JSONObject): List<InkWord> {
        val page = result.optJSONArray("pages")?.optJSONObject(0) ?: return emptyList()
        val a = page.optJSONArray("words") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val w = a.optJSONObject(i) ?: return@mapNotNull null
            val b = w.optJSONArray("box") ?: return@mapNotNull null
            val text = w.optString("text").trim()
            if (text.isEmpty()) return@mapNotNull null
            val conf = if (w.isNull("confidence")) null else w.optDouble("confidence").toFloat()
            InkWord(text, b.optDouble(0).toFloat(), b.optDouble(1).toFloat(), b.optDouble(2).toFloat(), b.optDouble(3).toFloat(), conf)
        }
    }

    /**
     * Each ink comment's text: the words whose centre lies in it (the smallest such comment when
     * several overlap), lines top to bottom and words left to right. Comments no word fell in
     * are left out.
     */
    fun assign(inks: List<InkBox>, words: List<InkWord>): Map<Int, String> {
        val byInk = HashMap<Int, MutableList<InkWord>>()
        for (w in words) {
            if (w.confidence != null && w.confidence <= MIN_CONFIDENCE) continue
            val ink = inks.filter { it.contains(w.cx, w.cy) }.minByOrNull { it.area } ?: continue
            byInk.getOrPut(ink.index) { mutableListOf() } += w
        }
        return byInk.mapValues { (_, ws) -> readingOrder(ws) }.filterValues { it.isNotBlank() }
    }

    /** Words grouped into lines (a word joins a line when its centre is within half its height of the line's), then joined. */
    internal fun readingOrder(words: List<InkWord>): String {
        val lines = mutableListOf<MutableList<InkWord>>()
        for (w in words.sortedBy { it.cy }) {
            val line = lines.lastOrNull()?.takeIf { l -> kotlin.math.abs(l.map { it.cy }.average().toFloat() - w.cy) <= maxOf(w.h, l.maxOf { it.h }) / 2 }
            if (line != null) line += w else lines += mutableListOf(w)
        }
        return lines.joinToString("\n") { l -> l.sortedBy { it.x }.joinToString(" ") { it.text } }
    }
}
