package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/** What was studied in one document. */
data class DocStudy(
    val uri: String,
    val name: String,
    /** Active reading time, ms. */
    val activeMs: Long = 0,
    /** Pages seen (0-based), kept as a sorted set. */
    val pagesSeen: Set<Int> = emptySet(),
    val pageCount: Int = 0,
    val annotations: Int = 0,
    val lastPage: Int = 0,
    val lastStudied: Long = 0,
    /** Active ms per local day (epoch day), the last [StudyStats.DAYS_KEPT] days. */
    val days: Map<Long, Long> = emptyMap(),
) {
    /** Share of the pages seen, 0 to 1. */
    val progress: Float get() = if (pageCount <= 0) 0f else (pagesSeen.size.toFloat() / pageCount).coerceIn(0f, 1f)

    fun toJson(): JSONObject = JSONObject()
        .put("uri", uri).put("name", name).put("ms", activeMs)
        .put("seen", JSONArray(pagesSeen.sorted())).put("pages", pageCount)
        .put("notes", annotations).put("last", lastPage).put("at", lastStudied)
        .put("days", JSONObject().apply { days.forEach { (d, ms) -> put(d.toString(), ms) } })

    companion object {
        fun fromJson(o: JSONObject): DocStudy? {
            val uri = o.optString("uri").ifEmpty { return null }
            val seen = o.optJSONArray("seen")?.let { a -> (0 until a.length()).map { a.optInt(it) }.filter { it >= 0 }.toSortedSet() } ?: sortedSetOf()
            val days = HashMap<Long, Long>()
            o.optJSONObject("days")?.let { d -> d.keys().forEach { k -> k.toLongOrNull()?.let { days[it] = d.optLong(k).coerceAtLeast(0) } } }
            return DocStudy(uri, o.optString("name"), o.optLong("ms").coerceAtLeast(0), seen, o.optInt("pages"), o.optInt("notes"), o.optInt("last"), o.optLong("at"), days)
        }
    }
}

/**
 * Time-in-document tracking: counts reading time only while the viewer is in front and the
 * reader did something in the last [IDLE_MS] (a touch, a scroll, a page turn), so a phone left
 * open on a page does not count. Pure bookkeeping: the clock and the "something happened" calls
 * come from the viewer.
 */
class StudyClock(private val idleMs: Long = IDLE_MS) {
    private var lastActivity = -1L
    private var counted = 0L

    /** The reader did something at [now] (ms, monotonic). Returns the active ms to add. */
    fun activity(now: Long): Long {
        val add = if (lastActivity >= 0) (now - lastActivity).coerceIn(0, idleMs) else 0
        lastActivity = now
        counted += add
        return add
    }

    /** The viewer left the front at [now]: count up to then (bounded by the idle limit) and stop. */
    fun pause(now: Long): Long {
        val add = if (lastActivity >= 0) (now - lastActivity).coerceIn(0, idleMs) else 0
        lastActivity = -1
        counted += add
        return add
    }

    val total: Long get() = counted

    companion object { const val IDLE_MS = 2 * 60_000L }
}

