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
    fun processedPagesKeepTheirOwnResolution() {
        val args = scanCreateArgs(listOf(
            ScanSavedPage("/c/page-0.jpg", 300f),
            ScanSavedPage("/c/page-1.png", 400f),
            ScanSavedPage("/c/page-2.jpg", 320f),
        ))
        assertEquals("images", args["from"])
        assertEquals(listOf("/c/page-0.jpg", "/c/page-1.png", "/c/page-2.jpg"), args["paths"])
        // each file says its own size, so an A4 page and a Letter page are each exact
        assertFalse(args.containsKey("dpi"))
    }

    @Test
    fun aCameraFileAmongProcessedPagesTakesTheirMedianResolution() {
        val args = scanCreateArgs(listOf(
            ScanSavedPage("/c/page-0.jpg", 300f),
            ScanSavedPage("/c/shot-1.jpg", null),
            ScanSavedPage("/c/page-2.png", 400f),
            ScanSavedPage("/c/page-3.jpg", 320f),
        ))
        assertEquals(listOf("/c/page-0.jpg", "/c/shot-1.jpg", "/c/page-2.png", "/c/page-3.jpg"), args["paths"])
        assertEquals(320.0, args["dpi"] as Double, 1e-6)
        // an even count takes the mean of the middle two
        assertEquals(350.0, scanCreateArgs(listOf(ScanSavedPage("a", 300f), ScanSavedPage("b", 400f), ScanSavedPage("c", null)))["dpi"] as Double, 1e-6)
        // clamped to 72–600
        assertEquals(600.0, scanCreateArgs(listOf(ScanSavedPage("a", 900f), ScanSavedPage("c", null)))["dpi"] as Double, 1e-6)
    }

    @Test
    fun withoutProcessedPagesImagesKeepTheirOwnResolution() {
        val args = scanCreateArgs(listOf(ScanSavedPage("/c/shot.jpg", null)))
        assertFalse(args.containsKey("dpi"))
        assertEquals(listOf("/c/shot.jpg"), args["paths"])
    }

    @Test
    fun theRequestIsOneDescriptionForThePreviewAndTheSave() {
        val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        val lab = ScanLab(snapPaper = false, perspective = false, upright = false, sharpen = 60, denoise = false, strength = 80)
        val saved = scanPageRequest("/c/shot.jpg", corners, ScanFilter.CleanScan, true, lab, "/c/scan/page-0")
        val preview = scanPageRequest("/c/shot.jpg", corners, ScanFilter.CleanScan, true, lab, "")
        // only where the page is written differs
        assertEquals(saved.copy(outputPath = ""), preview)
        assertEquals(ScanLook.AUTO, saved.look)
        assertEquals(80, saved.strength.toInt())
        assertEquals(corners.toList(), saved.corners)
        assertFalse(saved.snapPaper)
        assertTrue(saved.flatten && saved.sideProportions)
        assertFalse(saved.upright || saved.denoise)
        assertEquals(60, saved.sharpen.toInt())
        // a look's own strength without the override
        assertEquals(ScanFilter.LightText.level, scanPageRequest("/a", corners, ScanFilter.LightText, false, ScanLab(), "").strength.toInt())
    }

    @Test
    fun aPagesOwnTurnGoesIntoTheRequestAndStopsTheClassifier() {
        val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        val auto = scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "")
        assertTrue(auto.upright)
        assertEquals(0, auto.turns.toInt())
        // Rotate (#339): the person's turn, and the classifier no longer asked about the page
        val turned = scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "", turns = 3)
        assertFalse(turned.upright)
        assertEquals(3, turned.turns.toInt())
        // the ordinary setting off stops the classifier too
        assertFalse(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "", autoUpright = false).upright)
    }

    @Test
    fun aPreviewIsOutOfDateWhenAnythingThatChangesThePageChanges() {
        val corners = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val key = ScanPreviewKey("/c/shot.jpg", corners, ScanFilter.CleanScan, false, ScanLab())
        assertEquals(key, ScanPreviewKey("/c/shot.jpg", corners.toList(), ScanFilter.CleanScan, false, ScanLab()))
        assertFalse(key == key.copy(filter = ScanFilter.Grayscale))
        assertFalse(key == key.copy(flatten = true))
        assertFalse(key == key.copy(corners = corners.mapIndexed { i, v -> if (i == 0) 0.01f else v }))
        assertFalse(key == key.copy(lab = ScanLab(sharpen = 0)))
        assertFalse(key == key.copy(photoPath = "/c/other.jpg"))
        // a turn by hand, or the automatic turn switched off, changes the page shown (#339)
        assertFalse(key == key.copy(turns = 1))
        assertFalse(key == key.copy(turns = 0))
        assertFalse(key == key.copy(autoUpright = false))
    }

    @Test
    fun theScreenShowsTheResultOnlyWhenItIsWantedAndReady() {
        // ready: the page as it will be saved
        assertEquals(ScanShown(ScanView.Result, false), scanShown(previews = true, chosen = ScanView.Result, haveWanted = true, failed = false))
        // not yet: the photo, with a note that the page is being prepared
        assertEquals(ScanShown(ScanView.Photo, true), scanShown(true, ScanView.Result, haveWanted = false, failed = false))
        // it could not be made: the photo, without the note
        assertEquals(ScanShown(ScanView.Photo, false), scanShown(true, ScanView.Result, haveWanted = false, failed = true))
        // the person asked for the photo, or previews are off for testing
        assertEquals(ScanShown(ScanView.Photo, false), scanShown(true, ScanView.Photo, haveWanted = true, failed = false))
        assertEquals(ScanShown(ScanView.Photo, false), scanShown(previews = false, chosen = ScanView.Result, haveWanted = true, failed = false))
    }
}
