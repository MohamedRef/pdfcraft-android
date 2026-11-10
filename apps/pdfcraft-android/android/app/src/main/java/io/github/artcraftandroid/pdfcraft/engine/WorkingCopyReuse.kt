package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.io.RandomAccessFile

/**
 * Reusing a working copy instead of copying a large PDF in again on every open (#210).
 *
 * A copy is reused only when everything below still holds, so a doubtful case copies as before:
 * - the provider reports both a size and a modification date, and both equal what it reported
 *   when the copy was made;
 * - the copy itself is unchanged since then (same length and date), so no local edit or failed
 *   write-back is hidden behind it, and no recovery marker is pending;
 * - the copy still starts and ends like a PDF.
 * No Android types, so the decision is tested on the JVM.
 */
internal data class SourceStamp(val size: Long, val modified: Long) {
    val usable: Boolean get() = size > 0 && modified > 0
}

internal data class CopyStamp(val source: SourceStamp, val localLength: Long, val localModified: Long) {
    fun encode(): String = "v1 ${source.size} ${source.modified} $localLength $localModified\n"

    companion object {
        const val FILE = ".source-stamp"

        fun decode(text: String): CopyStamp? {
            val p = text.trim().split(' ')
            if (p.size != 5 || p[0] != "v1") return null
            val n = p.drop(1).map { it.toLongOrNull() ?: return null }
            return CopyStamp(SourceStamp(n[0], n[1]), n[2], n[3])
        }

        fun read(dir: File): CopyStamp? = runCatching { decode(File(dir, FILE).readText()) }.getOrNull()

        fun write(dir: File, stamp: CopyStamp) {
            val tmp = File(dir, "$FILE.part")
            tmp.writeText(stamp.encode())
            if (!tmp.renameTo(File(dir, FILE))) {
                tmp.delete()
                throw java.io.IOException("Cannot record working copy stamp")
            }
        }

        fun clear(dir: File) {
            File(dir, FILE).delete()
        }
    }
}

/** Whether [copy] can stand for the source, which now reports [now]. */
internal fun canReuseCopy(stamp: CopyStamp?, now: SourceStamp?, copy: File?, pending: Boolean): Boolean {
    if (pending || stamp == null || now == null || copy == null) return false
    if (!now.usable || stamp.source != now) return false
    if (!copy.isFile) return false
    val length = copy.length()
    if (length != stamp.localLength || length != now.size) return false
    if (copy.lastModified() != stamp.localModified) return false
    return fileLooksLikePdf(copy)
}

/** `%PDF-` near the start and `%%EOF` near the end, read without loading the file. */
internal fun fileLooksLikePdf(file: File): Boolean = runCatching {
    RandomAccessFile(file, "r").use { f ->
        val len = f.length()
        val head = ByteArray(minOf(1024L, len).toInt())
        f.readFully(head)
        val tailLen = minOf(2048L, len).toInt()
        val tail = ByteArray(tailLen)
        f.seek(len - tailLen)
        f.readFully(tail)
        pdfLooksIntact(head, tail)
    }
}.getOrDefault(false)

/** One kept working copy, for [copiesToKeep]. */
internal data class KeptCopy(val name: String, val bytes: Long, val lastUsed: Long)

/**
 * Which reusable copies stay: the most recently used ones whose total fits [budgetBytes].
 * [always] (the open document) stays whatever its size and does count toward the budget.
 */
internal fun copiesToKeep(copies: List<KeptCopy>, budgetBytes: Long, always: String? = null): Set<String> {
    val keep = HashSet<String>()
    var used = 0L
    copies.firstOrNull { it.name == always }?.let { keep += it.name; used += it.bytes }
    for (c in copies.sortedByDescending { it.lastUsed }) {
        if (c.name in keep) continue
        if (used + c.bytes > budgetBytes) continue
        keep += c.name
        used += c.bytes
    }
    return keep
}

/** At most 2 GiB of reusable copies, and never more than a quarter of the free space. */
internal fun reuseBudget(freeBytes: Long, capBytes: Long = 2L shl 30): Long =
    minOf(capBytes, (freeBytes.coerceAtLeast(0)) / 4)
