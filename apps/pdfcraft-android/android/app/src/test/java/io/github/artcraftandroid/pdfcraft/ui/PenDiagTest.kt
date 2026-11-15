package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagSink
import io.github.artcraftandroid.pdfcraft.ui.viewer.PalmSpot
import io.github.artcraftandroid.pdfcraft.ui.viewer.PaletteImportError
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenButtonClick
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDiag
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDownMode
import io.github.artcraftandroid.pdfcraft.ui.viewer.diagWord
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDials
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenDynamics
import io.github.artcraftandroid.pdfcraft.ui.viewer.PenStrokeStats
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureRange
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureSource
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStrokeFields
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import java.nio.file.Files
import java.time.LocalDateTime

class PenDiagTest {
    private fun recorded(block: () -> Unit): List<String> {
        val dir = Files.createTempDirectory("pendiag").toFile()
        try {
            val sink = DiagSink(dir)
            Diag.sink = sink
            Diag.now = { LocalDateTime.of(2026, 11, 11, 21, 0, 0) }
            block()
            sink.flush()
            return sink.lines().map { it.substringAfter("] ") }
        } finally {
            Diag.sink = null
            Diag.now = { LocalDateTime.now() }
            dir.deleteRecursively()
        }
    }

    @Test
    fun palmTouchesAreSummedIntoOneLineAndReset() {
        val lines = recorded {
            PenDiag.palmRejected(PalmSpot.Page, 120f, left = true)
            PenDiag.palmRejected(PalmSpot.Page, 120f, left = true)
            PenDiag.palmRejected(PalmSpot.Toolbar, 120f, left = true)
            PenDiag.flushPalm()
            PenDiag.flushPalm() // nothing new: no second line
        }
        assertEquals(listOf("palm_guard page_touches=2 toolbar_touches=1 guard_dp=120 hand=left"), lines)
    }

    @Test
    fun palmTouchesAreNotCountedWhileTheLogIsOff() {
        Diag.sink = null
        PenDiag.palmRejected(PalmSpot.Page, 90f, left = false)
        assertEquals(emptyList<String>(), recorded { PenDiag.flushPalm() })
    }

    @Test
    fun penModeIsTakenOnce() {
        recorded {
            PenDiag.penDown(PenDownMode.Highlight)
            assertEquals(PenDownMode.Highlight, PenDiag.takePenMode())
            assertNull(PenDiag.takePenMode())
        }
    }

    @Test
    fun penToolLinesHoldOnlyWordsAndNumbers() {
        val lines = recorded {
            PenDiag.penButtonClick(PenButtonClick.TogglePens)
            PenDiag.gesture("scratch_out", "erased", drafts = 2, saved = 1)
            PenDiag.gestureUndo(markupUndone = true)
            PenDiag.guideStroke("protractor_ray", 38.6f, 12)
            PenDiag.zoomBoxAdvance(newLine = true, newPage = false)
            PenDiag.zoomBoxRender(41, 6.5f, 800, 300, ok = true)
            PenDiag.eyedropper(found = false)
            PenDiag.palette("import", PaletteImportError.NewerVersion.diagWord())
        }
        assertEquals(
            listOf(
                "pen_button_click action=toggle",
                "pen_gesture kind=scratch_out outcome=erased drafts=2 saved=1",
                "gesture_undo markup_undone=true",
                "guide_stroke guide=protractor_ray angle_deg=39 points=12",
                "zoom_box_advance new_line=true new_page=false",
                "zoom_box_render ms=41 scale=6.50 px=240000 ok=true",
                "eyedropper found=false",
                "palette action=import outcome=newer_version colors=0",
            ),
            lines,
        )
        assertFalse(lines.any { Diag.REDACTED in it })
    }

    /** #569: the pen model's fields on the stroke line are numbers and fixed words only. */
    @Test
    fun inkStrokeFieldsHoldOnlyWordsAndNumbers() {
        val dials = PenDials.default(InkStyle.Fountain)
        val stats = PenStrokeStats(InkStyle.Fountain, PressureSource.Pen, 4f, dials, 1f)
        val dyn = PenDynamics(dials, PressureSource.Pen, PressureRange(), 4f, stats)
        for (i in 0 until 50) dyn.next(androidx.compose.ui.geometry.Offset(i * 2f, 0f), i * 8L, 0.2f + i / 100f)
        val lines = recorded { Diag.event(DiagArea.Ink, "stroke", *inkStrokeFields(stats, -12f, 48.6f, true)) }
        val line = lines.single()
        assertFalse(Diag.REDACTED in line)
        for (part in listOf("style=fountain", "pressure_source=pen", "tilt_min=-12", "tilt_max=49", "tilt_reported=true", "taper_start_pt=10.00", "taper_end_pt=12.00", "stabilisation=0")) {
            org.junit.Assert.assertTrue("$part in $line", part in line)
        }
        // a gesture that drew no ink adds nothing
        assertEquals(0, inkStrokeFields(null, Float.NaN, Float.NaN, null).size)
    }
}
