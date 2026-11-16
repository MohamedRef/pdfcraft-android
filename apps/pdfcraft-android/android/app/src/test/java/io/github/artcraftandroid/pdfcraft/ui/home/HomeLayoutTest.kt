package io.github.artcraftandroid.pdfcraft.ui.home

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.core.app.ApplicationProvider
import io.github.artcraftandroid.pdfcraft.engine.DocCategory
import io.github.artcraftandroid.pdfcraft.engine.RecentFile
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.TabPolicy
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
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
 * Home on a phone (#615): with a Continue card, three open documents and five recent files,
 * three file rows are wholly on the screen without scrolling, above the open-documents bar, and
 * no text in that bar wraps. Pictures only with -Ppdfcraft.screenshots=true (home-615).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class HomeLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val out = File(System.getProperty("pdfcraft.screenshots.dir") ?: "build/screenshots", "home-615").apply { mkdirs() }
    private lateinit var vm: AppViewModel

    private fun show(theme: ThemeChoice = ThemeChoice.Light) {
        // three documents left open in tabs, as a restart brings them back (parked)
        context.getSharedPreferences("app", Context.MODE_PRIVATE).edit()
            .putString("openTabs", TabPolicy.encode(listOf("content://t/a" to "Anatomy.pdf", "content://t/b" to "Invoice.pdf", "content://t/c" to "Novel.pdf")))
            .remove("home.fold.scans").remove("home.fold.continue")
            .commit()
        rule.setContent {
            PdfcraftTheme(theme) {
                vm = viewModel()
                HomeRoot(vm) {}
            }
        }
        rule.waitForIdle()
        val now = System.currentTimeMillis()
        vm.recents.clear()
        for (i in 5 downTo 1) vm.recents.touch(RecentFile("content://t/recent$i", "Recent file $i.pdf", now - i * 60_000L, 12, 1_000_000L))
        // a full Continue card: three documents read, one of them for study
        val s = vm.study
        s.studyStats.clear()
        s.library.update { it.copy(items = emptyList(), categories = DocCategory.DEFAULTS) }
        listOf("Anatomy lecture 4.pdf", "Invoice October.pdf", "A novel.pdf").forEachIndexed { i, name ->
            s.studyStats.record("content://t/s$i", name, 40, (0..12).toSet(), 12, (20 - i) * 60_000L, now - i * 1000)
        }
        s.library.setCategory("content://t/s0", "Anatomy lecture 4.pdf", null, DocCategory.STUDY, now)
        rule.runOnUiThread { vm.refreshRecents(); s.refreshStudy() }
        settle { vm.recentList.size == 5 && s.studyRecent.size == 3 && vm.tabs.tabs.size == 3 }
    }

    private fun settle(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "home state: recents=${vm.recentList.size} study=${vm.study.studyRecent.size} tabs=${vm.tabs.tabs.size}" }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        rule.waitForIdle()
    }

    private fun lines(text: String): Int {
        val layouts = mutableListOf<TextLayoutResult>()
        rule.onNodeWithText(text).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action?.invoke(layouts)
        return layouts.single().lineCount
    }

    private fun capture(name: String) {
        if (System.getProperty("pdfcraft.screenshots") != "true") return
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun assertThreeRowsShow() {
        val screen = rule.onRoot().fetchSemanticsNode().boundsInRoot
        val bar = rule.onNodeWithText("3 open documents").fetchSemanticsNode().boundsInRoot
        for (i in 1..3) {
            val row = rule.onNodeWithText("Recent file $i").fetchSemanticsNode().boundsInRoot
            assertTrue("file $i ends at ${row.bottom}, the bar starts at ${bar.top}", row.bottom <= bar.top)
            assertTrue("file $i starts at ${row.top}", row.top >= screen.top)
        }
        assertEquals(1, lines("3 open documents"))
        assertEquals(1, lines("Back to documents"))
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun threeRecentFilesShowAboveTheOpenDocumentsBar() {
        show()
        assertThreeRowsShow()
        capture("home_en_384x832_light")
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun theOpenedContinueCardShowsOneDocumentThenMore() {
        show()
        rule.onNodeWithText("Continue reading").assertExists()
        rule.runOnUiThread { vm.home.setFoldContinue(false) }
        rule.waitForIdle()
        rule.onNodeWithText("More (2)").assertExists()
        rule.onNodeWithText("A novel").assertDoesNotExist()
        capture("home_card_open_en_384x832_light")
    }

    /** #636: the ✕ in the bar closes nothing by itself; it asks, and only "Close all" there closes the documents. */
    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun closeAllAsksFirstAndClosesOnlyOnYes() {
        show()
        rule.onNodeWithContentDescription("Close all").performClick()
        rule.waitForIdle()
        assertEquals("nothing closed by the tap", 3, vm.tabs.tabs.size)
        rule.onNodeWithText("Close 3 open documents?").assertExists()
        rule.onNodeWithText("Changes are saved first. You can open the documents again from Recent.").assertExists()
        rule.onNodeWithText("Cancel").performClick()
        rule.waitForIdle()
        assertEquals("Cancel keeps them open", 3, vm.tabs.tabs.size)
        rule.onNodeWithText("Close 3 open documents?").assertDoesNotExist()
        rule.onNodeWithContentDescription("Close all").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Close all").performClick()
        settle { vm.tabs.tabs.isEmpty() }
        rule.onNodeWithText("Back to documents").assertDoesNotExist()
    }

    @Test @Config(qualifiers = "de-w320dp-h640dp-xhdpi")
    fun theBarStaysOneLineInGermanAt320() {
        show()
        rule.onNodeWithText("3 geöffnete Dokumente", substring = true, useUnmergedTree = true).assertExists()
        capture("home_de_320x640_light")
    }

    @Test @Config(qualifiers = "ar-w384dp-h832dp-xhdpi")
    fun arabicDark() {
        show(ThemeChoice.Dark)
        capture("home_ar_384x832_dark")
    }
}
