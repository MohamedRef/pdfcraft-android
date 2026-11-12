package io.github.artcraftandroid.pdfcraft.ui.viewer

import android.graphics.BitmapFactory
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.engine.toBitmap
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BackgroundSpec
import io.github.artcraftandroid.pdfcraft.ui.HeaderFooterSpec
import io.github.artcraftandroid.pdfcraft.ui.MARK_TOKENS
import io.github.artcraftandroid.pdfcraft.ui.MarkFont
import io.github.artcraftandroid.pdfcraft.ui.MarkKind
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.SwitchRow
import io.github.artcraftandroid.pdfcraft.ui.ToolChoice
import io.github.artcraftandroid.pdfcraft.ui.ToolPage
import io.github.artcraftandroid.pdfcraft.ui.WatermarkSpec
import io.github.artcraftandroid.pdfcraft.ui.expandMarkTokens
import io.github.artcraftandroid.pdfcraft.ui.markArgs
import io.github.artcraftandroid.pdfcraft.ui.markPreviewArgs
import io.github.artcraftandroid.pdfcraft.ui.parsePageRange
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The colours page marks offer, as `#RRGGBB`. */
private val MARK_COLORS = listOf("#000000", "#808080", "#D32F2F", "#1565C0", "#2E7D32", "#F9A825", "#6A1B9A", "#FFFFFF")
private val BACKGROUND_COLORS = listOf("#FFF8DC", "#FFFFCC", "#E3F2FD", "#E8F5E9", "#FCE4EC", "#F3E5F5", "#EEEEEE", "#FFFFFF")

/**
 * Page marks (#168): Header & footer, Watermark and Background, each with a preview of the page as
 * it will look, the pages to mark, and Add, or Update and Remove when the document already has one.
 * The preview is the engine's own drawing of the mark on a copy of the page (#314); each Add,
 * Update or Remove is one undo step.
 */
