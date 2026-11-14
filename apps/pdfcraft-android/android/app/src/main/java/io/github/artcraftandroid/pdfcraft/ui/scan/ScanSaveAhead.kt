package io.github.artcraftandroid.pdfcraft.ui.scan

import android.content.SharedPreferences
import io.github.artcraftandroid.pdfcraft.ui.ScanPreviewKey

/*
 * Save PDF that only writes the file (#504). Each page is made at full size in the background
 * once its preview is ready, by the same native request Save would make, and kept in the scan's
 * cache until the scan ends. Save, Share and Save to gallery take the pages already made and wait
 * only for the others. Text recognition runs on Save only when the person turned it on in
 * Settings ▸ Scanner, and the busy dialog can skip it. What can be worked out without a phone is
 * here, so it can be tested on the desktop.
 */

/** Settings ▸ Scanner: "Make the text selectable (OCR)" on Save PDF (#504); off until the person turns it on. */
internal object ScanOcrSetting {
    private const val KEY = "scanSaveOcr"

    fun get(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, false)

    fun set(prefs: SharedPreferences, on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
    }
}

/**
 * What became of text recognition on a Save or Share (#504), as the log writes it: `off` (the
 * setting is off), `unavailable` (on, but text recognition is not installed), `skipped` (the person
 * pressed Skip; pages read before keep their text) or `done`.
 */
internal enum class ScanOcrOutcome(val word: String) { Off("off"), Unavailable("unavailable"), Skipped("skipped"), Done("done") }

internal fun scanOcrOutcome(on: Boolean, available: Boolean, skipped: Boolean): ScanOcrOutcome = when {
    !on -> ScanOcrOutcome.Off
    !available -> ScanOcrOutcome.Unavailable
    skipped -> ScanOcrOutcome.Skipped
    else -> ScanOcrOutcome.Done
}

/**
 * Why a page is made at full size ahead of Save (#504), for `full_page_ready`: `new` (never made
 * at full size), the preview's own reason when the page changed (`look`, `options`, `borders`),
 * or `turn` when only the turn or document type the preview showed changed what Save would ask.
 */
internal fun scanFullWhy(old: ScanPreviewKey?, now: ScanPreviewKey): String = when {
    old == null -> "new"
    old == now -> "turn"
    else -> io.github.artcraftandroid.pdfcraft.ui.scanQueueWhy(old, now, false)
}

/**
 * The pages Save still has to make (#504), by index, in order: the ones whose full-size page was
 * not ready when the button was pressed.
 */
internal fun scanPagesToFinish(ready: List<Boolean>): List<Int> = ready.indices.filter { !ready[it] }

/**
 * The fields added to `saved` and `share` (#504): the testing option, how many full-size pages
 * were already made when the button was pressed, the wait from the tap to the file, and what
 * became of text recognition with its time.
 */
internal fun scanSaveWaitDiag(ahead: Boolean, pagesReady: Int, waitMs: Long, ocr: ScanOcrOutcome, ocrMs: Long): Array<Pair<String, Any?>> = arrayOf(
    "full_pages_ahead" to ahead, "pages_ready" to pagesReady, "wait_ms" to waitMs, "ocr" to ocr.word, "ocr_ms" to ocrMs,
)

/** One `full_page_ready` line (#504): a page made at full size ahead of Save. */
internal fun scanFullReadyDiag(index: Int, ms: Long, bytes: Long, why: String, flattenReused: Boolean = false): Array<Pair<String, Any?>> = arrayOf(
    "index" to index + 1, "ms" to ms, "bytes" to bytes, "why" to why,
    // the preview's flattening decision taken over instead of made again (#549)
    "flatten_reused" to flattenReused,
)

/**
 * A kept scan's page file (#504): whether the page's full-size result was made before Save was
 * pressed; null with the testing option off.
 */
internal fun scanMadeFullBeforeSave(ahead: Boolean, ready: Boolean): Boolean? = if (ahead) ready else null

/** What Save found ready (#528): pages made ahead at any size, those already stored at the size chosen, and the making Save did itself. */
internal fun scanSizesAheadDiag(on: Boolean, madeAhead: Int, storedAhead: Int, makeMs: Long): Array<Pair<String, Any?>> = arrayOf(
    "sizes_ahead" to on, "pages_made_ahead" to madeAhead, "pages_stored_ahead" to storedAhead, "make_ms" to makeMs,
)
