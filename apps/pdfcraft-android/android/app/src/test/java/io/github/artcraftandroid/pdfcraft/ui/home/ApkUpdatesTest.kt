package io.github.artcraftandroid.pdfcraft.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ApkUpdatesTest {
    @Test fun gradleVersionCodeParity() {
        assertEquals(101L, updateVersionBase("0.0.1-beta"))
        assertEquals(102L, updateVersionBase("0.0.1-beta.2"))
        assertEquals(199L, updateVersionBase("0.0.1"))
        assertEquals(10001L, updateVersionBase("0.1.0-alpha"))
        assertNull(updateVersionBase("1.100.0"))
        assertEquals(1000098L, updateVersionBase("1.0.0-beta.98"))
        assertNull(updateVersionBase("1.0.0-beta.99"))
        assertNull(updateVersionBase("1.0.0-preview.0"))
        assertNull(updateVersionBase("1.0.0-alpha.100"))
        assertNull(updateVersionBase("unknown"))
    }

    @Test fun verifiedAssetDigestMustBeSha256() {
        val hex = "Ab".repeat(32)
        assertEquals(hex.lowercase(), releaseSha256("sha256:${hex}"))
        assertNull(releaseSha256(null))
        assertNull(releaseSha256("sha256:abc"))
        assertNull(releaseSha256("md5:" + hex))
        assertNull(releaseSha256(hex))
        assertNull(releaseSha256("sha256:" + "x".repeat(64)))
    }

    @Test fun arm64BuildCodeIsComputedForDownload() {
        val update = ApkUpdate(
            "0.0.7-beta.3", "Beta", 703L, "pdfcraft-android-0.0.7-beta.3.apk",
            "https://github.com/MohamedRef/pdfcraft-android/releases/download/v0.0.7-beta.3/pdfcraft-android-0.0.7-beta.3.apk",
            "2026-11-06", "", 1048576L, "a".repeat(64),
        )
        assertEquals(7032L, update.apkVersionCode)
    }

    @Test fun releaseChannelRecognition() {
        assertEquals("Alpha", updateChannel("1.0.0-alpha.2"))
        assertEquals("Beta", updateChannel("1.0.0-beta.4"))
        assertEquals("Preview", updateChannel("1.0.0-preview.1"))
        assertEquals("Preview", updateChannel("1.0.0-rc.1"))
        assertEquals("Stable", updateChannel("1.0.0"))
    }
}
