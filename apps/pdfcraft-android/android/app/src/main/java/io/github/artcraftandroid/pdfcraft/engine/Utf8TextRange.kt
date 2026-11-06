package io.github.artcraftandroid.pdfcraft.engine

/** Compose selections use UTF-16; native editor ranges use half-open UTF-8 byte offsets. */
internal fun nativeUtf8Range(text: String, anchor: Int, focus: Int): List<Int>? {
    val start = minOf(anchor, focus)
    val end = maxOf(anchor, focus)
    require(start >= 0 && end <= text.length)
    fun boundary(at: Int): Boolean = at == 0 || at == text.length ||
        !(text[at - 1].isHighSurrogate() && text[at].isLowSurrogate())
    require(boundary(start) && boundary(end)) { "Selection splits a Unicode character." }
    if (start == end) return null
    // Count once without allocating copies of a potentially large paragraph or UTF-8 buffers.
    var at = 0
    var bytes = 0L
    var startBytes = 0
    while (at < end) {
        if (at == start) startBytes = bytes.toInt()
        val c = text[at]
        bytes += when {
            c.code <= 0x7f -> 1
            c.code <= 0x7ff -> 2
            c.isHighSurrogate() -> {
                require(at + 1 < end && text[at + 1].isLowSurrogate())
                at++
                4
            }
            c.isLowSurrogate() -> throw IllegalArgumentException("Unpaired Unicode surrogate.")
            else -> 3
        }
        require(bytes <= Int.MAX_VALUE)
        at++
    }
    return listOf(startBytes, bytes.toInt())
}
