package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.BarDivider
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * Two visible tiers of direct-access annotation controls, inspired by the discoverability of
 * pen-first note apps. Top: all five actual pen types, Eraser, Lasso. Bottom: the favourites,
 * then Color, Settings, Shapes and Guides.
 *
 * The top row (eight buttons, 384 dp) scrolls on screens narrower than that; where it fits with the
 * text markups, its buttons share the bar (#704). The bottom row's four fixed buttons always keep
 * their place; only the favourites before them scroll (#522).
 */
internal val DrawToolbarHeight = 112.dp
private val DrawToolSlot = 48.dp
private val DrawRowHeight = 56.dp

/** The widest a first-row button grows where the whole row fits, as on a tablet (#704). */
private val DrawWideSlot = 64.dp

/** A Guides sheet button (#704): the three have the sheet's width to share, so their names read whole. */
private val DrawGuideSlot = 80.dp

/** A caption's size, then the sizes it steps down through to fit its button before it is cut (#704). */
private val DrawCaptionSizes = listOf(10.sp, 9.5.sp, 9.sp, 8.5.sp, 8.sp, 7.5.sp)

internal fun activateDrawingPen(ctl: ViewerController, prefs: ToolPrefs, style: InkStyle) {
    prefs.updateInkStyle(style)
    ctl.eraserMode = false
    ctl.lassoMode = false
    ctl.inkShapeMode = InkShapeMode.Freehand
}

/** What tapping a pen button opens: its style popover when it is already the selected pen. */
internal fun penButtonPicker(penAlreadyActive: Boolean, current: Picker): Picker =
    if (penAlreadyActive && current != Picker.PenStyle) Picker.PenStyle else Picker.None

internal fun activateDrawingPreset(ctl: ViewerController, prefs: ToolPrefs, index: Int): Boolean {
    if (!prefs.applyInkPreset(index)) return false
    ctl.eraserMode = false
    ctl.lassoMode = false
    ctl.inkShapeMode = InkShapeMode.Freehand
    return true
}

@Composable
internal fun DrawToolbar(vm: AppViewModel, doc: OpenDocument) =
    DrawToolbarBar(doc.controller, vm.settings.toolPrefs, onMarkup = { switchAnnotateTool(vm, doc, markup = it) })

/** The Draw bar itself, from the viewer's state and the tool settings (laid out alone in tests). */
@Composable
internal fun DrawToolbarBar(ctl: ViewerController, prefs: ToolPrefs, onMarkup: ((TextMarkup) -> Unit)? = null) {
    val c = pdfColors
    val color = prefs.color(StyledTool.Ink)
    val opacity = prefs.opacity(StyledTool.Ink)
    val picker = ctl.picker

    Column(Modifier.fillMaxWidth().height(DrawToolbarHeight).barTopLine().background(c.surface).palmGuardedTaps(ctl, prefs)) {
        // Six pens, the eraser and the lasso: 8 * 48dp = 384dp, a whole phone bar (#697).
        BoxWithConstraints(Modifier.fillMaxWidth().height(DrawRowHeight)) {
            // The pens, the eraser and the lasso, then the four text markups (#660), in one row
            // that scrolls. Where they all fit (a tablet) they share the bar; where they don't,
            // the pens, the eraser and the lasso keep the bar's width, so the lasso stays in view
            // with six pens on a 384 dp phone, and the markups follow in the scroll (#697).
            // read here: the Box and Row scopes below cannot see BoxWithConstraints' maxWidth
            val barWidth = maxWidth
            val primarySlots = InkStyle.entries.size + 2
            val markupSlots = if (onMarkup != null) 4 else 0
            // the markups' divider, with a little room on each side
            val divider = if (onMarkup != null) 9.dp else 0.dp
            val markupWidth = DrawToolSlot * markupSlots + divider
            val shared = DrawToolSlot * primarySlots + markupWidth <= barWidth
            val primaryWidth = maxOf(barWidth, DrawToolSlot * primarySlots)
            // Sharing the bar, each button takes its share of it up to 64 dp, so a tablet has the
            // room to show its captions whole at full size; on a phone each keeps 48 dp (#704).
            val slot = if (!shared) DrawToolSlot else
                ((barWidth - divider) / (primarySlots + markupSlots)).coerceIn(DrawToolSlot, DrawWideSlot)
            val pens = rememberScrollState()
            val primaryTools: @Composable () -> Unit = {
                InkStyle.entries.forEach { style ->
                    val label = stringResource(inkStyleLabel(style))
                    val active = !ctl.eraserMode && !ctl.lassoMode &&
                        ctl.inkShapeMode == InkShapeMode.Freehand && prefs.inkStyle == style
                    val pen = prefs.penSettings(style)
                    DrawToolbarButton(label, active, width = slot, onClick = {
                        // Like the eraser: a second tap on the selected pen opens its colour and width.
                        ctl.picker = penButtonPicker(active, picker)
                        if (!active) activateDrawingPen(ctl, prefs, style)
                    }, caption = stringResource(when (style) {
                        InkStyle.Ballpoint -> R.string.ink_toolbar_ballpoint
                        InkStyle.Fountain -> R.string.ink_toolbar_fountain
                        InkStyle.Calligraphy -> R.string.ink_toolbar_calligraphy
                        InkStyle.Pencil -> R.string.ink_toolbar_pencil
                        InkStyle.Brush -> R.string.ink_toolbar_brush
                        InkStyle.Highlighter -> R.string.ink_toolbar_marker
                    }), onClickLabel = if (active) stringResource(R.string.ink_pen_edit_action) else null) {
                        PenNibPreview(Color(inkColorOnPaper(pen.argb, style, ctl.darkPaper)), pen.width, pen.opacity, style, Modifier.size(29.dp, 31.dp))
                    }
                }
                DrawToolbarButton(
                    label = stringResource(R.string.toolbar_eraser),
                    active = ctl.eraserMode,
                    caption = stringResource(R.string.ink_toolbar_eraser),
                    width = slot,
                    onClick = {
                        if (ctl.eraserMode) {
                            ctl.picker = if (picker == Picker.EraserSize) Picker.None else Picker.EraserSize
                        } else {
                            ctl.eraserMode = true
                            ctl.lassoMode = false
                            ctl.picker = Picker.None
                        }
                    },
                ) { Icon(LucideIcons.Eraser, null, tint = if (ctl.eraserMode) c.toolAccent else c.icon, modifier = Modifier.size(24.dp)) }
                DrawToolbarButton(
                    label = stringResource(R.string.toolbar_ink_lasso),
                    active = ctl.lassoMode,
                    caption = stringResource(R.string.ink_toolbar_lasso),
                    width = slot,
                    onClick = {
                        ctl.lassoMode = true
                        ctl.eraserMode = false
                        ctl.picker = Picker.None
                    },
                ) { Icon(LucideIcons.Lasso, null, tint = if (ctl.lassoMode) c.toolAccent else c.icon, modifier = Modifier.size(24.dp)) }
            }
            val markupTools: @Composable () -> Unit = {
                if (onMarkup != null) {
                    // the text markups, one tap from the pens and back (#660): they mark the
                    // text itself, unlike the free-hand highlighter pen above
                    BarDivider(height = 30.dp)
                    DrawToolbarButton(
                        label = stringResource(R.string.common_tool_highlight),
                        active = false,
                        caption = stringResource(R.string.tool_caption_highlight),
                        width = slot,
                        onClick = { onMarkup(TextMarkup.Highlight) },
                    ) { Icon(LucideIcons.Highlighter, null, tint = c.icon, modifier = Modifier.size(24.dp)) }
                    DrawToolbarButton(
                        label = stringResource(R.string.common_tool_underline),
                        active = false,
                        caption = stringResource(R.string.tool_caption_underline),
                        width = slot,
                        onClick = { onMarkup(TextMarkup.Underline) },
                    ) { Icon(LucideIcons.Underline, null, tint = c.icon, modifier = Modifier.size(24.dp)) }
                    DrawToolbarButton(
                        label = stringResource(R.string.toolbar_strikethrough),
                        active = false,
                        caption = stringResource(R.string.tool_caption_strike),
                        width = slot,
                        onClick = { onMarkup(TextMarkup.Strikethrough) },
                    ) { Icon(LucideIcons.Strikethrough, null, tint = c.icon, modifier = Modifier.size(24.dp)) }
                    DrawToolbarButton(
                        label = stringResource(R.string.toolbar_squiggly),
                        active = false,
                        caption = stringResource(R.string.tool_caption_squiggly),
                        width = slot,
                        onClick = { onMarkup(TextMarkup.Squiggly) },
                    ) { Icon(io.github.artcraftandroid.pdfcraft.ui.PdfIcons.SquigglyUnderline, null, tint = c.icon, modifier = Modifier.size(24.dp)) }
                }
            }
            Box(Modifier.fillMaxSize().horizontalScroll(pens)) {
                if (shared) {
                    Row(
                        Modifier.width(barWidth).height(DrawRowHeight),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceEvenly,
                    ) {
                        primaryTools()
                        markupTools()
                    }
                } else {
                    Row(Modifier.height(DrawRowHeight), verticalAlignment = Alignment.CenterVertically) {
                        Row(
                            Modifier.width(primaryWidth).height(DrawRowHeight).testTag(DrawPrimaryToolsTag),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) { primaryTools() }
                        Row(
                            Modifier.width(markupWidth).height(DrawRowHeight),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) { markupTools() }
                    }
                }
            }
            MoreToTheSide(pens)
        }

        // Favourites, then four fixed buttons (#522). The favourites scroll in their own part, so
        // however many there are they cannot push Color, Settings, Shapes or Guides off the bar;
        // the seven shapes and the ruler, protractor and zoom box open from Shapes and Guides.
        Row(
            Modifier.fillMaxWidth().height(DrawRowHeight).padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val favourites = rememberScrollState()
            Box(Modifier.weight(1f).fillMaxHeight().testTag(DrawFavouritesTag)) {
                Row(
                    Modifier.fillMaxHeight().horizontalScroll(favourites),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    prefs.inkPresets.forEachIndexed { index, preset ->
                        val slot = stringResource(R.string.ink_preset_slot, index + 1)
                        val active = !ctl.eraserMode && !ctl.lassoMode &&
                            ctl.inkShapeMode == InkShapeMode.Freehand && prefs.currentInkPreset() == preset
                        DrawToolbarButton(slot, active, onClick = {
                            if (activateDrawingPreset(ctl, prefs, index)) ctl.picker = Picker.None
                        }) {
                            Box(contentAlignment = Alignment.Center) {
                                PenNibPreview(
                                    Color(preset.argb), preset.width, preset.opacity, preset.style,
                                    Modifier.size(27.dp, 31.dp),
                                )
                            }
                        }
                    }
                }
                MoreToTheSide(favourites)
            }
            if (ctl.eraserMode) {
                DrawToolbarButton(
                    label = stringResource(R.string.ink_eraser_size_title),
                    active = picker == Picker.EraserSize,
                    caption = stringResource(R.string.ink_eraser_size_short, (prefs.eraserRadius * 2f).toInt()),
                    onClick = { ctl.picker = if (picker == Picker.EraserSize) Picker.None else Picker.EraserSize },
                ) {
                    Box(
                        Modifier.size((8f + prefs.eraserRadius * 0.4f).dp)
                            .border(1.5.dp, if (picker == Picker.EraserSize) c.toolAccent else c.icon, RoundedCornerShape(50)),
                    )
                }
            } else {
                DrawToolbarButton(
                    label = stringResource(R.string.toolbar_color_drawing),
                    active = picker == Picker.Color,
                    caption = stringResource(R.string.ink_toolbar_color),
                    onClick = { ctl.picker = if (picker == Picker.Color) Picker.None else Picker.Color },
                ) {
                    Box(
                        Modifier.size(23.dp).border(1.5.dp, c.icon, RoundedCornerShape(50))
                            .padding(3.dp).background(color, RoundedCornerShape(50)),
                    )
                }
            }
            DrawToolbarButton(
                label = stringResource(R.string.ink_pen_settings_title),
                active = picker == Picker.Presets,
                caption = stringResource(R.string.ink_toolbar_settings),
                onClick = { ctl.picker = if (picker == Picker.Presets) Picker.None else Picker.Presets },
            ) {
                Icon(LucideIcons.SlidersHorizontal, null, tint = if (picker == Picker.Presets) c.toolAccent else c.icon, modifier = Modifier.size(24.dp))
            }
            // Shows the shape in use, so a glance at the bar says which one a stroke will draw.
            val shape = (DrawShapes + DrawMoreShapes).firstOrNull { it.mode == ctl.inkShapeMode }
                ?.takeIf { !ctl.eraserMode && !ctl.lassoMode }
            val shapesOpen = picker == Picker.Shapes || picker == Picker.MoreShapes
            DrawToolbarButton(
                label = stringResource(R.string.ink_shapes),
                active = shape != null || shapesOpen,
                caption = if (shape != null) stringResource(shape.caption) else stringResource(R.string.ink_shapes),
                onClick = { ctl.picker = if (shapesOpen) Picker.None else Picker.Shapes },
            ) {
                val tint = if (shape != null || shapesOpen) c.toolAccent else c.icon
                if (shape != null && shape.icon == null) {
                    androidx.compose.foundation.Canvas(Modifier.size(24.dp)) { drawCatalogShapeIcon(shape.mode, tint) }
                } else {
                    Icon(shape?.icon ?: LucideIcons.Shapes, null, tint = tint, modifier = Modifier.size(24.dp))
                }
            }
            val guidesOn = ctl.rulerOn || ctl.protractorOn || ctl.zoomBoxOn
            val guidesOpen = picker == Picker.Guides
            DrawToolbarButton(
                label = stringResource(R.string.ink_guides_title),
                active = guidesOn || guidesOpen,
                caption = stringResource(R.string.ink_toolbar_guides),
                onClick = { ctl.picker = if (guidesOpen) Picker.None else Picker.Guides },
            ) {
                Icon(LucideIcons.Ruler, null, tint = if (guidesOn || guidesOpen) c.toolAccent else c.icon, modifier = Modifier.size(24.dp))
            }
        }
    }
}

/** What the pens, the eraser and the lasso of Draw's first row are tagged with in tests (#697). */
internal const val DrawPrimaryToolsTag = "draw_primary_tools"

/** What the favourites part of Draw's second row is tagged with in tests. */
internal const val DrawFavouritesTag = "draw_favourites"

/** One of Draw's shapes: its mode, icon (null: a picture drawn from its geometry, #693), full name (read aloud) and short caption. */
internal class DrawShape(val mode: InkShapeMode, val icon: ImageVector?, @StringRes val label: Int, @StringRes val caption: Int)

/** The seven shapes, in the order the Shapes sheet shows them. */
internal val DrawShapes = listOf(
    DrawShape(InkShapeMode.Line, io.github.artcraftandroid.pdfcraft.ui.PdfIcons.StraightLine, R.string.toolbar_draw_line, R.string.ink_toolbar_line),
    DrawShape(InkShapeMode.Rectangle, LucideIcons.Square, R.string.toolbar_draw_rectangle, R.string.ink_toolbar_rectangle),
    DrawShape(InkShapeMode.Oval, LucideIcons.Circle, R.string.toolbar_draw_oval, R.string.ink_toolbar_oval),
    DrawShape(InkShapeMode.Arrow, LucideIcons.ArrowUpRight, R.string.toolbar_draw_arrow, R.string.ink_toolbar_arrow),
    DrawShape(InkShapeMode.Cloud, LucideIcons.Cloud, R.string.toolbar_draw_cloud, R.string.ink_toolbar_cloud),
    DrawShape(InkShapeMode.Polygon, LucideIcons.Pentagon, R.string.toolbar_draw_polygon, R.string.ink_toolbar_polygon),
    DrawShape(InkShapeMode.Polyline, LucideIcons.Spline, R.string.toolbar_draw_polyline, R.string.ink_toolbar_polyline),
)

/**
 * More shapes (#693), on their own sheet: flat ones, then solids. Each name is its caption too,
 * but for the three too long for a 48 dp button in some language, which have a short one (#704).
 */
internal val DrawMoreShapes = listOf(
    DrawShape(InkShapeMode.Triangle, null, R.string.ink_shape_triangle, R.string.ink_shape_triangle),
    DrawShape(InkShapeMode.RightTriangle, null, R.string.ink_shape_right_triangle, R.string.ink_toolbar_right_triangle),
    DrawShape(InkShapeMode.Diamond, null, R.string.ink_shape_diamond, R.string.ink_shape_diamond),
    DrawShape(InkShapeMode.Pentagon, null, R.string.ink_shape_pentagon, R.string.ink_shape_pentagon),
    DrawShape(InkShapeMode.Hexagon, null, R.string.ink_shape_hexagon, R.string.ink_shape_hexagon),
    DrawShape(InkShapeMode.DoubleArrow, null, R.string.ink_shape_double_arrow, R.string.ink_toolbar_double_arrow),
    DrawShape(InkShapeMode.Axes, null, R.string.ink_shape_axes, R.string.ink_shape_axes),
    DrawShape(InkShapeMode.Cube, null, R.string.ink_shape_cube, R.string.ink_shape_cube),
    DrawShape(InkShapeMode.Prism, null, R.string.ink_shape_prism, R.string.ink_shape_prism),
    DrawShape(InkShapeMode.Pyramid, null, R.string.ink_shape_pyramid, R.string.ink_shape_pyramid),
    DrawShape(InkShapeMode.Cylinder, null, R.string.ink_shape_cylinder, R.string.ink_shape_cylinder),
    DrawShape(InkShapeMode.Cone, null, R.string.ink_shape_cone, R.string.ink_shape_cone),
    DrawShape(InkShapeMode.Sphere, null, R.string.ink_shape_sphere, R.string.ink_shape_sphere),
    DrawShape(InkShapeMode.Hemisphere, null, R.string.ink_shape_hemisphere, R.string.ink_shape_hemisphere),
    DrawShape(InkShapeMode.Frustum, null, R.string.ink_shape_frustum, R.string.ink_toolbar_frustum),
    DrawShape(InkShapeMode.Tube, null, R.string.ink_shape_tube, R.string.ink_shape_tube),
)

/**
 * A fade with a chevron at each side of [scroll]'s row that has more past it, so a row of
 * favourites wider than its part shows that it scrolls.
 */
@Composable
private fun BoxScope.MoreToTheSide(scroll: ScrollState) {
    val c = pdfColors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    @Composable
    fun Cue(alignment: Alignment, atEnd: Boolean) {
        // The side of the screen this cue is on, whatever the layout direction.
        val onRight = atEnd != rtl
        val fade = listOf(c.surface.copy(alpha = 0f), c.surface)
        Box(
            Modifier.align(alignment).width(22.dp).fillMaxHeight()
                .background(Brush.horizontalGradient(if (onRight) fade else fade.reversed())),
            contentAlignment = if (onRight) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            // The chevrons mirror themselves right to left (Icons.kt), so the chevron is chosen by the logical side.
            Icon(if (atEnd) LucideIcons.ChevronRight else LucideIcons.ChevronLeft, null, tint = c.label, modifier = Modifier.size(14.dp))
        }
    }
    if (scroll.canScrollBackward) Cue(Alignment.CenterStart, atEnd = false)
    if (scroll.canScrollForward) Cue(Alignment.CenterEnd, atEnd = true)
}

/** The Shapes sheet: the seven shapes; picking one draws with it and closes the sheet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun DrawShapesPicker(ctl: ViewerController, prefs: ToolPrefs) {
    val c = pdfColors
    Text(
        stringResource(R.string.ink_shapes), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
    )
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        DrawShapes.forEach { DrawShapeButton(ctl, prefs, it) }
        // the catalog's shapes and solids, on a sheet of their own (#693)
        DrawToolbarButton(stringResource(R.string.ink_more_shapes), false, caption = stringResource(R.string.ink_toolbar_more_shapes), onClick = {
            ctl.picker = Picker.MoreShapes
        }) {
            Icon(LucideIcons.Shapes, null, tint = c.icon, modifier = Modifier.size(24.dp))
        }
    }
}

/** More shapes (#693): flat shapes, then solids with their hidden edges dashed, each drawn by one drag. */
@Composable
internal fun DrawMoreShapesPicker(ctl: ViewerController, prefs: ToolPrefs) {
    val c = pdfColors
    Text(
        stringResource(R.string.ink_more_shapes), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, bottom = 8.dp),
    )
    for ((title, modes) in listOf(R.string.ink_flat_shapes to CATALOG_FLAT_SHAPES, R.string.ink_solid_shapes to CATALOG_SOLIDS)) {
        Text(stringResource(title), color = c.label, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, bottom = 4.dp))
        FlowRow(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            DrawMoreShapes.filter { it.mode in modes }.forEach { DrawShapeButton(ctl, prefs, it) }
        }
    }
}

