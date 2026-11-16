package de.wanderlogplus.app.ui.trip

import org.junit.Assert.assertEquals
import org.junit.Test

class DayFlowUiTest {
    @Test fun legQueryTimeRoundsToTheHour() {
        assertEquals("13:00", legQueryTime(13 * 60 + 47))
        assertEquals("09:00", legQueryTime(9 * 60))
        assertEquals("01:00", legQueryTime(25 * 60 + 5))
    }
}
