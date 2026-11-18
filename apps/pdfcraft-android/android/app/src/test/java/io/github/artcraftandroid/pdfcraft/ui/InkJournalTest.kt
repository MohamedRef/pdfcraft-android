package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.DocInkJournal
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkDrafts
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkJournal
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkJournalFile
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkJournalItem
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkSaveStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageSize
import io.github.artcraftandroid.pdfcraft.ui.viewer.DraftInkEdit
import io.github.artcraftandroid.pdfcraft.ui.viewer.decodeInkJournalStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.encodeInkJournalStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkJournalFingerprint
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkJournalName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile
import kotlin.random.Random

/** #680: the journal of Draw strokes the file does not have yet, and its replay after a crash. */
class InkJournalTest {
    @get:Rule val tmp = TemporaryFolder()

    private val pages = listOf(PageSize(612f, 792f), PageSize(595f, 842f))
    private val print = inkJournalFingerprint(pages)

    private fun stroke(seed: Int, n: Int = 5 + seed % 7): InkSaveStroke {
        val r = Random(seed)
        val style = InkStyle.entries[seed % InkStyle.entries.size]
        return InkSaveStroke(
            page = seed % 2,
            appearance = InkAppearance(r.nextInt(), r.nextFloat(), 0.5f + r.nextFloat() * 4f, if (seed % 3 == 0) null else "Ada $seed", style, onDark = seed % 4 == 0),
            points = List(n) { Offset(r.nextFloat() * 600f, r.nextFloat() * 800f) },
            scales = List(n) { 0.5f + r.nextFloat() },
            pressureSensitive = seed % 2 == 0,
            densities = if (style == InkStyle.Pencil) List(n) { r.nextFloat() } else emptyList(),
        )
    }

    private fun item(s: InkSaveStroke) = InkJournalItem(Any(), listOf(s.points.size)) { s }

    private fun journalFile() = InkJournalFile(tmp.root.resolve("ink_journal").resolve(inkJournalName("content://doc/1")), print)

    @Test fun aStrokeComesBackAsItWasWritten() {
        for (seed in 0 until 12) {
            val s = stroke(seed)
            assertEquals(s, decodeInkJournalStroke(encodeInkJournalStroke(s)))
        }
    }

    /** 50 edits of every kind a Draw session makes; the file replays to the strokes on screen after each. */
    @Test fun fiftyEditsReplayToTheSameStrokes() {
        val file = journalFile()
        val journal = InkJournal(file) { it() }
        val live = ArrayList<InkJournalItem>()
        val r = Random(680)
        var next = 100
        repeat(50) { step ->
            when (r.nextInt(6)) {
                // a new stroke (the common case: one record added)
                0, 1 -> live.add(item(stroke(next++)))
                // the eraser cuts a stroke in the middle into two pieces
                2 -> if (live.isNotEmpty()) {
                    val i = r.nextInt(live.size)
                    live.removeAt(i)
                    live.add(i, item(stroke(next++)))
                    live.add(i + 1, item(stroke(next++)))
                }
                // a lasso move: the same stroke, moved (a new shape)
                3 -> if (live.isNotEmpty()) {
                    val i = r.nextInt(live.size)
                    val moved = live[i].data().let { s -> s.copy(points = s.points.map { it + Offset(5f, 5f) }) }
                    live[i] = InkJournalItem(live[i].key, listOf("moved", step)) { moved }
                }
                // Undo of the last stroke
                4 -> if (live.isNotEmpty()) live.removeAt(live.lastIndex)
                // a save wrote the oldest strokes
                else -> if (live.size > 2) repeat(2) { live.removeAt(0) }
            }
            journal.sync(live.toList())
            val read = file.read()
            if (live.isEmpty()) assertEquals("step $step", "none", read.outcome)
            else {
                assertEquals("step $step", "read", read.outcome)
                assertEquals("step $step", live.map { it.data() }, read.strokes)
                assertFalse(read.torn)
            }
        }
        assertTrue("an edit that changes nothing writes nothing", journal.writes <= 50)
    }

    @Test fun anUnchangedListWritesNothing() {
        val journal = InkJournal(journalFile()) { it() }
        val live = listOf(item(stroke(1)), item(stroke(2)))
        journal.sync(live)
        journal.sync(live)
        journal.sync(live.toList())
        assertEquals(1, journal.writes)
    }

    @Test fun aRecordCutShortByTheAppDyingLosesOnlyItself() {
        val file = journalFile()
        val journal = InkJournal(file) { it() }
        val a = item(stroke(1))
        val b = item(stroke(2))
        val c = item(stroke(3))
        journal.sync(listOf(a))
        journal.sync(listOf(a, b))
        journal.sync(listOf(a, b, c))
        RandomAccessFile(file.file, "rw").use { it.setLength(it.length() - 7) }
        val read = file.read()
        assertEquals("read", read.outcome)
        assertTrue(read.torn)
        assertEquals(listOf(a.data(), b.data()), read.strokes)
    }

