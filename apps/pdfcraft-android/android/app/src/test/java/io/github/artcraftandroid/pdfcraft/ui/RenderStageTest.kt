package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.TileRect
import io.github.artcraftandroid.pdfcraft.ui.viewer.ImageKey
import io.github.artcraftandroid.pdfcraft.ui.viewer.ImageRequest
import io.github.artcraftandroid.pdfcraft.ui.viewer.renderStage
import io.github.artcraftandroid.pdfcraft.ui.viewer.slowRenderFields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RenderStageTest {
    private val tile = TileRect(0u, 0u, 256u, 256u)

    @Test fun pagesTilesAndLowResolutionPagesHaveTheirOwnStage() {
        assertEquals("page_render", renderStage(ImageRequest(ImageKey(0, 0, 0), 1f, null)))
        assertEquals("page_render_lowres", renderStage(ImageRequest(ImageKey(0, 0, -1), 0.5f, null)))
        assertEquals("tile_render", renderStage(ImageRequest(ImageKey(0, 0, 2, 3), 2f, tile)))
    }

    @Test fun prefetchesAreNamedApart() {
        assertEquals("page_render_prefetch", renderStage(ImageRequest(ImageKey(4, 0, 0), 1f, null, prefetch = true)))
        assertEquals("page_render_lowres_prefetch", renderStage(ImageRequest(ImageKey(4, 0, -1), 0.5f, null, prefetch = true)))
    }

    @Test fun aSlowRenderNamesItsPageFromOneWithItsKindSizeAndScale() {
        val fields = slowRenderFields(ImageRequest(ImageKey(411, 3, 0), 2.625f, null, prefetch = true), 1179, 1179, 566L).toMap()
        assertEquals("page_render_prefetch", fields["stage"])
        assertEquals(412, fields["page_number"])
        assertEquals(566L, fields["ms"])
        assertEquals(1179, fields["width"])
        assertEquals(1179, fields["height"])
        assertEquals(2625, fields["scale_milli"])
        assertEquals(0, fields["level"])
        assertEquals(false, fields["tile"])
        val tileFields = slowRenderFields(ImageRequest(ImageKey(0, 0, 2, 3), 4f, tile), 256, 256, 300L).toMap()
        assertEquals("tile_render", tileFields["stage"])
        assertEquals(1, tileFields["page_number"])
        assertEquals(true, tileFields["tile"])
    }

    @Test fun aSlowRenderLineHoldsOnlyNumbersAndFixedWords() {
        val fields = slowRenderFields(ImageRequest(ImageKey(7, 0, -1), 0.5f, null), 10, 20, 251L)
        for ((k, v) in fields) assertTrue(k, v is Number || v is Boolean || v == "page_render_lowres")
    }
}
