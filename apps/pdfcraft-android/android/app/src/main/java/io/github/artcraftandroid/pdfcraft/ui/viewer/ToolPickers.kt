package io.github.artcraftandroid.pdfcraft.ui.viewer


import androidx.compose.foundation.selection.selectable
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.collect

import io.github.artcraftandroid.pdfcraft.engine.userMessage

import io.github.artcraftandroid.pdfcraft.engine.Engine

import io.github.artcraftandroid.pdfcraft.core.MobileException

import androidx.lifecycle.viewModelScope

import androidx.compose.runtime.snapshotFlow

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import io.github.artcraftandroid.pdfcraft.ui.LocalUndo
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.drawText
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.res.stringResource
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.text
import io.github.artcraftandroid.pdfcraft.core.TextRange
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetHost
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlinx.coroutines.Job
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.material3.minimumInteractiveComponentSize

// Moved from Tools.kt as is (#730): the annotate tools' sub-toolbars, sheets and pickers.

/** Which property picker a sub-toolbar has open. */
enum class Picker { None, Color, Width, TextSize, Presets, PenStyle, EraserSize, Stamp, Shapes, Guides, MoreShapes }

/** What [annotateToolName] calls the tool in use, for the log line of a switch (#660). */
internal fun annotateToolName(tool: ViewerTool, markup: TextMarkup, eraser: Boolean, lasso: Boolean): String = when (tool) {
    ViewerTool.Markup -> markup.name.lowercase()
    ViewerTool.Draw -> if (eraser) "eraser" else if (lasso) "lasso" else "pen"
    else -> tool.name.lowercase()
}

/**
 * From the pens to a text markup ([markup]) or back to the pens or the eraser ([eraser]) in one
 * tap (#660), without Done: leaving Draw adds its strokes first, as Done does.
 */
internal fun switchAnnotateTool(vm: AppViewModel, doc: OpenDocument, markup: TextMarkup? = null, eraser: Boolean = false) {
    val ctl = doc.controller
    val from = annotateToolName(ctl.tool, ctl.markup, ctl.eraserMode, ctl.lassoMode)
    val target = if (markup != null) ViewerTool.Markup else ViewerTool.Draw
    if (ctl.tool != target) vm.enterTool(doc, target)
    if (ctl.tool != target) return
    if (markup != null) ctl.markup = markup
    else {
        ctl.eraserMode = eraser
        ctl.lassoMode = false
        ctl.inkShapeMode = InkShapeMode.Freehand
    }
    ctl.picker = Picker.None
    MarkupDiag.toolSwitch(doc, from, annotateToolName(ctl.tool, ctl.markup, ctl.eraserMode, ctl.lassoMode))
}

/**
 * Highlight's row: the four text markups, each with its caption (#526), then the first [quick]
 * colours of [MARKUP_QUICK_COLORS] one tap away (#650) and the colour button, which opens all
 * colours and the opacity.
 */
@Composable
internal fun MarkupSubTools(
    markup: TextMarkup, onMarkup: (TextMarkup) -> Unit, color: Color, colorOpen: Boolean,
    quick: Int = 0, onQuickColor: (Color) -> Unit = {},
    // the pens and the eraser one tap away (#660); none in tests that lay out the row alone
    onPen: (() -> Unit)? = null, onEraser: (() -> Unit)? = null,
    // last, so a trailing lambda is the colour button's, as it was before the quick colours came
    onColor: () -> Unit,
) {
    SubToolbar {
        if (onPen != null) SubTool(LucideIcons.PenLine, stringResource(R.string.common_tool_draw), false, onPen, caption = stringResource(R.string.common_tool_draw))
        if (onEraser != null) SubTool(LucideIcons.Eraser, stringResource(R.string.toolbar_eraser), false, onEraser, caption = stringResource(R.string.ink_toolbar_eraser))
        if (onPen != null || onEraser != null) SubDivider()
        SubTool(LucideIcons.Highlighter, stringResource(R.string.common_tool_highlight), markup == TextMarkup.Highlight, { onMarkup(TextMarkup.Highlight) }, caption = stringResource(R.string.tool_caption_highlight))
        SubTool(LucideIcons.Underline, stringResource(R.string.common_tool_underline), markup == TextMarkup.Underline, { onMarkup(TextMarkup.Underline) }, caption = stringResource(R.string.tool_caption_underline))
        SubTool(LucideIcons.Strikethrough, stringResource(R.string.toolbar_strikethrough), markup == TextMarkup.Strikethrough, { onMarkup(TextMarkup.Strikethrough) }, caption = stringResource(R.string.tool_caption_strike))
        SubTool(io.github.artcraftandroid.pdfcraft.ui.PdfIcons.SquigglyUnderline, stringResource(R.string.toolbar_squiggly), markup == TextMarkup.Squiggly, { onMarkup(TextMarkup.Squiggly) }, caption = stringResource(R.string.tool_caption_squiggly))
        SubDivider()
        for (argb in MARKUP_QUICK_COLORS.take(quick)) {
            val quickColor = Color(argb)
            val name = CommentPalette.firstOrNull { it.first.toArgbInt() == quickColor.toArgbInt() }?.second
            QuickColorDot(quickColor, stringResource(name ?: R.string.viewer_markup_color), quickColor.toArgbInt() == color.toArgbInt()) { onQuickColor(quickColor) }
        }
        ColorDot(color, colorOpen, onColor)
    }
}

