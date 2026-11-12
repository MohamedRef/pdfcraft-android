package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.ScanLook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanSaveTest {
    @Test
    fun onlyAWholePhotoInOriginalSkipsProcessing() {
        assertFalse(scanNeedsProcessing(whole = true, mode = "original"))
        assertTrue(scanNeedsProcessing(whole = false, mode = "original"))
        assertTrue(scanNeedsProcessing(whole = true, mode = "colour"))
        assertTrue(scanNeedsProcessing(whole = true, mode = "bw"))
    }

    @Test
    fun everyFilterModeHasALook() {
        assertEquals(ScanLook.ORIGINAL, scanLookOf("original"))
        assertEquals(ScanLook.AUTO, scanLookOf("auto"))
        assertEquals(ScanLook.COLOUR, scanLookOf("colour"))
        assertEquals(ScanLook.GREYSCALE, scanLookOf("greyscale"))
        assertEquals(ScanLook.BLACK_WHITE, scanLookOf("bw"))
        for (f in ScanFilter.entries) scanLookOf(f.mode)
    }

    @Test
    fun aFailedPageFallsBackToTheCameraFile() {
        assertEquals(ScanSavedPage("/c/shot.jpg", null), scanSavedPage("/c/shot.jpg", null))
        assertEquals(ScanSavedPage("/c/page-0.jpg", 300f), scanSavedPage("/c/shot.jpg", "/c/page-0.jpg" to 300f))
    }

    @Test
    fun onePageListOneCallWithTheMedianResolution() {
        val args = scanCreateArgs(listOf(
            ScanSavedPage("/c/page-0.jpg", 300f),
            ScanSavedPage("/c/shot-1.jpg", null),
            ScanSavedPage("/c/page-2.png", 400f),
            ScanSavedPage("/c/page-3.jpg", 320f),
        ))
        assertEquals("images", args["from"])
        assertEquals(listOf("/c/page-0.jpg", "/c/shot-1.jpg", "/c/page-2.png", "/c/page-3.jpg"), args["paths"])
        assertEquals(320.0, args["dpi"] as Double, 1e-6)
        // an even count takes the mean of the middle two
        assertEquals(350.0, scanCreateArgs(listOf(ScanSavedPage("a", 300f), ScanSavedPage("b", 400f)))["dpi"] as Double, 1e-6)
        // clamped to 72–600
        assertEquals(600.0, scanCreateArgs(listOf(ScanSavedPage("a", 900f)))["dpi"] as Double, 1e-6)
    }

    @Test
    fun withoutProcessedPagesImagesKeepTheirOwnResolution() {
        val args = scanCreateArgs(listOf(ScanSavedPage("/c/shot.jpg", null)))
        assertFalse(args.containsKey("dpi"))
        assertEquals(listOf("/c/shot.jpg"), args["paths"])
    }
}
