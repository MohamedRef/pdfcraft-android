package io.github.artcraftandroid.pdfcraft

import io.github.artcraftandroid.pdfcraft.engine.pdfLooksIntact
import io.github.artcraftandroid.pdfcraft.ui.viewer.VOICE_MAX_MS
import io.github.artcraftandroid.pdfcraft.ui.viewer.VOICE_WARN_MS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HighPriorityFixesTest {
    @Test fun aTruncatedOriginalIsNotIntact() {
        assertTrue(pdfLooksIntact("%PDF-1.7\n1 0 obj".toByteArray(), "trailer\n<<>>\n%%EOF\n".toByteArray()))
        assertTrue(pdfLooksIntact("%PDF-1.4".toByteArray(), null))
        assertFalse(pdfLooksIntact(ByteArray(0), null))
        assertFalse(pdfLooksIntact("%PDF-1.7\n1 0 obj".toByteArray(), "1 0 obj << /Len".toByteArray()))
        assertFalse(pdfLooksIntact("<html>".toByteArray(), "%%EOF".toByteArray()))
    }

    @Test fun recordingsStopBeforeTheEngineSoundLimit() {
        val bytesPerMs = 16_000 * 2 / 1000.0 // 16 kHz mono 16-bit
        val engineLimit = 32 * 1024 * 1024
        assertTrue(VOICE_MAX_MS * bytesPerMs + 44 < engineLimit)
        assertTrue(VOICE_WARN_MS < VOICE_MAX_MS)
    }
}