/** Comment's row: text and voice comments and Stamp, with captions (#526); with Stamp, its choice. */
@Composable
internal fun CommentSubTools(mode: CommentMode, onMode: (CommentMode) -> Unit, stampOpen: Boolean, onChooseStamp: () -> Unit) {
    SubToolbar {
        SubTool(LucideIcons.MessageSquareText, stringResource(R.string.toolbar_text_comment), mode == CommentMode.Text, { onMode(CommentMode.Text) }, caption = stringResource(R.string.tool_caption_text_comment))
        SubTool(LucideIcons.MicAudioLines, stringResource(R.string.toolbar_voice_comment), mode == CommentMode.Voice, { onMode(CommentMode.Voice) }, caption = stringResource(R.string.tool_caption_voice_comment))
        SubTool(LucideIcons.Stamp, stringResource(R.string.toolbar_stamp), mode == CommentMode.Stamp, { onMode(CommentMode.Stamp) }, caption = stringResource(R.string.tool_caption_stamp))
        if (mode == CommentMode.Stamp) {
            SubDivider()
            SubProperty(LucideIcons.SlidersHorizontal, stringResource(R.string.toolbar_stamp_choose), stampOpen, onChooseStamp)
        }
    }
}

/** Text's row: Text box and Callout with captions (#526), then the text size and colour. */
@Composable
internal fun TextSubTools(callout: Boolean, onCallout: (Boolean) -> Unit, sizeOpen: Boolean, onSize: () -> Unit, color: Color, colorOpen: Boolean, onColor: () -> Unit) {
    SubToolbar {
        SubTool(LucideIcons.Type, stringResource(R.string.toolbar_text_box), !callout, { onCallout(false) }, caption = stringResource(R.string.tool_caption_text_box))
        SubTool(LucideIcons.MessageSquareQuote, stringResource(R.string.toolbar_callout), callout, { onCallout(true) }, caption = stringResource(R.string.tool_caption_callout))
        SubDivider()
        SubProperty(LucideIcons.ALargeSmall, stringResource(R.string.toolbar_text_size), sizeOpen, onSize)
        ColorDot(color, colorOpen, onColor)
    }
}

/** The active tool's sub-toolbar. */
@Composable
fun ToolSubToolbar(vm: AppViewModel, doc: OpenDocument) {
    val ctl = doc.controller
    val prefs = vm.settings.toolPrefs
    val picker = ctl.picker
    fun toggle(p: Picker) {
        ctl.picker = if (ctl.picker == p) Picker.None else p
    }
    when (ctl.tool) {
        ViewerTool.Markup -> MarkupSubTools(
            ctl.markup, { ctl.markup = it }, prefs.color(ctl.markup.styled), picker == Picker.Color,
            quick = markupQuickColorCount(androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp - MARKUP_ROW_SWITCH_DP),
            onQuickColor = {
                MarkupDiag.quickColor(doc, ctl.markup)
                prefs.setColor(ctl.markup.styled, it)
            },
            onPen = { switchAnnotateTool(vm, doc) },
            onEraser = { switchAnnotateTool(vm, doc, eraser = true) },
            onColor = { toggle(Picker.Color) },
        )
        ViewerTool.Draw -> DrawToolbar(vm, doc)
        ViewerTool.Comment -> CommentSubTools(ctl.commentMode, {
            ctl.commentMode = it
            if (it == CommentMode.Stamp) ctl.picker = Picker.Stamp
        }, picker == Picker.Stamp) { toggle(Picker.Stamp) }
        ViewerTool.Text -> TextSubTools(
            ctl.textCallout, { ctl.textCallout = it }, picker == Picker.TextSize, { toggle(Picker.TextSize) },
            prefs.color(StyledTool.FreeText), picker == Picker.Color,
        ) { toggle(Picker.Color) }
        ViewerTool.FillSign -> FillSignToolbar(vm, doc)
        ViewerTool.Edit -> androidx.compose.foundation.layout.Column {
            if (vm.editor.editMode == EditMode.Link && vm.editor.editSelection == null) LinkCommandsRow(vm, doc)
            EditToolbar(vm, doc)
        }
        ViewerTool.Measure -> MeasureToolbar(vm, doc)
        ViewerTool.None -> Unit
    }
}

