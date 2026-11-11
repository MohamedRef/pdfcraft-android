package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WriteBackCheckTest {
    private val tail = "trailer\n%%EOF\n".toByteArray()

    @Test fun matchingLengthAndTailIsVerified() {
        assertTrue(writeBackMatches(1_000, tail, WrittenBack(1_000, tail.copyOf())))
    }

    @Test fun providerThatIgnoresTruncationKeepsTheMarker() {
        // #102: a 1 MB PDF written over a 2 MB original leaves the old trailing bytes behind
        assertFalse(writeBackMatches(1_000_000, tail, WrittenBack(2_000_000, "old bytes".toByteArray())))
    }

    @Test fun shortWriteKeepsTheMarker() {
        assertFalse(writeBackMatches(1_000_000, tail, WrittenBack(999_999, null)))
    }

    @Test fun sameLengthWithDifferentEndKeepsTheMarker() {
        assertFalse(writeBackMatches(1_000, tail, WrittenBack(1_000, "trailer\n%%EOX\n".toByteArray())))
    }

    @Test fun providerThatCannotReportIsTakenAsBefore() {
        assertTrue(writeBackMatches(1_000, tail, null))
        assertTrue(writeBackMatches(1_000, tail, WrittenBack(null, null)))
        assertTrue(writeBackMatches(1_000, tail, WrittenBack(1_000, null)))
    }
}
