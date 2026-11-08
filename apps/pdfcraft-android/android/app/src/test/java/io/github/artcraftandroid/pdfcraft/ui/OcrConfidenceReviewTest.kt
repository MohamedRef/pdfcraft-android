package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.OcrConfidenceWord
import io.github.artcraftandroid.pdfcraft.ui.viewer.prioritizeOcrConfidenceWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrConfidenceReviewTest {
    @Test fun uncertainWordsArePrioritizedRegardlessOfInputOrder() {
        val words = (1..140).map { OcrConfidenceWord("certain$it", 97.0, false) } +
            (1..5).map { OcrConfidenceWord("uncertain$it", 45.0, true) }
        val visible = prioritizeOcrConfidenceWords(words)
        assertEquals(100, visible.size)
        assertEquals((1..5).map { "uncertain$it" }, visible.take(5).map { it.text })
    }

    @Test fun reviewIsBoundedAndDoesNotMutateSourceList() {
        val words = mutableListOf(
            OcrConfidenceWord("a", 95.0, false),
            OcrConfidenceWord("b", 65.0, true),
            OcrConfidenceWord("c", null, false),
        )
        assertEquals(listOf("b", "a"), prioritizeOcrConfidenceWords(words, 2).map { it.text })
        assertEquals(listOf("a", "b", "c"), words.map { it.text })
        assertTrue(prioritizeOcrConfidenceWords(words, 0).isEmpty())
        assertTrue(prioritizeOcrConfidenceWords(words, -1).isEmpty())
    }

    @Test fun noExtraWordsAreFabricated() {
        assertTrue(prioritizeOcrConfidenceWords(emptyList()).isEmpty())
        val one = listOf(OcrConfidenceWord("test", null, false))
        assertEquals(one, prioritizeOcrConfidenceWords(one))
    }
}
