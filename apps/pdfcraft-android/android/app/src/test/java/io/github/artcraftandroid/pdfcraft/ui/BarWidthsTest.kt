package io.github.artcraftandroid.pdfcraft.ui

import android.content.Context
import android.content.res.Configuration
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanBordersBar
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanBordersFinding
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanBordersOpener
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanBordersStep
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanBordersToggle
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanDocumentBar
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanEnhanceBar
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanEnhanceBarItem
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanEraseFill
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanMarkupBars
import io.github.artcraftandroid.pdfcraft.ui.scan.ScanMarkupTool
import io.github.artcraftandroid.pdfcraft.ui.scan.scanEnhanceBarItems
import io.github.artcraftandroid.pdfcraft.ui.scan.scanMarkupToolLabel
import io.github.artcraftandroid.pdfcraft.ui.viewer.DrawToolbarBar
import io.github.artcraftandroid.pdfcraft.ui.viewer.EditToolsRow
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.OrganizeBar
import io.github.artcraftandroid.pdfcraft.ui.viewer.QuickTool
import io.github.artcraftandroid.pdfcraft.ui.viewer.QuickToolbar
import io.github.artcraftandroid.pdfcraft.ui.viewer.ToolPrefs
import io.github.artcraftandroid.pdfcraft.ui.viewer.ViewerController
import io.github.artcraftandroid.pdfcraft.ui.viewer.editToolsBarItems
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkStyleLabel
import io.github.artcraftandroid.pdfcraft.ui.viewer.organizeBarItems
import io.github.artcraftandroid.pdfcraft.ui.viewer.toolbarSlots
import io.github.artcraftandroid.pdfcraft.ui.viewer.useFavourites
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Every bottom bar of the viewer and the scanner (#538), laid out at 320, 360, 384 and 600 dp in
 * each of the app's seven languages, with its optional buttons shown (Edit PDF's Paste, Draw's
 * favourites): every button is at least 48 dp wide and lies on the screen, or is brought onto it
 * by scrolling its bar. A button added to a bar then fails here instead of pushing another off the
 * screen, as Paste did to Link on Edit PDF's bar.
 *
 * The bars covered ([bars]): the quick toolbar; Edit PDF's bar, with and without Paste; the Pages
 * screen's three bars (organizing, exporting pages as pictures, and the plain one); Draw's bar with
 * 0 and 12 favourites; the scanner's Adjust borders bar (with and without Retake), its Enhance bar
 * (both orders), its document bar (plain, without Share, selecting, selecting several to adjust)
 * and its Markup tools. Tool sub-toolbars ([io.github.artcraftandroid.pdfcraft.ui.viewer.SubToolbar])
 * scroll by design and are not listed; text formatting and object selection bars scroll too.
 */
