package io.github.artcraftandroid.pdfcraft.ui.viewer

/** A favorite stores the selected pen type and appearance, not document data or author identity. */
internal data class InkPreset private constructor(
    val argb: Int,
    val width: Float,
    val opacity: Float,
    val style: InkStyle,
) {
    fun encode(): String = "v2|${argb.toUInt().toString(16).padStart(8, '0')}|$width|$opacity|${style.key}"

    companion object {
        fun create(argb: Int, width: Float, opacity: Float, style: InkStyle = InkStyle.Ballpoint): InkPreset? =
            if (width.isFinite() && width in InkPenSettings.MIN_WIDTH..InkPenSettings.MAX_WIDTH && opacity.isFinite() && opacity in 0.25f..1f) {
                InkPreset(argb, width, opacity, style)
            } else null

        fun decode(value: String?): InkPreset? {
            if (value == null || value.length > 128) return null
            val parts = value.split('|')
            if ((parts.size != 4 || parts[0] != "v1") && (parts.size != 5 || parts[0] != "v2")) return null
            if (parts[1].length != 8 || !parts[1].all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
            val color = parts[1].toLongOrNull(16)?.toInt() ?: return null
            val style = if (parts[0] == "v1") InkStyle.Ballpoint else InkStyle.fromKey(parts[4]) ?: return null
            return create(color, parts[2].toFloatOrNull() ?: return null, parts[3].toFloatOrNull() ?: return null, style)
        }

        /** Most favorites kept; far more than a pen rack needs, but bounded. */
        const val MAX_PRESETS = 48
        private const val LIST_PREFIX = "list1;"

        /** The whole favorites list in one preferences string (#149). */
        fun encodeList(presets: List<InkPreset>): String = LIST_PREFIX + presets.take(MAX_PRESETS).joinToString(";") { it.encode() }

        /** A stored list, dropping malformed entries; null when the string is not a list at all. */
        fun decodeList(value: String?): List<InkPreset>? {
            if (value == null || !value.startsWith(LIST_PREFIX) || value.length > MAX_PRESETS * 130 + LIST_PREFIX.length) return null
            val body = value.removePrefix(LIST_PREFIX)
            if (body.isEmpty()) return emptyList()
            return body.split(';').mapNotNull { decode(it) }.take(MAX_PRESETS)
        }

        val defaults: List<InkPreset> = listOf(
            InkPreset(0xFF0000FF.toInt(), 1f, 1f, InkStyle.Ballpoint),
            InkPreset(0xFFE52237.toInt(), 3f, 1f, InkStyle.Fountain),
            InkPreset(0xFF161616.toInt(), 2f, 0.75f, InkStyle.Pencil),
        )
    }
}

/** [list] with the item at [index] moved by [delta] places (clamped); unchanged for a bad index. */
internal fun <T> List<T>.movedBy(index: Int, delta: Int): List<T> {
    if (index !in indices) return this
    val target = (index + delta).coerceIn(0, lastIndex)
    if (target == index) return this
    return toMutableList().apply { add(target, removeAt(index)) }
}

/** `#RRGGBB` or `#AARRGGBB` (the `#` optional) as ARGB; null for anything else. */
internal fun parseHexColor(text: String): Int? {
    val h = text.trim().removePrefix("#")
    if (h.length != 6 && h.length != 8) return null
    if (!h.all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
    val v = h.toLong(16)
    return if (h.length == 6) (0xFF000000L or v).toInt() else v.toInt()
}

/** The recent-colours list after using [argb]: most recent first, no duplicates, at most [max]. */
internal fun withRecentColor(recent: List<Int>, argb: Int, max: Int = 8): List<Int> =
    (listOf(argb) + recent.filter { it != argb }).take(max)
