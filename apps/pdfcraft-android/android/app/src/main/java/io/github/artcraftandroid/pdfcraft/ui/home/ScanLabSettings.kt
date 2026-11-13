package io.github.artcraftandroid.pdfcraft.ui.home

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.selectable
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
 * Settings, Diagnostics, Scanner testing, while the log is being recorded: the scanner's testing options
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
    // the in-app camera (#294), or the phone's camera app it replaced (the same setting as in Settings)
    io.github.artcraftandroid.pdfcraft.ui.scan.ScanCameraSetting()
    io.github.artcraftandroid.pdfcraft.ui.scan.ScanLiveSetting()
    io.github.artcraftandroid.pdfcraft.ui.scan.ScanAutoSetting()
    io.github.artcraftandroid.pdfcraft.ui.scan.ScanMergeSettingRow()
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
    // a look per page and the looks strip's small previews (#301)
    SheetRow(null, stringResource(R.string.scan_lab_look_per_page), { lab.update(o.copy(lookPerPage = !o.lookPerPage)) }) {
        PdfSwitch(o.lookPerPage, { lab.update(o.copy(lookPerPage = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_look_strip), { lab.update(o.copy(lookStrip = !o.lookStrip)) }) {
        PdfSwitch(o.lookStrip, { lab.update(o.copy(lookStrip = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_whiteboard_look), { lab.update(o.copy(whiteboardLook = !o.whiteboardLook)) }) {
        PdfSwitch(o.whiteboardLook, { lab.update(o.copy(whiteboardLook = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_preview_stop), { lab.update(o.copy(previewStop = !o.previewStop)) }) {
        PdfSwitch(o.previewStop, { lab.update(o.copy(previewStop = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_look_reuse), { lab.update(o.copy(lookReuse = !o.lookReuse)) }) {
        PdfSwitch(o.lookReuse, { lab.update(o.copy(lookReuse = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_preview_zoom), { lab.update(o.copy(previewZoom = !o.previewZoom)) }) {
        PdfSwitch(o.previewZoom, { lab.update(o.copy(previewZoom = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_crop), { lab.update(o.copy(cropTools = !o.cropTools)) }) {
        PdfSwitch(o.cropTools, { lab.update(o.copy(cropTools = it)) })
    }
    // Auto-detect says when it finds nothing and does not repeat a search that found nothing (#443)
    SheetRow(null, stringResource(R.string.scan_lab_crop_detect_note), { lab.update(o.copy(cropDetectNote = !o.cropDetectNote)) }) {
        PdfSwitch(o.cropDetectNote, { lab.update(o.copy(cropDetectNote = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_flatten_auto), { lab.update(o.copy(flattenAuto = !o.flattenAuto)) }) {
        PdfSwitch(o.flattenAuto, { lab.update(o.copy(flattenAuto = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_flatten_trim), { lab.update(o.copy(flattenTrim = !o.flattenTrim)) }) {
        PdfSwitch(o.flattenTrim, { lab.update(o.copy(flattenTrim = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_flatten_line_floor), { lab.update(o.copy(flattenLineFloor = !o.flattenLineFloor)) }) {
        PdfSwitch(o.flattenLineFloor, { lab.update(o.copy(flattenLineFloor = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_line_flatten), { lab.update(o.copy(lineFlatten = !o.lineFlatten)) }) {
        PdfSwitch(o.lineFlatten, { lab.update(o.copy(lineFlatten = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_book_gutter), { lab.update(o.copy(bookGutter = !o.bookGutter)) }) {
        PdfSwitch(o.bookGutter, { lab.update(o.copy(bookGutter = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_upright), { lab.update(o.copy(upright = !o.upright)) }) {
        PdfSwitch(o.upright, { lab.update(o.copy(upright = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_settle_turns), { lab.update(o.copy(settleTurns = !o.settleTurns)) }) {
        PdfSwitch(o.settleTurns, { lab.update(o.copy(settleTurns = it)) })
    }
    val off = stringResource(R.string.scan_lab_off)
    Choice(stringResource(R.string.scan_lab_sharpen), ScanLab.SHARPENING.map { it to (if (it == 0) off else it.toString()) }, o.sharpen) { lab.update(o.copy(sharpen = it)) }
    SheetRow(null, stringResource(R.string.scan_lab_denoise), { lab.update(o.copy(denoise = !o.denoise)) }) {
        PdfSwitch(o.denoise, { lab.update(o.copy(denoise = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_grain_white), { lab.update(o.copy(grainWhite = !o.grainWhite)) }) {
        PdfSwitch(o.grainWhite, { lab.update(o.copy(grainWhite = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_presets), { lab.update(o.copy(presets = !o.presets)) }) {
        PdfSwitch(o.presets, { lab.update(o.copy(presets = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_kind_tone), { lab.update(o.copy(kindTone = !o.kindTone)) }) {
        PdfSwitch(o.kindTone, { lab.update(o.copy(kindTone = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_photo_sharpen), { lab.update(o.copy(photoSharpen = !o.photoSharpen)) }) {
        PdfSwitch(o.photoSharpen, { lab.update(o.copy(photoSharpen = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_suggestions), { lab.update(o.copy(suggestions = !o.suggestions)) }) {
        PdfSwitch(o.suggestions, { lab.update(o.copy(suggestions = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_strict_gutter), { lab.update(o.copy(strictGutter = !o.strictGutter)) }) {
        PdfSwitch(o.strictGutter, { lab.update(o.copy(strictGutter = it)) })
    }
    SheetRow(null, stringResource(R.string.scan_lab_strict_photo), { lab.update(o.copy(strictPhoto = !o.strictPhoto)) }) {
        PdfSwitch(o.strictPhoto, { lab.update(o.copy(strictPhoto = it)) })
    }
    // one curved side suggests flattening, a book page needs more (#442)
    SheetRow(null, stringResource(R.string.scan_lab_strict_book), { lab.update(o.copy(strictBook = !o.strictBook)) }) {
        PdfSwitch(o.strictBook, { lab.update(o.copy(strictBook = it)) })
    }
    // the slider's strength counts as set by hand, and sharpening scales the type's (#424)
    SheetRow(null, stringResource(R.string.scan_lab_page_strength), { lab.update(o.copy(pageStrength = !o.pageStrength)) }) {
        PdfSwitch(o.pageStrength, { lab.update(o.copy(pageStrength = it)) })
    }
    // a page whose outline was not found is a Document until it is cropped (#444)
    SheetRow(null, stringResource(R.string.scan_lab_unfound_document), { lab.update(o.copy(unfoundDocument = !o.unfoundDocument)) }) {
        PdfSwitch(o.unfoundDocument, { lab.update(o.copy(unfoundDocument = it)) })
    }
    // Back and Delete ask before a scan or a page is thrown away (#456)
    SheetRow(null, stringResource(R.string.scan_lab_ask_discard), { lab.update(o.copy(askDiscard = !o.askDiscard)) }) {
        PdfSwitch(o.askDiscard, { lab.update(o.copy(askDiscard = it)) })
    }
    // the research detectors (#305, #306): a report only, in kept scans, so it changes no page
    SheetRow(null, stringResource(R.string.scan_lab_inspect), { lab.update(o.copy(inspect = !o.inspect)) }) {
        PdfSwitch(o.inspect, { lab.update(o.copy(inspect = it)) })
    }
    val own = stringResource(R.string.scan_lab_strength_own)
    Choice(stringResource(R.string.scan_lab_strength), ScanLab.STRENGTHS.map { it to (it?.toString() ?: own) }, o.strength) { lab.update(o.copy(strength = it)) }
    Text(stringResource(R.string.scan_lab_options_body), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
    if (!o.asDesigned) SheetRow(null, stringResource(R.string.scan_lab_reset), { lab.reset() })

    // timing a learned clean-up model the person picks (#304): measurement only, nothing kept
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(lab::timeModel) }
    SheetRow(null, stringResource(if (lab.timingModel) R.string.scan_lab_time_model_running else R.string.scan_lab_time_model), { if (!lab.timingModel) pickModel.launch(arrayOf("*/*")) })
    Text(stringResource(R.string.scan_lab_time_model_body), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))

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
                    .selectable(selected = on, role = Role.RadioButton) { onPick(value) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}
