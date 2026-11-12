package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.VoiceRecorder
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The header voice comments are written with is what the engine's `wav_samples` reads
 * (crates/automation/src/comments.rs): RIFF/WAVE, a 16-byte PCM `fmt ` chunk, then `data` (#125).
 */
class WavHeaderTest {
    private fun header(dataBytes: Long, rate: Int): ByteBuffer {
        val f = Files.createTempFile("voice", ".wav").toFile()
        RandomAccessFile(f, "rw").use { VoiceRecorder.writeWavHeader(it, dataBytes, rate) }
        return ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).also { f.delete() }
    }

    private fun ByteBuffer.tag(at: Int) = String(ByteArray(4).also { position(at); get(it) })

    @Test fun aSixteenKilohertzMonoHeader() {
        val h = header(32_000, 16_000)
        assertEquals(44, h.limit())
        assertEquals("RIFF", h.tag(0)); assertEquals(32_036, h.getInt(4))
        assertEquals("WAVE", h.tag(8)); assertEquals("fmt ", h.tag(12)); assertEquals(16, h.getInt(16))
        assertEquals(1, h.getShort(20).toInt()) // PCM
        assertEquals(1, h.getShort(22).toInt()) // mono
        assertEquals(16_000, h.getInt(24)); assertEquals(32_000, h.getInt(28))
        assertEquals(2, h.getShort(32).toInt()); assertEquals(16, h.getShort(34).toInt())
        assertEquals("data", h.tag(36)); assertEquals(32_000, h.getInt(40))
    }

    @Test fun anOversizedLengthIsClampedToWhatRiffCanSay() {
        val h = header(Long.MAX_VALUE, 16_000)
        assertArrayEquals(byteArrayOf(-1, -1, -1, -1), ByteArray(4).also { h.position(4); h.get(it) })
        assertEquals(0xFFFF_FFFFL - 36, h.getInt(40).toLong() and 0xFFFF_FFFFL)
    }
}
