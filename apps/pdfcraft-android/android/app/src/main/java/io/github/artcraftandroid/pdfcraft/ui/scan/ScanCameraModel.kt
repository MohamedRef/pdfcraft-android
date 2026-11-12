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

    /** Whether the viewfinder shows the page's outline and advice (#295): a testing option, on by default. */
    var liveOutline by mutableStateOf(prefs.getBoolean(PREF_LIVE, true))
        private set

    fun updateLiveOutline(on: Boolean) {
        if (on == liveOutline) return
        liveOutline = on
        prefs.edit().putBoolean(PREF_LIVE, on).apply()
        Diag.event(DiagArea.Scan, "camera_live_option", "live_outline" to on)
    }

    /** Whether the camera shoots by itself when the page is ready (#296): a setting, on by default. */
    var autoCapture by mutableStateOf(prefs.getBoolean(PREF_AUTO, true))
        private set

    fun updateAutoCapture(on: Boolean) {
        if (on == autoCapture) return
        autoCapture = on
        prefs.edit().putBoolean(PREF_AUTO, on).apply()
        Diag.event(DiagArea.Scan, "camera_auto_option", "auto_capture" to on)
    }

    /** Whether the one-line tip about auto-capture has been shown (once). */
    var autoTipShown by mutableStateOf(prefs.getBoolean(PREF_AUTO_TIP, false))
        private set

    fun markAutoTipShown() {
        if (autoTipShown) return
        autoTipShown = true
        prefs.edit().putBoolean(PREF_AUTO_TIP, true).apply()
    }

    /** Low-light merge (#297): Auto, Always or Off, a setting. */
    var merge by mutableStateOf(ScanMergeSetting.decode(prefs.getString(PREF_MERGE, null)))
        private set

    /** Whether the person picked the merge setting; false while it is the default (Off, #426). */
    var mergeChosen by mutableStateOf(prefs.contains(PREF_MERGE))
        private set

    fun updateMerge(next: ScanMergeSetting) {
        if (next == merge && mergeChosen) return
        merge = next
        mergeChosen = true
        prefs.edit().putString(PREF_MERGE, next.id).apply()
        Diag.event(DiagArea.Scan, "camera_merge_option", "merge" to next.id, "default" to ScanMergeSetting.DEFAULT.id)
    }

    /** The phone's memory class in MB: a merge needs [ScanMergeRules.MEMORY_MB]. */
    val memoryClassMb: Int get() = getApplication<Application>().getSystemService(android.app.ActivityManager::class.java)?.memoryClass ?: 0

    /** A burst is being merged: the screen holds the shutter until the page is in the scan. */
    var merging by mutableStateOf(false)

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
        merging = false
    }

    private companion object {
        const val PREF_CHOICE = "scanCamera"
        const val PREF_LIVE = "scanLiveOutline"
        const val PREF_AUTO = "scanAutoCapture"
        const val PREF_AUTO_TIP = "scanAutoTip"
        const val PREF_MERGE = "scanLowLightMerge"
    }
}
