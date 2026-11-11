package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
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
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewModelScope
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.userMessage
import io.github.artcraftandroid.pdfcraft.plural
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** One editable object of a page as object_list gives it: its reference (kind, index), box (top-left points) and text. */
data class PageObject(val kind: String, val index: Int, val rect: FloatArray, val text: String?) {
    val ref: Map<String, Any> get() = mapOf("kind" to kind, "index" to index)
    val key: String get() = "$kind:$index"
}

fun parsePageObjects(o: JSONObject): List<PageObject> {
    val a = o.optJSONArray("objects") ?: JSONArray()
    return (0 until a.length()).mapNotNull { i ->
        val p = a.optJSONObject(i) ?: return@mapNotNull null
        val r = p.optJSONArray("rect")?.takeIf { it.length() == 4 } ?: return@mapNotNull null
        PageObject(p.optString("kind"), p.optInt("index"), FloatArray(4) { r.getDouble(it).toFloat() }, if (p.isNull("text")) null else p.optString("text"))
    }
}

/** The object a tap at ([x], [y]) means: of those under it (with [slop] points' reach), the smallest. */
fun objectAt(objects: List<PageObject>, x: Float, y: Float, slop: Float): PageObject? =
    objects.filter { x in (it.rect[0] - slop)..(it.rect[2] + slop) && y in (it.rect[1] - slop)..(it.rect[3] + slop) }
        .minByOrNull { (it.rect[2] - it.rect[0]) * (it.rect[3] - it.rect[1]) }

/** The objects a marquee from ([x0], [y0]) to ([x1], [y1]) touches. */
fun objectsIn(objects: List<PageObject>, x0: Float, y0: Float, x1: Float, y1: Float): List<PageObject> {
    val l = minOf(x0, x1); val r = maxOf(x0, x1); val t = minOf(y0, y1); val b = maxOf(y0, y1)
    return objects.filter { it.rect[0] <= r && it.rect[2] >= l && it.rect[1] <= b && it.rect[3] >= t }
}

/** Several objects on one page selected together (#162), and whether something has been copied. */
class ObjectSelectState {
    var doc by mutableStateOf<OpenDocument?>(null)
    var page by mutableStateOf(0)
    var generation = 0L
    var objects by mutableStateOf<List<PageObject>>(emptyList())
    var selected by mutableStateOf<Set<String>>(emptySet())
    /** How many objects the engine's clipboard holds (it keeps them after their document closes). */
    var copied by mutableStateOf(0)

    val chosen: List<PageObject> get() = objects.filter { it.key in selected }
}

fun AppViewModel.startObjectSelect(doc: OpenDocument) {
    editor.editing?.let { editor.commitInlineEdit(doc) }
    editor.editSelection = null
    editor.editImage = null
    editor.objectSelect.doc = doc
    editor.objectSelect.selected = emptySet()
    loadObjects(doc, doc.viewer.currentPage)
}

fun AppViewModel.endObjectSelect() {
    editor.objectSelect.doc = null
    editor.objectSelect.objects = emptyList()
    editor.objectSelect.selected = emptySet()
}

/** List page [page]'s objects again; a different page starts a new selection. */
fun AppViewModel.loadObjects(doc: OpenDocument, page: Int, keep: Set<String> = emptySet()) {
    val eng = engine ?: return
    viewModelScope.launch {
        try {
            val r = eng.tool("object_list", mapOf("doc" to doc.id, "page" to page + 1))
            editor.objectSelect.page = page
            editor.objectSelect.generation = r.optLong("generation")
            editor.objectSelect.objects = parsePageObjects(r)
            editor.objectSelect.selected = keep.filterTo(HashSet()) { k -> editor.objectSelect.objects.any { it.key == k } }
        } catch (e: Exception) {
            tell(e.userMessage)
        }
    }
}

private fun AppViewModel.selectionArgs(): Map<String, Any?> = mapOf(
    "page" to editor.objectSelect.page + 1,
    "objects" to editor.objectSelect.chosen.map { it.ref },
    "generation" to editor.objectSelect.generation,
)

