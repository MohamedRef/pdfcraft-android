package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.core.ScanFindOptions
import io.github.artcraftandroid.pdfcraft.core.ScanFinder
import java.io.File
import java.nio.file.Files
import java.util.zip.ZipFile
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The scanner's testing options and the scans kept for testing (#288). */
class ScanLabTest {
    private val crop = floatArrayOf(0.1f, 0.1f, 0.9f, 0.12f, 0.88f, 0.95f, 0.08f, 0.9f)

    @Test
    fun theDefaultsAreTheScannerAsItIsMeantToBeUsed() {
        val lab = ScanLab()
        assertTrue(lab.asDesigned)
        assertFalse(lab.keep)
        assertEquals(ScanFindOptions(ScanFinder.AUTO, true), lab.findOptions())
        assertEquals(60, lab.strengthFor(60))
        // keeping scans changes no page; every other option does
        assertTrue(lab.copy(keep = true).asDesigned)
        assertFalse(lab.copy(finder = ScanFinder.LINES).asDesigned)
        assertFalse(lab.copy(refine = false).asDesigned)
        assertFalse(lab.copy(perspective = false).asDesigned)
        assertFalse(lab.copy(snapPaper = false).asDesigned)
        assertFalse(lab.copy(strength = 80).asDesigned)
    }

    @Test
    fun aStrengthOverridesEveryLooksOwn() {
        assertEquals(80, ScanLab(strength = 80).strengthFor(40))
        assertEquals(100, ScanLab().strengthFor(250))
        assertEquals(0, ScanLab().strengthFor(-3))
        assertTrue(null in ScanLab.STRENGTHS && ScanLab.STRENGTHS.filterNotNull().all { it in 0..100 })
    }

    @Test
    fun optionsComeBackFromThePreferencesAsTheyWere() {
        val all = listOf(
            ScanLab(),
            ScanLab(keep = true, finder = ScanFinder.NETWORK, refine = false, perspective = false, snapPaper = false, strength = 0),
            ScanLab(finder = ScanFinder.LINES, strength = 100),
            ScanLab(finder = ScanFinder.OFF),
        )
        for (lab in all) assertEquals(lab, ScanLab.decode(lab.encode()))
        // every detector has a name of its own
        assertEquals(ScanFinder.entries.size, ScanFinder.entries.map { ScanLab.finderId(it) }.toSet().size)
    }

    @Test
    fun whatIsNotUnderstoodIsTheDefault() {
        assertEquals(ScanLab(), ScanLab.decode(null))
        assertEquals(ScanLab(), ScanLab.decode(""))
        assertEquals(ScanLab(), ScanLab.decode("nonsense;;=;finder=moon;refine=yes;strength=900"))
        // a later version's extra option is passed over
        assertEquals(ScanLab(refine = false), ScanLab.decode("refine=0;colour_space=wide"))
    }

    @Test
    fun theLogGetsWordsAndNumbersOnly() {
        val fields = ScanLab(finder = ScanFinder.LINES, perspective = false, strength = 40).diag().toMap()
        assertEquals("lines", fields["finder"])
        assertEquals(true, fields["side_proportions"])
        assertEquals(40, fields["strength_override"])
        assertEquals(-1, ScanLab().diag().toMap()["strength_override"])
        for (value in fields.values) assertTrue(value is Boolean || value is Int || (value is String && value.all { it in 'a'..'z' }))
    }

    private val result = ScanTestResult(1654, 2339, "jpeg", 200.0, "a4", "known_focal", focalMismatch = false, flattened = true, bendPct = 0.6912)

    @Test
    fun aKeptPageSaysWhatTheTestSetNeedsAndHowTheAppGotThere() {
        val finding = ScanFinding("model", 0.8349, 0, 1, 4, 0)
        val json = JSONObject(scanTestPageJson("0.0.26-beta (26001)", true, crop, crop, finding, "auto", 60, true, ScanLab(strength = 60), "page-01.out.jpg", result))
        // the test set's own keys (#288)
        assertEquals(8, json.getJSONArray("corners").length())
        assertEquals(0.12, json.getJSONArray("corners").getDouble(3), 1e-9)
        assertEquals("other", json.getString("kind"))
        assertEquals("unknown", json.getString("paper"))
        assertFalse(json.getBoolean("curved"))
        assertEquals("", json.getString("notes"))
        // how the app got there
        assertEquals("0.0.26-beta (26001)", json.getString("app"))
        assertEquals("saved", json.getString("outcome"))
        assertEquals(8, json.getJSONArray("found").length())
        assertEquals("model", json.getJSONObject("finding").getString("source"))
        assertEquals(0.835, json.getJSONObject("finding").getDouble("confidence"), 1e-9)
        assertEquals(4, json.getJSONObject("finding").getInt("refined_sides"))
        assertEquals("auto", json.getString("look"))
        assertEquals(60, json.getInt("strength"))
        assertTrue(json.getBoolean("flatten"))
        assertEquals("auto", json.getJSONObject("options").getString("finder"))
        assertEquals(60, json.getJSONObject("options").getInt("strength_override"))
        val made = json.getJSONObject("result")
        assertEquals("page-01.out.jpg", made.getString("file"))
        assertEquals(1654, made.getInt("width"))
        assertEquals("a4", made.getString("paper"))
        assertEquals(0.691, made.getDouble("bend_pct"), 1e-9)
        assertTrue(made.getBoolean("flattened"))
    }

