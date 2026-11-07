package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CancellationException

class BatesBatchTest {
    @Test fun settingsValidateNativeByteBoundsAndNumericPrecision() {
        assertEquals("PRE000042Z", BatesSettings.parse("42", "6", "PRE", "Z")!!.preview)
        for (n in listOf("0", "-1", "1000000000000", "9223372036854775808", "1.5")) assertNull(BatesSettings.parse(n, "6", "", ""))
        for (d in listOf("0", "13", "-1")) assertNull(BatesSettings.parse("1", d, "", ""))
        assertNotNull(BatesSettings.parse("1", "12", "أ".repeat(64), ""))
        for (p in listOf("أ".repeat(65), "#", ">>", "\uD800", "\uDC00")) assertNull(BatesSettings.parse("1", "6", p, ""))
    }

    @Test fun reportsMustCoverEverySourceWithExactConsecutiveRanges() {
        val valid = listOf(BatesInterval(2, 42, 43), BatesInterval(1, 44, 44))
        validateBatesIntervals(valid, 2, 42)
        for (rows in listOf(valid.take(1), valid.reversed(), listOf(valid[0], BatesInterval(1, 45, 45)), listOf(BatesInterval(2, 42, 44), valid[1]))) {
            assertThrows(IllegalArgumentException::class.java) { validateBatesIntervals(rows, 2, 42) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            validateBatesIntervals(listOf(BatesInterval(1, Long.MAX_VALUE, Long.MAX_VALUE), BatesInterval(1, Long.MIN_VALUE, Long.MIN_VALUE)), 2, Long.MAX_VALUE)
        }
    }

    @Test fun cancellationAfterWritingDeletesOnlyTheNewPendingEntry() {
        val events = mutableListOf<String>()
        var cancelled = false
        assertThrows(CancellationException::class.java) {
            publishNewBatchCopy(
                reserve = { events += "reserve:new"; "new" },
                write = { events += "write:$it"; cancelled = true },
                commit = { events += "commit:$it" },
                rollback = { events += "delete:$it" },
                checkpoint = { if (cancelled) throw CancellationException() },
            )
        }
        assertEquals(listOf("reserve:new", "write:new", "delete:new"), events)
    }

    @Test fun publicationFailureRetainsPrimaryErrorAndCleanupEvidence() {
        val original = IllegalStateException("publication failed")
        val thrown = assertThrows(IllegalStateException::class.java) {
            publishNewBatchCopy({ "owned-row" }, {}, { throw original }, { throw IllegalArgumentException("cleanup failed") }, {})
        }
        assertSame(original, thrown)
        assertEquals("cleanup failed", thrown.suppressed.single().message)
        assertEquals("new", publishNewBatchCopy({ "new" }, {}, {}, { fail("must not delete published copy") }, {}))
    }
}
