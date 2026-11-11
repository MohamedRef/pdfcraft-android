package io.github.artcraftandroid.pdfcraft.engine

import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.zip.Deflater
import org.json.JSONObject

/** A notebook's page size, in PDF points (1/72 inch), portrait unless the name says otherwise. */
enum class NotebookSize(val width: Double, val height: Double) {
    A4(595.28, 841.89),
    Letter(612.0, 792.0),
    A5(419.53, 595.28),
    /** Phone width, twice as tall as wide, for long scrolling notes. */
    Tall(420.0, 840.0),
    /** A4 turned sideways, for tablets held landscape. */
    Landscape(841.89, 595.28),
}

/** What is printed on a notebook page. */
enum class NotebookPaper { Blank, NarrowRuled, WideRuled, Grid, Dots, Cornell, Music, Engineering }

/** The paper colour; lines are drawn in a shade that reads on it. */
enum class NotebookColor(val paper: Triple<Double, Double, Double>, val line: Triple<Double, Double, Double>, val accent: Triple<Double, Double, Double>) {
    White(Triple(1.0, 1.0, 1.0), Triple(0.72, 0.78, 0.86), Triple(0.86, 0.45, 0.45)),
    Cream(Triple(0.984, 0.965, 0.914), Triple(0.70, 0.72, 0.76), Triple(0.84, 0.47, 0.42)),
    Dark(Triple(0.13, 0.13, 0.14), Triple(0.33, 0.35, 0.39), Triple(0.62, 0.34, 0.34)),
}

/** The look of a notebook's pages: what [NotebookPdf] draws and what "add page" repeats. */
data class NotebookTemplate(
    val size: NotebookSize = NotebookSize.A4,
    val paper: NotebookPaper = NotebookPaper.Dots,
    val color: NotebookColor = NotebookColor.White,
) {
    fun toJson(): JSONObject = JSONObject().put("size", size.name).put("paper", paper.name).put("color", color.name)

    companion object {
        fun fromJson(o: JSONObject?): NotebookTemplate? {
            o ?: return null
            return runCatching {
                NotebookTemplate(NotebookSize.valueOf(o.getString("size")), NotebookPaper.valueOf(o.getString("paper")), NotebookColor.valueOf(o.getString("color")))
            }.getOrNull()
        }
    }
}

/**
 * Writes notebooks as plain, portable PDFs: every page draws its paper (colour, then lines, grid
 * or dots) as vector graphics from one shared, compressed content stream, so a page costs a few
 * hundred bytes and any PDF reader shows the template. Pure Kotlin, no engine call: the result is
 * opened like any other PDF.
 */
object NotebookPdf {
    private const val MM = 72.0 / 25.4

