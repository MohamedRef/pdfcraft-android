package io.github.artcraftandroid.pdfcraft.ui

import android.content.ComponentCallbacks2
import io.github.artcraftandroid.pdfcraft.core.SearchHit
import io.github.artcraftandroid.pdfcraft.core.TextRange
import io.github.artcraftandroid.pdfcraft.trimFor
import io.github.artcraftandroid.pdfcraft.ui.viewer.THUMBNAIL_MARGIN
import io.github.artcraftandroid.pdfcraft.ui.viewer.insertionPoint
import io.github.artcraftandroid.pdfcraft.ui.viewer.thumbnailWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PerformanceBoundsTest {
    private fun hit(page: Int) = SearchHit(TextRange(page.toUInt(), 0u, 1u), emptyList(), "")

    /** #98: hits of a page go after those of earlier and the same pages, as the old linear scan put them. */
    @Test fun searchHitsInsertInPageOrder() {
        val hits = listOf(1, 1, 3, 3, 3, 7).map(::hit)
        for (page in -1..9) {
            val linear = hits.indexOfFirst { it.range.page.toInt() > page }.let { if (it < 0) hits.size else it }
            assertEquals("page $page", linear, insertionPoint(hits, page))
        }
        assertEquals(0, insertionPoint(emptyList(), 4))
        // the hits drawn for pages 3..6 are exactly those pages' hits
        val drawn = (insertionPoint(hits, 2) until insertionPoint(hits, 6)).map { hits[it].range.page.toInt() }
        assertEquals(listOf(3, 3, 3), drawn)
    }

    /** #95: the Pages grid keeps a bounded window of thumbnails round the pages in view. */
    @Test fun gridThumbnailsStayNearTheView() {
        assertEquals(0..(5 + THUMBNAIL_MARGIN), thumbnailWindow(0, 5))
        val w = thumbnailWindow(500, 507)
        assertEquals(500 - THUMBNAIL_MARGIN, w.first)
        assertEquals(507 + THUMBNAIL_MARGIN, w.last)
        assertTrue(w.count() <= 8 + 2 * THUMBNAIL_MARGIN)
    }

    /** #92: backgrounding drops page images only; real memory pressure drops native caches too. */
    @Suppress("DEPRECATION")
    @Test fun memoryTrimsMatchTheirLevel() {
        val hidden = trimFor(ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN)
        assertTrue(hidden.images)
        assertFalse(hidden.native)
        for (level in listOf(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW, ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL, ComponentCallbacks2.TRIM_MEMORY_BACKGROUND, ComponentCallbacks2.TRIM_MEMORY_COMPLETE)) {
            assertTrue("$level", trimFor(level).native && trimFor(level).images)
        }
        val moderate = trimFor(ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE)
        assertFalse(moderate.images || moderate.native)
    }
}
