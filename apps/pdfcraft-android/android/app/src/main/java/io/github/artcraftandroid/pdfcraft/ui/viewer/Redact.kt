package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.text as appText
import io.github.artcraftandroid.pdfcraft.plural
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
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.launch
import org.json.JSONObject

/** The built-in patterns Find & mark offers, as `redact_mark` names them. */
enum class RedactPattern(val arg: String, val label: Int) {
    Phone("phone", R.string.redact_pattern_phone),
    Email("email", R.string.redact_pattern_email),
    Card("credit-card", R.string.redact_pattern_card),
    Ssn("ssn", R.string.redact_pattern_ssn),
    Date("date", R.string.redact_pattern_date),
    Iban("iban", R.string.redact_pattern_iban),
}

/** What Find & mark looks for: typed text (one phrase, or several separated by commas), a pattern, or a regular expression. */
sealed class RedactSearch {
    data class Text(val text: String, val wholeWords: Boolean, val caseSensitive: Boolean) : RedactSearch()
    data class Pattern(val pattern: RedactPattern) : RedactSearch()
    data class Regex(val expression: String) : RedactSearch()
}

/** How marked boxes look once applied: their fill (#rrggbb) and the words shown on them. */
data class RedactLook(val fill: String = "#000000", val overlay: String = "")

/**
 * The `redact_mark` arguments for [search] with [look]: several comma-separated phrases become
 * `words` (every match of each), one phrase `find`. Null when there is nothing to look for.
 */
fun redactSearchArgs(search: RedactSearch, look: RedactLook): Map<String, Any?>? {
    val args = mutableMapOf<String, Any?>()
    when (search) {
        is RedactSearch.Text -> {
            val parts = search.text.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            when {
                parts.isEmpty() -> return null
                parts.size == 1 -> args["find"] = parts[0]
                else -> args["words"] = parts
            }
            args["whole_words"] = search.wholeWords
            args["case_sensitive"] = search.caseSensitive
        }
        is RedactSearch.Pattern -> args["pattern"] = search.pattern.arg
        is RedactSearch.Regex -> args["regex"] = search.expression.takeIf { it.isNotBlank() } ?: return null
    }
    return args + lookArgs(look)
}

/** The box's look, as every `redact_mark` call takes it. */
fun lookArgs(look: RedactLook): Map<String, Any?> =
    mapOf("fill" to look.fill) + (look.overlay.trim().takeIf { it.isNotEmpty() }?.let { mapOf("overlay" to it) } ?: emptyMap())

/** A redaction mark waiting to be applied: where it is (0-based page, points) and its place in the page's comments. */
data class RedactMark(val page: Int, val index: Int, val rect: FloatArray)

/** The pending marks among `comment_list`'s comments (type Redact). */
fun parseRedactMarks(o: JSONObject): List<RedactMark> {
    val list = o.optJSONArray("comments") ?: return emptyList()
    return (0 until list.length()).mapNotNull { i ->
        val c = list.optJSONObject(i) ?: return@mapNotNull null
        if (c.optString("type") != "Redact") return@mapNotNull null
        val r = c.optJSONArray("rect") ?: return@mapNotNull null
        RedactMark(c.optInt("page", 1) - 1, c.optInt("index", 1), FloatArray(4) { r.optDouble(it).toFloat() })
    }.sortedWith(compareBy({ it.page }, { it.rect[1] }, { it.rect[0] }))
}

/** A fake redaction `doc_audit_redactions` found: covered but still readable text, or a mark never applied. */
data class RedactFinding(val page: Int, val kind: String, val rect: FloatArray?, val text: String)

fun parseRedactFindings(o: JSONObject): List<RedactFinding> {
    val list = o.optJSONArray("findings") ?: return emptyList()
    return (0 until list.length()).mapNotNull { i ->
        val f = list.optJSONObject(i) ?: return@mapNotNull null
        val r = f.optJSONArray("rect")
        RedactFinding(f.optInt("page", 1) - 1, f.optString("kind"), r?.let { a -> FloatArray(4) { a.optDouble(it).toFloat() } }, f.optString("covered_text").take(300))
    }
}

/** One category of hidden information (`doc_hidden_info`). */
data class HiddenCategory(val id: String, val label: String, val count: Int)

