package io.github.artcraftandroid.pdfcraft.ui.scan

import android.app.Application
import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The camera screen lies over the scan's own screens and keeps every touch: one on a part of it
 * that takes none (the black around the buttons, the shutter while it is grey) must not reach the
 * screen underneath, where Enhance's Rotate lies under the shutter. A scan of twelve pages had two
 * of them turned a quarter by such touches, with the camera open for the next page.
 *
 * The camera itself (CameraX) does not run in a JVM test, so the screen is shown as it is on a
 * phone without a camera: the same outer layer, with Import photos and Cancel in its middle.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class ScanCameraTouchesTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private val actions = ScanCameraActions({ _, _, _, _ -> }, {}, {}, {}, {}, {})

    @Test @Config(qualifiers = "en-w384dp-h832dp-xhdpi")
    fun aTouchOnAnEmptyPartOfTheCameraScreenDoesNotReachTheScreenUnderneath() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val model = ScanCameraModel(app)
        var underneath = 0
        rule.setContent {
            Box(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxSize().clickable { underneath++ })
                ScanCameraScreen(model, emptyList(), actions, bar = true)
            }
        }
        rule.waitForIdle()
        // the camera closed: the screen underneath is the one touched
        rule.onRoot().performTouchInput { click(Offset(8f, 8f)) }
        rule.waitForIdle()
        assertEquals(1, underneath)

        rule.runOnUiThread { model.show(File(app.cacheDir, "scan-touches.jpg"), ScanCaptureRoute.ImportOnly, false, "single", true) }
        rule.waitForIdle()
        // a corner, and the bottom's middle where the shutter is: nothing of the camera screen is there on this page
        rule.onRoot().performTouchInput { click(Offset(8f, 8f)) }
        rule.onRoot().performTouchInput { click(Offset(width / 2f, height - 8f)) }
        rule.waitForIdle()
        assertEquals(1, underneath)
    }
}
