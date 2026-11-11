package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.geometry.Offset

/** Reversible changes to the uncommitted Draw layer, in actual gesture order. */
internal sealed class DraftInkEdit {
    /** One gesture's strokes (several when the pen left the page and came back, #75). */
    data class Added(val strokes: List<InkStroke>) : DraftInkEdit()
    data class Moved(val strokes: List<InkStroke>, val offset: Offset) : DraftInkEdit()
    /** Lasso Delete of draft strokes, each restored at its own position in paint order. */
    data class Removed(val strokes: List<Pair<Int, InkStroke>>) : DraftInkEdit()
}

internal fun applyDraftInkEdit(strokes: SnapshotStateList<InkStroke>, edit: DraftInkEdit, forward: Boolean) {
    when (edit) {
        is DraftInkEdit.Added -> {
            if (forward) strokes.addAll(edit.strokes) else strokes.removeAll(edit.strokes)
        }
        is DraftInkEdit.Removed -> {
            if (forward) edit.strokes.forEach { (_, s) -> strokes.remove(s) }
            else edit.strokes.sortedBy { it.first }.forEach { (i, s) -> strokes.add(i.coerceIn(0, strokes.size), s) }
        }
        is DraftInkEdit.Moved -> {
            val d = if (forward) edit.offset else -edit.offset
            edit.strokes.forEach { stroke ->
                for (i in stroke.points.indices) stroke.points[i] = stroke.points[i] + d
                stroke.preview = null
            }
        }
    }
}

/**
 * The Draw tool's ink that the document does not have yet. It belongs to the document, not to
 * the Draw layer on screen, so a save the engine refuses after the tool was left still finds
 * its strokes to keep (#73).
 */
internal class InkDrafts {
    /** Drafts, in paint order. */
    val strokes = mutableStateListOf<InkStroke>()
    /** A single ordered history keeps mixed draw/lasso/transform gestures undoable in the right order. */
    val edits = mutableStateListOf<DraftInkEdit>()
    val undone = mutableStateListOf<DraftInkEdit>()
    /** Strokes handed to the engine and not yet accepted: still drawn, no longer editable. */
    val saving = mutableStateListOf<InkStroke>()

    /** Hand every draft over for saving; the caller passes them to [settle] when the engine is done. */
    fun takeForSaving(): List<InkStroke> {
        val taken = strokes.toList()
        saving.addAll(taken)
        strokes.clear()
        edits.clear()
        undone.clear()
        return taken
    }

    /**
     * The engine accepted the first [applied] of [taken] (see [inkSaveCommandGroups]). The others
     * go back to the drafts, before anything drawn since and undoable again, never dropped.
     * Returns how many were kept.
     */
    fun settle(taken: List<InkStroke>, applied: Int): Int {
        saving.removeAll(taken.toSet())
        val kept = taken.drop(applied.coerceIn(0, taken.size))
        if (kept.isEmpty()) return 0
        strokes.addAll(0, kept)
        edits.addAll(0, kept.map { DraftInkEdit.Added(listOf(it)) })
        return kept.size
    }
}

/**
 * Which of the document's undo steps the current Draw session made (#78). Draw's Undo takes back
 * drafts first and then the document's steps; the steps made in this session (saved ink, the
 * eraser, lasso edits, shapes) are taken back quietly, as part of drawing, while an older step,
 * one made before Draw was entered, is [Route.Announced], with its name, as the top bar's Undo
 * does outside Draw. A Draw edit made while one is still running holds Undo and Redo until it
 * ends, since only then is it known whether it became a step.
 */
internal class DrawUndoSteps {
    enum class Route { Quiet, Announced }

    /** This session's steps at the top of the document's history. */
    var own by mutableIntStateOf(0)
        private set
    private var ownUndone by mutableIntStateOf(0)
    /** Older steps undone from Draw, redone (announced) before this session's own. */
    private var olderUndone by mutableIntStateOf(0)
    /** False once a draft was drawn after an undo: redoing a document step would reorder ink. */
    private var redoKept by mutableStateOf(true)
    /** Draw edits sent to the engine and not finished yet. */
    var running by mutableIntStateOf(0)
        private set

    val idle: Boolean get() = running == 0

    fun started() { running++ }

    /** A Draw edit ended; [steps] undo steps it added to the document (0 when refused or a no-op). */
    fun finished(steps: Int) {
        running = (running - 1).coerceAtLeast(0)
        if (steps > 0) {
            own += steps
            ownUndone = 0
            olderUndone = 0
            redoKept = true
        }
    }

    /** A new draft: the document's redo steps are no longer the next thing to redo. */
    fun drafted() {
        ownUndone = 0
        olderUndone = 0
        redoKept = false
    }

    fun undo(): Route = if (own > 0) {
        own--
        ownUndone++
        Route.Quiet
    } else {
        olderUndone++
        Route.Announced
    }

    /** Whether Redo goes to the document first: it undid something since the last draft was undone. */
    fun redoesDocumentFirst(): Boolean = ownUndone + olderUndone > 0

    fun canRedoDocument(documentHasRedo: Boolean): Boolean = documentHasRedo && redoKept

    fun redo(): Route = if (olderUndone > 0) {
        olderUndone--
        Route.Announced
    } else if (ownUndone > 0) {
        ownUndone--
        own++
        Route.Quiet
    } else {
        Route.Announced
    }
}
