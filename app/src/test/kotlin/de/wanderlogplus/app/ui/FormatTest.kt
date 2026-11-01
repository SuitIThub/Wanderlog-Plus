package de.wanderlogplus.app.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FormatTest {
    @Test fun dayLabelIsGermanShortWeekday() {
        assertEquals("So, 09.05.", dayLabel("2027-05-09"))
        assertEquals("Sa, 01.01.", dayLabel("2028-01-01"))
        assertEquals("kaputt", dayLabel("kaputt"))
    }

    @Test fun relativeAgoBoundaries() {
        val now = 10_000_000_000L
        assertEquals("gerade eben", relativeAgo(now - 59_999, now))
        assertEquals("gerade eben", relativeAgo(now + 5_000, now))
        assertEquals("vor 1 Min.", relativeAgo(now - 60_000, now))
        assertEquals("vor 59 Min.", relativeAgo(now - 59 * 60_000L, now))
        assertEquals("vor 1 Std.", relativeAgo(now - 60 * 60_000L, now))
        assertEquals("vor 23 Std.", relativeAgo(now - 23 * 3_600_000L - 59 * 60_000L, now))
        assertEquals("vor 1 Tag", relativeAgo(now - 24 * 3_600_000L, now))
        assertEquals("vor 1 Tag", relativeAgo(now - 47 * 3_600_000L, now))
        assertEquals("vor 2 Tagen", relativeAgo(now - 48 * 3_600_000L, now))
    }

    @Test fun regionColorParsesHexAndFallsBack() {
        assertEquals(Color(0xFF3498DB), regionColor("#3498db"))
        val fallback = regionColor(null)
        assertEquals(fallback, regionColor("nope"))
        assertEquals(fallback, regionColor("#12"))
        assertEquals(fallback, regionColor("#zzzzzz"))
        assertNotEquals(fallback, regionColor("#3498db"))
    }
}
