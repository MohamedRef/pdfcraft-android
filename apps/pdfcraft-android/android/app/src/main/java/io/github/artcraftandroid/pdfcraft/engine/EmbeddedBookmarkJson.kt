package io.github.artcraftandroid.pdfcraft.engine

import java.io.InputStream
import java.io.OutputStream

/** SAF JSON staging policy; Rust bookmark_import remains the only bookmark tree validator. */
internal object EmbeddedBookmarkJson {
    const val MAX_BYTES = 16L * 1024L * 1024L

    fun copyBounded(input: InputStream, output: OutputStream): Long =
        SafBoundedCopy.copy(input, output, MAX_BYTES, "Bookmark JSON")
}
