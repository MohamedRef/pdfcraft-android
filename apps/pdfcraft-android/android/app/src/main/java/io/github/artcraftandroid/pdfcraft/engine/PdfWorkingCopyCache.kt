package io.github.artcraftandroid.pdfcraft.engine

import java.io.File

/** Source metadata may be absent or unreliable, e.g. for a remote document provider. */
internal data class PdfSourceFingerprint(val size: Long?, val modified: Long?) {
    val reliable: Boolean get() = size != null && size > 0 && modified != null && modified > 0
}

/**
 * A reusable provider copy has an atomic stamp from a completed transfer. If metadata is
 * missing, has changed, or a copy has been truncated, read from the provider again.
 * A pending unsynced working copy is protected separately by PendingWorkingCopy.
 */
internal object PdfWorkingCopyCache {
    private const val STAMP = ".source-fingerprint"
    private fun marker(dir: File) = File(dir, STAMP)

    fun hasStamp(dir: File): Boolean = marker(dir).isFile
    fun clear(dir: File) { marker(dir).delete() }

    fun canReuse(dir: File, target: File, source: PdfSourceFingerprint): Boolean {
        if (!source.reliable || !target.isFile || target.length() != source.size) return false
        return runCatching { marker(dir).readText() }.getOrNull() == "${source.size}:${source.modified}\n"
    }

    fun record(dir: File, source: PdfSourceFingerprint) {
        if (!source.reliable) return
        val tmp = File(dir, "$STAMP.part")
        try {
            tmp.writeText("${source.size}:${source.modified}\n")
            if (!tmp.renameTo(marker(dir))) {
                marker(dir).delete()
                if (!tmp.renameTo(marker(dir))) return
            }
        } catch (_: java.io.IOException) {
            // A stamp is only an optimization; a future open can safely copy again.
        } finally {
            tmp.delete()
        }
    }
}
