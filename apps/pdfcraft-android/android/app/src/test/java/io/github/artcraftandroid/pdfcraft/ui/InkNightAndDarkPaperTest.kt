package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import io.github.artcraftandroid.pdfcraft.engine.NotebookColor
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkLook
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkPenSettings
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkSaveStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.NIGHT_INVERT_MATRIX
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkColorOnPaper
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkHighlighterPaint
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkSaveCommands
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkScreenColor
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStyleOpacity
import io.github.artcraftandroid.pdfcraft.ui.viewer.nightColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #610: a draft looks as it will once saved in Night Mode, and on dark paper the marker and the
 * default pens show. Colours are worked out as the screen does it (source over, Darken, Lighten,
 * Night Mode's matrix); nothing here is read back from a screen.
 */
class InkNightAndDarkPaperTest {
    private data class Rgb(val r: Float, val g: Float, val b: Float) {
        fun map(o: Rgb, f: (Float, Float) -> Float) = Rgb(f(r, o.r), f(g, o.g), f(b, o.b))
    }

    private fun rgb(c: Color) = Rgb(c.red, c.green, c.blue)
    private fun over(src: Color, dst: Rgb) = rgb(src).map(dst) { s, d -> s * src.alpha + d * (1f - src.alpha) }
    private fun blend(src: Color, dst: Rgb, mode: BlendMode) = when (mode) {
        BlendMode.Darken -> rgb(src).map(dst) { s, d -> minOf(s, d) }
        BlendMode.Lighten -> rgb(src).map(dst) { s, d -> maxOf(s, d) }
        else -> over(src, dst)
    }

    /** Night Mode as the page's image is drawn: [NIGHT_INVERT_MATRIX] on one pixel. */
    private fun night(c: Rgb): Rgb {
        val m = NIGHT_INVERT_MATRIX
        fun row(i: Int) = ((m[i * 5] * c.r + m[i * 5 + 1] * c.g + m[i * 5 + 2] * c.b) * 255f + m[i * 5 + 4]) / 255f
        return Rgb(row(0), row(1), row(2))
    }

    private fun assertSame(what: String, a: Rgb, b: Rgb, tolerance: Float = 1.5f / 255f) {
        assertTrue("$what: $a and $b", kotlin.math.abs(a.r - b.r) <= tolerance && kotlin.math.abs(a.g - b.g) <= tolerance && kotlin.math.abs(a.b - b.b) <= tolerance)
    }

    private val white = Rgb(1f, 1f, 1f)
    private val darkPaper = NotebookColor.Dark.paper.let { Rgb(it.first.toFloat(), it.second.toFloat(), it.third.toFloat()) }
    private val nightOn = InkLook(night = true)
    private val pens = InkStyle.entries.filter { it != InkStyle.Highlighter }

    @Test fun nightColorIsThePagesInversion() {
        for (argb in listOf(0xFF000000, 0xFFFFFFFF, 0xFF0000FF, 0xFFE52237, 0xFF444444, 0xFFFFD100)) {
            val c = Color(argb.toInt())
            assertSame("#%06X".format(argb and 0xFFFFFF), night(rgb(c)), rgb(nightColor(c)))
        }
        assertEquals(0.4f, nightColor(Color(1f, 0f, 0f, 0.4f)).alpha, 1e-6f)
        assertEquals(Color.Red, inkScreenColor(Color.Red, InkLook.Plain))
    }

    @Test fun aDraftAndTheSameStrokeSavedAreOneColourInNightMode() {
        // saved: the ink is part of the page's image, which Night Mode inverts.
        // draft: painted over the inverted page with inkScreenColor.
        for (paper in listOf(white, Rgb(0.984f, 0.965f, 0.914f), Rgb(0.2f, 0.4f, 0.6f))) for (style in pens) for (opacity in listOf(1f, 0.6f, 0.25f)) {
            val ink = Color(InkPenSettings.default(style).argb).copy(alpha = opacity)
            val saved = night(over(ink, paper))
            val draft = over(inkScreenColor(ink, nightOn), night(paper))
            assertSame("$style at $opacity", saved, draft)
        }
        // black on white paper used to be written black on the black page
        assertSame("black ink", white, over(inkScreenColor(Color.Black, nightOn), night(white)))
    }

    @Test fun aDraftMarkerAndTheSameMarkerSavedAreOneColourInNightMode() {
        val marker = Color(InkPenSettings.default(InkStyle.Highlighter).argb)
        val opacity = inkStyleOpacity(1f, InkStyle.Highlighter)
        // over the paper, and over black text on it, which the marker leaves black (white in Night Mode)
        for (under in listOf(white, Rgb(0f, 0f, 0f))) {
            val (paint, mode) = inkHighlighterPaint(marker, opacity, InkLook.Plain)
            val saved = night(blend(paint, under, mode))
            val (nightPaint, nightMode) = inkHighlighterPaint(marker, opacity, nightOn)
            assertEquals(BlendMode.Lighten, nightMode)
            assertSame("marker over $under", saved, blend(nightPaint, night(under), nightMode))
        }
        // and on dark paper, where the saved marker lightens: in Night Mode the draft darkens
        val onDark = InkLook(night = true, darkPaper = true)
        for (under in listOf(darkPaper, white)) {
            val (paint, mode) = inkHighlighterPaint(marker, opacity, InkLook(darkPaper = true))
            val (nightPaint, nightMode) = inkHighlighterPaint(marker, opacity, onDark)
            assertEquals(BlendMode.Darken, nightMode)
            // the mean of a per-channel maximum is not the maximum of the means: the draft's grey is
            // within 6 % of the saved one over the paper (0.76 and 0.71 for the yellow marker)
            assertSame("marker on dark over $under", night(blend(paint, under, mode)), blend(nightPaint, night(under), nightMode), 0.06f)
        }
    }

    @Test fun theMarkerShowsOnDarkPaperAndLeavesLightInkLight() {
        val marker = Color(InkPenSettings.default(InkStyle.Highlighter).argb)
        val opacity = inkStyleOpacity(1f, InkStyle.Highlighter)
        // as it was: Darken keeps the darker colour, so the paper wins everywhere
        val (old, oldMode) = inkHighlighterPaint(marker, opacity, InkLook.Plain)
        assertEquals(darkPaper, blend(old, darkPaper, oldMode))
        // on dark paper: Lighten
        val (paint, mode) = inkHighlighterPaint(marker, opacity, InkLook(darkPaper = true))
        assertEquals(BlendMode.Lighten, mode)
        val band = blend(paint, darkPaper, mode)
        assertTrue("the marker's band $band on the paper $darkPaper", band.r - darkPaper.r > 0.2f && band.g - darkPaper.g > 0.15f)
        // yellow stays yellow: more red and green than blue
        assertTrue(band.r > band.b && band.g > band.b)
        // light ink under it keeps its lightness, channel by channel
        for (ink in listOf(white, Rgb(0.82f, 0.82f, 0.82f), Rgb(0.54f, 0.71f, 1f))) {
            val covered = blend(paint, ink, mode)
            assertTrue(covered.r >= ink.r && covered.g >= ink.g && covered.b >= ink.b)
        }
        // a stroke crossing itself lays the colour down once
        assertEquals(band, blend(paint, band, mode))
        // on light paper nothing changes
        assertEquals(BlendMode.Darken, oldMode)
    }

    private fun luminance(c: Rgb): Float {
        fun lin(v: Float) = if (v <= 0.03928f) v / 12.92f else Math.pow((v + 0.055) / 1.055, 2.4).toFloat()
        return 0.2126f * lin(c.r) + 0.7152f * lin(c.g) + 0.0722f * lin(c.b)
    }

    private fun contrast(a: Rgb, b: Rgb): Float {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05f) / (lo + 0.05f)
    }

    @Test fun inADarkNotebookEveryPensFirstStrokeShows() {
        for (style in pens) {
            val first = rgb(Color(inkColorOnPaper(InkPenSettings.default(style).argb, style, darkPaper = true)))
            assertTrue("$style: ${contrast(first, darkPaper)} to 1", contrast(first, darkPaper) >= 4.5f)
            // and as they were, three of the four did not
            assertEquals(InkPenSettings.default(style).argb, inkColorOnPaper(InkPenSettings.default(style).argb, style, darkPaper = false))
        }
        assertTrue(contrast(rgb(Color(InkPenSettings.default(InkStyle.Ballpoint).argb)), darkPaper) < 1.5f)
        // a colour the owner chose is his, on any paper
        val chosen = 0xFF123456.toInt()
        for (style in InkStyle.entries) assertEquals(chosen, inkColorOnPaper(chosen, style, darkPaper = true))
    }

    @Test fun aMarkerOnDarkPaperIsSavedForIt() {
        fun hex(argb: Int) = "#%06X".format(argb and 0xFFFFFF)
        val line = List(4) { Offset(it * 10f, 10f) }
        val scales = List(4) { 1f }
        fun saved(style: InkStyle, onDark: Boolean) =
            inkSaveCommands(listOf(InkSaveStroke(0, InkAppearance(0xFFFFD100.toInt(), 0.4f, 6f, null, style, onDark), line, scales, false)), ::hex).single()
        assertEquals("dark", saved(InkStyle.Highlighter, true)["backdrop"])
        assertFalse(saved(InkStyle.Highlighter, false).containsKey("backdrop"))
        assertFalse(saved(InkStyle.Ballpoint, true).containsKey("backdrop"))
    }
}
