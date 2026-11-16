package io.github.artcraftandroid.pdfcraft.ui.home

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artcraftandroid.pdfcraft.engine.DocCategory
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Home's card with categories (#560): what TalkBack is told (always run), and pictures for UI
 * review with five categories at 384 dp, where the chips scroll and none is squeezed (only with
 * -Ppdfcraft.screenshots=true; written to app/build/screenshots/study-card).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StudyCardScreenshotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val out = File(System.getProperty("pdfcraft.screenshots.dir") ?: "build/screenshots", "study-card").apply { mkdirs() }

    private lateinit var vm: AppViewModel

    private fun show(theme: ThemeChoice = ThemeChoice.Light, custom: List<String> = emptyList()) {
        rule.setContent {
            PdfcraftTheme(theme) {
                vm = viewModel()
                ContinueStudying(vm)
            }
        }
        rule.waitForIdle()
        // the card open, as a tablet shows it and a phone does after a tap (#615)
        rule.runOnUiThread { vm.home.setFoldContinue(false) }
        val s = vm.study
        s.studyStats.clear()
        val lib = s.library
        val now = System.currentTimeMillis()
        lib.update { it.copy(items = emptyList(), categories = DocCategory.DEFAULTS) }
        custom.forEachIndexed { i, name -> lib.addCategory(name, id = "custom$i") }
        val docs = listOf(
            Triple("content://t/anatomy", "Anatomy lecture 4 — muscles of the upper limb.pdf", DocCategory.STUDY),
            Triple("content://t/invoice", "Invoice October.pdf", DocCategory.WORK),
            Triple("content://t/novel", "A novel.pdf", null),
        ) + custom.indices.map { Triple("content://t/c$it", "Notes $it.pdf", "custom$it") } +
            listOf(Triple("content://t/trip", "Trip plan.pdf", DocCategory.PERSONAL))
        docs.forEachIndexed { i, (uri, name, cat) ->
            s.studyStats.record(uri, name, 40, (0..12).toSet(), 12, (20 - i) * 60_000L, now - i * 1000)
            if (cat != null) lib.setCategory(uri, name, null, cat, now)
        }
        rule.runOnUiThread { s.refreshStudy() }
        settle { s.studyRecent.isNotEmpty() }
    }

    /** Let the card's loading (off the main thread, then back on it) finish until [done]. */
    private fun settle(done: () -> Boolean) {
        val until = System.currentTimeMillis() + 10_000
        while (!done()) {
            check(System.currentTimeMillis() < until) { "card state: rows=${vm.study.studyRecent.size} any=${vm.study.studyAnyRows} chips=${vm.study.studyChips} stats=${vm.study.studyStats.all().size}" }
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        rule.waitForIdle()
    }

    private fun capture(name: String) {
        if (System.getProperty("pdfcraft.screenshots") != "true") return
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun talkBackHearsTheTitleAndEachChipsState() {
        show()
        rule.onNodeWithText("Continue reading").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        rule.onNodeWithText("All").assertIsSelected()
        rule.onNodeWithText("Study").assertIsNotSelected().performClick()
        settle { vm.study.studyChip == DocCategory.STUDY && vm.study.studyRecent.size == 1 }
        rule.onNodeWithText("Study").assertIsSelected()
        rule.onNodeWithText("Continue studying").assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading))
        assertEquals("content://t/anatomy", vm.study.studyRecent.single().uri)
        // the remembered chip goes back to All for the next test
        rule.onNodeWithText("All").performClick()
        settle { vm.study.studyChip == null && vm.study.studyRecent.size > 1 }
    }

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun englishFiveCategories() {
        assumeTrue(System.getProperty("pdfcraft.screenshots") == "true")
        show(custom = listOf("Medicine", "Hobbies"))
        capture("card_all_en_384x832_light")
        rule.onNodeWithText("Study").performClick()
        settle { vm.study.studyChip == DocCategory.STUDY && vm.study.studyRecent.size == 1 }
        capture("card_study_en_384x832_light")
        rule.onNodeWithText("All").performClick()
        settle { vm.study.studyChip == null && vm.study.studyRecent.size > 1 }
    }

    @Test @Config(qualifiers = "ar-w384dp-h832dp-xhdpi")
    fun arabicFiveCategoriesDark() {
        assumeTrue(System.getProperty("pdfcraft.screenshots") == "true")
        show(ThemeChoice.Dark, custom = listOf("مذاكرة الطب", "هوايات"))
        capture("card_all_ar_384x832_dark")
    }

    @Test @Config(qualifiers = "de-w320dp-h640dp-xhdpi")
    fun germanSmallPhone() {
        assumeTrue(System.getProperty("pdfcraft.screenshots") == "true")
        show(custom = listOf("Medizin", "Freizeit"))
        capture("card_all_de_320x640_light")
    }
}
