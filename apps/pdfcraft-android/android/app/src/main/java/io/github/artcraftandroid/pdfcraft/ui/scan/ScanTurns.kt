package io.github.artcraftandroid.pdfcraft.ui.scan

import android.content.SharedPreferences
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel

/*
 * Which way up a scanned page is saved (#339): the orientation classifier's turn, the person's
 * own turn from the Rotate button, and the multi-page rule that lets an unsure page follow its
 * neighbours. Pure functions, so the JVM tests cover them; the scan screen and Save call them.
 *
 * A page's `manual` turn is null while the classifier decides. A press of Rotate, or Undo on the
 * "Turned upright" chip, gives the page a manual turn (its whole turn, 0–3, clockwise), and from
 * then on the classifier is not asked about that page, so it never fights the person.
 */

/** A turn as 0–3 quarter turns clockwise, whatever whole number it starts as. */
internal fun scanQuarter(turns: Int): Int = ((turns % 4) + 4) % 4

/** The turn the page is shown and saved with: the person's when there is one, else the classifier's. */
internal fun scanTurnShown(manual: Int?, auto: Int): Int = scanQuarter(manual ?: auto)

/** The page's manual turn after one press of Rotate: one quarter turn clockwise from what is shown. */
internal fun scanTurnAfterRotate(manual: Int?, auto: Int): Int = scanQuarter(scanTurnShown(manual, auto) + 1)

/** The page's manual turn after Undo on the chip: the page as the corners give it, unturned. */
internal const val SCAN_TURN_UNDONE: Int = 0

/** Whether the scan screen shows "Turned upright · Undo" on a page: the classifier turned it and the person has not. */
internal fun scanShowsTurnedChip(manual: Int?, auto: Int): Boolean = manual == null && scanQuarter(auto) != 0

/**
 * The turn fields of a page's native request: the classifier is asked only when turning upright is
 * on (the ordinary setting and the testing option both) and the person has not turned the page.
 */
internal data class ScanTurnAsk(val upright: Boolean, val turns: Int)

internal fun scanTurnAsk(manual: Int?, setting: Boolean, testing: Boolean): ScanTurnAsk =
    ScanTurnAsk(upright = manual == null && setting && testing, turns = scanQuarter(manual ?: 0))

/**
 * The multi-page rule's verdict for one page: the turn it settles on and whether that turn came
 * from its neighbours (from `scanSettleTurns`).
 */
internal data class ScanSettled(val turns: Int, val followed: Boolean)

/**
 * The pages Save makes again after the multi-page rule (#339), with the turn each is made with:
 * a page that followed its neighbours and was saved with another turn. [savedWith] is the turn
 * each page was saved with (null for a page that failed: it is not made again). With the rule off
 * nothing is made again.
 */
internal fun scanTurnsToRedo(settled: List<ScanSettled>, savedWith: List<Int?>, rule: Boolean): Map<Int, Int> {
    if (!rule) return emptyMap()
    val redo = linkedMapOf<Int, Int>()
    for ((i, s) in settled.withIndex()) {
        val had = savedWith.getOrNull(i) ?: continue
        if (s.followed && scanQuarter(s.turns) != scanQuarter(had)) redo[i] = scanQuarter(s.turns)
    }
    return redo
}

/** What a page's turn came from, for the diagnostics log and a kept scan's page file: short lowercase words. */
internal fun scanTurnSource(manual: Int?, followed: Boolean, upright: Boolean): String = when {
    manual != null -> "manual"
    followed -> "neighbours"
    upright -> "classifier"
    else -> "off"
}

/** Settings ▸ Preferences ▸ Scan: "Turn pages upright automatically" (#339), on unless switched off. */
internal object ScanUprightSetting {
    private const val KEY = "scanUprightAuto"

    fun get(prefs: SharedPreferences): Boolean = prefs.getBoolean(KEY, true)

    fun set(prefs: SharedPreferences, on: Boolean) {
        prefs.edit().putBoolean(KEY, on).apply()
    }
}

/**
 * The classifier's turn of the page at [index] as the scan screen last showed it: from the latest
 * preview, when that preview is of this photo and was made with the classifier asked; else 0.
 */
internal fun AppViewModel.scanAutoTurns(index: Int): Int {
    val page = scanPages.getOrNull(index) ?: return 0
    return scanPreview?.takeIf { it.key.photoPath == page.file.absolutePath && it.key.turns == null }?.quarterTurns ?: 0
}

/** Rotate on the scan screen: the page at [index] one quarter turn clockwise, by the person's hand from now on (#339). */
internal fun AppViewModel.rotateScanPage(index: Int) {
    val page = scanPages.getOrNull(index) ?: return
    val auto = scanAutoTurns(index)
    val next = scanTurnAfterRotate(page.turns, auto)
    scanPages[index] = page.copy(turns = next)
    Diag.event(DiagArea.Scan, "turn", "index" to index + 1, "action" to "rotate", "auto_turns" to auto, "turns" to next)
}

/** Undo on the "Turned upright" chip: the page at [index] as its corners give it, and the classifier no longer asked (#339). */
internal fun AppViewModel.undoScanTurn(index: Int) {
    val page = scanPages.getOrNull(index) ?: return
    val auto = scanAutoTurns(index)
    scanPages[index] = page.copy(turns = SCAN_TURN_UNDONE)
    Diag.event(DiagArea.Scan, "turn", "index" to index + 1, "action" to "undo", "auto_turns" to auto, "turns" to SCAN_TURN_UNDONE)
}

/** Rotate left (-1) or right (+1) on Adjust borders (#458): the page at [index] turned by the person's hand from now on. */
internal fun AppViewModel.rotateScanPageBy(index: Int, delta: Int) {
    val page = scanPages.getOrNull(index) ?: return
    val auto = scanAutoTurns(index)
    val next = scanTurnAfter(page.turns, auto, delta)
    scanPages[index] = page.copy(turns = next)
    Diag.event(DiagArea.Scan, "crop_rotate", "index" to index + 1, "delta" to delta, "auto_turns" to auto, "turns" to next)
}
