package io.github.artcraftandroid.pdfcraft.ui

/**
 * A scan in progress as text, kept in the activity's saved state: Android often closes the app
 * while the camera app is in front, and the photo it brings back belongs to this (#38).
 *
 * [shot] is the file the camera is writing, [retake] the page it replaces, [forImage] when the
 * photo is for Add image rather than the scan.
 */
data class ScanSession(
    val shot: String?,
    val retake: Int?,
    val forImage: Boolean,
    val name: String,
    val pages: List<Page>,
) {
    /** [turns] is the person's own turn of the page (#339), null while the classifier decides. */
    /** [preset] is the kind of document the person chose for the page (#299), null while Clean scan works it out. */
    /** [book] is a book spread's half as [io.github.artcraftandroid.pdfcraft.ui.scan.ScanBookHalf.encode] wrote it (#303); null for a page of its own. */
    /** [flatten] is the person's Flatten page choice (#302), null when they made none. */
    class Page(val path: String, val corners: FloatArray, val found: FloatArray?, val turns: Int? = null, val preset: String? = null, val book: String? = null, val flatten: Boolean? = null)

    fun encode(): String = buildString {
        append(HEADER).append('\n')
        append("shot\t").append(shot.orEmpty()).append('\n')
        append("retake\t").append(retake?.toString().orEmpty()).append('\n')
        append("image\t").append(if (forImage) "1" else "0").append('\n')
        append("name\t").append(name.replace('\n', ' ').replace('\t', ' ')).append('\n')
        for (p in pages) {
            append("page\t").append(p.path).append('\t').append(p.corners.joinToString(","))
            append('\t').append(p.found?.joinToString(",").orEmpty())
            append('\t').append(p.turns?.toString().orEmpty())
            append('\t').append(p.preset?.takeIf(::isWord).orEmpty())
            // the two halves of a book spread name the same photo; the seventh field says which half (#303)
            if (p.book != null || p.flatten != null) append('\t').append(p.book.orEmpty().replace('\t', ' ').replace('\n', ' '))
            // the eighth, the page's Flatten page choice (#302): 1, 0, or nothing
            if (p.flatten != null) append('\t').append(if (p.flatten) "1" else "0")
            append('\n')
        }
    }

    companion object {
        // 2: a page line may end with the person's turn of the page (#339); a session written as
        // 1 (before an update) still comes back, its pages with no turn
        private const val HEADER = "pdfcraft-scan 2"
        private const val HEADER_V1 = "pdfcraft-scan 1"

        /** The session in [text]; null when there is none or it isn't one. A malformed page is left out. */
        fun decode(text: String?): ScanSession? {
            val lines = text?.lines() ?: return null
            if (lines.firstOrNull() != HEADER && lines.firstOrNull() != HEADER_V1) return null
            var shot: String? = null
            var retake: Int? = null
            var forImage = false
            var name = ""
            val pages = mutableListOf<Page>()
            for (line in lines.drop(1)) {
                val parts = line.split('\t')
                val value = parts.getOrNull(1).orEmpty()
                when (parts.first()) {
                    "shot" -> shot = value.ifEmpty { null }
                    "retake" -> retake = value.toIntOrNull()
                    "image" -> forImage = value == "1"
                    "name" -> name = value
                    "page" -> {
                        val corners = corners(parts.getOrNull(2)) ?: continue
                        val turns = parts.getOrNull(4)?.toIntOrNull()?.takeIf { it in 0..3 }
                        val preset = parts.getOrNull(5)?.takeIf { it.isNotEmpty() && isWord(it) }
                        val flatten = when (parts.getOrNull(7)) {
                            "1" -> true
                            "0" -> false
                            else -> null
                        }
                        if (value.isNotEmpty()) pages += Page(value, corners, corners(parts.getOrNull(3)), turns, preset, parts.getOrNull(6)?.ifEmpty { null }, flatten)
                    }
                }
            }
            return ScanSession(shot, retake, forImage, name, pages)
        }

        // a kind of document's word: lowercase letters and underscores, nothing that could break a line
        private fun isWord(text: String) = text.isNotEmpty() && text.length <= 32 && text.all { it in 'a'..'z' || it == '_' }

        // eight shares of the photo (x1, y1 … x4, y4), or null
        private fun corners(text: String?): FloatArray? {
            val values = text?.split(',')?.map { it.toFloatOrNull() ?: return null } ?: return null
            if (values.size != 8 || values.any { !it.isFinite() }) return null
            return values.toFloatArray()
        }
    }
}
