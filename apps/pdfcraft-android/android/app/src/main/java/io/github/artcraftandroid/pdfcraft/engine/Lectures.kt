package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** A comment written while a lecture was recorded: its id (`/NM`), page (0-based) and when, in the recording. */
data class LectureMark(val comment: String, val page: Int, val atMs: Long)

/** One recording made beside a document, and the comments written during it. */
data class Lecture(val id: String, val started: Long, val durationMs: Long, val marks: List<LectureMark>) {
    val fileName: String get() = "$id.m4a"

    fun json(): JSONObject = JSONObject().put("id", id).put("started", started).put("duration", durationMs)
        .put("marks", JSONArray(marks.map { JSONObject().put("c", it.comment).put("p", it.page).put("t", it.atMs) }))

    companion object {
        fun from(o: JSONObject): Lecture? {
            val id = o.optString("id").takeIf { Lectures.validId(it) } ?: return null
            val a = o.optJSONArray("marks") ?: JSONArray()
            val marks = (0 until a.length()).mapNotNull { i ->
                val m = a.optJSONObject(i) ?: return@mapNotNull null
                val c = m.optString("c").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
                LectureMark(c, m.optInt("p"), m.optLong("t").coerceAtLeast(0))
            }
            return Lecture(id, o.optLong("started"), o.optLong("duration").coerceAtLeast(0), marks)
        }
    }
}

/**
 * Lecture recordings (#142), kept beside the documents they were made with, in the app's private
 * files: the audio as `<id>.m4a`, and an index from each document's URI to its recordings and
 * when each comment was written in them. Small synchronous reads and writes; callers stay off
 * the main thread.
 */
class Lectures(val dir: File) {
    private val lock = Any()
    private val index = File(dir, "index.json")

    fun list(uri: String): List<Lecture> = synchronized(lock) {
        val a = read().optJSONArray(uri) ?: return@synchronized emptyList()
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Lecture::from) }.sortedByDescending { it.started }
    }

    fun audio(l: Lecture): File = File(dir, l.fileName)

    /** A new recording's id and file. */
    fun newFile(now: Long): Pair<String, File> {
        dir.mkdirs()
        val id = "lecture-$now"
        return id to File(dir, "$id.m4a")
    }

    /** Keep [l] (replacing one with its id) for [uri]. */
    fun put(uri: String, l: Lecture) = synchronized(lock) {
        val all = read()
        val list = all.optJSONArray(uri)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Lecture::from) } }.orEmpty()
        all.put(uri, JSONArray((list.filter { it.id != l.id } + l).map { it.json() }))
        write(all)
    }

    fun delete(uri: String, id: String) = synchronized(lock) {
        val all = read()
        val a = all.optJSONArray(uri) ?: return@synchronized
        val rest = (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Lecture::from) }.filter { it.id != id }
        if (rest.isEmpty()) all.remove(uri) else all.put(uri, JSONArray(rest.map { it.json() }))
        write(all)
        if (validId(id)) File(dir, "$id.m4a").delete()
    }

    private fun read(): JSONObject = runCatching { JSONObject(index.readText()) }.getOrElse { JSONObject() }

    private fun write(o: JSONObject) {
        dir.mkdirs()
        val tmp = File(dir, "index.json.tmp")
        tmp.writeText(o.toString())
        if (!tmp.renameTo(index)) { index.writeText(o.toString()); tmp.delete() }
    }

    companion object {
        /** Where playback starts for a comment: a little before it was written, to hear the lead-in. */
        const val LEAD_IN_MS = 3_000L

        fun validId(id: String) = Regex("lecture-\\d{1,19}").matches(id)

        /** The recording and position to play for [comment] (its id), the newest recording first. */
        fun seekFor(lectures: List<Lecture>, comment: String): Pair<Lecture, Long>? {
            for (l in lectures.sortedByDescending { it.started }) {
                val m = l.marks.firstOrNull { it.comment == comment } ?: continue
                return l to (m.atMs - LEAD_IN_MS).coerceIn(0, maxOf(0, l.durationMs))
            }
            return null
        }

        /** The comments of [l] written by [atMs] into the recording (for showing what was there then). */
        fun writtenBy(l: Lecture, atMs: Long): Set<String> = l.marks.filter { it.atMs <= atMs }.mapTo(HashSet()) { it.comment }
    }
}

/** Time spent recording, not counting pauses. */
class RecordingClock(private val now: () -> Long) {
    private var runningSince: Long? = null
    private var banked = 0L

    val running: Boolean get() = runningSince != null

    fun start() { if (runningSince == null) runningSince = now() }
    fun pause() { runningSince?.let { banked += now() - it }; runningSince = null }
    fun elapsed(): Long = banked + (runningSince?.let { now() - it } ?: 0L)
}
