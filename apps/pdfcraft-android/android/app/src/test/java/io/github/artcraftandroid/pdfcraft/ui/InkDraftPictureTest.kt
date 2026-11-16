package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.INK_PICTURE_MIN_STROKES
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkDraftKey
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkPictureView
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkPictureCovers
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkPictureKeeps
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkPictureRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #609: finished drafts are shown from one picture while writing. The picture is used only when it
 * stands for exactly what is on the page; these are the rules that decide it.
 */
class InkDraftPictureTest {
    private val view = InkPictureView(1080, 2200, 1.5f, 0f, 340f, "layout", night = false)
    private val pen = InkAppearance(0xFF000000.toInt(), 1f, 1.5f, null)

    /** A stroke stands for itself here: its name, and what its picture depends on. */
    private fun key(name: String, points: Int = 40, version: Int = 1, appearance: InkAppearance = pen) =
        InkDraftKey(name, points, Offset(1f, 2f), Offset(3f, 4f), version, appearance, preview = false)

    private fun covers(pictured: List<InkDraftKey<String>>, at: InkPictureView, finished: List<InkDraftKey<String>>) =
        inkPictureCovers(view, pictured, at, finished, { k, s -> k.stroke == s.stroke }, { k, s -> k == s })

    @Test fun thePictureIsTheHeaviestRunWithoutAMarker() {
        fun run(markers: String, weights: List<Int> = List(markers.length) { 10 }) = inkPictureRun(markers.map { it == 'm' }, weights)
        // handwriting only: all of it
        assertEquals(0..5, run("pppppp"))
        // a marker is blended with the page under it and drafts are painted in order: the picture stops there
        assertEquals(0..3, run("ppppmpp"))
        assertEquals(3..6, run("ppmpppp"))
        // of two runs, the one with more points
        assertEquals(4..6, run("pppmppp", listOf(5, 5, 5, 0, 30, 30, 30)))
        // too few strokes to be worth a picture, or none
        assertNull(run("pp"))
        assertNull(run("ppmppmpp"))
        assertNull(run("mmmm"))
        assertNull(run(""))
        assertEquals(INK_PICTURE_MIN_STROKES, run("ppp")!!.count())
    }

    @Test fun aStrokeJustWrittenIsPaintedOntoThePicture() {
        val a = key("a"); val b = key("b"); val c = key("c"); val d = key("d")
        // the same view, the picture's strokes first and unchanged: only d is painted
        assertEquals(3, inkPictureKeeps(view, listOf(a, b, c), view, listOf(a, b, c, d)))
        assertEquals(3, inkPictureKeeps(view, listOf(a, b, c), view, listOf(a, b, c)))
        // anything else makes the picture anew: the page moved, a stroke changed, one went (undo), no picture yet
        assertNull(inkPictureKeeps(view, listOf(a, b, c), view.copy(scrollY = 341f), listOf(a, b, c, d)))
        assertNull(inkPictureKeeps(view, listOf(a, b, c), view.copy(zoom = 1.6f), listOf(a, b, c, d)))
        assertNull(inkPictureKeeps(view, listOf(a, b, c), view.copy(night = true), listOf(a, b, c, d)))
        assertNull(inkPictureKeeps(view, listOf(a, b, c), view, listOf(a, key("b", points = 12), c, d)))
        assertNull(inkPictureKeeps(view, listOf(a, b, c), view, listOf(a, b)))
        assertNull(inkPictureKeeps(view, listOf(a, b, c), view, listOf(b, c, d)))
        assertNull(inkPictureKeeps(null, emptyList(), view, listOf(a, b, c)))
    }

    @Test fun thePictureIsShownOnlyWhenItIsWhatIsOnThePage() {
        val a = key("a"); val b = key("b"); val c = key("c"); val d = key("d")
        val pictured = listOf(a, b, c)
        assertEquals(0..2, covers(pictured, view, listOf(a, b, c)))
        // strokes written since are drawn after it; a marker or strokes before it, before it
        assertEquals(0..2, covers(pictured, view, listOf(a, b, c, d)))
        assertEquals(2..4, covers(pictured, view, listOf(key("m"), key("n"), a, b, c, d)))
        // the page scrolled, zoomed, turned to Night Mode or the screen changed size: drawn stroke by stroke
        for (other in listOf(view.copy(scrollY = 339f), view.copy(scrollX = 1f), view.copy(zoom = 2f), view.copy(night = true), view.copy(width = 2200, height = 1080), view.copy(layout = "another")))
            assertNull("$other", covers(pictured, other, listOf(a, b, c)))
        // a pictured stroke was erased into, moved, got its end taper or a new colour, or is gone (undo, lasso delete)
        assertNull(covers(pictured, view, listOf(a, key("b", points = 31), c)))
        assertNull(covers(pictured, view, listOf(a, key("b", version = 2), c)))
        assertNull(covers(pictured, view, listOf(a, key("b", appearance = pen.copy(argb = 0xFFFF0000.toInt())), c)))
        assertNull(covers(pictured, view, listOf(a, InkDraftKey("b", 40, Offset(9f, 2f), Offset(11f, 4f), 1, pen, false), c)))
        assertNull(covers(pictured, view, listOf(a, c)))
        assertNull(covers(pictured, view, listOf(a, b)))
        assertNull(covers(pictured, view, listOf(b, c, d)))
        // a stroke put between two of them (redo of an erased one) breaks the order
        assertNull(covers(pictured, view, listOf(a, d, b, c)))
        // no picture
        assertNull(covers(emptyList(), view, listOf(a, b, c)))
    }
}
