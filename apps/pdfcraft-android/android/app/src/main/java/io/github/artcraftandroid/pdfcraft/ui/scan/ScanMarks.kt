package io.github.artcraftandroid.pdfcraft.ui.scan

import org.json.JSONObject

/*
 * Marks made by hand on a scanned page (#495): an eraser that paints the paper over what it rubs,
 * a pen, a highlighter and text. They are data, not pixels: the engine draws them on the made
 * page after its look, so changing the look keeps them, and a kept scan (#461) can change them.
 *
 * Points are shares of the page as it was shown when the marks were made, which had [ScanMarks.turns]
 * quarter turns clockwise in all (the classifier's and the person's); widths and text sizes are
 * shares of the page's longer side, which no quarter turn changes. A later turn of the page
 * moves the marks exactly; new borders, a change of flattening or a book split do not, so the
 * app asks before those and clears the page's marks.
 */

/** A mark's tool, with its word in a session and the log. */
enum class ScanMarkTool(val id: String, val letter: Char) {
    Erase("erase", 'e'),
    Pen("pen", 'p'),
    Highlighter("highlighter", 'h'),
    ;

    companion object {
        fun ofLetter(c: Char): ScanMarkTool? = entries.firstOrNull { it.letter == c }
    }
}

/**
 * One stroke: [points] are x, y, pressure, x, y, pressure …; x and y shares of the page as shown,
 * pressure 0–1 (1 for a finger). [width] is the full width at full pressure as a share of the
 * page's longer side; [colour] is 0xRRGGBB.
 */
class ScanStrokeMark(val tool: ScanMarkTool, val colour: Int, val width: Float, val points: FloatArray) {
    val pointCount: Int get() = points.size / 3

    override fun equals(other: Any?): Boolean =
        other is ScanStrokeMark && other.tool == tool && other.colour == colour && other.width == width && other.points.contentEquals(points)

    override fun hashCode(): Int = ((tool.hashCode() * 31 + colour) * 31 + width.hashCode()) * 31 + points.contentHashCode()
}

/**
 * A text box: its [text] (lines split by newline), its centre ([x], [y], shares of the page as
 * shown), its letters' height ([size], a share of the page's longer side) and [colour].
 */
data class ScanTextBox(val text: String, val x: Float, val y: Float, val size: Float, val colour: Int)

/** Every mark of a page; [turns] is the page's quarter turns in all when they were made (0–3). */
data class ScanMarks(val turns: Int = 0, val strokes: List<ScanStrokeMark> = emptyList(), val texts: List<ScanTextBox> = emptyList()) {
    val isEmpty: Boolean get() = strokes.isEmpty() && texts.isEmpty()

    /** How many marks of each kind: what the log and a kept page file say, never their content. */
    val counts: ScanMarkCounts
        get() = ScanMarkCounts(
            pen = strokes.count { it.tool == ScanMarkTool.Pen },
            highlighter = strokes.count { it.tool == ScanMarkTool.Highlighter },
            erase = strokes.count { it.tool == ScanMarkTool.Erase },
            text = texts.size,
        )

    /**
     * The marks as one line of text for a scan session (#38) and a kept scan (#461): fields split
     * by `|`, strokes and boxes by `;`. Points are whole ten-thousandths, widths and sizes whole
     * hundred-thousandths, pressure hundredths, and the text is Base64, so no tab, newline or
     * separator can get into the line.
     */
    fun encode(): String = buildString {
        append(VERSION).append('|').append(turns.mod(4)).append('|')
        strokes.joinTo(this, ";") { s ->
            buildString {
                append(s.tool.letter).append(':').append(hex(s.colour)).append(':').append(Math.round(s.width * 100_000.0)).append(':')
                for (i in 0 until s.pointCount) {
                    if (i > 0) append(' ')
                    append(Math.round(s.points[i * 3] * 10_000.0)).append(',').append(Math.round(s.points[i * 3 + 1] * 10_000.0)).append(',').append(Math.round(s.points[i * 3 + 2].coerceIn(0f, 1f) * 100.0))
                }
            }
        }
        append('|')
        texts.joinTo(this, ";") { t ->
            "${Math.round(t.x * 10_000.0)}:${Math.round(t.y * 10_000.0)}:${Math.round(t.size * 100_000.0)}:${hex(t.colour)}:" +
                java.util.Base64.getEncoder().encodeToString(t.text.toByteArray(Charsets.UTF_8))
        }
    }

