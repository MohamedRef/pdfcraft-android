package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.engine.Diag
import io.github.artcraftandroid.pdfcraft.engine.DiagSink
import io.github.artcraftandroid.pdfcraft.ui.viewer.GestureDiag
import io.github.artcraftandroid.pdfcraft.ui.viewer.GestureRole
import io.github.artcraftandroid.pdfcraft.ui.viewer.GestureRoute
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkAppearance
import io.github.artcraftandroid.pdfcraft.ui.viewer.InkStroke
import io.github.artcraftandroid.pdfcraft.ui.viewer.LassoDragDiag
import io.github.artcraftandroid.pdfcraft.ui.viewer.ToolPointer
import io.github.artcraftandroid.pdfcraft.ui.viewer.logEraseGesture
import io.github.artcraftandroid.pdfcraft.ui.viewer.logStrokeClip
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** The drawing and eraser lines of the diagnostics log (#74 #75 #76 #81 #84 #85). */
class InkDiagnosticsTest {
    private lateinit var dir: File
    private lateinit var sink: DiagSink

    @Before fun on() {
        dir = Files.createTempDirectory("inkdiag").toFile()
        sink = DiagSink(dir)
        Diag.sink = sink
    }

    @After fun off() {
        Diag.sink = null
        dir.deleteRecursively()
    }

    private fun lines() = sink.lines().map { it.substringAfter("] ") }

    @Test fun aFingerStrokeTakenOverByThePenIsOneGestureLine() {
        val route = GestureRoute(slop = 8f)
        route.start(ToolPointer.Touch, Offset.Zero)
        val diag = GestureDiag(startMs = 100, pointer = ToolPointer.Touch, startRole = route.role)
        diag.fingerEvents = 2
        diag.fingerSamples = 7
        diag.penArrived = true
        route.penArrives(ToolPointer.Stylus, Offset(5f, 5f), buttonHeld = false)
        route.motion(Offset(50f, 5f))
        diag.log(route, touches = 1, endMs = 340, cancelled = false)
        assertEquals(
            listOf(
                "gesture pointer=touch start_role=draw role=draw mode=ink pen_arrived=true moved=true touches=1 " +
                    "finger_events=2 finger_samples=7 ms=240 cancelled=false",
            ),
            lines(),
        )
    }

    @Test fun aSecondFingerShowsAsNavigation() {
        val route = GestureRoute(slop = 8f)
        route.start(ToolPointer.Touch, Offset.Zero)
        val diag = GestureDiag(0, ToolPointer.Touch, route.role)
        route.touches(2)
        diag.log(route, touches = 2, endMs = 50, cancelled = false)
        val line = lines().single()
        assertTrue(line, "start_role=draw role=navigate" in line && "touches=2" in line)
        assertEquals(GestureRole.Navigate, route.role)
    }

    @Test fun eraseClipAndLassoLinesHoldOnlyCounts() {
        logEraseGesture(points = 5000, earlyChunks = 1, radiusPt = 12f)
        val piece = InkStroke(0, InkAppearance(0xFF000000.toInt(), 1f, 2f, null)).apply {
            points.addAll(listOf(Offset(1f, 1f), Offset(2f, 2f)))
        }
        logStrokeClip(pointsIn = 9, pieces = listOf(piece))
        logStrokeClip(pointsIn = 4, pieces = emptyList())
        val drag = LassoDragDiag()
        drag.add(1_500_000)
        drag.add(500_000)
        drag.end(strokes = 3, saved = 1, moved = true)
        drag.end(strokes = 3, saved = 1, moved = true) // nothing timed since: no line
        assertEquals(
            listOf(
                "erase points=5000 early_chunks=1 radius_pt=12.00",
                "stroke_clip points_in=9 pieces=1 points_out=2",
                "stroke_clip points_in=4 pieces=0 points_out=0",
                "lasso_drag moves=2 total_us=2000 max_us=1500 strokes=3 saved=1 moved=true",
            ),
            lines(),
        )
    }
}
