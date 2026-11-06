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
        assertEquals("Do 20.05.", dayCardLabel("2027-05-20"))
        assertEquals("kaputt", dayCardLabel("kaputt"))
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
        assertEquals(fallback, regionColor("#+12345"))
        assertEquals(fallback, regionColor("#12345g"))
        assertEquals(fallback, regionColor("#1234567"))
        assertEquals(Color(0x803498DB), regionColor("#803498db"))
        assertNotEquals(fallback, regionColor("#3498db"))
    }

    @Test fun dayKickerNumbersOnlyDaysInsideTheTrip() {
        // 2027-05-20 is a Thursday; trip 2027-05-08 .. 2027-05-25.
        assertEquals("Do · 20. Mai · Tag 13", dayKicker("2027-05-20", "2027-05-08", "2027-05-25"))
        assertEquals("Sa · 8. Mai · Tag 1", dayKicker("2027-05-08", "2027-05-08", "2027-05-25"))
        assertEquals("Di · 25. Mai · Tag 18", dayKicker("2027-05-25", "2027-05-08", "2027-05-25"))
        // Before the start (would be "Tag 0" / negative) and after the end: no day number.
        assertEquals("Fr · 7. Mai", dayKicker("2027-05-07", "2027-05-08", "2027-05-25"))
        assertEquals("Mo · 3. Mai", dayKicker("2027-05-03", "2027-05-08", "2027-05-25"))
        assertEquals("Mi · 26. Mai", dayKicker("2027-05-26", "2027-05-08", "2027-05-25"))
        // Missing/unparsable start or end; unparsable date.
        assertEquals("Do · 20. Mai", dayKicker("2027-05-20", null, "2027-05-25"))
        assertEquals("Do · 20. Mai · Tag 13", dayKicker("2027-05-20", "2027-05-08", ""))
        assertEquals("Fr · 7. Mai", dayKicker("2027-05-07", "2027-05-08", null))
        assertEquals("kaputt", dayKicker("kaputt", "2027-05-08", "2027-05-25"))
    }

    @Test fun shortlistIsVorgemerkt() {
        org.junit.Assert.assertEquals("Vorgemerkt", statusLabel(de.wanderlogplus.shared.model.PlaceStatus.SHORTLIST))
        org.junit.Assert.assertEquals(
            listOf("IDEA", "SHORTLIST", "PLANNED", "VISITED", "DROPPED"),
            de.wanderlogplus.shared.model.PlaceStatus.entries.map { it.name },
        )
    }
}
