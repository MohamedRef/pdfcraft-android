package io.github.artcraftandroid.pdfcraft.ui.viewer

import io.github.artcraftandroid.pdfcraft.ui.openUnlock
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarIcon
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlin.math.roundToInt

/**
 * The groups tools are filed under in Home ▸ Tools (#539): each tool has exactly one, and the
 * list shows them in this order.
 */
enum class ToolCategory(val key: String, @StringRes val label: Int) {
    Scan("Scan", R.string.home_category_scan),
    Annotate("Annotate", R.string.home_category_annotate),
    Edit("Edit", R.string.home_category_edit),
    Pages("Pages", R.string.home_category_pages),
    Convert("Convert", R.string.home_category_convert),
    Read("Read", R.string.home_category_read),
}

/**
 * Every tool of the app, in one list (#539): the viewer's bottom toolbar and More tools, Home ▸
 * Tools and "Start with a tool" all show tools from here. [label] on the bar, [title] in the lists,
 * [subTools] when it opens a row of sub-tools (the bar's corner mark), [category] its one group.
 */
enum class QuickTool(val id: String, @StringRes val label: Int, @StringRes val title: Int, val icon: ImageVector, val hue: Color, val category: ToolCategory, val subTools: Boolean = false) {
    Edit("edit", R.string.common_tool_edit_pdf, R.string.common_tool_edit_pdf, LucideIcons.FilePenLine, ToolHues.edit, ToolCategory.Edit),
    Comment("comment", R.string.common_tool_comment, R.string.common_tool_comment, LucideIcons.MessageSquareText, ToolHues.comment, ToolCategory.Annotate, subTools = true),
    Highlight("highlight", R.string.common_tool_highlight, R.string.common_tool_highlight, LucideIcons.Highlighter, ToolHues.text, ToolCategory.Annotate, subTools = true),
    Draw("draw", R.string.common_tool_draw, R.string.common_tool_draw, LucideIcons.PenLine, ToolHues.sign, ToolCategory.Annotate),
    FillSign("fillsign", R.string.common_tool_fill_sign, R.string.common_tool_fill_sign, LucideIcons.Signature, ToolHues.sign, ToolCategory.Annotate, subTools = true),
    Export("export", R.string.common_tool_export, R.string.common_tool_export, LucideIcons.FileOutput, ToolHues.export, ToolCategory.Convert),
    Combine("combine", R.string.toolbar_label_combine, R.string.common_tool_combine, LucideIcons.Combine, ToolHues.combine, ToolCategory.Pages),
    Compress("compress", R.string.toolbar_label_compress, R.string.common_tool_compress, LucideIcons.FileArchive, ToolHues.compress, ToolCategory.Convert),
    Protect("protect", R.string.toolbar_label_password, R.string.common_tool_set_password, LucideIcons.FileLock2, ToolHues.protect, ToolCategory.Edit),
    // a copy without its password and restrictions (#517), beside Set password
    Unlock("unlock", R.string.toolbar_label_unlock, R.string.unlock_title, LucideIcons.LockOpen, ToolHues.protect, ToolCategory.Edit),
    // a tool that changes the content: More tools, not ⋮ (#195)
    Redact("redact", R.string.redact_title, R.string.redact_title, LucideIcons.SquareDashed, ToolHues.protect, ToolCategory.Edit),
    // numbers across several saved PDFs (#539: was on Home only)
    Bates("bates", R.string.toolbar_label_bates, R.string.bates_title, LucideIcons.FilePenLine, ToolHues.edit, ToolCategory.Edit),
    // headers, footers, watermarks and backgrounds (#539: was in ⋮ and on Home only)
    PageMarks("marks", R.string.toolbar_label_marks, R.string.marks_title, LucideIcons.Heading, ToolHues.edit, ToolCategory.Edit),
    Accessibility("accessibility", R.string.a11y_title, R.string.a11y_title, LucideIcons.Accessibility, ToolHues.protect, ToolCategory.Read),
    PrepareForm("prepareform", R.string.form_prepare, R.string.form_prepare, LucideIcons.TextCursorInput, ToolHues.sign, ToolCategory.Edit),
    Organize("organize", R.string.toolbar_label_organize, R.string.common_tool_organize, LucideIcons.LayoutGrid, ToolHues.organize, ToolCategory.Pages),
    // #539: was on the Pages screen and Home only
    Split("split", R.string.toolbar_label_split, R.string.home_tool_split, LucideIcons.Scissors, ToolHues.organize, ToolCategory.Pages),
    Crop("crop", R.string.toolbar_label_crop, R.string.common_tool_crop, LucideIcons.Crop, ToolHues.crop, ToolCategory.Pages),
    // room beside the content for notes (#146): changes the pages, so More tools
    NoteMargin("margin", R.string.margin_title, R.string.margin_title, LucideIcons.PanelRight, ToolHues.crop, ToolCategory.Pages),
    Ocr("ocr", R.string.toolbar_label_recognize, R.string.common_tool_recognize_text, LucideIcons.ScanText, ToolHues.ocr, ToolCategory.Scan),
    // the scan tools sit with Recognize text, in one place (#195)
    ScanEnhance("enhance", R.string.toolbar_label_enhance, R.string.scan_enhance_title, LucideIcons.WandSparkles, ToolHues.ocr, ToolCategory.Scan),
    OcrReview("ocrreview", R.string.toolbar_label_review_ocr, R.string.ocr_review_title, LucideIcons.ListChecks, ToolHues.ocr, ToolCategory.Scan),
    // ink comments get their handwriting as text (#151), beside the other reading tools
    Handwriting("handwriting", R.string.toolbar_label_handwriting, R.string.handwriting_read_page, LucideIcons.ScanText, ToolHues.ocr, ToolCategory.Scan),
    // several files at once (#539: was on Home only)
    OcrBatch("ocrbatch", R.string.toolbar_label_ocr_files, R.string.ocr_batch_title, LucideIcons.ScanText, ToolHues.scan, ToolCategory.Scan),
    Create("create", R.string.common_tool_create_pdf, R.string.common_tool_create_pdf, LucideIcons.FilePlus2, ToolHues.create, ToolCategory.Convert),
    Text("text", R.string.toolbar_label_add_text, R.string.toolbar_tool_text, LucideIcons.Type, ToolHues.text, ToolCategory.Annotate),
    Scan("scan", R.string.common_tool_scan, R.string.common_tool_scan, LucideIcons.Camera, ToolHues.scan, ToolCategory.Scan),
    // the kept scans (#461, #539: was on Home only)
    ScanRecent("recentscans", R.string.toolbar_label_recent_scans, R.string.scan_recent_title, LucideIcons.FileStack, ToolHues.scan, ToolCategory.Scan),
    ReadAloud("readaloud", R.string.common_tool_read_aloud, R.string.common_tool_read_aloud, LucideIcons.Volume2, ToolHues.read, ToolCategory.Read),
    // #539: was on Home only
    Compare("compare", R.string.toolbar_label_compare, R.string.compare_title, LucideIcons.GitCompare, ToolHues.read, ToolCategory.Read),
    // distance, perimeter and area on plans and drawings (#135, #180)
    Measure("measure", R.string.measure_tool, R.string.measure_tool, LucideIcons.Ruler, ToolHues.crop, ToolCategory.Annotate);