/** The study statistics of every document, in a JSON file in the app's private files. */
class StudyStats(private val file: File, private val zone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private val lock = Any()

    fun all(): List<DocStudy> = synchronized(lock) { read().values.sortedByDescending { it.lastStudied } }

    fun get(uri: String): DocStudy? = synchronized(lock) { read()[uri] }

    /** Add [ms] of active time, the [pages] seen (0-based) and [annotations] made to [uri]'s record at wall-clock [at]. */
    fun record(uri: String, name: String, pageCount: Int, pages: Set<Int>, lastPage: Int, ms: Long, at: Long, annotations: Int = 0) = synchronized(lock) {
        val all = read()
        val old = all[uri] ?: DocStudy(uri, name)
        val day = Instant.ofEpochMilli(at).atZone(zone()).toLocalDate().toEpochDay()
        val days = HashMap(old.days)
        if (ms > 0) days[day] = (days[day] ?: 0) + ms
        // keep the recent days only
        days.keys.removeAll { it < day - DAYS_KEPT }
        val seen = (old.pagesSeen + pages.filter { it in 0 until maxOf(pageCount, 1) }).filter { it < maxOf(pageCount, 1) }.toSortedSet()
        all[uri] = old.copy(
            name = name.ifEmpty { old.name }, activeMs = old.activeMs + ms.coerceAtLeast(0), pagesSeen = seen,
            pageCount = pageCount, annotations = old.annotations + annotations.coerceAtLeast(0),
            lastPage = lastPage.coerceAtLeast(0), lastStudied = at, days = days,
        )
        // bounded: the documents studied most recently
        val kept = all.values.sortedByDescending { it.lastStudied }.take(MAX_DOCS).associateBy { it.uri }
        write(kept)
    }

    /** Active ms per day of the 7 days ending [today], oldest first. */
    fun week(today: LocalDate, uris: Set<String>? = null): List<Long> = synchronized(lock) {
        val docs = read().values.filter { uris == null || it.uri in uris }
        (6 downTo 0).map { back -> val d = today.minusDays(back.toLong()).toEpochDay(); docs.sumOf { it.days[d] ?: 0 } }
    }

    fun clear() = synchronized(lock) { file.delete() }

    fun remove(uri: String) = synchronized(lock) { write(read().apply { remove(uri) }) }

    private fun read(): MutableMap<String, DocStudy> {
        val arr = runCatching { JSONArray(file.readText()) }.getOrElse { return LinkedHashMap() }
        val out = LinkedHashMap<String, DocStudy>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let(DocStudy::fromJson)?.let { out[it.uri] = it }
        return out
    }

    private fun write(all: Map<String, DocStudy>) {
        val text = JSONArray().apply { all.values.forEach { put(it.toJson()) } }.toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }

    companion object {
        const val MAX_DOCS = 200
        const val DAYS_KEPT = 56L
    }
}

/** A focus timer: work then break, repeating. All times are wall-clock ms so it survives the process. */
data class FocusTimer(
    val workMs: Long = 25 * 60_000L,
    val breakMs: Long = 5 * 60_000L,
    /** When the current phase ends; 0: not running. */
    val endsAt: Long = 0,
    val onBreak: Boolean = false,
    /** Remaining ms while paused, else -1. */
    val pausedLeft: Long = -1,
) {
    val running: Boolean get() = endsAt > 0 && pausedLeft < 0
    val active: Boolean get() = endsAt > 0 || pausedLeft >= 0

    fun start(now: Long) = copy(endsAt = now + workMs, onBreak = false, pausedLeft = -1)
    fun left(now: Long): Long = if (pausedLeft >= 0) pausedLeft else (endsAt - now).coerceAtLeast(0)
    fun pause(now: Long) = if (running) copy(pausedLeft = left(now)) else this
    fun resume(now: Long) = if (pausedLeft >= 0) copy(endsAt = now + pausedLeft, pausedLeft = -1) else this
    fun stop() = FocusTimer(workMs, breakMs)

    /** At [now], move past a phase that ended (work to break, break to work). */
    fun tick(now: Long): FocusTimer {
        if (!running || now < endsAt) return this
        var t = this
        while (t.endsAt <= now) t = t.copy(onBreak = !t.onBreak, endsAt = t.endsAt + if (t.onBreak) workMs else breakMs)
        return t
    }

    fun toJson(): JSONObject = JSONObject().put("work", workMs).put("break", breakMs).put("ends", endsAt).put("onBreak", onBreak).put("paused", pausedLeft)

    companion object {
        fun fromJson(o: JSONObject?): FocusTimer = o?.let {
            FocusTimer(it.optLong("work", 25 * 60_000L).coerceIn(60_000L, 180 * 60_000L), it.optLong("break", 5 * 60_000L).coerceIn(60_000L, 60 * 60_000L), it.optLong("ends"), it.optBoolean("onBreak"), it.optLong("paused", -1))
        } ?: FocusTimer()
    }
}