    /** A PDF of [pages] pages of [t]. */
    fun write(t: NotebookTemplate, pages: Int = 1): ByteArray {
        require(pages in 1..10_000)
        val content = deflate(content(t).toByteArray(Charsets.ISO_8859_1))
        val dotRow = if (t.paper == NotebookPaper.Dots) deflate(dotRow(t).toByteArray(Charsets.ISO_8859_1)) else null
        val objects = mutableListOf<ByteArray>()
        // 1 catalog, 2 pages, 3 content, 4 dot row (or an empty stub), 5.. the pages
        val kids = (0 until pages).joinToString(" ") { "${5 + it} 0 R" }
        objects += "<< /Type /Catalog /Pages 2 0 R >>".toByteArray(Charsets.ISO_8859_1)
        objects += "<< /Type /Pages /Kids [$kids] /Count $pages >>".toByteArray(Charsets.ISO_8859_1)
        objects += stream("<< /Length ${content.size} /Filter /FlateDecode >>", content)
        objects += if (dotRow != null) {
            stream("<< /Type /XObject /Subtype /Form /BBox [0 0 ${n(t.size.width)} 4] /Length ${dotRow.size} /Filter /FlateDecode >>", dotRow)
        } else {
            "null".toByteArray(Charsets.ISO_8859_1)
        }
        val resources = if (dotRow != null) "<< /XObject << /R 4 0 R >> >>" else "<< >>"
        repeat(pages) {
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 ${n(t.size.width)} ${n(t.size.height)}] /Contents 3 0 R /Resources $resources >>".toByteArray(Charsets.ISO_8859_1)
        }
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.5\n%âãÏÓ\n".toByteArray(Charsets.ISO_8859_1))
        val offsets = IntArray(objects.size)
        for ((i, body) in objects.withIndex()) {
            offsets[i] = out.size()
            out.write("${i + 1} 0 obj\n".toByteArray(Charsets.ISO_8859_1))
            out.write(body)
            out.write("\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xref = out.size()
        val sb = StringBuilder("xref\n0 ${objects.size + 1}\n0000000000 65535 f \n")
        for (o in offsets) sb.append(String.format(Locale.US, "%010d 00000 n \n", o))
        sb.append("trailer\n<< /Size ${objects.size + 1} /Root 1 0 R >>\nstartxref\n$xref\n%%EOF\n")
        out.write(sb.toString().toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun stream(dict: String, data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("$dict\nstream\n".toByteArray(Charsets.ISO_8859_1))
        out.write(data)
        out.write("\nendstream".toByteArray(Charsets.ISO_8859_1))
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }

    /** A number as PDF writes it: at most two decimals, no exponent. */
    internal fun n(v: Double): String {
        val r = Math.round(v * 100) / 100.0
        return if (r == Math.floor(r)) r.toLong().toString() else String.format(Locale.US, "%.2f", r).trimEnd('0').trimEnd('.')
    }

    private fun rgb(c: Triple<Double, Double, Double>, stroke: Boolean) = "${n(c.first)} ${n(c.second)} ${n(c.third)} ${if (stroke) "RG" else "rg"}"

    /** The page's drawing: the paper's colour, then its lines. */
    internal fun content(t: NotebookTemplate): String {
        val (w, h) = t.size.width to t.size.height
        val sb = StringBuilder()
        if (t.color != NotebookColor.White) sb.append("${rgb(t.color.paper, false)} 0 0 ${n(w)} ${n(h)} re f\n")
        sb.append("${rgb(t.color.line, true)} 0.5 w\n")
        fun hLine(y: Double, x0: Double = 0.0, x1: Double = w) = sb.append("${n(x0)} ${n(y)} m ${n(x1)} ${n(y)} l\n")
        fun vLine(x: Double, y0: Double = 0.0, y1: Double = h) = sb.append("${n(x)} ${n(y0)} m ${n(x)} ${n(y1)} l\n")
        val top = 20 * MM
        val bottom = 12 * MM
        when (t.paper) {
            NotebookPaper.Blank -> {}
            NotebookPaper.NarrowRuled, NotebookPaper.WideRuled -> {
                val step = if (t.paper == NotebookPaper.NarrowRuled) 7.1 * MM else 8.7 * MM
                var y = h - top
                while (y > bottom) { hLine(y); y -= step }
                sb.append("S\n")
                // the margin line, a little in from the left
                sb.append("${rgb(t.color.accent, true)} ${n(25 * MM)} 0 m ${n(25 * MM)} ${n(h)} l S\n")
            }
            NotebookPaper.Grid, NotebookPaper.Engineering -> {
                val step = 5 * MM
                var i = 1
                while (i * step < w) { vLine(i * step); i++ }
                i = 1
                while (i * step < h) { hLine(h - i * step); i++ }
                sb.append("S\n")
                if (t.paper == NotebookPaper.Engineering) {
                    // every fifth line darker, as on engineering pads
                    sb.append("0.9 w\n")
                    i = 5
                    while (i * step < w) { vLine(i * step); i += 5 }
                    i = 5
                    while (i * step < h) { hLine(h - i * step); i += 5 }
                    sb.append("S\n")
                }
            }
            NotebookPaper.Dots -> {
                val step = 5 * MM
                var i = 1
                // one row of dots (a form, drawn once per row) keeps the page tiny
                while (i * step < h) { sb.append("q 1 0 0 1 0 ${n(h - i * step - 2)} cm /R Do Q\n"); i++ }
            }
            NotebookPaper.Cornell -> {
                val step = 7.1 * MM
                val cue = 60 * MM
                val summary = 50 * MM
                var y = h - top
                while (y > summary) { hLine(y, cue, w); y -= step }
                sb.append("S\n")
                sb.append("1 w\n")
                hLine(h - top + step)
                vLine(cue, summary, h - top + step)
                hLine(summary)
                sb.append("S\n")
            }
            NotebookPaper.Music -> {
                val line = 2 * MM
                val gap = 13 * MM
                val margin = 15 * MM
                var y = h - top
                while (y - 4 * line > bottom) {
                    for (k in 0 until 5) hLine(y - k * line, margin, w - margin)
                    y -= 4 * line + gap
                }
                sb.append("S\n")
            }
        }
        return sb.toString()
    }

    /** One row of 5 mm dots, as a form 4 points tall. */
    private fun dotRow(t: NotebookTemplate): String {
        val sb = StringBuilder("${rgb(t.color.line, true)} 1.6 w 1 J\n")
        val step = 5 * MM
        var i = 1
        while (i * step < t.size.width) { sb.append("${n(i * step)} 2 m ${n(i * step)} 2 l\n"); i++ }
        sb.append("S\n")
        return sb.toString()
    }
}
