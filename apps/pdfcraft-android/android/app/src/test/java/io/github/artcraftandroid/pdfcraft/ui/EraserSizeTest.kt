package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.ERASER_RADIUS_DEFAULT
import io.github.artcraftandroid.pdfcraft.ui.viewer.ERASER_RADIUS_MAX
import io.github.artcraftandroid.pdfcraft.ui.viewer.ERASER_RADIUS_MIN
import io.github.artcraftandroid.pdfcraft.ui.viewer.inkEraseCommand
import io.github.artcraftandroid.pdfcraft.ui.viewer.normalizedEraserRadius
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class EraserSizeTest {
    @Test fun persistedAndUiRadiusStayInSafePageSpaceRange() {
        assertEquals(ERASER_RADIUS_DEFAULT, normalizedEraserRadius(Float.NaN), 0f)
        assertEquals(ERASER_RADIUS_DEFAULT, normalizedEraserRadius(Float.POSITIVE_INFINITY), 0f)
        assertEquals(ERASER_RADIUS_DEFAULT, normalizedEraserRadius(Float.NEGATIVE_INFINITY), 0f)
        assertEquals(ERASER_RADIUS_MIN, normalizedEraserRadius(-5f), 0f)
        assertEquals(ERASER_RADIUS_MAX, normalizedEraserRadius(1000f), 0f)
        assertEquals(11.5f, normalizedEraserRadius(11.5f), 0f)
    }

    @Test fun adjustableSizeReachesTheNativeErasingCommand() {
        val point = Offset(22f, 34f)
        for (radius in listOf(2.0, 6.5, 8.0, 16.0, 32.0)) {
            val command = inkEraseCommand(0, listOf(point), radius)
            assertEquals(radius, command["radius"])
            assertEquals(1, command["page"])
            assertEquals(true, command["erase_highlights"])
        }
    }

    @Test fun nativeCommandRejectsOutOfRangeOrMalformedRadius() {
        val points = listOf(Offset(1f, 2f))
        for (radius in listOf(Double.NaN, Double.POSITIVE_INFINITY, -10.0, 1.5, 33.0, 100.1)) {
            assertThrows(IllegalArgumentException::class.java) { inkEraseCommand(0, points, radius) }
        }
    }
}
