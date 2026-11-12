package io.github.artcraftandroid.pdfcraft.ui.scan

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanLowLightTest {
    @Test
    fun offOrSmallMemoryNeverTakesABurst() {
        assertEquals(ScanBurstWhy.Off, scanBurstWhy(ScanMergeSetting.Off, 512, 10f, 3_200))
        assertEquals(ScanBurstWhy.Memory, scanBurstWhy(ScanMergeSetting.Always, 192, 10f, 3_200))
        assertEquals(ScanBurstWhy.Memory, scanBurstWhy(ScanMergeSetting.Auto, 255, 10f, null))
        assertFalse(ScanBurstWhy.Off.burst || ScanBurstWhy.Memory.burst)
    }

    @Test
    fun alwaysTakesABurstInAnyLight() {
        val why = scanBurstWhy(ScanMergeSetting.Always, 256, 200f, 50)
        assertEquals(ScanBurstWhy.Always, why)
        assertTrue(why.burst)
    }

    @Test
    fun autoFollowsTheDark() {
        assertEquals(ScanBurstWhy.DarkLuma, scanBurstWhy(ScanMergeSetting.Auto, 256, 69.9f, null))
        assertEquals(ScanBurstWhy.Bright, scanBurstWhy(ScanMergeSetting.Auto, 256, 70f, null))
        assertEquals(ScanBurstWhy.DarkIso, scanBurstWhy(ScanMergeSetting.Auto, 256, null, 800))
        assertEquals(ScanBurstWhy.Bright, scanBurstWhy(ScanMergeSetting.Auto, 256, null, 799))
        // a bright live frame but a high sensitivity: still dark
        assertEquals(ScanBurstWhy.DarkIso, scanBurstWhy(ScanMergeSetting.Auto, 512, 120f, 1_600))
        assertEquals(ScanBurstWhy.Unknown, scanBurstWhy(ScanMergeSetting.Auto, 512, null, null))
        assertFalse(ScanBurstWhy.Unknown.burst || ScanBurstWhy.Bright.burst)
        assertTrue(ScanBurstWhy.DarkLuma.burst && ScanBurstWhy.DarkIso.burst)
    }

    @Test
    fun settingDecodes() {
        assertEquals(ScanMergeSetting.Auto, ScanMergeSetting.decode(null))
        assertEquals(ScanMergeSetting.Auto, ScanMergeSetting.decode("nonsense"))
        for (s in ScanMergeSetting.entries) assertEquals(s, ScanMergeSetting.decode(s.id))
    }

    @Test
    fun burstFilesSitBesideTheTarget() {
        val first = File("/cache/camera/scan-42.jpg")
        val files = scanBurstFiles(first)
        assertEquals(4, files.size)
        assertEquals(first, files[0])
        assertEquals(listOf("scan-42.jpg", "scan-42-b2.jpg", "scan-42-b3.jpg", "scan-42-b4.jpg"), files.map { it.name })
        assertTrue(files.all { it.parentFile == first.parentFile })
        assertEquals(4, files.toSet().size)
        assertEquals("scan-42-merged.jpg", scanMergedFile(first).name)
    }

    private val quad = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.9f, 0.1f, 0.9f)

    @Test
    fun framesWithoutAPageAreLeftOut() {
        val files = scanBurstFiles(File("/c/scan-1.jpg"))
        val frames = scanMergeFrames(files, listOf(quad, null, quad, floatArrayOf(0f, 0f)))
        assertNotNull(frames)
        assertEquals(listOf(files[0], files[2]), frames!!.map { it.first })
        assertNull("one page found: nothing to merge", scanMergeFrames(files, listOf(quad, null, null, null)))
        assertNull(scanMergeFrames(files, listOf(quad, floatArrayOf(Float.NaN, 0f, 1f, 0f, 1f, 1f, 0f, 1f), null, null)))
    }

    @Test
    fun aFailedMergeKeepsTheFirstPhoto() {
        val files = scanBurstFiles(File("/c/scan-1.jpg"))
        val failed = scanBurstOutcome(files, null)
        assertEquals(files[0], failed.keep)
        assertFalse(failed.merged)
        assertEquals(files.drop(1), failed.delete)
        val merged = scanMergedFile(files[0])
        val ok = scanBurstOutcome(files, merged)
        assertEquals(merged, ok.keep)
        assertTrue(ok.merged)
        assertEquals(files, ok.delete)
        assertFalse(merged in ok.delete)
    }

    @Test
    fun mergeFactsAreRecorded() {
        val f = ScanMergeFacts("dark_luma", frames = 4, found = 4, merged = true, used = 3, rejectedBlur = 1, psnrGainTenthsDb = 42, burstMs = 2_600, findMs = 900, mergeMs = 1_400)
        val d = f.diag().toMap()
        assertEquals("dark_luma", d["why"])
        assertEquals(true, d["slow_burst"])
        assertEquals(42, d["psnr_gain_tenths_db"])
        val c = ScanCapture("in_app", merge = f)
        assertTrue(c.straightened)
        assertEquals(true, c.diag().toMap()["merged"])
        val j = c.json().getJSONObject("merge")
        assertEquals(3, j.getInt("used"))
        assertEquals("none", j.getString("fallback"))
        assertFalse(ScanCapture("in_app", merge = f.copy(merged = false, fallback = "few_found")).straightened)
        assertTrue(ScanCapture("in_app").json().isNull("merge"))
        assertFalse(ScanCapture("in_app").straightened)
    }
}
