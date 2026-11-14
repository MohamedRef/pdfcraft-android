package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The stroke weight PdfCraft draws Lucide's 24-unit icons with on phones: a little lighter than
 * Lucide's 2, for the thin outline look of a phone's tool bars.
 */
const val ICON_STROKE = 1.6f

/** A Lucide icon from its SVG path strings (see `LucideIconsTask` in app/build.gradle.kts). */
internal fun lucide(name: String, vararg paths: String): ImageVector {
    // Navigation follows layout direction; physical-direction tools (rotation, alignment, drawing) do not.
    val mirrorInRtl = name in setOf("arrow-left", "arrow-right", "chevron-left", "chevron-right")
    val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f, autoMirror = mirrorInRtl)
    for (d in paths) {
        b.addPath(
            pathData = PathParser().parsePathString(d).toNodes(),
            fill = null,
            stroke = SolidColor(Color.Black),
            strokeLineWidth = ICON_STROKE,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
        )
    }
    return b.build()
}

/**
 * PdfCraft's own icons, drawn in Lucide's style (24-unit grid, strokes only) where Lucide has none
 * that fits, so that no two tools in the viewer's bars share an icon (#526).
 */
object PdfIcons {
    /** Squiggly underline: a letter with a wave under it, beside Underline's and Strikethrough's letters. */
    val SquigglyUnderline: ImageVector by lazy {
        lucide("squiggly-underline", "M6 3v6a6 6 0 0 0 12 0V3", "M3 20c1.5-2 3-2 4.5 0s3 2 4.5 0s3-2 4.5 0s3 2 4.5 0")
    }

    /** Draw's straight line: a slanted stroke (Fill & Sign's line mark is the level one, Lucide's minus). */
    val StraightLine: ImageVector by lazy { lucide("straight-line", "M5 19L19 5") }

    /** Measure's distance: a dimension line between two end marks (Draw's Ruler is Lucide's ruler). */
    val Distance: ImageVector by lazy { lucide("measure-distance", "M3 12h18", "M3 8v8", "M21 8v8") }

    /** Measure's perimeter: a pentagon's corners, its outline traced (Draw's connected lines are Lucide's spline). */
    val Perimeter: ImageVector by lazy {
        lucide(
            "measure-perimeter",
            "M9.12 5.08L12 3L14.88 5.08", "M18.12 7.42L21 9.5L20.04 13.02", "M18.96 16.98L18 20.5L14.16 20.5",
            "M9.84 20.5L6 20.5L5.04 16.98", "M3.96 13.02L3 9.5L5.88 7.42",
        )
    }

    /** Measure's area: a hatched pentagon (Draw's polygon is Lucide's plain pentagon). */
    val Area: ImageVector by lazy { lucide("measure-area", "M12 3L21 9.5L18 20.5H6L3 9.5Z", "M5 13l4-4", "M7.5 17.5l6-6", "M11 20l6.5-6.5") }
}
