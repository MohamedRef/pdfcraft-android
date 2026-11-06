package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Durable marker beside a PDF working copy. A provider write can truncate its original
 * before a failure; keep and reopen the app-owned copy until the write actually succeeds.
 * It has no Android dependencies so JVM tests can verify its persistence behavior.
 */
internal object PendingWorkingCopy {
    private const val MARKER = ".writeback-pending"

    fun isPending(dir: File): Boolean = File(dir, MARKER).isFile

    fun mark(dir: File) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create PDF recovery directory")
        FileOutputStream(File(dir, MARKER)).use { stream ->
            stream.write(1)
            stream.fd.sync()
        }
    }

    fun clear(dir: File) {
        val marker = File(dir, MARKER)
        if (marker.exists() && !marker.delete()) throw IOException("Cannot clear PDF recovery marker")
    }

    /** Only for a user-confirmed Discard; automatic pruning must respect the marker. */
    fun discard(dir: File) {
        if (dir.exists() && !dir.deleteRecursively()) throw IOException("Cannot discard PDF recovery copy")
    }
}
