package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #634: what the busy dialog says for each step Split and Extract pages report. */
class JobStepsTest {
    @Test
    fun splitCountsPagesMeasuredAndPartsFromOne() {
        assertEquals(JobLine(R.string.msg_busy_splitting_measuring, listOf(1, 869)), splitLine("measuring", 0, 869))
        assertEquals(JobLine(R.string.msg_busy_splitting_part, listOf(3, 12)), splitLine("parts", 2, 12))
        // never past the total
        assertEquals(JobLine(R.string.msg_busy_splitting_part, listOf(12, 12)), splitLine("parts", 12, 12))
    }

    @Test
    fun splitWithoutACountKeepsItsOwnMessage() {
        assertNull(splitLine("parts", 0, 0))
        assertNull(splitLine("something_new", 1, 4))
    }

    @Test
    fun extractNamesItsStepsAndCountsSeparatePages() {
        assertEquals(JobLine(R.string.msg_busy_extracting_copying), extractLine("copying", 0, 0))
        assertEquals(JobLine(R.string.msg_busy_extracting_writing), extractLine("writing", 0, 0))
        assertEquals(JobLine(R.string.msg_busy_extracting_checking), extractLine("checking", 0, 0))
        assertEquals(JobLine(R.string.msg_busy_extracting_page, listOf(2, 5)), extractLine("pages", 1, 5))
        assertNull(extractLine("pages", 0, 0))
        assertNull(extractLine("something_new", 0, 0))
    }

    @Test
    fun exportCountsPagesThenWrites() {
        assertEquals(JobLine(R.string.msg_busy_exporting_doc_page, listOf(1, 40)), exportLine("pages", 0, 40))
        assertEquals(JobLine(R.string.msg_busy_exporting_doc_page, listOf(40, 40)), exportLine("pages", 40, 40))
        assertEquals(JobLine(R.string.msg_busy_exporting_writing), exportLine("writing", 0, 0))
        assertNull(exportLine("pages", 0, 0))
        assertNull(exportLine("something_new", 0, 0))
    }

    @Test
    fun printCountsPicturesAndSheets() {
        assertEquals(JobLine(R.string.print_busy_picture, listOf(1, 3)), printLine("pictures", 0, 3))
        assertEquals(JobLine(R.string.print_busy_sheet, listOf(2, 2)), printLine("sheets", 1, 2))
        assertEquals(JobLine(R.string.print_busy_writing), printLine("writing", 0, 0))
        assertNull(printLine("sheets", 0, 0))
        assertNull(printLine("something_new", 0, 0))
    }

    @Test
    fun compareCountsBothFilesThenTheVisualPages() {
        assertEquals(JobLine(R.string.compare_busy_reading, listOf(5, 20)), compareLine("reading", 4, 20))
        assertEquals(JobLine(R.string.compare_busy_comparing), compareLine("comparing", 20, 20))
        assertEquals(JobLine(R.string.compare_busy_visual, listOf(10, 10)), compareLine("visual", 10, 10))
        assertNull(compareLine("visual", 0, 0))
        assertNull(compareLine("something_new", 0, 0))
    }
}
