package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStyle
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkPreviewIsPressureOutline
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkSignedArea
import io.github.artcraftandroid.pdfcraft.ui.viewer.pressureInkRadii
import io.github.artcraftandroid.pdfcraft.ui.viewer.pressureInkSegmentQuads
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Differential test for #129: the draft preview's pressure outline must be the same geometry the
 * engine saves (`pdfcraft_annot::appearance::pressure_ink_outline`). The expected contours were
 * produced by that Rust function for this fixture (`/PCWidths` = widths, `/BS /W` = 1) and are
 * rounded to the engine's three decimals.
 */
class InkPressureOutlineTest {
    private val points = listOf(Offset(10f, 10f), Offset(20f, 14f), Offset(32f, 12f), Offset(40f, 25f), Offset(41f, 40f))
    private val widths = listOf(1f, 2f, 3f, 1.5f, 0.8f)

    /** Each disc's first point (x + r) as the engine writes it. */
    private val engineDiscStartX = floatArrayOf(10.5f, 21f, 33.5f, 40.75f, 41.4f)

    /** Each quad of the engine outline, in order: x0, y0, ... x3, y3. */
    private val engineQuads = listOf(
        floatArrayOf(9.791f, 10.454f, 10.209f, 9.546f, 10.993f, 9.839f, 10.522f, 10.86f),
        floatArrayOf(10.501f, 10.85f, 11.015f, 9.849f, 12.036f, 10.303f, 11.464f, 11.415f),
        floatArrayOf(11.462f, 11.414f, 12.038f, 10.304f, 13.246f, 10.86f, 12.613f, 12.081f),
        floatArrayOf(12.624f, 12.087f, 13.235f, 10.855f, 14.583f, 11.453f, 13.917f, 12.797f),
        floatArrayOf(13.941f, 12.809f, 14.559f, 11.441f, 15.999f, 12.023f, 15.33f, 13.504f),
        floatArrayOf(15.371f, 13.522f, 15.957f, 12.006f, 17.44f, 12.512f, 16.81f, 14.144f),
        floatArrayOf(16.877f, 14.167f, 17.373f, 12.489f, 18.852f, 12.861f, 18.32f, 14.659f),
        floatArrayOf(18.429f, 14.684f, 18.743f, 12.836f, 20.167f, 13.014f, 19.833f, 14.986f),
        floatArrayOf(20.046f, 14.999f, 19.954f, 13.001f, 21.383f, 12.873f, 21.48f, 14.996f),
        floatArrayOf(21.679f, 14.968f, 21.184f, 12.901f, 22.691f, 12.476f, 23.215f, 14.664f),
        floatArrayOf(23.318f, 14.635f, 22.588f, 12.506f, 24.144f, 11.907f, 24.914f, 14.154f),
        floatArrayOf(24.943f, 14.143f, 24.116f, 11.917f, 25.69f, 11.266f, 26.56f, 13.609f),
        floatArrayOf(26.517f, 13.624f, 25.733f, 11.251f, 27.293f, 10.669f, 28.117f, 13.161f),
        floatArrayOf(27.981f, 13.198f, 27.429f, 10.632f, 28.945f, 10.242f, 29.524f, 12.93f),
        floatArrayOf(29.246f, 12.961f, 29.222f, 10.211f, 30.665f, 10.136f, 30.69f, 13.011f),
        floatArrayOf(30.236f, 12.941f, 31.119f, 10.205f, 32.461f, 10.573f, 31.539f, 13.427f),
        floatArrayOf(31.123f, 13.217f, 32.877f, 10.783f, 34.062f, 11.753f, 32.417f, 14.034f),
        floatArrayOf(32.225f, 13.868f, 34.253f, 11.919f, 35.392f, 13.239f, 33.499f, 15.058f),
        floatArrayOf(33.395f, 14.936f, 35.495f, 13.361f, 36.576f, 14.957f, 34.626f, 16.42f),
        floatArrayOf(34.565f, 16.332f, 36.636f, 15.045f, 37.643f, 16.844f, 35.732f, 18.031f),
        floatArrayOf(35.694f, 17.966f, 37.681f, 16.909f, 38.599f, 18.835f, 36.778f, 19.804f),
        floatArrayOf(36.753f, 19.753f, 38.624f, 18.886f, 39.437f, 20.864f, 37.735f, 21.652f),
        floatArrayOf(37.717f, 21.609f, 39.455f, 20.906f, 40.144f, 22.86f, 38.58f, 23.493f),
        floatArrayOf(38.566f, 23.455f, 40.159f, 22.898f, 40.708f, 24.752f, 39.292f, 25.248f),
        floatArrayOf(39.272f, 25.181f, 40.728f, 24.819f, 41.152f, 26.712f, 39.781f, 27.052f),
        floatArrayOf(39.768f, 26.982f, 41.166f, 26.781f, 41.421f, 28.867f, 40.11f, 29.055f),
        floatArrayOf(40.105f, 29.011f, 41.426f, 28.911f, 41.549f, 31.091f, 40.315f, 31.185f),
        floatArrayOf(40.313f, 31.157f, 41.55f, 31.118f, 41.575f, 33.294f, 40.425f, 33.331f),
        floatArrayOf(40.425f, 33.314f, 41.575f, 33.311f, 41.537f, 35.384f, 40.475f, 35.387f),
        floatArrayOf(40.475f, 35.38f, 41.537f, 35.392f, 41.472f, 37.263f, 40.497f, 37.252f),
        floatArrayOf(40.497f, 37.254f, 41.472f, 37.262f, 41.414f, 38.833f, 40.527f, 38.825f),
        floatArrayOf(40.527f, 38.84f, 41.414f, 38.818f, 41.4f, 39.99f, 40.6f, 40.01f),
    )

    @Test
    fun previewOutlineMatchesTheEngineOutline() {
        val radii = pressureInkRadii(widths)
        points.indices.forEach { i -> assertEquals(engineDiscStartX[i], points[i].x + radii[i], 0.002f) }
        val quads = (0 until points.lastIndex).flatMap { pressureInkSegmentQuads(points, radii, it) }
        assertEquals(engineQuads.size, quads.size)
        quads.forEachIndexed { i, quad ->
            quad.forEachIndexed { j, p ->
                assertEquals("quad $i x$j", engineQuads[i][2 * j], p.x, 0.002f)
                assertEquals("quad $i y$j", engineQuads[i][2 * j + 1], p.y, 0.002f)
            }
            assertTrue(inkSignedArea(quad) >= 0f)
        }
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
