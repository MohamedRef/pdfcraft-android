package io.github.artcraftandroid.pdfcraft.ui

import io.github.artcraftandroid.pdfcraft.ui.viewer.CertOptions
import io.github.artcraftandroid.pdfcraft.ui.viewer.CertShown
import io.github.artcraftandroid.pdfcraft.ui.viewer.CertifyLevel
import io.github.artcraftandroid.pdfcraft.ui.viewer.certPlacementArgs
import io.github.artcraftandroid.pdfcraft.ui.viewer.signedRevision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/** Sign and Certify's arguments (#178), as `sign_document` takes them. */
class CertOptionsTest {
    private val box = floatArrayOf(10f, 20f, 190f, 74f)

    @Test
    fun visibleBoxSendsPageRectAndAppearance() {
        val a = certPlacementArgs(null, 2, box, CertOptions())
        assertEquals(3, a["page"])
        assertEquals(listOf(10.0, 20.0, 190.0, 74.0), a["rect"])
        assertEquals(
            mapOf("name" to true, "date" to true, "reason" to true, "location" to true, "distinguished_name" to false, "labels" to true),
            a["appearance"],
        )
        assertFalse("certify" in a)
        assertFalse("contact" in a)
    }

    @Test
    fun invisibleSignatureSendsOnlyThePage() {
        // the engine refuses appearance (and a graphic) without a visible box
        val a = certPlacementArgs(null, 0, box, CertOptions(invisible = true, contact = "  "))
        assertEquals(mapOf<String, Any?>("page" to 1), a)
        assertEquals(mapOf<String, Any?>("page" to 1), certPlacementArgs(null, 0, null, CertOptions()))
    }

    @Test
    fun fieldCertifyAndContactPassThrough() {
        val a = certPlacementArgs("Sig1", 0, null, CertOptions(certify = CertifyLevel.NoChanges, invisible = true, shows = CertShown(date = false), contact = " me@example.com "))
        assertEquals("Sig1", a["field"])
        assertNull(a["page"])
        assertEquals("no_changes", a["certify"])
        assertEquals("me@example.com", a["contact"])
        @Suppress("UNCHECKED_CAST")
        assertEquals(false, (a["appearance"] as Map<String, Boolean>)["date"])
    }

    @Test
    fun certifyLevelsMatchTheEngine() {
        assertEquals(listOf("no_changes", "form_fill", "comments"), CertifyLevel.entries.map { it.arg })
    }

    @Test
    fun signedRevisionIsTheOneListingTheField() {
        val revs = listOf(1 to emptyList(), 2 to listOf("Sig1"), 3 to listOf("Sig2"))
        assertEquals(2, signedRevision("Sig1", revs))
        assertEquals(3, signedRevision("Sig2", revs))
        assertNull(signedRevision("Other", revs))
    }
}