    /** True when the tool works on the open file, so Home asks for one first (#539). */
    val needsFile: Boolean get() = this !in WITHOUT_FILE

    companion object {
        /** The bar as it comes: the reference's default tools and order. */
        val DEFAULT_BAR = listOf(Edit, Comment, Highlight, Draw, FillSign, Export)

        /** Tools that start without an open file: they make one, or choose their files on their own screen. */
        val WITHOUT_FILE = setOf(Create, Scan, ScanRecent, Combine, Unlock, Bates, OcrBatch)

        /** "Start with a tool" on Home, after Open file: Scan first, one tap from Home (#539). */
        val HOME_START = listOf(Scan, Edit, ReadAloud, FillSign, Create, Organize, Combine, Compress, Export, Protect)

        fun of(id: String): QuickTool? = entries.firstOrNull { it.id == id }

        /**
         * Home ▸ Tools: the tools of [category] (all of them when null) whose shown name, or
         * category's name, contains [query]; by category, then by name, Scan leading its own (#539).
         */
        fun listed(category: ToolCategory?, query: String, nameOf: (QuickTool) -> String, categoryName: (ToolCategory) -> String = { it.key }): List<QuickTool> {
            val q = query.trim().lowercase()
            return entries
                .filter { category == null || it.category == category }
                .filter { q.isEmpty() || nameOf(it).lowercase().contains(q) || categoryName(it.category).lowercase().contains(q) }
                .sortedWith(compareBy<QuickTool>({ it.category.ordinal }, { it != Scan }, { nameOf(it).lowercase() }))
        }
    }
}

