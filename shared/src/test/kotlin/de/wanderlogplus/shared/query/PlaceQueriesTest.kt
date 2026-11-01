package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaceQueriesTest {
    private val monday = listOf(OpeningPeriod(1, "09:00", 1, "17:00"))

    @Test fun openAtSameDay() {
        assertEquals(true, isOpenAt(monday, 1, "10:00"))
        assertEquals(false, isOpenAt(monday, 1, "18:00"))
        assertEquals(false, isOpenAt(monday, 2, "10:00"))
        assertEquals(true, isOpenOn(monday, 1))
        assertEquals(false, isOpenOn(monday, 2))
    }

    @Test fun openPastMidnight() {
        val fri = listOf(OpeningPeriod(5, "18:00", 6, "02:00"))
        assertEquals(true, isOpenAt(fri, 6, "01:00"))
        assertEquals(true, isOpenAt(fri, 5, "23:00"))
        assertEquals(false, isOpenAt(fri, 6, "03:00"))
        assertEquals(true, isOpenOn(fri, 6))
    }

    @Test fun alwaysOpen() {
        val p = listOf(OpeningPeriod(0, "00:00", null, null))
        for (d in 0..6) assertEquals(true, isOpenAt(p, d, "13:00"))
    }

    @Test fun unknownWithoutPeriods() {
        assertNull(isOpenAt(emptyList(), 1, "10:00"))
        assertNull(isOpenOn(emptyList(), 1))
    }

    @Test fun tagRanks() {
        assertEquals(TagRank.STAR, tagRank(listOf("🌟")))
        assertEquals(TagRank.WARNING, tagRank(listOf("⚠️")))
        assertEquals(TagRank.STAR, tagRank(listOf("⭐", "⚠️")))
        assertEquals(TagRank.NEUTRAL, tagRank(listOf("x")))
    }

    private fun sug(id: String, tag: String?, rating: Double?, cat: PlaceCategory = PlaceCategory.SIGHT, periods: List<OpeningPeriod> = emptyList()) =
        Suggestion(SavedPlace(id, "p$id", category = cat, tags = listOfNotNull(tag)), Place("p$id", name = id, rating = rating, openingPeriods = periods))

    @Test fun sorting() {
        val list = listOf(sug("w", "⚠️", 4.9), sug("n4", null, 4.0), sug("s", "⭐", 3.0), sug("nn", null, null))
        assertEquals(listOf("s", "n4", "nn", "w"), sortSuggestions(list).map { it.saved.id })
    }

    @Test fun filtering() {
        val list = listOf(
            sug("food", null, 1.0, PlaceCategory.FOOD, monday),
            sug("sight", null, 1.0, PlaceCategory.SIGHT, monday),
            sug("unknown", null, 1.0, PlaceCategory.FOOD),
        )
        assertEquals(listOf("food", "unknown"), filterSuggestions(list, PlaceCategory.FOOD, null).map { it.saved.id })
        assertEquals(listOf("unknown"), filterSuggestions(list, null, 2).map { it.saved.id })
        assertEquals(3, filterSuggestions(list, null, 1).size)
    }
}