@Composable
fun PageMarksScreen(vm: AppViewModel, doc: OpenDocument) {
    val c = pdfColors
    var tab by remember { mutableStateOf(MarkKind.HeaderFooter) }
    var existing by remember { mutableStateOf<Set<MarkKind>>(emptySet()) }
    LaunchedEffect(doc.info.generation) { existing = vm.docTools.pageMarks(doc) }
    var hf by remember { mutableStateOf(HeaderFooterSpec()) }
    var wm by remember { mutableStateOf(WatermarkSpec()) }
    var bg by remember { mutableStateOf(BackgroundSpec()) }
    var allPages by remember { mutableStateOf(true) }
    var range by remember { mutableStateOf("") }
    val count = doc.info.pages.size
    val pages = if (allPages) null else parsePageRange(range, count)
    val rangeOk = allPages || pages != null
    // a picture picked for the tab that asked for it
    var pictureFor by remember { mutableStateOf<MarkKind?>(null) }
    val picked = vm.docTools.markImage
    LaunchedEffect(picked) {
        val f = picked ?: return@LaunchedEffect
        when (pictureFor) {
            MarkKind.Watermark -> wm = wm.copy(imagePath = f.absolutePath, imageName = f.name)
            MarkKind.Background -> bg = bg.copy(imagePath = f.absolutePath, imageName = f.name)
            else -> {}
        }
    }
    val update = tab in existing
    val usable = rangeOk && when (tab) {
        MarkKind.HeaderFooter -> hf.usable
        MarkKind.Watermark -> wm.usable
        MarkKind.Background -> true
    }
    val close = { vm.docTools.clearMarkImage(); vm.tools.toolScreen = null }
    ToolPage(
        stringResource(R.string.marks_title), close,
        stringResource(if (update) R.string.marks_update else R.string.marks_add), usable,
        {
            val spec: Any = when (tab) { MarkKind.HeaderFooter -> hf; MarkKind.Watermark -> wm; MarkKind.Background -> bg }
            vm.docTools.applyMark(doc, tab, markArgs(spec, pages, replace = update), update)
        },
        trailing = {
            if (update) {
                Text(
                    stringResource(R.string.marks_remove), color = c.red, fontSize = 16.sp,
                    modifier = Modifier.clickable(role = Role.Button) { vm.docTools.removeMarks(doc, tab) { existing = existing - tab } }.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
        },
    ) {
        Box(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(1.dp).background(c.divider))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                listOf(
                    MarkKind.HeaderFooter to R.string.marks_tab_header_footer,
                    MarkKind.Watermark to R.string.marks_tab_watermark,
                    MarkKind.Background to R.string.marks_tab_background,
                ).forEachIndexed { i, (k, label) ->
                    Column(Modifier.clickable { tab = k }.padding(horizontal = 8.dp).width(IntrinsicSize.Max)) {
                        Text(stringResource(label), color = if (tab == k) c.label else c.text, fontSize = 16.sp, modifier = Modifier.padding(vertical = 12.dp))
                        Box(Modifier.fillMaxWidth().height(2.dp).background(if (tab == k) c.label else Color.Transparent))
                    }
                    if (i < 2) Spacer(Modifier.width(14.dp))
                }
            }
        }
        Column(Modifier.verticalScroll(rememberScrollState())) {
            MarkPreview(vm, doc, tab, hf, wm, bg, pages, update)
            if (update) {
                Text(stringResource(R.string.marks_update_note), color = c.textSecondary, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
            }
            Text(stringResource(R.string.marks_pages), color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, top = 12.dp))
            ToolChoice(allPages, stringResource(R.string.tools_all_pages), null) { allPages = true }
            ToolChoice(!allPages, stringResource(R.string.marks_page_range), null) { allPages = false }
            if (!allPages) {
                PdfField(
                    range, { range = it.take(200) }, stringResource(R.string.marks_page_range_hint, count),
                    modifier = Modifier.padding(horizontal = 24.dp), error = range.isNotBlank() && pages == null,
                )
            }
            Spacer(Modifier.height(8.dp))
            when (tab) {
                MarkKind.HeaderFooter -> HeaderFooterControls(hf) { hf = it }
                MarkKind.Watermark -> WatermarkControls(wm, { wm = it }) { pictureFor = MarkKind.Watermark; vm.docTools.pickMarkImage() }
                MarkKind.Background -> BackgroundControls(bg, { bg = it }) { pictureFor = MarkKind.Background; vm.docTools.pickMarkImage() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * A marked page with the mark on it. The engine draws it on a copy of the page (#314) a moment
 * after the settings stop changing; until then the app draws an approximation over the page as it
 * is now, so the preview follows every change at once.
 */
@Composable
private fun MarkPreview(vm: AppViewModel, doc: OpenDocument, tab: MarkKind, hf: HeaderFooterSpec, wm: WatermarkSpec, bg: BackgroundSpec, pages: List<Int>?, replace: Boolean) {
    val c = pdfColors
    val marked = pages ?: (1..doc.info.pages.size).toList()
    var at by remember(marked) { mutableStateOf(0) }
    val page = (marked.getOrNull(at) ?: 1) - 1
    val info = doc.info.pages.getOrNull(page) ?: return
    var bitmap by remember(page, doc.info.generation) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(page, doc.info.generation) {
        val eng = vm.engine ?: return@LaunchedEffect
        val scale = 900f / maxOf(info.width, info.height).coerceAtLeast(1f)
        bitmap = runCatching { withContext(eng.thumbnails) { eng.session.renderPage(doc.id, page.toUInt(), scale, null).toBitmap().asImageBitmap() } }.getOrNull()
    }
    // the engine's preview for these exact settings, once it is ready
    val spec: Any = when (tab) { MarkKind.HeaderFooter -> hf; MarkKind.Watermark -> wm; MarkKind.Background -> bg }
    val usable = when (tab) { MarkKind.HeaderFooter -> hf.usable; MarkKind.Watermark -> wm.usable; MarkKind.Background -> true }
    val previewArgs = remember(tab, spec, at, replace, doc.info.pages.size) { markPreviewArgs(spec, at, doc.info.pages.size, replace) }
    var exact by remember(page, doc.info.generation) { mutableStateOf<Pair<Map<String, Any?>, ImageBitmap>?>(null) }
    LaunchedEffect(previewArgs, page, doc.info.generation, usable) {
        if (!usable) return@LaunchedEffect
        delay(400)
        val img = vm.docTools.markPreview(doc, tab, previewArgs, page, 900f)?.asImageBitmap() ?: return@LaunchedEffect
        exact = previewArgs to img
    }
    val engineImage = exact?.takeIf { it.first == previewArgs && usable }?.second
    val picturePath = when (tab) { MarkKind.Watermark -> wm.imagePath; MarkKind.Background -> bg.imagePath; else -> null }
    var picture by remember(picturePath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(picturePath) {
        picture = picturePath?.let { p ->
            withContext(Dispatchers.IO) {
                runCatching {
                    val o = BitmapFactory.Options().apply { inSampleSize = 2 }
                    BitmapFactory.decodeFile(p, o)?.asImageBitmap()
                }.getOrNull()
            }
        }
    }
    val previewDesc = stringResource(R.string.marks_preview_page, page + 1)
    Column(Modifier.fillMaxWidth().background(c.desk).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        BoxWithConstraints(Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
            val ratio = info.width / info.height.coerceAtLeast(1f)
            val h = minOf(340f, maxWidth.value / ratio)
            Canvas(Modifier.size((h * ratio).dp, h.dp).semantics { contentDescription = previewDesc }) {
                val pt = size.width / info.width.coerceAtLeast(1f)
                val img = bitmap
                if (engineImage != null) {
                    drawImage(engineImage, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()))
                    return@Canvas
                }
                when (tab) {
                    MarkKind.Background -> {
                        // the background sits behind the content: white paper takes its colour or picture
                        drawRect(Color.White)
                        val pic = picture
                        if (bg.imagePath != null && pic != null) drawPictureFitted(pic, bg.scale, 0f, bg.opacity)
                        else if (bg.imagePath == null) drawRect(parseHex(bg.color).copy(alpha = bg.opacity))
                        img?.let { drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), blendMode = BlendMode.Multiply) }
                    }
                    else -> {
                        drawRect(Color.White)
                        if (tab == MarkKind.Watermark && wm.behind) drawWatermark(wm, picture, pt)
                        img?.let { drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), blendMode = if (tab == MarkKind.Watermark && wm.behind) BlendMode.Multiply else BlendMode.SrcOver) }
                        if (tab == MarkKind.Watermark && !wm.behind) drawWatermark(wm, picture, pt)
                        if (tab == MarkKind.HeaderFooter) drawHeaderFooter(hf, at, doc.info.pages.size, pt)
                    }
                }
            }
        }
        if (marked.size > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 8.dp)) {
                Text("‹", color = if (at > 0) c.label else c.textSecondary, fontSize = 22.sp,
                    modifier = Modifier.clickable(enabled = at > 0, role = Role.Button) { at-- }.padding(horizontal = 16.dp))
                Text(stringResource(R.string.marks_preview_page, page + 1), color = c.text, fontSize = 13.sp)
                Text("›", color = if (at < marked.size - 1) c.label else c.textSecondary, fontSize = 22.sp,
                    modifier = Modifier.clickable(enabled = at < marked.size - 1, role = Role.Button) { at++ }.padding(horizontal = 16.dp))
            }
        }
    }
}

