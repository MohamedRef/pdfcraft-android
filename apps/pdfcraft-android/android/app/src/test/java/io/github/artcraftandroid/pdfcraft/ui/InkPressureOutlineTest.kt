package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkPreviewIsPressureOutline
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkSignedArea
import io.github.artcraftandroid.pdfcraft.ui.viewer.pressureInkRadii
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkOutlineBuilder
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkOutlineSink
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Differential test for #129 and #609: the draft preview's pressure outline must be the same
 * geometry the engine saves (`pdfcraft_annot::appearance::ink_samples` and `ink_pieces`). The
 * expected quadrilaterals and discs were produced by those Rust functions for this fixture (a
 * gentle line, a sharp turn back, a point given twice) and are rounded to three decimals.
 */
class InkPressureOutlineTest {
    private val points = listOf(Offset(10f, 10f), Offset(20f, 14f), Offset(32f, 12f), Offset(40f, 25f), Offset(41f, 40f), Offset(41.2f, 39f), Offset(41.2f, 39f), Offset(43f, 30f))
    private val radii = pressureInkRadii(listOf(1f, 2f, 3f, 1.5f, 0.8f, 1.2f, 1.2f, 2f))

    /** A quad of the engine's outline: its segment, whether it shares its ends, x0, y0, … x3, y3. */
    private class Q(val seg: Int, val shared: Boolean, vararg val at: Float)

