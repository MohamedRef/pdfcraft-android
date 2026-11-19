package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.ui.geometry.Offset
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.zip.CRC32

/*
 * A journal of the Draw strokes a document's file does not have yet (#680): drafts, strokes being
 * saved, and strokes the engine has but has not saved. Without it they are only in memory until the
 * next save, so a crash, a killed app or a phone switched off while writing lost them all.
 *
 * The file is append-only: a header (magic, version, the document's page fingerprint), then
 * records of [type][payload length][CRC32 of the payload][payload]. A record keeps the first k
 * strokes ([REC_KEEP]) or adds one ([REC_ADD]); replaying them in order gives the strokes. A
 * [REC_TOUCHED] record says an edit changed some of them in a way the journal did not follow
 * (#703), so a restore can say that changes may be missing. Each
 * write is flushed to the disk, so only the gesture being written when the app died can be lost,
 * and a torn or damaged record ends the replay there (the records before it stand).
 */

private const val JOURNAL_MAGIC = 0x50434A4C // "PCJL"
private const val JOURNAL_VERSION = 1
private const val REC_KEEP: Byte = 1
private const val REC_ADD: Byte = 2
private const val REC_TOUCHED: Byte = 3

/** How long after a gesture ends the journal is written: a burst of edits is written once. */
internal const val INK_JOURNAL_SYNC_MS = 250L

/** Bounds a damaged or hostile file cannot get past while it is read back. */
internal const val INK_JOURNAL_MAX_STROKES = 100_000
private const val MAX_POINTS = 1_000_000
private const val MAX_RECORD_BYTES = 64 * 1024 * 1024

/** The pages a journal's strokes were drawn on: their count and sizes, as a short hash. */
internal fun inkJournalFingerprint(pages: List<PageSize>): String {
    val text = buildString {
        append(pages.size)
        for (p in pages) append(';').append(Math.round(p.width * 10f)).append('x').append(Math.round(p.height * 10f))
    }
    return sha256Hex(text).take(32)
}

/** The journal's file name for the document opened from [source] (a URI or a path; never stored as such). */
internal fun inkJournalName(source: String): String = sha256Hex(source).take(40) + ".pcjournal"

private fun sha256Hex(text: String): String =
    MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

/** One stroke as the journal stores it. */
internal fun encodeInkJournalStroke(s: InkSaveStroke): ByteArray {
    val bytes = ByteArrayOutputStream(64 + s.points.size * 12 + s.densities.size * 4)
    DataOutputStream(bytes).use { out ->
        out.writeInt(s.page)
        out.writeInt(s.appearance.argb)
        out.writeFloat(s.appearance.opacity)
        out.writeFloat(s.appearance.width)
        out.writeBoolean(s.appearance.author != null)
        s.appearance.author?.let { out.writeUTF(it.take(512)) }
        out.writeUTF(s.appearance.style.key)
        out.writeBoolean(s.appearance.onDark)
        out.writeBoolean(s.pressureSensitive)
        out.writeInt(s.points.size)
        for (p in s.points) {
            out.writeFloat(p.x)
            out.writeFloat(p.y)
        }
        out.writeInt(s.scales.size)
        for (v in s.scales) out.writeFloat(v)
        out.writeInt(s.densities.size)
        for (v in s.densities) out.writeFloat(v)
    }
    return bytes.toByteArray()
}

/** The stroke [encodeInkJournalStroke] wrote; throws on a damaged payload. */
internal fun decodeInkJournalStroke(bytes: ByteArray): InkSaveStroke {
    val input = DataInputStream(bytes.inputStream())
    fun count(): Int = input.readInt().also { require(it in 0..MAX_POINTS) { "bad count $it" } }
    val page = input.readInt()
    require(page >= 0) { "bad page $page" }
    val argb = input.readInt()
    val opacity = input.readFloat()
    val width = input.readFloat()
    val author = if (input.readBoolean()) input.readUTF() else null
    val style = InkStyle.fromKey(input.readUTF()) ?: InkStyle.Ballpoint
    val onDark = input.readBoolean()
    val varies = input.readBoolean()
    val points = List(count()) { Offset(input.readFloat(), input.readFloat()) }
    val scales = List(count()) { input.readFloat() }
    val densities = List(count()) { input.readFloat() }
    require(points.isNotEmpty() && scales.size == points.size) { "bad stroke" }
    return InkSaveStroke(page, InkAppearance(argb, opacity, width, author, style, onDark), points, scales, varies, densities)
}

