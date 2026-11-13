package io.github.artcraftandroid.pdfcraft.ui.scan

import android.content.SharedPreferences
import io.github.artcraftandroid.pdfcraft.ui.ScanSession
import java.io.File

/*
 * Recent scans (#461): the last scans saved keep their photos and each page's settings (corners,
 * look, strength, turn, flattening) in the app's own storage, so a scan can be opened again on the
 * document screen, its borders or look changed, and saved again. The owner chose the last 10 scans.
 * What can be worked out without a phone is here, so it can be tested on the desktop.
 */

/** How many scans are kept to be edited again; the oldest goes when an eleventh is saved. */
internal const val SCAN_RECENT_MAX = 10

/** Settings ▸ Scanner: whether scans are kept to be edited again (on until the person turns it off). */
internal object ScanRecentSetting {
    private const val KEY = "scanKeepRecent"

    fun get(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, true)

    fun set(prefs: SharedPreferences, on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
    }
}

/** A scan kept to be edited again: its name, when it was saved, its pages and the space its photos take. */
internal data class ScanRecentEntry(
    val id: String,
    val name: String,
    val savedAt: Long,
    val pages: Int,
    val bytes: Long,
    /** The first page's photo and corners, for the list's picture; null when the photo is gone. */
    val firstPhoto: File?,
    val firstCorners: FloatArray?,
    /** The first page as it was made when the scan was saved (#494), for the list's picture; null when it was not made in the background. */
    val cover: File? = null,
)

/** What keeping a scan did: its id, the photos and space it now takes, whether it replaced its own older copy, and how many old scans went. */
internal data class ScanRecentKept(val id: String, val photos: Int, val bytes: Long, val updated: Boolean, val kept: Int, val removed: Int)

/** A kept scan opened again: its pages, with photo paths in the scan's working folder, and the pages whose photo was missing. */
internal data class ScanRecentOpened(val session: ScanSession, val missing: Int, val savedAt: Long)

/** The ids to remove so that [max] scans stay: the oldest by [savedAt], never [keep]. */
internal fun scanRecentToPrune(savedAt: Map<String, Long>, max: Int, keep: String? = null): List<String> {
    val newestFirst = savedAt.entries.sortedWith(compareByDescending<Map.Entry<String, Long>> { it.key == keep }.thenByDescending { it.value }.thenByDescending { it.key })
    return newestFirst.drop(max.coerceAtLeast(0)).map { it.key }
}

/** The photos a scan's pages use, each once, in page order (the two halves of a book spread share one). */
internal fun scanRecentPhotos(pages: List<ScanSession.Page>): List<String> = pages.map { it.path }.distinct()

/** [session] with each page's photo path changed through [moved]; a page whose photo is not in it is left out. */
internal fun scanRecentRepath(session: ScanSession, moved: Map<String, String>): ScanSession =
    session.copy(shot = null, retake = null, forImage = false, pages = session.pages.mapNotNull { p -> moved[p.path]?.let { to -> ScanSession.Page(to, p.corners, p.found, p.turns, p.preset, p.book, p.look, p.flatten, p.auto, p.marks) } })

/** The log's line for a scan kept to be edited again (#461). */
internal fun scanRecentKeepDiag(kept: ScanRecentKept, pages: Int, ms: Long): Array<Pair<String, Any?>> =
    arrayOf("pages" to pages, "photos" to kept.photos, "bytes" to kept.bytes, "updated" to kept.updated, "kept" to kept.kept, "removed" to kept.removed, "max" to SCAN_RECENT_MAX, "ms" to ms)

/** The log's line for a kept scan opened again (#461): its pages, how old it is in hours, and pages whose photo was gone. */
internal fun scanRecentOpenDiag(pages: Int, missing: Int, savedAt: Long, now: Long, ms: Long): Array<Pair<String, Any?>> =
    arrayOf("pages" to pages, "missing" to missing, "age_h" to ((now - savedAt).coerceAtLeast(0L) / 3_600_000L), "ms" to ms)

/** The log's line for kept scans deleted (#461): how many, their space, and whether it was Delete all. */
internal fun scanRecentDeleteDiag(count: Int, bytes: Long, all: Boolean): Array<Pair<String, Any?>> =
    arrayOf("count" to count, "bytes" to bytes, "all" to all)

/**
 * The scans kept to be edited again, one folder each under [root]: `scan.txt` (the pages as
 * [ScanSession] writes them, with paths into the folder), `saved.txt` (when it was saved) and the
 * photos. Blocking: call it off the main thread.
 */
internal class ScanRecentStore(private val root: File) {
    private fun dir(id: String) = File(root, id)

