package io.github.artcraftandroid.pdfcraft.ui.scan

import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import kotlin.math.abs

/*
 * The scanner's screens in steps (#455): camera, adjust borders, enhance, document, one job
 * each. #458 adds the Adjust borders step straight after a photo in One page mode (and after an
 * import of one photo). Behind the testing option "Scan screens in steps"; off is today's single
 * scan screen, where the borders are reached only from Crop.
 */

/** Why the Adjust borders screen opened (#458), for the log: straight after a photo, an import of one photo, a retake from it, or from a page. */
internal enum class ScanBordersOpener(val id: String) {
    Capture("capture"),
    Import("import"),
    Retake("retake"),
    Page("page"),
    ;

    /** Opened as a step after a photo: Next goes on, Back goes back to the camera and asks before dropping the photo. */
    val afterPhoto: Boolean get() = this != Page
}

/** The Adjust borders screen open on one page: the page's key and why it opened. */
internal data class ScanBordersOpen(val key: String, val opener: ScanBordersOpener, val openedAt: Long, val mode: String = "none")

/**
 * Whether Adjust borders follows a photo from the in-app camera: with the steps on, in One page
 * mode with the new camera bar, for a new page; a retake does only when it was asked for from
 * Adjust borders itself (it then returns there).
 */
internal fun scanBordersAfterCapture(steps: Boolean, bar: Boolean, mode: ScanCameraMode, retaking: Boolean, retakeFromBorders: Boolean): ScanBordersOpener? = when {
    !steps -> null
    retaking -> if (retakeFromBorders) ScanBordersOpener.Retake else null
    bar && mode == ScanCameraMode.Single -> ScanBordersOpener.Capture
    else -> null
}

/** Whether Adjust borders follows an import: one photo behaves as One page, several as Many pages (#455). */
internal fun scanBordersAfterImport(steps: Boolean, photos: Int, retaking: Boolean, retakeFromBorders: Boolean): ScanBordersOpener? = when {
    !steps || photos != 1 -> null
    retaking -> if (retakeFromBorders) ScanBordersOpener.Retake else null
    else -> ScanBordersOpener.Import
}

/** Where the page's outline search stands, for the Adjust borders screen. */
internal enum class ScanBordersFinding { Searching, Found, NotFound }

/**
 * The search's state from what the page holds: corners found, the search's report, or the
 * corners it started with when nothing was found (#444). A page with none of these is still
 * being searched (the search runs after the page joins the scan).
 */
internal fun scanBordersFinding(found: FloatArray?, searched: Boolean, uncropped: FloatArray?): ScanBordersFinding = when {
    found != null -> ScanBordersFinding.Found
    searched || uncropped != null -> ScanBordersFinding.NotFound
    else -> ScanBordersFinding.Searching
}

/** What the one Whole photo ⇄ Auto button does next (#458): it names what a tap will do. */
internal enum class ScanBordersToggle { WholePhoto, Auto }

/** "Whole photo" while the corners are on the page found (or the last Auto found); "Auto" when they are at the photo's edges or were moved. */
internal fun scanBordersToggle(corners: FloatArray, found: FloatArray?): ScanBordersToggle =
    if (found != null && scanSameCorners(corners, found)) ScanBordersToggle.WholePhoto else ScanBordersToggle.Auto

/** Two sets of corners within a thousandth of the photo of each other. */
internal fun scanSameCorners(a: FloatArray, b: FloatArray): Boolean = a.size == 8 && b.size == 8 && a.indices.all { abs(a[it] - b[it]) < 0.001f }

/** The page's manual turn after Rotate left (-1) or right (+1) on Adjust borders. */
internal fun scanTurnAfter(manual: Int?, auto: Int, delta: Int): Int = scanQuarter(scanTurnShown(manual, auto) + delta)

/**
 * The steps' state between the camera, the scan and Adjust borders: which page the screen is open
 * on, and the opener waiting for the photo being taken. Kept apart from the app's view model.
 */
internal class ScanStepsController {
    /** Adjust borders, open on a page; null when it is not showing. */
    var borders by mutableStateOf<ScanBordersOpen?>(null)
        private set

    /** The opener for the next page to join the scan, set just before the photo is handed over. */
    var pending: ScanBordersOpener? = null

    /** The camera's mode for that photo, for the log. */
    var pendingMode: String = "none"

    /** A retake was asked for from Adjust borders: the new photo comes back there. */
    var retakeFromBorders: Boolean = false

    /** The photos whose outline search has answered (found or not), by path. */
    val searched = androidx.compose.runtime.mutableStateListOf<String>()

    fun markSearched(path: String) {
        if (path !in searched) searched += path
    }

    /** A page joined the scan (or replaced one): Adjust borders opens on it when a step asked for that. */
    fun pageAdded(key: String) {
        val mode = pendingMode
        val opener = pending ?: return
        pending = null
        retakeFromBorders = false
        val from = if (borders != null) "borders" else "camera"
        borders = ScanBordersOpen(key, opener, SystemClock.elapsedRealtime(), mode)
        Diag.event(DiagArea.Scan, "screen", "from" to from, "to" to "borders", "mode" to mode, "opened_by" to opener.id, "ms_on_screen" to 0L)
    }

    /** The photo was not taken after all: nothing opens. */
    fun photoDropped() {
        pending = null
        retakeFromBorders = false
    }

    /** Adjust borders opened from a page on the scan screen. */
    fun openFromPage(key: String, page: Int, mode: String) {
        borders = ScanBordersOpen(key, ScanBordersOpener.Page, SystemClock.elapsedRealtime(), mode)
        Diag.event(DiagArea.Scan, "screen", "from" to "scan", "to" to "borders", "mode" to mode, "page" to page, "opened_by" to "page", "ms_on_screen" to 0L)
    }

    /** Adjust borders closed, to [to] (`scan`, `camera`); [page] counted from 1. */
    fun close(to: String, page: Int) {
        val open = borders ?: return
        borders = null
        // Next goes to Enhance on the page, even when the page was added from the document screen (#460)
        if (to == "scan") document = false
        Diag.event(
            DiagArea.Scan, "screen", "from" to "borders", "to" to to, "mode" to open.mode, "page" to page,
            "opened_by" to open.opener.id, "ms_on_screen" to (SystemClock.elapsedRealtime() - open.openedAt),
        )
    }

    /** The document screen (#460) is showing: the scan's pages in a grid. */
    var document by mutableStateOf(false)
        private set

    /** The document screen opens, from [from] (`camera`, `import`, `enhance`, `borders`). */
    fun showDocument(from: String, pages: Int) {
        if (document) return
        document = true
        Diag.event(DiagArea.Scan, "screen", "from" to from, "to" to "document", "pages" to pages)
    }

    /** A page was opened from the document screen in Enhance; [page] counted from 1. */
    fun openEnhance(page: Int, pages: Int) {
        if (!document) return
        document = false
        Diag.event(DiagArea.Scan, "screen", "from" to "document", "to" to "enhance", "page" to page, "pages" to pages)
    }

    /** The scan ended: nothing stays open. */
    fun reset() {
        borders = null
        document = false
        searched.clear()
        pending = null
        retakeFromBorders = false
    }
}
