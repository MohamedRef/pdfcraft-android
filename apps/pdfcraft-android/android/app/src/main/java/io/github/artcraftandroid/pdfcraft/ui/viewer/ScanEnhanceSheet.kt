package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.PdfSwitch
import io.github.artcraftandroid.pdfcraft.ui.SheetBackHeader
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/** Opt-in native v0.19.0 scan cleanup for an open document. No source URI replacement. */
@Composable
fun ScanEnhanceSheet(vm: AppViewModel, doc: OpenDocument) {
    val ctl = doc.controller
    val currentPage = doc.viewer.currentPage
    val lastPage = minOf(doc.pageCount, currentPage + MAX_SCAN_ENHANCE_PAGES)
    var batch by remember(doc.id) { mutableStateOf(false) }
    var level by remember(doc.id) { mutableIntStateOf(50) }
    var edges by remember(doc.id) { mutableStateOf(true) }
    var speckles by remember(doc.id) { mutableStateOf(false) }
    var confirm by remember(doc.id) { mutableStateOf(false) }
    val plan = scanEnhancePlan(doc.pageCount, currentPage, batch, level, edges, speckles)
    val writable = vm.document === doc && doc.writable && !doc.info.signed &&
        doc.info.readOnlyReason == null && vm.busy == null

    PdfSheet(onDismiss = { ctl.sheet = ViewerSheet.None }, maxHeightFraction = 0.82f) {
        SheetBackHeader(stringResource(R.string.scan_enhance_title), onBack = { ctl.sheet = ViewerSheet.More })
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.scan_enhance_explanation), color = pdfColors.textSecondary)
            if (!writable) {
                Text(stringResource(R.string.scan_enhance_unavailable), color = pdfColors.red)
            }
            Row(
                Modifier.fillMaxWidth().clickable { batch = false },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = !batch, onClick = { batch = false })
                Text(stringResource(R.string.scan_enhance_current, currentPage + 1), color = pdfColors.text)
            }
            Row(
                Modifier.fillMaxWidth().clickable { batch = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = batch, onClick = { batch = true })
                Text(stringResource(R.string.scan_enhance_batch, currentPage + 1, lastPage), color = pdfColors.text)
            }
            Text(stringResource(R.string.scan_enhance_level, level), color = pdfColors.text)
            Slider(
                value = level.toFloat(),
                onValueChange = { level = it.toInt().coerceIn(0, 100) },
                valueRange = 0f..100f,
                steps = 19,
            )
            Row(Modifier.fillMaxWidth().clickable { edges = !edges },
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.scan_enhance_edges), color = pdfColors.text, modifier = Modifier.weight(1f))
                PdfSwitch(edges, { edges = it })
            }
            Row(Modifier.fillMaxWidth().clickable { speckles = !speckles },
                verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.scan_enhance_speckles), color = pdfColors.text, modifier = Modifier.weight(1f))
                PdfSwitch(speckles, { speckles = it })
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(
                    onClick = { confirm = true },
                    enabled = writable && plan != null,
                ) { Text(stringResource(R.string.scan_enhance_apply), color = pdfColors.accent) }
            }
            Spacer(Modifier.padding(bottom = 4.dp))
        }
    }

    if (confirm && plan != null) PdfDialog(
        onDismiss = { confirm = false },
        title = stringResource(R.string.scan_enhance_confirm_title),
        confirm = stringResource(R.string.scan_enhance_apply),
        confirmEnabled = writable,
        onConfirm = {
            confirm = false
            ctl.sheet = ViewerSheet.None
            vm.enhanceScannedPages(doc, plan)
        },
    ) {
        Text(stringResource(R.string.scan_enhance_confirm_body), color = pdfColors.text)
    }
}