    private fun savedAt(dir: File): Long = runCatching { File(dir, SAVED).readText().trim().toLong() }.getOrDefault(dir.lastModified())

    private fun session(dir: File): ScanSession? = runCatching { ScanSession.decode(File(dir, SESSION).readText()) }.getOrNull()

    private fun size(dir: File): Long = dir.listFiles()?.sumOf { if (it.isFile) it.length() else 0L } ?: 0L

    /** The kept scans, newest first; a folder that can't be read is left out. */
    fun list(): List<ScanRecentEntry> = (root.listFiles()?.filter { it.isDirectory } ?: emptyList()).mapNotNull { d ->
        val s = session(d) ?: return@mapNotNull null
        val first = s.pages.firstOrNull()
        ScanRecentEntry(d.name, s.name, savedAt(d), s.pages.size, size(d), first?.path?.let(::File)?.takeIf { it.isFile }, first?.corners, File(d, COVER).takeIf { it.isFile })
    }.sortedByDescending { it.savedAt }

    /** How many scans are kept and the space they take. */
    fun total(): Pair<Int, Long> = list().let { l -> l.size to l.sumOf { it.bytes } }

    /**
     * Keep [session]'s photos (moved, not copied) and settings as scan [id], or as a new scan when
     * [id] is null or gone. Photos the scan no longer uses are removed, and then the oldest scans
     * beyond [max]. [cover] is the first page as made (#494), copied for the list's picture.
     */
    fun keep(id: String?, session: ScanSession, now: Long, max: Int = SCAN_RECENT_MAX, cover: File? = null): ScanRecentKept {
        root.mkdirs()
        val updated = id != null && dir(id).isDirectory
        val key = if (updated) id!! else "scan-$now-${System.nanoTime() % 100000}"
        val d = dir(key).apply { mkdirs() }
        val moved = LinkedHashMap<String, String>()
        for ((n, path) in scanRecentPhotos(session.pages).withIndex()) {
            val from = File(path)
            if (!from.isFile) continue
            val to = File(d, "photo-$now-$n.jpg")
            if (!from.renameTo(to)) {
                from.copyTo(to, overwrite = true)
                from.delete()
            }
            moved[path] = to.absolutePath
        }
        val kept = scanRecentRepath(session, moved)
        writeAtomically(File(d, SESSION), kept.encode())
        writeAtomically(File(d, SAVED), now.toString())
        // the first page as made, for the list (#494); a scan saved without one shows its photo
        val hasCover = cover?.isFile == true && runCatching { cover.copyTo(File(d, COVER), overwrite = true) }.isSuccess
        // the photos of the scan as it was before it was opened again
        val used = moved.values.map { File(it).name }.toSet() + setOf(SESSION, SAVED) + (if (hasCover) setOf(COVER) else emptySet())
        d.listFiles()?.forEach { if (it.name !in used) it.delete() }
        val all = (root.listFiles()?.filter { it.isDirectory } ?: emptyList()).associate { it.name to savedAt(it) }
        val prune = scanRecentToPrune(all, max, keep = key)
        prune.forEach { dir(it).deleteRecursively() }
        return ScanRecentKept(key, moved.size, size(d), updated, all.size - prune.size, prune.size)
    }

    /** Copy scan [id]'s photos into [work] and hand back its pages, pointing at the copies; null when it can't be read. */
    fun open(id: String, work: File): ScanRecentOpened? {
        val d = dir(id)
        val s = session(d) ?: return null
        work.mkdirs()
        val copied = LinkedHashMap<String, String>()
        for (path in scanRecentPhotos(s.pages)) {
            val from = File(path)
            if (!from.isFile) continue
            val to = File(work, "scan-${System.nanoTime()}.jpg")
            from.copyTo(to, overwrite = true)
            copied[path] = to.absolutePath
        }
        val opened = scanRecentRepath(s, copied)
        return ScanRecentOpened(opened, s.pages.size - opened.pages.size, savedAt(d))
    }

    /** Remove scan [id]; the space it took. */
    fun delete(id: String): Long {
        val d = dir(id)
        val bytes = size(d)
        d.deleteRecursively()
        return bytes
    }

    /** Remove every kept scan; how many and the space they took. */
    fun deleteAll(): Pair<Int, Long> {
        val (count, bytes) = total()
        root.deleteRecursively()
        return count to bytes
    }

    private fun writeAtomically(f: File, text: String) {
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }

    private companion object {
        const val SESSION = "scan.txt"
        const val SAVED = "saved.txt"
        /** The first page as made (#494). */
        const val COVER = "cover.jpg"
    }
}