/**
 * The active tool's sheets (colour, thickness, text size; Fill & Sign's Sign panel), drawn over
 * the whole viewer rather than inside the bottom bar.
 */
@Composable
fun ToolSheets(vm: AppViewModel, doc: OpenDocument) {
    val ctl = doc.controller
    val styled = when (ctl.tool) {
        ViewerTool.Markup -> ctl.markup.styled
        ViewerTool.Draw -> StyledTool.Ink
        ViewerTool.Text -> StyledTool.FreeText
        else -> null
    }
    SheetHost(ctl.picker.takeIf { it != Picker.None && styled != null }) { p ->
        PropertyPicker(vm, ctl, styled ?: StyledTool.Highlight, p, ctl.markup) {
            ctl.picker = Picker.None
            // Pen selection works even after the eraser/lasso or shape tool was active.
            if (p == Picker.Presets && ctl.tool == ViewerTool.Draw) {
                ctl.eraserMode = false
                ctl.lassoMode = false
                ctl.inkShapeMode = InkShapeMode.Freehand
            }
        }
    }
    if (ctl.tool == ViewerTool.FillSign && ctl.signPanel) SignPanel(vm, doc) { ctl.signPanel = false }
    if (ctl.tool == ViewerTool.Comment && ctl.picker == Picker.Stamp) StampPicker(ctl) { ctl.picker = Picker.None }
}

