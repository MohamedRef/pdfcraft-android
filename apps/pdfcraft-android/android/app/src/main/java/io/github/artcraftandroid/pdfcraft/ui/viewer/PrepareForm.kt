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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.text as appText
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfDialog
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.PdfSwitch
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** The kinds of field Prepare form places, as form_add_field names them, with their size when tapped in (points). */
enum class FieldKind(val arg: String, val label: Int, val w: Float, val h: Float) {
    Text("text", R.string.form_kind_text, 160f, 22f),
    Date("date", R.string.form_kind_date, 100f, 22f),
    Checkbox("checkbox", R.string.form_kind_checkbox, 14f, 14f),
    Radio("radio", R.string.form_kind_radio, 14f, 14f),
    Combo("combo", R.string.form_kind_combo, 140f, 22f),
    List("list", R.string.form_kind_list, 140f, 60f),
    Button("button", R.string.form_kind_button, 80f, 24f),
    Image("image", R.string.form_kind_image, 100f, 100f),
    Signature("signature", R.string.form_kind_signature, 160f, 40f),
}

/** The Format choices the Properties sheet offers, as form_set_props takes them. */
enum class FieldFormat(val arg: Any, val label: Int) {
    None("none", R.string.form_format_none),
    Number(mapOf("type" to "number", "decimals" to 2), R.string.form_format_number),
    Percent(mapOf("type" to "percent"), R.string.form_format_percent),
    Date(mapOf("type" to "date", "pattern" to "mm/dd/yyyy"), R.string.form_format_date),
    Phone(mapOf("type" to "phone"), R.string.form_format_phone),
    Zip(mapOf("type" to "zip"), R.string.form_format_zip),
    Ssn(mapOf("type" to "ssn"), R.string.form_format_ssn),
    ;

    companion object {
        /** The choice a field's format (form_fields' "format".type) stands for; anything else counts as none. */
        fun of(type: String?): FieldFormat = when (type) {
            "number" -> Number
            "percent" -> Percent
            "date" -> Date
            "phone" -> Phone
            "zip" -> Zip
            "ssn" -> Ssn
            else -> None
        }
    }
}

/** A field automatic detection proposes: page (0-based), kind, name, box (top-left points) and comb cells. */
data class DetectedField(val page: Int, val kind: String, val name: String, val rect: FloatArray, val comb: Int?)

/** One field's properties as the Properties sheet edits them. */
data class FieldProps(
    val name: String,
    val type: String,
    val tooltip: String = "",
    val required: Boolean = false,
    val readOnly: Boolean = false,
    val multiline: Boolean = false,
    val options: String = "",
    val format: FieldFormat = FieldFormat.None,
)

/** A box from a bottom-left-origin rect on a page [pageHeight] tall to top-left points. */
fun flipRect(r: FloatArray, pageHeight: Float): FloatArray = floatArrayOf(r[0], pageHeight - r[3], r[2], pageHeight - r[1])

/** A box of [w] by [h] points centred on ([x], [y]), kept on a [pw] by [ph] page. */
fun boxAt(x: Float, y: Float, w: Float, h: Float, pw: Float, ph: Float): FloatArray {
    val x0 = (x - w / 2f).coerceIn(0f, (pw - w).coerceAtLeast(0f))
    val y0 = (y - h / 2f).coerceIn(0f, (ph - h).coerceAtLeast(0f))
    return floatArrayOf(x0, y0, x0 + w, y0 + h)
}

/** Options typed one per line or separated by commas, trimmed, blanks dropped. */
fun parseOptions(text: String): List<String> = text.split('\n', ',').map { it.trim() }.filter { it.isNotEmpty() }

