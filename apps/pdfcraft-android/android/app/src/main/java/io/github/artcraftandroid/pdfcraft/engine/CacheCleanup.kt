package io.github.artcraftandroid.pdfcraft.engine

import java.io.File

/**
 * Removes temporary files the app leaves in its cache: decrypted print copies and other `out-*`
 * results, extracted attachments, picked files, voice playback copies and old share copies (#105).
 *
 * Each flow deletes its own file when it is done with it; this sweep is the safety net for
 * files a crash, a failed job or a killed process left behind. It runs when the app starts and
 * when a document closes, and never touches anything written in the last [SETTLE_MS] (a job may
 * still be using it) or a share copy younger than [SHARE_KEEP_MS] (a receiving app may still be
 * reading it).
 */
object CacheCleanup {
    /** Temporary files younger than this may still be in use. */
    const val SETTLE_MS = 10 * 60 * 1000L

    /** Share copies stay this long, so the app that received one can finish reading it. */
    const val SHARE_KEEP_MS = 60 * 60 * 1000L

    /** Folders that hold one subfolder per use (`attachments/<n>/name`, `picked/<n>/name`). */
    private val PER_USE_DIRS = setOf("attachments", "picked")

    /** Top-level cache files the app writes for one use. */
    fun isTemporaryFile(name: String): Boolean =
        name.startsWith("out-") || name.startsWith("voice-play") || name.startsWith("print-")

    /** Delete the stale temporary files under [cacheDir]; returns how many entries went. */
    fun sweep(cacheDir: File, now: Long = System.currentTimeMillis()): Int {
        var removed = 0
        val entries = cacheDir.listFiles() ?: return 0
        for (entry in entries) {
            when {
                entry.isDirectory && entry.name in PER_USE_DIRS ->
                    removed += removeOlderThan(entry, now - SETTLE_MS)
                entry.isDirectory && entry.name == "share" ->
                    removed += removeOlderThan(entry, now - SHARE_KEEP_MS, directoriesOnly = true)
                entry.isFile && isTemporaryFile(entry.name) && newest(entry) < now - SETTLE_MS ->
                    if (entry.delete()) removed++
            }
        }
        return removed
    }

    /**
     * Delete the children of [dir] whose newest file is older than [before]. With
     * [directoriesOnly], loose files (such as the open-timings report) are left alone.
     */
    fun removeOlderThan(dir: File, before: Long, directoriesOnly: Boolean = false): Int {
        var removed = 0
        for (child in dir.listFiles().orEmpty()) {
            if (directoriesOnly && !child.isDirectory) continue
            if (newest(child) < before && child.deleteRecursively()) removed++
        }
        return removed
    }

    /** The latest modification time of [f] or anything inside it. */
    private fun newest(f: File): Long =
        if (f.isDirectory) maxOf(f.lastModified(), f.listFiles().orEmpty().maxOfOrNull { newest(it) } ?: 0L)
        else f.lastModified()
}
