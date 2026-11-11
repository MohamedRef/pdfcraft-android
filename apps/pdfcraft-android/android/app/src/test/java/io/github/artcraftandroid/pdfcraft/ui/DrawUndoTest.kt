package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.engine.ToolBatchException
import io.github.artcraftandroid.pdfcraft.engine.runToolBatch
import io.github.artcraftandroid.pdfcraft.ui.viewer.DraftInkEdit
import io.github.artcraftandroid.pdfcraft.ui.viewer.DrawUndoSteps
import io.github.artcraftandroid.pdfcraft.ui.viewer.DrawUndoSteps.Route
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkDrafts
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStroke
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** #72, #73, #78: the Draw tool's drafts, their save and its Undo. */
class DrawUndoTest {
    private fun stroke() = InkStroke(0, InkAppearance(0xFF000000.toInt(), 1f, 2f, null)).apply {
        points.add(Offset(0f, 0f)); points.add(Offset(1f, 1f))
        pressures.add(1f); pressures.add(1f)
    }

    /** A fake document history: each command is one undo step, `bad` ones are refused. */
    private class History(val bad: Set<String> = emptySet()) {
        val steps = ArrayList<String>()
        fun execute(args: Map<String, Any?>) {
            val id = args["id"] as String
            if (id in bad) throw IllegalStateException("refused $id")
            steps.add(id)
        }
        fun undo(@Suppress("UNUSED_PARAMETER") args: Map<String, Any?>) { steps.removeAt(steps.lastIndex) }
    }

    private fun groups(vararg ids: List<String>) = ids.map { g -> g.map { mapOf<String, Any?>("id" to it) } }

    @Test fun aBatchAppliesEveryGroupInOrder() {
        val h = History()
        assertEquals(2, runToolBatch(groups(listOf("a"), listOf("b1", "b2")), h::execute, h::undo))
        assertEquals(listOf("a", "b1", "b2"), h.steps)
    }

    @Test fun aRefusedGroupIsTakenBackWholeAndTheEarlierOnesStay() {
        val h = History(bad = setOf("b2"))
        try {
            runToolBatch(groups(listOf("a"), listOf("b1", "b2"), listOf("c")), h::execute, h::undo)
            fail("expected the refusal")
        } catch (e: ToolBatchException) {
            assertEquals(1, e.applied)
            assertTrue(e.rolledBack)
            assertEquals("refused b2", e.cause.message)
        }
        // b1 was undone with its group; c never ran.
        assertEquals(listOf("a"), h.steps)
    }

    @Test fun refusedDraftsComeBackBeforeNewerOnesAndUndoable() {
        val d = InkDrafts()
        val (a, b, c) = List(3) { stroke() }
        d.strokes.addAll(listOf(a, b, c))
        d.edits.addAll(listOf(a, b, c).map { DraftInkEdit.Added(it) })
        val taken = d.takeForSaving()
        assertEquals(listOf(a, b, c), d.saving.toList())
        assertTrue(d.strokes.isEmpty() && d.edits.isEmpty())
        // drawn while the save runs
        val newer = stroke()
        d.strokes.add(newer)
        d.edits.add(DraftInkEdit.Added(newer))
        // the engine took only the first
        assertEquals(2, d.settle(taken, applied = 1))
        assertTrue(d.saving.isEmpty())
        assertEquals(listOf(b, c, newer), d.strokes.toList())
        assertEquals(listOf(b, c, newer).map { DraftInkEdit.Added(it) }, d.edits.toList())
    }

    @Test fun acceptedDraftsLeaveNothingBehind() {
        val d = InkDrafts()
        d.strokes.add(stroke())
        val taken = d.takeForSaving()
        assertEquals(0, d.settle(taken, applied = 1))
        assertTrue(d.saving.isEmpty() && d.strokes.isEmpty() && d.edits.isEmpty())
    }

    /** #78: rotate, enter Draw, draw and save a stroke, Undo twice. */
    @Test fun undoNamesAnEditMadeBeforeDraw() {
        val s = DrawUndoSteps()
        s.started(); s.finished(steps = 1) // the stroke, saved
        assertEquals(Route.Quiet, s.undo()) // takes the stroke back
        assertEquals(Route.Announced, s.undo()) // "Rotate page undone"
        // Redo goes back in the opposite order.
        assertTrue(s.redoesDocumentFirst())
        assertEquals(Route.Announced, s.redo())
        assertEquals(Route.Quiet, s.redo())
        assertFalse(s.redoesDocumentFirst())
    }

    @Test fun editsThatAddNoStepAreNotCounted() {
        val s = DrawUndoSteps()
        s.started()
        assertFalse(s.idle) // Undo waits while a Draw edit runs
        s.finished(steps = 0) // an eraser that met no ink, or a refused save
        assertTrue(s.idle)
        assertEquals(0, s.own)
        assertEquals(Route.Announced, s.undo())
    }

    @Test fun aNewDraftEndsTheDocumentsRedo() {
        val s = DrawUndoSteps()
        s.started(); s.finished(steps = 2)
        s.undo()
        assertTrue(s.canRedoDocument(documentHasRedo = true))
        s.drafted()
        assertFalse(s.redoesDocumentFirst())
        assertFalse(s.canRedoDocument(documentHasRedo = true))
        // Saving that draft is a new document edit: redo is possible again later.
        s.started(); s.finished(steps = 1)
        assertEquals(2, s.own)
        assertTrue(s.canRedoDocument(documentHasRedo = true))
    }
}
