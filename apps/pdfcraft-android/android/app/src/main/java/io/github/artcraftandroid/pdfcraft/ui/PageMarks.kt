package io.github.artcraftandroid.pdfcraft.ui

import java.time.LocalDate

/** The three kinds of page mark, named as `doc_remove_marks` and the bridge's `pageMarks` name them. */
enum class MarkKind(val key: String, val tool: String) {
    HeaderFooter("header_footer", "doc_header_footer"),
    Watermark("watermark", "doc_watermark"),
    Background("background", "doc_background"),
}

/** The standard faces `doc_header_footer` draws in. */
enum class MarkFont { Helvetica, Times, Courier }

/** Header & footer: six text slots (header left, centre, right, then footer left, centre, right). */
data class HeaderFooterSpec(
    val slots: List<String> = List(6) { "" },
    val font: MarkFont = MarkFont.Helvetica,
    val fontSize: Float = 8f,
    val color: String = "#000000",
    /** Top, bottom, left, right, in points (the engine's defaults). */
    val margins: List<Float> = listOf(36f, 36f, 72f, 72f),
    val startNumber: Int = 1,
    val shrink: Boolean = false,
) {
    val usable: Boolean get() = slots.any { it.isNotBlank() }

    fun args(): Map<String, Any?> = buildMap {
        SLOT_KEYS.forEachIndexed { i, k -> slots.getOrNull(i)?.takeIf { it.isNotBlank() }?.let { put(k, it) } }
        put("font", font.name)
        put("font_size", fontSize.toDouble())
        put("color", color)
        put("margins", margins.map { it.toDouble() })
        put("start_number", startNumber.coerceAtLeast(1))
        put("shrink", shrink)
    }

    companion object {
        val SLOT_KEYS = listOf("header_left", "header_center", "header_right", "footer_left", "footer_center", "footer_right")
    }
}

/** Watermark: text, or a picture from [imagePath] (an image or a PDF's first page). */
data class WatermarkSpec(
    val text: String = "",
    val imagePath: String? = null,
    val imageName: String? = null,
    /** Degrees counter-clockwise. */
    val rotation: Float = 45f,
    val opacity: Float = 0.5f,
    val behind: Boolean = false,
    /** Share of the page a picture takes. */
    val scale: Float = 0.5f,
    val color: String = "#808080",
) {
    val usable: Boolean get() = imagePath != null || text.isNotBlank()

    fun args(): Map<String, Any?> = buildMap {
        if (imagePath != null) put("file", imagePath) else put("text", text.trim())
        put("rotation", rotation.toDouble())
        put("opacity", opacity.toDouble().coerceIn(0.0, 1.0))
        put("behind", behind)
        if (imagePath != null) put("scale", scale.toDouble().coerceIn(0.01, 1.0)) else put("color", color)
    }
}

/** Background: a colour, or a picture from [imagePath] fitted at [scale]. */
data class BackgroundSpec(
    val color: String = "#FFF8DC",
    val imagePath: String? = null,
    val imageName: String? = null,
    val opacity: Float = 1f,
    val scale: Float = 1f,
) {
    fun args(): Map<String, Any?> = buildMap {
        if (imagePath != null) {
            put("file", imagePath)
            put("scale", scale.toDouble().coerceIn(0.01, 1.0))
        } else {
            put("color", color)
        }
        put("opacity", opacity.toDouble().coerceIn(0.0, 1.0))
    }
}

/** The token chips Header & footer offers, in order (as `doc_header_footer` spells them). */
val MARK_TOKENS = listOf("<<1>>", "<<Page 1 of n>>", "<<1 of n>>", "<<n>>", "<<m/d/yyyy>>", "<<yyyy-mm-dd>>", "<<dd.mm.yyyy>>", "<<Bates Number#6#1##>>")

/** The arguments for a mark of [kind] from its spec, on [pages] (1-based; null: every page). */
fun markArgs(spec: Any, pages: List<Int>?, replace: Boolean): Map<String, Any?> {
    val base = when (spec) {
        is HeaderFooterSpec -> spec.args()
        is WatermarkSpec -> spec.args()
        is BackgroundSpec -> spec.args()
        else -> error("not a mark: $spec")
    }
    return base + buildMap {
        if (pages != null) put("pages", pages)
        if (replace) put("replace", true)
    }
}

/**
 * The arguments for the engine-drawn preview (#314) of [spec] on the page at place [index] among
 * the marked ones, in a document of [count] pages: the preview marks a copy of that one page, so
 * a header's or footer's tokens are filled in here as the engine fills them on that page, and
 * every page of the copy is marked. [replace] as Update sends it.
 */