/** Redact (#163): the marks, the box drawn by touch, the look, the apply confirmation and the after-save checks. */
class RedactState {
    var marks by mutableStateOf<List<RedactMark>>(emptyList())
    var placing by mutableStateOf<OpenDocument?>(null)
    var look by mutableStateOf(RedactLook())
    var confirmApply by mutableStateOf(false)
    var hidden by mutableStateOf<List<HiddenCategory>?>(null)
    var findings by mutableStateOf<List<RedactFinding>?>(null)
}

fun AppViewModel.openRedact(doc: OpenDocument) {
    if (!doc.info.readOnlyReason.isNullOrEmpty()) {
        tell(doc.info.readOnlyReason ?: "")
        return
    }
    doc.controller.sheet = ViewerSheet.Redact
    loadRedactMarks(doc)
}

fun AppViewModel.loadRedactMarks(doc: OpenDocument) {
    val eng = engine ?: return
    viewModelScope.launch {
        redact.marks = runCatching { parseRedactMarks(eng.tool("comment_list", mapOf("doc" to doc.id))) }.getOrDefault(emptyList())
    }
}

/** Mark with [args] (one `redact_mark` call, one undo step) and say how many marks it made. */
fun AppViewModel.markRedaction(doc: OpenDocument, args: Map<String, Any?>) {
    runTool(doc, "redact_mark", args) { r ->
        val n = r.optInt("marked")
        editEvent(doc, "redact_mark", "how" to redactHow(args), "marked" to n)
        if (n == 0) tell(appText(R.string.redact_no_matches)) else tell(plural(R.plurals.redact_marked, n, n), appText(R.string.common_undo)) { undo(doc) }
        loadRedactMarks(doc)
    }
}

fun AppViewModel.markArea(doc: OpenDocument, page: Int, rect: FloatArray) {
    redact.placing = null
    doc.controller.sheet = ViewerSheet.Redact
    markRedaction(doc, mapOf("page" to page + 1, "rect" to rect.map { it.toDouble() }) + lookArgs(redact.look))
}

fun AppViewModel.markPage(doc: OpenDocument, page: Int) {
    markRedaction(doc, mapOf("page" to page + 1, "whole_pages" to true) + lookArgs(redact.look))
}

fun AppViewModel.deleteRedactMark(doc: OpenDocument, m: RedactMark) {
    runTool(doc, "comment_delete", mapOf("page" to m.page + 1, "index" to m.index)) { loadRedactMarks(doc) }
}

fun AppViewModel.clearRedactMarks(doc: OpenDocument) {
    runTool(doc, "redact_clear") { loadRedactMarks(doc) }
}

/** Apply every mark: the content under them goes for good once saved; then offer Save a copy and the hidden-information check. */
fun AppViewModel.applyRedactions(doc: OpenDocument) {
    redact.confirmApply = false
    runTool(doc, "redact_apply") { r ->
        redact.marks = emptyList()
        editEvent(doc, "redact_apply", "applied" to r.optInt("applied"))
        tell(plural(R.plurals.redact_applied, r.optInt("applied"), r.optInt("applied")), appText(R.string.common_save_copy)) { saveCopyAs(doc) }
        checkHiddenInfo(doc)
    }
}

/** What Remove hidden information would remove, shown for the user to choose. */
fun AppViewModel.checkHiddenInfo(doc: OpenDocument) {
    val eng = engine ?: return
    viewModelScope.launch {
        try {
            val o = eng.tool("doc_hidden_info", mapOf("doc" to doc.id))
            val cats = o.optJSONArray("categories")
            redact.hidden = (0 until (cats?.length() ?: 0)).mapNotNull { i ->
                val c = cats?.optJSONObject(i) ?: return@mapNotNull null
                HiddenCategory(c.optString("category"), c.optString("label"), c.optInt("count"))
            }.filter { it.count > 0 }
            editEvent(doc, "hidden_info", "categories" to redact.hidden?.size)
        } catch (e: Exception) {
            tell(e.userMessage)
        }
    }
}