/** What reading a journal found. */
internal data class InkJournalRead(
    /** The strokes, in paint order. */
    val strokes: List<InkSaveStroke>,
    /** `none` (no file), `read`, `stale` (another document's pages), `damaged` (no usable header). */
    val outcome: String,
    /** Records that were read, and whether the file ended in a torn or damaged one. */
    val records: Int = 0,
    val torn: Boolean = false,
    val bytes: Long = 0L,
    /** An edit changed some of the strokes afterwards in a way the journal did not follow (#703). */
    val touched: Boolean = false,
)

/**
 * The journal's file: [rewrite] starts it over with a set of strokes, [append] adds records to it,
 * [read] replays it. Each write is flushed to the disk before it returns. Not thread-safe: one
 * writer at a time ([InkJournal] keeps to one).
 */
internal class InkJournalFile(val file: File, private val fingerprint: String) {
    /** Writes this many bytes in all, for the log. */
    @Volatile var bytesWritten = 0L
        private set

    fun read(): InkJournalRead {
        if (!file.isFile) return InkJournalRead(emptyList(), "none")
        val size = file.length()
        val strokes = ArrayList<InkSaveStroke>()
        var records = 0
        var torn = false
        var touched = false
        try {
            DataInputStream(file.inputStream().buffered()).use { input ->
                val header = try {
                    Triple(input.readInt(), input.readInt(), input.readUTF())
                } catch (_: Exception) {
                    return InkJournalRead(emptyList(), "damaged", bytes = size)
                }
                if (header.first != JOURNAL_MAGIC || header.second != JOURNAL_VERSION) return InkJournalRead(emptyList(), "damaged", bytes = size)
                if (header.third != fingerprint) return InkJournalRead(emptyList(), "stale", bytes = size)
                while (true) {
                    val type = try { input.readByte() } catch (_: EOFException) { break }
                    try {
                        val length = input.readInt()
                        val crc = input.readInt()
                        require(length in 0..MAX_RECORD_BYTES)
                        val payload = ByteArray(length)
                        input.readFully(payload)
                        require(crcOf(payload) == crc)
                        when (type) {
                            REC_KEEP -> {
                                val keep = DataInputStream(payload.inputStream()).readInt()
                                require(keep >= 0)
                                while (strokes.size > keep) strokes.removeAt(strokes.lastIndex)
                            }
                            REC_ADD -> {
                                require(strokes.size < INK_JOURNAL_MAX_STROKES)
                                strokes.add(decodeInkJournalStroke(payload))
                            }
                            REC_TOUCHED -> touched = true
                            else -> error("unknown record $type")
                        }
                        records++
                    } catch (_: Exception) {
                        // a record cut short by the app dying while it was written, or damaged
                        torn = true
                        break
                    }
                }
            }
        } catch (_: Exception) {
            return InkJournalRead(emptyList(), "damaged", bytes = size)
        }
        return InkJournalRead(strokes, "read", records, torn, size, touched)
    }

    /** The file holds the header and [strokes] only, marked [touched] (written to a temporary file, then renamed over it). */
    fun rewrite(strokes: List<ByteArray>, touched: Boolean = false) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            val data = DataOutputStream(out.buffered())
            data.writeInt(JOURNAL_MAGIC)
            data.writeInt(JOURNAL_VERSION)
            data.writeUTF(fingerprint)
            if (touched) record(data, REC_TOUCHED, ByteArray(0))
            for (s in strokes) record(data, REC_ADD, s)
            data.flush()
            out.fd.sync()
        }
        bytesWritten += tmp.length()
        if (!tmp.renameTo(file)) {
            file.delete()
            check(tmp.renameTo(file)) { "the journal could not be replaced" }
        }
    }

    /** Mark the strokes [touched] if asked, keep the first [keep] of them, then add [added]. */
    fun append(keep: Int?, added: List<ByteArray>, touched: Boolean = false) {
        if (!file.isFile) return rewrite(added, touched)
        FileOutputStream(file, true).use { out ->
            val data = DataOutputStream(out.buffered())
            if (touched) record(data, REC_TOUCHED, ByteArray(0))
            if (keep != null) record(data, REC_KEEP, ByteArrayOutputStream(4).also { DataOutputStream(it).writeInt(keep) }.toByteArray())
            for (s in added) record(data, REC_ADD, s)
            data.flush()
            out.fd.sync()
            bytesWritten += data.size()
        }
    }

    fun delete() {
        file.delete()
        File(file.parentFile, file.name + ".tmp").delete()
    }

    private fun record(out: DataOutputStream, type: Byte, payload: ByteArray) {
        out.writeByte(type.toInt())
        out.writeInt(payload.size)
        out.writeInt(crcOf(payload))
        out.write(payload)
    }

    private fun crcOf(payload: ByteArray): Int = CRC32().apply { update(payload) }.value.toInt()
}

