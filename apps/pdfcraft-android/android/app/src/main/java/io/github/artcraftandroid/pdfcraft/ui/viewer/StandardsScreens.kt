package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.ActionPill
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.HairlineFix
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.PdfaReport
import io.github.artcraftandroid.pdfcraft.ui.PrintFinding
import io.github.artcraftandroid.pdfcraft.ui.PrintLayout
import io.github.artcraftandroid.pdfcraft.ui.PrintOptions
import io.github.artcraftandroid.pdfcraft.ui.PrintSubset
import io.github.artcraftandroid.pdfcraft.ui.SwitchRow
import io.github.artcraftandroid.pdfcraft.ui.ToolChoice
import io.github.artcraftandroid.pdfcraft.ui.ToolPage
import io.github.artcraftandroid.pdfcraft.ui.parsePageRange
import io.github.artcraftandroid.pdfcraft.ui.parsePdfaReport
import io.github.artcraftandroid.pdfcraft.ui.parsePrintFindings
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.launch

@Composable
private fun Tabs(labels: List<Int>, selected: Int, onSelect: (Int) -> Unit) {
    val c = pdfColors
    Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
        Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(c.divider))
        Row(Modifier.horizontalScroll(rememberScrollState())) {
            labels.forEachIndexed { i, label ->
                Column(Modifier.clickable { onSelect(i) }.padding(horizontal = 8.dp).width(IntrinsicSize.Max)) {
                    Text(stringResource(label), color = if (selected == i) c.label else c.text, fontSize = 16.sp, modifier = Modifier.padding(vertical = 12.dp))
                    Box(Modifier.fillMaxWidth().height(2.dp).background(if (selected == i) c.label else Color.Transparent))
                }
                if (i < labels.size - 1) Spacer(Modifier.width(14.dp))
            }
        }
    }
}

@Composable
private fun SmallChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val c = pdfColors
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) c.toolAccent.copy(alpha = 0.18f) else c.pressed)
            .border(1.dp, if (selected) c.toolAccent else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(role = Role.RadioButton, onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    ) { Text(label, color = c.label, fontSize = 14.sp) }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, color = pdfColors.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp, bottom = 4.dp))
}