/** The colour, thickness or text-size picker of a tool, as a sheet above its sub-toolbar. */
@Composable
fun PropertyPicker(vm: AppViewModel, ctl: ViewerController, tool: StyledTool, picker: Picker, markup: TextMarkup, onDismiss: () -> Unit) {
    val prefs = vm.settings.toolPrefs
    val c = pdfColors
    // over the tool's bar, which stays in view with the picker's button shaded
    PdfSheet(onDismiss = onDismiss, modifier = Modifier.padding(bottom = (if (tool == StyledTool.Ink) DrawToolbarHeight else BottomBarHeight) + 1.dp).navigationBarsPadding(), bottomBand = false) {
        when (picker) {
            Picker.Color -> {
                val title = stringResource(
                    when (tool) {
                        StyledTool.Highlight -> R.string.toolbar_color_highlight
                        StyledTool.Underline -> R.string.toolbar_color_underline
                        StyledTool.Strikethrough -> R.string.toolbar_color_strikethrough
                        StyledTool.Squiggly -> R.string.toolbar_color_squiggly
                        StyledTool.Ink -> R.string.toolbar_color_drawing
                        StyledTool.FreeText -> R.string.toolbar_color_text
                        StyledTool.Note -> R.string.toolbar_color_comment
                    },
                )
                Text(title, color = c.text, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, top = 3.dp, bottom = 18.dp))
                ColorGrid(prefs.color(tool)) { prefs.setColor(tool, it) }
                if (tool != StyledTool.FreeText) {
                    Text(stringResource(R.string.toolbar_opacity), color = c.text, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(start = 78.dp, top = 20.dp, bottom = 8.dp))
                    OpacitySlider(prefs.color(tool), prefs.opacity(tool)) { prefs.setOpacity(tool, it) }
                }
                Spacer(Modifier.height(30.dp))
            }
            Picker.Width -> {
                Text(stringResource(R.string.toolbar_line_thickness), color = c.text, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, bottom = 12.dp))
                InkWidthRow(prefs.color(StyledTool.Ink), prefs.inkWidth) { prefs.updateInkWidth(it) }
            }
            Picker.TextSize -> {
                Text(stringResource(R.string.toolbar_text_size), color = c.text, fontSize = 17.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.toolbar_text_size_pt, prefs.textSize.toInt()), color = c.text, fontSize = 15.sp, modifier = Modifier.width(56.dp))
                    Slider(
                        value = prefs.textSize,
                        onValueChange = { v -> prefs.updateTextSize((Math.round(v / 2f) * 2f).coerceIn(6f, 22f)) },
                        valueRange = 6f..22f,
                        steps = TextSizes.size - 2,
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            Picker.Presets -> InkPresetsPicker(vm, onDismiss)
            Picker.PenStyle -> PenStylePicker(vm)
            Picker.Shapes -> DrawShapesPicker(ctl, prefs)
            Picker.MoreShapes -> DrawMoreShapesPicker(ctl, prefs)
            Picker.Guides -> DrawGuidesPicker(ctl)
            Picker.EraserSize -> {
                Text(
                    stringResource(R.string.ink_eraser_size_title),
                    color = c.text,
                    fontSize = 17.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
                )
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.ink_eraser_diameter, (prefs.eraserRadius * 2f).toInt()),
                        color = c.text,
                        fontSize = 15.sp,
                        modifier = Modifier.width(90.dp),
                    )
                    Slider(
                        value = prefs.eraserRadius,
                        onValueChange = { prefs.updateEraserRadius(it) },
                        valueRange = ERASER_RADIUS_MIN..ERASER_RADIUS_MAX,
                        colors = SliderDefaults.colors(thumbColor = c.accent, activeTrackColor = c.accent),
                        modifier = Modifier.weight(1f),
                    )
                }
                Box(Modifier.fillMaxWidth().height(78.dp), contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size((prefs.eraserRadius * 2f).dp)
                            .border(2.dp, c.toolAccent, RoundedCornerShape(50)),
                    )
                }
                Text(
                    stringResource(R.string.ink_eraser_size_help),
                    color = c.label,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .toggleable(value = prefs.eraseWholeStrokes, role = androidx.compose.ui.semantics.Role.Switch) { prefs.updateEraseWholeStrokes(it) }
                        .padding(horizontal = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ink_eraser_whole), color = c.text, fontSize = 15.sp)
                        Text(stringResource(R.string.ink_eraser_whole_summary), color = c.label, fontSize = 12.sp)
                    }
                    io.github.artcraftandroid.pdfcraft.ui.PdfSwitch(prefs.eraseWholeStrokes, null)
                }
                Spacer(Modifier.height(12.dp))
            }
            Picker.None, Picker.Stamp -> Unit
        }
    }
}

/** The seven line widths as bars of growing thickness, the chosen one framed. */
@Composable
fun InkWidthRow(color: Color, selected: Float?, onPick: (Float) -> Unit) {
    val c = pdfColors
    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), horizontalArrangement = Arrangement.Center) {
        InkWidths.forEachIndexed { i, w ->
            val description = stringResource(R.string.toolbar_stroke_width, i + 1)
            Box(
                Modifier.size(48.dp)
                    .then(if (selected == w) Modifier.border(1.5.dp, c.accent, RoundedCornerShape(4.dp)) else Modifier)
                    .selectable(selected = selected == w, role = Role.RadioButton) { onPick(w) }
                    .semantics { contentDescription = description },
                contentAlignment = Alignment.Center,
            ) {
                Canvas(Modifier.size(32.dp)) { drawCircle(color, radius = (1.5f + w / 2f).dp.toPx(), center = center) }
            }
        }
    }
}