/** One stroke the file does not have yet, as [InkJournal.sync] compares them: its identity and shape. */
internal class InkJournalItem(
    /** The stroke itself ([InkStroke]), or a token for one restored from the journal. */
    val key: Any,
    /** What changes when the stroke is changed in place (points moved, a taper added). */
    val shape: List<Any>,
    /** Its data, read when it has to be written. */
    val data: () -> InkSaveStroke,
)

/** What changes when [stroke] is changed in place, read without copying its points. */
internal fun inkStrokeShape(stroke: InkStroke): List<Any> = listOf(
    stroke.points.size, stroke.points.firstOrNull() ?: Offset.Zero, stroke.points.lastOrNull() ?: Offset.Zero,
    stroke.scales.size, stroke.densities.size, stroke.widthVersion,
)

/** [stroke] as a journal item: a copy of its data, taken now (the stroke can change afterwards). */
internal fun inkJournalItem(stroke: InkStroke, shape: List<Any> = inkStrokeShape(stroke)): InkJournalItem {
    val data = InkSaveStroke(stroke.page, stroke.appearance, stroke.points.toList(), stroke.scales.toList(), stroke.pressureSensitive, stroke.densities.toList())
    return InkJournalItem(stroke, shape) { data }
}

/**
 * The journal of one open document (#680). [sync] is given every stroke the file does not have yet,
 * in order, and writes what changed since the last time: a kept prefix and the strokes after it.
 * The writing runs through [run] (one at a time, in order: a single background thread in the app,
 * at once in tests).
 */
internal class InkJournal(private val file: InkJournalFile, private val run: (() -> Unit) -> Unit) {
    /** What the file holds now, as far as [sync] knows. */
    private var written: List<InkJournalItem> = emptyList()
    private var writtenTouched = false

    /** Writes made, the longest one and failed ones, for the log. */
    @Volatile var writes = 0
        private set
    @Volatile var slowestWriteMs = 0L
        private set
    @Volatile var failures = 0
        private set
    /** A write failed (the disk full, say): the next one starts the file over. */
    @Volatile private var failed = false

    /** The strokes the file already held when the document opened ([read]): they become [written]. */
    fun adopt(items: List<InkJournalItem>, touched: Boolean = false) {
        written = items
        writtenTouched = touched
    }

    fun read(): InkJournalRead = file.read()

    /**
     * The strokes the file should hold, in order, and whether they are [touched] (#703). Strokes that
     * are the same objects with the same shape as the start of what was written stay; the rest is
     * written after a "keep" record.
     */
    fun sync(items: List<InkJournalItem>, touched: Boolean = false) {
        var same = 0
        if (!failed) while (same < items.size && same < written.size && items[same].key === written[same].key && items[same].shape == written[same].shape) same++
        val mark = touched && items.isNotEmpty()
        if (!failed && same == items.size && same == written.size && mark == writtenTouched) return
        val keep = if (same < written.size) same else null
        // a mark is only ever added to the file: taking it back starts the file over
        val unmark = writtenTouched && !mark
        val newMark = mark && !writtenTouched
        val all = items.map { it.data }
        written = items.toList()
        writtenTouched = mark
        failed = false
        run {
            val started = System.nanoTime()
            try {
                when {
                    all.isEmpty() -> file.delete()
                    // the file is gone or was never written: all of it, not only what is new
                    keep == 0 || unmark || !file.file.isFile -> file.rewrite(all.map { encodeInkJournalStroke(it()) }, mark)
                    else -> file.append(keep, all.subList(same, all.size).map { encodeInkJournalStroke(it()) }, newMark)
                }
                writes++
            } catch (_: Exception) {
                // never take the app down for its journal; start over at the next write
                failures++
                failed = true
            }
            slowestWriteMs = maxOf(slowestWriteMs, (System.nanoTime() - started) / 1_000_000)
        }
    }

