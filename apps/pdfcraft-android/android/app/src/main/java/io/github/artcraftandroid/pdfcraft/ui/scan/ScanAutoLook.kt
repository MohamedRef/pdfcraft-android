package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import org.json.JSONObject

/*
 * The automatic look (#466): Clean scan decides everything for each page by itself, on every page,
 * with no taps. What Clean scan already decided (borders, upright, document type, tone, paper,
 * sharpening) stays as it was; this adds the two things it used to leave to the person:
 *
 * - its suggestions are applied, not offered: a receipt becomes Grayscale, with a
 *   "Grayscale · Undo" chip (testing option `autoLook`, on);
 * - flattening is its own decision, through the guarded check of the automatic flattening mode
 *   (#302, testing option `flattenAuto`), with a "Flattened · Undo" chip; a page whose Flatten
 *   page switch the person set is left to that choice.
 *
 * - fingers holding the page at its edge are papered over after Clean scan at strength 40 or more
 *   (testing option `removeFingers`, on), with "Finger removed · Undo"; when a finger hid print,
 *   the chip says only a new photo brings it back.
 *
 * The Flatten page switch stays as the person's override for one page. Moiré, glare, stains and
 * deblurring are not part of it yet (#305, #306, #304).
 */

/** What the automatic look did to a page and the person took back (#466). */
data class ScanAutoMarks(
    /** The look was made Grayscale because the page's kind suggested it. */
    val greyApplied: Boolean = false,
    /** What the person undid on this page: "greyscale", "flatten", "fingers". Not done again for the page. */
    val undone: Set<String> = emptySet(),
) {
    /** For a scan session: `g=1;u=flatten,greyscale`. */
    fun encode(): String = "g=${if (greyApplied) 1 else 0};u=${undone.sorted().joinToString(",")}"

    companion object {
        const val GREYSCALE = "greyscale"
        const val FLATTEN = "flatten"
        const val FINGERS = "fingers"

        /** The marks in [text]; none for anything not understood. */
        fun decode(text: String?): ScanAutoMarks {
            val values = text.orEmpty().split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val undone = values["u"].orEmpty().split(',').filter { it == GREYSCALE || it == FLATTEN || it == FINGERS }.toSet()
            return ScanAutoMarks(values["g"] == "1", undone)
        }
    }
}

/**
 * Whether a page's flattening is decided automatically (#302, #466): the automatic mode is on and
 * the person has not chosen either way with Flatten page (their switch, or Undo on the chip,
 * wins). The page is then asked with the guarded check, which keeps the flattened page only when
 * its lines of text come out straighter.
 */
internal fun scanAutoFlattens(choice: Boolean?, lab: ScanLab): Boolean = lab.flattenAuto && choice == null

/** Whether fingers at the page's edge are removed (#466): the testing option is on and the person has not undone it on this page. */
internal fun scanRemovesFingers(lab: ScanLab, marks: ScanAutoMarks): Boolean = lab.removeFingers && ScanAutoMarks.FINGERS !in marks.undone

/**
 * Whether a page just made should become Grayscale (#466): the testing option is on, its kind
 * suggests it, it is still in Clean scan, and the person has not taken that back on this page.
 */
internal fun scanAutoGrey(lab: ScanLab, suggestGreyscale: Boolean, look: ScanPageLook, marks: ScanAutoMarks): Boolean =
    lab.autoLook && lab.presets && suggestGreyscale && look.filter == ScanFilter.CleanScan && !marks.greyApplied && ScanAutoMarks.GREYSCALE !in marks.undone

/** The chip saying what the automatic look did to the page shown, with Undo (#466); null for none. */
internal enum class ScanAutoChip {
    Flattened,
    Grayscale,
    /** "Finger removed · Undo". */
    Fingers,
    /** A finger that hid print was removed: only a new photo brings that print back; Undo still puts the finger back. */
    FingersOverPrint,
}

/**
 * The chips for the page shown (#466). [fingersFilled] and [fingersOverPrint] are what removing
 * fingers did to it (0 when it did not run).
 */
