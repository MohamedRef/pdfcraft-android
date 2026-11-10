package io.github.artcraftandroid.pdfcraft

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalOpenPolicyTest {
    private val view = "android.intent.action.VIEW"
    private val send = "android.intent.action.SEND"
    private val multiple = "android.intent.action.SEND_MULTIPLE"

    @Test fun knownExternalActionsCanOpenContentProviderUris() {
        val content = IncomingPdfUri("content", "com.android.providers.downloads.documents", "/document/32")
        for (action in listOf(view, send, multiple)) {
            assertTrue(acceptableExternalPdfUri(action, content))
        }
    }

    @Test fun validFileUriWithAbsolutePathMayOpen() {
        assertTrue(acceptableExternalPdfUri(view, IncomingPdfUri("file", null, "/storage/emulated/0/Download/notes.pdf")))
        assertTrue(acceptableExternalPdfUri(view, IncomingPdfUri("FILE", null, "/storage/emulated/0/Download/notes.pdf")))
    }

    @Test fun hostileOrUnknownUrisAreRejected() {
        val valid = IncomingPdfUri("content", "cloud.files", "/pdf")
        for (action in listOf(null, "", "android.intent.action.MAIN", "android.intent.action.EDIT")) {
            assertFalse(acceptableExternalPdfUri(action, valid))
        }
        for (uri in listOf(
            IncomingPdfUri(null, null, null),
            IncomingPdfUri("content", null, "/doc"),
            IncomingPdfUri("content", "", "/doc"),
            IncomingPdfUri("https", "example.com", "/file.pdf"),
            IncomingPdfUri("javascript", null, "/bad"),
            IncomingPdfUri("file", null, "../relative.pdf"),
            IncomingPdfUri("file", null, null),
        )) {
            assertFalse(acceptableExternalPdfUri(view, uri))
        }
    }

    @Test fun coldAndRecreatedActivityOnlyRouteWhenNoLiveOpenExists() {
        assertTrue(shouldRouteInitialPdfIntent(false, false, false, false))
        assertTrue(shouldRouteInitialPdfIntent(true, false, false, false))
        assertFalse(shouldRouteInitialPdfIntent(true, true, false, false))
        assertFalse(shouldRouteInitialPdfIntent(true, false, true, false))
        assertFalse(shouldRouteInitialPdfIntent(true, false, false, true))
        // A retained encrypted PDF must not be opened again merely because of rotation.
        assertFalse(shouldRouteInitialPdfIntent(true, false, false, true))
        // A genuine process death has no retained document or dialog; replay the original intent.
        assertTrue(shouldRouteInitialPdfIntent(true, false, false, false))
    }

    @Test fun supersededProviderFailureCannotResurrectAnOlderOpen() {
        // Request A starts, then B arrives while A's provider/cache copy suspends.
        val requestA = 41L
        val requestB = 42L
        assertTrue(canProceedWithPdfOpen(requestA, requestA, false, false))
        assertFalse(canProceedWithPdfOpen(requestA, requestB, false, false))
        assertTrue(canProceedWithPdfOpen(requestB, requestB, false, false))
        // Neither an unresolved nor an actively resolving save choice allows replacement.
        assertFalse(canProceedWithPdfOpen(requestB, requestB, true, false))
        assertFalse(canProceedWithPdfOpen(requestB, requestB, false, true))
        assertFalse(canProceedWithPdfOpen(requestA, requestB, true, true))
    }

    @Test fun clipFallbackDoesNotLetBrokenFirstItemMaskValidPdf() {
        val candidates = listOf(
            IncomingPdfUri("https", "example.com", "/pdf"),
            IncomingPdfUri("content", "", "/missing-authority"),
            IncomingPdfUri("content", "com.samsung.android.myfiles", "/document/42"),
            IncomingPdfUri("content", "another.provider", "/document/43"),
        )
        assertEquals(2, firstExternalPdfCandidate(view, candidates))
        assertEquals(2, firstExternalPdfCandidate(send, candidates))
        assertEquals(2, firstExternalPdfCandidate(multiple, candidates))
        assertNull(firstExternalPdfCandidate("invalid-action", candidates))
        assertNull(firstExternalPdfCandidate(view, candidates.take(2)))
        assertNull(firstExternalPdfCandidate(view, emptyList()))
    }
}

class PrivateFileIntentTest {
    private val view = "android.intent.action.VIEW"
    private val roots = listOf("/data/user/0/io.github.example", "/storage/emulated/0/Android/data/io.github.example")

    @org.junit.Test fun anotherAppCannotOpenPdfcraftsPrivateFiles() {
        val private = "/data/user/0/io.github.example/cache/share/1/x.pdf"
        org.junit.Assert.assertFalse(acceptableExternalPdfUri(view, IncomingPdfUri("file", null, private, private), roots))
        val external = "/storage/emulated/0/Android/data/io.github.example/files/a.pdf"
        org.junit.Assert.assertFalse(acceptableExternalPdfUri(view, IncomingPdfUri("file", null, external, external), roots))
    }

    @org.junit.Test fun aClimbIntoPrivateStorageIsResolvedFirst() {
        val sneaky = "/storage/emulated/0/Download/../../../../data/user/0/io.github.example/files/a.pdf"
        org.junit.Assert.assertFalse(acceptableExternalPdfUri(view, IncomingPdfUri("file", null, sneaky, "/data/user/0/io.github.example/files/a.pdf"), roots))
        org.junit.Assert.assertFalse(acceptableExternalPdfUri(view, IncomingPdfUri("file", null, sneaky, null), roots))
    }

    @org.junit.Test fun publicFilesAndLookalikeFoldersStillOpen() {
        val pub = "/storage/emulated/0/Download/notes.pdf"
        org.junit.Assert.assertTrue(acceptableExternalPdfUri(view, IncomingPdfUri("file", null, pub, pub), roots))
        val lookalike = "/data/user/0/io.github.example2/files/a.pdf"
        org.junit.Assert.assertTrue(acceptableExternalPdfUri(view, IncomingPdfUri("file", null, lookalike, lookalike), roots))
    }

    @org.junit.Test fun pathWithinRespectsSeparators() {
        org.junit.Assert.assertTrue(pathWithin("/a/files/x", "/a/files"))
        org.junit.Assert.assertTrue(pathWithin("/a/files", "/a/files/"))
        org.junit.Assert.assertFalse(pathWithin("/a/files_x/y", "/a/files"))
        org.junit.Assert.assertFalse(pathWithin("/x", ""))
    }
}

class AttachmentPolicyTest {
    @org.junit.Test fun appPackagesAreNeverHandedOut() {
        val f = io.github.artcraftandroid.pdfcraft.ui.viewer::installableAttachment
        org.junit.Assert.assertTrue(f("invoice.apk", null))
        org.junit.Assert.assertTrue(f("Invoice.APK", null))
        org.junit.Assert.assertTrue(f("bundle.xapk", null))
        org.junit.Assert.assertTrue(f("trailing.apk. ", null))
        org.junit.Assert.assertTrue(f("noext", "application/vnd.android.package-archive"))
        org.junit.Assert.assertFalse(f("photo.jpg", "image/jpeg"))
        org.junit.Assert.assertFalse(f("report.docx", null))
    }
}
