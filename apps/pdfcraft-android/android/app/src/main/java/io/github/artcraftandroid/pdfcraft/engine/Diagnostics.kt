package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.Executors

/**
 * The areas a diagnostics line belongs to, and the issues its evidence answers. A report groups
 * its summary by area, so a line's tag tells the reader which issue it is for.
 * See docs/ANDROID_DIAGNOSTICS_LOG.md before adding an area or a log point.
 */
internal enum class DiagArea(val tag: String, val issues: String) {
    App("app", "lifecycle, memory trims"),
    Open("open", "#211 #212 #234 #235 #317"),
    Render("render", "#99 #236"),
    Ink("ink", "#74 #75 #76 #81 #84 #85 #128 #130 #144 #148 #149 #150 #156"),
    Save("save", "#97 #101-#104"),
    Scan("scan", "#286-#307"),
    Memory("memory", "#212 #92"),
    Tool("tool", "#96 #113, slow or failed engine tools"),
    Error("error", "#114, any failure"),
    Share("share", "#205 #155"),
    Update("update", "#193"),
    Viewer("viewer", "#152 #153 #155 #157 #184 #198 #203 #204 #327"),
}

/**
 * The detailed diagnostics log that Settings → About → Diagnostics records and shares (#128, #97,
 * the large-PDF open issues and the Scan gates). Off by default; nothing is written while it is off.
 *
 * Privacy (#105 #107 #108): a line holds only a fixed event name and numbers, booleans and short
 * lowercase words (enum names, stage names). Any other string value is replaced by
 * [REDACTED], so a file name, URI, path, password, PDF text or exception message cannot reach the
 * log even by mistake. Documents are told apart by a per-process number (`doc=3`), never a name.
 */
internal object Diag {
    const val REDACTED = "<redacted>"

    /** Where lines go while recording; null when Diagnostics is off. */
    @Volatile
    var sink: DiagSink? = null

    /** Clock for line times; tests replace it. */
    @Volatile
    var now: () -> LocalDateTime = { LocalDateTime.now() }

    private var docCounter = 0

    fun enabled(): Boolean = sink != null

    /** One event: `[tag] name key=value ...`. Values that are not safe are redacted. */
    fun event(area: DiagArea, name: String, vararg fields: Pair<String, Any?>) {
        val s = sink ?: return
        s.add(diagLine(now(), area, name, fields.asList()))
    }

    /** Times [block] and logs `name ms=<duration>` plus [fields], also when it throws (`ok=false`). */
    inline fun <T> measure(area: DiagArea, name: String, vararg fields: Pair<String, Any?>, block: () -> T): T {
        if (!enabled()) return block()
        val start = System.nanoTime()
        var ok = false
        try {
            return block().also { ok = true }
        } finally {
            event(area, name, "ms" to (System.nanoTime() - start) / 1_000_000, *fields, "ok" to ok)
        }
    }

    /** A failure: only the stage and the exception's class, never its message (it can hold a path). */
    fun failure(area: DiagArea, stage: String, error: Throwable) {
        event(DiagArea.Error, "failed", "area" to area.tag, "stage" to stage, "error_class" to DiagClass(error))
    }

    /** A number for a newly opened document, so lines about two documents can be told apart. */
    @Synchronized
    fun nextDocNumber(): Int = ++docCounter
}

/** An exception's class name for a log line: code, not user data (its message is never logged). */
@JvmInline
internal value class DiagClass(val name: String) {
    constructor(error: Throwable) : this(error.javaClass.simpleName)
}

private val CLASS_NAME = Regex("[A-Za-z][A-Za-z0-9_$]{0,79}")

private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS")
private val SAFE_KEY = Regex("[a-z][a-z0-9_]{0,39}")
private val SAFE_WORD = Regex("[a-z][a-z0-9_.-]{0,39}")

