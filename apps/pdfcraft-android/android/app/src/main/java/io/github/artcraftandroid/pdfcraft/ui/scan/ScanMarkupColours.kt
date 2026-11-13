package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import io.github.artcraftandroid.pdfcraft.ui.viewer.HsvColorPicker
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkPaletteSection

/*
 * Colours in the scan's Markup (#503): the viewer's colour sheet (its palettes, a custom colour and
 * the recent colours, one store for both), an eyedropper that reads the made page's pixels, and the
 * eraser's three fills.
 */

/** What Erase paints over what it rubs (#503), with its word in the log. */
internal enum class ScanEraseFill(val id: String, val tool: ScanMarkTool) {
    /** The paper around it (#495): the default. */
    Paper("paper", ScanMarkTool.Erase),

    /** The picked colour, opaque, with the eraser's soft edge. */
    Colour("colour", ScanMarkTool.EraseColour),

    /** The colours at its edge carried inward, with the grain added back. */
    Edge("edge", ScanMarkTool.EraseEdge),
}

/** The fills Erase offers with [lab]'s testing options: the paper always, then Color and Follow the edges. */
internal fun scanEraseFills(lab: ScanLab): List<ScanEraseFill> = buildList {
    add(ScanEraseFill.Paper)
    if (lab.markupColours) add(ScanEraseFill.Colour)
    if (lab.markupEdge) add(ScanEraseFill.Edge)
}

/** Erase's Color swatches: paper white and black. On a black-and-white page they are the only colours it can be. */
internal val SCAN_ERASE_COLOURS: List<Int> = listOf(0xFFFFFF, 0x000000)

/** Whether a mark of [tool] (null for a text box) in [colour] is in a colour that is not one of its tool's own swatches (#503). */
internal fun scanMarkIsCustomColour(tool: ScanMarkTool?, colour: Int): Boolean {
    val own = when (tool) {
        null -> scanMarkupColours(ScanMarkupTool.Text)
        ScanMarkTool.Pen -> scanMarkupColours(ScanMarkupTool.Pen)
        ScanMarkTool.Highlighter -> scanMarkupColours(ScanMarkupTool.Highlighter)
        ScanMarkTool.EraseColour -> SCAN_ERASE_COLOURS
        ScanMarkTool.Erase, ScanMarkTool.EraseEdge -> return false
    }
    return (colour and 0xFFFFFF) !in own
}

/**
 * The swatches in the row of [tool] with [fill] (for Erase): its own, then [current] when it is
 * another colour (picked from the sheet or the page), so the colour in use is always shown. A
 * black-and-white page's eraser has black and paper only.
 */
internal fun scanMarkupSwatches(tool: ScanMarkupTool, fill: ScanEraseFill, current: Int, bilevel: Boolean): List<Int> {
    val own = when (tool) {
        ScanMarkupTool.Erase -> if (fill == ScanEraseFill.Colour) SCAN_ERASE_COLOURS else return emptyList()
        else -> scanMarkupColours(tool)
    }
    val rgb = current and 0xFFFFFF
    return if (rgb in own || (bilevel && tool == ScanMarkupTool.Erase)) own else own + rgb
}

/**
 * The mean colour (0xRRGGBB) of the disc of radius [radius] around ([cx], [cy]) of a picture
 * [width] × [height] whose pixels are [argb] (row by row); pixels off the picture are left out.
 * Null when the disc is wholly off it.
 */
internal fun scanDiscMean(argb: IntArray, width: Int, height: Int, cx: Float, cy: Float, radius: Float): Int? {
    if (width <= 0 || height <= 0 || argb.size < width * height || !cx.isFinite() || !cy.isFinite()) return null
    val r = radius.coerceAtLeast(0.5f)
    val x0 = kotlin.math.floor(cx - r).toInt().coerceAtLeast(0)
    val x1 = kotlin.math.ceil(cx + r).toInt().coerceAtMost(width - 1)
    val y0 = kotlin.math.floor(cy - r).toInt().coerceAtLeast(0)
    val y1 = kotlin.math.ceil(cy + r).toInt().coerceAtMost(height - 1)
    var n = 0L
    var sr = 0L
    var sg = 0L
    var sb = 0L
    for (y in y0..y1) {
        for (x in x0..x1) {
            val dx = x + 0.5f - cx
            val dy = y + 0.5f - cy
            if (dx * dx + dy * dy > r * r) continue
            val p = argb[y * width + x]
            sr += (p shr 16) and 0xFF
            sg += (p shr 8) and 0xFF
            sb += p and 0xFF
            n++
        }
    }
    if (n == 0L) return null
    fun avg(s: Long) = ((s + n / 2) / n).toInt()
    return (avg(sr) shl 16) or (avg(sg) shl 8) or avg(sb)
}

