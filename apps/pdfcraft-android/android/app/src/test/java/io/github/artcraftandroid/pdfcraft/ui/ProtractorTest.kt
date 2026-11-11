package io.github.artcraftandroid.pdfcraft.ui

import androidx.compose.ui.geometry.Offset
import io.github.artcraftandroid.pdfcraft.ui.viewer.Protractor
import io.github.artcraftandroid.pdfcraft.ui.viewer.ProtractorSnap
import io.github.artcraftandroid.pdfcraft.ui.viewer.protractorRayAngle
import io.github.artcraftandroid.pdfcraft.ui.viewer.sweptAngle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProtractorTest {
    private val pr = Protractor(Offset(500f, 500f), 0f, radius = 200f)

    private fun assertNear(e: Offset, a: Offset) {
        assertEquals(e.x, a.x, 0.01f)
        assertEquals(e.y, a.y, 0.01f)
    }

    @Test
    fun anglesAreMeasuredFromTheBaselineTowardsTheHalfDisc() {
        assertEquals(0f, pr.angleOf(Offset(600f, 500f)), 0.01f)
        assertEquals(90f, pr.angleOf(Offset(500f, 400f)), 0.01f)   // straight up on screen
        assertEquals(45f, pr.angleOf(Offset(600f, 400f)), 0.01f)
        assertEquals(180f, pr.angleOf(Offset(400f, 500f)), 0.01f)
        // turned a quarter clockwise, "up" is now towards +x
        assertEquals(90f, pr.copy(angleDeg = 90f).angleOf(Offset(600f, 500f)), 0.01f)
    }

    @Test
    fun theHalfDiscIsWhereFingersGrabIt() {
        assertTrue(pr.contains(Offset(500f, 350f)))
        assertFalse(pr.contains(Offset(500f, 550f)))       // below the baseline
        assertTrue(pr.contains(Offset(500f, 505f), slack = 10f))
        assertFalse(pr.contains(Offset(500f, 250f)))       // beyond the arc
    }

    @Test
    fun strokesSnapToTheArcOrToARayFromTheCentre() {
        assertEquals(ProtractorSnap.Ray, pr.snapAt(Offset(505f, 495f), 24f))
        assertEquals(ProtractorSnap.Arc, pr.snapAt(Offset(500f, 290f), 24f))
        assertEquals(ProtractorSnap.Arc, pr.snapAt(Offset(705f, 510f), 24f))   // at the baseline's end
        assertNull(pr.snapAt(Offset(500f, 400f), 24f))                         // inside, away from both
        assertNull(pr.snapAt(Offset(500f, 700f), 24f))                         // under the baseline
    }

    @Test
    fun arcPointsLieExactlyOnTheCircle() {
        for (p in listOf(Offset(520f, 290f), Offset(690f, 420f), Offset(320f, 450f))) {
            val q = pr.projectArc(p, outset = 3f)
            assertEquals(203f, (q - pr.center).getDistance(), 0.01f)
            assertEquals(pr.angleOf(p), pr.angleOf(q), 0.01f)
        }
    }

    @Test
    fun raysAreStraightAtWholeDegreesAndNeverBehindTheCentre() {
        val deg = protractorRayAngle(pr.angleOf(Offset(600f, 420f)))
        assertEquals(39f, deg, 0f)
        val a = pr.projectRay(Offset(600f, 420f), deg)
        val b = pr.projectRay(Offset(700f, 300f), deg)
        assertEquals(deg, pr.angleOf(a), 0.01f)
        assertEquals(deg, pr.angleOf(b), 0.01f)
        assertNear(pr.center, pr.projectRay(Offset(400f, 600f), deg))
    }

    @Test
    fun sweepStaysContinuousPastHalfATurn() {
        var prev = 0f
        // an arc drawn from 170° on through 180° to -170° (i.e. 190°)
        for (now in listOf(175f, 180f, -175f, -170f)) prev = sweptAngle(170f, now, prev)
        assertEquals(20f, prev, 0.01f)
        assertEquals(-30f, sweptAngle(10f, -20f, 0f), 0.01f)
    }

    @Test
    fun turningAboutTheCentreKeepsItInPlace() {
        val t = pr.transformed(Offset.Zero, 30f, pr.center)
        assertNear(pr.center, t.center)
        assertEquals(30f, t.angleDeg, 0.01f)
    }
}