/** A value as the log shows it; strings must be short lowercase words, anything else is redacted. */
internal fun diagValue(v: Any?): String = when (v) {
    null -> "-"
    is Boolean -> v.toString()
    is Int, is Long, is Short, is Byte -> v.toString()
    is Float -> if (v.isFinite()) "%.2f".format(java.util.Locale.ROOT, v) else "-"
    is Double -> if (v.isFinite()) "%.2f".format(java.util.Locale.ROOT, v) else "-"
    is Enum<*> -> v.name.lowercase().takeIf { SAFE_WORD.matches(it) } ?: Diag.REDACTED
    is String -> v.takeIf { SAFE_WORD.matches(it) } ?: Diag.REDACTED
    is DiagClass -> v.name.takeIf { CLASS_NAME.matches(it) } ?: Diag.REDACTED
    else -> Diag.REDACTED
}

/** A line's time and area tag. */
internal fun diagPrefix(at: LocalDateTime, area: DiagArea): String = "${TIME.format(at)} [${area.tag}] "

/** One line of the log. A key that is not a plain identifier is redacted like an unsafe value. */
internal fun diagLine(at: LocalDateTime, area: DiagArea, name: String, fields: List<Pair<String, Any?>>): String = buildString {
    append(diagPrefix(at, area))
    append(if (SAFE_KEY.matches(name)) name else Diag.REDACTED)
    for ((k, v) in fields) {
        append(' ').append(if (SAFE_KEY.matches(k)) k else "key").append('=').append(diagValue(v))
    }
}

/**
 * Keeps the log in two app-private files, so evidence survives Android closing the app in the
 * background (#97, #212) and a crash (#114): `diagnostics.log` and, once it passes [maxBytes],
 * the previous one as `diagnostics.1.log`. At most about twice [maxBytes] is kept. Writes happen
 * on one background thread; [flush] waits for them.
 */
internal class DiagSink(private val dir: File, private val maxBytes: Long = 768L * 1024) {
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "pdfcraft-diagnostics").apply { isDaemon = true } }
    private val current get() = File(dir, "diagnostics.log")
    private val previous get() = File(dir, "diagnostics.1.log")

    fun add(line: String) {
        runCatching { writer.execute { append(line) } }
    }

    /** Writes [line] before returning: for a crash, when the process is about to end. */
    fun addNow(line: String) {
        flush()
        synchronized(this) { append(line) }
    }

    @Synchronized
    private fun append(line: String) {
        runCatching {
            dir.mkdirs()
            val f = current
            if (f.length() >= maxBytes) {
                previous.delete()
                f.renameTo(previous)
            }
            f.appendText(line + "\n")
        }
    }

    /** Waits until every line added so far is in the files. */
    fun flush() {
        runCatching { writer.submit {}.get() }
    }

    /** The lines kept, oldest first. */
    fun lines(): List<String> {
        flush()
        synchronized(this) {
            return listOf(previous, current).filter { it.isFile }.flatMap { runCatching { it.readLines() }.getOrDefault(emptyList()) }
        }
    }

    fun clear() {
        flush()
        synchronized(this) {
            previous.delete()
            current.delete()
        }
    }
}

/** How many lines each area has, for the report's summary. */
internal fun diagCounts(lines: List<String>): Map<DiagArea, Int> =
    DiagArea.entries.associateWith { a -> lines.count { "[${a.tag}]" in it } }

/** The shared report: a header of [facts], a per-area summary with its issues, then every line. */
internal fun diagReport(facts: List<Pair<String, String>>, lines: List<String>): String = buildString {
    appendLine("PdfCraft diagnostics log")
    appendLine("Only event names, times, counts and sizes. No file names, paths or document contents.")
    for ((k, v) in facts) appendLine("$k: $v")
    appendLine()
    appendLine("Areas (lines · issues):")
    for ((area, n) in diagCounts(lines)) appendLine("  [${area.tag}] $n · ${area.issues}")
    appendLine()
    for (l in lines) appendLine(l)
}
