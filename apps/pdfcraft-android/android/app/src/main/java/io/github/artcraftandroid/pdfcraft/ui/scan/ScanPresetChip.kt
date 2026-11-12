package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.annotation.StringRes
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.SCAN_PRESETS
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** The name people read for a kind of document (#299); null for a word that is not one. */
@StringRes
internal fun scanPresetLabel(preset: String): Int? = when (preset) {
    "document" -> R.string.scan_preset_document
    "receipt" -> R.string.scan_preset_receipt
    "whiteboard" -> R.string.scan_preset_whiteboard
    "book_page" -> R.string.scan_preset_book_page
    "photo_rich" -> R.string.scan_preset_photo_rich
    "photo" -> R.string.scan_preset_photo
    "id_card" -> R.string.scan_preset_id_card
    "coloured_form" -> R.string.scan_preset_coloured_form
    else -> null
}

/**
 * What the chip under the page preview says (#299): the kind Clean scan worked out ("Auto:
 * Receipt"), or the kind the person chose. Null when there is nothing to say: no kind from the
 * preview yet and none chosen.
 */
internal data class ScanPresetChipText(val preset: String, val auto: Boolean)

internal fun scanPresetChipText(chosen: String?, previewed: String?): ScanPresetChipText? = when {
    chosen != null && scanPresetLabel(chosen) != null -> ScanPresetChipText(chosen, auto = false)
    previewed != null && scanPresetLabel(previewed) != null -> ScanPresetChipText(previewed, auto = true)
    else -> null
}

/** The person chose [preset] for the page at [index], or null to let Clean scan work it out again (#299). */
internal fun AppViewModel.chooseScanPreset(index: Int, preset: String?) {
    val page = scanPages.getOrNull(index) ?: return
    if (page.preset == preset) return
    scanPages[index] = page.copy(preset = preset)
    Diag.event(DiagArea.Scan, "preset_chosen", "index" to index + 1, "preset" to (preset ?: "auto"))
}

/**
 * The chip under the page preview in Clean scan (#299): "Auto: Receipt", and a list of the kinds
 * to choose from when it is tapped. [previewed] is the kind the latest preview of this page reports.
 */
@Composable
internal fun ScanPresetChip(vm: AppViewModel, index: Int, chosen: String?, previewed: String?, modifier: Modifier = Modifier) {
    val c = pdfColors
    var choosing by remember { mutableStateOf(false) }
    val text = scanPresetChipText(chosen, previewed) ?: return
    val name = stringResource(scanPresetLabel(text.preset) ?: return)
    Text(
        if (text.auto) stringResource(R.string.scan_preset_auto_is, name) else name, color = c.text, fontSize = 14.sp,
        modifier = modifier.clip(CircleShape).border(1.dp, c.textSecondary, CircleShape)
            .clickable(role = Role.Button) { choosing = true }.padding(horizontal = 14.dp, vertical = 6.dp),
    )
    if (choosing) {
        PdfDialog(onDismiss = { choosing = false }, title = stringResource(R.string.scan_preset_title), confirm = null, list = true) {
            SheetRow(null, stringResource(R.string.scan_preset_automatic), { vm.chooseScanPreset(index, null); choosing = false }, checked = chosen == null)
            for (preset in SCAN_PRESETS) {
                val label = scanPresetLabel(preset) ?: continue
                SheetRow(null, stringResource(label), { vm.chooseScanPreset(index, preset); choosing = false }, checked = chosen == preset)
            }
        }
    }
}