/** Start [tool] from Home, where no file is open: one that works on a file asks for it first (#539). */
fun AppViewModel.startToolFromHome(tool: QuickTool) {
    when (tool) {
        QuickTool.Create -> tools.openCreate()
        QuickTool.Scan -> startScan()
        QuickTool.ScanRecent -> scanRecent.show()
        QuickTool.Combine -> pickFiles(io.github.artcraftandroid.pdfcraft.ui.FilePickKind.Combine)
        QuickTool.Unlock -> openUnlock(null)
        QuickTool.Bates -> openBates()
        QuickTool.OcrBatch -> openOcrBatch()
        else -> home.toolForFile(tool)
    }
}

/**
 * How many tools fit on the bar beside More tools at a screen [widthDp] wide. Below 352 dp six
 * tools and More would each be under 48 dp wide (43 dp at 320 dp), too narrow to tap surely, so the
 * sixth waits in More tools there (#538).
 */
fun toolbarSlots(widthDp: Int): Int = when {
    widthDp >= 768 -> 8
    widthDp >= 460 -> 7
    widthDp >= 352 -> 6
    else -> 5
}

/** The bar's tools and More tools' order, as the user arranged them (kept in the app's preferences). */
@Stable
class QuickToolbarPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("quick_toolbar", Context.MODE_PRIVATE)

    /** Optional Xodo-style vertical rail; legacy bottom arrangement stays the default. */
    var railMode by mutableStateOf(prefs.getBoolean("railMode", false))
        private set
    var railRight by mutableStateOf(prefs.getBoolean("railRight", false))
        private set

    /** Center-relative vertical position in dp; clamped again at each viewport size. */
    var railYOffsetDp by mutableStateOf(prefs.getFloat("railYOffsetDp", 0f).takeIf { it.isFinite() }?.coerceIn(-400f, 400f) ?: 0f)
        private set

    fun updateRailYOffsetDp(value: Float) {
        if (!value.isFinite()) return
        railYOffsetDp = value.coerceIn(-400f, 400f)
        prefs.edit().putFloat("railYOffsetDp", railYOffsetDp).apply()
    }

    fun updateRailMode(enabled: Boolean) {
        railMode = enabled
        prefs.edit().putBoolean("railMode", enabled).apply()
    }

    fun updateRailRight(enabled: Boolean) {
        railRight = enabled
        prefs.edit().putBoolean("railRight", enabled).apply()
    }

    var bar by mutableStateOf(read("bar") ?: QuickTool.DEFAULT_BAR)
        private set

    /** More tools' order: every tool not on the bar. */
    var grid by mutableStateOf(complete(read("grid").orEmpty(), bar))
        private set

    private fun read(key: String): List<QuickTool>? =
        prefs.getString(key, null)?.let { csv -> csv.split(',').filter { it.isNotBlank() }.mapNotNull(QuickTool::of).distinct() }

    fun save(newBar: List<QuickTool>, newGrid: List<QuickTool>) {
        bar = newBar.distinct()
        grid = complete(newGrid, bar)
        prefs.edit().putString("bar", bar.joinToString(",") { it.id }).putString("grid", grid.joinToString(",") { it.id }).apply()
    }

    companion object {
        /**
         * [order] without the bar's tools, plus any tool it is missing: placed after the tool
         * before it in [QuickTool]'s order when that one is there (a new tool lands beside its
         * kin, as Enhance beside Recognize text), else at the end.
         */
        fun complete(order: List<QuickTool>, bar: List<QuickTool>): List<QuickTool> {
            val g = order.filter { it !in bar }.distinct().toMutableList()
            for (t in QuickTool.entries) {
                if (t in bar || t in g) continue
                val before = QuickTool.entries.subList(0, t.ordinal).lastOrNull { it in g }
                if (before == null) g.add(t) else g.add(g.indexOf(before) + 1, t)
            }
            return g
        }
    }
}

