package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.TileRect
import io.github.artcraftandroid.pdfcraft.ui.viewer.ImageKey
import io.github.artcraftandroid.pdfcraft.ui.viewer.ImageRequest
import io.github.artcraftandroid.pdfcraft.ui.viewer.renderStage
import org.junit.Assert.assertEquals
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
}
