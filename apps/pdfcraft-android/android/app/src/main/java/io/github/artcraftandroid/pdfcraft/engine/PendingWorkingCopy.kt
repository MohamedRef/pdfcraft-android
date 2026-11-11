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

    /**
     * Mark [dir]'s working copy as holding changes the original may not have. [copy] names the
     * working copy the changes are in, so recovery reopens exactly that file (#101).
     */
    fun mark(dir: File, copy: String? = null) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Cannot create PDF recovery directory")
        val name = copy?.takeIf { validCopyName(it) }
        // Keep the name an earlier mark recorded when this one does not know it.
        val recorded = name ?: recordedCopy(dir)
        FileOutputStream(File(dir, MARKER)).use { stream ->
            if (recorded != null) stream.write(recorded.toByteArray(Charsets.UTF_8)) else stream.write(1)
            stream.fd.sync()
        }
    }

    /** The working copy's name recorded with the marker, if a valid one was. */
    fun recordedCopy(dir: File): String? {
        val marker = File(dir, MARKER)
        if (!marker.isFile) return null
        val name = runCatching { marker.readText(Charsets.UTF_8) }.getOrNull() ?: return null
        return name.takeIf { validCopyName(it) }
    }

    /**
     * The working copy in [dir] that recovery must open: the one the marker names, else the most
     * recently written PDF (ties by name), never an arbitrary directory-order pick (#101).
     */
    fun recoveryCopy(dir: File): File? {
        recordedCopy(dir)?.let { name -> File(dir, name).takeIf { it.isFile } }?.let { return it }
        return dir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedWith(compareByDescending<File> { it.lastModified() }.thenBy { it.name })
            ?.firstOrNull()
    }

    private fun validCopyName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 255 && !name.startsWith(".") && name.none { it == '/' || it == '\\' || it.isISOControl() }

    fun clear(dir: File) {
        val marker = File(dir, MARKER)
        if (marker.exists() && !marker.delete()) throw IOException("Cannot clear PDF recovery marker")
    }

    /** Only for a user-confirmed Discard; automatic pruning must respect the marker. */
    fun discard(dir: File) {
        if (dir.exists() && !dir.deleteRecursively()) throw IOException("Cannot discard PDF recovery copy")
    }
}

/**
 * Whether bytes read from a PDF's start ([head]) and, when readable, its end ([tail]) look like an
 * intact file: `%PDF-` within the first 1 KiB, and `%%EOF` near the end. A truncated original
 * fails this, so a failed write-back's recovery copy is never discarded for it.
 */
internal fun pdfLooksIntact(head: ByteArray, tail: ByteArray?): Boolean {
    val start = String(head, Charsets.ISO_8859_1)
    if (!start.contains("%PDF-")) return false
    if (tail == null) return true
    return String(tail, Charsets.ISO_8859_1).contains("%%EOF")
}