/** form_set_props' arguments for what changed between [old] and [new]; empty when nothing did. */
fun propsArgs(old: FieldProps, new: FieldProps): Map<String, Any?> = buildMap {
    if (new.name.trim() != old.name && new.name.isNotBlank()) put("name", new.name.trim())
    if (new.tooltip != old.tooltip) put("tooltip", new.tooltip)
    if (new.required != old.required) put("required", new.required)
    if (new.readOnly != old.readOnly) put("read_only", new.readOnly)
    if (new.type == "text" && new.multiline != old.multiline) put("multiline", new.multiline)
    if ((new.type == "combo" || new.type == "list") && parseOptions(new.options) != parseOptions(old.options)) put("options", parseOptions(new.options))
    if (new.type == "text" && new.format != old.format) put("format", new.format.arg)
}

/** Fields in [tabOrder] first, in that order; the rest after, as they were. */
fun <T> inTabOrder(items: List<T>, tabOrder: List<String>, name: (T) -> String): List<T> {
    if (tabOrder.isEmpty()) return items
    val rank = tabOrder.withIndex().associate { it.value to it.index }
    return items.withIndex().sortedWith(compareBy({ rank[name(it.value)] ?: Int.MAX_VALUE }, { it.index })).map { it.value }
}

/** Which of a selected box's edges a touch at ([x], [y]) holds (left, top, right, bottom): all four inside it, none away from it. */
fun grabEdges(r: FloatArray, x: Float, y: Float, slop: Float): BooleanArray? {
    if (x < r[0] - slop || x > r[2] + slop || y < r[1] - slop || y > r[3] + slop) return null
    val e = booleanArrayOf(kotlin.math.abs(x - r[0]) <= slop, kotlin.math.abs(y - r[1]) <= slop, kotlin.math.abs(x - r[2]) <= slop, kotlin.math.abs(y - r[3]) <= slop)
    if (e[0] && e[2]) if (x - r[0] < r[2] - x) e[2] = false else e[0] = false
    if (e[1] && e[3]) if (y - r[1] < r[3] - y) e[3] = false else e[1] = false
    return if (e.none { it }) booleanArrayOf(true, true, true, true) else e
}

/** Prepare form (#164): the document being prepared, the kind to place, the selected field and the open dialogs. */
class PrepareFormState {
    var doc by mutableStateOf<OpenDocument?>(null)
    var kind by mutableStateOf<FieldKind?>(null)
    var selected by mutableStateOf<String?>(null)
    var props by mutableStateOf<FieldProps?>(null)
    var detected by mutableStateOf<List<DetectedField>?>(null)
    var tabOrder by mutableStateOf<List<String>?>(null)
}

fun AppViewModel.openPrepareForm(doc: OpenDocument) {
    if (!doc.info.readOnlyReason.isNullOrEmpty()) {
        tell(doc.info.readOnlyReason ?: "")
        return
    }
    doc.controller.sheet = ViewerSheet.None
    prepareForm.kind = null
    prepareForm.selected = null
    prepareForm.doc = doc
    viewModelScope.launch { loadAllWidgets(doc) }
}

fun AppViewModel.closePrepareForm() {
    prepareForm.doc = null
    prepareForm.kind = null
    prepareForm.selected = null
    prepareForm.props = null
    prepareForm.detected = null
    prepareForm.tabOrder = null
}

fun AppViewModel.addField(doc: OpenDocument, page: Int, kind: FieldKind, rect: FloatArray, extra: Map<String, Any?> = emptyMap()) {
    val args = buildMap<String, Any?> {
        put("page", page + 1)
        put("type", kind.arg)
        put("rect", rect.toList())
        if (kind == FieldKind.Combo || kind == FieldKind.List) put("options", listOf(appText(R.string.form_option_default, 1), appText(R.string.form_option_default, 2)))
        putAll(extra)
    }
    runTool(doc, "form_add_field", args) { r ->
        prepareForm.kind = null
        prepareForm.selected = r.optString("field").takeIf { it.isNotEmpty() }
    }
}

fun AppViewModel.moveField(doc: OpenDocument, field: String, rect: FloatArray) {
    runTool(doc, "form_set_props", mapOf("field" to field, "rect" to rect.toList()))
}

