package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** #572: a refused tool is logged with a fixed word for why, never with its message. */
class ToolRefusalWordTest {
    @Test
    fun refusalsGetAFixedWord() {
        assertEquals("unknown_argument", toolRefusalWord("comment_erase: unknown argument \"erase_highlights\" (expected: doc, page, path, radius)"))
        assertEquals("too_large", toolRefusalWord("eraser path requires 1 to 4096 points"))
        assertEquals("invalid_argument", toolRefusalWord("eraser radius must be 0.1 to 100 points"))
        assertEquals("password", toolRefusalWord("the document is protected by a password"))
        assertEquals("other", toolRefusalWord("/storage/emulated/0/Secret Notes.pdf"))
    }
}
