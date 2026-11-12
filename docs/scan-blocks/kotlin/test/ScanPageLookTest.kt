package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The per-page look settings: their text form, the old filters, and "Apply to all" (#301). */
class ScanPageLookTest {
    @Test
    fun encodeThenDecodeGivesTheSameLookForEveryLookAndEdgeValues() {
        val strengths = listOf(0, 1, 60, 100)
        val turns = listOf(Int.MIN_VALUE, -5, -1, 0, 1, 7, Int.MAX_VALUE)
        var checked = 0
        for (kind in ScanLookKind.entries) for (strength in strengths) for (paper in listOf(false, true))
            for (flatten in ScanFlattenMode.entries) for (turn in turns) for (upright in listOf(false, true)) {
                val look = ScanPageLook(kind, strength, paper, flatten, turn, upright)
                assertEquals(look, ScanPageLook.decode(look.encode()))
                assertFalse(look.encode().contains('\t') || look.encode().contains('\n'))
                checked += 1
            }
        assertEquals(6 * 4 * 2 * 3 * 7 * 2, checked)
    }

    @Test
    fun theTextFormIsTheOneDocumented() {
        val look = ScanPageLook(look = ScanLookKind.Colour, strength = 50, keepPaper = true, flatten = ScanFlattenMode.On, userTurns = -1, keepUpright = true)
        assertEquals("look=colour;strength=50;keep=1;flatten=on;turns=-1;upright=1", look.encode())
        assertEquals("look=auto;strength=60;keep=0;flatten=off;turns=0;upright=0", ScanPageLook().encode())
        assertEquals("look=bw;strength=60;keep=0;flatten=auto;turns=0;upright=0", ScanPageLook(look = ScanLookKind.BlackWhite, flatten = ScanFlattenMode.Auto).encode())
    }

    @Test
    fun rubbishDecodesToTheDefault() {
        assertEquals(ScanPageLook(), ScanPageLook.decode(null))
        assertEquals(ScanPageLook(), ScanPageLook.decode(""))
        assertEquals(ScanPageLook(), ScanPageLook.decode("nonsense"))
        assertEquals(ScanPageLook(), ScanPageLook.decode("look=moon;strength=abc;keep=yes;flatten=sometimes;turns=many;upright=maybe"))
        assertEquals(ScanPageLook(), ScanPageLook.decode(";;=;=1;look"))
    }

    @Test
    fun aMissingStrengthIsTheDefaultOfTheLookItIsWith() {
        assertEquals(ScanPageLook(look = ScanLookKind.Original, strength = 0), ScanPageLook.decode("look=original"))
        assertEquals(75, ScanPageLook.decode("look=bw").strength)
        assertEquals(85, ScanPageLook.decode("look=whiteboard").strength)
        assertEquals(ScanPageLook(), ScanPageLook.decode("keep=0"))
    }

    @Test
    fun aLaterBuildsFieldIsPassedOver() {
        assertEquals(ScanPageLook(look = ScanLookKind.Colour, strength = 30), ScanPageLook.decode("look=colour;strength=30;grain=9;future=x"))
    }

    @Test
    fun strengthIsKeptBetweenZeroAndAHundred() {
        assertEquals(100, ScanPageLook.decode("look=auto;strength=900").strength)
        assertEquals(0, ScanPageLook.decode("strength=-4").strength)
        assertEquals(100, ScanPageLook().withStrength(250).strength)
        assertEquals(0, ScanPageLook().withStrength(-1).strength)
        assertEquals(40, ScanPageLook().withStrength(40).strength)
        assertEquals("look=auto;strength=100;keep=0;flatten=off;turns=0;upright=0", ScanPageLook(strength = 250).encode())
    }

    @Test
    fun aNewLookTakesItsOwnDefaultStrengthAndKeepsTheRest() {
        assertEquals(0, ScanPageLook().with(ScanLookKind.Original).strength)
        assertEquals(50, ScanPageLook().with(ScanLookKind.Colour).strength)
        assertEquals(40, ScanPageLook().with(ScanLookKind.Greyscale).strength)
        assertEquals(75, ScanPageLook().with(ScanLookKind.BlackWhite).strength)
        assertEquals(85, ScanPageLook().with(ScanLookKind.Whiteboard).strength)
        val kept = ScanPageLook(keepPaper = true, flatten = ScanFlattenMode.On, userTurns = 2, keepUpright = true).with(ScanLookKind.Colour)
        assertEquals(ScanPageLook(look = ScanLookKind.Colour, strength = 50, keepPaper = true, flatten = ScanFlattenMode.On, userTurns = 2, keepUpright = true), kept)
    }

    @Test
    fun rotatingCountsTurnsAndKeepsThePageUpright() {
        val once = ScanPageLook().rotated(clockwise = true)
        assertEquals(1, once.userTurns)
        assertTrue(once.keepUpright)
        assertEquals(0, once.rotated(clockwise = false).userTurns)
        assertEquals(-1, ScanPageLook().rotated(clockwise = false).userTurns)
        var four = ScanPageLook()
        repeat(4) { four = four.rotated(clockwise = true) }
        assertEquals(4, four.userTurns)
        // the turns wrap at the top of an Int and stay a whole number of quarter turns
        assertEquals(Int.MIN_VALUE, ScanPageLook(userTurns = Int.MAX_VALUE).rotated(clockwise = true).userTurns)
        assertEquals(0, Int.MIN_VALUE % 4)
    }

    @Test
    fun theOldFilterNamesMapToTheLooksTheSpecGives() {
        assertEquals(ScanPageLook(look = ScanLookKind.Original, strength = 0), ScanPageLook.ofOldFilter("Original"))
        assertEquals(ScanPageLook(look = ScanLookKind.Auto, strength = 60), ScanPageLook.ofOldFilter("CleanScan"))
        assertEquals(ScanPageLook(look = ScanLookKind.Colour, strength = 50), ScanPageLook.ofOldFilter("AutoColor"))
        assertEquals(ScanPageLook(look = ScanLookKind.Greyscale, strength = 40), ScanPageLook.ofOldFilter("Grayscale"))
        assertEquals(ScanPageLook(look = ScanLookKind.Whiteboard, strength = 85), ScanPageLook.ofOldFilter("Whiteboard"))
        assertEquals(ScanPageLook(look = ScanLookKind.BlackWhite, strength = 75), ScanPageLook.ofOldFilter("LightText"))
        assertEquals(ScanPageLook(), ScanPageLook.ofOldFilter("Sepia"))
        assertEquals(ScanPageLook(), ScanPageLook.ofOldFilter(null))
    }

    @Test
    fun theNativeLookIsTheRustNameAndWhiteboardIsAskedForAsColour() {
        assertEquals(Pair("original", 0), ScanPageLook.nativeLook(ScanPageLook(look = ScanLookKind.Original, strength = 0)))
        assertEquals(Pair("auto", 60), ScanPageLook.nativeLook(ScanPageLook()))
        assertEquals(Pair("colour", 50), ScanPageLook.nativeLook(ScanPageLook(look = ScanLookKind.Colour, strength = 50)))
        assertEquals(Pair("greyscale", 40), ScanPageLook.nativeLook(ScanPageLook(look = ScanLookKind.Greyscale, strength = 40)))
        assertEquals(Pair("bw", 75), ScanPageLook.nativeLook(ScanPageLook(look = ScanLookKind.BlackWhite, strength = 75)))
        assertEquals(Pair("colour", 85), ScanPageLook.nativeLook(ScanPageLook(look = ScanLookKind.Whiteboard, strength = 85)))
    }

    @Test
    fun looksDifferIgnoresTheTurnsOfThePages() {
        assertFalse(looksDiffer(emptyList()))
        assertFalse(looksDiffer(listOf(ScanPageLook())))
        assertFalse(looksDiffer(listOf(ScanPageLook(), ScanPageLook(userTurns = 1, keepUpright = true))))
        assertTrue(looksDiffer(listOf(ScanPageLook(), ScanPageLook(strength = 61))))
        assertTrue(looksDiffer(listOf(ScanPageLook(), ScanPageLook(look = ScanLookKind.Colour, strength = 50))))
        assertTrue(looksDiffer(listOf(ScanPageLook(), ScanPageLook(flatten = ScanFlattenMode.On))))
    }

    @Test
    fun applyToAllCopiesTheLookButKeepsEachPagesTurns() {
        val pages = listOf(
            ScanPageLook(userTurns = 1, keepUpright = true),
            ScanPageLook(look = ScanLookKind.Greyscale, strength = 40, userTurns = -2),
        )
        val chosen = ScanPageLook(look = ScanLookKind.BlackWhite, strength = 75, keepPaper = true, flatten = ScanFlattenMode.On, userTurns = 9, keepUpright = false)
        val result = applyToAll(pages, chosen)
        assertEquals(
            listOf(
                ScanPageLook(look = ScanLookKind.BlackWhite, strength = 75, keepPaper = true, flatten = ScanFlattenMode.On, userTurns = 1, keepUpright = true),
                ScanPageLook(look = ScanLookKind.BlackWhite, strength = 75, keepPaper = true, flatten = ScanFlattenMode.On, userTurns = -2, keepUpright = false),
            ),
            result,
        )
        assertFalse(looksDiffer(result))
        assertEquals(emptyList<ScanPageLook>(), applyToAll(emptyList(), chosen))
    }
}