    @Test
    fun aDiscardedPageHasNoResultAndMayHaveNoFinding() {
        val json = JSONObject(scanTestPageJson("1.0 (1)", false, ScanPage.WHOLE, null, null, null, null, false, ScanLab(), null, null))
        assertEquals("discarded", json.getString("outcome"))
        assertTrue(json.isNull("found") && json.isNull("finding") && json.isNull("look") && json.isNull("strength") && json.isNull("result"))
        assertTrue(json.getJSONObject("options").isNull("strength_override"))
        assertEquals(1.0, json.getJSONArray("corners").getDouble(2), 0.0)
        // a corner that is not a number never breaks the file
        val odd = crop.copyOf().also { it[0] = Float.NaN }
        assertEquals(0.0, JSONObject(scanTestPageJson("1.0 (1)", false, odd, null, null, null, null, false, ScanLab(), null, null)).getJSONArray("corners").getDouble(0), 0.0)
    }

    @Test
    fun pagesAreNamedInOrder() {
        assertEquals("page-01", scanTestName(0))
        assertEquals("page-12", scanTestName(11))
        assertEquals("page-100", scanTestName(99))
    }

    private fun file(dir: File, name: String, bytes: Int): File = File(dir, name).apply { writeBytes(ByteArray(bytes) { (it % 251).toByte() }) }

    private fun keep(store: ScanTestStore, stamp: String, camera: File, pages: Int, withOutput: Boolean): File {
        val scan = store.newScan(stamp)!!
        for (i in 0 until pages) {
            val photo = file(camera, "scan-$stamp-$i.jpg", 4000)
            val output = if (withOutput) file(camera, "page-$i.png", 1000) else null
            store.addPage(scan, i, photo, output) { kept -> scanTestPageJson("1.0 (1)", withOutput, crop, null, null, "bw", 75, false, ScanLab(), kept, if (withOutput) result else null) }
        }
        store.finish(scan)
        return scan
    }

    @Test
    fun aScanIsKeptWithItsPhotosFactsAndPages() {
        val root = Files.createTempDirectory("scan-tests").toFile()
        val camera = Files.createTempDirectory("camera").toFile()
        val store = ScanTestStore(File(root, "kept"))
        assertEquals(ScanTestStore.Summary(0, 0L), store.summary())
        val scan = keep(store, "20261112-081500", camera, pages = 2, withOutput = true)
        assertEquals(listOf("page-01.jpg", "page-01.json", "page-01.out.png", "page-02.jpg", "page-02.json", "page-02.out.png"), scan.list()!!.sorted())
        // the photo is the camera's file, byte for byte
        assertTrue(File(scan, "page-01.jpg").readBytes().contentEquals(File(camera, "scan-20261112-081500-0.jpg").readBytes()))
        assertEquals("page-02.out.png", JSONObject(File(scan, "page-02.json").readText()).getJSONObject("result").getString("file"))
        // a discarded scan: photos and facts only
        val discarded = keep(store, "20261112-081500", camera, pages = 1, withOutput = false)
        assertEquals("20261112-081500-2", discarded.name)
        assertEquals(listOf("page-01.jpg", "page-01.json"), discarded.list()!!.sorted())
        val summary = store.summary()
        assertEquals(3, summary.pages)
        assertTrue(summary.bytes > 3 * 4000 + 2 * 1000)

        val zip = File(root, "share/scan-tests/out.zip")
        assertEquals(3, store.zip(zip, mapOf("diagnostics.txt" to "PdfCraft diagnostics log\n")))
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }.toSet()
            assertTrue("diagnostics.txt" in names && "20261112-081500/page-02.out.png" in names && "20261112-081500-2/page-01.json" in names)
            assertEquals(1 + 6 + 2, names.size)
            assertTrue(z.getInputStream(z.getEntry("20261112-081500/page-01.jpg")).readBytes().contentEquals(File(scan, "page-01.jpg").readBytes()))
        }

        store.clear()
        assertEquals(ScanTestStore.Summary(0, 0L), store.summary())
        assertEquals(0, store.zip(zip, emptyMap()))
        assertFalse(zip.exists())
    }

    @Test
    fun olderScansMakeRoomAndTheNewestAlwaysStays() {
        val root = Files.createTempDirectory("scan-tests").toFile()
        val camera = Files.createTempDirectory("camera").toFile()
        val store = ScanTestStore(root)
        val first = keep(store, "20261101-100000", camera, pages = 2, withOutput = false)
        val second = keep(store, "20261102-100000", camera, pages = 2, withOutput = false)
        val third = keep(store, "20261103-100000", camera, pages = 2, withOutput = false)
        val each = first.listFiles()!!.sumOf { it.length() }
        store.trim(2 * each)
        assertFalse(first.exists())
        assertTrue(second.exists() && third.exists())
        store.trim(1)
        assertFalse(second.exists())
        assertTrue(third.exists())
        assertEquals(2, store.summary().pages)
    }

    @Test
    fun aMissingPhotoOrAnEmptyScanLeavesNothingBehind() {
        val root = Files.createTempDirectory("scan-tests").toFile()
        val store = ScanTestStore(root)
        val scan = store.newScan("2026/11/12 08:15")!!
        // only letters, digits and dashes of the stamp make the folder's name
        assertEquals("202611120815", scan.name)
        store.addPage(scan, 0, File(root, "gone.jpg"), null) { "{}" }
        store.finish(scan)
        assertFalse(scan.exists())
        assertNull(root.listFiles()?.firstOrNull())
        assertNotNull(store.newScan(""))
    }
}