    /** Nothing is unsaved any more, or the changes were discarded: the file goes. */
    fun delete() {
        written = emptyList()
        writtenTouched = false
        run { runCatching { file.delete() } }
    }

    val bytesWritten: Long get() = file.bytesWritten
}

/**
 * The journal's side of one open document (#680): which strokes the engine has taken and the file
 * not yet, and which to write. Every stroke the file does not have yet is journaled, in paint
 * order: those the engine took ([committed]), those being handed to it ([InkDrafts.saving]) and
 * the drafts ([InkDrafts.strokes]). Main thread only.
 */
internal class DocInkJournal(val journal: InkJournal, private val drafts: InkDrafts) {
    private val committed = ArrayList<InkJournalItem>()
    private val items = java.util.IdentityHashMap<InkStroke, InkJournalItem>()

    /** While the strokes found at open are being added back nothing is written: the file still holds them. */
    var restoring = true

    /**
     * An edit changed strokes the engine took from the journal in a way it could not follow (an
     * eraser cut, Undo or Redo, an edit of part of a stroke): a restore says changes may be missing
     * (#703). Cleared once a save has written every such stroke.
     */
    var touched = false
        private set

    /** Strokes the file does not have yet. */
    val unsaved: Int get() = committed.size + drafts.saving.size + drafts.strokes.size

    private fun item(s: InkStroke): InkJournalItem {
        val shape = inkStrokeShape(s)
        items[s]?.takeIf { it.shape == shape }?.let { return it }
        return inkJournalItem(s, shape).also { items[s] = it }
    }

    /** Write what changed (each gesture's end, a save, a restore). */
    fun sync() {
        if (restoring) return
        if (committed.isEmpty()) touched = false
        val live = committed + drafts.saving.map(::item) + drafts.strokes.map(::item)
        // forget the copies of strokes that are gone
        if (items.size > live.size) items.keys.retainAll(live.mapNotNull { it.key as? InkStroke }.toSet())
        journal.sync(live, touched)
    }

    /** The engine took these drafts; they stay journaled until a save writes them. */
    fun committed(strokes: List<InkStroke>) {
        committed.addAll(strokes.map(::item))
        sync()
    }

    /** Its gesture watcher, stopped when the document closes. */
    var watcher: kotlinx.coroutines.Job? = null

    /** The strokes read back at open, now being added back as ink: journaled as the engine's, still [touched] if they were. */
    fun adoptRestored(restored: List<InkJournalItem>, touched: Boolean = false) {
        journal.adopt(restored, touched)
        committed.addAll(restored)
        this.touched = touched
    }

    /**
     * The engine moved, turned, scaled, recoloured or deleted the saved annotations at [rects] on
     * [page] (#703). A stroke it took from the journal and saved as annotations that are all among
     * them is changed the same way here ([change]; null deletes it); one that only overlaps them
     * marks the journal [touched]. Returns the strokes changed.
     */
    fun savedInkChanged(page: Int, rects: List<List<Float>>, change: (InkSaveStroke) -> InkSaveStroke?): Int {
        if (committed.isEmpty() || rects.isEmpty()) return 0
        var changed = 0
        val each = committed.listIterator()
        while (each.hasNext()) {
            val s = each.next().data()
            if (s.page != page) continue
            when (inkJournalMatch(s, rects)) {
                InkJournalMatch.All -> {
                    changed++
                    val next = change(s)
                    if (next == null) each.remove() else each.set(InkJournalItem(Any(), listOf("changed")) { next })
                }
                InkJournalMatch.Some -> touched = true
                InkJournalMatch.None -> Unit
            }
        }
        sync()
        return changed
    }

    /** The engine changed ink on [page] within [area] (page points) in a way the journal cannot follow, such as an eraser cut (#703). */
    fun savedInkTouched(page: Int, area: androidx.compose.ui.geometry.Rect) {
        if (committed.none { it.data().let { s -> s.page == page && inkJournalArea(s)?.overlaps(area) == true } }) return
        touched = true
        sync()
    }

    /** An Undo or Redo of the document while it has strokes from the journal: what it changed is not followed (#703). */
    fun documentStepped() {
        if (committed.isEmpty()) return
        touched = true
        sync()
    }

    /** Of the strokes read back, these the engine refused: they become drafts instead. */
    fun refused(restored: List<InkJournalItem>) {
        val gone = restored.map { it.key }.toSet()
        committed.removeAll { it.key in gone }
    }