/** The Guides sheet: Ruler, Protractor and Zoom box, each switched on or off with one tap. */
@Composable
internal fun DrawGuidesPicker(ctl: ViewerController) {
    val c = pdfColors
    Text(
        stringResource(R.string.ink_guides_title), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 20.dp, bottom = 12.dp),
    )
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp).padding(bottom = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        DrawToolbarButton(
            label = stringResource(R.string.ink_ruler),
            active = ctl.rulerOn,
            onClick = { ctl.rulerOn = !ctl.rulerOn; ctl.picker = Picker.None },
            role = Role.Switch,
            width = DrawGuideSlot,
        ) { Icon(LucideIcons.Ruler, null, tint = if (ctl.rulerOn) c.toolAccent else c.icon, modifier = Modifier.size(24.dp)) }
        DrawToolbarButton(
            label = stringResource(R.string.ink_protractor),
            active = ctl.protractorOn,
            onClick = { ctl.protractorOn = !ctl.protractorOn; ctl.picker = Picker.None },
            role = Role.Switch,
            width = DrawGuideSlot,
        ) { Icon(LucideIcons.Gauge, null, tint = if (ctl.protractorOn) c.toolAccent else c.icon, modifier = Modifier.size(24.dp)) }
        DrawToolbarButton(
            label = stringResource(R.string.zoom_box),
            active = ctl.zoomBoxOn,
            onClick = { ctl.zoomBoxOn = !ctl.zoomBoxOn; ctl.picker = Picker.None },
            role = Role.Switch,
            width = DrawGuideSlot,
        ) { Icon(LucideIcons.ZoomIn, null, tint = if (ctl.zoomBoxOn) c.toolAccent else c.icon, modifier = Modifier.size(24.dp)) }
    }
}

