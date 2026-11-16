package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.PressureRange
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.geom.Area
import java.awt.geom.Ellipse2D
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * Draws [PenSheet] with every pen into PNG files, for a pull request's before and after pictures
 * (#601). Skipped unless `-Dpdfcraft.penSheet=<folder>` names where to put them;
 * `-Dpdfcraft.penSheet.label=` is written on each picture.
 */
class PenSheetPictures {
    private val pxPerDp = 4f
    private val pens = listOf(InkStyle.Ballpoint, InkStyle.Fountain, InkStyle.Brush, InkStyle.Pencil)

    @Test fun drawTheSheet() {
        val folder = System.getProperty("pdfcraft.penSheet")
        assumeTrue(!folder.isNullOrBlank())
        val label = System.getProperty("pdfcraft.penSheet.label") ?: ""
        val out = File(folder!!).apply { mkdirs() }
        // the owner's pages (0.0.36 log): fit-width on the phone, and zoomed in
        for ((zoom, dpPerPt) in listOf("phone" to 0.61f, "zoomed" to 1.5f)) for (finger in listOf(false, true)) for (penWidth in listOf(2f, 13.8f)) {
            // a thick pen is shown on the zoomed-out page only, where its strokes still fit the sheet
            if (penWidth > 5f && dpPerPt > 1f) continue
            val name = "${if (finger) "finger" else "spen"}_${zoom}_${penWidth.toString().replace('.', '_')}pt"
            ImageIO.write(sheet(finger, dpPerPt, penWidth, "$label  ${if (finger) "finger" else "S Pen, light hand"}  pen $penWidth pt  page at $dpPerPt dp/pt"), "png", File(out, "$name.png"))
        }
    }

    private fun sheet(finger: Boolean, dpPerPt: Float, penWidth: Float, title: String): BufferedImage {
        val strokes = PenSheet.strokes(finger, dpPerPt)
        val rowDp = 70f
        val widthDp = 560f
        val img = BufferedImage((widthDp * pxPerDp).toInt(), ((rowDp * pens.size + 24f) * pxPerDp).toInt(), BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, img.width, img.height)
        g.color = Color(0x55, 0x55, 0x55)
        g.font = Font(Font.SANS_SERIF, Font.PLAIN, (9f * pxPerDp).toInt())
        g.drawString(title, 8f * pxPerDp, 14f * pxPerDp)
        val pxPerPt = pxPerDp * dpPerPt
        pens.forEachIndexed { row, style ->
            val top = (24f + row * rowDp) * pxPerDp
            g.color = Color(0x55, 0x55, 0x55)
            g.drawString(style.key, 8f * pxPerDp, top + 30f * pxPerDp)
            var left = 70f
            for ((_, stroke) in strokes) {
                val line = PenSheet.draw(style, stroke, finger, penWidth, dpPerPt, PressureRange(0.08f, 0.18f))
                val minX = stroke.minOf { it.p.x } * dpPerPt
                val maxX = stroke.maxOf { it.p.x } * dpPerPt
                val origin = Offset((left - minX) * pxPerDp, top + 14f * pxPerDp)
                g.color = Color(0x10, 0x20, 0x70)
                g.fill(outline(line.points.map { origin + it * pxPerPt }, line.widths.map { it * pxPerPt / 2f }))
                left += maxX - minX + 26f
            }
        }
        g.dispose()
        return img
    }

    /** The union of a disc at every point and a quad between neighbours: the line the app's ribbon outlines (#609), drawn the plain way. */
    private fun outline(pts: List<Offset>, radii: List<Float>): Area {
        val path = Path2D.Float(Path2D.WIND_NON_ZERO)
        val area = Area()
        for (i in pts.indices) {
            val r = radii[i]
            area.add(Area(Ellipse2D.Float(pts[i].x - r, pts[i].y - r, r * 2f, r * 2f)))
            if (i == 0) continue
            val a = pts[i - 1]
            val b = pts[i]
            val d = b - a
            val len = d.getDistance()
            if (!(len > 1e-4f)) continue
            val n = Offset(-d.y / len, d.x / len)
            path.reset()
            path.moveTo(a.x + n.x * radii[i - 1], a.y + n.y * radii[i - 1])
            path.lineTo(b.x + n.x * r, b.y + n.y * r)
            path.lineTo(b.x - n.x * r, b.y - n.y * r)
            path.lineTo(a.x - n.x * radii[i - 1], a.y - n.y * radii[i - 1])
            path.closePath()
            area.add(Area(path))
        }
        return area
    }
}
