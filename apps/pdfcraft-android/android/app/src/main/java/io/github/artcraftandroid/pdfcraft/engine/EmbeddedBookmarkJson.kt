package io.github.artcraftandroid.pdfcraft.engine

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/**
 * Bounded staging for an untrusted SAF bookmark JSON provider. This is not a JSON parser: the
 * existing Rust bookmark_import command validates schema, depth, node counts and destinations.
 * Keep the provider's URI out of the native engine and never buffer the entire input in heap.
 */
internal object EmbeddedBookmarkJson {
    const val MAX_BYTES = 16L * 1024L * 1024L
    private const val BUFFER_BYTES = 32 * 1024

    /** Return bytes staged, or fail before writing beyond MAX_BYTES. Caller owns stream cleanup. */
    fun copyBounded(input: InputStream, output: OutputStream): Long {
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            // A valid InputStream cannot return zero with a nonempty buffer. Do not spin on a
            // broken/hostile content provider.
            if (read == 0) throw IOException("Bookmark provider did not make progress.")
            if (read > MAX_BYTES - total) {
                throw IOException("Bookmark JSON exceeds the 16 MiB import limit.")
            }
            output.write(buffer, 0, read)
            total += read
        }
        if (total == 0L) throw IOException("The selected bookmark JSON is empty.")
        return total
    }
}
