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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.pdfColors

/**
 * Two visible tiers of direct-access annotation controls, inspired by the discoverability of
 * pen-first note apps. Top: all five actual pen types, Eraser, Lasso. Bottom: the favourites,
 * then Color, Settings, Shapes and Guides.
 *
 * The top row (seven buttons, 336 dp) scrolls on screens narrower than that. The bottom row's four
 * fixed buttons always keep their place; only the favourites before them scroll (#522).
 */
internal val DrawToolbarHeight = 112.dp
private val DrawToolSlot = 48.dp
private val DrawRowHeight = 56.dp

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
internal fun DrawToolbar(vm: AppViewModel, doc: OpenDocument) = DrawToolbarBar(doc.controller, vm.settings.toolPrefs)

/** The Draw bar itself, from the viewer's state and the tool settings (laid out alone in tests). */
@Composable
internal fun DrawToolbarBar(ctl: ViewerController, prefs: ToolPrefs) {
    val c = pdfColors
    val color = prefs.color(StyledTool.Ink)
    val opacity = prefs.opacity(StyledTool.Ink)
    val picker = ctl.picker

    Column(Modifier.fillMaxWidth().height(DrawToolbarHeight).barTopLine().background(c.surface).palmGuardedTaps(ctl, prefs)) {
        // Seven primary tools. 7 * 48dp = 336dp; all are directly usable on normal phones.
        BoxWithConstraints(Modifier.fillMaxWidth().height(DrawRowHeight)) {
            val contentWidth = maxOf(maxWidth, DrawToolSlot * 7)
            val pens = rememberScrollState()
            Box(Modifier.fillMaxSize().horizontalScroll(pens)) {
                Row(
                    Modifier.width(contentWidth).height(DrawRowHeight),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    InkStyle.entries.forEach { style ->
                        val label = stringResource(inkStyleLabel(style))
                        val active = !ctl.eraserMode && !ctl.lassoMode &&
                            ctl.inkShapeMode == InkShapeMode.Freehand && prefs.inkStyle == style
                        val pen = prefs.penSettings(style)
                        DrawToolbarButton(label, active, onClick = {
                            // Like the eraser: a second tap on the selected pen opens its colour and width.
                            ctl.picker = penButtonPicker(active, picker)
                            if (!active) activateDrawingPen(ctl, prefs, style)
                        }, caption = stringResource(when (style) {
                            InkStyle.Ballpoint -> R.string.ink_toolbar_ballpoint
                            InkStyle.Fountain -> R.string.ink_toolbar_fountain
                            InkStyle.Pencil -> R.string.ink_toolbar_pencil
                            InkStyle.Brush -> R.string.ink_toolbar_brush
                            InkStyle.Highlighter -> R.string.ink_toolbar_marker
                        }), onClickLabel = if (active) stringResource(R.string.ink_pen_edit_action) else null) {
                            PenNibPreview(Color(pen.argb), pen.width, pen.opacity, style, Modifier.size(29.dp, 31.dp))
                        }
                    }
                    DrawToolbarButton(
                        label = stringResource(R.string.toolbar_eraser),
                        active = ctl.eraserMode,
                        caption = stringResource(R.string.ink_toolbar_eraser),
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
                        onClick = {
                            ctl.lassoMode = true
                            ctl.eraserMode = false
                            ctl.picker = Picker.None
                        },
                    ) { Icon(LucideIcons.Lasso, null, tint = if (ctl.lassoMode) c.toolAccent else c.icon, modifier = Modifier.size(24.dp)) }
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
            val shape = DrawShapes.firstOrNull { it.mode == ctl.inkShapeMode }
                ?.takeIf { !ctl.eraserMode && !ctl.lassoMode }
            val shapesOpen = picker == Picker.Shapes
            DrawToolbarButton(
                label = stringResource(R.string.ink_shapes),
                active = shape != null || shapesOpen,
                caption = if (shape != null) stringResource(shape.caption) else stringResource(R.string.ink_shapes),
                onClick = { ctl.picker = if (shapesOpen) Picker.None else Picker.Shapes },
            ) {
                Icon(
                    shape?.icon ?: LucideIcons.Shapes, null,
                    tint = if (shape != null || shapesOpen) c.toolAccent else c.icon,
                    modifier = Modifier.size(24.dp),
                )
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

/** What the favourites part of Draw's second row is tagged with in tests. */
internal const val DrawFavouritesTag = "draw_favourites"

/** One of Draw's shapes: its mode, icon, full name (read aloud) and short caption. */
internal class DrawShape(val mode: InkShapeMode, val icon: ImageVector, @StringRes val label: Int, @StringRes val caption: Int)

/** The seven shapes, in the order the Shapes sheet shows them. */
internal val DrawShapes = listOf(
    DrawShape(InkShapeMode.Line, LucideIcons.Minus, R.string.toolbar_draw_line, R.string.ink_toolbar_line),
    DrawShape(InkShapeMode.Rectangle, LucideIcons.Square, R.string.toolbar_draw_rectangle, R.string.ink_toolbar_rectangle),
    DrawShape(InkShapeMode.Oval, LucideIcons.Circle, R.string.toolbar_draw_oval, R.string.ink_toolbar_oval),
    DrawShape(InkShapeMode.Arrow, LucideIcons.ArrowUpRight, R.string.toolbar_draw_arrow, R.string.ink_toolbar_arrow),
    DrawShape(InkShapeMode.Cloud, LucideIcons.Cloud, R.string.toolbar_draw_cloud, R.string.ink_toolbar_cloud),
    DrawShape(InkShapeMode.Polygon, LucideIcons.Pentagon, R.string.toolbar_draw_polygon, R.string.ink_toolbar_polygon),
    DrawShape(InkShapeMode.Polyline, LucideIcons.Spline, R.string.toolbar_draw_polyline, R.string.ink_toolbar_polyline),
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
        ) { Icon(LucideIcons.Ruler, null, tint = if (ctl.rulerOn) c.toolAccent else c.icon, modifier = Modifier.size(24.dp)) }
        DrawToolbarButton(
            label = stringResource(R.string.ink_protractor),
            active = ctl.protractorOn,
            onClick = { ctl.protractorOn = !ctl.protractorOn; ctl.picker = Picker.None },
            role = Role.Switch,
        ) { Icon(LucideIcons.Gauge, null, tint = if (ctl.protractorOn) c.toolAccent else c.icon, modifier = Modifier.size(24.dp)) }
        DrawToolbarButton(
            label = stringResource(R.string.zoom_box),
            active = ctl.zoomBoxOn,
            onClick = { ctl.zoomBoxOn = !ctl.zoomBoxOn; ctl.picker = Picker.None },
            role = Role.Switch,
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
        Icon(shape.icon, null, tint = if (selected) c.toolAccent else c.icon, modifier = Modifier.size(24.dp))
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
    icon: @Composable () -> Unit,
) {
    val c = pdfColors
    val shape = RoundedCornerShape(10.dp)
    val tint = if (active) c.toolAccent else c.label
    Column(
        Modifier.width(DrawToolSlot).height(54.dp)
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
        Text(
            caption,
            color = tint,
            fontSize = 10.sp,
            fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            lineHeight = 11.sp,
        )
    }
}
