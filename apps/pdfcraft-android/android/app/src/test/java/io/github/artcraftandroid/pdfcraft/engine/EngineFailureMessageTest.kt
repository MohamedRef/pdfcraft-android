package io.github.artcraftandroid.pdfcraft.engine

import org.junit.Assert.assertEquals
import org.junit.Test

/** Engine refusals are English; outside English a translated summary comes first (#118). */
class EngineFailureMessageTest {
    @Test fun inEnglishTheEngineReasonIsTheMessage() {
        assertEquals("page 3 is out of range", engineFailure("page 3 is out of range", english = true) { error("the summary is not read in English") })
    }

    @Test fun inAnotherLanguageTheSummaryLeadsAndTheReasonFollows() {
        assertEquals("تعذّر\npage 3 is out of range", engineFailure("page 3 is out of range", english = false) { "تعذّر" })
    }

    @Test fun withoutAReasonOnlyTheSummaryIsSaid() {
        assertEquals("S", engineFailure(null, english = true) { "S" })
        assertEquals("S", engineFailure(null, english = false) { "S" })
    }
}
