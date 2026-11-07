package io.github.artcraftandroid.pdfcraft.ui.home

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ApkResumePolicyTest {
    @Test fun exactRangeCanAppend() {
        assertEquals(ApkResumeAction.APPEND, apkResumeAction(500, 206, "bytes 500-999/1000", 1000))
        assertEquals(ApkResumeAction.APPEND, apkResumeAction(1, 206, "bytes 1-999/1000", 1000))
    }

    @Test fun ignoredRangeStartsFreshWithoutDuplicatingBytes() {
        assertEquals(ApkResumeAction.RESTART, apkResumeAction(500, 200, null, 1000))
        assertEquals(ApkResumeAction.RESTART, apkResumeAction(0, 200, null, 1000))
    }

    @Test fun expiredRangeCanRestartFreshOnce() {
        assertEquals(ApkResumeAction.RETRY_WITHOUT_RANGE, apkResumeAction(500, 416, "bytes */1000", 1000))
        assertThrows(IOException::class.java) { apkResumeAction(0, 416, null, 1000) }
    }

    @Test fun rejectMismatchedOrInvalidContentRanges() {
        for (range in listOf(
            "bytes 499-999/1000", "bytes 500-998/999", "bytes 500-1200/1000",
            "bytes 500-499/1000", "bytes 500-999/*", "bytes 500-999/1001",
            "bytes 500-999/1000, 0-1/1000", "bytes 500-999/0", null
        )) {
            assertThrows("Range: $range", IOException::class.java) { apkResumeAction(500, 206, range, 1000) }
        }
        assertThrows(IOException::class.java) { apkResumeAction(0, 206, "bytes 0-999/1000", 1000) }
        assertThrows(IOException::class.java) { apkResumeAction(-1, 200, null, 1000) }
        assertThrows(IOException::class.java) { apkResumeAction(1001, 200, null, 1000) }
        assertThrows(IOException::class.java) { apkResumeAction(500, 404, null, 1000) }
    }

    @Test fun immutableDigestNamesAreSafe() {
        val digest = "9e".repeat(32)
        assertEquals("download-$digest.part", apkPartialFileName(digest))
        for (invalid in listOf("", "../update.apk", "A".repeat(64), "f".repeat(65))) {
            assertThrows(IOException::class.java) { apkPartialFileName(invalid) }
        }
    }

    @Test fun progressFormatsPersistedBytes() {
        assertEquals("0.0", readableMegabytes(-1))
        assertEquals("1.5", readableMegabytes(1572864L))
    }
}
