package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanTrim
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Flatten page for each page, and the border trim of a flattened page (#302). */
class ScanFlattenPageTest {
    @Test
    fun aPageIsFlattenedOnlyWhenItsOwnSwitchIsOnAndTheModelIsThere() {
        assertTrue(scanPageFlattens(true, canFlatten = true))
        assertFalse(scanPageFlattens(false, canFlatten = true))
        // no choice yet is off: nothing measured tells a bent page from a flat one
        assertFalse(scanPageFlattens(null, canFlatten = true))
        assertFalse(scanPageFlattens(true, canFlatten = false))
    }

    @Test
    fun aNewPageStartsWithTheChoiceOfThePageBeforeOrOfThePageItReplaces() {
        assertNull(scanNewPageFlatten(emptyList(), null))
        assertEquals(true, scanNewPageFlatten(listOf(false, true), null))
        assertEquals(false, scanNewPageFlatten(listOf(false, true), 0))
        assertNull(scanNewPageFlatten(listOf(true, null), null))
        // a retake past the end is a new page after the last
        assertEquals(true, scanNewPageFlatten(listOf(false, true), 5))
    }

    @Test
    fun theChoiceIsLoggedAsAShortWord() {
        assertEquals(listOf("on", "off", "none"), listOf(true, false, null).map(::scanFlattenChoiceId))
    }

    @Test
    fun aTrimIsLoggedInPercentOfEachSide() {
        val fields = scanTrimDiag(ScanTrim(0.0125f, 0f, 0.02f, 0.006667f)).toMap()
        assertEquals(true, fields["trim_looked"])
        assertEquals(true, fields["trimmed"])
        assertEquals(1.25, fields["trim_left_pct"])
        assertEquals(0.0, fields["trim_top_pct"])
        assertEquals(2.0, fields["trim_right_pct"])
        assertEquals(0.67, fields["trim_bottom_pct"])
        val nothing = scanTrimDiag(ScanTrim(0f, 0f, 0f, 0f)).toMap()
        assertEquals(true, nothing["trim_looked"])
        assertEquals(false, nothing["trimmed"])
        val none = scanTrimDiag(null).toMap()
        assertEquals(false, none["trim_looked"])
        assertEquals(-1.0, none["trim_left_pct"])
        // only numbers and booleans go in the log
        assertTrue(none.values.all { it is Boolean || it is Double })
    }

    @Test
    fun aTrimGoesInAKeptPagesFileInPercent() {
        val json = scanTrimJson(ScanTrim(0.01f, 0.02f, Float.NaN, 0f))
        assertEquals(1.0, json.getDouble("left_pct"), 1e-9)
        assertEquals(2.0, json.getDouble("top_pct"), 1e-9)
        assertEquals(0.0, json.getDouble("right_pct"), 1e-9)
        assertEquals(0.0, json.getDouble("bottom_pct"), 1e-9)
    }
}
