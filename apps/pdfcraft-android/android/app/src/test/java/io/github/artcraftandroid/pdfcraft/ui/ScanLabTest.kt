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
        // nor does the detectors' report (#305, #306), which is off as designed
        assertFalse(lab.inspect)
        assertTrue(lab.copy(inspect = true).asDesigned)
        assertTrue(lab.copy(keep = true, inspect = true).asDesigned)
        assertFalse(lab.copy(finder = ScanFinder.LINES).asDesigned)
        assertFalse(lab.copy(refine = false).asDesigned)
        assertFalse(lab.copy(perspective = false).asDesigned)
        assertFalse(lab.copy(snapPaper = false).asDesigned)
        assertFalse(lab.copy(strength = 80).asDesigned)
        assertFalse(lab.copy(upright = false).asDesigned)
        assertTrue(lab.upright)
        assertFalse(lab.copy(sharpen = 0).asDesigned)
        assertFalse(lab.copy(denoise = false).asDesigned)
        // the page preview is on as designed; off is a testing option (#301)
        assertTrue(lab.preview)
        assertFalse(lab.copy(preview = false).asDesigned)
        // unsure pages follow their neighbours as designed (#339)
        assertTrue(lab.settleTurns)
        assertFalse(lab.copy(settleTurns = false).asDesigned)
        // document types are on as designed (#299)
        assertTrue(lab.presets)
        assertFalse(lab.copy(presets = false).asDesigned)
        // the crop screen with magnifier and snapping is as designed; the old one is a testing option (#292)
        assertTrue(lab.cropTools)
        assertFalse(lab.copy(cropTools = false).asDesigned)
        // automatic flattening is a testing option, off as designed until real photos show it helps (#302)
        assertFalse(lab.flattenAuto)
        assertFalse(lab.copy(flattenAuto = true).asDesigned)
        // a flattened page's border is trimmed as designed; off is a testing option (#302)
        assertTrue(lab.flattenTrim)
        assertFalse(lab.copy(flattenTrim = false).asDesigned)
        // a look per page and the looks strip's previews are as designed; off is the way back (#301)
        assertTrue(lab.lookPerPage && lab.lookStrip)
        assertFalse(lab.copy(lookPerPage = false).asDesigned)
        assertFalse(lab.copy(lookStrip = false).asDesigned)
        // the Whiteboard look, stopping old previews and zoom are as designed (#301)
        assertTrue(lab.whiteboardLook && lab.previewStop && lab.previewZoom)
        assertFalse(lab.copy(whiteboardLook = false).asDesigned)
        assertFalse(lab.copy(previewStop = false).asDesigned)
        assertFalse(lab.copy(previewZoom = false).asDesigned)
        assertEquals(30, lab.sharpen)
        assertTrue(lab.denoise && lab.sharpen in ScanLab.SHARPENING && 0 in ScanLab.SHARPENING)
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
            ScanLab(keep = true, finder = ScanFinder.NETWORK, refine = false, perspective = false, snapPaper = false, strength = 0, upright = false, sharpen = 0, denoise = false),
            ScanLab(sharpen = 100),
            ScanLab(preview = false),
            ScanLab(settleTurns = false),
            ScanLab(presets = false),
            ScanLab(cropTools = false),
            ScanLab(flattenAuto = true),
            ScanLab(flattenTrim = false),
            ScanLab(lookPerPage = false),
            ScanLab(lookStrip = false),
            ScanLab(whiteboardLook = false, previewStop = false, previewZoom = false),
            ScanLab(lookPerPage = false, lookStrip = false, presets = false),
            ScanLab(pageStrength = false),
            ScanLab(keep = true, inspect = true),
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
        // options saved before pages were turned upright (#339) get that as it is meant to be: on
        assertEquals(ScanLab(keep = true), ScanLab.decode("keep=1;finder=auto;refine=1;perspective=1;snap=1;strength="))
        // a sharpening that is not a number from 0 to 100 is the default
        assertEquals(ScanLab(), ScanLab.decode("sharpen=250;denoise=maybe"))
    }

    @Test
    fun theLogGetsWordsAndNumbersOnly() {
        val fields = ScanLab(finder = ScanFinder.LINES, perspective = false, strength = 40).diag().toMap()
        assertEquals("lines", fields["finder"])
        assertEquals(true, fields["side_proportions"])
        assertEquals(40, fields["strength_override"])
        assertEquals(true, fields["upright"])
        assertEquals(30, fields["sharpen"])
        assertEquals(true, fields["denoise"])
        assertEquals(-1, ScanLab().diag().toMap()["strength_override"])
        for (value in fields.values) assertTrue(value is Boolean || value is Int || (value is String && value.all { it in 'a'..'z' }))
    }

    private val result = ScanTestResult(
        1654, 2339, "jpeg", 200.0, "a4", "known_focal", focalMismatch = false, flattened = true, bendPct = 0.6912,
        quarterTurns = 3, orientation = ScanTestOrientation(3, 0.8349, 0.8889, 9, true), noise = 1.2345,
    )

    @Test
    fun aKeptPageSaysWhatTheTestSetNeedsAndHowTheAppGotThere() {
        val finding = ScanFinding("model", 0.8349, 0, 1, 4, 0)
        val json = JSONObject(scanTestPageJson("0.0.26-beta (26001)", true, crop, crop, finding, "auto", 60, true, ScanLab(strength = 60), "page-01.out.jpg", result, ScanTestPreview(true, true, 412, 905, 1280, "auto")))
        // #425: nobody has checked the corners yet, so scan-eval leaves the page out until someone sets this
        assertFalse(json.getBoolean("checked"))
        // the last preview shown before saving (#301)
        val shown = json.getJSONObject("preview")
        assertTrue(shown.getBoolean("shown") && shown.getBoolean("reused"))
        assertEquals(412, shown.getInt("ms"))
        assertEquals(1280, shown.getInt("height"))
        assertEquals("auto", shown.getString("look"))
        assertTrue(json.getJSONObject("options").getBoolean("preview"))
        assertTrue(json.getJSONObject("options").getBoolean("crop_tools"))
        // a page never opened in Crop has no crop facts
        assertTrue(json.isNull("crop"))
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
        // which way up it was found to be, and the turn it was given (#339)
        assertEquals(3, made.getInt("quarter_turns"))
        assertEquals(0.835, made.getJSONObject("orientation").getDouble("confidence"), 1e-9)
        assertEquals(0.889, made.getJSONObject("orientation").getDouble("agreement"), 1e-9)
        assertEquals(9, made.getJSONObject("orientation").getInt("tiles"))
        assertTrue(made.getJSONObject("orientation").getBoolean("sure"))
        assertTrue(json.getJSONObject("options").getBoolean("upright"))
        // the sharpening and smoothing asked for, and the grain measured (#299)
        assertEquals(30, json.getJSONObject("options").getInt("sharpen"))
        assertTrue(json.getJSONObject("options").getBoolean("denoise"))
        assertEquals(1.23, made.getDouble("noise"), 1e-9)
        // a page the classifier was not asked about
        val plain = JSONObject(scanTestPageJson("1.0 (1)", true, crop, null, null, "bw", 75, false, ScanLab(upright = false), null, result.copy(quarterTurns = 0, orientation = null, noise = null)))
        assertTrue(plain.getJSONObject("result").isNull("orientation") && plain.getJSONObject("result").isNull("noise"))
        assertFalse(plain.getJSONObject("options").getBoolean("upright"))
    }

    @Test
    fun aKeptPageSaysHowItsTurnWasDecided() {
        // a page that took its neighbours' turn (#339)
        val followed = JSONObject(scanTestPageJson("1.0 (1)", true, crop, null, null, "auto", 60, false, ScanLab(), null, result, null, turn = ScanTestTurn(null, true, 1, true, "neighbours")))
        val turns = followed.getJSONObject("turns")
        assertTrue(turns.isNull("manual"))
        assertTrue(turns.getBoolean("auto") && turns.getBoolean("followed"))
        assertEquals(1, turns.getInt("total"))
        assertEquals("neighbours", turns.getString("source"))
        assertTrue(followed.getJSONObject("options").getBoolean("settle_turns"))
        // a page the person turned
        val manual = JSONObject(scanTestPageJson("1.0 (1)", true, crop, null, null, "auto", 60, false, ScanLab(settleTurns = false), null, result, null, turn = ScanTestTurn(2, false, 2, false, "manual")))
        assertEquals(2, manual.getJSONObject("turns").getInt("manual"))
        assertFalse(manual.getJSONObject("options").getBoolean("settle_turns"))
        // and in the log, as a word
        assertEquals(true, ScanLab().diag().toMap()["settle_turns"])
    }

    @Test
    fun aKeptPageSaysWhichKindOfDocumentItWasTakenFor() {
        val kind = ScanTestKind("receipt", false, 80, 35, false, false, true, 3.0004, 0.04321, 0.0, 0.0, 0.3387, 0.0, false, false, gutterLoose = true, gutterCover = 0.61234)
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, crop, null, null, "auto", 60, false, ScanLab(), null, result, null, kind = kind))
        val type = json.getJSONObject("document_type")
        assertEquals("receipt", type.getString("preset"))
        assertFalse(type.getBoolean("chosen"))
        assertEquals(80, type.getInt("strength"))
        assertEquals(35, type.getInt("sharpen"))
        assertTrue(type.getBoolean("suggest_greyscale"))
        assertEquals(3.0, type.getDouble("ratio"), 1e-9)
        assertEquals(0.043, type.getDouble("ink_share"), 1e-9)
        assertEquals(0.339, type.getDouble("stroke_mm"), 1e-9)
        assertTrue(json.getJSONObject("options").getBoolean("presets"))
        // the test set's own label stays the person's to fill in (#288)
        assertEquals("other", json.getString("kind"))
        assertTrue(JSONObject(scanTestPageJson("1.0 (1)", true, crop, null, null, "bw", 75, false, ScanLab(), null, result)).isNull("document_type"))
        assertEquals(true, ScanLab().diag().toMap()["presets"])
    }

    @Test
    fun aDiscardedPageHasNoResultAndMayHaveNoFinding() {
        val json = JSONObject(scanTestPageJson("1.0 (1)", false, ScanPage.WHOLE, null, null, null, null, false, ScanLab(), null, null))
        assertEquals("discarded", json.getString("outcome"))
        assertTrue(json.isNull("found") && json.isNull("finding") && json.isNull("look") && json.isNull("strength") && json.isNull("result") && json.isNull("preview") && json.isNull("crop") && json.isNull("turns"))
        assertTrue(json.getJSONObject("options").isNull("strength_override"))
        // the detectors' report (#305, #306) is there only when it was asked for
        assertTrue(json.isNull("inspect"))
        assertFalse(json.getJSONObject("options").getBoolean("inspect"))
        val report = JSONObject().put("flagged", 1)
        val inspected = JSONObject(scanTestPageJson("1.0 (1)", false, ScanPage.WHOLE, null, null, null, null, false, ScanLab(keep = true, inspect = true), null, null, inspection = report))
        assertEquals(1, inspected.getJSONObject("inspect").getInt("flagged"))
        assertTrue(inspected.getJSONObject("options").getBoolean("inspect"))
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

    @Test
    fun aKeptPageSaysWhatHappenedOnTheCropScreen() {
        val stats = io.github.artcraftandroid.pdfcraft.ui.scan.ScanCropStats(visits = 1, cornerDrags = 2, sideDrags = 1, snapsAsked = 3, snapsTaken = 2, snapMsMax = 85, snapOn = true, ms = 9100, movedPct = 1.23456, cornersOff = 1)
        val json = JSONObject(scanTestPageJson("1.0 (1)", false, crop, crop, null, null, null, false, ScanLab(), null, null, null, stats)).getJSONObject("crop")
        assertEquals(1, json.getInt("visits"))
        assertEquals(2, json.getInt("corner_drags"))
        assertEquals(2, json.getInt("snaps_taken"))
        assertEquals(85, json.getInt("snap_ms_max"))
        assertEquals(1.235, json.getDouble("moved_pct"), 1e-9)
        assertEquals(1, json.getInt("corners_off"))
        assertTrue(json.getBoolean("snap"))
    }

    @Test
    fun aKeptPageSaysHowFlatteningWasChecked() {
        val check = io.github.artcraftandroid.pdfcraft.core.ScanFlattenCheck(false, "notbetter", 3.0f, 2.9f, 12u, 12u)
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, crop, crop, null, "auto", 60, false, ScanLab(flattenAuto = true), "page-01.out.jpg", result.copy(flattenCheck = check)))
        val c = json.getJSONObject("result").getJSONObject("flatten_check")
        assertFalse(c.getBoolean("kept"))
        assertEquals("notbetter", c.getString("why"))
        assertEquals(3.0, c.getDouble("straightened_px"), 1e-9)
        assertEquals(2.9, c.getDouble("flattened_px"), 1e-6)
        assertEquals(12, c.getInt("flattened_lines"))
        assertTrue(json.getJSONObject("options").getBoolean("flatten_auto"))
        // not checked: null
        val plain = JSONObject(scanTestPageJson("1.0 (1)", true, crop, crop, null, "auto", 60, false, ScanLab(), "page-01.out.jpg", result))
        assertTrue(plain.getJSONObject("result").isNull("flatten_check"))
        val unmeasured = scanFlattenCheckJson(io.github.artcraftandroid.pdfcraft.core.ScanFlattenCheck(false, "unmeasured", null, Float.NaN, 0u, 0u))
        assertTrue(unmeasured.isNull("straightened_px") && unmeasured.isNull("flattened_px"))
    }

    @Test
    fun aKeptPageSaysWhatWasTrimmedAndThePersonsFlattenChoice() {
        val trim = io.github.artcraftandroid.pdfcraft.core.ScanTrim(0.015f, 0f, 0f, 0.02f)
        val json = JSONObject(
            scanTestPageJson("1.0 (1)", true, crop, crop, null, "auto", 60, true, ScanLab(), "page-01.out.jpg", result.copy(trim = trim), flattenChosen = true),
        )
        val t = json.getJSONObject("result").getJSONObject("flatten_trim")
        assertEquals(1.5, t.getDouble("left_pct"), 1e-9)
        assertEquals(2.0, t.getDouble("bottom_pct"), 1e-9)
        assertTrue(json.getBoolean("flatten_chosen"))
        assertTrue(json.getJSONObject("options").getBoolean("flatten_trim"))
        // no trim looked for, and no choice made: null
        val plain = JSONObject(scanTestPageJson("1.0 (1)", true, crop, crop, null, "auto", 60, false, ScanLab(flattenTrim = false), "page-01.out.jpg", result))
        assertTrue(plain.getJSONObject("result").isNull("flatten_trim"))
        assertTrue(plain.isNull("flatten_chosen"))
        assertFalse(plain.getJSONObject("options").getBoolean("flatten_trim"))
    }

    @Test
    fun aKeptPageSaysItsOwnLookAndTheLookOptions() {
        val look = io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(ScanFilter.Whiteboard, 70)
        val json = JSONObject(scanTestPageJson("1.0 (1)", true, crop, null, null, "colour", 70, false, ScanLab(lookStrip = false), null, result, pageLook = look.json(true)))
        val own = json.getJSONObject("page_look")
        // the look's word tells Whiteboard from Auto color, which share the native "colour" look
        assertEquals("whiteboard", own.getString("id"))
        assertEquals(70, own.getInt("strength"))
        assertEquals(70, own.getInt("level"))
        assertTrue(own.getBoolean("per_page"))
        assertTrue(json.getJSONObject("options").getBoolean("look_per_page"))
        assertFalse(json.getJSONObject("options").getBoolean("look_strip"))
        // the look's own strength is no strength in the file, and its level is the look's
        val plain = io.github.artcraftandroid.pdfcraft.ui.scan.ScanPageLook(ScanFilter.LightText).json(false)
        assertTrue(plain.isNull("strength"))
        assertEquals(ScanFilter.LightText.level, plain.getInt("level"))
        // a page from before #301, or discarded without a look, has none
        assertTrue(JSONObject(scanTestPageJson("1.0 (1)", false, crop, null, null, null, null, false, ScanLab(), null, null)).isNull("page_look"))
        // the log's fields stay words and booleans
        val fields = ScanLab(lookPerPage = false).diag().toMap()
        assertEquals(false, fields["look_per_page"])
        assertEquals(true, fields["look_strip"])
    }
}