fun markPreviewArgs(spec: Any, index: Int, count: Int, replace: Boolean, date: LocalDate = LocalDate.now()): Map<String, Any?> {
    val filled = if (spec is HeaderFooterSpec) spec.copy(slots = spec.slots.map { if (it.isBlank()) it else expandMarkTokens(it, index, count, spec.startNumber, date) }) else spec
    return markArgs(filled, null, replace)
}

/**
 * Pages written as "1-3, 5, 8-" (1-based, in the order written, each once) for a document of
 * [count] pages; null when anything is out of range or not understood, or nothing is listed.
 */
fun parsePageRange(text: String, count: Int): List<Int>? {
    val out = LinkedHashSet<Int>()
    for (part in text.split(',', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
        val dash = part.indexOfAny(charArrayOf('-', '–'))
        if (dash < 0) {
            val p = part.toIntOrNull()?.takeIf { it in 1..count } ?: return null
            out += p
        } else {
            val from = part.substring(0, dash).trim().ifEmpty { "1" }.toIntOrNull() ?: return null
            val to = part.substring(dash + 1).trim().ifEmpty { count.toString() }.toIntOrNull() ?: return null
            if (from !in 1..count || to !in 1..count) return null
            val step = if (to >= from) 1 else -1
            var p = from
            while (true) { out += p; if (p == to) break; p += step }
        }
    }
    return out.toList().ifEmpty { null }
}

private val MONTHS = listOf("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/**
 * [template] with its tokens filled in as `doc_header_footer` fills them, for the preview: [index]
 * is the page's place among the marked pages (0-based), [count] the document's page count. The
 * page number is [startNumber] + [index]; a Bates number counts from its own start the same way.
 */
fun expandMarkTokens(template: String, index: Int, count: Int, startNumber: Int, date: LocalDate = LocalDate.now()): String {
    val y = date.year
    val m = date.monthValue
    val d = date.dayOfMonth
    val yy = "%02d".format(Math.floorMod(y, 100))
    val p = startNumber.toLong() + index
    val bates0 = batesStart(template) ?: 1L
    val out = StringBuilder()
    var rest = template
    while (true) {
        val start = rest.indexOf("<<")
        if (start < 0) break
        out.append(rest, 0, start)
        val after = rest.substring(start + 2)
        val end = after.indexOf(">>")
        if (end < 0) { out.append(rest.substring(start)); return out.toString() }
        val token = after.substring(0, end)
        val value = when (token) {
            "1" -> "$p"
            "n" -> "$count"
            "1 of n" -> "$p of $count"
            "1/n" -> "$p/$count"
            "Page 1" -> "Page $p"
            "Page 1 of n" -> "Page $p of $count"
            "m/d" -> "$m/$d"
            "m/d/yy" -> "$m/$d/$yy"
            "m/d/yyyy" -> "$m/$d/$y"
            "mm/dd/yy" -> "%02d/%02d/%s".format(m, d, yy)
            "mm/dd/yyyy" -> "%02d/%02d/%d".format(m, d, y)
            "d/m/yy" -> "$d/$m/$yy"
            "d/m/yyyy" -> "$d/$m/$y"
            "dd/mm/yyyy" -> "%02d/%02d/%d".format(d, m, y)
            "d.m.yy" -> "$d.$m.$yy"
            "d.m.yyyy" -> "$d.$m.$y"
            "dd.mm.yy" -> "%02d.%02d.%s".format(d, m, yy)
            "dd.mm.yyyy" -> "%02d.%02d.%d".format(d, m, y)
            "yyyy-mm-dd" -> "%d-%02d-%02d".format(y, m, d)
            "yyyy/mm/dd" -> "%d/%02d/%02d".format(y, m, d)
            "yyyy/m/d" -> "$y/$m/$d"
            "mmmm d, yyyy" -> "${MONTHS[m.coerceIn(1, 12) - 1]} $d, $y"
            else -> if (token.startsWith("Bates Number")) {
                val parts = token.split('#')
                val digits = parts.getOrNull(1)?.toIntOrNull()?.coerceIn(1, 15) ?: 6
                parts.getOrElse(3) { "" } + (bates0 + index).toString().padStart(digits, '0') + parts.getOrElse(4) { "" }
            } else null
        }
        if (value == null) {
            // not a token: keep one '<' and look again from the next character
            out.append('<')
            rest = rest.substring(start + 1)
            continue
        }
        out.append(value)
        rest = after.substring(end + 2)
    }
    out.append(rest)
    return out.toString()
}

/** The Bates start number in [template] (`<<Bates Number#digits#start#…>>`), if it has one. */
private fun batesStart(template: String): Long? {
    val i = template.indexOf("<<Bates Number").takeIf { it >= 0 } ?: return null
    val t = template.substring(i + 2)
    val end = t.indexOf(">>").takeIf { it >= 0 } ?: return null
    return t.substring(0, end).split('#').getOrNull(2)?.toLongOrNull() ?: 1L
}
