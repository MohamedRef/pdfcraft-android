package io.github.artcraftandroid.pdfcraft.engine

import org.json.JSONObject

/** One comment as a study digest lists it. */
data class DigestEntry(
    /** 1-based page. */
    val page: Int,
    /** The PDF subtype: Highlight, Underline, Ink, Text, FreeText, … */
    val type: String,
    /** `#RRGGBB`, when it has a colour. */
    val color: String?,
    val author: String?,
    /** The comment's own text. */
    val note: String?,
    /** The text it marks (highlights, underlines, …), when known. */
    var marked: String? = null,
    val status: String?,
    val checked: Boolean,
    val replies: List<Pair<String?, String>>,
    /** Its rectangle (points, top-left origin), for cropping an image of it. */
    val rect: FloatArray,
    /** The image of it in the export (a relative path), for ink. */
    var image: String? = null,
) {
    val isMarkup: Boolean get() = type in MARKUP
    val isInk: Boolean get() = type == "Ink"

    companion object {
        val MARKUP = setOf("Highlight", "Underline", "StrikeOut", "Squiggly")
    }
}

/** How a digest groups its entries. */
enum class DigestGrouping { Page, Color }

/** What a digest includes. */
enum class DigestFilter { All, Checked, Unresolved }

/** The words the digest is written with (translated by the caller). */
data class DigestLabels(
    val page: String = "Page %1\$d",
    val color: String = "Colour %s",
    val noColor: String = "No colour",
    val replies: String = "Replies",
    val status: String = "Status",
    val drawing: String = "Drawing",
    val colorNames: Map<String, String> = emptyMap(),
)

/**
 * Turns a document's comments into a review sheet: Markdown (Obsidian-friendly, with
 * `[[file#page=N]]` links) or CSV. Pure text formatting; the caller gathers the comments,
 * the marked text and ink images.
 */
object StudyDigest {
    /** The entries of a `comment_list` result, in page order, replies folded in. */
    fun parse(list: JSONObject): List<DigestEntry> {
        val arr = list.optJSONArray("comments") ?: return emptyList()
        val out = ArrayList<DigestEntry>(arr.length())
        for (i in 0 until arr.length()) {
            val c = arr.optJSONObject(i) ?: continue
            val type = c.optString("type")
            // pop-ups, links and widgets are not listed; sounds and files have nothing to read
            if (type in setOf("Popup", "Link", "Widget")) continue
            val rect = c.optJSONArray("rect")?.let { r -> FloatArray(4) { k -> r.optDouble(k, 0.0).toFloat() } } ?: FloatArray(4)
            val replies = c.optJSONArray("replies")?.let { r ->
                (0 until r.length()).mapNotNull { k -> r.optJSONObject(k)?.let { it.optStringOrNull("author") to (it.optStringOrNull("contents") ?: return@mapNotNull null) } }
            } ?: emptyList()
            out += DigestEntry(
                page = c.optInt("page", 1),
                type = type,
                color = c.optStringOrNull("color")?.uppercase()?.let { if (it.startsWith("#")) it else "#$it" },
                author = c.optStringOrNull("author"),
                note = c.optStringOrNull("contents")?.trim()?.ifEmpty { null },
                status = c.optStringOrNull("status")?.takeIf { it != "None" },
                checked = c.optBoolean("marked", false),
                replies = replies,
                rect = rect,
            )
        }
        return out.sortedWith(compareBy<DigestEntry> { it.page }.thenBy { it.rect[1] }.thenBy { it.rect[0] })
    }

    fun filter(entries: List<DigestEntry>, filter: DigestFilter): List<DigestEntry> = when (filter) {
        DigestFilter.All -> entries
        DigestFilter.Checked -> entries.filter { it.checked }
        DigestFilter.Unresolved -> entries.filter { it.status == null || it.status !in setOf("Accepted", "Completed", "Cancelled", "Rejected") }
    }