private fun DrawScope.drawPictureFitted(pic: ImageBitmap, scale: Float, rotation: Float, alpha: Float) {
    val fit = minOf(size.width / pic.width, size.height / pic.height) * scale.coerceIn(0.01f, 1f)
    val w = pic.width * fit
    val h = pic.height * fit
    rotate(-rotation) {
        drawImage(
            pic, dstOffset = IntOffset(((size.width - w) / 2).roundToInt(), ((size.height - h) / 2).roundToInt()),
            dstSize = IntSize(w.roundToInt().coerceAtLeast(1), h.roundToInt().coerceAtLeast(1)), alpha = alpha.coerceIn(0f, 1f),
        )
    }
}

private fun DrawScope.drawWatermark(wm: WatermarkSpec, picture: ImageBitmap?, pt: Float) {
    if (wm.imagePath != null) {
        picture?.let { drawPictureFitted(it, wm.scale, wm.rotation, wm.opacity) }
        return
    }
    val text = wm.text.trim().ifEmpty { return }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
        color = parseHex(wm.color).copy(alpha = wm.opacity.coerceIn(0f, 1f)).toArgb()
        textAlign = Paint.Align.CENTER
        textSize = 100f
    }
    // fitted to the page along its rotation, as the engine fits text without a font size
    val rad = Math.toRadians(wm.rotation.toDouble())
    val reach = (size.width * kotlin.math.abs(kotlin.math.cos(rad)) + size.height * kotlin.math.abs(kotlin.math.sin(rad))).toFloat()
    paint.textSize = 100f * (reach * 0.8f / paint.measureText(text).coerceAtLeast(1f)).coerceAtMost(size.height / 100f)
    rotate(-wm.rotation) {
        drawContext.canvas.nativeCanvas.drawText(text, size.width / 2, size.height / 2 - (paint.ascent() + paint.descent()) / 2, paint)
    }
}

