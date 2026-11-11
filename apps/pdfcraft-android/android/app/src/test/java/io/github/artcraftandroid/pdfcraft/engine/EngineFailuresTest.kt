package io.github.artcraftandroid.pdfcraft.engine

import io.github.artcraftandroid.pdfcraft.core.InternalException
import io.github.artcraftandroid.pdfcraft.core.MobileException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** An engine panic must reach the app's `catch (e: MobileException)` handlers, not crash it (#114). */
class EngineFailuresTest {
    @Test fun anEnginePanicBecomesAMobileExceptionWithAMessage() {
        val e = assertThrows(MobileException.Command::class.java) {
            engineFailuresAsMobile<Unit> { throw InternalException("panicked at crates/cos/src/lib.rs") }
        }
        assertTrue(e.message.orEmpty().contains("internal error"))
    }

    @Test fun mobileExceptionsAndResultsPassThroughUnchanged() {
        assertEquals(7, engineFailuresAsMobile { 7 })
        val refused = MobileException.WrongPassword("the password is incorrect")
        assertSame(refused, assertThrows(MobileException.WrongPassword::class.java) { engineFailuresAsMobile<Unit> { throw refused } })
    }
}