/** Do what [tool] does on the bar or in More tools. */
fun AppViewModel.runQuickTool(doc: OpenDocument, tool: QuickTool) {
    when (tool) {
        QuickTool.Edit -> enterTool(doc, ViewerTool.Edit)
        QuickTool.Comment -> enterTool(doc, ViewerTool.Comment)
        QuickTool.Highlight -> enterTool(doc, ViewerTool.Markup)
        QuickTool.Draw -> enterTool(doc, ViewerTool.Draw)
        QuickTool.FillSign -> enterTool(doc, ViewerTool.FillSign)
        QuickTool.Text -> enterTool(doc, ViewerTool.Text)
        QuickTool.Export -> sharing.openExport(doc)
        QuickTool.Combine -> tools.openCombine(doc)
        QuickTool.Compress -> tools.openCompress(doc)
        QuickTool.Protect -> tools.openProtect(doc)
        QuickTool.Unlock -> openUnlock(doc)
        QuickTool.Organize -> {
            doc.controller.pagesOpen = true
            tools.organizing = true
        }
        QuickTool.Crop -> tools.openCrop(doc)
        QuickTool.NoteMargin -> {
            // Draft ink keeps page sizes; add it before the pages grow.
            doc.commitTool?.invoke()
            doc.controller.sheet = ViewerSheet.NoteMargin
        }
        QuickTool.Ocr -> recognizeText(doc)
        QuickTool.ScanEnhance -> doc.controller.sheet = ViewerSheet.ScanEnhance
        QuickTool.Redact -> openRedact(doc)
        QuickTool.Accessibility -> doc.controller.sheet = ViewerSheet.Accessibility
        QuickTool.PrepareForm -> openPrepareForm(doc)
        QuickTool.OcrReview -> doc.controller.sheet = ViewerSheet.OcrConfidence
        QuickTool.Handwriting -> noteTaking.recognizeHandwriting(doc, doc.viewer.currentPage)
        QuickTool.Create -> tools.openCreate()
        QuickTool.Scan -> startScan()
        QuickTool.ReadAloud -> sharing.startReadAloud(doc)
        QuickTool.Measure -> enterTool(doc, ViewerTool.Measure)
        QuickTool.Bates -> openBates()
        QuickTool.PageMarks -> docTools.openPageMarks(doc)
        QuickTool.Split -> {
            tools.organizing = true
            doc.controller.pagesOpen = true
            docTools.splitOpen = true
        }
        QuickTool.OcrBatch -> openOcrBatch()
        QuickTool.ScanRecent -> scanRecent.show()
        QuickTool.Compare -> startCompare(doc)
    }
}

/**
 * Customize toolbar: the bar's tools on top and the rest below, as More tools holds them. A tool
 * is dragged (or tapped) from one to the other; dropped on a full bar it pushes the bar's last
 * tool down. The tick keeps the new arrangement, the cross leaves the old one.
 */
