package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.roundToInt

/** Where the palm guard swallowed a finger touch (#156). */
internal enum class PalmSpot { Page, Toolbar }

/**
 * Diagnostics log lines for the pen tools (#130 #144 #148 #149 #150 #156), next to the per-stroke
 * `stroke` line of #128. Only counts, enums and fixed words: never colours, names or positions.
 *
 * Palm touches are counted, not logged one by one, and summed up in one `palm_guard` line after
 * the next pen stroke (or when the log is shared), so a resting hand does not flood the log.
 */
internal object PenDiag {
    private val palmPage = AtomicInteger(0)
    private val palmToolbar = AtomicInteger(0)
    @Volatile private var guardDp = 0f
    @Volatile private var leftHanded = false
    @Volatile private var penMode: PenDownMode? = null

    /** The guard ignored a finger touch at [spot]; the guard's size and hand are kept for the summary. */
    fun palmRejected(spot: PalmSpot, sizeDp: Float, left: Boolean) {
        if (!Diag.enabled()) return
        guardDp = sizeDp
        leftHanded = left
        (if (spot == PalmSpot.Page) palmPage else palmToolbar).incrementAndGet()
    }

    /** One `palm_guard` line with the touches ignored since the last one; nothing when there were none. */
    fun flushPalm() {
        val page = palmPage.getAndSet(0)
        val toolbar = palmToolbar.getAndSet(0)
        if (page + toolbar == 0) return
        Diag.event(
            DiagArea.Ink, "palm_guard",
            "page_touches" to page, "toolbar_touches" to toolbar,
            "guard_dp" to guardDp.roundToInt(), "hand" to if (leftHanded) "left" else "right",
        )
    }

    /** How the page's pen-down started (#144): ink, erase or highlight, from the tip and side button. */
    fun penDown(mode: PenDownMode) {
        if (Diag.enabled()) penMode = mode
    }

    /** The mode of the stroke that just ended, once; null when the page did not see its start. */
    fun takePenMode(): PenDownMode? = penMode.also { penMode = null }

    /** A side-button click while hovering (#144) and what it did. */
    fun penButtonClick(action: PenButtonClick) =
        Diag.event(DiagArea.Ink, "pen_button_click", "action" to action.key)

    /** A smart pen gesture (#148): its kind, outcome, and how many draft and saved items it touched. */
    fun gesture(kind: String, outcome: String, drafts: Int = 0, saved: Int = 0) =
        Diag.event(DiagArea.Ink, "pen_gesture", "kind" to kind, "outcome" to outcome, "drafts" to drafts, "saved" to saved)

    /** Undo of a stroke that became a markup (#148): whether the markup went too (the two-step undo). */
    fun gestureUndo(markupUndone: Boolean) =
        Diag.event(DiagArea.Ink, "gesture_undo", "markup_undone" to markupUndone)

    /** A stroke that followed the ruler or protractor (#150), with the angle it ended at in whole degrees. */
    fun guideStroke(guide: String, angleDeg: Float?, points: Int) =
        Diag.event(DiagArea.Ink, "guide_stroke", "guide" to guide, "angle_deg" to angleDeg?.roundToInt(), "points" to points)

    /** The zoom box moved on after a stroke in its advance zone (#130). */
    fun zoomBoxAdvance(newLine: Boolean, newPage: Boolean) =
        Diag.event(DiagArea.Ink, "zoom_box_advance", "new_line" to newLine, "new_page" to newPage)

    /** The zoom box's sharp render (#130): time, size and whether it came back. */
    fun zoomBoxRender(ms: Long, scale: Float, w: Int, h: Int, ok: Boolean) =
        Diag.event(DiagArea.Ink, "zoom_box_render", "ms" to ms, "scale" to scale, "px" to w.toLong() * h, "ok" to ok)

    /** An eyedropper tap (#149): whether it found a colour. */
    fun eyedropper(found: Boolean) = Diag.event(DiagArea.Ink, "eyedropper", "found" to found)

    /** A palette import or export (#149) and its outcome; [colors] is the palette's size. */
    fun palette(action: String, outcome: String, colors: Int = 0) =
        Diag.event(DiagArea.Ink, "palette", "action" to action, "outcome" to outcome, "colors" to colors)
}

/** The log's word for why an import was refused. */
internal fun PaletteImportError.diagWord(): String = when (this) {
    PaletteImportError.NotAPalette -> "not_a_palette"
    PaletteImportError.NewerVersion -> "newer_version"
    PaletteImportError.NoColors -> "no_colors"
}
