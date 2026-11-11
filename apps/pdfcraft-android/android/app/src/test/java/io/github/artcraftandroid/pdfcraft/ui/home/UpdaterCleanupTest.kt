package io.github.artcraftandroid.pdfcraft.ui.home

import org.junit.Assert.assertEquals
import org.junit.Test

/** Update files left behind after an update (#109). */
class UpdaterCleanupTest {
    private val sha = "a".repeat(64)
    private val other = "b".repeat(64)

    @Test
    fun installedOrOlderApksAreDeletedNewerOnesKept() {
        assertEquals(UpdateFileAction.DELETE, updateFileAction("pdfcraft-android-0.0.24.apk", legacy = false, archiveVersionCode = 242, installed = 242, keepSha256 = null))
        assertEquals(UpdateFileAction.DELETE, updateFileAction("pdfcraft-android-0.0.23.apk", legacy = false, archiveVersionCode = 232, installed = 242, keepSha256 = null))
        assertEquals(UpdateFileAction.KEEP, updateFileAction("pdfcraft-android-0.0.25.apk", legacy = false, archiveVersionCode = 252, installed = 242, keepSha256 = null))
        // unreadable: can never be installed
        assertEquals(UpdateFileAction.DELETE, updateFileAction("pdfcraft-android-0.0.25.apk", legacy = false, archiveVersionCode = null, installed = 242, keepSha256 = null))
    }

    @Test
    fun aNewerApkInTheOldFolderMovesToTheSharedOne() {
        assertEquals(UpdateFileAction.MOVE_TO_VERIFIED, updateFileAction("pdfcraft-android-0.0.25.apk", legacy = true, archiveVersionCode = 252, installed = 242, keepSha256 = null))
        assertEquals(UpdateFileAction.DELETE, updateFileAction("pdfcraft-android-0.0.24.apk", legacy = true, archiveVersionCode = 242, installed = 242, keepSha256 = null))
    }

    @Test
    fun partialsAreKeptUntilACheckShowsTheyAreSuperseded() {
        val part = "download-$sha.part"
        assertEquals(UpdateFileAction.KEEP, updateFileAction(part, legacy = true, archiveVersionCode = null, installed = 242, keepSha256 = null))
        assertEquals(UpdateFileAction.KEEP, updateFileAction(part, legacy = true, archiveVersionCode = null, installed = 242, keepSha256 = sha))
        assertEquals(UpdateFileAction.DELETE, updateFileAction(part, legacy = true, archiveVersionCode = null, installed = 242, keepSha256 = other))
        // the check found no update at all
        assertEquals(UpdateFileAction.DELETE, updateFileAction(part, legacy = true, archiveVersionCode = null, installed = 242, keepSha256 = ""))
        assertEquals(UpdateFileAction.KEEP, updateFileAction("notes.txt", legacy = true, archiveVersionCode = null, installed = 242, keepSha256 = ""))
    }
}
