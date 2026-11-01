package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TripQueriesTest {
    private val trip = Trip("trip", startDate = "2027-05-08", endDate = "2027-05-29")
    private val regions = listOf(
        Region("t", "Tokio"),
        Region("o", "Osaka"),
        Region("k", "Kyoto", dayTripFromRegionId = "o"),
    )
    private val stays = listOf(
        Stay("s1", regionId = "t", checkIn = "2027-05-09", checkOut = "2027-05-13"),
        Stay("s2", regionId = "o", checkIn = "2027-05-17", checkOut = "2027-05-22"),
        Stay("s3", regionId = "t", checkIn = "2027-05-28", checkOut = "2027-05-29"),
    )

    private fun queries(
        days: List<Day> = emptyList(),
        places: List<SavedPlace> = emptyList(),
        items: List<DayItem> = emptyList(),
    ) = TripQueries(trip, stays, days, regions, places, items)

    @Test
    fun `days and regionFor agree when several days share a date`() {
        val q = queries(
            days = listOf(
                Day("d1", date = "2027-05-10", regionOverrideId = "o"),
                Day("d2", date = "2027-05-10", regionOverrideId = "k"),
            ),
        )
        val view = q.days().first { it.date == "2027-05-10" }
        assertEquals(view.day?.regionOverrideId, q.regionFor("2027-05-10"))
        assertEquals(view.regionId, q.regionFor("2027-05-10"))
    }

    @Test
    fun `dayFor picks the largest id deterministically`() {
        val a = Day("d1", date = "2027-05-10")
        val b = Day("d2", date = "2027-05-10")
        assertEquals("d2", queries(days = listOf(a, b)).dayFor("2027-05-10")?.id)
        assertEquals("d2", queries(days = listOf(b, a)).dayFor("2027-05-10")?.id)
        assertNull(queries(days = listOf(a)).dayFor("2027-05-11"))
    }

    @Test
    fun `days covers whole trip`() {
        val d = queries().days()
        assertEquals(22, d.size)
        assertEquals("2027-05-08", d.first().date)
        assertEquals("2027-05-29", d.last().date)
    }

    @Test
    fun `arrival day before first stay has no region`() {
        val q = queries()
        assertNull(q.regionFor("2027-05-08"))
        assertTrue(q.days().first().isTravelDay)
    }

    @Test
    fun `check-in day belongs to new stay`() {
        assertEquals("o", queries().regionFor("2027-05-17"))
    }

    @Test
    fun `last day falls back to last stay`() {
        assertEquals("t", queries().regionFor("2027-05-29"))
    }

    @Test
    fun `gap between stays`() {
        assertNull(queries().regionFor("2027-05-14"))
    }

    @Test
    fun `override wins`() {
        val q = queries(days = listOf(Day("d", date = "2027-05-20", regionOverrideId = "k")))
        assertEquals("k", q.regionFor("2027-05-20"))
        assertEquals("d", q.days().first { it.date == "2027-05-20" }.day?.id)
    }

    @Test
    fun `derivedRegionFor ignores override`() {
        val q = queries(days = listOf(Day("d", date = "2027-05-20", regionOverrideId = "k")))
        assertEquals("o", q.derivedRegionFor("2027-05-20"))
        assertEquals("k", q.regionFor("2027-05-20"))
        assertNull(q.derivedRegionFor("2027-05-08"))
    }

    @Test
    fun `travel day on check-in and check-out`() {
        val byDate = queries().days().associateBy { it.date }
        assertTrue(byDate.getValue("2027-05-09").isTravelDay)
        assertTrue(byDate.getValue("2027-05-13").isTravelDay)
        assertFalse(byDate.getValue("2027-05-10").isTravelDay)
    }

    @Test
    fun `suggestions include day-trip regions and exclude planned`() {
        val places = listOf(
            SavedPlace("a", placeId = "pa", regionId = "o"),
            SavedPlace("b", placeId = "pb", regionId = "o"),
            SavedPlace("c", placeId = "pc", regionId = "o", status = PlaceStatus.VISITED),
            SavedPlace("d", placeId = "pd", regionId = "k"),
            SavedPlace("e", placeId = "pe", regionId = "t"),
        )
        val items = listOf(DayItem("i", dayId = "d", kind = DayItemKind.PLACE, placeId = "pb"))
        val result = queries(places = places, items = items).suggestionsFor("2027-05-19")
        assertEquals(setOf("a", "d"), result.map { it.id }.toSet())
    }

    @Test
    fun `no suggestions without region`() {
        val places = listOf(SavedPlace("a", placeId = "pa", regionId = "o"))
        assertTrue(queries(places = places).suggestionsFor("2027-05-08").isEmpty())
    }

    @Test
    fun emptyStayDatesIgnored() {
        val q = TripQueries(
            trip,
            listOf(Stay("bad", regionId = "o", checkIn = "", checkOut = "x"), stays[0]),
            emptyList(), regions, emptyList(), emptyList(),
        )
        assertEquals("t", q.regionFor("2027-05-10"))
        assertEquals(22, q.days().size)
    }

    @Test
    fun emptyTripDates() {
        val q = TripQueries(Trip("t"), stays, emptyList(), regions, emptyList(), emptyList())
        assertTrue(q.days().isEmpty())
    }
}