/** The eyedropper's disc on the made page: about a 250th of its longer side, at least a pixel and a half. */
internal fun scanDropperRadius(width: Int, height: Int): Float = (maxOf(width, height) / 250f).coerceAtLeast(1.5f)

/** The mean colour under the eyedropper at page pixel ([x], [y]) of [bitmap]; null off the page. */
internal fun scanDropperSample(bitmap: android.graphics.Bitmap, x: Float, y: Float): Int? {
    val w = bitmap.width
    val h = bitmap.height
    val r = scanDropperRadius(w, h)
    val x0 = kotlin.math.floor(x - r).toInt().coerceIn(0, w - 1)
    val y0 = kotlin.math.floor(y - r).toInt().coerceIn(0, h - 1)
    val x1 = kotlin.math.ceil(x + r).toInt().coerceIn(0, w - 1)
    val y1 = kotlin.math.ceil(y + r).toInt().coerceIn(0, h - 1)
    if (x < 0f || y < 0f || x > w || y > h) return null
    val bw = x1 - x0 + 1
    val bh = y1 - y0 + 1
    val px = IntArray(bw * bh)
    runCatching { bitmap.getPixels(px, 0, bw, x0, y0, bw, bh) }.getOrElse { return null }
    return scanDiscMean(px, bw, bh, x - x0, y - y0, r)
}

/**
 * "More colors" (#503): the viewer's colour sheet for a Markup tool: the recent colours, the
 * palettes (the standard swatches, the colour-blind-safe set and the person's own, with new, add,
 * export, import and delete) and a custom colour. One store with the viewer's pen: a palette made
 * in either is in both, and a colour picked here joins the recent ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ScanColourSheet(vm: AppViewModel, current: Int, onDismiss: () -> Unit, onPick: (Int) -> Unit) {
    val c = pdfColors
    val prefs = vm.settings.toolPrefs
    val argb = 0xFF000000.toInt() or current
    fun pick(to: Int) {
        prefs.rememberInkColor(0xFF000000.toInt() or to)
        onPick(to and 0xFFFFFF)
    }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        scrimColor = Color.Transparent,
        containerColor = c.surface,
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.scan_markup_more_colours), color = c.text, fontSize = 16.sp)
            if (prefs.recentInkColors.isNotEmpty()) {
                Text(stringResource(R.string.ink_recent_colors), color = c.label, fontSize = 12.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    prefs.recentInkColors.forEach { recent ->
                        val name = stringResource(R.string.ink_color_hex_name, "#%06X".format(recent and 0xFFFFFF))
                        val on = (recent and 0xFFFFFF) == current
                        Box(
                            Modifier.size(32.dp)
                                .border(if (on) 2.dp else 1.dp, if (on) c.accent else c.divider, RoundedCornerShape(50))
                                .padding(3.dp).background(Color(recent), RoundedCornerShape(50))
                                .selectable(selected = on, role = Role.RadioButton) { pick(recent) }
                                .semantics { contentDescription = name },
                        )
                    }
                }
            }
            InkPaletteSection(vm, argb) { pick(it) }
            var custom by remember { mutableStateOf(false) }
            TextButton(onClick = { custom = !custom }) {
                Text(stringResource(if (custom) R.string.ink_custom_color_hide else R.string.ink_custom_color), color = c.accent)
            }
            if (custom) HsvColorPicker(argb) { pick(it) }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The eyedropper's icon, drawn for PdfCraft in its icons' 24-unit, 2-unit-stroke style (#503). */
internal object ScanMarkupIcons {
    val Dropper: ImageVector by lazy {
        icon(
            "ScanDropper",
            // the bulb, the collar across the tube, the tube's two sides and its tip
            "M15 6 L18 3 A2.12 2.12 0 0 1 21 6 L18 9",
            "M12 5 L19 12",
            "M14 7 L5 16 L5 19 L8 19 L17 10",
            "M5 19 L3 21",
        )
    }

    private fun icon(name: String, vararg paths: String): ImageVector {
        val b = ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f)
        for (d in paths) {
            b.addPath(
                PathParser().parsePathString(d).toNodes(), fill = null, stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build()
    }
}
