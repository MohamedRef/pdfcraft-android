package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanPaper
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.ScanPage
import io.github.artcraftandroid.pdfcraft.ui.scanTestPageJson
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A scan's pages share one paper size, and a page is an ID card only from a sure outline (#759). */
class ScanPaperRulesTest {
    private val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theScansPaperIsWhatMostOfItsOtherPagesAre() {
        // the owner's scan of 2026-11-19: the sure pages came out A4, A4, A4, A4 and Letter
        assertEquals(ScanPaper.A4, scanSharedPaper(listOf(ScanPaper.A4, ScanPaper.A4, ScanPaper.A4, ScanPaper.A4, ScanPaper.LETTER)))
        // two must agree, and more than half of the pages made
        assertNull(scanSharedPaper(listOf(ScanPaper.A4)))
        assertNull(scanSharedPaper(listOf(ScanPaper.A4, ScanPaper.LETTER)))
        assertNull(scanSharedPaper(listOf(ScanPaper.A4, ScanPaper.A4, ScanPaper.CUSTOM, ScanPaper.CUSTOM)))
        assertEquals(ScanPaper.A4, scanSharedPaper(listOf(ScanPaper.A4, ScanPaper.A4, ScanPaper.CUSTOM)))
        // a page not made yet doesn't count either way
        assertEquals(ScanPaper.A4, scanSharedPaper(listOf(ScanPaper.A4, null, ScanPaper.A4, null)))
        // a scan of cards has the card's shape
        assertEquals(ScanPaper.ID_CARD, scanSharedPaper(listOf(ScanPaper.ID_CARD, ScanPaper.ID_CARD)))
        // no standard shape is no paper to share
        assertNull(scanSharedPaper(listOf(ScanPaper.CUSTOM, ScanPaper.CUSTOM, ScanPaper.CUSTOM)))
        assertNull(scanSharedPaper(emptyList()))
    }

    @Test
    fun theWholePhotoHasNoOutlineOfItsOwn() {
        assertTrue(scanHasOutline(quad, quad, null))
        // no page found: the whole photo it started as, until the person sets corners
        assertFalse(scanHasOutline(ScanPage.WHOLE, null, ScanPage.WHOLE))
        assertFalse(scanHasOutline(ScanPage.WHOLE, null, null))
        assertTrue(scanHasOutline(quad, null, ScanPage.WHOLE))
    }

    @Test
    fun aCardOnlyFromASureOutline() {
        assertTrue(scanCardPaper(cardSure = true, unsure = false))
        assertFalse(scanCardPaper(cardSure = true, unsure = true))
        // off: any card-shaped page is a card, as before
        assertTrue(scanCardPaper(cardSure = false, unsure = true))
    }

    @Test
    fun theLogSaysWhatThePageWasAskedWith() {
        val asked = scanPaperRuleDiag(ScanPaper.A4, false, ScanPaper.CUSTOM).toMap()
        assertEquals("a4", asked["paper_hint"])
        assertEquals(false, asked["card_paper"])
        assertEquals("custom", asked["paper_size"])
        val none = scanPaperRuleDiag(null, true, null).toMap()
        assertEquals("none", none["paper_hint"])
        assertEquals(true, none["card_paper"])
        assertFalse("paper_size" in none)
        assertEquals("id_card", scanPaperRuleDiag(ScanPaper.ID_CARD, true, null).toMap()["paper_hint"])
    }

    @Test
    fun theKeptPageSaysItToo() {
        val json = JSONObject(scanTestPageJson("t", true, quad, quad, null, "auto", 60, false, ScanLab(), null, null, paperHint = "a4", cardPaper = false))
        assertEquals("a4", json.getString("paper_hint"))
        assertFalse(json.getBoolean("card_paper"))
        val unknown = JSONObject(scanTestPageJson("t", true, quad, quad, null, "auto", 60, false, ScanLab(), null, null))
        assertTrue(unknown.isNull("paper_hint") && unknown.isNull("card_paper"))
    }

    @Test
    fun theTestingOptionsAreOnAndRemembered() {
        assertTrue(ScanLab().paperShared)
        assertTrue(ScanLab().cardSure)
        val off = ScanLab(paperShared = false, cardSure = false)
        val back = ScanLab.decode(off.encode())
        assertFalse(back.paperShared)
        assertFalse(back.cardSure)
        assertFalse(off.asDesigned)
        assertEquals(false, off.diag().toMap()["paper_shared"])
        assertEquals(false, off.json().getBoolean("card_sure"))
        // a phone that saved its options before #759 has both on
        val before = ScanLab.decode("keep=0;snap=1")
        assertTrue(before.paperShared && before.cardSure)
    }
}