    @Test fun aDamagedRecordEndsTheReplayThere() {
        val file = journalFile()
        val journal = InkJournal(file) { it() }
        val a = item(stroke(1))
        val b = item(stroke(2))
        journal.sync(listOf(a))
        val firstEnds = file.file.length()
        journal.sync(listOf(a, b))
        // a byte of the second record's payload flipped: its checksum no longer matches
        RandomAccessFile(file.file, "rw").use { f ->
            f.seek(firstEnds + 12)
            val v = f.read()
            f.seek(firstEnds + 12)
            f.write(v xor 0x5A)
        }
        val read = file.read()
        assertTrue(read.torn)
        assertEquals(listOf(a.data()), read.strokes)
    }

    @Test fun anotherDocumentsJournalIsNotRestored() {
        val journal = InkJournal(journalFile()) { it() }
        journal.sync(listOf(item(stroke(1))))
        val other = InkJournalFile(journalFile().file, inkJournalFingerprint(listOf(PageSize(612f, 792f))))
        assertEquals("stale", other.read().outcome)
        assertTrue(other.read().strokes.isEmpty())
        // not a journal at all
        journalFile().file.writeText("not a journal")
        assertEquals("damaged", journalFile().read().outcome)
    }

    @Test fun theFingerprintIsThePages() {
        assertEquals(print, inkJournalFingerprint(pages.toList()))
        assertNotEquals(print, inkJournalFingerprint(pages.reversed()))
        assertNotEquals(print, inkJournalFingerprint(pages + PageSize(612f, 792f)))
        // the name does not show where the document is
        assertFalse(inkJournalName("content://doc/secret.pdf").contains("secret"))
    }

    private fun drafted(page: Int, vararg pts: Offset) = InkStroke(page, InkAppearance(0xFF000000.toInt(), 1f, 2f, null)).also { s ->
        s.points.addAll(pts)
        repeat(pts.size) { s.scales.add(1f) }
    }

    /** What a document's journal holds through a Draw session: drafts, the hand-over, a save, the close. */
    @Test fun aDocumentsJournalHoldsWhatTheFileHasNotYet() {
        val file = journalFile()
        val drafts = InkDrafts()
        val doc = DocInkJournal(InkJournal(file) { it() }, drafts).apply { restoring = false }
        val a = drafted(0, Offset(1f, 1f), Offset(5f, 5f))
        val b = drafted(1, Offset(2f, 2f), Offset(9f, 3f))
        drafts.strokes.addAll(listOf(a, b))
        drafts.edits.addAll(listOf(DraftInkEdit.Added(listOf(a)), DraftInkEdit.Added(listOf(b))))
        doc.sync()
        assertEquals(2, file.read().strokes.size)
        val written = doc.journal.writes
        // Done: handed to the engine, then taken; still in the journal until the save
        val taken = drafts.takeForSaving()
        doc.sync()
        drafts.settle(taken, taken.size)
        doc.committed(taken)
        assertEquals(2, file.read().strokes.size)
        assertEquals("the hand-over keeps the order: nothing rewritten", written, doc.journal.writes)
        // a stroke drawn while the save runs stays after it
        val c = drafted(0, Offset(3f, 3f), Offset(4f, 8f))
        val mark = doc.saveStarted()
        drafts.strokes.add(c)
        doc.saved(mark)
        assertEquals(listOf(c.points.toList()), file.read().strokes.map { it.points })
        assertEquals(1, doc.unsaved)
        // closed with that stroke unsaved: kept for the next visit
        doc.closed(discarded = false)
        assertEquals(1, file.read().strokes.size)
        // discarded on purpose: gone
        DocInkJournal(InkJournal(file) { it() }, drafts).closed(discarded = true)
        assertEquals("none", file.read().outcome)
    }

    @Test fun nothingIsWrittenWhileTheStrokesFoundAreAddedBack() {
        val file = journalFile()
        InkJournal(file) { it() }.sync(listOf(item(stroke(1)), item(stroke(2))))
        val drafts = InkDrafts()
        val doc = DocInkJournal(InkJournal(file) { it() }, drafts)
        val found = file.read().strokes.map { s -> InkJournalItem(Any(), listOf("restored")) { s } }
        // the gesture watcher runs before the restore: the file must keep what it found
        doc.sync()
        assertEquals(2, file.read().strokes.size)
        doc.adoptRestored(found)
        doc.restoring = false
        doc.sync()
        assertEquals(0, doc.journal.writes)
        // the engine refused the second: it is a draft now, in the same place
        doc.refused(found.drop(1))
        val back = drafted(1, *file.read().strokes[1].points.toTypedArray())
        drafts.strokes.add(back)
        doc.sync()
        assertEquals(2, file.read().strokes.size)
        assertEquals(2, doc.unsaved)
    }
}