private fun DrawScope.drawHeaderFooter(hf: HeaderFooterSpec, index: Int, count: Int, pt: Float) {
    val base = when (hf.font) { MarkFont.Helvetica -> Typeface.SANS_SERIF; MarkFont.Times -> Typeface.SERIF; MarkFont.Courier -> Typeface.MONOSPACE }
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = base
        color = parseHex(hf.color).toArgb()
        textSize = hf.fontSize * pt
    }
    val (top, bottom, left, right) = hf.margins.map { it * pt }
    hf.slots.forEachIndexed { i, template ->
        if (template.isBlank()) return@forEachIndexed
        val lines = expandMarkTokens(template, index, count, hf.startNumber).lines()
        val header = i < 3
        paint.textAlign = when (i % 3) { 0 -> Paint.Align.LEFT; 1 -> Paint.Align.CENTER; else -> Paint.Align.RIGHT }
        val x = when (i % 3) { 0 -> left; 1 -> size.width / 2; else -> size.width - right }
        val lineH = paint.fontSpacing
        lines.forEachIndexed { k, line ->
            // a header hangs from the top margin; a footer sits on the bottom one
            val y = if (header) top - paint.ascent() + k * lineH else size.height - bottom - (lines.size - 1 - k) * lineH
            drawContext.canvas.nativeCanvas.drawText(line, x, y, paint)
        }
    }
}

@Composable
private fun SliderRow(label: String, value: Float, range: ClosedFloatingPointRange<Float>, onChange: (Float) -> Unit) {
    val c = pdfColors
    Text(label, color = c.text, fontSize = 15.sp, modifier = Modifier.padding(start = 24.dp, top = 8.dp))
    Slider(value = value.coerceIn(range), onValueChange = onChange, valueRange = range, modifier = Modifier.padding(horizontal = 24.dp))
}

@Composable
private fun ColorRow(colors: List<String>, selected: String, onPick: (String) -> Unit) {
    val c = pdfColors
    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (hex in colors) {
            val on = hex.equals(selected, ignoreCase = true)
            Box(
                Modifier.size(32.dp).clip(CircleShape).background(parseHex(hex))
                    .border(if (on) 3.dp else 1.dp, if (on) c.toolAccent else c.divider, CircleShape)
                    .clickable(role = Role.RadioButton) { onPick(hex) }.semantics { contentDescription = hex },
            )
        }
    }
}

