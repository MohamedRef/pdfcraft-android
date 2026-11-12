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
        assertFalse(saved.flattenCheck)
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
    fun theDocumentTypeGoesIntoTheRequest() {
        val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        val plain = scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "")
        // worked out from the page unless the person chose one (#299)
        assertEquals("auto", plain.preset)
        assertFalse(plain.fixedStrength)
        assertEquals(0, plain.curvedSides.toInt())
        assertEquals("receipt", scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "", preset = "receipt", curvedSides = 2).preset)
        assertEquals(2, scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "", curvedSides = 2).curvedSides.toInt())
        assertEquals(4, scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "", curvedSides = 9).curvedSides.toInt())
        // the testing option off is as before, whatever was chosen
        assertEquals("off", scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(presets = false), "", preset = "receipt").preset)
        // a word that is no kind is worked out
        assertEquals("auto", scanPresetAsk("magic", true))
        // a strength set by hand is not changed by the kind
        assertTrue(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(strength = 40), "").fixedStrength)
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
        // and a kind of document chosen for the page (#299)
        assertFalse(key == key.copy(preset = "receipt"))
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

    @Test
    fun automaticFlatteningFlattensEveryPageAndChecksItUnlessTheSwitchAsks() {
        val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)
        val auto = ScanLab(flattenAuto = true)
        // the testing option off: flattened only when asked, never checked
        val plain = scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "")
        assertFalse(plain.flatten || plain.flattenCheck)
        // on: every page goes through the network, and is kept only when straighter (#302)
        val checked = scanPageRequest("/a", corners, ScanFilter.CleanScan, false, auto, "")
        assertTrue(checked.flatten && checked.flattenCheck)
        // the scan screen's switch asks for it: flattened as asked, without the check
        val asked = scanPageRequest("/a", corners, ScanFilter.CleanScan, true, auto, "")
        assertTrue(asked.flatten)
        assertFalse(asked.flattenCheck)
        // a preview made with the option is not the one made without it
        val page = ScanPreviewKey("/a", corners.toList(), ScanFilter.CleanScan, false, ScanLab())
        assertFalse(page == page.copy(lab = auto))
    }

    @Test
    fun aFlatteningCheckIsLoggedAsNumbersAndShortWords() {
        val check = io.github.artcraftandroid.pdfcraft.core.ScanFlattenCheck(true, "straighter", 5.583f, 1.588f, 25u, 24u)
        val fields = scanFlattenCheckDiag(check).toMap()
        assertEquals(true, fields["flatten_checked"])
        assertEquals(true, fields["flatten_kept"])
        assertEquals("straighter", fields["flatten_why"])
        assertEquals(5.6, fields["straight_px"])
        assertEquals(1.6, fields["straight_flat_px"])
        assertEquals(25L, fields["straight_lines"])
        val none = scanFlattenCheckDiag(null).toMap()
        assertEquals(false, none["flatten_checked"])
        assertEquals("none", none["flatten_why"])
        assertEquals(-1.0, none["straight_px"])
        // an unmeasured page
        val unmeasured = scanFlattenCheckDiag(io.github.artcraftandroid.pdfcraft.core.ScanFlattenCheck(false, "unmeasured", null, 2f, 0u, 5u)).toMap()
        assertEquals(-1.0, unmeasured["straight_px"])
    }

    @Test
    fun aPagesOwnStrengthGoesIntoTheRequestUnlessATestingOptionSetsOne() {
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        // the slider's strength for the page (#301), the look's own without one
        assertEquals(25, scanPageRequest("/a", corners, ScanFilter.AutoColor, false, ScanLab(), "", strength = 25).strength.toInt())
        assertEquals(ScanFilter.AutoColor.level, scanPageRequest("/a", corners, ScanFilter.AutoColor, false, ScanLab(), "").strength.toInt())
        // the testing option's one strength for every look wins over the slider
        assertEquals(80, scanPageRequest("/a", corners, ScanFilter.AutoColor, false, ScanLab(strength = 80), "", strength = 25).strength.toInt())
        // a page's strength does not make it "set by hand" for the document types: only the testing option does
        assertFalse(scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "", strength = 25).fixedStrength)
    }

    @Test
    fun aPreviewOfAnotherLookOrStrengthIsOfTheSamePage() {
        val corners = listOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val key = ScanPreviewKey("/c/shot.jpg", corners, ScanFilter.CleanScan, false, ScanLab())
        // a strength from the slider changes the preview (#301)
        assertFalse(key == key.copy(strength = 40))
        assertTrue(key.samePage(key.copy(strength = 40)))
        assertTrue(key.samePage(key.copy(filter = ScanFilter.LightText, strength = 10)))
        assertTrue(key.samePage(key))
        // anything that changes the page itself is another page
        assertFalse(key.samePage(key.copy(corners = corners.mapIndexed { i, v -> if (i == 0) 0.01f else v })))
        assertFalse(key.samePage(key.copy(photoPath = "/c/other.jpg")))
        assertFalse(key.samePage(key.copy(flatten = true)))
        assertFalse(key.samePage(key.copy(turns = 1)))
        assertFalse(key.samePage(null))
        assertEquals(io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(ScanFilter.CleanScan, 40), key.copy(strength = 40).look)
    }

    @Test
    fun theLastLookOfTheSamePageStaysUpWhileTheNewOneIsMade() {
        // the same page in its previous look is shown, marked as being updated, instead of the photo (#301)
        assertEquals(ScanShown(ScanView.Result, true), scanShown(true, ScanView.Result, haveWanted = false, failed = false, haveSamePage = true))
        // but not once the new look failed, nor when the photo was asked for or previews are off
        assertEquals(ScanShown(ScanView.Photo, false), scanShown(true, ScanView.Result, haveWanted = false, failed = true, haveSamePage = true))
        assertEquals(ScanShown(ScanView.Photo, false), scanShown(true, ScanView.Photo, haveWanted = false, failed = false, haveSamePage = true))
        assertEquals(ScanShown(ScanView.Photo, false), scanShown(false, ScanView.Result, haveWanted = false, failed = false, haveSamePage = true))
    }

    @Test
    fun whiteboardIsItsOwnLookAndTheTestingOptionPutsBackTheOldOne() {
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        val board = scanPageRequest("/a", corners, ScanFilter.Whiteboard, false, ScanLab(), "")
        assertEquals(io.github.artcraftandroid.pdfcraft.core.ScanLook.WHITEBOARD, board.look)
        assertEquals(90, board.strength.toInt())
        assertEquals("whiteboard", scanLookWord(ScanFilter.Whiteboard, ScanLab()))
        // off: Auto color at 85, as before #301, and the kept page file says "colour"
        val old = scanPageRequest("/a", corners, ScanFilter.Whiteboard, false, ScanLab(whiteboardLook = false), "")
        assertEquals(io.github.artcraftandroid.pdfcraft.core.ScanLook.COLOUR, old.look)
        assertEquals(85, old.strength.toInt())
        assertEquals("colour", scanLookWord(ScanFilter.Whiteboard, ScanLab(whiteboardLook = false)))
        // the slider's strength still applies, and the other looks are not touched by the option
        assertEquals(30, scanPageRequest("/a", corners, ScanFilter.Whiteboard, false, ScanLab(whiteboardLook = false), "", strength = 30).strength.toInt())
        assertEquals("colour", scanLookWord(ScanFilter.AutoColor, ScanLab()))
        assertEquals(85, scanLookStrength(io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(ScanFilter.Whiteboard), ScanLab(whiteboardLook = false)))
    }

    @Test
    fun thePaperChosenGoesIntoTheRequestAndChangesThePreview() {
        val corners = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f)
        assertEquals("auto", scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "").paper)
        assertEquals("keep", scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "", paper = io.github.artcraftandroid.pdfcraft.ui.scan.ScanPaperChoice.Keep).paper)
        val key = ScanPreviewKey("/c/shot.jpg", corners.toList(), ScanFilter.CleanScan, false, ScanLab())
        val kept = key.copy(paper = io.github.artcraftandroid.pdfcraft.ui.scan.ScanPaperChoice.Keep)
        assertFalse(key == kept)
        assertTrue(key.samePage(kept))
    }
}