fun AppViewModel.deleteField(doc: OpenDocument, field: String) {
    prepareForm.selected = null
    runTool(doc, "form_delete_field", mapOf("field" to field)) { tell(appText(R.string.form_field_deleted, field)) }
}

/** A copy of [field] just below it: the same kind, size and choices, with the next free name. */
fun AppViewModel.duplicateField(doc: OpenDocument, field: String) {
    val w = doc.widgets.firstOrNull { it.field == field } ?: return
    val kind = FieldKind.entries.firstOrNull { it.arg == w.kind } ?: return
    val page = doc.info.pages.getOrNull(w.page.toInt()) ?: return
    val h = w.rect[3] - w.rect[1]
    val dy = if (w.rect[3] + 8f + h <= page.height) h + 8f else -(h + 8f)
    val rect = floatArrayOf(w.rect[0], w.rect[1] + dy, w.rect[2], w.rect[3] + dy)
    val extra = buildMap<String, Any?> {
        if (w.options.isNotEmpty()) put("options", w.options.chunked(2).map { it.last() })
        if (w.multiline) put("multiline", true)
    }
    addField(doc, w.page.toInt(), kind, rect, extra)
}

/** Read [field]'s properties from form_fields and open the Properties sheet. */
fun AppViewModel.editFieldProps(doc: OpenDocument, field: String) {
    val eng = engine ?: return
    viewModelScope.launch {
        try {
            val r = eng.tool("form_fields", mapOf("doc" to doc.id))
            val fs = r.optJSONArray("fields") ?: JSONArray()
            val f = (0 until fs.length()).map { fs.getJSONObject(it) }.firstOrNull { it.optString("name") == field } ?: return@launch
            val opts = f.optJSONArray("options")
            val options = if (opts == null) emptyList() else (0 until opts.length()).map { i ->
                when (val o = opts.get(i)) {
                    is JSONObject -> o.optString("label", o.optString("value"))
                    else -> o.toString()
                }
            }
            prepareForm.props = FieldProps(
                name = field, type = f.optString("type"),
                tooltip = f.optString("tooltip").takeUnless { f.isNull("tooltip") } ?: "",
                required = f.optBoolean("required"), readOnly = f.optBoolean("read_only"), multiline = f.optBoolean("multiline"),
                options = if (f.optString("type") == "combo" || f.optString("type") == "list") options.joinToString(", ") else "",
                format = FieldFormat.of(f.optJSONObject("format")?.optString("type")),
            )
        } catch (e: Exception) {
            tell(e.userMessage)
        }
    }
}

fun AppViewModel.saveFieldProps(doc: OpenDocument, old: FieldProps, new: FieldProps) {
    prepareForm.props = null
    val args = propsArgs(old, new)
    if (args.isEmpty()) return
    runTool(doc, "form_set_props", args + ("field" to old.name)) { r ->
        if (prepareForm.selected == old.name) prepareForm.selected = r.optString("field").ifEmpty { new.name.trim() }
    }
}

/** Find the blanks a printed form asks to be filled, without adding them yet. */
fun AppViewModel.detectFields(doc: OpenDocument) {
    val eng = engine ?: return
    viewModelScope.launch {
        try {
            val r = eng.tool("form_detect_fields", mapOf("doc" to doc.id, "add" to false))
            val fs = r.optJSONArray("fields") ?: JSONArray()
            val list = (0 until fs.length()).mapNotNull { i ->
                val f = fs.getJSONObject(i)
                val page = f.optInt("page", 0) - 1
                val ph = doc.info.pages.getOrNull(page)?.height ?: return@mapNotNull null
                val a = f.optJSONArray("rect") ?: return@mapNotNull null
                DetectedField(page, f.optString("kind"), f.optString("name"), flipRect(FloatArray(4) { a.getDouble(it).toFloat() }, ph), if (f.has("comb")) f.optInt("comb") else null)
            }
            if (list.isEmpty()) tell(appText(R.string.form_detect_none)) else prepareForm.detected = list
        } catch (e: Exception) {
            tell(e.userMessage)
        }
    }
}

