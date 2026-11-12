package io.github.artcraftandroid.pdfcraft.ui.scan

import android.app.Application
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import java.io.File

/**
 * The in-app scan camera's state (#294), apart from the app's main view model: whether the camera
 * screen is showing, the file the next photo goes into, and the Scan with setting. The activity
 * routes each scan photo request here or to the phone's camera app ([scanCaptureRoute]).
 */
internal class ScanCameraModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("app", Context.MODE_PRIVATE)

    /** Scan with: PdfCraft's camera or the phone's camera app. */
    var choice by mutableStateOf(ScanCameraChoice.decode(prefs.getString(PREF_CHOICE, null)))
        private set

    /** The file the next photo is written into, while the camera screen shows; null when it is closed. */
    var target by mutableStateOf<File?>(null)
        private set

    /** What the camera screen offers: the camera, the permission request, or importing only. */
    var route by mutableStateOf(ScanCaptureRoute.InApp)
        private set

    /** Whether the photo replaces a page (Retake): the screen then closes after one photo. */
    var retaking by mutableStateOf(false)
        private set

    val hasCamera: Boolean get() = getApplication<Application>().packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)

    val granted: Boolean get() = ContextCompat.checkSelfPermission(getApplication(), android.Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    fun updateChoice(next: ScanCameraChoice) {
        if (next == choice) return
        choice = next
        prefs.edit().putString(PREF_CHOICE, next.id).apply()
        Diag.event(DiagArea.Scan, "camera_choice", "choice" to next.id)
    }

    /** Show the camera screen for a photo into [file]; [screen] is one of the routes the screen shows. */
    fun show(file: File, screen: ScanCaptureRoute, retake: Boolean) {
        target = file
        route = screen
        retaking = retake
        Diag.event(DiagArea.Scan, "camera_open", "route" to screen.id, "retake" to retake)
    }

    /** The permission came back while the screen shows: it can be the camera now. */
    fun recheck() {
        if (target != null && route != ScanCaptureRoute.InApp && route != ScanCaptureRoute.ImportOnly && granted) route = ScanCaptureRoute.InApp
    }

    fun hide() {
        target = null
    }

    private companion object {
        const val PREF_CHOICE = "scanCamera"
    }
}
