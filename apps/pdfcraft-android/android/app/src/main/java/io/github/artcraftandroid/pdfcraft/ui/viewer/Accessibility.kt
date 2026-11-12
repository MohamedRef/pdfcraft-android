package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text as appText
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.PdfSwitch
import io.github.artcraftandroid.pdfcraft.ui.SheetBackHeader
import io.github.artcraftandroid.pdfcraft.ui.SheetDivider
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.copyOut
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/** One problem the checker found: its words and, when it has one, the page (0-based). */
data class A11yFinding(val message: String, val page: Int?)

/** One of the checker's rules: id (such as tagged-pdf), name, category, status and findings. */
data class A11yRule(val rule: String, val name: String, val category: String, val status: String, val findings: List<A11yFinding>)

/** A tagged figure: its number for accessibility_set_alt, page (0-based), alternate text and where it's drawn (top-left points). */
data class A11yFigure(val figure: Int, val page: Int?, val alt: String, val rect: FloatArray?)

/** What Tag text automatically proposes for one page. */
data class A11yPreview(val page: Int, val roles: List<Pair<String, String>>, val images: Int)

/** The rules accessibility_fix can mend on its own. */
val A11Y_FIXABLE = setOf("primary-language", "title", "tab-order")

/** Failed rules first, then the ones that need a person, passed, skipped; the checker's order within each. */
fun sortA11yRules(rules: List<A11yRule>): List<A11yRule> {
    val rank = mapOf("failed" to 0, "manual" to 1, "passed" to 2, "skipped" to 3)
    return rules.withIndex().sortedWith(compareBy({ rank[it.value.status] ?: 4 }, { it.index })).map { it.value }
}

/** The pages (0-based, each once, in order) a rule's findings name. */
fun a11yPages(rule: A11yRule): List<Int> = rule.findings.mapNotNull { it.page }.distinct().sorted()

/** A BCP 47 tag for the primary language, from the device's language when nothing was typed. */
fun a11yLanguage(typed: String, locale: Locale = Locale.getDefault()): String =
    typed.trim().ifEmpty { locale.toLanguageTag() }

fun parseA11yRules(o: JSONObject): List<A11yRule> {
    val rs = o.optJSONArray("results") ?: JSONArray()
    return (0 until rs.length()).map { i ->
        val r = rs.getJSONObject(i)
        val fs = r.optJSONArray("findings") ?: JSONArray()
        A11yRule(
            rule = r.optString("rule"), name = r.optString("name"), category = r.optString("category"), status = r.optString("status"),
            findings = (0 until fs.length()).map { j ->
                val f = fs.getJSONObject(j)
                A11yFinding(f.optString("message"), if (f.isNull("page")) null else f.optInt("page", 0).takeIf { it > 0 }?.minus(1))
            },
        )
    }
}

fun parseA11yFigures(o: JSONObject): List<A11yFigure> {
    val fs = o.optJSONArray("figures") ?: JSONArray()
    return (0 until fs.length()).map { i ->
        val f = fs.getJSONObject(i)
        val r = f.optJSONArray("rect")
        A11yFigure(
            figure = f.optInt("figure"),
            page = if (f.isNull("page")) null else f.optInt("page", 0).takeIf { it > 0 }?.minus(1),
            alt = if (f.isNull("alt")) "" else f.optString("alt"),
            rect = r?.takeIf { it.length() == 4 }?.let { a -> FloatArray(4) { a.getDouble(it).toFloat() } },
        )
    }
}

/** Accessibility (#182): the last check, the figures, the auto-tag preview and what's running. */
class AccessibilityState {
    var rules by mutableStateOf<List<A11yRule>?>(null)
    var counts by mutableStateOf(Triple(0, 0, 0))
    var figures by mutableStateOf<List<A11yFigure>>(emptyList())
    var preview by mutableStateOf<A11yPreview?>(null)
    var checking by mutableStateOf(false)
}

/** Run the full check and list the figures. */
fun AppViewModel.checkAccessibility(doc: OpenDocument) {
    val eng = engine ?: return
    accessibility.checking = true
    viewModelScope.launch {
        try {
            val t0 = System.nanoTime()
            val r = eng.tool("accessibility_check", mapOf("doc" to doc.id))
            accessibility.rules = sortA11yRules(parseA11yRules(r))
            accessibility.counts = Triple(r.optInt("failed"), r.optInt("manual"), r.optInt("passed"))
            accessibility.figures = parseA11yFigures(eng.tool("accessibility_figures", mapOf("doc" to doc.id)))
            editEvent(doc, "a11y_check", "failed" to r.optInt("failed"), "manual" to r.optInt("manual"), "passed" to r.optInt("passed"), "figures" to accessibility.figures.size, "ms" to (System.nanoTime() - t0) / 1_000_000)
        } catch (e: Exception) {
            tell(e.userMessage)
        } finally {
            accessibility.checking = false
        }
    }
}