@Composable
private fun Chip(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val c = pdfColors
    Box(
        Modifier.clip(RoundedCornerShape(16.dp)).background(if (selected) c.toolAccent.copy(alpha = 0.18f) else c.pressed)
            .border(1.dp, if (selected) c.toolAccent else Color.Transparent, RoundedCornerShape(16.dp))
            .clickable(role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
    ) { Text(label, color = c.label, fontSize = 14.sp, maxLines = 1) }
}

@Composable
private fun PictureRow(name: String?, onPick: () -> Unit) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(name ?: stringResource(R.string.marks_no_picture), color = if (name == null) c.textSecondary else c.text, fontSize = 15.sp,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Chip(stringResource(R.string.marks_choose_picture), onClick = onPick)
    }
}

@Composable
private fun HeaderFooterControls(hf: HeaderFooterSpec, onChange: (HeaderFooterSpec) -> Unit) {
    val c = pdfColors
    var slot by remember { mutableStateOf(4) }
    val names = listOf(
        R.string.marks_slot_header_left, R.string.marks_slot_header_center, R.string.marks_slot_header_right,
        R.string.marks_slot_footer_left, R.string.marks_slot_footer_center, R.string.marks_slot_footer_right,
    )
    Text(stringResource(R.string.marks_text_boxes), color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp))
    // the six boxes laid out as on the page: headers above, footers below
    for (row in 0..1) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            for (col in 0..2) {
                val i = row * 3 + col
                val name = stringResource(names[i])
                Box(
                    Modifier.weight(1f).height(40.dp).clip(RoundedCornerShape(6.dp))
                        .border(if (slot == i) 2.dp else 1.dp, if (slot == i) c.toolAccent else c.divider, RoundedCornerShape(6.dp))
                        .clickable(role = Role.RadioButton) { slot = i }.semantics { contentDescription = name }.padding(horizontal = 6.dp),
                    contentAlignment = when (col) { 0 -> Alignment.CenterStart; 1 -> Alignment.Center; else -> Alignment.CenterEnd },
                ) {
                    Text(hf.slots[i].ifEmpty { "—" }, color = if (hf.slots[i].isEmpty()) c.textSecondary else c.text, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    Text(stringResource(names[slot]), color = c.text, fontSize = 14.sp, modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp))
    PdfField(
        hf.slots[slot], { v -> onChange(hf.copy(slots = hf.slots.toMutableList().also { it[slot] = v.take(500) })) },
        stringResource(R.string.marks_slot_hint), modifier = Modifier.padding(horizontal = 24.dp),
    )
    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val tokenNames = listOf(
            R.string.marks_token_page, R.string.marks_token_page_of_n, R.string.marks_token_x_of_n, R.string.marks_token_count,
            R.string.marks_token_date_us, R.string.marks_token_date_iso, R.string.marks_token_date_eu, R.string.marks_token_bates,
        )
        MARK_TOKENS.forEachIndexed { i, token ->
            Chip(stringResource(tokenNames[i])) {
                val cur = hf.slots[slot]
                val joined = if (cur.isEmpty() || cur.endsWith(' ')) cur + token else "$cur $token"
                onChange(hf.copy(slots = hf.slots.toMutableList().also { it[slot] = joined }))
            }
        }
    }
    Text(stringResource(R.string.marks_font), color = c.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 24.dp, top = 8.dp))
    Row(Modifier.padding(horizontal = 24.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in MarkFont.entries) Chip(f.name, hf.font == f) { onChange(hf.copy(font = f)) }
    }
    SliderRow(stringResource(R.string.marks_font_size, hf.fontSize.roundToInt()), hf.fontSize, 6f..36f) { onChange(hf.copy(fontSize = it.roundToInt().toFloat())) }
    ColorRow(MARK_COLORS, hf.color) { onChange(hf.copy(color = it)) }
    SliderRow(stringResource(R.string.marks_margin_top_bottom, hf.margins[0].roundToInt()), hf.margins[0], 0f..144f) {
        val v = it.roundToInt().toFloat(); onChange(hf.copy(margins = listOf(v, v, hf.margins[2], hf.margins[3])))
    }
    SliderRow(stringResource(R.string.marks_margin_sides, hf.margins[2].roundToInt()), hf.margins[2], 0f..144f) {
        val v = it.roundToInt().toFloat(); onChange(hf.copy(margins = listOf(hf.margins[0], hf.margins[1], v, v)))
    }
    Text(stringResource(R.string.marks_start_number), color = c.text, fontSize = 15.sp, modifier = Modifier.padding(start = 24.dp, top = 8.dp, bottom = 4.dp))
    PdfField(
        hf.startNumber.toString(), { v -> v.filter(Char::isDigit).take(6).toIntOrNull()?.let { onChange(hf.copy(startNumber = it.coerceAtLeast(1))) } }, "1",
        modifier = Modifier.padding(horizontal = 24.dp),
        keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
    )
    SwitchRow(stringResource(R.string.marks_shrink), hf.shrink) { onChange(hf.copy(shrink = it)) }
}