    private val engineQuads = listOf(
        Q(0, true, 9.793f, 10.455f, 10.164f, 9.528f, 11.008f, 9.846f, 10.507f, 10.853f),
        Q(0, true, 10.507f, 10.853f, 11.008f, 9.846f, 12.039f, 10.305f, 11.461f, 11.414f),
        Q(0, true, 11.461f, 11.414f, 12.039f, 10.305f, 13.242f, 10.858f, 12.617f, 12.083f),
        Q(0, true, 12.617f, 12.083f, 13.242f, 10.858f, 14.573f, 11.448f, 13.927f, 12.802f),
        Q(0, true, 13.927f, 12.802f, 14.573f, 11.448f, 15.981f, 12.015f, 15.348f, 13.512f),
        Q(0, true, 15.348f, 13.512f, 15.981f, 12.015f, 17.411f, 12.501f, 16.839f, 14.155f),
        Q(0, true, 16.839f, 14.155f, 17.411f, 12.501f, 18.804f, 12.848f, 18.368f, 14.672f),
        Q(0, true, 18.368f, 14.672f, 18.804f, 12.848f, 20.045f, 13.001f, 19.864f, 14.991f),
        Q(1, true, 19.864f, 14.991f, 20.045f, 13.001f, 21.265f, 12.885f, 21.598f, 14.984f),
        Q(1, true, 21.598f, 14.984f, 21.265f, 12.885f, 22.626f, 12.494f, 23.280f, 14.647f),
        Q(1, true, 23.280f, 14.647f, 22.626f, 12.494f, 24.118f, 11.916f, 24.940f, 14.144f),
        Q(1, true, 24.940f, 14.144f, 24.118f, 11.916f, 25.699f, 11.262f, 26.551f, 13.613f),
        Q(1, true, 26.551f, 13.613f, 25.699f, 11.262f, 27.344f, 10.653f, 28.066f, 13.177f),
        Q(1, true, 28.066f, 13.177f, 27.344f, 10.653f, 29.059f, 10.222f, 29.409f, 12.950f),
        Q(1, true, 29.409f, 12.950f, 29.059f, 10.222f, 30.869f, 10.149f, 30.486f, 12.998f),
        Q(1, true, 30.486f, 12.998f, 30.869f, 10.149f, 32.737f, 10.694f, 31.292f, 13.322f),
        Q(2, true, 31.292f, 13.322f, 32.737f, 10.694f, 34.172f, 11.841f, 32.306f, 13.946f),
        Q(2, true, 32.306f, 13.946f, 34.172f, 11.841f, 35.451f, 13.305f, 33.440f, 14.992f),
        Q(2, true, 33.440f, 14.992f, 35.451f, 13.305f, 36.609f, 15.004f, 34.592f, 16.373f),
        Q(2, true, 34.592f, 16.373f, 36.609f, 15.004f, 37.664f, 16.878f, 35.711f, 17.997f),
        Q(2, true, 35.711f, 17.997f, 37.664f, 16.878f, 38.613f, 18.862f, 36.764f, 19.777f),
        Q(2, true, 36.764f, 19.777f, 38.613f, 18.862f, 39.446f, 20.886f, 37.725f, 21.630f),
        Q(2, true, 37.725f, 21.630f, 39.446f, 20.886f, 40.152f, 22.879f, 38.573f, 23.474f),
        Q(2, true, 38.573f, 23.474f, 40.152f, 22.879f, 40.722f, 24.797f, 39.295f, 25.256f),
        Q(3, true, 39.295f, 25.256f, 40.722f, 24.797f, 41.160f, 26.759f, 39.771f, 27.018f),
        Q(3, true, 39.771f, 27.018f, 41.160f, 26.759f, 41.420f, 28.917f, 40.102f, 29.052f),
        Q(3, true, 40.102f, 29.052f, 41.420f, 28.917f, 41.541f, 31.152f, 40.305f, 31.212f),
        Q(3, true, 40.305f, 31.212f, 41.541f, 31.152f, 41.562f, 33.368f, 40.413f, 33.382f),
        Q(3, true, 40.413f, 33.382f, 41.562f, 33.368f, 41.522f, 35.463f, 40.460f, 35.455f),
        Q(3, true, 40.460f, 35.455f, 41.522f, 35.463f, 41.458f, 37.334f, 40.483f, 37.322f),
        Q(3, true, 40.483f, 37.322f, 41.458f, 37.334f, 41.405f, 38.874f, 40.517f, 38.880f),
        Q(3, true, 40.517f, 38.880f, 41.405f, 38.874f, 41.399f, 39.970f, 40.602f, 40.038f),
        Q(4, true, 40.602f, 40.038f, 41.399f, 39.970f, 41.486f, 40.577f, 40.643f, 40.690f),
        Q(4, false, 40.649f, 40.722f, 41.480f, 40.545f, 41.551f, 40.758f, 40.671f, 40.945f),
        Q(4, false, 41.540f, 40.988f, 40.682f, 40.715f, 40.690f, 40.609f, 41.595f, 40.897f),
        Q(4, true, 41.614f, 40.806f, 40.670f, 40.700f, 40.663f, 40.418f, 41.662f, 40.457f),
        Q(4, true, 41.662f, 40.457f, 40.663f, 40.418f, 40.650f, 39.994f, 41.700f, 40.016f),
        Q(4, true, 41.700f, 40.016f, 40.650f, 39.994f, 40.633f, 39.546f, 41.733f, 39.564f),
        Q(4, true, 41.733f, 39.564f, 40.633f, 39.546f, 40.615f, 39.171f, 41.765f, 39.202f),
        Q(4, false, 41.764f, 39.217f, 40.616f, 39.156f, 40.601f, 38.968f, 41.799f, 39.032f),
        Q(5, false, 40.612f, 38.882f, 41.788f, 39.118f, 41.786f, 39.131f, 40.609f, 38.896f),
        Q(5, true, 40.609f, 38.896f, 41.786f, 39.131f, 41.760f, 39.258f, 40.584f, 39.023f),
        Q(5, true, 40.584f, 39.023f, 41.760f, 39.258f, 41.724f, 39.440f, 40.547f, 39.205f),
        Q(5, true, 40.547f, 39.205f, 41.724f, 39.440f, 41.688f, 39.618f, 40.512f, 39.382f),
        Q(5, true, 40.512f, 39.382f, 41.688f, 39.618f, 41.665f, 39.733f, 40.489f, 39.498f),
        Q(5, false, 41.665f, 39.733f, 40.489f, 39.498f, 40.490f, 39.492f, 41.666f, 39.727f),
        Q(5, true, 41.666f, 39.727f, 40.490f, 39.492f, 40.527f, 39.306f, 41.704f, 39.541f),
        Q(5, true, 41.704f, 39.541f, 40.527f, 39.306f, 40.607f, 38.908f, 41.783f, 39.143f),
        Q(6, true, 41.783f, 39.143f, 40.607f, 38.908f, 40.714f, 38.117f, 41.989f, 38.372f),
        Q(6, true, 41.989f, 38.372f, 40.714f, 38.117f, 40.879f, 37.035f, 42.252f, 37.309f),
        Q(6, true, 42.252f, 37.309f, 40.879f, 37.035f, 41.087f, 35.742f, 42.558f, 36.036f),
        Q(6, true, 42.558f, 36.036f, 41.087f, 35.742f, 41.316f, 34.343f, 42.884f, 34.657f),
        Q(6, true, 42.884f, 34.657f, 41.316f, 34.343f, 41.544f, 32.945f, 43.211f, 33.278f),
        Q(6, true, 43.211f, 33.278f, 41.544f, 32.945f, 41.752f, 31.652f, 43.517f, 32.005f),
        Q(6, true, 43.517f, 32.005f, 41.752f, 31.652f, 41.917f, 30.570f, 43.780f, 30.942f),
        Q(6, true, 43.780f, 30.942f, 41.917f, 30.570f, 42.012f, 29.847f, 43.971f, 30.239f),
    )

