package io.github.artcraftandroid.pdfcraft.ui.scan

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which engine runs, what a failure does, and the comparison record (#307, spec S21). */
class ScanEngineChoiceTest {

    @Test
    fun theOwnEngineIsTheDefaultAndTheIdsAreTheStoredWords() {
        assertEquals(ScanEngine.Own, ScanEngine.fromId(null))
        assertEquals(ScanEngine.Own, ScanEngine.fromId(""))
        assertEquals(ScanEngine.Own, ScanEngine.fromId("some other"))
        assertEquals(ScanEngine.Google, ScanEngine.fromId("google"))
        assertEquals("own", ScanEngine.Own.id)
        assertEquals("google", ScanEngine.Google.id)
    }

    @Test
    fun googleRunsOnlyWhenChosenAndPlayServicesIsThere() {
        assertEquals(ScanEngine.Own, engineFor(ScanEngine.Own, playServices = true))
        assertEquals(ScanEngine.Own, engineFor(ScanEngine.Own, playServices = false))
        assertEquals(ScanEngine.Google, engineFor(ScanEngine.Google, playServices = true))
        assertEquals(ScanEngine.Own, engineFor(ScanEngine.Google, playServices = false))
    }

    @Test
    fun aDeviceTooSmallForGoogleSwitchesTheSettingBack() {
        assertEquals(GoogleStartAction.SwitchBackWithNotice, onGoogleStartFailure(GoogleStartFailure.Unsupported))
        assertEquals("switch_back_notice", GoogleStartAction.SwitchBackWithNotice.id)
    }

    @Test
    fun anotherStartFailureOpensPdfCraftsCameraWithANotice() {
        assertEquals(GoogleStartAction.OwnCameraWithNotice, onGoogleStartFailure(GoogleStartFailure.Other))
        assertEquals("own_camera_notice", GoogleStartAction.OwnCameraWithNotice.id)
        assertEquals("unsupported", GoogleStartFailure.Unsupported.id)
        assertEquals("other", GoogleStartFailure.Other.id)
    }

    @Test
    fun aCancelledResultIsCancelledWhateverItHolds() {
        for (returned in 0..3) {
            for (copied in 0..returned) {
                assertEquals(GoogleResultAction.Cancelled, onGoogleResult(resultOk = false, pagesReturned = returned, pagesCopied = copied))
            }
        }
    }

    @Test
    fun aResultIsAddedOnlyWhenEveryReturnedPageWasCopied() {
        for (returned in 0..3) {
            for (copied in 0..returned) {
                val expected = if (returned > 0 && copied == returned) GoogleResultAction.AddPages else GoogleResultAction.Failed
                assertEquals("returned $returned copied $copied", expected, onGoogleResult(resultOk = true, pagesReturned = returned, pagesCopied = copied))
            }
        }
        assertEquals(GoogleResultAction.AddPages, onGoogleResult(true, 1, 1))
        assertEquals(GoogleResultAction.Failed, onGoogleResult(true, 0, 0))
        assertEquals(GoogleResultAction.Failed, onGoogleResult(true, 3, 2))
        assertEquals("add_pages", GoogleResultAction.AddPages.id)
        assertEquals("cancelled", GoogleResultAction.Cancelled.id)
        assertEquals("failed", GoogleResultAction.Failed.id)
    }

    @Test
    fun theDefaultOptionsAskForFullModeWithGalleryAndNoPageLimit() {
        val options = GoogleScanOptions()
        assertNull(options.pageLimit)
        assertTrue(options.galleryImportAllowed)
        assertEquals(GoogleScanMode.Full, options.mode)
        assertEquals("full", GoogleScanMode.Full.id)
        assertEquals("base", GoogleScanMode.Base.id)
        assertEquals("base_with_filter", GoogleScanMode.BaseWithFilter.id)
    }

    @Test
    fun aComparisonRecordIsValidOnlyForAThreeWayRankAndANonNegativeDocument() {
        val good = ScanComparisonRecord(document = 0, engine = ScanEngine.Google, cornersAdjusted = false, rank = 1, fault = ComparisonFault.None)
        assertTrue(good.valid)
        assertFalse(good.copy(rank = 0).valid)
        assertFalse(good.copy(rank = 4).valid)
        assertFalse(good.copy(document = -1).valid)
        assertTrue(good.copy(rank = 3).valid)
    }

    @Test
    fun aComparisonRecordGoesToJsonAsNumbersBooleansAndShortWords() {
        val record = ScanComparisonRecord(document = 3, engine = ScanEngine.Google, cornersAdjusted = true, rank = 2, fault = ComparisonFault.Edges)
        val json: JSONObject = record.json()
        assertEquals(3, json.getInt("document"))
        assertEquals("google", json.getString("engine"))
        assertTrue(json.getBoolean("corners_adjusted"))
        assertEquals(2, json.getInt("rank"))
        assertEquals("edges", json.getString("fault"))
        val keys = json.keys().asSequence().toList().sorted()
        assertEquals(listOf("corners_adjusted", "document", "engine", "fault", "rank"), keys)
        val wordPattern = Regex("[a-z_]+")
        for (key in keys) {
            val value = json.get(key)
            val plainWord = value is String && wordPattern.matches(value)
            assertTrue("$key holds $value", value is Number || value is Boolean || plainWord)
        }
    }

    @Test
    fun everyComparisonFaultIsAShortLowerCaseWord() {
        val pattern = Regex("[a-z]+")
        for (fault in ComparisonFault.entries) {
            assertTrue(fault.id, pattern.matches(fault.id))
        }
        assertEquals(6, ComparisonFault.entries.size)
    }
}