/** Add the [accepted] proposals: all of them in one undoable step, or each one when only some are kept. */
fun AppViewModel.addDetected(doc: OpenDocument, all: List<DetectedField>, accepted: Set<Int>) {
    prepareForm.detected = null
    if (accepted.isEmpty()) return
    if (accepted.size == all.size) {
        runTool(doc, "form_detect_fields", mapOf("add" to true)) { tell(plural(R.plurals.form_fields_added, all.size, all.size)) }
        return
    }
    val eng = engine ?: return
    viewModelScope.launch {
        var added = 0
        try {
            for (i in accepted.sorted()) {
                val d = all[i]
                val r = eng.tool(
                    "form_add_field",
                    mapOf("doc" to doc.id, "page" to d.page + 1, "type" to if (d.kind == "checkbox") "checkbox" else "text", "rect" to d.rect.toList(), "name" to d.name.ifBlank { null }).filterValues { it != null },
                )
                d.comb?.let { cells -> eng.tool("form_set_props", mapOf("doc" to doc.id, "field" to r.optString("field"), "max_length" to cells, "flags" to mapOf("comb" to true))) }
                added++
            }
        } catch (e: Exception) {
            tell(e.userMessage)
        } finally {
            refresh(doc, null)
            if (added > 0) tell(plural(R.plurals.form_fields_added, added, added))
        }
    }
}

/** Read the tab order (or set it: row, column or structure; or move [field] earlier or later). */
fun AppViewModel.tabOrder(doc: OpenDocument, order: String? = null, field: String? = null, later: Boolean = false) {
    val eng = engine ?: return
    val args = buildMap<String, Any?> {
        put("doc", doc.id)
        order?.let { put("order", it) }
        field?.let { put("field", it); put("move", if (later) "later" else "earlier") }
    }
    viewModelScope.launch {
        try {
            val r = eng.tool("form_tab_order", args)
            if (order != null || field != null) refresh(doc, null)
            val t = r.optJSONArray("tab_order") ?: JSONArray()
            prepareForm.tabOrder = (0 until t.length()).map { t.getString(it) }
        } catch (e: Exception) {
            tell(e.userMessage)
        }
    }
}

/**
 * Prepare form, over the pages: every field outlined; with a kind chosen, a tap places that field
 * (or a drag draws its box); otherwise a tap selects a field, and dragging the selected field
 * moves it or, from an edge or corner, resizes it. The palette is along the bottom; a selected
 * field gets Properties, Duplicate and Delete there instead.
 */
