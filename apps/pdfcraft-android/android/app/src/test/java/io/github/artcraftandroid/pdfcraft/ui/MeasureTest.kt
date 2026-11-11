package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.MeasureMode
import io.github.artcraftandroid.pdfcraft.ui.viewer.calibrationScaleArgs
import io.github.artcraftandroid.pdfcraft.ui.viewer.parseMeasureNumber
import io.github.artcraftandroid.pdfcraft.ui.viewer.ratioScaleArgs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MeasureTest {
    @Test
    fun planRatios() {
        // 1:50 in mm: 1 mm on paper is 50 mm
        assertEquals(mapOf("page" to 3, "page_distance" to 1.0, "page_unit" to "mm", "distance" to 50.0, "unit" to "mm"), ratioScaleArgs(2, 50.0, "mm"))
        // 1:100 in metres: 1 mm on paper is 0.1 m
        assertEquals(0.1, ratioScaleArgs(0, 100.0, "m")!!["distance"] as Double, 1e-12)
        assertEquals("mm", ratioScaleArgs(0, 100.0, "m")!!["page_unit"])
        // 1:96 in feet (1/8" = 1'): 1 in on paper is 8 ft
        assertEquals(8.0, ratioScaleArgs(0, 96.0, "ft")!!["distance"] as Double, 1e-12)
        assertNull(ratioScaleArgs(0, 0.0, "mm"))
        assertNull(ratioScaleArgs(0, 50.0, "furlong"))
    }

    @Test
    fun calibrationNeedsTwoDistinctPointsAndALength() {
        val args = calibrationScaleArgs(0, Offset(10f, 10f), Offset(110f, 10f), 2.5, "m")!!
        assertEquals(listOf(listOf(10.0, 10.0), listOf(110.0, 10.0)), args["points"])
        assertEquals(2.5, args["distance"])
        assertNull(calibrationScaleArgs(0, Offset(10f, 10f), Offset(10.2f, 10f), 2.5, "m"))
        assertNull(calibrationScaleArgs(0, Offset(10f, 10f), Offset(110f, 10f), -1.0, "m"))
    }

    @Test
    fun numbersAndModes() {
        assertEquals(2.5, parseMeasureNumber(" 2,5 ")!!, 0.0)
        assertNull(parseMeasureNumber("0"))
        assertNull(parseMeasureNumber("abc"))
        assertEquals(3, MeasureMode.Area.minPoints)
        assertEquals("measure_perimeter", MeasureMode.Perimeter.tool)
    }
}