@Composable
fun CustomizeToolbar(vm: AppViewModel, onDone: () -> Unit) {
    val c = pdfColors
    val prefs = vm.settings.quickToolbar
    val slots = toolbarSlots(LocalConfiguration.current.screenWidthDp)
    val bar = remember { prefs.bar.take(slots).toMutableStateList() }
    val grid = remember { mutableStateListOf<QuickTool>().apply { addAll(QuickToolbarPrefs.complete(prefs.grid + prefs.bar.drop(slots), bar)) } }
    val rects = remember { mutableStateMapOf<QuickTool, Rect>() }
    var barRect by remember { mutableStateOf(Rect.Zero) }
    var gridRect by remember { mutableStateOf(Rect.Zero) }
    var origin by remember { mutableStateOf(Offset.Zero) }
    var dragging by remember { mutableStateOf<QuickTool?>(null) }
    var dragPos by remember { mutableStateOf(Offset.Zero) }

    fun toBar(t: QuickTool, index: Int) {
        bar.remove(t)
        grid.remove(t)
        bar.add(index.coerceIn(0, bar.size), t)
        while (bar.size > slots) grid.add(0, bar.removeAt(bar.lastIndex))
    }

    fun toGrid(t: QuickTool, index: Int) {
        bar.remove(t)
        grid.remove(t)
        grid.add(index.coerceIn(0, grid.size), t)
    }

    fun drop(t: QuickTool, at: Offset) {
        when {
            barRect.contains(at) -> toBar(t, bar.count { it != t && (rects[it]?.center?.x ?: 0f) < at.x })
            gridRect.contains(at) -> {
                // before the nearest tile, or after it when dropped on its right half
                val near = grid.filter { it != t }.minByOrNull { (rects[it]?.center ?: Offset.Infinite).minus(at).getDistanceSquared() }
                val others = grid.filter { it != t }
                val i = near?.let { n -> others.indexOf(n) + if (at.x > (rects[n]?.center?.x ?: 0f)) 1 else 0 } ?: others.size
                toGrid(t, i)
            }
        }
    }

    val wobble by rememberInfiniteTransition(label = "jiggle").animateFloat(-1.5f, 1.5f, infiniteRepeatable(tween(140), RepeatMode.Reverse), label = "angle")

    @Composable
    fun Draggable(t: QuickTool, content: @Composable () -> Unit) {
        val title = stringResource(t.title)
        val description = if (t in bar) stringResource(R.string.toolbar_tool_on_bar, title) else stringResource(R.string.toolbar_tool_in_more_tools, title)
        Box(
            Modifier
                .onGloballyPositioned { rects[t] = it.boundsInRoot() }
                .alpha(if (dragging == t) 0.3f else 1f)
                .rotate(if (dragging == null) wobble else 0f)
                .pointerInput(t) {
                    detectDragGestures(
                        onDragStart = { p ->
                            dragging = t
                            dragPos = (rects[t]?.topLeft ?: Offset.Zero) + p
                        },
                        onDrag = { change, amount ->
                            change.consume()
                            dragPos += amount
                        },
                        onDragEnd = {
                            drop(t, dragPos)
                            dragging = null
                        },
                        onDragCancel = { dragging = null },
                    )
                }
                .clickable(role = Role.Button) { if (t in bar) toGrid(t, 0) else toBar(t, bar.size) }
                .semantics { contentDescription = description },
        ) { content() }
    }

    Box(Modifier.fillMaxWidth().onGloballyPositioned { origin = it.boundsInRoot().topLeft }) {
        Column {
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.toolbar_customize), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                BarIcon(LucideIcons.X, stringResource(R.string.common_cancel), onDone)
                BarIcon(LucideIcons.Check, stringResource(R.string.toolbar_save), {
                    prefs.save(bar.toList(), grid.toList())
                    onDone()
                }, tint = c.accent)
            }
            Text(
                pluralStringResource(R.plurals.toolbar_customize_hint, slots, slots),
                color = c.textSecondary, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            Text(stringResource(R.string.toolbar_section_toolbar), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 12.dp, bottom = 6.dp))
            BoxWithConstraints(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp).clip(RoundedCornerShape(8.dp)).background(c.pressed)
                    .onGloballyPositioned { barRect = it.boundsInRoot() },
            ) {
                val itemW = minOf(72.dp, (maxWidth - 8.dp) / (slots + 1))
                Row(Modifier.fillMaxWidth().heightIn(min = BottomBarHeight), verticalAlignment = Alignment.CenterVertically) {
                    for (t in bar) Draggable(t) { BarTool(t, itemW) }
                    Spacer(Modifier.weight(1f))
                    // More tools always ends the bar
                    Box(Modifier.alpha(0.5f)) { BarTool(null, itemW) }
                }
            }
            Text(stringResource(R.string.common_tool_more_tools), color = c.text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 6.dp))
            Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp).heightIn(min = 96.dp).onGloballyPositioned { gridRect = it.boundsInRoot() }) {
                grid.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { t -> Box(Modifier.weight(1f), contentAlignment = Alignment.Center) { Draggable(t) { GridTool(t) } } }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        // the tool under the finger
        val t = dragging
        if (t != null) {
            val half = with(LocalDensity.current) { 36.dp.toPx() }
            Box(
                Modifier.offset { IntOffset((dragPos.x - origin.x - half).roundToInt(), (dragPos.y - origin.y - half).roundToInt()) }
                    .shadow(6.dp, RoundedCornerShape(8.dp)).background(c.surface, RoundedCornerShape(8.dp)),
            ) { BarTool(t, 72.dp) }
        }
    }
}

/** A tool as the bar shows it: icon over its short label ([tool] null: More tools). */
@Composable
private fun BarTool(tool: QuickTool?, width: androidx.compose.ui.unit.Dp) {
    val c = pdfColors
    // grows with a large font setting, as the bar does
    Column(Modifier.width(width).heightIn(min = BottomBarHeight), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center) {
        Icon(tool?.icon ?: LucideIcons.LayoutGrid, null, tint = c.icon, modifier = Modifier.size(24.dp))
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(tool?.label ?: R.string.common_tool_more_tools), color = c.text, fontSize = 12.sp, lineHeight = 14.sp, maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, textAlign = TextAlign.Center,
        )
    }
}

/** A tool as More tools shows it: a tinted tile over its name. */
@Composable
private fun GridTool(tool: QuickTool) {
    val c = pdfColors
    Column(Modifier.width(80.dp).padding(vertical = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)).background(tileBackground(tool.hue, c.dark)), contentAlignment = Alignment.Center) {
            Icon(tool.icon, null, tint = tool.hue, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.height(8.dp))
        Text(stringResource(tool.title), color = c.text, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 2, lineHeight = 16.sp)
    }
}
