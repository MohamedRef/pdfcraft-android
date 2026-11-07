package io.github.artcraftandroid.pdfcraft.engine

/** Only the four matching fields go back to the native heading-style command. */
internal data class PdfTextStyle(
    val font: String,
    val size: Double,
    val bold: Boolean,
    val italic: Boolean,
    val lines: Int,
    val pages: Int,
    val sample: String,
) {
    init {
        require(font.isNotBlank() && size.isFinite() && size > 0.0)
        require(lines >= 0 && pages >= 0)
    }
    fun arguments(): Map<String, Any> = mapOf("font" to font, "size" to size, "bold" to bold, "italic" to italic)
}

internal fun headingStyleArguments(styles: List<PdfTextStyle>): List<Map<String, Any>> {
    require(styles.size in 1..9) { "Choose between one and nine heading levels." }
    return styles.map { it.arguments() }
}