fun AppViewModel.removeHidden(doc: OpenDocument, categories: List<String>) {
    redact.hidden = null
    if (categories.isEmpty()) return
    runTool(doc, "doc_remove_hidden", mapOf("categories" to categories)) {
        editEvent(doc, "hidden_removed", "categories" to categories.size)
        tell(appText(R.string.redact_hidden_removed), appText(R.string.common_undo)) { undo(doc) } }
}

/** Check for fake redactions: boxes over text that can still be read, and marks never applied. */
fun AppViewModel.auditRedactions(doc: OpenDocument) {
    val eng = engine ?: return
    viewModelScope.launch {
        try {
            redact.findings = editMeasure(doc, "redact_audit") { parseRedactFindings(eng.tool("doc_audit_redactions", mapOf("doc" to doc.id))) }
            editEvent(doc, "redact_audit_found", "findings" to redact.findings?.size)
        } catch (e: Exception) {
            tell(e.userMessage)
        }
    }
}

/** Show [page] with [rect] (points) flashed, as a link target is. */
fun AppViewModel.flashRegion(doc: OpenDocument, page: Int, rect: FloatArray?) {
    doc.viewer.goToPage(page, ((rect?.get(1) ?: 0f) - 60f).coerceAtLeast(0f))
    if (rect == null) return
    viewModelScope.launch {
        kotlinx.coroutines.delay(250)
        doc.linkFlash = LinkFlash(page, rect)
        kotlinx.coroutines.delay(600)
        doc.linkFlash = null
    }
}

/**
 * Redact: mark an area, the current page, or every match of text, a pattern or an expression;
 * review the marks (tap to go there, delete one, or clear them all) and Apply, which first asks.
 */
