package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Engine refusals are English; outside English a translated summary comes first (#118). */
class EngineFailureMessageTest {
    @Test fun inEnglishTheEngineReasonIsTheMessage() {
        assertEquals("page 3 is out of range", engineFailure("page 3 is out of range", english = true, summary = "S"))
    }

    @Test fun inAnotherLanguageTheSummaryLeadsAndTheReasonFollows() {
        assertEquals("تعذّر\npage 3 is out of range", engineFailure("page 3 is out of range", english = false, summary = "تعذّر"))
    }

    @Test fun withoutAReasonOnlyTheSummaryIsSaid() {
        assertEquals("S", engineFailure(null, english = true, summary = "S"))
        assertEquals("S", engineFailure(null, english = false, summary = "S"))
    }
}
