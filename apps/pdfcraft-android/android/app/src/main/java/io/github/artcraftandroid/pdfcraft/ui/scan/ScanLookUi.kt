package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagArea
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.ScanFilter
import io.github.artcraftandroid.pdfcraft.ui.SCAN_TILE_SIDE_DP
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** Put the scan's looks back as [next] says: only the pages whose look changed are replaced. */
private fun AppViewModel.setScanLooks(next: List<ScanPageLook>) {
    for (i in scanPages.indices) {
        val look = next.getOrNull(i) ?: continue
        if (scanPages[i].look != look) scanPages[i] = scanPages[i].copy(look = look)
    }
}

/**
 * The person chose [look] for page [index] (#301): that page alone, or every page with the
 * testing option for a look per page off. [why] is "look", "strength", "reset" or "suggestion", for the log;
 * the slider logs once, when it is let go ([log] false while it moves).
 */
internal fun AppViewModel.chooseScanLook(index: Int, look: ScanPageLook, why: String, log: Boolean = true) {
    val lab = scanLab.options
    val looks = scanPages.map { it.look }
    if (looks.getOrNull(index) == look && !log) return
    setScanLooks(scanChooseLook(looks, index, look, lab.lookPerPage))
    if (log) {
        Diag.event(
            DiagArea.Scan, "look_chosen", "index" to index + 1, "why" to why, "look" to scanFilterId(look.filter),
            "strength" to look.level, "strength_source" to look.strengthSource, "paper" to look.paper.word, "look_per_page" to lab.lookPerPage,
            "pages" to looks.size,
        )
    }
}

/**
 * Apply to all (#301): every page takes page [index]'s look and strength; corners and turns stay.
 * With every page made in the background (#465) it also takes the page's paper colour, document
 * type and Flatten page, so photos imported together are flattened with one tap.
 */
internal fun AppViewModel.applyScanLookToAll(index: Int) {
    val all = scanLab.options.backgroundPages
    val settings = scanPages.map { scanPageSettings(it, all) }
    val from = settings.getOrNull(index) ?: return
    val changed = settings.count { it != from }
    val next = scanApplyAll(settings, index)
    val touched = ArrayList<String>()
    for (i in scanPages.indices) {
        val s = next.getOrNull(i) ?: continue
        val page = scanPages[i]
        if (scanPageSettings(page, all) == s) continue
        touched += page.key
        scanPages[i] = if (all) page.copy(look = s.look, preset = s.preset, flatten = s.flatten) else page.copy(look = s.look)
    }
    pageQueue.appliedToAll(touched)
    Diag.event(
        DiagArea.Scan, "look_applied_all", "index" to index + 1, "look" to scanFilterId(from.look.filter), "strength" to from.look.level, "pages" to settings.size, "changed" to changed,
        // what it carried (#465): the document type and Flatten page too, with every page made in the background
        "carries_all" to all, "preset" to (from.preset ?: "auto"), "flatten" to scanFlattenChoiceId(from.flatten),
    )
}

/**
 * The looks strip (#301): each look as a small preview of the page shown, its name underneath,
 * the page's own look outlined. [shown] is the page's preview in its own look, used for that
 * look's tile. A tile whose preview is not ready yet shows its name on a plain card.
 */
@Composable
internal fun ScanLooksStrip(
    vm: AppViewModel, index: Int, look: ScanPageLook, shown: ImageBitmap?, modifier: Modifier = Modifier,
    /** The tiles' size: 72×96 on the old screen, 56×72 on Enhance (#459). */
    tileWidth: Int = SCAN_TILE_WIDTH_DP, tileHeight: Int = SCAN_TILE_SIDE_DP,
    /** What the log's `look` line says the choice came from (#459): `look` on the old screen, `strip` on Enhance. */
    from: String = "look",
) {
    val c = pdfColors
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp).selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (f in ScanFilter.entries) {
            val selected = f == look.filter
            val name = stringResource(f.label)
            val image = scanTileImage(f, look, vm.scanTiles, shown)
            val waiting = stringResource(R.string.scan_look_tile_waiting, name)
            Column(
                Modifier.width(tileWidth.dp).clip(RoundedCornerShape(6.dp))
                    .selectable(selected = selected, role = Role.RadioButton) { if (!selected) vm.chooseScanLook(index, ScanPageLook(f), from) }
                    .semantics { if (image == null) contentDescription = waiting }
                    .padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(tileWidth.dp, tileHeight.dp).clip(RoundedCornerShape(4.dp)).background(c.surfaceRaised)
                        .border(if (selected) 2.dp else 1.dp, if (selected) c.accent else c.textSecondary.copy(alpha = 0.4f), RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    // the name underneath says what the tile is; the image itself needs no description
                    if (image != null) Image(image, null, modifier = Modifier.fillMaxSize().padding(3.dp), contentScale = ContentScale.Fit)
                }
                Spacer(Modifier.size(4.dp))
                Text(
                    name, color = if (selected) c.accent else c.text, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center, lineHeight = 14.sp,
                )
            }
        }
    }
}

