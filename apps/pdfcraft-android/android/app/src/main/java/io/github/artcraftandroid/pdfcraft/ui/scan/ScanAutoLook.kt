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
 * The Flatten page switch stays as the person's override for one page. Moiré, fingers, glare,
 * stains and deblurring are not part of it yet (#305, #306, #304).
 */

/** What the automatic look did to a page and the person took back (#466). */
data class ScanAutoMarks(
    /** The look was made Grayscale because the page's kind suggested it. */
    val greyApplied: Boolean = false,
    /** What the person undid on this page: "greyscale", "flatten". Not done again for the page. */
    val undone: Set<String> = emptySet(),
) {
    /** For a scan session: `g=1;u=flatten,greyscale`. */
    fun encode(): String = "g=${if (greyApplied) 1 else 0};u=${undone.sorted().joinToString(",")}"

    companion object {
        const val GREYSCALE = "greyscale"
        const val FLATTEN = "flatten"

        /** The marks in [text]; none for anything not understood. */
        fun decode(text: String?): ScanAutoMarks {
            val values = text.orEmpty().split(';').mapNotNull { part -> part.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
            val undone = values["u"].orEmpty().split(',').filter { it == GREYSCALE || it == FLATTEN }.toSet()
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

/**
 * Whether a page just made should become Grayscale (#466): the testing option is on, its kind
 * suggests it, it is still in Clean scan, and the person has not taken that back on this page.
 */
internal fun scanAutoGrey(lab: ScanLab, suggestGreyscale: Boolean, look: ScanPageLook, marks: ScanAutoMarks): Boolean =
    lab.autoLook && lab.presets && suggestGreyscale && look.filter == ScanFilter.CleanScan && !marks.greyApplied && ScanAutoMarks.GREYSCALE !in marks.undone

/** The chip saying what the automatic look did to the page shown, with Undo (#466); null for none. */
internal enum class ScanAutoChip { Flattened, Grayscale }

internal fun scanAutoChips(lab: ScanLab, marks: ScanAutoMarks, look: ScanPageLook, flattenChoice: Boolean?, autoFlattened: Boolean): List<ScanAutoChip> = buildList {
    if (lab.flattenAuto && flattenChoice == null && autoFlattened) add(ScanAutoChip.Flattened)
    if (marks.greyApplied && look.filter == ScanFilter.Grayscale) add(ScanAutoChip.Grayscale)
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
    /** What the automatic look applied: `greyscale`, `flatten`, `greyscale+flatten` or `none`. */
    val suggestionApplied: String,
    /** What the person took back: `greyscale`, `flatten`, both joined by `+`, or `none`. */
    val undone: String,
) {
    fun diag(index: Int, lab: ScanLab): Array<Pair<String, Any?>> = arrayOf(
        "index" to index + 1, "kind" to kind, "turned" to turned, "flattened" to flattened, "flatten_why" to flattenWhy,
        "suggestion_applied" to suggestionApplied, "undone" to undone, "auto_look" to lab.autoLook, "flatten_auto" to lab.flattenAuto,
    )

    fun json(): JSONObject = JSONObject().put("kind", kind).put("turned", turned).put("flattened", flattened).put("flatten_why", flattenWhy)
        .put("suggestion_applied", suggestionApplied).put("undone", undone)
}

/**
 * A page's automatic decisions (#466). [preset] is the kind it was made as (null for none),
 * [manualTurns] the person's own turn, [turns] the turn it got, [flattened] whether it came out
 * flattened, [checkWhy] the flattening check's word when one ran, [choice] the Flatten page
 * choice and [auto] whether the automatic look decided its flattening.
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
): ScanAutoDecisions {
    val why = when {
        choice != null -> if (ScanAutoMarks.FLATTEN in marks.undone && choice == false) "undone" else "chosen"
        checkWhy != null -> checkWhy
        else -> "off"
    }
    val applied = buildList {
        if (marks.greyApplied) add(ScanAutoMarks.GREYSCALE)
        if (auto && flattened) add(ScanAutoMarks.FLATTEN)
    }
    return ScanAutoDecisions(
        kind = preset ?: "none",
        turned = manualTurns == null && scanQuarter(turns) != 0,
        flattened = flattened,
        flattenWhy = why,
        suggestionApplied = applied.joinToString("+").ifEmpty { "none" },
        undone = marks.undone.sorted().joinToString("+").ifEmpty { "none" },
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
    }
    Diag.event(DiagArea.Scan, "auto_undone", "index" to index + 1, "what" to if (chip == ScanAutoChip.Flattened) ScanAutoMarks.FLATTEN else ScanAutoMarks.GREYSCALE)
}

/** The chip for what the automatic look did, with Undo (#466), like "Turned upright · Undo". */
@Composable
internal fun ScanAutoChipView(chip: ScanAutoChip, onUndo: () -> Unit, modifier: Modifier = Modifier) {
    val (label, undo) = when (chip) {
        ScanAutoChip.Flattened -> R.string.scan_auto_flattened_undo to R.string.scan_auto_flattened_undo_description
        ScanAutoChip.Grayscale -> R.string.scan_auto_grayscale_undo to R.string.scan_auto_grayscale_undo_description
    }
    val undoLabel = stringResource(undo)
    Text(
        stringResource(label), color = Color.White, fontSize = 13.sp,
        modifier = modifier.clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
            .clickable(role = Role.Button, onClickLabel = undoLabel) { onUndo() }.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}