/** Mend [rule] (one of [A11Y_FIXABLE]) with [value], then check again. */
fun AppViewModel.fixAccessibility(doc: OpenDocument, rule: String, value: String?) {
    val args = buildMap<String, Any?> {
        put("rule", rule)
        if (!value.isNullOrBlank()) put("value", value.trim())
    }
    runTool(doc, "accessibility_fix", args) { r ->
        editEvent(doc, "a11y_fix", "rule" to rule, "status" to r.optString("status"))
        tell(appText(if (r.optString("status") == "passed") R.string.a11y_fixed else R.string.a11y_fix_partial))
        checkAccessibility(doc)
    }
}

/** Set a figure's alternate text, or mark it decorative. */
fun AppViewModel.setAltText(doc: OpenDocument, figure: A11yFigure, alt: String, decorative: Boolean) {
    val args = if (decorative) mapOf("figure" to figure.figure, "decorative" to true) else mapOf("figure" to figure.figure, "alt" to alt.trim())
    runTool(doc, "accessibility_set_alt", args) { r ->
        editEvent(doc, "a11y_alt", "decorative" to decorative)
        accessibility.figures = parseA11yFigures(r)
        tell(appText(if (decorative) R.string.a11y_marked_decorative else R.string.a11y_alt_saved))
        checkAccessibility(doc)
    }
}

/** What Tag text automatically would do on [page] (0-based). */
fun AppViewModel.previewAutotag(doc: OpenDocument, page: Int) {
    val eng = engine ?: return
    viewModelScope.launch {
        try {
            val r = eng.tool("accessibility_autotag_preview", mapOf("doc" to doc.id, "page" to page + 1))
            val cs = r.optJSONArray("candidates") ?: JSONArray()
            accessibility.preview = A11yPreview(
                page,
                (0 until cs.length()).map { cs.getJSONObject(it).let { c -> c.optString("type") to c.optString("text") } },
                r.optInt("unclassified_images"),
            )
            editEvent(doc, "a11y_autotag_preview", "page" to page + 1, "candidates" to cs.length(), "unclassified_images" to r.optInt("unclassified_images"))
        } catch (e: Exception) {
            tell(e.userMessage)
        }
    }
}

fun AppViewModel.applyAutotag(doc: OpenDocument) {
    accessibility.preview = null
    runTool(doc, "accessibility_autotag_apply") { editEvent(doc, "a11y_autotag_apply"); tell(appText(R.string.a11y_tagged)); checkAccessibility(doc) }
}

fun AppViewModel.tagFormFields(doc: OpenDocument) {
    runTool(doc, "accessibility_autotag_form_fields") { editEvent(doc, "a11y_tag_fields"); tell(appText(R.string.a11y_fields_tagged)); checkAccessibility(doc) }
}

/** Write the checker's HTML report and save it where the user picks. */
fun AppViewModel.saveAccessibilityReport(doc: OpenDocument) {
    val eng = engine ?: return
    val ctx = getApplication<Application>()
    viewModelScope.launch {
        val out = File(ctx.cacheDir, "out-a11y-report-${System.nanoTime()}.html")
        try {
            eng.tool("accessibility_report", mapOf("doc" to doc.id, "path" to out.absolutePath))
        } catch (e: Exception) {
            out.delete()
            tell(e.userMessage)
            return@launch
        }
        editEvent(doc, "a11y_report")
        requests.create("${doc.name.removeSuffix(".pdf")}_accessibility.html", "text/html") { uri ->
            viewModelScope.launch {
                try {
                    if (uri != null) {
                        copyOut(ctx, out, uri)
                        tell(appText(R.string.a11y_report_saved))
                    }
                } catch (e: Exception) {
                    tell(e.userMessage)
                } finally {
                    out.delete()
                }
            }
        }
    }
}

