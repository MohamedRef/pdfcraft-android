package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import org.json.JSONArray
import org.json.JSONObject

/** How well a card was remembered. */
enum class Rating(val grade: Int) { Again(1), Hard(2), Good(3), Easy(4) }

/** A card's memory state: FSRS stability (days) and difficulty (1 to 10). */
data class Memory(val stability: Double, val difficulty: Double)

/**
 * The FSRS-4.5 scheduler (the open Free Spaced Repetition Scheduler; its published default
 * parameters): from a card's memory and the days since its last review, the next memory and
 * interval. Only the formulas; no data, no clock.
 */
class Fsrs(
    private val w: DoubleArray = DEFAULT_WEIGHTS,
    /** The chance of remembering a card when it comes due. */
    val retention: Double = 0.9,
    val maxIntervalDays: Long = 36_500,
) {
    init { require(w.size == 17) { "FSRS-4.5 has 17 parameters" } }

    fun retrievability(elapsedDays: Double, stability: Double): Double = (1 + FACTOR * elapsedDays / stability).pow(DECAY)

    /** Days until the chance of remembering falls to [retention]. */
    fun interval(stability: Double): Long {
        val days = stability / FACTOR * (retention.pow(1 / DECAY) - 1)
        return days.roundToLong().coerceIn(1, maxIntervalDays)
    }

    private fun initDifficulty(r: Rating) = (w[4] - (r.grade - 3) * w[5]).coerceIn(1.0, 10.0)

    fun first(r: Rating): Memory = Memory(max(w[r.grade - 1], 0.1), initDifficulty(r))

    fun next(m: Memory, elapsedDays: Double, r: Rating): Memory {
        val ret = retrievability(max(elapsedDays, 0.0), m.stability)
        val d0 = initDifficulty(Rating.Good)
        val d = (w[7] * d0 + (1 - w[7]) * (m.difficulty - w[6] * (r.grade - 3))).coerceIn(1.0, 10.0)
        val s = if (r == Rating.Again) {
            val forget = w[11] * m.difficulty.pow(-w[12]) * ((m.stability + 1).pow(w[13]) - 1) * exp(w[14] * (1 - ret))
            min(forget, m.stability)
        } else {
            val hard = if (r == Rating.Hard) w[15] else 1.0
            val easy = if (r == Rating.Easy) w[16] else 1.0
            m.stability * (1 + exp(w[8]) * (11 - m.difficulty) * m.stability.pow(-w[9]) * (exp(w[10] * (1 - ret)) - 1) * hard * easy)
        }
        return Memory(s.coerceAtLeast(0.1), d)
    }

    companion object {
        const val DECAY = -0.5
        const val FACTOR = 19.0 / 81.0
        val DEFAULT_WEIGHTS = doubleArrayOf(
            0.4872, 1.4003, 3.7145, 13.8206, 5.1618, 1.2298, 0.8975, 0.031, 1.6474,
            0.1367, 1.0461, 2.1072, 0.0793, 0.3246, 1.587, 0.2272, 2.8755,
        )
    }
}

/** A flashcard, linked back to the page it came from. */
data class Flashcard(
    val id: String,
    /** The deck: the document's URI. */
    val deck: String,
    val deckName: String,
    val front: String,
    val back: String,
    /** 0-based page it came from, -1: none. */
    val page: Int = -1,
    /** A picture for the front (a file in the app's private cards folder), if any. */
    val image: String? = null,
    val created: Long = 0,
    /** When it is next due (ms); 0: new. */
    val due: Long = 0,
    val memory: Memory? = null,
    val lastReview: Long = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
) {
    val isNew: Boolean get() = memory == null

    /** A cloze card's front with each {{answer}} blanked, and its back with them shown. */
    val clozeFront: String get() = CLOZE.replace(front) { "[…]" }
    val clozeBack: String get() = CLOZE.replace(front) { it.groupValues[1] }
    val isCloze: Boolean get() = CLOZE.containsMatchIn(front)

    fun toJson(): JSONObject = JSONObject().put("id", id).put("deck", deck).put("deckName", deckName).put("front", front).put("back", back)
        .put("page", page).put("image", image ?: JSONObject.NULL).put("created", created).put("due", due)
        .put("s", memory?.stability ?: JSONObject.NULL).put("d", memory?.difficulty ?: JSONObject.NULL)
        .put("last", lastReview).put("reps", reps).put("lapses", lapses)

    companion object {
        val CLOZE = Regex("""\{\{(.+?)\}\}""")

        fun fromJson(o: JSONObject): Flashcard? {
            val id = o.optString("id").ifEmpty { return null }
            val memory = if (o.isNull("s") || o.isNull("d")) null else Memory(o.optDouble("s", 1.0).coerceAtLeast(0.1), o.optDouble("d", 5.0).coerceIn(1.0, 10.0))
            return Flashcard(
                id, o.optString("deck"), o.optString("deckName"), o.optString("front"), o.optString("back"), o.optInt("page", -1),
                o.optString("image").takeIf { !o.isNull("image") && it.isNotEmpty() }, o.optLong("created"), o.optLong("due"), memory,
                o.optLong("last"), o.optInt("reps"), o.optInt("lapses"),
            )
        }
    }
}

