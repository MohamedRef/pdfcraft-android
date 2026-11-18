package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * #704: every caption of Draw's bar and of its Shapes, More shapes and Guides sheets is on one
 * line and whole, in every language, on a 384 dp phone and a 753 dp tablet, whichever shape the
 * bar's Shapes button shows. On the tablet, where the whole first row fits, Calligraphy and the
 * highlighter read at the full 10 sp, selected or not. Before #704 every Draw button was 48 dp
 * with a 10 sp caption, so they read "Calligra…" and "Highligh…" on the phone and the tablet
 * alike. Text has its real widths only with native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class DrawCaptionsFitTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private enum class Part { Bar, ShapesAndGuidesSheets, MoreShapesSheet }

    private fun layoutOf(node: SemanticsNode): TextLayoutResult {
        val found = mutableListOf<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(found)
        return found.single()
    }

    /** Every text shown, as laid out: the captions, and a sheet's titles. */
    private fun texts(): List<TextLayoutResult> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
            .fetchSemanticsNodes().map(::layoutOf)

    private fun assertEveryTextWhole(where: String) {
        val texts = texts()
        assertTrue("no text $where", texts.isNotEmpty())
        for (t in texts) {
            val what = "\"${t.layoutInput.text}\" $where at ${t.layoutInput.style.fontSize}"
            assertEquals("$what: lines", 1, t.lineCount)
            assertFalse("$what is cut", t.isLineEllipsized(0))
            assertFalse("$what overflows", t.hasVisualOverflow)
        }
    }

    /** Every node showing [caption] shows it at the full 10 sp. */
    private fun assertFullSize(caption: String, where: String) {
        val nodes = rule.onAllNodesWithText(caption, useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("\"$caption\" isn't shown $where", nodes.isNotEmpty())
        for (node in nodes) assertEquals("\"$caption\" $where: size", 10f, layoutOf(node).layoutInput.style.fontSize.value, 0.01f)
    }

    private fun everyCaptionIsWhole() {
        val prefs = ToolPrefs(context)
        prefs.useFavourites(12)
        val ctl = ViewerController()
        var width by mutableStateOf(384.dp)
        var part by mutableStateOf(Part.Bar)
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) {
                // laid out afresh for each width and part, as each first shows on a phone or a tablet
                key(width, part) {
                    Box(Modifier.requiredWidth(width)) {
                        when (part) {
                            Part.Bar -> DrawToolbarBar(ctl, prefs, onMarkup = {})
                            Part.ShapesAndGuidesSheets -> Column {
                                DrawShapesPicker(ctl, prefs)
                                DrawGuidesPicker(ctl)
                            }
                            Part.MoreShapesSheet -> Column { DrawMoreShapesPicker(ctl, prefs) }
                        }
                    }
                }
            }
        }
        for (w in listOf(384.dp, 753.dp)) for (p in Part.entries) {
            width = w
            part = p
            rule.waitForIdle()
            assertEveryTextWhole("on the ${p.name} at ${w.value.toInt()} dp")
        }

        // The tablet's first row has the room: Calligraphy and the highlighter at full size, selected or not.
        width = 753.dp
        part = Part.Bar
        val calligraphy = context.getString(R.string.ink_toolbar_calligraphy)
        val highlighter = context.getString(R.string.ink_toolbar_marker)
        for (pen in listOf(InkStyle.Ballpoint, InkStyle.Calligraphy, InkStyle.Highlighter)) {
            activateDrawingPen(ctl, prefs, pen)
            rule.waitForIdle()
            assertFullSize(calligraphy, "with the ${pen.name} pen at 753 dp")
            assertFullSize(highlighter, "with the ${pen.name} pen at 753 dp")
        }

        // The bar's Shapes button names the shape in use, in a 48 dp button at any width.
        width = 384.dp
        for (shape in DrawShapes + DrawMoreShapes) {
            ctl.inkShapeMode = shape.mode
            rule.waitForIdle()
            assertEveryTextWhole("on the bar at 384 dp with the ${shape.mode} shape")
        }
    }

    @Test @Config(qualifiers = "en-w800dp-h1280dp-xhdpi") fun english() = everyCaptionIsWhole()
    @Test @Config(qualifiers = "ar-w800dp-h1280dp-xhdpi") fun arabic() = everyCaptionIsWhole()
    @Test @Config(qualifiers = "de-w800dp-h1280dp-xhdpi") fun german() = everyCaptionIsWhole()
    @Test @Config(qualifiers = "es-w800dp-h1280dp-xhdpi") fun spanish() = everyCaptionIsWhole()
    @Test @Config(qualifiers = "fr-w800dp-h1280dp-xhdpi") fun french() = everyCaptionIsWhole()
    @Test @Config(qualifiers = "it-w800dp-h1280dp-xhdpi") fun italian() = everyCaptionIsWhole()
    @Test @Config(qualifiers = "pt-rBR-w800dp-h1280dp-xhdpi") fun brazilianPortuguese() = everyCaptionIsWhole()
}