@Composable
fun PrepareFormLayer(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    val st = vm.prepareForm
    val density = LocalDensity.current
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    var drag by remember { mutableStateOf<Pair<FloatArray, BooleanArray>?>(null) }
    var dragRect by remember { mutableStateOf<FloatArray?>(null) }
    var menu by remember { mutableStateOf(false) }
    val selected = st.selected?.let { name -> doc.widgets.firstOrNull { it.field == name } }
    Box(
        Modifier.fillMaxSize().toolGestures(
            doc,
            onDown = { p, _, _ ->
                start = p
                end = p
                drag = null
                dragRect = null
                val sel = selected ?: return@toolGestures
                if (st.kind != null || sel.rect.size < 4) return@toolGestures
                val at = doc.viewer.screenToPageOf(sel.page.toInt(), p) ?: return@toolGestures
                val slop = with(density) { 24.dp.toPx() } / doc.viewer.pixelsPerPoint(sel.page.toInt()).coerceAtLeast(0.01f)
                val r = sel.rect.toFloatArray()
                grabEdges(r, at.x, at.y, slop)?.let { drag = r to it; dragRect = r }
            },
            onMove = { p, _, _ ->
                end = p
                val (r, edges) = drag ?: return@toolGestures
                val sel = selected ?: return@toolGestures
                val s = start ?: return@toolGestures
                val page = doc.info.pages.getOrNull(sel.page.toInt()) ?: return@toolGestures
                val ppp = doc.viewer.pixelsPerPoint(sel.page.toInt()).coerceAtLeast(0.01f)
                val dx = (p.x - s.x) / ppp
                val dy = (p.y - s.y) / ppp
                dragRect = if (edges.all { it }) {
                    val mx = dx.coerceIn(-r[0], page.width - r[2])
                    val my = dy.coerceIn(-r[1], page.height - r[3])
                    floatArrayOf(r[0] + mx, r[1] + my, r[2] + mx, r[3] + my)
                } else {
                    dragCrop(r, edges, dx, dy, floatArrayOf(0f, 0f, page.width, page.height), null, 6f)
                }
            },
            onUp = { p, moved ->
                val s = start
                start = null
                end = null
                val d = drag
                val dr = dragRect
                drag = null
                dragRect = null
                if (s == null) return@toolGestures
                val sel = selected
                if (d != null && dr != null && moved && sel != null) {
                    if (!dr.contentEquals(d.first)) vm.moveField(doc, sel.field, dr)
                    return@toolGestures
                }
                val at = doc.viewer.screenToPage(s) ?: return@toolGestures
                val page = doc.info.pages.getOrNull(at.page) ?: return@toolGestures
                val kind = st.kind
                if (kind != null) {
                    val rect = if (moved) {
                        val ppp = doc.viewer.pixelsPerPoint(at.page).coerceAtLeast(0.01f)
                        val x1 = (at.x + (p.x - s.x) / ppp).coerceIn(0f, page.width)
                        val y1 = (at.y + (p.y - s.y) / ppp).coerceIn(0f, page.height)
                        floatArrayOf(minOf(at.x, x1), minOf(at.y, y1), maxOf(at.x, x1), maxOf(at.y, y1)).takeIf { it[2] - it[0] >= 6f && it[3] - it[1] >= 6f }
                    } else {
                        boxAt(at.x, at.y, kind.w, kind.h, page.width, page.height)
                    } ?: return@toolGestures
                    vm.addField(doc, at.page, kind, rect)
                } else if (!moved) {
                    val hit = doc.widgets.lastOrNull { w ->
                        w.page.toInt() == at.page && w.rect.size >= 4 && at.x in w.rect[0]..w.rect[2] && at.y in w.rect[1]..w.rect[3]
                    }
                    st.selected = hit?.field
                }
            },
            onCancel = { start = null; end = null; drag = null; dragRect = null },
        ),
    ) {
        val s = start
        val e = end
        Canvas(Modifier.fillMaxSize()) {
            for (w in doc.widgets) {
                if (w.rect.size < 4) continue
                val on = w.field == st.selected
                val r = if (on && dragRect != null) dragRect!!.toList() else w.rect
                val a = doc.viewer.pageToScreen(w.page.toInt(), r[0], r[1])
                val b = doc.viewer.pageToScreen(w.page.toInt(), r[2], r[3])
                if (b.y < 0f || a.y > size.height) continue
                val sz = Size(b.x - a.x, b.y - a.y)
                drawRect(if (on) Color(0x333D6BEB) else Color(0x1F3D6BEB), a, sz)
                drawRect(if (on) c.accent else Color(0xB33D6BEB), a, sz, style = Stroke((if (on) 2 else 1).dp.toPx()))
                if (on) {
                    val hr = 6.dp.toPx()
                    for (pt in listOf(a, Offset(b.x, a.y), b, Offset(a.x, b.y), Offset((a.x + b.x) / 2, a.y), Offset((a.x + b.x) / 2, b.y), Offset(a.x, (a.y + b.y) / 2), Offset(b.x, (a.y + b.y) / 2))) {
                        drawCircle(Color.White, hr, pt)
                        drawCircle(c.accent, hr, pt, style = Stroke(2.dp.toPx()))
                    }
                }
            }
            if (st.kind != null && s != null && e != null && drag == null) {
                val tl = Offset(minOf(s.x, e.x), minOf(s.y, e.y))
                val size = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y))
                drawRect(Color(0x333D6BEB), tl, size)
                drawRect(c.accent, tl, size, style = Stroke(2.dp.toPx()))
            }
        }
        val top = with(density) { doc.viewer.padTop.toDp() }
        Row(
            Modifier.align(Alignment.TopCenter).padding(top = top + 12.dp, start = 16.dp, end = 16.dp)
                .clip(RoundedCornerShape(8.dp)).background(c.surface).padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                st.kind?.let { stringResource(R.string.form_place_hint, stringResource(it.label)) } ?: stringResource(R.string.form_prepare_hint),
                color = c.text, fontSize = 14.sp, modifier = Modifier.weight(1f, fill = false),
            )
            Box {
                BarIcon(LucideIcons.Ellipsis, stringResource(R.string.form_more), { menu = true })
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.form_detect)) }, onClick = { menu = false; vm.detectFields(doc) })
                    androidx.compose.material3.DropdownMenuItem(text = { Text(stringResource(R.string.form_tab_order)) }, onClick = { menu = false; vm.tabOrder(doc) })
                }
            }
            TextButton(onClick = { vm.closePrepareForm() }) { Text(stringResource(R.string.common_done), color = c.accent) }
        }
        val bottom = with(density) { doc.viewer.padBottom.toDp() }
        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = bottom + 12.dp, start = 12.dp, end = 12.dp).clip(RoundedCornerShape(12.dp)).background(c.surface)) {
            if (selected != null) {
                Row(Modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(selected.field, color = c.text, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp).weight(1f, fill = false))
                    BarIcon(LucideIcons.Settings2, stringResource(R.string.form_properties), { vm.editFieldProps(doc, selected.field) })
                    BarIcon(LucideIcons.Copy, stringResource(R.string.form_duplicate), { vm.duplicateField(doc, selected.field) })
                    BarIcon(LucideIcons.Trash2, stringResource(R.string.form_delete), { vm.deleteField(doc, selected.field) })
                    BarIcon(LucideIcons.X, stringResource(R.string.form_deselect), { st.selected = null })
                }
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 4.dp).selectableGroup()) {
                    for (k in FieldKind.entries) {
                        val on = st.kind == k
                        Text(
                            stringResource(k.label), color = if (on) c.accent else c.text, fontSize = 14.sp,
                            fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                            modifier = Modifier.heightIn(min = 44.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (on) c.accent.copy(alpha = 0.12f) else Color.Transparent)
                                .selectable(on, role = Role.RadioButton) { st.kind = if (on) null else k }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
    st.props?.let { p -> FieldPropsDialog(vm, doc, p) }
    st.detected?.let { d -> DetectDialog(vm, doc, d) }
    st.tabOrder?.let { t -> TabOrderDialog(vm, doc, t) }
}

/** Properties: name, tooltip, required, read-only, and per kind multiline, options and format. */
@Composable
private fun FieldPropsDialog(vm: AppViewModel, doc: OpenDocument, old: FieldProps) {
    val c = pdfColors
    var p by remember(old) { mutableStateOf(old) }
    PdfDialog(
        onDismiss = { vm.prepareForm.props = null },
        title = stringResource(R.string.form_properties),
        confirm = stringResource(R.string.common_save),
        confirmEnabled = p.name.isNotBlank() && !p.name.contains('.') || p.name == old.name,
        onConfirm = { vm.saveFieldProps(doc, old, p) },
    ) {
        Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()).imePadding()) {
            PdfField(p.name, { p = p.copy(name = it) }, stringResource(R.string.form_name), modifier = Modifier.padding(vertical = 4.dp))
            PdfField(p.tooltip, { p = p.copy(tooltip = it) }, stringResource(R.string.form_tooltip), modifier = Modifier.padding(vertical = 4.dp))
            PropToggle(stringResource(R.string.form_required), p.required) { p = p.copy(required = it) }
            PropToggle(stringResource(R.string.form_read_only), p.readOnly) { p = p.copy(readOnly = it) }
            if (p.type == "text") {
                PropToggle(stringResource(R.string.form_multiline), p.multiline) { p = p.copy(multiline = it) }
                Text(stringResource(R.string.form_format), color = c.textSecondary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                Column(Modifier.selectableGroup()) {
                    for (f in FieldFormat.entries) {
                        val on = p.format == f
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 44.dp).selectable(on, role = Role.RadioButton) { p = p.copy(format = f) },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(if (on) LucideIcons.CircleDot else LucideIcons.Circle, null, tint = if (on) c.accent else c.icon, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.width(10.dp))
                            Text(stringResource(f.label), color = c.text, fontSize = 15.sp)
                        }
                    }
                }
            }
            if (p.type == "combo" || p.type == "list") {
                PdfField(p.options, { p = p.copy(options = it) }, stringResource(R.string.form_options_hint), modifier = Modifier.padding(vertical = 4.dp))
            }
        }
    }
}

