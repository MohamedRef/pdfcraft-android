package io.github.artcraftandroid.pdfcraft.ui.scan

import io.github.artcraftandroid.pdfcraft.ui.ScanLab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every page's flattening decision kept for the whole scan (#549). */
class ScanFlattenKeepAllTest {
    @Test
    fun theTestingOptionIsOnAndRemembered() {
        assertTrue(ScanLab().flattenKeepAll)
        val off = ScanLab(flattenKeepAll = false)
        assertFalse(ScanLab.decode(off.encode()).flattenKeepAll)
        assertFalse(off.asDesigned)
        assertEquals(false, off.diag().toMap()["flatten_keep_all"])
        assertEquals(false, off.json().getBoolean("flatten_keep_all"))
        // a phone that saved its options before it has it on
        assertTrue(ScanLab.decode("keep=1;flatten_reuse=1").flattenKeepAll)
    }
}
