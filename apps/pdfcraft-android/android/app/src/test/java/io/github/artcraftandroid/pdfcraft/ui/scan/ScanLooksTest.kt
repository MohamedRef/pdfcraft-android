package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLooksTest {
    private val clean = ScanPageLook()
    private val bw = ScanPageLook(ScanFilter.LightText, 90)
    private val grey = ScanPageLook(ScanFilter.Grayscale)

    @Test
    fun aLooksStrengthIsItsOwnUntilTheSliderSetsOne() {
        assertEquals(ScanFilter.CleanScan.level, clean.level)
        assertEquals("look", clean.strengthSource)
        assertEquals(90, bw.level)
        assertEquals("page", bw.strengthSource)
        assertEquals(100, ScanPageLook(ScanFilter.AutoColor, 400).level)
        // Original has no strength to set
        assertFalse(ScanPageLook(ScanFilter.Original).hasStrength)
        assertTrue(ScanFilter.entries.filter { it != ScanFilter.Original }.all { ScanPageLook(it).hasStrength })
        assertEquals(0, scanSliderStrength(-2f))
        assertEquals(100, scanSliderStrength(100.4f))
        assertEquals(43, scanSliderStrength(42.6f))
        assertEquals(0, scanSliderStrength(Float.NaN))
    }

    @Test
    fun everyLookHasAWordOfItsOwnThatComesBack() {
        val ids = ScanFilter.entries.map(::scanFilterId)
        assertEquals(ids.size, ids.toSet().size)
        for (f in ScanFilter.entries) assertEquals(f, scanFilterOf(scanFilterId(f)))
        // words only: they go into the log and a session line
        assertTrue(ids.all { id -> id.all { it in 'a'..'z' } })
        // Auto color and Whiteboard share the native mode, not the word
        assertEquals(ScanFilter.AutoColor.mode, ScanFilter.Whiteboard.mode)
        assertFalse(scanFilterId(ScanFilter.AutoColor) == scanFilterId(ScanFilter.Whiteboard))
        assertNull(scanFilterOf("magic"))
        assertNull(scanFilterOf(null))
        assertEquals(ScanPageLook(ScanFilter.LightText, 75), ScanPageLook.decode("bw", "75"))
        assertEquals(ScanPageLook(ScanFilter.Whiteboard), ScanPageLook.decode("whiteboard", ""))
        assertEquals(ScanPageLook(ScanFilter.Whiteboard), ScanPageLook.decode("whiteboard", null))
        assertNull(ScanPageLook.decode("bw", "101"))
        assertNull(ScanPageLook.decode("bw", "x"))
        assertNull(ScanPageLook.decode("", "10"))
    }

    @Test
    fun aNewPageTakesTheLookChosenLastAndARetakeKeepsItsOwn() {
        assertEquals(ScanPageLook(), scanLookForNewPage(emptyList(), null))
        assertEquals(grey, scanLookForNewPage(listOf(bw, grey), null))
        assertEquals(bw, scanLookForNewPage(listOf(bw, grey), 0))
        // a retake of a page that is gone takes the last look
        assertEquals(grey, scanLookForNewPage(listOf(bw, grey), 9))
    }

    @Test
    fun aLookIsChosenForOnePageOrEveryPageWithTheTestingOptionOff() {
        val looks = listOf(clean, clean, bw)
        assertEquals(listOf(clean, grey, bw), scanChooseLook(looks, 1, grey, perPage = true))
        assertEquals(listOf(grey, grey, grey), scanChooseLook(looks, 1, grey, perPage = false))
        assertEquals(looks, scanChooseLook(looks, 7, grey, perPage = true))
        assertEquals(looks, scanChooseLook(looks, -1, grey, perPage = false))
    }

    @Test
    fun applyToAllCopiesTheLookAndAsksOnlyWhenAPageWouldChange() {
        val looks = listOf(clean, bw, grey)
        assertEquals(listOf(bw, bw, bw), scanApplyLookToAll(looks, 1))
        assertEquals(looks, scanApplyLookToAll(looks, 5))
        assertTrue(scanApplyLookNeedsConfirm(looks, 0))
        assertFalse(scanApplyLookNeedsConfirm(listOf(bw, bw), 1))
        assertFalse(scanApplyLookNeedsConfirm(looks, 5))
        assertFalse(scanApplyLookNeedsConfirm(emptyList(), 0))
    }

    @Test
    fun thePagesStripMarksPagesWhoseLookIsNotTheFirstPages() {
        val looks = listOf(clean, clean, bw, ScanPageLook(ScanFilter.CleanScan, 60))
        assertEquals(listOf(false, false, true, true), looks.indices.map { scanLookDiffers(looks, it) })
        assertFalse(scanLookDiffers(looks, 9))
        assertEquals(2, scanLooksMixed(looks))
        assertEquals(0, scanLooksMixed(emptyList()))
    }

    @Test
    fun theStripMakesOriginalFirstThenThePagesLookThenTheRest() {
        val order = scanTileOrder(ScanFilter.LightText)
        assertEquals(listOf(ScanFilter.Original, ScanFilter.LightText), order.take(2))
        assertEquals(ScanFilter.entries.toSet(), order.toSet())
        assertEquals(ScanFilter.entries.size, order.size)
        assertEquals(ScanFilter.entries.size, scanTileOrder(ScanFilter.Original).size)
    }

    @Test
    fun theTileOfThePagesLookIsThePagesOwnPreview() {
        val tiles = mapOf(ScanFilter.Original to "o", ScanFilter.LightText to "bw at its own strength")
        assertEquals("page at 90", scanTileImage(ScanFilter.LightText, bw, tiles, "page at 90"))
        // until the page's preview comes, the tile made at the look's own strength stands in
        assertEquals("bw at its own strength", scanTileImage(ScanFilter.LightText, bw, tiles, null))
        assertEquals("o", scanTileImage(ScanFilter.Original, bw, tiles, "page at 90"))
        assertNull(scanTileImage(ScanFilter.Grayscale, bw, tiles, "page at 90"))
    }

    @Test
    fun holdingShowsTheOriginalOnlyWhenThereIsOneToShow() {
        assertTrue(scanCompareShows(holding = true, haveOriginal = true, look = ScanFilter.CleanScan))
        assertFalse(scanCompareShows(holding = false, haveOriginal = true, look = ScanFilter.CleanScan))
        assertFalse(scanCompareShows(holding = true, haveOriginal = false, look = ScanFilter.CleanScan))
        assertFalse(scanCompareShows(holding = true, haveOriginal = true, look = ScanFilter.Original))
    }

    @Test
    fun theFinalMessageCountsPagesSavedAsPhotographed() {
        assertEquals(0, scanUnenhancedCount(listOf(true, true)))
        assertEquals(2, scanUnenhancedCount(listOf(false, true, false)))
    }
}
