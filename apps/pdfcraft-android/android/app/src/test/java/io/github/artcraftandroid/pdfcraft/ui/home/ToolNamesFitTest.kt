package io.github.artcraftandroid.pdfcraft.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import io.github.artcraftandroid.pdfcraft.ui.viewer.QuickTool
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
 * #721: on a tablet every tool's name in the Tools tab reads whole. Each name is laid out in a
 * Tools cell at the narrowest a wide screen's grid makes it ([toolsCellMinWidth]) and must fit in
 * [TOOL_NAME_LINES] lines without an ellipsis, in every language. Before #721 the grid's cells were
 * 80 dp at any width and names had two lines, so the owner's tablet showed "Enhance scanned pa…",
 * "Read handwriting…" and "Review recognized …". Text has its real widths only with native graphics.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolNamesFitTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()

    private fun everyNameFits() {
        val cell = toolsCellMinWidth(753)
        assertEquals(104f, cell.value, 0.01f)
        assertEquals(80f, toolsCellMinWidth(384).value, 0.01f)
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) {
                Column { for (t in QuickTool.entries) Box(Modifier.width(cell)) { ToolCell(t, context.getString(t.title)) {} } }
            }
        }
        rule.waitForIdle()
        val nodes = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals("one name per tool", QuickTool.entries.size, nodes.size)
        for (node in nodes) {
            val found = mutableListOf<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action?.invoke(found)
            val t = found.single()
            val what = "\"${t.layoutInput.text}\" in a ${cell.value.toInt()} dp cell"
            assertTrue("$what takes ${t.lineCount} lines", t.lineCount <= TOOL_NAME_LINES)
            assertFalse("$what is cut", t.isLineEllipsized(t.lineCount - 1))
        }
    }

    @Test @Config(qualifiers = "en-w800dp-h1280dp-xhdpi") fun english() = everyNameFits()
    @Test @Config(qualifiers = "ar-w800dp-h1280dp-xhdpi") fun arabic() = everyNameFits()
    @Test @Config(qualifiers = "de-w800dp-h1280dp-xhdpi") fun german() = everyNameFits()
    @Test @Config(qualifiers = "es-w800dp-h1280dp-xhdpi") fun spanish() = everyNameFits()
    @Test @Config(qualifiers = "fr-w800dp-h1280dp-xhdpi") fun french() = everyNameFits()
    @Test @Config(qualifiers = "it-w800dp-h1280dp-xhdpi") fun italian() = everyNameFits()
    @Test @Config(qualifiers = "pt-rBR-w800dp-h1280dp-xhdpi") fun brazilianPortuguese() = everyNameFits()
}
