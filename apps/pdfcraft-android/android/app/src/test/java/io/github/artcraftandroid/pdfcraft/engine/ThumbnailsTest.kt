package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The file list's preview cache keys and size limits (#125). */
class ThumbnailsTest {
    private val base = Thumbnails.key("/files/a.pdf", 1_000L, 2_000L, 160)

    @Test fun theSameFileAndSizeGiveTheSameKey() {
        assertEquals(base, Thumbnails.key("/files/a.pdf", 1_000L, 2_000L, 160))
        assertTrue(base.matches(Regex("[0-9a-f]{40}")))
    }

    @Test fun aChangedFileOrAnotherSizeGetsItsOwnKey() {
        // a stale preview, or a remembered failure, must not stick to a file that changed
        assertNotEquals(base, Thumbnails.key("/files/b.pdf", 1_000L, 2_000L, 160))
        assertNotEquals(base, Thumbnails.key("/files/a.pdf", 1_001L, 2_000L, 160))
        assertNotEquals(base, Thumbnails.key("/files/a.pdf", 1_000L, 2_001L, 160))
        assertNotEquals(base, Thumbnails.key("/files/a.pdf", 1_000L, 2_000L, 320))
    }

    @Test fun theLimitsMatchTheEngine() {
        // file_thumbnail in apps/pdfcraft-android/src/lib.rs: 512 MiB for files in the app's own
        // store, 100 MiB for anything it has to read in
        assertEquals(512L * 1024 * 1024, Thumbnails.MAX_LOCAL)
        assertEquals(100L * 1024 * 1024, Thumbnails.MAX_COPY)
    }
}
