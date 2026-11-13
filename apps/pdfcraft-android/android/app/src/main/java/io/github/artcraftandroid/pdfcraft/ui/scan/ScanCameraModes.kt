package io.github.artcraftandroid.pdfcraft.ui.scan

import kotlin.math.roundToInt

/**
 * The camera's modes (#457, part of #455): one page at a time, several pages in a row, or the two
 * pages of a book spread. One page is what a new install starts in; the mode chosen last is
 * remembered. Book is book mode (#303) itself: choosing it switches book mode on, and choosing
 * either of the others switches it off.
 */
internal enum class ScanCameraMode(val id: String) {
    /** One photo, then the camera closes and the page is shown (#458 puts Adjust borders there). */
    Single("single"),

    /** Photo after photo until Done, as the camera always was before #457. */
    Batch("batch"),

    /** As Batch, each photo cut into a spread's two pages (#303). */
    Book("book"),
    ;

    companion object {
        /** The mode a new install starts in. */
        val DEFAULT = Single

        /** The remembered mode; anything not understood is the default. Book is not stored here (book mode remembers itself). */
        fun decode(id: String?): ScanCameraMode = entries.firstOrNull { it.id == id && it != Book } ?: DEFAULT
    }
}

/**
 * The mode the camera shows: Book whenever book mode is on (it can also be switched on the scan
 * screen), otherwise the one remembered.
 */
internal fun scanCameraModeShown(remembered: ScanCameraMode, bookOn: Boolean): ScanCameraMode = when {
    bookOn -> ScanCameraMode.Book
    remembered == ScanCameraMode.Book -> ScanCameraMode.DEFAULT
    else -> remembered
}

/**
 * Whether the camera stays open for the next page after a photo. A retake is always one photo,
 * and Done pressed during the photo closes it. With the new bar ([bar], the testing option
 * "Camera modes and icon bar") One page closes it too; without it the camera keeps going, as before.
 */
internal fun scanCameraKeepsOpen(bar: Boolean, mode: ScanCameraMode, retaking: Boolean, doneQueued: Boolean): Boolean =
    !retaking && !doneQueued && !(bar && mode == ScanCameraMode.Single)

/** What the log and a kept scan's page file call the camera's mode: with the old bar there were no modes, only Batch or Book. */
internal fun scanCameraModeId(bar: Boolean, mode: ScanCameraMode): String = when {
    bar -> mode.id
    mode == ScanCameraMode.Book -> ScanCameraMode.Book.id
    else -> ScanCameraMode.Batch.id
}

/** The camera's zoom (#457): pinch, and a button between 1× and 2×. Never below 1×: the wide lens bends a page's edges. */
internal object ScanZoom {
    const val MIN = 1f

    /** Where the button goes from 1×. */
    const val STEP = 2f

    /** The zoom a pinch asks for, within what the camera allows (never below 1×). */
    fun clamp(ratio: Float, max: Float): Float {
        val top = if (max.isFinite() && max >= MIN) max else MIN
        return if (ratio.isFinite()) ratio.coerceIn(MIN, top) else MIN
    }

    /** The button: 2× from anything under it, 1× from 2× or more; within what the camera allows. */
    fun toggled(current: Float, max: Float): Float = if (current < STEP - 0.05f) clamp(STEP, max) else MIN

    /** The button's own text: the zoom to one decimal place, without ".0" ("1×", "1.5×", "2×"). */
    fun label(ratio: Float): String {
        val tenths = (ratio * 10).roundToInt()
        return if (tenths % 10 == 0) "${tenths / 10}×" else "${tenths / 10}.${tenths % 10}×"
    }

    /** The zoom for the log and the page file, to two decimal places. */
    fun rounded(ratio: Float): Double = (ratio * 100).roundToInt() / 100.0
}

/** The page count on the last page's thumbnail: up to 99, then "99+". */
internal fun scanCountBadge(pages: Int): String = if (pages > 99) "99+" else pages.coerceAtLeast(0).toString()