    /** The engine's discs, in order: x, y, radius. */
    private val engineDiscs = listOf(
        floatArrayOf(10.000f, 10.000f, 0.500f),
        floatArrayOf(41.065f, 40.634f, 0.425f),
        floatArrayOf(41.111f, 40.852f, 0.450f),
        floatArrayOf(41.142f, 40.753f, 0.475f),
        floatArrayOf(41.190f, 39.187f, 0.575f),
        floatArrayOf(41.200f, 39.000f, 0.600f),
        floatArrayOf(41.197f, 39.014f, 0.600f),
        floatArrayOf(41.077f, 39.615f, 0.600f),
        floatArrayOf(41.078f, 39.609f, 0.600f),
        floatArrayOf(43.000f, 30.000f, 1.000f),
    )

    private class Pieces : InkOutlineSink {
        val discs = ArrayList<Pair<Offset, Float>>()
        val polygons = ArrayList<List<Offset>>()
        override fun disc(center: Offset, radius: Float) { discs += center to radius }
        override fun polygon(corners: List<Offset>) { polygons += corners }
    }

    @Test
    fun previewOutlineMatchesTheEngineOutline() {
        val quads = (0 until points.lastIndex).flatMap { i -> inkSegment({ points[it] }, { radii[it] }, i, points.lastIndex).quads.filterNotNull().map { i to it } }
        assertEquals(engineQuads.size, quads.size)
        quads.forEachIndexed { i, (seg, quad) ->
            assertEquals("quad $i", engineQuads[i].seg, seg)
            assertEquals("quad $i", engineQuads[i].shared, quad.shared)
            quad.corners.forEachIndexed { j, p ->
                assertEquals("quad $i x$j", engineQuads[i].at[2 * j], p.x, 0.003f)
                assertEquals("quad $i y$j", engineQuads[i].at[2 * j + 1], p.y, 0.003f)
            }
            assertTrue(inkSignedArea(quad.corners) > 0f)
        }
        // the whole outline: the engine's discs, and ribbons that are those quads joined
        val all = Pieces()
        InkOutlineBuilder().update(points.size, points.size, points.size, closed = true, point = { points[it] }, radius = { radii[it] }, segmentLevel = { 0 }, loneLevel = { 0 }, settledSink = { all }, liveSink = { all })
        assertEquals(engineDiscs.size, all.discs.size)
        all.discs.forEachIndexed { i, (c, r) ->
            assertEquals("disc $i", engineDiscs[i][0], c.x, 0.003f)
            assertEquals("disc $i", engineDiscs[i][1], c.y, 0.003f)
            assertEquals("disc $i", engineDiscs[i][2], r, 0.003f)
        }
        // each quad adds two corners to a ribbon, each ribbon starts with two more
        assertEquals(2 * quads.size + 2 * all.polygons.size, all.polygons.sumOf { it.size })
        assertTrue(all.polygons.all { inkSignedArea(it) > 0f })
        val corners = quads.flatMap { it.second.corners }.toSet()
        assertTrue(all.polygons.flatten().all { it in corners })
    }

    @Test
    fun onlyPressurePenStrokesUseTheOutline() {
        assertTrue(inkPreviewIsPressureOutline(true, InkStyle.Fountain, 5, 5, shapePreview = false))
        assertFalse(inkPreviewIsPressureOutline(false, InkStyle.Fountain, 5, 5, shapePreview = false))
        assertFalse(inkPreviewIsPressureOutline(true, InkStyle.Highlighter, 5, 5, shapePreview = false))
        assertFalse(inkPreviewIsPressureOutline(true, InkStyle.Ballpoint, 5, 5, shapePreview = true))
        assertFalse(inkPreviewIsPressureOutline(true, InkStyle.Ballpoint, 1, 1, shapePreview = false))
        assertFalse(inkPreviewIsPressureOutline(true, InkStyle.Ballpoint, 5, 4, shapePreview = false))
    }
}