private fun categoryLabel(id: String): Int? = when (id) {
    "document" -> R.string.a11y_cat_document
    "page_content" -> R.string.a11y_cat_page_content
    "forms" -> R.string.a11y_cat_forms
    "alternate_text" -> R.string.a11y_cat_alternate_text
    "tables" -> R.string.a11y_cat_tables
    "lists" -> R.string.a11y_cat_lists
    "headings" -> R.string.a11y_cat_headings
    else -> null
}

/**
 * Accessibility: the full check (failed rules first, with Fix where the engine can mend it and
 * Go to page), alternate text for figures, automatic tags for text and form fields, and the report.
 */
@Composable
fun AccessibilitySheet(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    val ctl = doc.controller
    val st = vm.accessibility
    var fixing by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<A11yFigure?>(null) }
    var showPassed by remember { mutableStateOf(false) }
    LaunchedEffect(doc.id) { vm.checkAccessibility(doc) }
    PdfSheet(onDismiss = { ctl.sheet = ViewerSheet.None }, maxHeightFraction = 0.9f) {
        SheetBackHeader(stringResource(R.string.a11y_title), onBack = { ctl.sheet = ViewerSheet.MoreTools })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            val rules = st.rules
            val (failed, manual, passed) = st.counts
            Text(
                if (rules == null) stringResource(R.string.a11y_checking) else stringResource(R.string.a11y_summary, failed, manual, passed),
                color = c.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(stringResource(R.string.a11y_about), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp))
            if (rules != null) {
                for (r in rules) {
                    if (r.status == "passed" || r.status == "skipped") {
                        if (!showPassed) continue
                    }
                    RuleRow(vm, doc, r) { fixing = r.rule }
                }
                if (rules.any { it.status == "passed" || it.status == "skipped" }) {
                    TextButton(onClick = { showPassed = !showPassed }, modifier = Modifier.padding(horizontal = 8.dp)) {
                        Text(stringResource(if (showPassed) R.string.a11y_hide_passed else R.string.a11y_show_passed), color = c.accent)
                    }
                }
            }
            SheetDivider(vertical = 6.dp)
            Text(stringResource(R.string.a11y_alt_text), color = c.text, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
            if (st.figures.isEmpty()) {
                Text(stringResource(R.string.a11y_no_figures), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            }
            for (f in st.figures.take(300)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { editing = f }.padding(horizontal = 20.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            f.page?.let { stringResource(R.string.a11y_figure_on_page, f.figure, it + 1) } ?: stringResource(R.string.a11y_figure, f.figure),
                            color = c.text, fontSize = 15.sp,
                        )
                        Text(
                            f.alt.ifEmpty { stringResource(R.string.a11y_no_alt) }, color = if (f.alt.isEmpty()) c.red else c.textSecondary,
                            fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            SheetDivider(vertical = 6.dp)
            SheetRow(LucideIcons.Tag, stringResource(R.string.a11y_autotag), { vm.previewAutotag(doc, doc.viewer.currentPage) })
            SheetRow(LucideIcons.TextCursorInput, stringResource(R.string.a11y_tag_fields), { vm.tagFormFields(doc) })
            SheetRow(LucideIcons.FileText, stringResource(R.string.a11y_save_report), { vm.saveAccessibilityReport(doc) })
            SheetRow(LucideIcons.ListChecks, stringResource(R.string.a11y_check_again), { vm.checkAccessibility(doc) })
            Spacer(Modifier.height(8.dp))
        }
    }
    fixing?.let { rule -> FixDialog(vm, doc, rule) { fixing = null } }
    editing?.let { f -> AltTextDialog(vm, doc, f) { editing = null } }
    st.preview?.let { p -> AutotagDialog(vm, doc, p) }
}

@Composable
private fun RuleRow(vm: AppViewModel, doc: OpenDocument, r: A11yRule, onFix: () -> Unit) {
    val c = pdfColors
    val ctl = doc.controller
    val status = when (r.status) {
        "failed" -> R.string.a11y_status_failed
        "manual" -> R.string.a11y_status_manual
        "passed" -> R.string.a11y_status_passed
        else -> R.string.a11y_status_skipped
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r.name, color = c.text, fontSize = 15.sp)
                val cat = categoryLabel(r.category)?.let { stringResource(it) } ?: r.category
                Text("${stringResource(status)} · $cat", color = if (r.status == "failed") c.red else c.textSecondary, fontSize = 13.sp)
            }
            if (r.status == "failed" && r.rule in A11Y_FIXABLE) {
                TextButton(onClick = onFix) { Text(stringResource(R.string.a11y_fix), color = c.accent, fontWeight = FontWeight.SemiBold) }
            }
        }
        for (f in r.findings.take(3)) {
            Text(f.message, color = c.textSecondary, fontSize = 13.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (r.findings.size > 3) {
            Text(stringResource(R.string.a11y_more_findings, r.findings.size - 3), color = c.textSecondary, fontSize = 13.sp)
        }
        val pages = a11yPages(r)
        if (pages.isNotEmpty()) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                for (p in pages.take(6)) {
                    TextButton(onClick = { ctl.sheet = ViewerSheet.None; doc.viewer.goToPage(p, 0f) }) {
                        Text(stringResource(R.string.a11y_go_to_page, p + 1), color = c.accent, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** Fix: the language and title take a value (the device's language, the file's name), tab order doesn't. */
@Composable
private fun FixDialog(vm: AppViewModel, doc: OpenDocument, rule: String, onClose: () -> Unit) {
    val c = pdfColors
    var value by remember {
        mutableStateOf(
            when (rule) {
                "primary-language" -> a11yLanguage("")
                "title" -> doc.name.removeSuffix(".pdf")
                else -> ""
            },
        )
    }
    PdfDialog(
        onDismiss = onClose,
        title = stringResource(
            when (rule) {
                "primary-language" -> R.string.a11y_fix_language
                "title" -> R.string.a11y_fix_title
                else -> R.string.a11y_fix_tab_order
            },
        ),
        confirm = stringResource(R.string.a11y_fix),
        confirmEnabled = rule == "tab-order" || value.isNotBlank(),
        onConfirm = {
            onClose()
            vm.fixAccessibility(doc, rule, if (rule == "primary-language") a11yLanguage(value) else value.takeIf { rule == "title" })
        },
    ) {
        when (rule) {
            "primary-language" -> PdfField(value, { value = it }, stringResource(R.string.a11y_language_hint))
            "title" -> PdfField(value, { value = it }, stringResource(R.string.a11y_title_hint))
            else -> Text(stringResource(R.string.a11y_tab_order_body), color = c.text, fontSize = 14.sp)
        }
    }
}

@Composable
private fun AltTextDialog(vm: AppViewModel, doc: OpenDocument, f: A11yFigure, onClose: () -> Unit) {
    val c = pdfColors
    var alt by remember { mutableStateOf(f.alt) }
    var decorative by remember { mutableStateOf(false) }
    PdfDialog(
        onDismiss = onClose,
        title = f.page?.let { stringResource(R.string.a11y_figure_on_page, f.figure, it + 1) } ?: stringResource(R.string.a11y_figure, f.figure),
        confirm = stringResource(R.string.common_save),
        onConfirm = { onClose(); vm.setAltText(doc, f, alt, decorative) },
    ) {
        if (!decorative) PdfField(alt, { alt = it }, stringResource(R.string.a11y_alt_hint))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = decorative, role = Role.Switch) { decorative = it },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(stringResource(R.string.a11y_decorative), color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
            PdfSwitch(decorative, null)
        }
        if (f.page != null) {
            TextButton(onClick = { onClose(); doc.controller.sheet = ViewerSheet.None; vm.flashRegion(doc, f.page, f.rect) }) {
                Text(stringResource(R.string.a11y_show_figure), color = c.accent)
            }
        }
    }
}

/** What auto-tagging proposes for the current page, before it tags the whole document. */
@Composable
private fun AutotagDialog(vm: AppViewModel, doc: OpenDocument, p: A11yPreview) {
    val c = pdfColors
    PdfDialog(
        onDismiss = { vm.accessibility.preview = null },
        title = stringResource(R.string.a11y_autotag),
        confirm = stringResource(R.string.a11y_autotag_apply),
        confirmEnabled = p.roles.isNotEmpty(),
        onConfirm = { vm.applyAutotag(doc) },
    ) {
        Text(stringResource(R.string.a11y_autotag_body, p.page + 1), color = c.textSecondary, fontSize = 13.sp)
        Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState()).padding(vertical = 6.dp)) {
            if (p.roles.isEmpty()) Text(stringResource(R.string.a11y_autotag_none), color = c.text, fontSize = 14.sp)
            for ((role, text) in p.roles.take(100)) {
                Text("$role · $text", color = c.text, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(vertical = 3.dp))
            }
            if (p.images > 0) {
                Text(androidx.compose.ui.res.pluralStringResource(R.plurals.a11y_autotag_images, p.images, p.images), color = c.red, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}
