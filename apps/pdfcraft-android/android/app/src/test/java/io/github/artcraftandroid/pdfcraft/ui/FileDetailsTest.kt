package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.PageInfo
import io.github.artcraftandroid.pdfcraft.ui.viewer.pageGeometry
import io.github.artcraftandroid.pdfcraft.ui.viewer.providerTimestampMillis
import io.github.artcraftandroid.pdfcraft.ui.viewer.xmpDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FileDetailsTest {
    private fun page(w: Float, h: Float) = PageInfo(w, h, "1", 0u.toUShort())

    @Test
    fun pageDimensionsRespectOrientationAndMixedSizes() {
        val portrait = page(612f, 792f)
        val landscape = page(792f, 612f)
        val same = pageGeometry(listOf(portrait, portrait))
        assertEquals("612 × 792 pt", same.firstSize)
        assertFalse(same.mixedSizes)
        assertEquals(2, same.orientation)

        val mixed = pageGeometry(listOf(portrait, landscape))
        assertEquals("612 × 792 pt", mixed.firstSize)
        assertTrue(mixed.mixedSizes)
        assertNull(mixed.orientation)
        assertEquals(1, pageGeometry(listOf(landscape)).orientation)
    }

    @Test
    fun missingAndSquarePagesAreHandled() {
        assertNull(pageGeometry(emptyList()).firstSize)
        assertEquals(3, pageGeometry(listOf(page(200f, 200f))).orientation)
    }

    @Test
    fun xmpDatesCanBeReadFromElementsAndAttributes() {
        val xmp = """<rdf:Description xmp:ModifyDate="2026-11-06T10:00:00Z"><xmp:CreateDate>2026-01-01T08:30:00Z</xmp:CreateDate></rdf:Description>"""
        assertEquals("2026-01-01T08:30:00Z", xmpDate(xmp, "CreateDate"))
        assertEquals("2026-11-06T10:00:00Z", xmpDate(xmp, "ModifyDate"))
        assertNull(xmpDate(xmp, "Nonexistent"))
        assertNull(xmpDate(null, "CreateDate"))
    }

    @Test
    fun providerCreationTimestampsAreNormalizedWithoutInventingDates() {
        assertNull(providerTimestampMillis(0L))
        assertNull(providerTimestampMillis(-2L))
        assertEquals(1_700_000_000_000L, providerTimestampMillis(1_700_000_000L))
        assertEquals(1_700_000_000_123L, providerTimestampMillis(1_700_000_000_123L))
    }
}
