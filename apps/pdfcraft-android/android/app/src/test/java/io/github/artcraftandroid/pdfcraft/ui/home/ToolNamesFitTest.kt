package io.github.artcraftandroid.pdfcraft.ui.home

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.material3.LocalTextStyle
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
        var measurer: TextMeasurer? = null
        var style = TextStyle.Default
        var room = 0
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) {
                // as ToolCell sets its name (12 sp on 15 sp lines, centred, in the theme's font), in
                // the room a cell leaves it (its 2 dp padding each side)
                measurer = rememberTextMeasurer()
                style = LocalTextStyle.current.merge(TextStyle(fontSize = 12.sp, lineHeight = 15.sp, textAlign = TextAlign.Center))
                room = with(LocalDensity.current) { (cell - 4.dp).roundToPx() }
                // and some cells as the Tools tab draws them, so ToolCell is laid out too
                Column { for (t in QuickTool.entries.take(3)) Box(Modifier.width(cell)) { ToolCell(t, context.getString(t.title)) {} } }
            }
        }
        rule.waitForIdle()
        // A TextMeasurer, not the layout semantics hands back: that is redone at the node's own
        // height, which makes a last line look cut though nothing is cut on a device.
        val results = rule.runOnIdle {
            QuickTool.entries.map { t ->
                val name = context.getString(t.title)
                name to requireNotNull(measurer).measure(name, style, overflow = TextOverflow.Ellipsis, maxLines = TOOL_NAME_LINES, constraints = Constraints(maxWidth = room))
            }
        }
        for ((name, t) in results) {
            val what = "\"$name\" in a ${cell.value.toInt()} dp cell"
            assertTrue("$what takes ${t.lineCount} lines", t.lineCount <= TOOL_NAME_LINES)
            assertFalse("$what is cut", t.isLineEllipsized(t.lineCount - 1))
        }
    }

    @Test @Config(qualifiers = "en-w800dp-h1280dp-xhdpi") fun english() = everyNameFits()
    @Test @Config(qualifiers = "ar-w800dp-h1280dp-xhdpi") fun arabic() = everyNameFits()
}
