package io.github.artcraftandroid.pdfcraft.ui.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** The per-page looks kept as session lines, and read back from sessions of any age (#301). */
class ScanSessionLooksTest {
    /** The page lines of an older build's session, which carry no looks. */
    private val pageLines: String =
        "pdfcraft-scan 1\nshot\t\nretake\t\nimage\t0\nname\tScan\n" +
            "page\t/files/p0.jpg\t0.1,0.1,0.9,0.1,0.9,0.9,0.1,0.9\t\n" +
            "page\t/files/p1.jpg\t0.1,0.1,0.9,0.1,0.9,0.9,0.1,0.9\t0.2,0.2,0.8,0.2,0.8,0.8,0.2,0.8\n"

    @Test
    fun looksSurviveARoundTripWithEveryField() {
        val looks = listOf(
            ScanPageLook(),
            ScanPageLook(look = ScanLookKind.Original, strength = 0),
            ScanPageLook(look = ScanLookKind.Whiteboard, strength = 85, keepPaper = true, flatten = ScanFlattenMode.On, userTurns = -3, keepUpright = true),
        )
        val text = encodeLooks(looks)
        assertEquals(3, text.lines().filter { it.isNotEmpty() }.size)
        assertEquals(looks, decodeLooks(text, looks.size))
    }

    @Test
    fun theLineFormIsTheOneDocumented() {
        assertEquals(
            "look\t0\tlook=colour;strength=50;keep=1;flatten=off;turns=0;upright=0\n",
            encodeLooks(listOf(ScanPageLook(look = ScanLookKind.Colour, strength = 50, keepPaper = true))),
        )
        assertEquals("", encodeLooks(emptyList()))
    }

    @Test
    fun everyLineHasThreeColumnsAndNoValueHoldsATabOrNewline() {
        val looks = listOf(
            ScanPageLook(userTurns = Int.MIN_VALUE, keepUpright = true),
            ScanPageLook(look = ScanLookKind.BlackWhite, strength = 100, flatten = ScanFlattenMode.Auto),
            ScanPageLook(look = ScanLookKind.Greyscale, strength = 0, userTurns = Int.MAX_VALUE),
        )
        val text = encodeLooks(looks)
        for (line in text.lines().filter { it.isNotEmpty() }) {
            assertEquals(3, line.split('\t').size)
        }
        for (look in looks) {
            assertFalse(look.encode().contains('\t'))
            assertFalse(look.encode().contains('\n'))
            assertFalse(look.encode().contains('\r'))
        }
        assertEquals(looks, decodeLooks(text, looks.size))
    }

    @Test
    fun aSessionWithOtherLinesStillGivesItsLooks() {
        val text = pageLines + encodeLooks(listOf(ScanPageLook(look = ScanLookKind.Colour, strength = 50), ScanPageLook(look = ScanLookKind.BlackWhite, strength = 75)))
        assertEquals(
            listOf(ScanPageLook(look = ScanLookKind.Colour, strength = 50), ScanPageLook(look = ScanLookKind.BlackWhite, strength = 75)),
            decodeLooks(text, 2),
        )
    }

    @Test
    fun aSessionFromAnOlderBuildGivesTheDefaultLooks() {
        assertEquals(listOf(ScanPageLook(), ScanPageLook()), decodeLooks(pageLines, 2))
        assertEquals(listOf(ScanPageLook()), decodeLooks(null, 1))
        assertEquals(emptyList<ScanPageLook>(), decodeLooks("", 0))
        assertEquals(emptyList<ScanPageLook>(), decodeLooks(null, -1))
    }

    @Test
    fun linesForPagesThatDoNotExistAreIgnored() {
        val text = "look\t0\tlook=colour;strength=50\nlook\t5\tlook=bw\nlook\t-1\tlook=bw\nlook\tx\tlook=bw\n"
        assertEquals(
            listOf(ScanPageLook(look = ScanLookKind.Colour, strength = 50), ScanPageLook()),
            decodeLooks(text, 2),
        )
    }

    @Test
    fun theLastLineForAPageWins() {
        val text = "look\t0\tlook=colour;strength=50\nlook\t0\tlook=greyscale;strength=40\n"
        assertEquals(listOf(ScanPageLook(look = ScanLookKind.Greyscale, strength = 40)), decodeLooks(text, 1))
    }

    @Test
    fun aLineWithoutItsEncodedFieldIsTheDefaultLook() {
        assertEquals(listOf(ScanPageLook()), decodeLooks("look\t0\n", 1))
    }
}
