package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #445: the crop screen numbers pages from 1, and a failed model timing says why in the log. */
class ScanLogNumbersTest {
    @Test
    fun cropLinesNumberPagesFromOne() {
        // page 5 of the scan is list position 4, as `preview index=5` and `flatten_page index=5` say
        assertEquals(5, scanLogPageNumber(4))
        assertEquals(1, scanLogPageNumber(0))
    }

    @Test
    fun aFailedTimingLogsItsReasonStageAndTime() {
        val e = ScanModelTimingFailed("not_fixed", "time", "the model's input size is not fixed (height); export it at a fixed size", 412)
        val fields = scanModelTimingFailedDiag(e, 1_250).toMap()
        assertEquals("not_fixed", fields["reason"])
        assertEquals("time", fields["stage"])
        assertEquals(1_250L, fields["ms"])
        assertEquals(412L, fields["native_ms"])
        // the message on screen never goes into the log
        assertTrue(fields.values.none { it is String && "export" in it })
    }

    @Test
    fun anUnknownReasonIsLoggedAsOther() {
        val fields = scanModelTimingFailedDiag(ScanModelTimingFailed("Something Else", "time", "", 0), 0).toMap()
        assertEquals("other", fields["reason"])
    }

    @Test
    fun aFailedCopySaysWhetherTheFileWasTooLarge() {
        assertEquals("too_large", scanModelCopyReason(java.io.IOException(SCAN_MODEL_COPY_TOO_LARGE)))
        assertEquals("unreadable", scanModelCopyReason(java.io.IOException("unreadable")))
        assertEquals("unreadable", scanModelCopyReason(java.io.FileNotFoundException()))
    }

    @Test
    fun everyIssueReasonIsKnown() {
        for (word in listOf("not_a_model", "not_fixed", "no_input", "input_too_large", "no_output", "run_failed", "too_large", "outside_folder", "unreadable")) {
            assertTrue(word, word in SCAN_MODEL_TIMING_REASONS)
        }
    }
}
