package io.github.artcraftandroid.pdfcraft.ui

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StandardsTest {
    @Test fun pdfaReportAsTheEngineWritesIt() {
        // pdfa_verify on a blank page, then after pdfa_convert (engine output, verbatim)
        val before = parsePdfaReport(JSONObject("""{"compliant":false,"declared":{"output_intents":[],"pdfa":null,"pdfua":null},"issues":[{"clause":"6.6.2.1","fixable":true,"message":"The document has no XMP metadata","page":null}],"level":"PDF/A-2b"}"""))
        assertFalse(before.compliant)
        assertNull(before.declared)
        assertEquals(listOf(PdfaIssue("6.6.2.1", "The document has no XMP metadata", null, true)), before.issues)
        val after = parsePdfaReport(JSONObject("""{"compliant":true,"declared":{"output_intents":["sRGB IEC61966-2.1"],"pdfa":"PDF/A-2b","pdfua":null},"issues":[],"level":"PDF/A-2b"}"""))
        assertTrue(after.compliant)
        assertEquals("PDF/A-2b", after.declared)
        assertEquals(3, parsePdfaReport(JSONObject("""{"issues":[{"clause":"6.2","message":"m","page":3,"fixable":false}]}""")).issues.single().page)
    }

    @Test fun printFindings() {
        val f = parsePrintFindings(JSONObject("""{"findings":[{"id":"page-trim","message":"No page has a TrimBox, so bleed can't be measured.","page":null,"severity":"info"}]}"""))
        assertEquals(listOf(PrintFinding("info", "No page has a TrimBox, so bleed can't be measured.", null)), f)
        assertTrue(parsePrintFindings(JSONObject("{}")).isEmpty())
    }

    @Test fun hairlineLimits() {
        assertTrue(HairlineFix().valid)
        assertFalse(HairlineFix(width = 0.0).valid)
        assertFalse(HairlineFix(width = 11.0).valid)
        assertFalse(HairlineFix(narrowerThan = -1.0).valid)
        assertEquals(mapOf("narrower_than" to 0.216, "width" to 0.72, "pages" to listOf(2)), HairlineFix().args(listOf(2)))
    }

    @Test fun printOptions() {
        assertTrue(PrintOptions().isDefault)
        assertFalse(PrintOptions(reverse = true).isDefault)
        assertEquals(mapOf("path" to "/o.pdf", "layout" to "fit", "comments_forms" to "document-and-markups"), PrintOptions().args("/o.pdf"))
        val nUp = PrintOptions(pages = " 1-3 ", subset = PrintSubset.Odd, layout = PrintLayout.Multiple, perSheet = 5, comments = false).args("/o.pdf")
        assertEquals("1-3", nUp["pages"])
        assertEquals("odd", nUp["subset"])
        assertEquals(2, nUp["per_sheet"])
        assertEquals("document", nUp["comments_forms"])
        val booklet = PrintOptions(layout = PrintLayout.Booklet, bookletBinding = "right").args("/o.pdf")
        assertEquals("right", booklet["binding"])
        assertFalse(booklet.containsKey("per_sheet"))
    }
}
