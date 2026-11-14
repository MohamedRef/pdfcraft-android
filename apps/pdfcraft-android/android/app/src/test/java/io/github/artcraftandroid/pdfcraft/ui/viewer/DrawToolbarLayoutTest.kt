package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Draw's bar laid out at 320, 360, 384 and 600 dp with 0, 3 and 12 favourites (#522): every
 * button of both rows, and of the Shapes and Guides sheets, is at least 48 dp wide and can be
 * brought into view, and the favourites never cover Color, Settings, Shapes or Guides. Before
 * #522 the second row was a fixed eight slots wide, so its ninth button and every one after it
 * was laid out 0 dp wide.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "en-w640dp-h900dp-mdpi")
class DrawToolbarLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int, vararg args: Any) = context.getString(id, *args)

    private val widths = listOf(320.dp, 360.dp, 384.dp, 600.dp)
    private val favouriteCounts = listOf(0, 3, 12)

    private fun bounds(node: SemanticsNodeInteraction): Rect = with(rule.density) {
        val b = node.getUnclippedBoundsInRoot()
        Rect(b.left.toPx(), b.top.toPx(), b.right.toPx(), b.bottom.toPx())
    }

    /** [scrolls]: the button sits in a scrolling row, brought into view first as a finger would. */
    private fun assertReachable(label: String, width: Dp, within: Rect? = null, scrolls: Boolean = false) {
        val node = rule.onNodeWithContentDescription(label)
        if (scrolls) {
            node.performScrollTo()
            rule.waitForIdle()
        }
        node.assertIsDisplayed()
        val b = bounds(node)
        val slot = with(rule.density) { 48.dp.toPx() }
        val barRight = with(rule.density) { width.toPx() }
        val where = "\"$label\" at ${width.value.toInt()} dp"
        assertTrue("$where is ${b.width / rule.density.density} dp wide", b.width >= slot - 0.5f)
        assertTrue("$where lies outside the bar: $b", b.left >= -0.5f && b.right <= barRight + 0.5f)
        if (within != null) {
            assertTrue("$where lies outside its part $within: $b", b.left >= within.left - 0.5f && b.right <= within.right + 0.5f)
        }
    }

    @Test
    fun everyDrawButtonHasItsWidthAndCanBeReached() {
        val prefs = ToolPrefs(context)
        val ctl = ViewerController()
        var width by mutableStateOf(widths.first())
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) {
                Column {
                    Box(Modifier.requiredWidth(width).testTag("bar")) { DrawToolbarBar(ctl, prefs) }
                }
            }
        }
        val pens = InkStyle.entries.map { text(inkStyleLabel(it)) } + text(R.string.toolbar_eraser) + text(R.string.toolbar_ink_lasso)
        val fixed = listOf(
            text(R.string.toolbar_color_drawing), text(R.string.ink_pen_settings_title),
            text(R.string.ink_shapes), text(R.string.ink_guides_title),
        )
        for (w in widths) for (n in favouriteCounts) {
            width = w
            prefs.useFavourites(n)
            ctl.picker = Picker.None
            rule.waitForIdle()
            pens.forEach { assertReachable(it, w, scrolls = true) }
            fixed.forEach { assertReachable(it, w) }
            val part = bounds(rule.onNodeWithTag(DrawFavouritesTag))
            // The fixed buttons sit after the favourites' part, never under it.
            fixed.forEach { label ->
                val b = bounds(rule.onNodeWithContentDescription(label))
                assertTrue("\"$label\" overlaps the favourites at ${w.value} dp with $n", b.left >= part.right - 0.5f || b.right <= part.left + 0.5f)
            }
            repeat(n) { i -> assertReachable(text(R.string.ink_preset_slot, i + 1), w, within = part, scrolls = true) }
        }
        // Shapes and Guides open their sheets.
        rule.onNodeWithContentDescription(text(R.string.ink_shapes)).performClick()
        rule.waitForIdle()
        assertEquals(Picker.Shapes, ctl.picker)
        rule.onNodeWithContentDescription(text(R.string.ink_guides_title)).performClick()
        rule.waitForIdle()
        assertEquals(Picker.Guides, ctl.picker)
    }

    @Test
    fun shapesAndGuidesSheetsShowEveryToolAtEveryWidth() {
        val prefs = ToolPrefs(context)
        val ctl = ViewerController()
        var width by mutableStateOf(widths.first())
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) {
                Box(Modifier.requiredWidth(width)) {
                    Column {
                        DrawShapesPicker(ctl, prefs)
                        DrawGuidesPicker(ctl)
                    }
                }
            }
        }
        val tools = DrawShapes.map { text(it.label) } +
            listOf(text(R.string.ink_ruler), text(R.string.ink_protractor), text(R.string.zoom_box))
        for (w in widths) {
            width = w
            rule.waitForIdle()
            tools.forEach { assertReachable(it, w) }
        }
        // A shape draws with it and closes the sheet; a guide switches on.
        ctl.picker = Picker.Shapes
        rule.onNodeWithContentDescription(text(R.string.toolbar_draw_oval)).performClick()
        rule.waitForIdle()
        assertEquals(InkShapeMode.Oval, ctl.inkShapeMode)
        assertEquals(Picker.None, ctl.picker)
        rule.onNodeWithContentDescription(text(R.string.ink_ruler)).performClick()
        rule.waitForIdle()
        assertTrue(ctl.rulerOn)
    }
}

/** Replaces the favourites with [n] pens of each type in turn, each a little wider than the last. */
internal fun ToolPrefs.useFavourites(n: Int) {
    while (inkPresets.isNotEmpty()) deleteInkPreset(0)
    repeat(n) { i ->
        updateInkStyle(InkStyle.entries[i % InkStyle.entries.size])
        updateInkWidth(1f + i * 0.4f)
        addInkPreset()
    }
    check(inkPresets.size == n)
}
