package io.github.artcraftandroid.pdfcraft.ui.scan

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/**
 * The camera's light icons (#457), drawn here for PdfCraft in the same 24-unit, 2-unit-stroke
 * style as its Lucide icons: a lightning bolt, and the same bolt struck through for "off".
 */
internal object ScanCameraIcons {
    private const val BOLT = "M13 2 L4.5 13.5 H11.5 L10.5 22 L19.5 10.5 H12.5 Z"

    val LightOn: ImageVector by lazy { icon("ScanLightOn", BOLT) }

    val LightOff: ImageVector by lazy { icon("ScanLightOff", BOLT, "M3 3 L21 21") }

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
