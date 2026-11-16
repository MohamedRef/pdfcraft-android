package io.github.artcraftandroid.pdfcraft.ui.home

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artcraftandroid.pdfcraft.engine.RecentFile
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.ListMaxWidth
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.SheetMaxWidth
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import io.github.artcraftandroid.pdfcraft.ui.gridColumns
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home and a sheet on a 1280×800 dp tablet (#613): the side rail instead of the bottom bar, list
 * rows no wider than [ListMaxWidth], at least four grid cells a row, and a sheet no wider than
 * [SheetMaxWidth]. Pictures with -Ppdfcraft.screenshots=true (tablet-613), beside a phone's.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class TabletLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("pdfcraft.screenshots.dir") ?: "build/screenshots", "tablet-613").apply { mkdirs() }
    private lateinit var vm: AppViewModel

    private fun show(grid: Boolean, theme: ThemeChoice = ThemeChoice.Light) {
        rule.setContent {
            PdfcraftTheme(theme) {
                vm = viewModel()
                HomeRoot(vm) {}
            }
        }
        rule.waitForIdle()
        rule.runOnUiThread { vm.home.gridView = grid }
        val now = System.currentTimeMillis()
        vm.recents.clear()
        for (i in 8 downTo 1) vm.recents.touch(RecentFile("content://t/recent$i", "Recent file $i.pdf", now - i * 60_000L, 12, 1_000_000L))
        rule.runOnUiThread { vm.refreshRecents() }
        val until = System.currentTimeMillis() + 10_000
        while (vm.recentList.size < 8) {
            check(System.currentTimeMillis() < until) { "recents=${vm.recentList.size}" }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        rule.waitForIdle()
    }

    private fun widthDp(px: Float) = px / rule.density.density

    private fun capture(name: String) {
        if (System.getProperty("pdfcraft.screenshots") != "true") return
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "en-w1280dp-h800dp-mdpi")
    fun listRowsKeepAReadableWidthBesideASideRail() {
        show(grid = false)
        // the row is clickable, so its name is merged into the row's own node
        val row = widthDp(rule.onNodeWithText("Recent file 1").fetchSemanticsNode().boundsInRoot.width)
        assertTrue("a row is $row dp wide", row > 300f && row <= ListMaxWidth.value + 0.5f)
        // the tabs are down the side: Files sits beside the list, near the left edge
        val files = rule.onAllNodesWithContentDescription("Files").fetchSemanticsNodes().map { widthDp(it.boundsInRoot.right) }
        assertTrue("Files tab ends at $files", files.isNotEmpty() && files.min() <= 80.5f)
        capture("home_list_en_1280x800_light")
    }

    @Test @Config(qualifiers = "en-w1280dp-h800dp-mdpi")
    fun gridViewShowsAtLeastFourCellsARow() {
        show(grid = true)
        val tops = (1..4).map { rule.onNodeWithText("Recent file $it", substring = true).fetchSemanticsNode().boundsInRoot.top }
        assertEquals("first four cells on one line: $tops", 1, tops.distinct().size)
        capture("home_grid_en_1280x800_light")
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun aPhoneKeepsTwoCellsAndTheBottomBar() {
        show(grid = true)
        val one = rule.onNodeWithText("Recent file 1", substring = true).fetchSemanticsNode().boundsInRoot.top
        val three = rule.onNodeWithText("Recent file 3", substring = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue(three > one)
        capture("home_grid_en_384x832_light")
    }

    @Test @Config(qualifiers = "en-w1280dp-h800dp-mdpi")
    fun aSheetKeepsItsWidestSize() {
        rule.setContent {
            PdfcraftTheme(ThemeChoice.Light) {
                PdfSheet(onDismiss = {}, modifier = Modifier.testTag("sheet")) { Text("Sheet row") }
            }
        }
        rule.waitForIdle()
        val sheet = widthDp(rule.onNodeWithTag("sheet", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.width)
        assertEquals(SheetMaxWidth.value, sheet, 0.5f)
        capture("sheet_en_1280x800_light")
    }

    @Test
    fun gridColumnsGrowWithTheList() {
        assertEquals(2, gridColumns(360))
        assertEquals(2, gridColumns(384))
        assertEquals(4, gridColumns(760))
        assertTrue(gridColumns(840) >= 4)
    }
}