/** Move the selection by ([dx], [dy]) points as one undoable step; a stale selection is refused whole. */
fun AppViewModel.moveObjects(doc: OpenDocument, dx: Float, dy: Float) {
    if (editor.objectSelect.chosen.isEmpty()) return
    val keep = editor.objectSelect.selected
    val page = editor.objectSelect.page
    runTool(doc, "object_move", selectionArgs() + ("offset" to listOf(dx.toDouble(), dy.toDouble())), onSettled = { loadObjects(doc, page, keep) })
}

fun AppViewModel.copyObjects(doc: OpenDocument, cut: Boolean) {
    val n = editor.objectSelect.chosen.size
    if (n == 0) return
    val page = editor.objectSelect.page
    runTool(doc, "object_copy", selectionArgs() + ("cut" to cut), onSettled = { loadObjects(doc, page) }) { r ->
        editor.objectSelect.copied = r.optInt("copied", n)
        tell(plural(if (cut) R.plurals.objects_cut else R.plurals.objects_copied, editor.objectSelect.copied, editor.objectSelect.copied))
    }
}

/** Paste what was copied onto [page] of [doc] (any open document); the pasted objects come out selected. */
fun AppViewModel.pasteObjects(doc: OpenDocument, page: Int) {
    if (editor.objectSelect.copied == 0) return
    val before = editor.objectSelect.objects.takeIf { editor.objectSelect.doc === doc && editor.objectSelect.page == page }?.map { it.key }?.toSet()
    runTool(doc, "object_paste", mapOf("page" to page + 1)) { r ->
        tell(plural(R.plurals.objects_pasted, r.optInt("pasted", editor.objectSelect.copied), r.optInt("pasted", editor.objectSelect.copied)))
        if (editor.objectSelect.doc === doc) {
            val eng = engine ?: return@runTool
            viewModelScope.launch {
                val listed = runCatching { eng.tool("object_list", mapOf("doc" to doc.id, "page" to page + 1)) }.getOrNull() ?: return@launch
                val objects = parsePageObjects(listed)
                editor.objectSelect.page = page
                editor.objectSelect.generation = listed.optLong("generation")
                editor.objectSelect.objects = objects
                editor.objectSelect.selected = if (before == null) emptySet() else objects.map { it.key }.filterTo(HashSet()) { it !in before }
            }
        }
    }
}

/**
 * Select objects: a tap adds or removes the object under it, a drag from empty space draws a
 * marquee that adds what it touches, and a drag from a selected object moves the selection (one
 * undo step on release). Ctrl+A, C, X and V work from a hardware keyboard.
 */
