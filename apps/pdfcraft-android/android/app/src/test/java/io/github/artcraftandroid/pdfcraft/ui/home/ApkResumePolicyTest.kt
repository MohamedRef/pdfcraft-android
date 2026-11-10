package io.github.artcraftandroid.pdfcraft.ui.home

import java.io.EOFException
import java.io.IOException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLHandshakeException
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

    @Test fun retriesOnlyTransientNetworkFailures() {
        for (status in listOf(408, 429, 500, 502, 503, 504)) {
            assertEquals(true, apkRetryable(ApkHttpStatusException(status)))
        }
        for (status in listOf(301, 400, 401, 403, 404, 416)) {
            assertEquals(false, apkRetryable(ApkHttpStatusException(status)))
        }
        assertEquals(true, apkRetryable(SocketTimeoutException("timed out")))
        assertEquals(true, apkRetryable(SocketException("reset")))
        assertEquals(true, apkRetryable(UnknownHostException("offline")))
        assertEquals(true, apkRetryable(EOFException("short response")))
        assertEquals(false, apkRetryable(IOException("invalid range")))
        assertEquals(false, apkRetryable(SSLHandshakeException("certificate rejected")))
        assertEquals(false, apkRetryable(IOException("No space left on device")))
    }

    @Test fun retryBackoffIsBounded() {
        assertEquals(3, MAX_APK_NETWORK_RETRIES)
        assertEquals(1000L, apkRetryDelayMillis(1))
        assertEquals(2000L, apkRetryDelayMillis(2))
        assertEquals(4000L, apkRetryDelayMillis(3))
    }

    @Test fun progressRestartsTheRetryBudget() {
        assertEquals(true, apkRetryBudgetResets(-1L, 1024L))
        assertEquals(true, apkRetryBudgetResets(1024L, 4096L))
        assertEquals(false, apkRetryBudgetResets(4096L, 4096L))
        assertEquals(false, apkRetryBudgetResets(-1L, 0L))
    }

    @Test fun progressFormatsPersistedBytes() {
        assertEquals("0.0", readableMegabytes(-1))
        assertEquals("1.5", readableMegabytes(1572864L))
    }
}
