package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PdfTextStyleTest {
    private fun style(font: String = "عنوان", size: Double = 18.25) =
        PdfTextStyle(font, size, bold = true, italic = false, lines = 8, pages = 3, sample = "Private heading sample")

    @Test fun nativeArgumentsKeepOnlyExactMatchingFieldsAndSelectedLevelOrder() {
        val top = style()
        val nested = style("Helvetica", 12.0)
        assertEquals(listOf(
            mapOf("font" to "عنوان", "size" to 18.25, "bold" to true, "italic" to false),
            mapOf("font" to "Helvetica", "size" to 12.0, "bold" to true, "italic" to false),
        ), headingStyleArguments(listOf(top, nested)))
    }

    @Test fun rejectsEmptyOrExcessLevelsBeforeMutation() {
        assertThrows(IllegalArgumentException::class.java) { headingStyleArguments(emptyList()) }
        assertThrows(IllegalArgumentException::class.java) { headingStyleArguments(List(10) { style() }) }
        assertEquals(9, headingStyleArguments(List(9) { style() }).size)
    }

    @Test fun rejectsNonFiniteAndNonPositiveSizesAndMissingFont() {
        for (size in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertThrows(IllegalArgumentException::class.java) { style(size = size) }
        }
        assertThrows(IllegalArgumentException::class.java) { style(font = " ") }
    }
}
