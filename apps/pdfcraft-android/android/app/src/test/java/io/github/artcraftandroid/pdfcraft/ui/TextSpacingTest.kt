package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.LETTER_SPACING_MAX
import io.github.artcraftandroid.pdfcraft.ui.viewer.LINE_SPACING_MIN
import io.github.artcraftandroid.pdfcraft.ui.viewer.spacingLabel
import io.github.artcraftandroid.pdfcraft.ui.viewer.stepLetterSpacing
import io.github.artcraftandroid.pdfcraft.ui.viewer.stepLineSpacing
import org.junit.Assert.assertEquals
import org.junit.Test

/** The Spacing panel's steppers (#167), as text_edit takes line_spacing and char_spacing. */
class TextSpacingTest {
    @Test
    fun lineSpacingStepsByATenthWithinRange() {
        assertEquals(1.3f, stepLineSpacing(1.2f, true), 0.0001f)
        assertEquals(1.1f, stepLineSpacing(1.2f, false), 0.0001f)
        // a value off the grid lands on it
        assertEquals(1.6f, stepLineSpacing(1.53f, true), 0.0001f)
        assertEquals(LINE_SPACING_MIN, stepLineSpacing(LINE_SPACING_MIN, false), 0.0001f)
        assertEquals(3f, stepLineSpacing(3f, true), 0.0001f)
    }

    @Test
    fun letterSpacingStepsByHalfAPointWithinRange() {
        assertEquals(0.5f, stepLetterSpacing(0f, true), 0.0001f)
        assertEquals(-0.5f, stepLetterSpacing(0f, false), 0.0001f)
        assertEquals(-2f, stepLetterSpacing(-2f, false), 0.0001f)
        assertEquals(LETTER_SPACING_MAX, stepLetterSpacing(LETTER_SPACING_MAX, true), 0.0001f)
    }

    @Test
    fun labelsDropANeedlessDecimal() {
        assertEquals("1", spacingLabel(1f))
        assertEquals("1.5", spacingLabel(1.5f))
        assertEquals("-0.5", spacingLabel(-0.5f))
    }
}
