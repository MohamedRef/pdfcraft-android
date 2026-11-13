package io.github.artcraftandroid.pdfcraft.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The book page rule (#442): the testing option, the request, the log and the page file. */
class ScanStrictBookTest {
    private val corners = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun theTestingOptionIsOnByDefaultAndSurvivesBeingSaved() {
        val lab = ScanLab()
        assertTrue(lab.strictBook)
        assertFalse(lab.copy(strictBook = false).asDesigned)
        assertEquals(ScanLab(strictBook = false), ScanLab.decode(ScanLab(strictBook = false).encode()))
        // saved before the option existed: on
        assertTrue(ScanLab.decode("keep=1;finder=auto;strict_gutter=0;strict_photo=0").strictBook)
        assertEquals(false, ScanLab(strictBook = false).diag().toMap()["strict_book"])
        assertEquals(false, ScanLab(strictBook = false).json().getBoolean("strict_book"))
    }

    @Test
    fun onlyTwoOppositeCurvedSidesCountAsOpposite() {
        assertFalse(scanCurvedOpposite(listOf(true, false, false, false)))
        assertFalse(scanCurvedOpposite(listOf(true, true, false, false)))
        assertTrue(scanCurvedOpposite(listOf(true, false, true, false)))
        assertTrue(scanCurvedOpposite(listOf(false, true, false, true)))
        assertFalse(scanCurvedOpposite(emptyList()))
    }

    @Test
    fun theRequestCarriesTheRuleAndItsInputs() {
        val plain = scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(), "", curvedSides = 1)
        assertTrue(plain.strictBook)
        assertEquals(1, plain.curvedSides.toInt())
        assertFalse(plain.curvedOpposite || plain.bookMode)
        val book = scanPageRequest("/a", corners, ScanFilter.CleanScan, false, ScanLab(strictBook = false), "", curvedSides = 2, curvedOpposite = true, bookMode = true)
        assertFalse(book.strictBook)
        assertTrue(book.curvedOpposite && book.bookMode)
        // the other rules' options are their own
        assertTrue(book.strictGutter && book.strictPhoto)
        val page = ScanPreviewKey("/a", corners.toList(), ScanFilter.CleanScan, false, ScanLab())
        assertFalse(page == page.copy(lab = ScanLab(strictBook = false)))
    }

    @Test
    fun aKeptPageSaysWhatBothRulesReadAndWhichSidesWereCurved() {
        val kind = ScanTestKind(
            "document", false, 60, 30, false, true, false, 1.414, 0.05, 0.0, 0.0, 0.3, 0.42, false, false,
            bookLoose = true, curvedOpposite = false, bookMode = false,
        )
        val finding = ScanFinding("model", 0.9, 0, 1, 4, 1, curvedOpposite = false)
        val page = JSONObject(scanTestPageJson("1.0 (1)", true, corners, corners, finding, "auto", 60, false, ScanLab(), "page-01.out.jpg", null, kind = kind))
        val type = page.getJSONObject("document_type")
        assertEquals("document", type.getString("preset"))
        assertTrue(type.getBoolean("suggest_flatten"))
        assertTrue(type.getBoolean("book_loose"))
        assertFalse(type.getBoolean("curved_opposite"))
        assertFalse(type.getBoolean("book_mode"))
        assertEquals(1, page.getJSONObject("finding").getInt("curved_sides"))
        assertFalse(page.getJSONObject("finding").getBoolean("curved_opposite"))
        assertTrue(page.getJSONObject("options").getBoolean("strict_book"))
    }
}
