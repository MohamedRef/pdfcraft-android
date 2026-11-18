package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The quick toolbar as it comes, on a screen like the owner's phone (384 × 832 dp at 450 dpi,
 * #708): in English each of its seven captions lies on one line, so no item is taller than the
 * others and the bar keeps its 70 dp; in every language Export's caption is one line at its full
 * size. Before #708 Export's caption was its full name, "Export PDF", the only caption to wrap
 * there, which made the bar about 12 dp taller.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class QuickToolbarCaptionsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int) = context.getString(id)

    /** How much taller than its usual 70 dp the bar's row of tools is, in px, as the bar reports it. */
    private var extra = -1

    private fun show(widthDp: Int = 384) {
        val base = context.resources.configuration
        rule.setContent {
            val conf = Configuration(base).apply { screenWidthDp = widthDp }
            CompositionLocalProvider(LocalConfiguration provides conf) {
                PdfcraftTheme(ThemeChoice.Light) {
                    Box(Modifier.requiredWidth(widthDp.dp)) {
                        QuickToolbar(QuickTool.DEFAULT_BAR, {}, {}, moreToolsOpen = false, onExtraHeight = { extra = it })
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun layout(caption: String): TextLayoutResult {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(caption, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single()
    }

    /** [caption] lies on one line, whole, at the bar's 10.5 sp: not made smaller to fit. */
    private fun assertOneLine(caption: String, where: String) {
        val l = layout(caption)
        assertEquals("$where: \"$caption\" lines", 1, l.lineCount)
        assertFalse("$where: \"$caption\" is cut", l.isLineEllipsized(0))
        assertEquals("$where: \"$caption\" was made smaller to fit", 10.5f, l.layoutInput.style.fontSize.value, 0.01f)
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-450dpi")
    fun inEnglishEveryCaptionOfTheBarIsOneLine() {
        show()
        val captions = QuickTool.DEFAULT_BAR.take(toolbarSlots(384)).map { text(it.label) } + text(R.string.common_tool_more_tools)
        assertEquals("the bar's items at 384 dp: $captions", 7, captions.size)
        for (caption in captions) assertOneLine(caption, "en, 384 dp")
        assertEquals("the bar is $extra px taller than 70 dp", 0, extra)
        // the short caption is the bar's only: the lists and the Export screen keep the full name
        assertEquals("Export PDF", text(QuickTool.Export.title))
    }

    private fun exportCaptionIsOneLine(language: String) {
        show()
        assertOneLine(text(QuickTool.Export.label), "$language, 384 dp")
    }

    @Test @Config(qualifiers = "ar-w384dp-h832dp-450dpi") fun arabic() = exportCaptionIsOneLine("ar")
    @Test @Config(qualifiers = "de-w384dp-h832dp-450dpi") fun german() = exportCaptionIsOneLine("de")
    @Test @Config(qualifiers = "es-w384dp-h832dp-450dpi") fun spanish() = exportCaptionIsOneLine("es")
    @Test @Config(qualifiers = "fr-w384dp-h832dp-450dpi") fun french() = exportCaptionIsOneLine("fr")
    @Test @Config(qualifiers = "it-w384dp-h832dp-450dpi") fun italian() = exportCaptionIsOneLine("it")
    @Test @Config(qualifiers = "pt-rBR-w384dp-h832dp-450dpi") fun portuguese() = exportCaptionIsOneLine("pt")
}