@Composable
private fun Para(text: String, color: Color = pdfColors.text) {
    Text(text, color = color, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
}

/**
 * Check & prepare (#181): PDF/A (check against 2b or 3b, fix what can be fixed, remove the
 * identification), Print readiness (a read-only report) and Fix thin lines. Every report wraps at
 * phone width; the fixes are each one undo step.
 */
@Composable
fun StandardsScreen(vm: AppViewModel, doc: OpenDocument) {
    var tab by remember { mutableStateOf(0) }
    var level by remember { mutableStateOf("2b") }
    var pdfa by remember { mutableStateOf<PdfaReport?>(null) }
    var readiness by remember { mutableStateOf<Pair<List<PrintFinding>, String>?>(null) }
    var narrower by remember { mutableStateOf("0.216") }
    var width by remember { mutableStateOf("0.72") }
    var allPages by remember { mutableStateOf(true) }
    var range by remember { mutableStateOf("") }
    var running by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val c = pdfColors
    val fix = HairlineFix(narrower.toDoubleOrNull() ?: -1.0, width.toDoubleOrNull() ?: -1.0)
    val pages = if (allPages) null else parsePageRange(range, doc.info.pages.size)
    val editable = canEditDocument(doc)
    val (action, actionEnabled) = when (tab) {
        0 -> stringResource(R.string.std_pdfa_check) to !running
        1 -> stringResource(R.string.std_readiness_check) to !running
        else -> stringResource(R.string.std_hairlines_fix) to (fix.valid && (allPages || pages != null) && editable)
    }
    ToolPage(stringResource(R.string.std_title), { vm.toolScreen = null }, action, actionEnabled, {
        when (tab) {
            0 -> scope.launch { running = true; vm.report(doc, "pdfa_verify", mapOf("level" to level))?.let { pdfa = parsePdfaReport(it) }; running = false }
            1 -> scope.launch { running = true; vm.report(doc, "print_readiness")?.let { readiness = parsePrintFindings(it) to it.optString("text") }; running = false }
            else -> vm.fixHairlines(doc, fix, pages)
        }
    }) {
        Tabs(listOf(R.string.std_tab_pdfa, R.string.std_tab_readiness, R.string.std_tab_hairlines), tab) { tab = it }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            when (tab) {
                0 -> {
                    Para(stringResource(R.string.std_pdfa_explain))
                    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (l in listOf("2b", "3b")) SmallChip("PDF/A-$l", level == l) { level = l; pdfa = null }
                    }
                    if (running) Para("…")
                    pdfa?.let { r ->
                        r.declared?.let { Para(stringResource(R.string.std_pdfa_declares, it), c.textSecondary) }
                        if (r.compliant) {
                            SectionTitle(stringResource(R.string.std_pdfa_ok, r.level))
                        } else {
                            SectionTitle(pluralStringResource(R.plurals.std_pdfa_issues, r.issues.size, r.issues.size, r.level))
                            for (i in r.issues) {
                                val where = i.page?.let { stringResource(R.string.std_on_page, it) } ?: ""
                                val tag = stringResource(if (i.fixable) R.string.std_fixable else R.string.std_not_fixable)
                                Para("§${i.clause}$where · $tag\n${i.message}")
                            }
                            if (r.issues.any { it.fixable }) {
                                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp)) {
                                    ActionPill(stringResource(R.string.std_pdfa_fix), editable, { vm.convertToPdfa(doc, level) { pdfa = it } })
                                }
                            }
                        }
                        if (r.declared != null) {
                            Text(
                                stringResource(R.string.std_pdfa_remove_id), color = if (editable) c.red else c.textSecondary, fontSize = 15.sp,
                                modifier = Modifier.clickable(enabled = editable, role = Role.Button) { vm.removePdfaId(doc) { pdfa = null } }.padding(horizontal = 24.dp, vertical = 12.dp),
                            )
                        }
                    }
                }
                1 -> {
                    Para(stringResource(R.string.std_readiness_explain))
                    if (running) Para("…")
                    readiness?.let { (findings, text) ->
                        if (findings.isNotEmpty()) SectionTitle(stringResource(R.string.std_readiness_findings))
                        for (f in findings) {
                            val where = f.page?.let { stringResource(R.string.std_on_page, it) } ?: ""
                            Para("${f.severity.uppercase()}$where · ${f.message}", if (f.severity == "error") c.red else c.text)
                        }
                        SectionTitle(stringResource(R.string.std_readiness_report))
                        // the engine's plain-text report, wrapped to the screen
                        Para(text.trim(), c.textSecondary)
                    }
                }
                else -> {
                    Para(stringResource(R.string.std_hairlines_explain))
                    if (!editable) Para(stringResource(R.string.std_read_only), c.red)
                    SectionTitle(stringResource(R.string.std_hairlines_narrower))
                    PdfField(narrower, { narrower = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) }, "0.216",
                        modifier = Modifier.padding(horizontal = 24.dp), error = narrower.toDoubleOrNull()?.let { it in 0.0..10.0 } != true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    SectionTitle(stringResource(R.string.std_hairlines_width))
                    PdfField(width, { width = it.filter { ch -> ch.isDigit() || ch == '.' }.take(6) }, "0.72",
                        modifier = Modifier.padding(horizontal = 24.dp), error = width.toDoubleOrNull()?.let { it > 0 && it <= 10 } != true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    SectionTitle(stringResource(R.string.marks_pages))
                    ToolChoice(allPages, stringResource(R.string.tools_all_pages), null) { allPages = true }
                    ToolChoice(!allPages, stringResource(R.string.marks_page_range), null) { allPages = false }
                    if (!allPages) {
                        PdfField(range, { range = it.take(200) }, stringResource(R.string.marks_page_range_hint, doc.info.pages.size),
                            modifier = Modifier.padding(horizontal = 24.dp), error = range.isNotBlank() && pages == null)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * Print options (#185): pages, odd or even, reverse, layout (fit, actual size, shrink, several per
 * sheet, booklet) and comments, made into a print-ready PDF by `doc_print` for the system print
 * dialog (which still picks the printer, copies and paper), or saved to Downloads.
 */
@Composable
fun PrintOptionsScreen(vm: AppViewModel, doc: OpenDocument) {
    var o by remember { mutableStateOf(PrintOptions()) }
    val c = pdfColors
    ToolPage(
        stringResource(R.string.print_options_title), { vm.toolScreen = null }, stringResource(R.string.sheet_print), true, { vm.printWithOptions(doc, o, save = false) },
        trailing = {
            Text(stringResource(R.string.print_save_ready), color = c.label, fontSize = 16.sp,
                modifier = Modifier.clickable(role = Role.Button) { vm.printWithOptions(doc, o, save = true) }.padding(horizontal = 16.dp, vertical = 12.dp))
        },
    ) {
        Column(Modifier.verticalScroll(rememberScrollState())) {
            SectionTitle(stringResource(R.string.print_pages))
            PdfField(o.pages, { o = o.copy(pages = it.take(200)) }, stringResource(R.string.print_pages_hint), modifier = Modifier.padding(horizontal = 24.dp))
            Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SmallChip(stringResource(R.string.print_all), o.subset == PrintSubset.All) { o = o.copy(subset = PrintSubset.All) }
                SmallChip(stringResource(R.string.print_odd), o.subset == PrintSubset.Odd) { o = o.copy(subset = PrintSubset.Odd) }
                SmallChip(stringResource(R.string.print_even), o.subset == PrintSubset.Even) { o = o.copy(subset = PrintSubset.Even) }
            }
            SwitchRow(stringResource(R.string.print_reverse), o.reverse) { o = o.copy(reverse = it) }
            SectionTitle(stringResource(R.string.print_layout))
            listOf(
                PrintLayout.Fit to R.string.print_layout_fit, PrintLayout.Actual to R.string.print_layout_actual,
                PrintLayout.Shrink to R.string.print_layout_shrink, PrintLayout.Multiple to R.string.print_layout_multiple,
                PrintLayout.Booklet to R.string.print_layout_booklet,
            ).forEach { (l, label) -> ToolChoice(o.layout == l, stringResource(label), null) { o = o.copy(layout = l) } }
            if (o.layout == PrintLayout.Multiple) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (n in PrintOptions.PER_SHEET) SmallChip("$n", o.perSheet == n) { o = o.copy(perSheet = n) }
                }
                SwitchRow(stringResource(R.string.print_border), o.border) { o = o.copy(border = it) }
            }
            if (o.layout == PrintLayout.Booklet) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SmallChip(stringResource(R.string.print_binding_left), o.bookletBinding == "left") { o = o.copy(bookletBinding = "left") }
                    SmallChip(stringResource(R.string.print_binding_right), o.bookletBinding == "right") { o = o.copy(bookletBinding = "right") }
                }
            }
            SwitchRow(stringResource(R.string.print_comments), o.comments) { o = o.copy(comments = it) }
            Para(stringResource(R.string.print_explain), c.textSecondary)
            Spacer(Modifier.height(24.dp))
        }
    }
}