@Composable
private fun PropToggle(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = on, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, color = pdfColors.text, fontSize = 15.sp, modifier = Modifier.weight(1f))
        PdfSwitch(on, null)
    }
}

/** The proposed fields, each kept or left out, before any is added. */
@Composable
private fun DetectDialog(vm: AppViewModel, doc: OpenDocument, all: List<DetectedField>) {
    val c = pdfColors
    var keep by remember(all) { mutableStateOf(all.indices.toSet()) }
    PdfDialog(
        onDismiss = { vm.prepareForm.detected = null },
        title = stringResource(R.string.form_detect),
        confirm = stringResource(R.string.form_detect_add),
        confirmEnabled = keep.isNotEmpty(),
        onConfirm = { vm.addDetected(doc, all, keep) },
    ) {
        Text(stringResource(R.string.form_detect_body), color = c.textSecondary, fontSize = 13.sp)
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(top = 6.dp)) {
            for ((i, d) in all.withIndex()) {
                val on = i in keep
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, role = Role.Checkbox) { keep = if (it) keep + i else keep - i },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (on) LucideIcons.SquareCheckBig else LucideIcons.Square, null, tint = if (on) c.accent else c.icon, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(d.name, color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val kind = stringResource(if (d.kind == "checkbox") R.string.form_kind_checkbox else R.string.form_kind_text)
                        Text(stringResource(R.string.form_detected_where, kind, d.page + 1), color = c.textSecondary, fontSize = 13.sp)
                    }
                }
            }
        }
    }
}

