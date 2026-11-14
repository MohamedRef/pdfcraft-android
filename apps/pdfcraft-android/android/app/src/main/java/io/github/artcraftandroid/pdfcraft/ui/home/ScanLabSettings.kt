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
import io.github.artcraftandroid.pdfcraft.ui.SheetDivider
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

    // a switch per option; the whole row is one control (#448)
    @Composable
    fun Sw(label: Int, on: Boolean, set: (Boolean) -> Unit) = SettingsSwitch(stringResource(label), on, set)

    // grouped in the order a scan goes through (#463)
    SettingsHeader(stringResource(R.string.scan_lab_group_kept))
    SettingsSwitch(stringResource(R.string.scan_lab_keep), o.keep, { lab.update(o.copy(keep = it)) }, stringResource(R.string.scan_lab_keep_body))
    if (lab.kept.pages > 0) {
        SettingsRow(stringResource(R.string.scan_lab_share, lab.kept.pages, formatSize(lab.kept.bytes)), { confirmShare = true })
        SettingsRow(stringResource(R.string.scan_lab_clear), { confirmClear = true })
    }

    SettingsHeader(stringResource(R.string.scan_lab_group_finding))
    SettingsChoice(
        stringResource(R.string.scan_lab_finder),
        listOf(
            ScanFinder.AUTO to stringResource(R.string.scan_lab_finder_auto),
            ScanFinder.NETWORK to stringResource(R.string.scan_lab_finder_network),
            ScanFinder.LINES to stringResource(R.string.scan_lab_finder_lines),
            ScanFinder.OFF to stringResource(R.string.scan_lab_finder_off),
        ),
        o.finder, { lab.update(o.copy(finder = it)) },
    )
    Sw(R.string.scan_lab_refine, o.refine) { lab.update(o.copy(refine = it)) }
    Sw(R.string.scan_lab_strict_gutter, o.strictGutter) { lab.update(o.copy(strictGutter = it)) }
    Sw(R.string.scan_lab_strict_photo, o.strictPhoto) { lab.update(o.copy(strictPhoto = it)) }
    // one curved side suggests flattening, a book page needs more (#442)
    Sw(R.string.scan_lab_strict_book, o.strictBook) { lab.update(o.copy(strictBook = it)) }
    // a page whose outline was not found is a Document until it is cropped (#444)
    Sw(R.string.scan_lab_unfound_document, o.unfoundDocument) { lab.update(o.copy(unfoundDocument = it)) }
    Sw(R.string.scan_lab_upright, o.upright) { lab.update(o.copy(upright = it)) }
    Sw(R.string.scan_lab_settle_turns, o.settleTurns) { lab.update(o.copy(settleTurns = it)) }

    SettingsHeader(stringResource(R.string.scan_lab_group_borders))
    Sw(R.string.scan_lab_perspective, o.perspective) { lab.update(o.copy(perspective = it)) }
    Sw(R.string.scan_lab_snap_paper, o.snapPaper) { lab.update(o.copy(snapPaper = it)) }
    // a dragged corner or side is pulled onto the paper's edge while the finger moves (#514)
    Sw(R.string.scan_lab_snap_drag, o.snapWhileDragging) { lab.update(o.copy(snapWhileDragging = it)) }
    Sw(R.string.scan_lab_crop, o.cropTools) { lab.update(o.copy(cropTools = it)) }
    // borders and looks for several pages in one pass (#515)
    Sw(R.string.scan_lab_batch, o.batchBorders) { lab.update(o.copy(batchBorders = it)) }
    // Auto-detect says when it finds nothing and does not repeat a search that found nothing (#443)
    Sw(R.string.scan_lab_crop_detect_note, o.cropDetectNote) { lab.update(o.copy(cropDetectNote = it)) }
    Sw(R.string.scan_lab_book_gutter, o.bookGutter) { lab.update(o.copy(bookGutter = it)) }

    SettingsHeader(stringResource(R.string.scan_lab_group_flattening))
    Sw(R.string.scan_lab_flatten_auto, o.flattenAuto) { lab.update(o.copy(flattenAuto = it)) }
    Sw(R.string.scan_lab_flatten_trim, o.flattenTrim) { lab.update(o.copy(flattenTrim = it)) }
    Sw(R.string.scan_lab_flatten_line_floor, o.flattenLineFloor) { lab.update(o.copy(flattenLineFloor = it)) }
    Sw(R.string.scan_lab_line_flatten, o.lineFlatten) { lab.update(o.copy(lineFlatten = it)) }

    SettingsHeader(stringResource(R.string.scan_lab_group_looks))
    Sw(R.string.scan_lab_preview, o.preview) { lab.update(o.copy(preview = it)) }
    Sw(R.string.scan_lab_preview_stop, o.previewStop) { lab.update(o.copy(previewStop = it)) }
    Sw(R.string.scan_lab_look_reuse, o.lookReuse) { lab.update(o.copy(lookReuse = it)) }
    Sw(R.string.scan_lab_preview_zoom, o.previewZoom) { lab.update(o.copy(previewZoom = it)) }
    // every page made in the background (#465)
    Sw(R.string.scan_lab_background_pages, o.backgroundPages) { lab.update(o.copy(backgroundPages = it)) }
    // every page made at full size before Save (#504)
    Sw(R.string.scan_lab_full_pages_ahead, o.fullPagesAhead) { lab.update(o.copy(fullPagesAhead = it)) }
    // the automatic look (#466): suggestions applied with Undo
    Sw(R.string.scan_lab_auto_look, o.autoLook) { lab.update(o.copy(autoLook = it)) }
    // fingers at the page's edge removed by the automatic look (#466)
    Sw(R.string.scan_lab_remove_fingers, o.removeFingers) { lab.update(o.copy(removeFingers = it)) }
    // a look per page and the looks strip's small previews (#301)
    Sw(R.string.scan_lab_look_per_page, o.lookPerPage) { lab.update(o.copy(lookPerPage = it)) }
    Sw(R.string.scan_lab_look_strip, o.lookStrip) { lab.update(o.copy(lookStrip = it)) }
    Sw(R.string.scan_lab_whiteboard_look, o.whiteboardLook) { lab.update(o.copy(whiteboardLook = it)) }
    val off = stringResource(R.string.scan_lab_off)
    SettingsChoice(stringResource(R.string.scan_lab_sharpen), ScanLab.SHARPENING.map { it to (if (it == 0) off else it.toString()) }, o.sharpen, { lab.update(o.copy(sharpen = it)) })
    Sw(R.string.scan_lab_denoise, o.denoise) { lab.update(o.copy(denoise = it)) }
    // a grainy page's paper drawn white from a lower level (#466)
    Sw(R.string.scan_lab_grain_white, o.grainWhite) { lab.update(o.copy(grainWhite = it)) }
    Sw(R.string.scan_lab_presets, o.presets) { lab.update(o.copy(presets = it)) }
    Sw(R.string.scan_lab_kind_tone, o.kindTone) { lab.update(o.copy(kindTone = it)) }
    Sw(R.string.scan_lab_photo_sharpen, o.photoSharpen) { lab.update(o.copy(photoSharpen = it)) }
    Sw(R.string.scan_lab_suggestions, o.suggestions) { lab.update(o.copy(suggestions = it)) }
    // the slider's strength counts as set by hand, and sharpening scales the type's (#424)
    Sw(R.string.scan_lab_page_strength, o.pageStrength) { lab.update(o.copy(pageStrength = it)) }
    val own = stringResource(R.string.scan_lab_strength_own)
    SettingsChoice(stringResource(R.string.scan_lab_strength), ScanLab.STRENGTHS.map { it to (it?.toString() ?: own) }, o.strength, { lab.update(o.copy(strength = it)) })

    // the in-app camera (#294), or the phone's camera app it replaced (the same setting as in Settings)
    SettingsHeader(stringResource(R.string.scan_lab_group_camera))
    io.github.artcraftandroid.pdfcraft.ui.scan.ScanCameraSetting()
    io.github.artcraftandroid.pdfcraft.ui.scan.ScanLiveSetting()
    io.github.artcraftandroid.pdfcraft.ui.scan.ScanAutoSetting()
    io.github.artcraftandroid.pdfcraft.ui.scan.ScanMergeSettingRow()
    // the camera's modes, icon bar and zoom (#457)
    Sw(R.string.scan_lab_camera_bar, o.cameraBar) { lab.update(o.copy(cameraBar = it)) }
    // the screens one job each (#455): Adjust borders after a photo (#458), Enhance (#459)
    Sw(R.string.scan_lab_steps, o.steps) { lab.update(o.copy(steps = it)) }
    // saved scans kept to be opened and saved again (#461)
    Sw(R.string.scan_lab_recent, o.recentScans) { lab.update(o.copy(recentScans = it)) }
    // every tool in reach: Markup on the bar, Select shown, Extract text and Print before saving, scans on Home (#516)
    Sw(R.string.scan_lab_reach, o.toolsInReach) { lab.update(o.copy(toolsInReach = it)) }
    // a saved scan edited from its PDF in the viewer (#513)
    Sw(R.string.scan_lab_edit_viewer, o.editFromViewer) { lab.update(o.copy(editFromViewer = it)) }
    // erase, pen, highlighter and text on a scanned page (#495)
    Sw(R.string.scan_lab_markup, o.markup) { lab.update(o.copy(markup = it)) }
    // Markup's colours: the viewer's palettes, custom, recent, the eyedropper and Erase's Color fill (#503)
    Sw(R.string.scan_lab_markup_colours, o.markupColours) { lab.update(o.copy(markupColours = it)) }
    // Erase's "Follow the edges" fill, while it is judged (#503)
    Sw(R.string.scan_lab_markup_edge, o.markupEdge) { lab.update(o.copy(markupEdge = it)) }
    // the pen drawn on the screen as the engine draws it, Undo at once, Remove marks (#520)
    Sw(R.string.scan_lab_markup_ink, o.markupInk) { lab.update(o.copy(markupInk = it)) }
    // each page stored as what is on it asks, and a size for every save (#505)
    Sw(R.string.scan_lab_auto_encode, o.autoEncode) { lab.update(o.copy(autoEncode = it)) }
    val ask = stringResource(R.string.scan_lab_size_ask)
    SettingsChoice(stringResource(R.string.scan_lab_size), listOf<Pair<String?, String>>(null to ask) + ScanLab.SIZES.map { it to stringResource(io.github.artcraftandroid.pdfcraft.ui.scan.scanSizeName(it)) }, o.size, { lab.update(o.copy(size = it)) })
    // Back and Delete ask before a scan or a page is thrown away (#456)
    Sw(R.string.scan_lab_ask_discard, o.askDiscard) { lab.update(o.copy(askDiscard = it)) }

    SettingsHeader(stringResource(R.string.scan_lab_group_reports))
    // the research detectors (#305, #306): a report only, in kept scans, so it changes no page
    Sw(R.string.scan_lab_inspect, o.inspect) { lab.update(o.copy(inspect = it)) }
    // timing a learned clean-up model the person picks (#304): measurement only, nothing kept
    val pickModel = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(lab::timeModel) }
    SettingsRow(
        stringResource(if (lab.timingModel) R.string.scan_lab_time_model_running else R.string.scan_lab_time_model),
        { if (!lab.timingModel) pickModel.launch(arrayOf("*/*")) }, summary = stringResource(R.string.scan_lab_time_model_body),
    )

    SheetDivider(vertical = 8.dp)
    SettingsNote(stringResource(R.string.scan_lab_options_body))
    if (!o.asDesigned) SettingsRow(stringResource(R.string.scan_lab_reset), { lab.reset() })

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
