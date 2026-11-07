package io.github.artcraftandroid.pdfcraft.ui.viewer

/** A favorite pen carries appearance only, never author, pen routing or uncommitted strokes. */
internal data class InkPreset private constructor(val argb: Int, val width: Float, val opacity: Float) {
    fun encode(): String = "v1|${argb.toUInt().toString(16).padStart(8, '0')}|$width|$opacity"

    companion object {
        fun create(argb: Int, width: Float, opacity: Float): InkPreset? =
            if (width.isFinite() && width in 1f..10f && opacity.isFinite() && opacity in 0.25f..1f) InkPreset(argb, width, opacity) else null

        fun decode(value: String?): InkPreset? {
            if (value == null || value.length > 128) return null
            val parts = value.split('|')
            if (parts.size != 4 || parts[0] != "v1" || parts[1].length != 8 || !parts[1].all { it in '0'..'9' || it.lowercaseChar() in 'a'..'f' }) return null
            val color = parts[1].toLongOrNull(16)?.toInt() ?: return null
            return create(color, parts[2].toFloatOrNull() ?: return null, parts[3].toFloatOrNull() ?: return null)
        }

        val defaults: List<InkPreset> = listOf(
            InkPreset(0xFF0000FF.toInt(), 1f, 1f),
            InkPreset(0xFFE52237.toInt(), 3f, 1f),
            InkPreset(0xFF161616.toInt(), 2f, 0.75f),
        )
    }
}