/** The 18 colour swatches: 32 dp rounded squares 18 dp apart, the chosen one ringed and checked. */
@Composable
fun ColorGrid(selected: Color, onPick: (Color) -> Unit) {
    val c = pdfColors
    val ring = c.toolAccent
    Column(Modifier.fillMaxWidth().padding(horizontal = 51.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        CommentPalette.chunked(6).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                row.forEach { (color, name) ->
                    val label = stringResource(name)
                    val isSel = color.toArgbInt() == selected.toArgbInt()
                    // only a swatch the colour of the sheet itself needs an outline to be seen
                    val outlined = kotlin.math.abs(color.luminance() - c.surface.luminance()) < 0.08f
                    Box(
                        Modifier.minimumInteractiveComponentSize().size(32.dp)
                            .drawBehind {
                                if (isSel) {
                                    val w = 2.dp.toPx()
                                    val out = 4.dp.toPx()
                                    drawRoundRect(
                                        ring, topLeft = Offset(-out + w / 2, -out + w / 2), size = Size(size.width + 2 * out - w, size.height + 2 * out - w),
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()), style = androidx.compose.ui.graphics.drawscope.Stroke(w),
                                    )
                                }
                            }
                            .clip(RoundedCornerShape(3.dp))
                            .background(color)
                            .then(if (outlined) Modifier.border(1.dp, c.divider, RoundedCornerShape(3.dp)) else Modifier)
                            .selectable(selected = isSel, role = Role.RadioButton) { onPick(color) }
                            .semantics { contentDescription = label },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSel) Icon(LucideIcons.Check, null, tint = if (color.luminance() > 0.5f) Color.Black else Color.White, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }
    }
}

/**
 * Opacity from 25 % to 100 %: a 35 dp checkered track (12 dp squares) washed with the tool's colour,
 * and a round thumb in that colour, as the reference draws it. Tap or drag along it.
 */
@Composable
fun OpacitySlider(color: Color, value: Float, onChange: (Float) -> Unit) {
    val min = 0.25f
    val sheet = pdfColors.surface
    val description = stringResource(R.string.toolbar_opacity_percent, (value * 100).toInt())
    Box(
        Modifier.fillMaxWidth().padding(horizontal = 78.dp).height(35.dp)
            .pointerInput(Unit) {
                fun at(x: Float) = onChange((min + (x / size.width).coerceIn(0f, 1f) * (1f - min)))
                detectTapGestures { at(it.x) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ -> onChange((min + (change.position.x / size.width).coerceIn(0f, 1f) * (1f - min))) }
            }
            .semantics { contentDescription = description }
            // 5% steps from 25% to 100%: TalkBack's swipe up/down and Switch Access can change it.
            .rangeSemantics(value, min..1f, steps = 14, state = description, onChange = onChange),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val sq = 12.dp.toPx()
            clipPath(androidx.compose.ui.graphics.Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())))
            }) {
                var y = 0f
                var r = 0
                while (y < size.height) {
                    var x = 0f
                    var col = 0
                    while (x < size.width) {
                        drawRect(if ((col + r) % 2 == 0) Color(0xFF8A8A8A) else Color(0xFFE6E6E6), Offset(x, y), Size(sq, sq))
                        x += sq
                        col++
                    }
                    y += sq
                    r++
                }
                drawRect(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(color.copy(alpha = min), color)), size = size)
            }
            val cx = (value - min) / (1f - min) * size.width
            val radius = 10.5.dp.toPx()
            // a thin white ring, a gap in the sheet's colour, then the colour itself
            drawCircle(Color.White, radius, Offset(cx, size.height / 2))
            drawCircle(sheet, radius - 1.dp.toPx(), Offset(cx, size.height / 2))
            drawCircle(color.copy(alpha = 1f), radius - 3.dp.toPx(), Offset(cx, size.height / 2))
        }
    }
}

private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

/** The standard stamps, as a list above the Comment tool's bar; picking one closes it. */
@Composable
private fun StampPicker(ctl: ViewerController, onDismiss: () -> Unit) {
    val c = pdfColors
    PdfSheet(onDismiss = onDismiss, modifier = Modifier.padding(bottom = BottomBarHeight + 1.dp).navigationBarsPadding(), bottomBand = false) {
        Text(
            stringResource(R.string.toolbar_stamp_choose), color = c.text, fontSize = 17.sp,
            fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            modifier = Modifier.padding(start = 20.dp, top = 3.dp, bottom = 10.dp),
        )
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
            for (stamp in STANDARD_STAMPS) {
                val selected = ctl.stamp == stamp
                Text(
                    stampLabel(stamp),
                    color = if (selected) c.toolAccent else c.text,
                    fontSize = 16.sp,
                    fontWeight = if (selected) androidx.compose.ui.text.font.FontWeight.SemiBold else null,
                    modifier = Modifier.fillMaxWidth()
                        .selectable(selected = selected, role = Role.RadioButton) {
                            ctl.stamp = stamp
                            onDismiss()
                        }
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                )
            }
            Spacer(Modifier.height(12.dp))
        }
    }
}
