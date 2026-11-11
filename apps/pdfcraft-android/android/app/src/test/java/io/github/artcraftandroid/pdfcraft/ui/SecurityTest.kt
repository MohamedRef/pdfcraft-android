package io.github.artcraftandroid.pdfcraft.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SecurityTest {
    @Test fun openPasswordAloneRestrictsNothing() {
        assertEquals(mapOf("open_password" to "secret1"), protectArgs("secret1", null, Restrictions()))
    }

    @Test fun permissionsPasswordCarriesTheRestrictions() {
        assertEquals(
            mapOf("permissions_password" to "owner12", "printing" to "none", "changes" to "none", "copy" to false, "accessibility" to true),
            protectArgs("", "owner12", Restrictions(printing = false)),
        )
        assertEquals(
            mapOf("open_password" to "user123", "permissions_password" to "owner12", "printing" to "high", "changes" to "any-except-extract", "copy" to true, "accessibility" to false),
            protectArgs("user123", "owner12", Restrictions(changes = true, copy = true, accessibility = false)),
        )
    }

    @Test fun refusesWhatTheEngineWouldRefuse() {
        assertNull(protectArgs("", null, Restrictions()))
        assertNull(protectArgs("", "", Restrictions()))
        assertNull(protectArgs("short", null, Restrictions()))
        assertNull(protectArgs("same123", "same123", Restrictions()))
        assertNull(protectArgs("good123", "has space", Restrictions()))
    }
}