    companion object {
        private const val VERSION = "m1"

        /** The most marks a page keeps, and points a stroke keeps (as the engine reads them). */
        const val MAX_MARKS = 2000
        const val MAX_POINTS = 20_000

        private fun hex(colour: Int) = String.format(java.util.Locale.US, "%06x", colour and 0xFFFFFF)

        /** The marks in [text]; null when it is not marks (anything malformed is left out, not guessed). */
        fun decode(text: String?): ScanMarks? {
            val parts = text?.split('|') ?: return null
            if (parts.size != 4 || parts[0] != VERSION) return null
            val turns = parts[1].toIntOrNull()?.takeIf { it in 0..3 } ?: return null
            val strokes = parts[2].split(';').filter { it.isNotEmpty() }.take(MAX_MARKS).mapNotNull(::stroke)
            val texts = parts[3].split(';').filter { it.isNotEmpty() }.take(MAX_MARKS).mapNotNull(::box)
            return ScanMarks(turns, strokes, texts).takeUnless { it.isEmpty }
        }

        private fun stroke(text: String): ScanStrokeMark? {
            val f = text.split(':')
            if (f.size != 4 || f[0].length != 1) return null
            val tool = ScanMarkTool.ofLetter(f[0][0]) ?: return null
            val colour = f[1].toIntOrNull(16) ?: return null
            val width = (f[2].toLongOrNull() ?: return null) / 100_000f
            if (width <= 0f || width > 1f) return null
            val triples = f[3].split(' ').take(MAX_POINTS)
            val points = FloatArray(triples.size * 3)
            for ((i, t) in triples.withIndex()) {
                val v = t.split(',')
                if (v.size != 3) return null
                points[i * 3] = (v[0].toIntOrNull() ?: return null) / 10_000f
                points[i * 3 + 1] = (v[1].toIntOrNull() ?: return null) / 10_000f
                points[i * 3 + 2] = ((v[2].toIntOrNull() ?: return null) / 100f).coerceIn(0f, 1f)
            }
            return ScanStrokeMark(tool, colour, width, points)
        }

        private fun box(text: String): ScanTextBox? {
            val f = text.split(':')
            if (f.size != 5) return null
            val words = runCatching { String(java.util.Base64.getDecoder().decode(f[4]), Charsets.UTF_8) }.getOrNull() ?: return null
            if (words.isBlank()) return null
            val size = (f[2].toLongOrNull() ?: return null) / 100_000f
            if (size <= 0f || size > 1f) return null
            return ScanTextBox(words, (f[0].toIntOrNull() ?: return null) / 10_000f, (f[1].toIntOrNull() ?: return null) / 10_000f, size, f[3].toIntOrNull(16) ?: return null)
        }
    }
}

/** How many marks of each kind a page has (#495), for the log and a kept page file. */
data class ScanMarkCounts(val pen: Int = 0, val highlighter: Int = 0, val erase: Int = 0, val text: Int = 0) {
    val total: Int get() = pen + highlighter + erase + text

    fun json(erasedShare: Double?): JSONObject = JSONObject().put("pen", pen).put("highlighter", highlighter).put("erase", erase).put("text", text)
        .put("total", total).put("erased_share", erasedShare?.takeIf { it.isFinite() }?.let { Math.round(it * 10000.0) / 10000.0 } ?: JSONObject.NULL)
}

/** A share point of the page turned [turns] quarter turns clockwise: the top left goes to the top right. */
internal fun scanTurnShare(x: Float, y: Float, turns: Int): Pair<Float, Float> {
    var p = x to y
    repeat(turns.mod(4)) { p = (1f - p.second) to p.first }
    return p
}

/** The page's quarter turns in all as shown: the person's own when they gave one, else the classifier's (#339). */
internal fun scanShownTotalTurns(personTurns: Int?, classifierTurns: Int): Int = (personTurns ?: classifierTurns).mod(4)

