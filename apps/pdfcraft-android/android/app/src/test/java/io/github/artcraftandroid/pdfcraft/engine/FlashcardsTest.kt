package io.github.artcraftandroid.pdfcraft.engine

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FlashcardsTest {
    private val fsrs = Fsrs()

    @Test
    fun fsrsFollowsItsPublishedFormulas() {
        // first ratings take the default initial stabilities
        assertEquals(0.4872, fsrs.first(Rating.Again).stability, 1e-9)
        assertEquals(3.7145, fsrs.first(Rating.Good).stability, 1e-9)
        // at 90 % retention the interval equals the stability
        assertEquals(10, fsrs.interval(10.0))
        assertEquals(0.9, fsrs.retrievability(10.0, 10.0), 1e-9)
        // initial difficulty: w4 - (G - 3) * w5, clamped
        assertEquals(5.1618, fsrs.first(Rating.Good).difficulty, 1e-9)
        assertEquals(5.1618 + 2 * 1.2298, fsrs.first(Rating.Again).difficulty, 1e-9)
    }

    @Test
    fun goodReviewsGrowIntervalsAndLapsesShrinkThem() {
        var card = Flashcard("1", "d", "D", "front", "back")
        var now = 1_700_000_000_000L
        val intervals = ArrayList<Long>()
        repeat(5) {
            card = fsrs.review(card, Rating.Good, now)
            intervals += (card.due - now) / DAY_MS
            now = card.due
        }
        assertTrue(intervals.toString(), intervals.zipWithNext().all { (a, b) -> b > a })
        val before = card.memory!!.stability
        val lapsed = fsrs.review(card, Rating.Again, now)
        assertTrue(lapsed.memory!!.stability < before)
        assertEquals(1, lapsed.lapses)
        // Again shows the card again in the same session
        assertEquals(now + 60_000, lapsed.due)
        val easy = fsrs.review(card, Rating.Easy, now).memory!!.stability
        val good = fsrs.review(card, Rating.Good, now).memory!!.stability
        val hard = fsrs.review(card, Rating.Hard, now).memory!!.stability
        assertTrue(easy > good && good > hard)
    }

    @Test
    fun clozeCardsBlankTheirAnswer() {
        val front = Flashcards.clozeOf("The Krebs  cycle makes ATP in the mitochondria.", "krebs cycle")
        assertEquals("The {{Krebs cycle}} makes ATP in the mitochondria.", front)
        val card = Flashcard("1", "d", "D", front, "")
        assertTrue(card.isCloze)
        assertEquals("The […] makes ATP in the mitochondria.", card.clozeFront)
        assertEquals("The Krebs cycle makes ATP in the mitochondria.", card.clozeBack)
        assertEquals("{{ATP}}", Flashcards.clozeOf(null, "ATP"))
    }

    @Test
    fun ankiTsvEscapesAndImportsRoundTrip() {
        val cards = listOf(
            Flashcard("1", "d", "Bio.pdf", "What makes ATP?\tnow", "The <b>mitochondria</b>\nmostly", page = 36),
            Flashcard("2", "d", "Bio.pdf", "The {{Krebs cycle}} runs", ""),
        )
        val tsv = Flashcards.toAnkiTsv(cards)
        val lines = tsv.trimEnd().lines()
        assertEquals("#separator:tab", lines[0])
        val row = lines[3].split('\t')
        assertEquals(3, row.size)
        assertEquals("What makes ATP? now", row[0])
        assertEquals("The &lt;b&gt;mitochondria&lt;/b&gt;<br>mostly", row[1])
        assertEquals("Bio.pdf p. 37", row[2])
        assertEquals("The […] runs", lines[4].split('\t')[0])
        assertEquals(listOf("a" to "b, c", "x" to "y"), Flashcards.parseDelimited("# comment\n\"a\",\"b, c\"\r\nx\ty\n\n"))
    }

    @Test
    fun storeCountsDueCardsPerDeck() {
        val dir = Files.createTempDirectory("cards").toFile()
        try {
            val store = Flashcards(File(dir, "cards.json"))
            store.putAll(listOf(
                Flashcard("1", "a", "A", "f", "b"),
                Flashcard("2", "a", "A", "f", "b", due = 5_000),
                Flashcard("3", "b", "B", "f", "b", due = 1_000, memory = Memory(2.0, 5.0)),
            ))
            assertEquals(mapOf("a" to 1, "b" to 1), store.dueCounts(2_000))
            assertEquals(listOf("1", "3", "2"), store.due(10_000).map { it.id })
            assertEquals(Memory(2.0, 5.0), store.all().first { it.id == "3" }.memory)
            store.delete("1")
            assertEquals(2, store.all().size)
        } finally {
            dir.deleteRecursively()
        }
    }
}