@Composable
private fun WatermarkControls(wm: WatermarkSpec, onChange: (WatermarkSpec) -> Unit, onPickPicture: () -> Unit) {
    val picture = wm.imagePath != null
    ToolChoice(!picture, stringResource(R.string.marks_source_text), null) { onChange(wm.copy(imagePath = null, imageName = null)) }
    ToolChoice(picture, stringResource(R.string.marks_source_picture), null) { if (!picture) onPickPicture() }
    if (picture) {
        PictureRow(wm.imageName, onPickPicture)
        SliderRow(stringResource(R.string.marks_scale, (wm.scale * 100).roundToInt()), wm.scale, 0.05f..1f) { onChange(wm.copy(scale = it)) }
    } else {
        PdfField(wm.text, { onChange(wm.copy(text = it.take(200))) }, stringResource(R.string.marks_watermark_hint), modifier = Modifier.padding(horizontal = 24.dp))
        ColorRow(MARK_COLORS, wm.color) { onChange(wm.copy(color = it)) }
    }
    SliderRow(stringResource(R.string.marks_rotation, wm.rotation.roundToInt()), wm.rotation, -90f..90f) { onChange(wm.copy(rotation = (it / 5).roundToInt() * 5f)) }
    SliderRow(stringResource(R.string.marks_opacity, (wm.opacity * 100).roundToInt()), wm.opacity, 0.05f..1f) { onChange(wm.copy(opacity = it)) }
    SwitchRow(stringResource(R.string.marks_behind), wm.behind) { onChange(wm.copy(behind = it)) }
}

@Composable
private fun BackgroundControls(bg: BackgroundSpec, onChange: (BackgroundSpec) -> Unit, onPickPicture: () -> Unit) {
    val picture = bg.imagePath != null
    ToolChoice(!picture, stringResource(R.string.marks_source_color), null) { onChange(bg.copy(imagePath = null, imageName = null)) }
    ToolChoice(picture, stringResource(R.string.marks_source_picture), null) { if (!picture) onPickPicture() }
    if (picture) {
        PictureRow(bg.imageName, onPickPicture)
        SliderRow(stringResource(R.string.marks_scale, (bg.scale * 100).roundToInt()), bg.scale, 0.05f..1f) { onChange(bg.copy(scale = it)) }
    } else {
        ColorRow(BACKGROUND_COLORS, bg.color) { onChange(bg.copy(color = it)) }
    }
    SliderRow(stringResource(R.string.marks_opacity, (bg.opacity * 100).roundToInt()), bg.opacity, 0.05f..1f) { onChange(bg.copy(opacity = it)) }
}