/** The looks as words, as before the strip (#301): the testing option's way back. */
@Composable
internal fun ScanLookChips(vm: AppViewModel, index: Int, look: ScanPageLook) {
    val c = pdfColors
    for (f in ScanFilter.entries) {
        val selected = f == look.filter
        Text(
            stringResource(f.label), color = if (selected) c.accent else c.text, fontSize = 14.sp,
            modifier = Modifier.clip(CircleShape).background(if (selected) c.pressed else Color.Transparent)
                .selectable(selected = selected, role = Role.RadioButton) { if (!selected) vm.chooseScanLook(index, ScanPageLook(f), "look") }
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}

/**
 * The strength slider for the page's look (#301), hidden for Original. While the testing option
 * sets one strength for every look, the slider shows it and can't be moved, and says why. It
 * starts from the strength the preview was made at (#424): in Clean scan, the document type's
 * ([kindStrength], from the preview) until the person moves it.
 */
@Composable
internal fun ScanStrengthRow(vm: AppViewModel, index: Int, look: ScanPageLook, kindStrength: Int? = null) {
    if (!look.hasStrength) return
    val c = pdfColors
    val lab = vm.scanLab.options
    val fixed = lab.strength
    val value = io.github.artcraftandroid.pdfcraft.ui.scanAppliedStrength(look, lab, kindStrength).strength
    val label = stringResource(R.string.scan_look_strength_label)
    val state = stringResource(R.string.scan_look_strength, value)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(state, color = c.text, fontSize = 14.sp, modifier = Modifier.width(SCAN_STRENGTH_LABEL_DP.dp), maxLines = 2)
        Slider(
            value = value.toFloat(),
            onValueChange = { v -> vm.chooseScanLook(index, look.copy(strength = scanSliderStrength(v)), "strength", log = false) },
            onValueChangeFinished = { vm.scanPages.getOrNull(index)?.let { vm.chooseScanLook(index, it.look, "strength") } },
            valueRange = 0f..100f,
            enabled = fixed == null,
            colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
            modifier = Modifier.weight(1f).semantics {
                contentDescription = label
                stateDescription = state
            },
        )
    }
    if (fixed != null) {
        Text(stringResource(R.string.scan_look_strength_fixed), color = c.textSecondary, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 16.dp))
    }
}

/**
 * The paper's colour for Clean scan and Whiteboard (#301): Automatic (the kind of document
 * decides, white unless it keeps the paper), White, or Keep color.
 */
@Composable
internal fun ScanPaperRow(vm: AppViewModel, index: Int, look: ScanPageLook) {
    if (!look.hasPaper) return
    val c = pdfColors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(stringResource(R.string.scan_paper_label), color = c.text, fontSize = 14.sp)
        for ((choice, label) in listOf(
            ScanPaperChoice.Auto to R.string.scan_paper_auto,
            ScanPaperChoice.White to R.string.scan_paper_white,
            ScanPaperChoice.Keep to R.string.scan_paper_keep,
        )) {
            val selected = choice == look.paper
            Text(
                stringResource(label), color = if (selected) c.accent else c.text, fontSize = 13.sp,
                modifier = Modifier.clip(CircleShape).background(if (selected) c.pressed else Color.Transparent)
                    .border(1.dp, if (selected) c.accent else c.textSecondary.copy(alpha = 0.5f), CircleShape)
                    .selectable(selected = selected, role = Role.RadioButton) { if (!selected) vm.chooseScanLook(index, look.copy(paper = choice), "paper") }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/** Apply to all's question, asked when some page has a look of its own that would change (#301). */
@Composable
internal fun ScanApplyLookDialog(vm: AppViewModel, index: Int, look: ScanPageLook, onDone: () -> Unit) {
    PdfDialog(
        onDismiss = onDone,
        title = stringResource(R.string.scan_apply_look_title),
        confirm = stringResource(R.string.scan_apply_look_confirm),
        onConfirm = {
            vm.applyScanLookToAll(index)
            onDone()
        },
    ) {
        // with every page made in the background it also carries the document type and Flatten page (#465)
        val body = if (vm.scanLab.options.backgroundPages) R.string.scan_apply_all_body else R.string.scan_apply_look_body
        Text(stringResource(body, stringResource(look.filter.label)), color = pdfColors.text, fontSize = 14.sp)
    }
}

/** A tile's width in the looks strip: three quarters of its height, about a page's shape. */
private const val SCAN_TILE_WIDTH_DP = SCAN_TILE_SIDE_DP * 3 / 4

/** Room for "Strength: 100" beside the slider, in the longer translations too. */
private const val SCAN_STRENGTH_LABEL_DP = 112
