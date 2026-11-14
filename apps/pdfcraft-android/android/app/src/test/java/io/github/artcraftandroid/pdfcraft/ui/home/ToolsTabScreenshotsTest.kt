package io.github.artcraftandroid.pdfcraft.ui.home

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.PdfcraftTheme
import io.github.artcraftandroid.pdfcraft.ui.ThemeChoice
import java.io.File
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Pictures of Home ▸ Tools for UI review (#539): the one list of tools, by category, at a small
 * and a common phone size, in English, Arabic (right to left), German and French (long words).
 * Nothing is compared. Off unless Gradle gets -Ppdfcraft.screenshots=true; written to
 * app/build/screenshots (docs/ANDROID_SCREENSHOTS.md).
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ToolsTabScreenshotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getProperty("pdfcraft.screenshots") == "true")

    private val out = File(System.getProperty("pdfcraft.screenshots.dir") ?: "build/screenshots", "tools").apply { mkdirs() }

    private fun draw(config: String, theme: ThemeChoice = ThemeChoice.Light) {
        rule.setContent {
            PdfcraftTheme(theme) {
                val vm: AppViewModel = viewModel()
                ToolsTab(vm)
            }
        }
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(out, "tools_${config}_${theme.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test @Config(qualifiers = "en-w360dp-h640dp-xhdpi") fun english360() = draw("en_360x640")
    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi") fun english384Dark() = draw("en_384x832", ThemeChoice.Dark)
    @Test @Config(qualifiers = "ar-w384dp-h832dp-xhdpi") fun arabic384() = draw("ar_384x832")
    @Test @Config(qualifiers = "de-w320dp-h640dp-xhdpi") fun german320() = draw("de_320x640")
    @Test @Config(qualifiers = "fr-w360dp-h640dp-xhdpi") fun french360() = draw("fr_360x640")

    /** The whole list at once: two phone screens at most (#539). */
    @Test @Config(qualifiers = "en-w360dp-h1400dp-xhdpi") fun englishWhole() = draw("en_360_whole")
}
