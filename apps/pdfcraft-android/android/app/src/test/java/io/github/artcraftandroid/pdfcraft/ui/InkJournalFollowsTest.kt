package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import io.github.artcraftandroid.pdfcraft.ui.viewer.DocInkJournal
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkDrafts
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkJournal
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkJournalFile
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkJournalItem
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkJournalMatch
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkSaveStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PageSize
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkJournalFingerprint
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkJournalMatch
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkJournalName
import io.github.artcraftandroid.pdfcraft.ui.viewer.lassoTransformedSaved
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.math.abs

/**
 * #703: strokes the engine took from the crash journal stay in the journal until a save, so the
 * journal makes the lasso's edits of them too (move, resize, turn, recolour, delete), and says so
 * when an edit changed them in a way it could not follow (an eraser cut, Undo).
 */
class InkJournalFollowsTest {
    @get:Rule val tmp = TemporaryFolder()

    private val file by lazy {
        InkJournalFile(tmp.root.resolve("ink_journal").resolve(inkJournalName("content://doc/703")), inkJournalFingerprint(listOf(PageSize(612f, 792f))))
    }

    private fun drafted(page: Int, pts: List<Offset>, width: Float = 2f) = InkStroke(page, InkAppearance(0xFF000000.toInt(), 1f, width, null)).also { s ->
        s.points.addAll(pts)
        repeat(pts.size) { s.scales.add(1f) }
    }

    /** The rectangle the engine gives a drawing of constant [width]: its points' bounds grown by half the width and a point. */
    private fun engineRect(pts: List<Offset>, width: Float, jitter: Float = 0f): List<Float> {
        val grow = width / 2f + 1f
        return listOf(pts.minOf { it.x } - grow + jitter, pts.maxOf { it.y } + grow - jitter, pts.maxOf { it.x } + grow - jitter, pts.minOf { it.y } - grow + jitter)
    }

    /** A document's journal with [strokes] drawn, handed to the engine and taken by it, none saved yet. */
    private fun committed(strokes: List<InkStroke>, drafts: InkDrafts = InkDrafts()): DocInkJournal {
        val doc = DocInkJournal(InkJournal(file) { it() }, drafts).apply { restoring = false }
        drafts.strokes.addAll(strokes)
        doc.sync()
        val taken = drafts.takeForSaving()
        drafts.settle(taken, taken.size)
        doc.committed(taken)
        return doc
    }

    private fun near(a: Offset, b: Offset) = abs(a.x - b.x) < 1e-3f && abs(a.y - b.y) < 1e-3f

    @Test fun theLassosEditsOfTakenStrokesComeBackAsLeft() {
        val cube = listOf(Offset(100f, 100f), Offset(160f, 100f), Offset(160f, 160f))
        val note = listOf(Offset(300f, 500f), Offset(380f, 520f))
        val doc = committed(listOf(drafted(0, cube), drafted(0, note)))
        // moved: the engine's rectangle, a little off as it rounds, finds the cube only
        assertEquals(1, doc.savedInkChanged(0, listOf(engineRect(cube, 2f, jitter = 0.4f))) { s -> s.copy(points = s.points.map { it + Offset(10f, 20f) }) })
        var back = file.read()
        assertEquals(listOf(Offset(110f, 120f), Offset(170f, 120f), Offset(170f, 180f)), back.strokes[0].points)
        assertEquals(note, back.strokes[1].points)
        // turned a quarter turn and halved about its corner: its width halves as the engine's does
        val moved = back.strokes[0].points
        assertEquals(1, doc.savedInkChanged(0, listOf(engineRect(moved, 2f))) { s -> lassoTransformedSaved(s, Offset(110f, 120f), 0.5f, 90f) })
        back = file.read()
        val turned = back.strokes[0]
        assertTrue(near(Offset(110f, 120f), turned.points[0]))
        assertTrue(near(Offset(110f, 150f), turned.points[1]))
        assertTrue(near(Offset(80f, 150f), turned.points[2]))
        assertEquals(1f, turned.appearance.width, 1e-6f)
        // recoloured
        doc.savedInkChanged(0, listOf(engineRect(note, 2f))) { s -> s.copy(appearance = s.appearance.copy(argb = 0xFFFF0000.toInt())) }
        assertEquals(0xFFFF0000.toInt(), file.read().strokes[1].appearance.argb)
        // deleted: a crash no longer brings it back
        doc.savedInkChanged(0, listOf(engineRect(note, 2f))) { null }
        back = file.read()
        assertEquals(1, back.strokes.size)
        assertEquals(1, doc.unsaved)
        // all of it followed: a restore has nothing to warn about
        assertFalse(back.touched)
        assertFalse(doc.touched)
    }

    @Test fun anEditOfAnotherPageOrAnotherDrawingLeavesTheStrokesAlone() {
        val line = listOf(Offset(50f, 50f), Offset(90f, 60f))
        val doc = committed(listOf(drafted(0, line)))
        val writes = doc.journal.writes
        assertEquals(0, doc.savedInkChanged(1, listOf(engineRect(line, 2f))) { null })
        assertEquals(0, doc.savedInkChanged(0, listOf(engineRect(listOf(Offset(400f, 400f), Offset(450f, 420f)), 2f))) { null })
        assertEquals(line, file.read().strokes.single().points)
        assertFalse(file.read().touched)
        assertEquals("nothing changed, nothing written", writes, doc.journal.writes)
    }

