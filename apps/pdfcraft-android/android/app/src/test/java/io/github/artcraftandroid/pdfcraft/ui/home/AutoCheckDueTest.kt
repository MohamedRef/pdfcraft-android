package io.github.artcraftandroid.pdfcraft.ui.home

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The daily update check runs at most once a day (#193). */
class AutoCheckDueTest {
    private val day = 24L * 60 * 60 * 1000

    @Test
    fun dueOnTheFirstLaunchAndADayLater() {
        assertTrue(autoCheckDue(lastCheck = 0L, now = 5 * day))
        assertTrue(autoCheckDue(lastCheck = 4 * day, now = 5 * day))
    }

    @Test
    fun notAgainWithinTheSameDay() {
        assertFalse(autoCheckDue(lastCheck = 5 * day, now = 5 * day + day - 1))
    }

    @Test
    fun aClockThatWentBackDoesNotSilenceItForever() {
        assertTrue(autoCheckDue(lastCheck = 9 * day, now = 5 * day))
    }
}
