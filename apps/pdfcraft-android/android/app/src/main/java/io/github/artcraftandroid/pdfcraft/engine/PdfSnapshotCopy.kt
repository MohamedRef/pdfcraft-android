package io.github.artcraftandroid.pdfcraft.engine

import java.io.EOFException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/** Bounded-memory copy of exactly one provider asset range; never closes caller-owned channels. */
internal object PdfSnapshotCopy {
    const val BUFFER_BYTES = 256 * 1024
    /**
     * A few document providers advertise a descriptor length but supply a non-seekable
     * pipe or a FileChannel that rejects positional reads. Retry via ContentResolver's
     * regular input stream; never swallow permission denial or fatal VM errors.
     */
    fun retryWithProviderStream(error: Throwable): Boolean =
        error is IOException ||
            error is UnsupportedOperationException ||
            error is IllegalStateException

    private const val TRANSFER_BYTES = 8L * 1024 * 1024

    fun copyRange(
        source: FileChannel,
        target: FileChannel,
        offset: Long,
        length: Long,
        transfer: (Long, Long) -> Long = { position, count -> source.transferTo(position, count, target) },
    ): Long {
        require(offset >= 0 && length >= 0 && offset <= Long.MAX_VALUE - length)
        var copied = 0L
        var buffered = false
        var buffer: ByteBuffer? = null
        while (copied < length) {
            if (!buffered) {
                val count = minOf(TRANSFER_BYTES, length - copied)
                val moved = try { transfer(offset + copied, count) } catch (_: UnsupportedOperationException) { 0L }
                if (moved < 0 || moved > count) throw IOException("Invalid PDF transfer length")
                if (moved > 0) { copied += moved; continue }
                // transferTo may legally return zero. Switch once; do not spin or truncate.
                buffered = true
                buffer = ByteBuffer.allocate(BUFFER_BYTES)
            }
            val chunk = requireNotNull(buffer)
            chunk.clear()
            chunk.limit(minOf(chunk.capacity().toLong(), length - copied).toInt())
            val read = source.read(chunk, offset + copied)
            if (read < 0) throw EOFException("PDF source ended before its declared length")
            if (read == 0) throw IOException("PDF source made no read progress")
            chunk.flip()
            while (chunk.hasRemaining()) {
                if (target.write(chunk) <= 0) throw IOException("PDF destination made no write progress")
            }
            copied += read
        }
        return copied
    }
}