    @Test fun anEditOfPartOfAStrokeMarksTheJournalInsteadOfGuessing() {
        val line = listOf(Offset(50f, 50f), Offset(250f, 60f))
        val doc = committed(listOf(drafted(0, line)))
        // an annotation over half of it (a dashed line's piece, or another drawing on top): not this stroke as saved
        assertEquals(0, doc.savedInkChanged(0, listOf(engineRect(listOf(Offset(50f, 50f), Offset(150f, 55f)), 2f))) { null })
        val back = file.read()
        assertEquals("the stroke is kept as it was", line, back.strokes.single().points)
        assertTrue("a restore says changes may be missing", back.touched)
    }

    @Test fun anEraserCutMarksTheJournalUntilASaveWritesTheStrokes() {
        val line = listOf(Offset(50f, 50f), Offset(90f, 60f))
        val doc = committed(listOf(drafted(0, line)))
        // an eraser elsewhere on the page, or on another page, does not
        doc.savedInkTouched(0, Rect(300f, 300f, 320f, 320f))
        doc.savedInkTouched(1, Rect(40f, 40f, 100f, 100f))
        assertFalse(file.read().touched)
        // one across the stroke does; the stroke is kept as the journal has it
        doc.savedInkTouched(0, Rect(60f, 40f, 70f, 70f))
        assertTrue(file.read().touched)
        assertEquals(line, file.read().strokes.single().points)
        // the save writes the taken stroke: nothing is left to restore or to warn about
        doc.saved(doc.saveStarted())
        assertFalse(doc.touched)
        assertEquals("none", file.read().outcome)
    }

    @Test fun anUndoMarksTheJournalAndTheSaveClearsTheMarkButKeepsLaterDrafts() {
        val drafts = InkDrafts()
        val doc = committed(listOf(drafted(0, listOf(Offset(50f, 50f), Offset(90f, 60f)))), drafts)
        doc.documentStepped()
        assertTrue(file.read().touched)
        // a draft drawn afterwards is journaled after it
        val draft = drafted(0, listOf(Offset(10f, 10f), Offset(20f, 30f)))
        drafts.strokes.add(draft)
        doc.sync()
        assertEquals(2, file.read().strokes.size)
        // the save writes the taken stroke: the mark goes, the draft stays
        doc.saved(doc.saveStarted())
        val back = file.read()
        assertFalse(back.touched)
        assertEquals(listOf(draft.points.toList()), back.strokes.map { it.points })
        // with nothing taken, an Undo marks nothing
        doc.documentStepped()
        assertFalse(file.read().touched)
    }

    @Test fun aMarkKeptAcrossARestoreGoesWithTheSave() {
        InkJournal(file) { it() }.sync(listOf(InkJournalItem(Any(), listOf(1)) { InkSaveStroke(0, InkAppearance(-16777216, 1f, 2f, null), listOf(Offset(1f, 1f), Offset(5f, 5f)), listOf(1f, 1f), false) }), touched = true)
        val found = file.read()
        assertTrue(found.touched)
        val doc = DocInkJournal(InkJournal(file) { it() }, InkDrafts())
        doc.adoptRestored(found.strokes.map { s -> InkJournalItem(Any(), listOf("restored")) { s } }, found.touched)
        doc.restoring = false
        doc.sync()
        assertEquals("the file already says so", 0, doc.journal.writes)
        // a second crash before a save would warn again
        assertTrue(file.read().touched)
        val mark = doc.saveStarted()
        doc.saved(mark)
        assertFalse(doc.touched)
        assertEquals("none", file.read().outcome)
    }

    @Test fun aStrokeMatchesOnlyTheRectanglesOfEveryAnnotationItIsSavedAs() {
        val pts = listOf(Offset(10f, 10f), Offset(60f, 40f), Offset(110f, 15f))
        val s = InkSaveStroke(0, InkAppearance(-16777216, 1f, 4f, null), pts, List(3) { 1f }, false)
        assertEquals(InkJournalMatch.All, inkJournalMatch(s, listOf(engineRect(pts, 4f))))
        assertEquals(InkJournalMatch.All, inkJournalMatch(s, listOf(listOf(0f, 0f, 1f, 1f), engineRect(pts, 4f, jitter = 1.2f))))
        assertEquals(InkJournalMatch.None, inkJournalMatch(s, listOf(listOf(400f, 400f, 420f, 430f))))
        assertEquals(InkJournalMatch.Some, inkJournalMatch(s, listOf(listOf(0f, 0f, 70f, 50f))))
        assertEquals(InkJournalMatch.None, inkJournalMatch(s, emptyList()))
        // a calligraphy stroke is saved as one annotation per width: all of them are needed
        val calligraphy = InkSaveStroke(0, InkAppearance(-16777216, 1f, 4f, null, InkStyle.Calligraphy), pts, listOf(0.5f, 1.5f, 0.5f), false)
        val parts = io.github.artcraftandroid.pdfcraft.ui.viewer.inkJournalRects(calligraphy)
        assertEquals(InkJournalMatch.All, inkJournalMatch(calligraphy, parts.map { it.first }))
        if (parts.size > 1) assertEquals(InkJournalMatch.Some, inkJournalMatch(calligraphy, listOf(parts[0].first)))
    }
}
