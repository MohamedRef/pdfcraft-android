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

    @Test
    fun combineCountsFilesThenNamesItsSteps() {
        assertEquals(JobLine(R.string.msg_busy_combining_reading, listOf(1, 4)), combineLine("reading", 0, 4))
        assertEquals(JobLine(R.string.msg_busy_combining_opening, listOf(4, 4)), combineLine("opening", 3, 4))
        assertEquals(JobLine(R.string.msg_busy_combining_pages), combineLine("combining", 0, 0))
        assertEquals(JobLine(R.string.msg_busy_combining_writing), combineLine("writing", 0, 0))
        assertEquals(JobLine(R.string.msg_busy_combining_writing), combineLine("saving", 0, 0))
        assertNull(combineLine("opening", 0, 0))
        assertNull(combineLine("something_new", 0, 0))
    }

    @Test
    fun createCountsFilesThenWrites() {
        assertEquals(JobLine(R.string.msg_busy_creating_file, listOf(2, 3)), createLine("files", 1, 3))
        assertEquals(JobLine(R.string.msg_busy_combining_writing), createLine("writing", 0, 0))
        assertNull(createLine("files", 0, 0))
        assertNull(createLine("something_new", 0, 0))
    }

    /** #646: the steps the engine now tells inside a stage keep the same lines, whatever their counts. */
    @Test
    fun countsInsideAStageKeepItsLine() {
        assertEquals(JobLine(R.string.msg_busy_extracting_copying), extractLine("copying", 300, 869))
        assertEquals(JobLine(R.string.msg_busy_extracting_writing), extractLine("writing", 41_000, 96_000))
        assertEquals(JobLine(R.string.msg_busy_combining_pages), combineLine("combining", 512, 1738))
        assertEquals(JobLine(R.string.msg_busy_combining_writing), combineLine("writing", 41_000, 96_000))
        assertEquals(JobLine(R.string.msg_busy_combining_writing), createLine("writing", 41_000, 96_000))
        assertEquals(JobLine(R.string.compare_busy_comparing), compareLine("comparing", 120_000, 400_000))
        assertEquals(JobLine(R.string.print_busy_sheet, listOf(435, 435)), printLine("sheets", 435, 435))
    }

    /** #646: Set a password's bar is three steps, and the saving moves through its own third. */
    @Test
    fun protectBarMovesThroughTheSaving() {
        assertEquals(0 to 300, protectBar(0, 0))
        assertEquals(100 to 300, protectBar(1, 0))
        assertEquals(200 to 300, protectBar(2, 0))
        assertEquals(245 to 300, protectBar(2, 45))
        assertEquals(290 to 300, protectBar(2, 90))
        // never past its third, nor past the end
        assertEquals(300 to 300, protectBar(2, 250))
        assertEquals(200 to 300, protectBar(2, -5))
        assertEquals(300 to 300, protectBar(3, 80))
    }

    /** #646: enhancing scanned pages counts its images from one. */
    @Test
    fun scanEnhanceCountsImagesFromOne() {
        assertEquals(JobLine(R.string.scan_enhance_busy_image, listOf(1, 12)), scanEnhanceLine("images", 0, 12))
        assertEquals(JobLine(R.string.scan_enhance_busy_image, listOf(12, 12)), scanEnhanceLine("images", 11, 12))
        assertEquals(JobLine(R.string.scan_enhance_busy_image, listOf(12, 12)), scanEnhanceLine("applying", 12, 12))
        // no scanned image on the pages: the job's own message stays
        assertNull(scanEnhanceLine("images", 0, 0))
        assertNull(scanEnhanceLine("applying", 0, 0))
        assertNull(scanEnhanceLine("something_new", 1, 4))
    }
}