/** Review [card] at [now] with [rating]: its next state. Again keeps it due now, to see again in the session. */
fun Fsrs.review(card: Flashcard, rating: Rating, now: Long): Flashcard {
    val elapsed = if (card.lastReview > 0) (now - card.lastReview) / DAY_MS.toDouble() else 0.0
    val m = card.memory?.let { next(it, elapsed, rating) } ?: first(rating)
    val due = if (rating == Rating.Again) now + 60_000 else now + interval(m.stability) * DAY_MS
    return card.copy(memory = m, due = due, lastReview = now, reps = card.reps + 1, lapses = card.lapses + if (rating == Rating.Again && !card.isNew) 1 else 0)
}

const val DAY_MS = 86_400_000L

/** All flashcards, in one JSON file in the app's private files. */
class Flashcards(private val file: File) {
    private val lock = Any()

    fun all(): List<Flashcard> = synchronized(lock) { read() }

    fun deck(uri: String): List<Flashcard> = all().filter { it.deck == uri }

    /** Cards due at [now] (new cards count as due), oldest due first. */
    fun due(now: Long, deck: String? = null): List<Flashcard> = all().filter { (deck == null || it.deck == deck) && it.due <= now }.sortedBy { it.due }

    fun dueCounts(now: Long): Map<String, Int> = all().filter { it.due <= now }.groupingBy { it.deck }.eachCount()

    fun put(card: Flashcard) = synchronized(lock) { write(read().filter { it.id != card.id } + card) }

    fun putAll(cards: List<Flashcard>) = synchronized(lock) {
        val ids = cards.map { it.id }.toSet()
        write(read().filter { it.id !in ids } + cards)
    }

    fun delete(id: String) = synchronized(lock) { write(read().filter { it.id != id }) }

    fun clear() = synchronized(lock) { file.delete() }

    private fun read(): List<Flashcard> {
        val arr = runCatching { JSONArray(file.readText()) }.getOrElse { return emptyList() }
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it)?.let(Flashcard::fromJson) }
    }

    private fun write(cards: List<Flashcard>) {
        val text = JSONArray().apply { cards.forEach { put(it.toJson()) } }.toString()
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(file)) { file.writeText(text); tmp.delete() }
    }

    companion object {
        /** Anki's "Notes in plain text" import: front TAB back, one card per line, HTML allowed. */
        fun toAnkiTsv(cards: List<Flashcard>, images: (Flashcard) -> String? = { null }): String {
            fun cell(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\t", " ").replace("\r", "").replace("\n", "<br>")
            val sb = StringBuilder("#separator:tab\n#html:true\n#columns:Front\tBack\tSource\n")
            for (c in cards) {
                val img = images(c)?.let { "<br><img src=\"${cell(it)}\">" } ?: ""
                val front = if (c.isCloze) cell(c.clozeFront) else cell(c.front)
                val back = if (c.isCloze) cell(c.clozeBack) + (if (c.back.isNotBlank()) "<br>" + cell(c.back) else "") else cell(c.back)
                val source = cell(c.deckName) + if (c.page >= 0) " p. ${c.page + 1}" else ""
                sb.append(front).append(img).append('\t').append(back).append('\t').append(source).append('\n')
            }
            return sb.toString()
        }

        /**
         * A fill-in card's front: [line] with [answer] marked as the blank ({{answer}}), or just
         * the blank when the line is unknown or does not contain the answer.
         */
        fun clozeOf(line: String?, answer: String): String {
            val a = answer.trim()
            val l = line?.trim()?.replace(Regex("\\s+"), " ")
            if (l.isNullOrEmpty() || a.isEmpty()) return "{{$a}}"
            val i = l.indexOf(a, ignoreCase = true)
            return if (i < 0) "{{$a}}" else l.substring(0, i) + "{{" + l.substring(i, i + a.length) + "}}" + l.substring(i + a.length)
        }

        /** Cards from CSV/TSV lines "front,back" (or tab-separated); quoted CSV cells are understood. */
        fun parseDelimited(text: String): List<Pair<String, String>> {
            val out = ArrayList<Pair<String, String>>()
            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\r')
                if (line.isBlank() || line.startsWith("#")) continue
                val cells = if ('\t' in line) line.split('\t') else splitCsv(line)
                val front = cells.getOrNull(0)?.trim().orEmpty()
                if (front.isNotEmpty()) out += front to cells.getOrNull(1)?.trim().orEmpty()
            }
            return out
        }

        private fun splitCsv(line: String): List<String> {
            val cells = ArrayList<String>()
            val sb = StringBuilder()
            var quoted = false
            var i = 0
            while (i < line.length) {
                val ch = line[i]
                when {
                    quoted && ch == '"' && line.getOrNull(i + 1) == '"' -> { sb.append('"'); i++ }
                    ch == '"' -> quoted = !quoted
                    ch == ',' && !quoted -> { cells += sb.toString(); sb.clear() }
                    else -> sb.append(ch)
                }
                i++
            }
            cells += sb.toString()
            return cells
        }
    }
}