/** Tab order: by rows, by columns or by the tags, or one field at a time earlier or later. */
@Composable
private fun TabOrderDialog(vm: AppViewModel, doc: OpenDocument, order: List<String>) {
    val c = pdfColors
    PdfDialog(
        onDismiss = { vm.prepareForm.tabOrder = null },
        title = stringResource(R.string.form_tab_order),
        confirm = stringResource(R.string.common_done),
        onConfirm = { vm.prepareForm.tabOrder = null },
        dismiss = null,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            for ((arg, label) in listOf("row" to R.string.form_tab_rows, "column" to R.string.form_tab_columns, "structure" to R.string.form_tab_structure)) {
                TextButton(onClick = { vm.tabOrder(doc, order = arg) }) { Text(stringResource(label), color = c.accent) }
            }
        }
        Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
            if (order.isEmpty()) Text(stringResource(R.string.form_no_fields), color = c.textSecondary, fontSize = 14.sp)
            for ((i, name) in order.withIndex()) {
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${i + 1}. $name", color = c.text, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    BarIcon(LucideIcons.ChevronUp, stringResource(R.string.form_tab_earlier, name), { vm.tabOrder(doc, field = name, later = false) }, enabled = i > 0)
                    BarIcon(LucideIcons.ChevronDown, stringResource(R.string.form_tab_later, name), { vm.tabOrder(doc, field = name, later = true) }, enabled = i < order.size - 1)
                }
            }
        }
    }
}