@Composable
fun ObjectSelectLayer(vm: AppViewModel, doc: OpenDocument) {
    val st = vm.editor.objectSelect
    val c = pdfColors
    var start by remember { mutableStateOf<Offset?>(null) }
    var end by remember { mutableStateOf<Offset?>(null) }
    var moving by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    LaunchedEffect(doc.info.generation) { vm.loadObjects(doc, st.page, st.selected) }
    Box(
        Modifier.fillMaxSize().focusRequester(focus).focusable()
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !(e.isCtrlPressed || e.isMetaPressed)) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.A -> { st.selected = st.objects.map { it.key }.toSet(); true }
                    Key.C -> { vm.copyObjects(doc, cut = false); true }
                    Key.X -> { vm.copyObjects(doc, cut = true); true }
                    Key.V -> { vm.pasteObjects(doc, doc.viewer.currentPage); true }
                    else -> false
                }
            }
            .toolGestures(
                doc,
                onDown = { p, _, _ ->
                    start = p
                    end = p
                    val at = doc.viewer.screenToPageOf(st.page, p)
                    val slop = with(density) { 8.dp.toPx() } / doc.viewer.pixelsPerPoint(st.page).coerceAtLeast(0.01f)
                    moving = at != null && st.chosen.any { o -> at.x in (o.rect[0] - slop)..(o.rect[2] + slop) && at.y in (o.rect[1] - slop)..(o.rect[3] + slop) }
                },
                onMove = { p, _, _ -> end = p },
                onUp = { p, moved ->
                    val s = start
                    start = null
                    end = null
                    val wasMoving = moving
                    moving = false
                    if (s == null) return@toolGestures
                    val ppp = doc.viewer.pixelsPerPoint(st.page).coerceAtLeast(0.01f)
                    if (moved && wasMoving) {
                        vm.moveObjects(doc, (p.x - s.x) / ppp, (p.y - s.y) / ppp)
                        return@toolGestures
                    }
                    val at = doc.viewer.screenToPage(s) ?: return@toolGestures
                    if (at.page != st.page) {
                        vm.loadObjects(doc, at.page)
                        return@toolGestures
                    }
                    if (moved) {
                        val b = doc.viewer.screenToPageOf(st.page, p) ?: return@toolGestures
                        st.selected = st.selected + objectsIn(st.objects, at.x, at.y, b.x, b.y).map { it.key }
                    } else {
                        val hit = objectAt(st.objects, at.x, at.y, with(density) { 4.dp.toPx() } / ppp)
                        st.selected = when {
                            hit == null -> emptySet()
                            hit.key in st.selected -> st.selected - hit.key
                            else -> st.selected + hit.key
                        }
                    }
                },
                onCancel = { start = null; end = null; moving = false },
            ),
    ) {
        val s = start
        val e = end
        Canvas(Modifier.fillMaxSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            val shift = if (moving && s != null && e != null) Offset(e.x - s.x, e.y - s.y) else Offset.Zero
            for (o in st.objects) {
                val on = o.key in st.selected
                val a = doc.viewer.pageToScreen(st.page, o.rect[0], o.rect[1]) + (if (on) shift else Offset.Zero)
                val b = doc.viewer.pageToScreen(st.page, o.rect[2], o.rect[3]) + (if (on) shift else Offset.Zero)
                if (on) drawRect(c.accent.copy(alpha = 0.15f), a, Size(b.x - a.x, b.y - a.y))
                drawRect(
                    if (on) c.accent else c.accent.copy(alpha = 0.5f), a, Size(b.x - a.x, b.y - a.y),
                    style = Stroke((if (on) 2 else 1).dp.toPx(), pathEffect = if (on) null else dash),
                )
            }
            if (!moving && s != null && e != null) {
                val tl = Offset(minOf(s.x, e.x), minOf(s.y, e.y))
                val sz = Size(kotlin.math.abs(e.x - s.x), kotlin.math.abs(e.y - s.y))
                drawRect(c.accent.copy(alpha = 0.1f), tl, sz)
                drawRect(c.accent, tl, sz, style = Stroke(1.dp.toPx(), pathEffect = dash))
            }
        }
    }
}

/** The bottom bar while selecting objects: the count, then Select all, Copy, Cut, Paste and Done. */
@Composable
fun ObjectSelectBar(vm: AppViewModel, doc: OpenDocument) {
    val st = vm.editor.objectSelect
    val c = pdfColors
    val n = st.selected.size
    Column(Modifier.fillMaxWidth().background(c.surface)) {
        Text(
            if (n == 0) stringResource(R.string.objects_hint) else pluralStringResource(R.plurals.objects_selected, n, n),
            color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).heightIn(min = 64.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BarAction(LucideIcons.SquareDashedMousePointer, stringResource(R.string.objects_select_all), st.objects.isNotEmpty()) { st.selected = st.objects.map { it.key }.toSet() }
            BarAction(LucideIcons.Copy, stringResource(R.string.objects_copy), n > 0) { vm.copyObjects(doc, cut = false) }
            BarAction(LucideIcons.Scissors, stringResource(R.string.objects_cut_label), n > 0) { vm.copyObjects(doc, cut = true) }
            BarAction(LucideIcons.ClipboardPaste, stringResource(R.string.objects_paste), st.copied > 0) { vm.pasteObjects(doc, st.page) }
            BarAction(LucideIcons.Check, stringResource(R.string.common_done), true) { vm.endObjectSelect() }
        }
    }
}

@Composable
private fun BarAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val c = pdfColors
    val tint = if (enabled) c.icon else c.textSecondary.copy(alpha = 0.5f)
    Column(
        Modifier.width(76.dp).heightIn(min = 56.dp).clickable(enabled = enabled, role = Role.Button, onClick = onClick).semantics { contentDescription = label },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, color = if (enabled) c.label else tint, fontSize = 12.sp, maxLines = 1)
    }
}
