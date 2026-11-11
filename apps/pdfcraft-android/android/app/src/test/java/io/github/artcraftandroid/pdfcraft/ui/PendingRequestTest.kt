package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingRequestTest {
    @Test fun resultGoesToTheRequestWaitingForIt() {
        val slot = PendingRequest<String>()
        assertNull(slot.start("document"))
        assertEquals("document", slot.take())
        assertNull(slot.take())
    }

    @Test fun aRequestWhosePickerNeverReturnedCannotTakeALaterResult() {
        // #104: a file row's Save a copy failed to launch; the document's copy comes next
        val slot = PendingRequest<String>()
        slot.start("row")
        assertEquals("row", slot.take()) // launch failure clears it
        slot.start("document")
        assertEquals("document", slot.take())
    }

    @Test fun aNewRequestDisplacesTheOldOneForTheCallerToResolve() {
        val slot = PendingRequest<String>()
        slot.start("row")
        assertEquals("row", slot.start("document"))
        assertEquals("document", slot.take())
    }

    @Test fun aResultWithNoRequestFindsNothing() {
        // after process death the restored activity delivers a result to a fresh slot
        assertNull(PendingRequest<String>().take())
    }
}
