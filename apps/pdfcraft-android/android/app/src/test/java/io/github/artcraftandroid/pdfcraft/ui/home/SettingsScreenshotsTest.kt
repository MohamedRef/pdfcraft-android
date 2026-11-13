package io.github.artcraftandroid.pdfcraft.ui.home

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * Pictures of Settings' pages for UI review (#462): every page, light and dark, at a small and a
 * common phone size, in English, Arabic (right to left) and French (long words). Nothing is
 * compared; the files are for a person to look at. Off unless Gradle gets -Ppdfcraft.screenshots=true;
 * written to app/build/screenshots (docs/ANDROID_SCREENSHOTS.md). No photo is ever drawn here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class SettingsScreenshotsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun onlyWhenAsked() = assumeTrue(System.getProperty("pdfcraft.screenshots") == "true")

    private val out = File(System.getProperty("pdfcraft.screenshots.dir") ?: "build/screenshots", "settings").apply { mkdirs() }

    private fun drawAll(config: String, themes: List<ThemeChoice> = listOf(ThemeChoice.Light, ThemeChoice.Dark)) {
        var page by mutableStateOf(SettingsPage.Root)
        var theme by mutableStateOf(themes.first())
        rule.setContent {
            PdfcraftTheme(theme) {
                val vm: AppViewModel = viewModel()
                // a fresh screen per picture, opened straight on its page
                key(page, theme) {
                    vm.home.openSettings(page, show = false)
                    SettingsScreen(vm)
                }
            }
        }
        for (t in themes) for (p in SettingsPage.entries) {
            page = p
            theme = t
            rule.waitForIdle()
            val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
            File(out, "${p.name.lowercase()}_${config}_${t.name.lowercase()}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }

    @Test @Config(qualifiers = "en-w360dp-h640dp-xhdpi") fun english360() = drawAll("en_360x640")
    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi") fun english384() = drawAll("en_384x832")
    @Test @Config(qualifiers = "ar-w360dp-h640dp-xhdpi") fun arabic360() = drawAll("ar_360x640")
    @Test @Config(qualifiers = "ar-w384dp-h832dp-xhdpi") fun arabic384() = drawAll("ar_384x832")
    @Test @Config(qualifiers = "fr-w360dp-h640dp-xhdpi") fun french360() = drawAll("fr_360x640", listOf(ThemeChoice.Light))

    /** Each page whole, for the rows below the fold. */
    @Test @Config(qualifiers = "en-w384dp-h2400dp-xhdpi") fun englishWhole() = drawAll("en_384_whole", listOf(ThemeChoice.Light))
}
