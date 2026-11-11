package io.github.artcraftandroid.pdfcraft.ui.home

import io.github.artcraftandroid.pdfcraft.R
import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateExceptionTest {
    @Test fun keepsTheTranslatedResourceAndTheEnglishMessage() {
        val cause = IOException("disk")
        val e = UpdateException(R.string.updater_err_storage, "Could not save the verified APK.", cause)
        assertEquals(R.string.updater_err_storage, e.messageRes)
        assertEquals("Could not save the verified APK.", e.message)
        assertSame(cause, e.cause)
    }

    @Test fun isAnIOExceptionSoDownloadRetriesStillTreatItAsFinal() {
        val e: Throwable = UpdateException(R.string.updater_err_checksum, "checksum")
        assertTrue(e is IOException)
        assertEquals(false, apkRetryable(e as IOException))
    }
}
