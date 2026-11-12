package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.PageObject
import io.github.artcraftandroid.pdfcraft.ui.viewer.objectAt
import io.github.artcraftandroid.pdfcraft.ui.viewer.objectsIn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Selecting several objects (#162): which object a tap means and what a marquee takes. */
class ObjectSelectTest {
    private val page = PageObject("image", 1, floatArrayOf(0f, 0f, 400f, 300f), null)
    private val heading = PageObject("text", 1, floatArrayOf(72f, 60f, 300f, 80f), "Title")
    private val note = PageObject("added", 1, floatArrayOf(350f, 400f, 450f, 420f), "Note")
    private val all = listOf(page, heading, note)

    @Test
    fun referencesAreWhatObjectMoveTakes() {
        assertEquals(mapOf("kind" to "text", "index" to 1), heading.ref)
        assertEquals("text:1", heading.key)
    }

    @Test
    fun aTapMeansTheSmallestObjectUnderIt() {
        assertEquals(heading, objectAt(all, 100f, 70f, 2f))
        assertEquals(page, objectAt(all, 10f, 10f, 2f))
        assertEquals(note, objectAt(all, 349f, 410f, 2f))
        assertNull(objectAt(all, 500f, 500f, 2f))
    }

    @Test
    fun aMarqueeTakesWhatItTouchesEitherWay() {
        // dragged up and to the left
        assertEquals(listOf(heading, note), objectsIn(listOf(heading, note), 420f, 410f, 100f, 50f))
        assertEquals(listOf(note), objectsIn(all, 440f, 415f, 445f, 418f))
        assertEquals(listOf(page, heading), objectsIn(all, 10f, 10f, 80f, 65f))
    }
}
