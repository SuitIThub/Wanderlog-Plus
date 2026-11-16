package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.OpeningPeriod
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Train
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class DayScheduleTest {
    private val date = "2027-05-20" // a Thursday
    private val hotel = Place("h", name = "Hotel", lat = 35.000, lng = 135.780)
    private val a = Place("a", name = "A", lat = 35.009, lng = 135.780, minMinutes = 60, maxMinutes = 60)
    private val b = Place("b", name = "B", lat = 35.018, lng = 135.780, minMinutes = 60, maxMinutes = 60)
    private val c = Place("c", name = "C", lat = 35.027, lng = 135.780, minMinutes = 60, maxMinutes = 60)
    private val places = listOf(hotel, a, b, c).associateBy { it.id }
    private val stay = Stay("st", "h", checkIn = "2027-05-19", checkOut = "2027-05-22")

    private fun item(p: String, fixed: String? = null, stay: Int? = null) = DayItem("i" + p.uppercase(), "d", p, placeId = p, fixedTime = fixed, stayMinutes = stay)
    private fun way(from: Place, to: Place) = DayNow.way(DayNow.km(from.lat, from.lng, to.lat, to.lng), null).first

    private fun schedule(
        items: List<DayItem>,
        day: Day? = null,
        stays: List<Stay> = listOf(stay),
        trains: List<Train> = emptyList(),
        pl: Map<String, Place> = places,
        known: (String) -> Int? = { null },
    ) = DaySchedule.forDate(date, day, items, emptyList(), trains, stays, emptyList(), pl, emptyList(), known = known)

    @Test fun startsAtNineFromTheStay() {
        val s = schedule(listOf(item("a"), item("b")))
        val first = s.stops.getValue("iA")
        assertEquals(DaySchedule.DEFAULT_START + way(hotel, a), first.start)
        assertFalse(first.fixed)
        assertTrue(s.startText("iA")!!.startsWith("~"))
        assertEquals(first.end + way(a, b), s.stops.getValue("iB").start)
    }

    @Test fun reorderingChangesTimes() {
        val forward = schedule(listOf(item("a"), item("b"), item("c"))).stops.getValue("iA").start
        val backward = schedule(listOf(item("c"), item("b"), item("a"))).stops.getValue("iA").start
        assertNotEquals(forward, backward)
    }

    @Test fun fixedTimeHoldsAndWaits() {
        val s = schedule(listOf(item("a"), item("b", fixed = "15:00"), item("c")))
        val bStop = s.stops.getValue("iB")
        assertEquals(15 * 60, bStop.start)
        assertTrue(bStop.fixed)
        assertTrue(bStop.warnings.isEmpty())
        assertEquals("15:00", s.startText("iB"))
        assertEquals(15 * 60 + 60 + way(b, c), s.stops.getValue("iC").start)
    }

    @Test fun lateForFixedTime() {
        val s = schedule(listOf(item("a"), item("b", fixed = "09:05")))
        val arrive = DaySchedule.DEFAULT_START + way(hotel, a) + 60 + way(a, b)
        assertEquals(listOf(ScheduleWarning(TimeWarning.LATE, arrive - (9 * 60 + 5))), s.stops.getValue("iB").warnings)
    }

    @Test fun aFixedTimeEarlierThanAPreviousPlaceIsOnlyLate() {
        val s = schedule(listOf(item("a", fixed = "14:00"), item("b", fixed = "10:00")))
        assertEquals(listOf("iA", "iB"), s.stops.keys.toList())
        assertEquals(TimeWarning.LATE, s.stops.getValue("iB").warnings.single().kind)
    }

    @Test fun dayStartAndDurationOverride() {
        val s = schedule(listOf(item("a", stay = 120), item("b")), day = Day("d", date, startTime = "10:30"))
        val aStop = s.stops.getValue("iA")
        assertEquals(10 * 60 + 30 + way(hotel, a), aStop.start)
        assertEquals(aStop.start + 120, aStop.end)
        assertEquals(10 * 60 + 30, s.dayStart)
        assertEquals(aStop.end + way(a, b), s.stops.getValue("iB").start)
    }

    @Test fun knownWayWins() {
        val s = schedule(listOf(item("a"), item("b")), known = { if (it == "iB") 40 else null })
        assertEquals(s.stops.getValue("iA").end + 40, s.stops.getValue("iB").start)
        assertEquals(40, s.stops.getValue("iB").wayMinutes)
    }

    @Test fun closedOnArrival() {
        val shut = a.copy(openingPeriods = listOf(OpeningPeriod(4, "13:00", 4, "17:00")))
        val s = schedule(listOf(item("a")), pl = places + ("a" to shut))
        assertEquals(listOf(ScheduleWarning(TimeWarning.CLOSED)), s.stops.getValue("iA").warnings)
    }

    @Test fun missesRide() {
        val train = Train(
            "t", "JR",
            depart = Endpoint("Bahnhof", lat = 35.0, lng = 135.79, date = date, time = "09:10"),
            arrive = Endpoint("Ziel", lat = 35.027, lng = 135.78, date = date, time = "12:00"),
        )
        // A before the train (by its fixed time), C after it.
        val s = schedule(listOf(item("a", fixed = "09:00"), item("c", fixed = "12:30")), trains = listOf(train), day = Day("d", date, startTime = "08:00"))
        assertEquals(listOf(ScheduleWarning(TimeWarning.MISSES_RIDE)), s.stops.getValue("iA").warnings)
        assertTrue(s.stops.getValue("iC").warnings.isEmpty())
    }

    @Test fun aRideMovesTheClockToItsArrival() {
        val train = Train(
            "t", "JR",
            depart = Endpoint("Bahnhof", lat = 35.0, lng = 135.78, date = date, time = "11:00"),
            arrive = Endpoint("Ziel", lat = 35.027, lng = 135.78, date = date, time = "12:00"),
        )
        val s = schedule(listOf(item("a", fixed = "09:00"), item("c", fixed = "13:00"), item("b")), trains = listOf(train))
        // b has no fixed time: it follows c (after the train).
        assertEquals(13 * 60 + 60 + way(c, b), s.stops.getValue("iB").start)
    }

    @Test fun noMorningStayStartsAtFirstPlace() {
        val s = schedule(listOf(item("a"), item("b")), stays = emptyList())
        assertEquals(DaySchedule.DEFAULT_START, s.stops.getValue("iA").start)
        assertEquals(DaySchedule.DEFAULT_START + 60 + way(a, b), s.stops.getValue("iB").start)
    }

    @Test fun invalidFixedTimeIsEstimated() {
        listOf("25:99", "", "abc").forEach { bad ->
            val st = schedule(listOf(item("a", fixed = bad))).stops.getValue("iA")
            assertFalse(st.fixed, bad)
            assertEquals(DaySchedule.DEFAULT_START + way(hotel, a), st.start)
        }
    }

    @Test fun notesGetNoTime() {
        val note = DayItem("n", "d", "n", kind = de.wanderlogplus.shared.model.DayItemKind.NOTE, text = "Geld")
        val s = schedule(listOf(item("a"), note))
        assertEquals(null, s.startText("n"))
        assertFalse("n" in s.stops)
    }
}
