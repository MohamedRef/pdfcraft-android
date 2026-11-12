package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.core.ScanModelTiming
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.engine.diagLine
import java.time.LocalDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The log line and message after timing a picked model file (#304). */
class ScanModelTimingTest {
    private val timing = ScanModelTiming(
        bytes = 183_260_711uL, sha25616 = "1d6a89d754fe1e58", inputShape = listOf(1u, 6u, 512u, 384u), inputs = 1u,
        outputShape = listOf(1u, 3u, 512u, 384u), outputFinite = true, threads = 0u, cores = 8u,
        loadMs = 50u, runsMs = listOf(9700u, 8800u), peakKbBefore = 120_000uL, peakKbAfter = 2_150_400uL,
    )

    @Test
    fun the_log_line_has_every_number_and_nothing_redacted() {
        val line = diagLine(LocalDateTime.of(2026, 11, 12, 14, 0), DiagArea.Scan, "model_timing", scanModelTimingDiag(timing).asList())
        for (part in listOf(
            "bytes=183260711", "sha=h1d6a89d754fe1e58", "inputs=1", "input_dims=4", "input_0=1", "input_1=6", "input_2=512", "input_3=384",
            "output_dims=4", "output_1=3", "output_finite=true", "threads=0", "cores=8", "load_ms=50", "run_1_ms=9700", "run_2_ms=8800",
            "peak_mb_before=117", "peak_mb_after=2100",
        )) {
            assertTrue("$part not in $line", " $part" in line)
        }
        assertFalse(line, "redacted" in line)
    }

    @Test
    fun memory_not_known_is_minus_one_and_a_question_mark() {
        val unknown = timing.copy(peakKbBefore = null, peakKbAfter = null)
        val line = diagLine(LocalDateTime.of(2026, 11, 12, 14, 0), DiagArea.Scan, "model_timing", scanModelTimingDiag(unknown).asList())
        assertTrue(line, " peak_mb_before=-1" in line && " peak_mb_after=-1" in line)
        assertEquals("?", scanModelTimingPeak(unknown))
        assertEquals("2100", scanModelTimingPeak(timing))
        assertEquals("9700, 8800", scanModelTimingRuns(timing))
    }
}