@RunWith(RobolectricTestRunner::class)
class BarWidthsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private fun text(id: Int) = context.getString(id)

    private val widths = listOf(320.dp, 360.dp, 384.dp, 600.dp)

    /** A bar to lay out, and the names of the buttons it must show. */
    private class Bar(val name: String, val labels: (Int) -> List<String>, val before: () -> Unit = {}, val content: @Composable () -> Unit)

    private fun bars(prefs: ToolPrefs, ctl: ViewerController): List<Bar> {
        val quickTools = QuickTool.DEFAULT_BAR
        val borders = { retake: Boolean ->
            ScanBordersStep(1, 1, ScanBordersOpener.Capture, ScanBordersFinding.Found, 0, if (retake) ({}) else null, {}, {})
        }
        val pens = InkStyle.entries.map { text(inkStyleLabel(it)) } + text(R.string.toolbar_eraser) + text(R.string.toolbar_ink_lasso) +
            listOf(text(R.string.toolbar_color_drawing), text(R.string.ink_pen_settings_title), text(R.string.ink_shapes), text(R.string.ink_guides_title))
        val enhance = { items: List<ScanEnhanceBarItem> ->
            items.map {
                text(
                    when (it) {
                        ScanEnhanceBarItem.Retake -> R.string.tools_retake
                        ScanEnhanceBarItem.Borders -> R.string.scan_enhance_borders
                        ScanEnhanceBarItem.Markup -> R.string.scan_markup
                        ScanEnhanceBarItem.Rotate -> R.string.scan_rotate
                        ScanEnhanceBarItem.Delete -> R.string.common_delete
                        ScanEnhanceBarItem.More -> R.string.scan_enhance_more
                    },
                )
            }
        }
        return buildList {
            add(Bar("quick toolbar", { w -> quickTools.take(toolbarSlots(w)).map { text(it.label) } + text(R.string.common_tool_more_tools) }) {
                QuickToolbar(quickTools, {}, {}, moreToolsOpen = false)
            })
            for (paste in listOf(false, true)) {
                val items = editToolsBarItems(paste)
                add(Bar("Edit PDF bar" + if (paste) " with Paste" else "", { items.map { text(it.label) } }) { EditToolsRow(items, null) {} })
            }
            for ((name, exporting, organizing) in listOf(Triple("organizing", false, true), Triple("exporting", true, false), Triple("plain", false, false))) {
                val items = organizeBarItems(exporting, organizing)
                add(Bar("Pages bar, $name", { items.map { text(it.label) } }) { OrganizeBar(items, organizing) {} })
            }
            for (n in listOf(0, 12)) {
                add(Bar("Draw bar with $n favourites", { pens + List(n) { context.getString(R.string.ink_preset_slot, it + 1) } }, { prefs.useFavourites(n) }) {
                    DrawToolbarBar(ctl, prefs)
                })
            }
            for (retake in listOf(true, false)) {
                val labels = listOfNotNull(
                    if (retake) text(R.string.tools_retake) else null,
                    text(R.string.scan_borders_rotate_left), text(R.string.scan_borders_rotate_right), text(R.string.scan_borders_whole),
                )
                add(Bar("scan Adjust borders bar" + if (retake) "" else " in a pass", { labels }) {
                    Column { ScanBordersBar(borders(retake), ScanBordersToggle.WholePhoto, false, true, {}, {}, {}) }
                })
            }
            for (inReach in listOf(false, true)) {
                val items = scanEnhanceBarItems(inReach, markup = true)
                add(Bar("scan Enhance bar" + if (inReach) " (every tool in reach)" else "", { enhance(items) }) {
                    ScanEnhanceBar(true, { true }, {}, {}, {}, {}, {}, items = items, markupEnabled = true)
                })
            }
            add(Bar("scan document bar", { listOf(text(R.string.tools_add_page), text(R.string.scan_camera_import_short), text(R.string.common_share)) }) {
                ScanDocumentBar(false, 0, true, true, {}, {}, {}, {}, {}, {}, {})
            })
            add(Bar("scan document bar without Share", { listOf(text(R.string.tools_add_page), text(R.string.scan_camera_import_short)) }) {
                ScanDocumentBar(false, 0, false, true, {}, {}, {}, {}, {}, {}, {})
            })
            for (batch in listOf(false, true)) {
                val labels = (if (batch) listOf(text(R.string.scan_enhance_borders), text(R.string.scan_batch_look)) else emptyList()) +
                    listOf(text(R.string.scan_rotate), text(R.string.scan_gallery_save), text(R.string.common_delete))
                add(Bar("scan document bar, selecting" + if (batch) " several" else "", { labels }) {
                    ScanDocumentBar(true, 2, true, true, {}, {}, {}, {}, {}, {}, {}, batch = batch)
                })
            }
            val tools = ScanMarkupTool.entries.toList()
            add(Bar("scan Markup tools", { tools.map { text(scanMarkupToolLabel(it)) } }) {
                ScanMarkupBars(
                    ScanMarkupTool.Pen, tools, ScanEraseFill.Paper, ScanEraseFill.entries.toList(), false, 1, 0xFF000000.toInt(),
                    listOf(0xFF000000.toInt(), 0xFFFF0000.toInt()), more = true, dropper = true, selectedText = null,
                    onTool = {}, onFill = {}, onSize = {}, onColour = {}, onMore = {}, onDropper = {}, onEditText = {}, onDeleteText = {},
                )
            })
        }
    }

    private fun named(label: String): SemanticsMatcher = hasContentDescription(label) or hasText(label)

    private fun check(language: String) {
        val prefs = ToolPrefs(context)
        val ctl = ViewerController()
        val bars = bars(prefs, ctl)
        var width by mutableStateOf(widths.first())
        var shown by mutableStateOf(0)
        val base = context.resources.configuration
        rule.setContent {
            val conf = Configuration(base).apply { screenWidthDp = width.value.toInt() }
            CompositionLocalProvider(LocalConfiguration provides conf) {
                PdfcraftTheme(ThemeChoice.Light) {
                    Box(Modifier.requiredWidth(width)) { bars[shown].content() }
                }
            }
        }
        val slot = with(rule.density) { 48.dp.toPx() }
        val failures = mutableListOf<String>()
        for ((i, bar) in bars.withIndex()) for (w in widths) {
            bar.before()
            shown = i
            width = w
            rule.waitForIdle()
            val right = with(rule.density) { w.toPx() }
            for (label in bar.labels(w.value.toInt())) {
                val where = "$language, ${bar.name} at ${w.value.toInt()} dp: \"$label\""
                val node = rule.onNode(named(label))
                try {
                    if (rule.onAllNodes(named(label) and hasAnyAncestor(SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy))).fetchSemanticsNodes().isNotEmpty()) {
                        node.performScrollTo()
                        rule.waitForIdle()
                    }
                    node.assertIsDisplayed()
                } catch (e: AssertionError) {
                    failures += "$where cannot be found or shown (${e.message?.lineSequence()?.firstOrNull()})"
                    continue
                }
                val b = node.getUnclippedBoundsInRoot()
                val (l, r) = with(rule.density) { b.left.toPx() to b.right.toPx() }
                if (r - l < slot - 0.5f) failures += "$where is ${(b.right - b.left).value} dp wide"
                if (l < -0.5f || r > right + 0.5f) failures += "$where lies off the screen ($l..$r px)"
            }
        }
        assertTrue("Bars too narrow for their buttons:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test @Config(sdk = [35], qualifiers = "en-w640dp-h900dp-mdpi") fun english() = check("en")
    @Test @Config(sdk = [35], qualifiers = "ar-w640dp-h900dp-mdpi") fun arabic() = check("ar")
    @Test @Config(sdk = [35], qualifiers = "de-w640dp-h900dp-mdpi") fun german() = check("de")
    @Test @Config(sdk = [35], qualifiers = "fr-w640dp-h900dp-mdpi") fun french() = check("fr")
    @Test @Config(sdk = [35], qualifiers = "es-w640dp-h900dp-mdpi") fun spanish() = check("es")
    @Test @Config(sdk = [35], qualifiers = "it-w640dp-h900dp-mdpi") fun italian() = check("it")
    @Test @Config(sdk = [35], qualifiers = "pt-rBR-w640dp-h900dp-mdpi") fun portuguese() = check("pt")
}