/**
 * Drop the points of a stroke that add nothing: one closer than [minStep] (a share of the longer
 * side, in the page's own proportions [aspect] = width / height) to the last point kept. The first
 * and last points always stay. Keeps a session small (#38) without changing the line drawn.
 */
internal fun scanThinPoints(points: FloatArray, minStep: Float, aspect: Float): FloatArray {
    val n = points.size / 3
    if (n <= 2) return points
    // shares to lengths on the longer side
    val (sx, sy) = if (aspect >= 1f) 1f to 1f / aspect else aspect to 1f
    val out = ArrayList<Float>(points.size)
    fun keep(i: Int) { out += points[i * 3]; out += points[i * 3 + 1]; out += points[i * 3 + 2] }
    keep(0)
    var lx = points[0]
    var ly = points[1]
    for (i in 1 until n - 1) {
        val dx = (points[i * 3] - lx) * sx
        val dy = (points[i * 3 + 1] - ly) * sy
        if (dx * dx + dy * dy >= minStep * minStep) {
            keep(i)
            lx = points[i * 3]
            ly = points[i * 3 + 1]
        }
    }
    keep(n - 1)
    return out.toFloatArray()
}

/** The marks fields of a `page` or `preview` log line (#495): counts only, never the marks themselves. */
internal fun scanMarksDiag(option: Boolean, marks: ScanMarks?, erasedShare: Float?, ms: Long?): Array<Pair<String, Any?>> {
    val c = marks?.counts ?: ScanMarkCounts()
    return arrayOf(
        "markup" to option, "marks" to c.total, "marks_pen" to c.pen, "marks_highlighter" to c.highlighter, "marks_erase" to c.erase, "marks_text" to c.text,
        "erased_share" to (erasedShare?.takeIf { it.isFinite() }?.let { Math.round(it * 10000.0) / 10000.0 } ?: 0.0), "markup_ms" to (ms ?: -1L),
    )
}

/** The `markup_done` log line's fields (#495). */
internal fun scanMarkupDoneDiag(index: Int, marks: ScanMarks?, erasedShare: Float?, ms: Long, cancelled: Boolean, changed: Boolean): Array<Pair<String, Any?>> {
    val c = marks?.counts ?: ScanMarkCounts()
    return arrayOf(
        "index" to index + 1, "strokes" to c.pen + c.highlighter, "pen" to c.pen, "highlighter" to c.highlighter, "erase" to c.erase, "text" to c.text,
        "erased_share" to (erasedShare?.takeIf { it.isFinite() }?.let { Math.round(it * 10000.0) / 10000.0 } ?: 0.0),
        "ms" to ms, "cancelled" to cancelled, "changed" to changed,
    )
}

/** The marks [page] is made with (#495): its own, with the testing option on; null for none. */
internal fun scanMarksFor(page: io.github.artcraftandroid.pdfcraft.ui.ScanPage, lab: io.github.artcraftandroid.pdfcraft.ui.ScanLab): ScanMarks? =
    page.marks?.takeIf { lab.markup && !it.isEmpty }

/**
 * The longest scan session kept in the activity's saved state itself (#38): Android refuses a
 * saved state of about 1 MB, and a scan with many marks (#495) can come near it.
 */
internal const val SCAN_STATE_MAX_CHARS = 200_000

private const val SCAN_STATE_FILE_HEADER = "pdfcraft-scan-file\t"

/** What the saved state keeps of [session]: the text itself, or (when longer than [SCAN_STATE_MAX_CHARS]) [file]'s name after writing it there. */
internal fun scanStateWrite(session: String, file: java.io.File): String {
    if (session.length <= SCAN_STATE_MAX_CHARS) return session
    return runCatching { file.writeText(session); SCAN_STATE_FILE_HEADER + file.name }.getOrElse { session }
}

/** The session the saved state kept: [kept] itself, or the text of [file] when [kept] names it; null when that file is gone. */
internal fun scanStateRead(kept: String, file: java.io.File): String? {
    if (!kept.startsWith(SCAN_STATE_FILE_HEADER)) return kept
    if (kept.removePrefix(SCAN_STATE_FILE_HEADER) != file.name) return null
    return runCatching { file.readText() }.getOrNull()
}
