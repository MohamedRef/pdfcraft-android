package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.home.FileRow
import org.junit.Assert.assertEquals
import org.junit.Test

/** Recent and Search list a file once, though Recent kept an old address of it that can't open (#712). */
class EachFileOnceTest {
    private fun row(key: String, size: Long, name: String = "PdfCraft-test-sample.pdf") = FileRow(key, name, null, "Downloads", 0L, size, false) {}

    // the address 0.0.41 opened the file at, whose access was lost (#705), and the one it has now
    private val old = row("content://com.android.providers.downloads.documents/document/raw%3A%2Fstorage%2Femulated%2F0%2FDownload%2FPdfCraft-test-sample.pdf", 10_900)
    private val current = row("content://com.android.providers.downloads.documents/document/msf%3A524", 210_500)
    private val onDevice = row("content://com.android.externalstorage.documents/tree/primary%3ADownload/document/primary%3ADownload%2FPdfCraft-test-sample.pdf", 210_500)

    @Test
    fun searchListsTheFileOnce() {
        // Home › Search said "2 results on this device" for the one file in Download: the old
        // entry has the size the file had then, so name and size did not tell it was the same
        val rows = listOf(onDevice) + eachFileOnce(listOf(current, old), setOf(old.key))
        assertEquals(listOf(onDevice), filesMatching(rows, "test-sample", setOf(old.key)))
    }

    @Test
    fun recentListsTheFileOnce() {
        assertEquals(listOf(current), eachFileOnce(listOf(current, old), setOf(old.key)))
    }

    @Test
    fun searchDropsTheOldEntryBesideTheFileOnTheDeviceAlone() {
        assertEquals(listOf(onDevice), filesMatching(listOf(onDevice, old), "sample", setOf(old.key)))
    }

    @Test
    fun anEntryThatCannotOpenStaysWhenNoOtherRowHasItsName() {
        // opening it says the access was lost and how to open the file again
        assertEquals(listOf(old), eachFileOnce(listOf(old), setOf(old.key)))
        assertEquals(listOf(old), filesMatching(listOf(row("content://other/1", 5, name = "Other.pdf"), old), "test-sample", setOf(old.key)))
    }

    @Test
    fun twoFilesOfOneNameThatBothOpenStay() {
        val elsewhere = row("content://com.android.externalstorage.documents/document/primary%3ADocuments%2FPdfCraft-test-sample.pdf", 4_200)
        assertEquals(listOf(current, elsewhere), eachFileOnce(listOf(current, elsewhere), emptySet()))
        assertEquals(listOf(current, elsewhere), filesMatching(listOf(current, elsewhere), "test-sample", emptySet()))
    }
}