internal fun scanAutoChips(
    lab: ScanLab,
    marks: ScanAutoMarks,
    look: ScanPageLook,
    flattenChoice: Boolean?,
    autoFlattened: Boolean,
    fingersFilled: Int = 0,
    fingersOverPrint: Int = 0,
): List<ScanAutoChip> = buildList {
    if (lab.flattenAuto && flattenChoice == null && autoFlattened) add(ScanAutoChip.Flattened)
    if (marks.greyApplied && look.filter == ScanFilter.Grayscale) add(ScanAutoChip.Grayscale)
    if (scanRemovesFingers(lab, marks) && fingersFilled > 0) add(if (fingersOverPrint > 0) ScanAutoChip.FingersOverPrint else ScanAutoChip.Fingers)
}

/** The look a page keeps for the scan's next page: a look the automatic look chose goes back to Clean scan (#466). */
internal fun scanLookToPassOn(look: ScanPageLook, marks: ScanAutoMarks): ScanPageLook = if (marks.greyApplied) ScanPageLook() else look

/** The looks strip's order (#466): with the automatic look, Clean scan first; else as the looks are listed. */
fun scanStripOrder(autoLook: Boolean): List<ScanFilter> =
    if (autoLook) listOf(ScanFilter.CleanScan) + ScanFilter.entries.filter { it != ScanFilter.CleanScan } else ScanFilter.entries

/** The automatic decisions of one page (#466), for the `auto_decisions` log line and a kept scan's page file. */
internal data class ScanAutoDecisions(
    val kind: String,
    val turned: Boolean,
    val flattened: Boolean,
    /** Why the page is or is not flattened: the check's word (`straighter`, `notbetter`, …), `chosen`, `undone` or `off`. */
    val flattenWhy: String,
    /** What the automatic look applied: `greyscale`, `flatten`, `fingers`, joined by `+`, or `none`. */
    val suggestionApplied: String,
    /** What the person took back: `greyscale`, `flatten`, `fingers`, joined by `+`, or `none`. */
    val undone: String,
    /** What removing fingers did to the page; null when it did not run. */
    val fingers: ScanFingerCounts? = null,
) {
    fun diag(index: Int, lab: ScanLab): Array<Pair<String, Any?>> = arrayOf(
        "index" to index + 1, "kind" to kind, "turned" to turned, "flattened" to flattened, "flatten_why" to flattenWhy,
        "suggestion_applied" to suggestionApplied, "undone" to undone, "auto_look" to lab.autoLook, "flatten_auto" to lab.flattenAuto,
        *scanFingersDiag(lab.removeFingers, fingers),
    )

    fun json(): JSONObject = JSONObject().put("kind", kind).put("turned", turned).put("flattened", flattened).put("flatten_why", flattenWhy)
        .put("suggestion_applied", suggestionApplied).put("undone", undone).apply { scanFingersDiag(true, fingers).drop(1).forEach { (k, v) -> put(k, v) } }
}

/** What removing fingers did to one page (#466), from the native step's report. */
internal data class ScanFingerCounts(val found: Int, val overPrint: Int, val filled: Int, val ms: Int) {
    companion object {
        fun of(f: io.github.artcraftandroid.pdfcraft.core.ScanFingers?): ScanFingerCounts? =
            f?.let { ScanFingerCounts(it.found.toInt(), it.overPrint.toInt(), it.filled.toInt(), it.ms.toInt()) }
    }
}

/**
 * The finger fields of a page's log line and page file (#466): the option, then fingers found,
 * those over print, those filled and the milliseconds; -1 each when the step did not run (another
 * look, a strength under 40, the option off or the person's Undo).
 */
internal fun scanFingersDiag(option: Boolean, fingers: ScanFingerCounts?): Array<Pair<String, Any?>> = arrayOf(
    "remove_fingers" to option,
    "fingers" to (fingers?.found ?: -1), "fingers_over_print" to (fingers?.overPrint ?: -1),
    "fingers_filled" to (fingers?.filled ?: -1), "fingers_ms" to (fingers?.ms ?: -1),
)

/**
 * A page's automatic decisions (#466). [preset] is the kind it was made as (null for none),
 * [manualTurns] the person's own turn, [turns] the turn it got, [flattened] whether it came out
 * flattened, [checkWhy] the flattening check's word when one ran, [choice] the Flatten page
 * choice, [auto] whether the automatic look decided its flattening and [fingers] what removing
 * fingers did.
 */
