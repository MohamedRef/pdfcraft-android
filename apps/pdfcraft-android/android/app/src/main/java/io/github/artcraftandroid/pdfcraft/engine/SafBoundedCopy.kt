package io.github.artcraftandroid.pdfcraft.engine

import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Read an untrusted SAF stream without unbounded heap/disk use or provider identity in logs. */
internal object SafBoundedCopy {
    const val MAX_BOOKMARK_SOURCE_PDF_BYTES = 512L * 1024L * 1024L
    private const val BUFFER_BYTES = 32 * 1024

    /**
     * The caller owns and closes both streams. A thrown exception leaves an incomplete output,
     * which the caller must delete rather than passing to any native PDF operation.
     */
    fun copy(input: InputStream, output: OutputStream, maxBytes: Long, kind: String): Long {
        require(maxBytes > 0L)
        val buffer = ByteArray(BUFFER_BYTES)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            if (n == 0) throw IOException("$kind provider did not make progress.")
            if (n > maxBytes - total) {
                throw IOException("$kind exceeds the ${maxBytes / (1024L * 1024L)} MiB size limit.")
            }
            output.write(buffer, 0, n)
            total += n
        }
        if (total == 0L) throw IOException("The selected $kind file is empty.")
        return total
    }
}