@Composable
fun RedactSheet(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    val ctl = doc.controller
    var find by remember { mutableStateOf(false) }
    LaunchedEffect(doc.info.generation) { vm.loadRedactMarks(doc) }
    PdfSheet(onDismiss = { ctl.sheet = ViewerSheet.None }, maxHeightFraction = 0.86f) {
        SheetBackHeader(stringResource(R.string.redact_title), onBack = { ctl.sheet = ViewerSheet.More })
        Column(Modifier.verticalScroll(rememberScrollState())) {
            Text(stringResource(R.string.redact_about), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
            SheetRow(LucideIcons.SquareDashed, stringResource(R.string.redact_mark_area), { ctl.sheet = ViewerSheet.None; vm.redact.placing = doc })
            SheetRow(LucideIcons.File, stringResource(R.string.redact_mark_page, doc.viewer.currentPage + 1), { vm.markPage(doc, doc.viewer.currentPage) })
            SheetRow(LucideIcons.Search, stringResource(R.string.redact_find), { find = true })
            LookRow(vm)
            SheetDivider(vertical = 6.dp)
            val marks = vm.redact.marks
            Text(
                androidx.compose.ui.res.pluralStringResource(R.plurals.redact_marks_count, marks.size, marks.size), color = c.text, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            for (m in marks.take(200)) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { ctl.sheet = ViewerSheet.None; vm.flashRegion(doc, m.page, m.rect) }.padding(start = 20.dp, end = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(stringResource(R.string.redact_mark_on_page, m.page + 1), color = c.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
                    io.github.artcraftandroid.pdfcraft.ui.BarIcon(LucideIcons.Trash2, stringResource(R.string.redact_delete_mark, m.page + 1), { vm.deleteRedactMark(doc, m) })
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(enabled = marks.isNotEmpty(), onClick = { vm.clearRedactMarks(doc) }) { Text(stringResource(R.string.redact_clear), color = if (marks.isNotEmpty()) c.accent else c.textSecondary) }
                TextButton(enabled = marks.isNotEmpty(), onClick = { vm.redact.confirmApply = true }) { Text(stringResource(R.string.redact_apply), color = if (marks.isNotEmpty()) c.red else c.textSecondary, fontWeight = FontWeight.SemiBold) }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
    if (find) FindAndMarkDialog(vm, doc) { find = false }
    if (vm.redact.confirmApply) {
        PdfDialog(
            onDismiss = { vm.redact.confirmApply = false }, title = stringResource(R.string.redact_apply_title),
            confirm = stringResource(R.string.redact_apply), onConfirm = { vm.applyRedactions(doc) }, destructive = true,
        ) { Text(stringResource(R.string.redact_apply_body), color = c.text, fontSize = 14.sp) }
    }
    vm.redact.hidden?.let { HiddenInfoDialog(vm, doc, it) }
}

/** The boxes' colour (black, white, red) and the words shown on them once applied. */
@Composable
private fun LookRow(vm: AppViewModel) {
    val c = pdfColors
    val look = vm.redact.look
    Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
        Text(stringResource(R.string.redact_look), color = c.textSecondary, fontSize = 13.sp)
        Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((hex, label) in listOf("#000000" to R.string.prefs_color_black, "#ffffff" to R.string.prefs_color_white, "#e32121" to R.string.prefs_color_red)) {
                val on = look.fill.equals(hex, ignoreCase = true)
                val name = stringResource(label)
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).background(parseHex(hex))
                        .toggleable(value = on, role = Role.RadioButton) { vm.redact.look = look.copy(fill = hex) }
                        .semantics { contentDescription = name },
                ) {
                    Canvas(Modifier.fillMaxSize()) { drawCircle(if (on) c.accent else c.textSecondary, style = Stroke((if (on) 3 else 1).dp.toPx())) }
                }
            }
        }
        PdfField(look.overlay, { vm.redact.look = look.copy(overlay = it) }, stringResource(R.string.redact_overlay_hint))
    }
}

/** Find & mark: text (several phrases separated by commas), one of the patterns, or an expression. */
@Composable
private fun FindAndMarkDialog(vm: AppViewModel, doc: OpenDocument, onClose: () -> Unit) {
    val c = pdfColors
    var text by remember { mutableStateOf("") }
    var whole by remember { mutableStateOf(false) }
    var case by remember { mutableStateOf(false) }
    var pattern by remember { mutableStateOf<RedactPattern?>(null) }
    var regex by remember { mutableStateOf(false) }
    val search = when {
        pattern != null -> RedactSearch.Pattern(pattern!!)
        regex -> RedactSearch.Regex(text)
        else -> RedactSearch.Text(text, whole, case)
    }
    val args = redactSearchArgs(search, vm.redact.look)
    PdfDialog(
        onDismiss = onClose, title = stringResource(R.string.redact_find), confirm = stringResource(R.string.redact_mark_all),
        confirmEnabled = args != null, onConfirm = { onClose(); args?.let { vm.markRedaction(doc, it) } },
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
            if (pattern == null) {
                PdfField(text, { text = it }, stringResource(if (regex) R.string.redact_regex_hint else R.string.redact_find_hint))
                if (!regex) {
                    Toggle(stringResource(R.string.redact_whole_words), whole) { whole = it }
                    Toggle(stringResource(R.string.redact_match_case), case) { case = it }
                }
                Toggle(stringResource(R.string.redact_use_regex), regex) { regex = it }
            }
            Text(stringResource(R.string.redact_patterns), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (p in RedactPattern.entries) {
                    val on = pattern == p
                    Text(
                        stringResource(p.label), color = if (on) c.accent else c.text, fontSize = 14.sp,
                        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.clip(RoundedCornerShape(16.dp)).background(if (on) c.pressed else Color.Transparent)
                            .toggleable(value = on, role = Role.Checkbox) { pattern = if (on) null else p }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).toggleable(value = on, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = pdfColors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        PdfSwitch(on, null)
    }
}

/** After applying: the hidden information still in the file, by category, to remove. */
@Composable
private fun HiddenInfoDialog(vm: AppViewModel, doc: OpenDocument, categories: List<HiddenCategory>) {
    val c = pdfColors
    val chosen = remember(categories) { androidx.compose.runtime.mutableStateListOf<String>().apply { addAll(categories.map { it.id }) } }
    PdfDialog(
        onDismiss = { vm.redact.hidden = null }, title = stringResource(R.string.redact_hidden_title),
        confirm = if (categories.isEmpty()) stringResource(R.string.common_ok) else stringResource(R.string.redact_hidden_remove),
        confirmEnabled = categories.isEmpty() || chosen.isNotEmpty(),
        onConfirm = { vm.removeHidden(doc, chosen.toList()) },
        dismiss = if (categories.isEmpty()) null else stringResource(R.string.common_cancel),
    ) {
        if (categories.isEmpty()) {
            Text(stringResource(R.string.redact_hidden_none), color = c.text, fontSize = 14.sp)
        } else {
            Text(stringResource(R.string.redact_hidden_body), color = c.text, fontSize = 14.sp)
            for (cat in categories) {
                Toggle("${cat.label} (${cat.count})", cat.id in chosen) { on -> if (on) chosen.add(cat.id) else chosen.remove(cat.id) }
            }
        }
    }
}

/**
 * Check for fake redactions (File details): boxes painted over text that can still be read or
 * copied, and redaction marks never applied, each with its page; tap to go there.
 */
@Composable
fun RedactionAuditDialog(vm: AppViewModel, doc: OpenDocument, findings: List<RedactFinding>) {
    val c = pdfColors
    PdfDialog(
        onDismiss = { vm.redact.findings = null }, title = stringResource(R.string.redact_audit_title),
        confirm = stringResource(R.string.common_ok), onConfirm = { vm.redact.findings = null }, dismiss = null,
    ) {
        if (findings.isEmpty()) {
            Text(stringResource(R.string.redact_audit_none), color = c.text, fontSize = 14.sp)
        } else {
            Text(androidx.compose.ui.res.pluralStringResource(R.plurals.redact_audit_found, findings.size, findings.size), color = c.text, fontSize = 14.sp)
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                for (f in findings) {
                    Column(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable {
                            vm.redact.findings = null
                            doc.controller.sheet = ViewerSheet.None
                            vm.flashRegion(doc, f.page, f.rect)
                        }.padding(vertical = 6.dp),
                    ) {
                        Text(
                            stringResource(if (f.kind == "unapplied-mark") R.string.redact_audit_unapplied else R.string.redact_audit_covered, f.page + 1),
                            color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                        )
                        if (f.text.isNotBlank()) Text(f.text, color = c.textSecondary, fontSize = 13.sp, maxLines = 2)
                    }
                }
            }
        }
    }
}

/**
 * Mark an area: drag a box with one finger (two still scroll and zoom); the box is marked on
 * the page it starts on.
 */
@Composable
fun RedactPlaceLayer(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    Box(
        Modifier.fillMaxSize().toolGestures(
            doc,
            onDown = { p, _, _ -> start = p; end = p },
            onMove = { p, _, _ -> end = p },
            onUp = { p, moved ->
                val s = start
                start = null
                end = null
                if (vm.redact.placing !== doc || !moved || s == null) return@toolGestures
                val at = doc.viewer.screenToPage(s) ?: return@toolGestures
                val page = doc.info.pages.getOrNull(at.page) ?: return@toolGestures
                val ppp = doc.viewer.pixelsPerPoint(at.page).coerceAtLeast(0.01f)
                val x1 = (at.x + (p.x - s.x) / ppp).coerceIn(0f, page.width)
                val y1 = (at.y + (p.y - s.y) / ppp).coerceIn(0f, page.height)
                val rect = floatArrayOf(minOf(at.x, x1), minOf(at.y, y1), maxOf(at.x, x1), maxOf(at.y, y1))
                if (rect[2] - rect[0] >= 2f && rect[3] - rect[1] >= 2f) vm.markArea(doc, at.page, rect)
            },
            onCancel = { start = null; end = null },
        ),
    ) {
        val s = start
        val e = end
        if (s != null && e != null) {
            Canvas(Modifier.fillMaxSize()) {
                val tl = Offset(minOf(s.x, e.x), minOf(s.y, e.y))
                val size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y))
                drawRect(Color(0x55E32121), tl, size)
                drawRect(Color(0xFFE32121), tl, size, style = Stroke(2.dp.toPx()))
            }
        }
        val top = with(LocalDensity.current) { doc.viewer.padTop.toDp() }
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = top + 12.dp, start = 16.dp, end = 16.dp)
                .clip(RoundedCornerShape(8.dp)).background(c.surface).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LucideIcons.SquareDashed, null, tint = c.icon, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.redact_place_hint), color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false))
            TextButton(onClick = { vm.redact.placing = null; doc.controller.sheet = ViewerSheet.Redact }) { Text(stringResource(R.string.common_done), color = c.accent) }
        }
    }
}