    /** The nearest of a few named colours, so yellow highlights group as "Yellow". */
    fun colorKey(hex: String?): String? {
        val v = hex?.removePrefix("#")?.toIntOrNull(16) ?: return null
        val (r, g, b) = Triple(v shr 16 and 255, v shr 8 and 255, v and 255)
        return NAMED.minBy { (_, c) ->
            val (cr, cg, cb) = Triple(c shr 16 and 255, c shr 8 and 255, c and 255)
            (r - cr) * (r - cr) + (g - cg) * (g - cg) + (b - cb) * (b - cb)
        }.first
    }

    private val NAMED = listOf(
        "yellow" to 0xFFEB3B, "green" to 0x4CAF50, "blue" to 0x2196F3, "pink" to 0xF06292, "red" to 0xE53935,
        "orange" to 0xFF9800, "purple" to 0x9C27B0, "black" to 0x000000, "grey" to 0x9E9E9E,
    )

    /** One line of Markdown, safe inside a list item: no leading markers, no raw newlines. */
    internal fun inline(s: String): String = s.replace("\r", "").split('\n').joinToString(" ") { it.trim() }.replace(Regex("^([#>*+-]|\\d+\\.)\\s"), "\\\\$0").trim()

    fun markdown(title: String, fileLink: String, entries: List<DigestEntry>, grouping: DigestGrouping, labels: DigestLabels = DigestLabels()): String {
        val sb = StringBuilder("# ").append(inline(title)).append("\n\n")
        val groups: List<Pair<String, List<DigestEntry>>> = when (grouping) {
            DigestGrouping.Page -> entries.groupBy { it.page }.toSortedMap().map { (p, e) -> labels.page.format(p) to e }
            DigestGrouping.Color -> {
                val order = NAMED.map { it.first }
                entries.groupBy { colorKey(it.color) }.entries
                    .sortedBy { (k, _) -> k?.let(order::indexOf) ?: Int.MAX_VALUE }
                    .map { (k, e) -> (k?.let { labels.colorNames[it] ?: it.replaceFirstChar(Char::uppercase) } ?: labels.noColor) to e }
            }
        }
        for ((heading, list) in groups) {
            sb.append("## ").append(heading).append("\n\n")
            for (e in list) {
                val link = "[[${fileLink}#page=${e.page}|p. ${e.page}]]"
                sb.append(if (e.checked) "- [x] " else "- ")
                val body = when {
                    e.marked != null && e.note != null -> "> ${inline(e.marked!!)}\n  ${inline(e.note)}"
                    e.marked != null -> "> ${inline(e.marked!!)}"
                    e.note != null -> inline(e.note)
                    e.isInk -> labels.drawing
                    else -> e.type
                }
                // a quote starts the item on its own line so Markdown keeps it inside the list
                if (body.startsWith(">")) sb.append(link).append("\n  ").append(body) else sb.append(body).append(" — ").append(link)
                e.status?.let { sb.append(" · ").append(labels.status).append(": ").append(it) }
                sb.append('\n')
                e.image?.let { sb.append("  ![").append(labels.drawing).append("](").append(it).append(")\n") }
                for ((who, text) in e.replies) sb.append("  - ").append(who?.let { "**${inline(it)}**: " } ?: "").append(inline(text)).append('\n')
            }
            sb.append('\n')
        }
        return sb.toString()
    }

    private fun csvCell(s: String?): String {
        val v = s.orEmpty()
        // a leading = + - @ would run as a formula in a spreadsheet
        val safe = if (v.isNotEmpty() && v[0] in "=+-@\t\r") "'$v" else v
        return if (safe.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }

    fun csv(entries: List<DigestEntry>): String {
        val sb = StringBuilder("page,type,color,author,marked_text,note,status,checked,replies\r\n")
        for (e in entries) {
            val replies = e.replies.joinToString("\n") { (who, t) -> (who?.let { "$it: " } ?: "") + t }
            sb.append(listOf(e.page.toString(), e.type, e.color, e.author, e.marked, e.note, e.status, if (e.checked) "yes" else "no", replies).joinToString(",") { csvCell(it) }).append("\r\n")
        }
        return sb.toString()
    }
}

private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key) || !has(key)) null else optString(key)