@Composable
private fun DrawShapeButton(ctl: ViewerController, prefs: ToolPrefs, shape: DrawShape) {
    val c = pdfColors
    val selected = ctl.inkShapeMode == shape.mode && !ctl.eraserMode && !ctl.lassoMode
    DrawToolbarButton(stringResource(shape.label), selected, caption = stringResource(shape.caption), onClick = {
        prefs.selectWritingPen()
        ctl.inkShapeMode = shape.mode
        ctl.eraserMode = false
        ctl.lassoMode = false
        ctl.picker = Picker.None
    }) {
        val tint = if (selected) c.toolAccent else c.icon
        val icon = shape.icon
        if (icon != null) Icon(icon, null, tint = tint, modifier = Modifier.size(24.dp))
        else androidx.compose.foundation.Canvas(Modifier.size(24.dp)) { drawCatalogShapeIcon(shape.mode, tint) }
    }
}

@Composable
private fun DrawToolbarButton(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
    caption: String = label,
    onClickLabel: String? = null,
    role: Role = Role.RadioButton,
    width: Dp = DrawToolSlot,
    icon: @Composable () -> Unit,
) {
    val c = pdfColors
    val shape = RoundedCornerShape(10.dp)
    val tint = if (active) c.toolAccent else c.label
    Column(
        Modifier.width(width).height(54.dp)
            .background(if (active) c.pressed else Color.Transparent, shape)
            .then(if (active) Modifier.border(1.dp, c.toolAccent.copy(alpha = 0.5f), shape) else Modifier)
            .clickable(role = role, onClickLabel = onClickLabel, onClick = onClick)
            .semantics {
                contentDescription = label
                if (role == Role.Switch) toggleableState = ToggleableState(active) else selected = active
            },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(Modifier.height(33.dp), contentAlignment = Alignment.Center) { icon() }
        DrawCaption(caption, tint, bold = active)
    }
}

/**
 * A Draw button's [caption] on one line that fits the button (#704): set at the largest of
 * [DrawCaptionSizes] at which it fits in SemiBold, the weight a selected button's caption takes,
 * and in Regular, so selecting a button never changes its caption's size. Only a caption too long
 * even at the smallest is cut with an ellipsis. Like WordWrapLabel (Components.kt), on one line.
 */
@Composable
private fun DrawCaption(caption: String, color: Color, bold: Boolean) {
    val measurer = rememberTextMeasurer()
    val style = LocalTextStyle.current.merge(TextStyle(fontSize = DrawCaptionSizes.first(), lineHeight = 11.sp))
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val room = constraints.maxWidth
        val size = remember(caption, style, room, measurer) {
            DrawCaptionSizes.firstOrNull { candidate ->
                listOf(FontWeight.SemiBold, FontWeight.Normal).all { weight ->
                    val at = style.copy(fontSize = candidate, fontWeight = weight)
                    measurer.measure(caption, at, softWrap = false, maxLines = 1).size.width <= room
                }
            } ?: DrawCaptionSizes.last()
        }
        Text(
            caption,
            color = color,
            style = style.copy(fontSize = size, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal),
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
