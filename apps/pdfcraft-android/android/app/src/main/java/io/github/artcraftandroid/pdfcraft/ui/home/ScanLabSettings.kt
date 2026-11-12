package io.github.artcraftandroid.pdfcraft.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.core.ScanFinder
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfSwitch
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.viewer.formatSize

/**
 * Settings, About, Diagnostics, while the log is being recorded: the scanner's testing options
 * (#288). Each scanner feature can be switched off or swapped for what it replaced, and scans can
 * be kept on the device and shared as a zip, so a page that comes out wrong on a real phone can
 * be looked at afterwards with the photo it was made from.
 */
@Composable
internal fun ScanLabSettings(vm: AppViewModel) {
    val c = pdfColors
    val lab = vm.scanLab
    val o = lab.options
    var confirmShare by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { lab.refreshKept() }

    Text(stringResource(R.string.scan_lab_section), color = c.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))

    // the scans themselves
    SheetRow(null, stringResource(R.string.scan_lab_keep), { lab.update(o.copy(keep = !o.keep)) }) {
        PdfSwitch(o.keep, { lab.update(o.copy(keep = it)) })
    }
    Text(stringResource(R.string.scan_lab_keep_body), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
    if (lab.kept.pages > 0) {
        SheetRow(null, stringResource(R.string.scan_lab_share, lab.kept.pages, formatSize(lab.kept.bytes)), { confirmShare = true })
        SheetRow(null, stringResource(R.string.scan_lab_clear), { confirmClear = true })
    }

    // one row per scanner feature
    Choice(
        stringResource(R.string.scan_lab_finder),
        listOf(
            ScanFinder.AUTO to stringResource(R.string.scan_lab_finder_auto),
            ScanFinder.NETWORK to stringResource(R.string.scan_lab_finder_network),
            ScanFinder.LINES to stringResource(R.string.scan_lab_finder_lines),
            ScanFinder.OFF to stringResource(R.string.scan_lab_finder_off),
        ),
        o.finder,
    ) { lab.update(o.copy(finder = it)) }
    SheetRow(null, stringResource(R.string.scan_lab_refine), { lab.update(o.copy(refine = !o.refine)) }) {
        PdfSwitch(o.refine, { lab.update(o.copy(refine = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_perspective), { lab.update(o.copy(perspective = !o.perspective)) }) {
        PdfSwitch(o.perspective, { lab.update(o.copy(perspective = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_snap_paper), { lab.update(o.copy(snapPaper = !o.snapPaper)) }) {
        PdfSwitch(o.snapPaper, { lab.update(o.copy(snapPaper = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_preview), { lab.update(o.copy(preview = !o.preview)) }) {
        PdfSwitch(o.preview, { lab.update(o.copy(preview = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_crop), { lab.update(o.copy(cropTools = !o.cropTools)) }) {
        PdfSwitch(o.cropTools, { lab.update(o.copy(cropTools = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_upright), { lab.update(o.copy(upright = !o.upright)) }) {
        PdfSwitch(o.upright, { lab.update(o.copy(upright = it)) })
    }
    val off = stringResource(R.string.scan_lab_off)
    Choice(stringResource(R.string.scan_lab_sharpen), ScanLab.SHARPENING.map { it to (if (it == 0) off else it.toString()) }, o.sharpen) { lab.update(o.copy(sharpen = it)) }
    SheetRow(null, stringResource(R.string.scan_lab_denoise), { lab.update(o.copy(denoise = !o.denoise)) }) {
        PdfSwitch(o.denoise, { lab.update(o.copy(denoise = it)) })
    }
    val own = stringResource(R.string.scan_lab_strength_own)
    Choice(stringResource(R.string.scan_lab_strength), ScanLab.STRENGTHS.map { it to (it?.toString() ?: own) }, o.strength) { lab.update(o.copy(strength = it)) }
    Text(stringResource(R.string.scan_lab_options_body), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
    if (!o.asDesigned) SheetRow(null, stringResource(R.string.scan_lab_reset), { lab.reset() })

    if (confirmShare) {
        PdfDialog(
            onDismiss = { confirmShare = false },
            title = stringResource(R.string.scan_lab_share_title),
            confirm = stringResource(R.string.common_share),
            onConfirm = {
                confirmShare = false
                lab.share()
            },
        ) { Text(stringResource(R.string.scan_lab_share_body), color = c.text, fontSize = 14.sp) }
    }
    if (confirmClear) {
        PdfDialog(
            onDismiss = { confirmClear = false },
            title = stringResource(R.string.scan_lab_clear_title),
            confirm = stringResource(R.string.common_delete),
            onConfirm = {
                confirmClear = false
                lab.clear()
            },
            destructive = true,
        )
    }
}

/** A setting with a few values: its name, then the values as pills on a line of their own (they scroll when the line is short). */
@Composable
private fun <T> Choice(label: String, values: List<Pair<T, String>>, selected: T, onPick: (T) -> Unit) {
    val c = pdfColors
    Text(label, color = c.text, fontSize = 16.sp, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
    Row(
        Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for ((value, name) in values) {
            val on = value == selected
            Text(
                name, color = if (on) Color.White else c.text, fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) c.accent else c.surface)
                    .clickable(role = Role.RadioButton) { onPick(value) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