internal fun scanAutoDecisions(
    preset: String?,
    manualTurns: Int?,
    turns: Int,
    flattened: Boolean,
    checkWhy: String?,
    choice: Boolean?,
    auto: Boolean,
    marks: ScanAutoMarks,
    fingers: ScanFingerCounts? = null,
): ScanAutoDecisions {
    val why = when {
        choice != null -> if (ScanAutoMarks.FLATTEN in marks.undone && choice == false) "undone" else "chosen"
        checkWhy != null -> checkWhy
        else -> "off"
    }
    val applied = buildList {
        if (marks.greyApplied) add(ScanAutoMarks.GREYSCALE)
        if (auto && flattened) add(ScanAutoMarks.FLATTEN)
        if ((fingers?.filled ?: 0) > 0) add(ScanAutoMarks.FINGERS)
    }
    return ScanAutoDecisions(
        kind = preset ?: "none",
        turned = manualTurns == null && scanQuarter(turns) != 0,
        flattened = flattened,
        flattenWhy = why,
        suggestionApplied = applied.joinToString("+").ifEmpty { "none" },
        undone = marks.undone.sorted().joinToString("+").ifEmpty { "none" },
        fingers = fingers,
    )
}

/** A page made with [kind] (#466): it becomes Grayscale when its kind suggests that. Main thread. */
internal fun AppViewModel.scanApplyAutoLook(pageKey: String, suggestGreyscale: Boolean) {
    val at = scanPages.indexOfFirst { it.key == pageKey }
    if (at < 0) return
    val page = scanPages[at]
    if (!scanAutoGrey(scanLab.options, suggestGreyscale, page.look, page.auto)) return
    scanPages[at] = page.copy(look = ScanPageLook(ScanFilter.Grayscale), auto = page.auto.copy(greyApplied = true))
    Diag.event(DiagArea.Scan, "auto_applied", "index" to at + 1, "suggestion" to ScanAutoMarks.GREYSCALE)
}

/** Undo on an automatic look's chip (#466): that page only, and not done again for it. */
internal fun AppViewModel.undoScanAuto(index: Int, chip: ScanAutoChip) {
    val page = scanPages.getOrNull(index) ?: return
    scanPages[index] = when (chip) {
        ScanAutoChip.Flattened -> page.copy(flatten = false, auto = page.auto.copy(undone = page.auto.undone + ScanAutoMarks.FLATTEN))
        ScanAutoChip.Grayscale -> page.copy(look = ScanPageLook(), auto = page.auto.copy(greyApplied = false, undone = page.auto.undone + ScanAutoMarks.GREYSCALE))
        ScanAutoChip.Fingers, ScanAutoChip.FingersOverPrint -> page.copy(auto = page.auto.copy(undone = page.auto.undone + ScanAutoMarks.FINGERS))
    }
    Diag.event(DiagArea.Scan, "auto_undone", "index" to index + 1, "what" to scanAutoChipWhat(chip))
}

/** The word an Undo of [chip] logs and keeps in the page's marks (#466). */
internal fun scanAutoChipWhat(chip: ScanAutoChip): String = when (chip) {
    ScanAutoChip.Flattened -> ScanAutoMarks.FLATTEN
    ScanAutoChip.Grayscale -> ScanAutoMarks.GREYSCALE
    ScanAutoChip.Fingers, ScanAutoChip.FingersOverPrint -> ScanAutoMarks.FINGERS
}

/** The chip for what the automatic look did, with Undo (#466), like "Turned upright · Undo". */
@Composable
internal fun ScanAutoChipView(chip: ScanAutoChip, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val (label, undo) = when (chip) {
        ScanAutoChip.Flattened -> R.string.scan_auto_flattened_undo to R.string.scan_auto_flattened_undo_description
        ScanAutoChip.Grayscale -> R.string.scan_auto_grayscale_undo to R.string.scan_auto_grayscale_undo_description
        ScanAutoChip.Fingers -> R.string.scan_auto_fingers_undo to R.string.scan_auto_fingers_undo_description
        ScanAutoChip.FingersOverPrint -> R.string.scan_auto_fingers_over_print to R.string.scan_auto_fingers_undo_description
    }
    val undoLabel = stringResource(undo)
    Text(
        stringResource(label), color = Color.White, fontSize = 13.sp,
        modifier = modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
            .clickable(role = Role.Button, onClickLabel = undoLabel) { onUndo() }.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