    /** A save starts: the strokes the engine has now are in the file once it succeeds ([saved]). */
    fun saveStarted(): Int = committed.size

    fun saved(mark: Int) {
        repeat(mark.coerceAtMost(committed.size)) { committed.removeAt(0) }
        sync()
    }

    /** The document closes: the file goes unless something is still unsaved (then it is restored next time). */
    fun closed(discarded: Boolean) {
        watcher?.cancel()
        watcher = null
        if (discarded || unsaved == 0) {
            committed.clear()
            touched = false
            journal.delete()
        } else if (!restoring) {
            // the last gesture's write may still have been waiting
            sync()
        }
        // nothing more is written for this visit
        restoring = true
    }
}

/** How the annotations a journaled stroke was saved as compare with those an edit changed (#703). */
internal enum class InkJournalMatch { All, Some, None }

/**
 * The rectangles [x0, y0, x1, y1] (page points) of the annotations [s] is saved as ([inkSaveCommands]),
 * as the engine gives them: the points' bounds grown by half the widest width and a point. Each comes
 * with the slack a match allows, since the engine rounds and widens them a little differently.
 */
internal fun inkJournalRects(s: InkSaveStroke): List<Pair<List<Float>, Float>> =
    inkSaveCommands(listOf(s)) { "" }.mapNotNull { command ->
        @Suppress("UNCHECKED_CAST")
        val lines = command["strokes"] as? List<List<List<Double>>> ?: return@mapNotNull null
        val points = lines.flatten().filter { it.size == 2 }
        if (points.isEmpty()) return@mapNotNull null
        @Suppress("UNCHECKED_CAST")
        val widths = (command["widths"] as? List<List<Double>>)?.flatten()
        val widest = (widths?.maxOrNull() ?: (command["width"] as? Double) ?: 1.0).toFloat()
        val grow = widest / 2f + 1f
        val rect = listOf(
            points.minOf { it[0] }.toFloat() - grow, points.minOf { it[1] }.toFloat() - grow,
            points.maxOf { it[0] }.toFloat() + grow, points.maxOf { it[1] }.toFloat() + grow,
        )
        rect to (2f + widest / 2f)
    }

/** Where [s] is drawn, grown by its width (page points); null for a stroke with no points. */
internal fun inkJournalArea(s: InkSaveStroke): androidx.compose.ui.geometry.Rect? {
    val rects = inkJournalRects(s).map { it.first }
    if (rects.isEmpty()) return null
    return androidx.compose.ui.geometry.Rect(rects.minOf { it[0] }, rects.minOf { it[1] }, rects.maxOf { it[2] }, rects.maxOf { it[3] })
}

/**
 * Whether [s] is among the annotations at [rects] (page points, as the engine reports them, either
 * corner first): [InkJournalMatch.All] when each annotation it is saved as has its rectangle there,
 * [InkJournalMatch.None] when it overlaps none of them, [InkJournalMatch.Some] otherwise.
 */
internal fun inkJournalMatch(s: InkSaveStroke, rects: List<List<Float>>): InkJournalMatch {
    val own = inkJournalRects(s)
    if (own.isEmpty()) return InkJournalMatch.None
    val theirs = rects.filter { it.size == 4 && it.all(Float::isFinite) }.map { r ->
        listOf(minOf(r[0], r[2]), minOf(r[1], r[3]), maxOf(r[0], r[2]), maxOf(r[1], r[3]))
    }
    if (own.all { (mine, slack) -> theirs.any { r -> (0..3).all { kotlin.math.abs(r[it] - mine[it]) <= slack } } }) return InkJournalMatch.All
    val area = inkJournalArea(s) ?: return InkJournalMatch.None
    val touches = theirs.any { r -> r[0] <= area.right && r[2] >= area.left && r[1] <= area.bottom && r[3] >= area.top }
    return if (touches) InkJournalMatch.Some else InkJournalMatch.None
}

/** [s] as the lasso's resize and turn handles leave it (#689, #703): as [lassoTransformedDraft] leaves a draft. */
internal fun lassoTransformedSaved(s: InkSaveStroke, pivot: Offset, scale: Float, angleDeg: Float): InkSaveStroke =
    s.copy(
        appearance = s.appearance.copy(width = (s.appearance.width * scale).coerceIn(0.1f, 30f)),
        points = s.points.map { lassoTransformPoint(it, pivot, scale, angleDeg) },
    )
