package io.github.artcraftandroid.pdfcraft.ui.scan

/** The first column of a look line in the session text (#301). */
private const val LOOK_LINE: String = "look"

/**
 * The looks of a scan in progress as session lines (#301, spec S15 section 1): one
 * `look<TAB>index<TAB>encoded` line per page, each ending in a newline, so the text can be
 * appended after the page lines.
 *
 * The session header does not change for these lines. `ScanSession.decode` ignores a first
 * column it does not know, so an older build still reads the session, and an older header would
 * make it reject the whole session.
 */
fun encodeLooks(looks: List<ScanPageLook>): String {
    val text = StringBuilder()
    looks.forEachIndexed { index, look ->
        text.append(LOOK_LINE).append('\t').append(index).append('\t').append(look.encode()).append('\n')
    }
    return text.toString()
}

/**
 * The looks of a scan with [pages] pages, from the session [text] (#301). A page with no look line
 * keeps the default look, which is what sessions from older builds get. A line for a page that
 * does not exist, a bad index, or a later line for the same page (the last one wins) is handled
 * as the rules of [ScanPageLook.decode] say. Other session lines are ignored.
 */
fun decodeLooks(text: String?, pages: Int): List<ScanPageLook> {
    val count: Int = pages.coerceAtLeast(0)
    val looks: MutableList<ScanPageLook> = MutableList(count) { ScanPageLook() }
    for (line in text.orEmpty().lines()) {
        val parts = line.split('\t')
        if (parts.size < 2 || parts[0] != LOOK_LINE) continue
        val index = parts[1].toIntOrNull() ?: continue
        if (index < 0 || index >= count) continue
        looks[index] = ScanPageLook.decode(parts.getOrNull(2))
    }
    return looks
}
