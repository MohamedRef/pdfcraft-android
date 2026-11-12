package io.github.artcraftandroid.pdfcraft.ui.viewer

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.artcraftandroid.pdfcraft.R
import io.github.artcraftandroid.pdfcraft.ui.AppViewModel
import io.github.artcraftandroid.pdfcraft.ui.LucideIcons
import io.github.artcraftandroid.pdfcraft.ui.OpenDocument
import io.github.artcraftandroid.pdfcraft.ui.PdfField
import io.github.artcraftandroid.pdfcraft.ui.PdfSheet
import io.github.artcraftandroid.pdfcraft.ui.SheetRow
import io.github.artcraftandroid.pdfcraft.ui.pdfColors
import kotlin.math.roundToInt

/** What the Measure tool measures (#135, #180), as the engine's tools name it. */
enum class MeasureMode(val tool: String, val kind: String, val minPoints: Int) {
    Distance("measure_distance", "distance", 2),
    Perimeter("measure_perimeter", "perimeter", 2),
    Area("measure_area", "area", 3),
}

/** The units a scale can be given in. */
internal val MEASURE_UNITS = listOf("mm", "cm", "m", "in", "ft")

/**
 * `measure_scale` arguments for a "1 : [ratio]" plan scale in [unit] on [page] (0-based): one
 * [unit] on paper is [ratio] of them in reality. Metres and feet are drawn in millimetres and
 * inches on the page.
 */
internal fun ratioScaleArgs(page: Int, ratio: Double, unit: String): Map<String, Any?>? {
    if (!ratio.isFinite() || ratio <= 0.0 || unit !in MEASURE_UNITS) return null
    val (pageUnit, perPageUnit) = when (unit) {
        "m" -> "mm" to ratio / 1000.0
        "ft" -> "in" to ratio / 12.0
        else -> unit to ratio
    }
    return mapOf("page" to page + 1, "page_distance" to 1.0, "page_unit" to pageUnit, "distance" to perPageUnit, "unit" to unit)
}

/** `measure_scale` arguments calibrating from two page points and their real [distance] in [unit]. */
internal fun calibrationScaleArgs(page: Int, a: Offset, b: Offset, distance: Double, unit: String): Map<String, Any?>? {
    if (!distance.isFinite() || distance <= 0.0 || unit !in MEASURE_UNITS || (a - b).getDistance() < 1f) return null
    return mapOf(
        "page" to page + 1, "points" to listOf(listOf(a.x.toDouble(), a.y.toDouble()), listOf(b.x.toDouble(), b.y.toDouble())),
        "distance" to distance, "unit" to unit,
    )
}

/** A typed positive number (a comma as the decimal point too), or null. */
internal fun parseMeasureNumber(raw: String): Double? = raw.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }

/** A measurement being placed: the mode, its page and points (page points), and the live reading. */
@Stable
class MeasureSession(val doc: OpenDocument) {
    var mode by mutableStateOf(MeasureMode.Distance)
    var page by mutableStateOf<Int?>(null)
    val points = mutableStateListOf<Offset>()
    var reading by mutableStateOf<String?>(null)

    /** Two taps that set the scale instead of measuring. */
    var calibrating by mutableStateOf(false)

    /** Calibration's two points, waiting for their real distance. */
    var calibration by mutableStateOf<Pair<Offset, Offset>?>(null)
    var scaleSheet by mutableStateOf(false)

    fun clear() {
        points.clear()
        page = null
        reading = null
    }
}

