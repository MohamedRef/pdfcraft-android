package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.OcrOptions
import io.github.artcraftandroid.pdfcraft.ui.viewer.OcrPages
import io.github.artcraftandroid.pdfcraft.ui.viewer.parsePageRange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Recognize text's options (#166), as ocr_recognize takes them. */
class OcrOptionsTest {
    @Test
    fun pageRangesAreSortedUniqueAndClamped() {
        assertEquals(listOf(1, 2, 3, 5), parsePageRange("5, 1-3, 2", 10))
        assertEquals(listOf(8, 9, 10), parsePageRange("8-", 10))
        assertEquals(listOf(1, 2), parsePageRange("-2", 10))
        assertEquals(listOf(9, 10), parsePageRange("9-20", 10))
        assertNull(parsePageRange("", 10))
        assertNull(parsePageRange("0", 10))
        assertNull(parsePageRange("3-1", 10))
        assertNull(parsePageRange("abc", 10))
        assertNull(parsePageRange("-", 10))
        assertNull(parsePageRange("11", 10))
    }

    @Test
    fun optionsMapToTheEngineFlags() {
        val o = OcrOptions(straighten = true, cleanUp = false, redo = true)
        val a = o.recognizeArgs(current = 4, pageCount = 10, out = null)!!
        assertEquals(true, a["auto_rotate"])
        assertEquals(true, a["deskew"])
        assertEquals(false, a["denoise"])
        assertEquals(false, a["binarize"])
        assertEquals(true, a["force_ocr"])
        assertEquals("en", a["language"])
        assertFalse("pages" in a)
        assertFalse("output_mode" in a)
    }

    @Test
    fun pagesAndEditableOutput() {
        assertEquals(listOf(5), OcrOptions(pages = OcrPages.Current).recognizeArgs(4, 10, null)!!["pages"])
        assertEquals(listOf(2, 3), OcrOptions(pages = OcrPages.Range, range = "2-3").recognizeArgs(0, 10, null)!!["pages"])
        assertNull(OcrOptions(pages = OcrPages.Range, range = "x").recognizeArgs(0, 10, null))
        val e = OcrOptions(editable = true).recognizeArgs(0, 3, "/cache/out.pdf")!!
        assertEquals("editable", e["output_mode"])
        assertEquals("/cache/out.pdf", e["out"])
        assertNull(OcrOptions(editable = true).recognizeArgs(0, 3, null))
    }
}