/** Measure: taps place points, snapped to the drawing's lines; a loupe shows what is under the finger. */
@Composable
fun MeasureLayer(vm: AppViewModel, doc: OpenDocument) {
    val m = vm.linksAndMeasure.measureSession(doc)
    Box(
        Modifier.fillMaxSize().toolGestures(
            doc,
            onDown = { p, _, _ -> doc.selection.magnifierAt = p },
            onMove = { p, _, _ -> doc.selection.magnifierAt = p },
            onUp = { p, _ ->
                doc.selection.magnifierAt = null
                val at = doc.viewer.screenToPage(p) ?: return@toolGestures
                vm.linksAndMeasure.measureTap(m, at)
            },
            onCancel = { doc.selection.magnifierAt = null },
        ),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val page = m.page ?: return@Canvas
            val pts = m.points.map { doc.viewer.pageToScreen(page, it.x, it.y) }
            val blue = Color(0xFF0078D6)
            if (pts.size >= 2) {
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    for (o in pts.drop(1)) lineTo(o.x, o.y)
                    if (m.mode == MeasureMode.Area && pts.size >= 3) close()
                }
                if (m.mode == MeasureMode.Area && pts.size >= 3) drawPath(path, blue.copy(alpha = 0.15f))
                drawPath(path, blue, style = Stroke(2.dp.toPx()))
            }
            for (o in pts) {
                drawCircle(Color.White, 5.dp.toPx(), o)
                drawCircle(blue, 5.dp.toPx(), o, style = Stroke(1.5.dp.toPx()))
            }
        }
        val reading = m.reading
        val last = m.points.lastOrNull()
        val page = m.page
        if (reading != null && last != null && page != null) {
            val o = doc.viewer.pageToScreen(page, last.x, last.y)
            Text(
                reading, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.offset { IntOffset((o.x + 12f).roundToInt(), (o.y - 40f).roundToInt()) }
                    .background(Color(0xE6202020), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
    }
    if (m.scaleSheet) ScaleSheet(vm, m)
    m.calibration?.let { CalibrationSheet(vm, m, it) }
}

/** Measure's bar: Distance · Perimeter · Area, then Undo point, Done, Scale and Export. */
@Composable
fun MeasureToolbar(vm: AppViewModel, doc: OpenDocument) {
    val m = vm.linksAndMeasure.measureSession(doc)
    SubToolbar {
        SubTool(LucideIcons.Ruler, stringResource(R.string.measure_distance), m.mode == MeasureMode.Distance && !m.calibrating, { vm.linksAndMeasure.setMeasureMode(m, MeasureMode.Distance) })
        SubTool(LucideIcons.Spline, stringResource(R.string.measure_perimeter), m.mode == MeasureMode.Perimeter && !m.calibrating, { vm.linksAndMeasure.setMeasureMode(m, MeasureMode.Perimeter) })
        SubTool(LucideIcons.Pentagon, stringResource(R.string.measure_area), m.mode == MeasureMode.Area && !m.calibrating, { vm.linksAndMeasure.setMeasureMode(m, MeasureMode.Area) })
        SubDivider()
        SubTool(LucideIcons.Undo2, stringResource(R.string.measure_undo_point), false, { vm.linksAndMeasure.undoMeasurePoint(m) }, enabled = m.points.isNotEmpty())
        SubTool(LucideIcons.Check, stringResource(R.string.measure_done), false, { vm.linksAndMeasure.finishMeasurement(m) }, enabled = m.points.size >= m.mode.minPoints && !m.calibrating)
        SubDivider()
        SubTool(LucideIcons.Settings2, stringResource(R.string.measure_scale), m.calibrating, { m.scaleSheet = true })
        SubTool(LucideIcons.FileSpreadsheet, stringResource(R.string.measure_export), false, { vm.linksAndMeasure.exportMeasurements(doc) })
    }
}

/** Set the page's scale: a plan ratio (1 : 50 in mm), or calibrate by tapping a known length. */
@Composable
private fun ScaleSheet(vm: AppViewModel, m: MeasureSession) {
    val c = pdfColors
    var ratio by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("mm") }
    var wrong by remember { mutableStateOf(false) }
    PdfSheet(onDismiss = { m.scaleSheet = false }, dim = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.measure_scale_title), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            Text(stringResource(R.string.measure_scale_ratio), color = c.textSecondary, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                Text("1 : ", color = c.text, fontSize = 16.sp)
                PdfField(ratio, { ratio = it; wrong = false }, "50", modifier = Modifier.weight(1f), error = wrong, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            }
            UnitChips(unit) { unit = it }
            if (wrong) Text(stringResource(R.string.measure_number_wrong), color = c.red, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = {
                    val n = parseMeasureNumber(ratio)
                    if (n == null) wrong = true else {
                        m.scaleSheet = false
                        vm.linksAndMeasure.setMeasureRatio(m, n, unit)
                    }
                }) { Text(stringResource(R.string.measure_scale_apply), color = c.accent, fontWeight = FontWeight.SemiBold) }
            }
        }
        SheetRow(LucideIcons.Ruler, stringResource(R.string.measure_calibrate), {
            m.scaleSheet = false
            vm.linksAndMeasure.startCalibration(m)
        })
        Spacer(Modifier.height(8.dp))
    }
}

/** Calibration's two points are placed: how long that is in reality. */
@Composable
private fun CalibrationSheet(vm: AppViewModel, m: MeasureSession, pts: Pair<Offset, Offset>) {
    val c = pdfColors
    var length by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("mm") }
    var wrong by remember { mutableStateOf(false) }
    PdfSheet(onDismiss = { m.calibration = null; m.clear() }, dim = true) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.measure_calibrate_title), color = c.text, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
            PdfField(length, { length = it; wrong = false }, stringResource(R.string.measure_calibrate_hint), error = wrong, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal))
            UnitChips(unit) { unit = it }
            if (wrong) Text(stringResource(R.string.measure_number_wrong), color = c.red, fontSize = 14.sp)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = {
                    val n = parseMeasureNumber(length)
                    if (n == null) wrong = true else vm.linksAndMeasure.finishCalibration(m, pts, n, unit)
                }) { Text(stringResource(R.string.measure_scale_apply), color = c.accent, fontWeight = FontWeight.SemiBold) }
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun UnitChips(unit: String, onPick: (String) -> Unit) {
    val c = pdfColors
    Row(Modifier.padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (u in MEASURE_UNITS) {
            val on = u == unit
            Text(
                u, color = if (on) c.accent else c.text, fontSize = 15.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                modifier = Modifier.background(if (on) c.pressed else Color.Transparent, RoundedCornerShape(16.dp))
                    .clickable(role = Role.RadioButton) { onPick(u) }.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
