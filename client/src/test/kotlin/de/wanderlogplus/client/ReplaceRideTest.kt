package de.wanderlogplus.client

import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Seat
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.model.Trip
import de.wanderlogplus.shared.sync.EntityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReplaceRideTest {
    private fun ride(from: String, to: String, dep: String, arr: String, number: String) =
        Train("", carrier = "JR", number = number, depart = Endpoint(from, date = "2027-05-20", time = dep), arrive = Endpoint(to, date = "2027-05-20", time = arr))

    private val shinano = Train(
        "t", carrier = "JR", number = "Shinano 7", confirmationNumber = "ABC", seats = listOf(Seat("5", "5A")),
        depart = Endpoint("Nagoya", date = "2027-05-20", time = "09:55"), arrive = Endpoint("Nakatsugawa", date = "2027-05-20", time = "11:10"),
    )

    private fun fx(vararg trains: Train) = EditorFixture().also { f ->
        f.seedEntity(EntityType.TRIP, "trip", Trip("trip", "Japan", "2027-05-08", "2027-05-29"))
        trains.forEach { f.seedEntity(EntityType.TRAIN, it.id, it) }
    }

    @Test
    fun replacesTimesKeepsBooking() {
        val f = fx(shinano)
        f.editor.replaceRide(f.snapshot(), "t", listOf(ride("Nagoya", "Nakatsugawa", "10:25", "11:40", "Shinano 9")))
        val t = f.snapshot().trains.single()
        assertEquals("10:25", t.depart.time)
        assertEquals("11:40", t.arrive.time)
        assertEquals("Shinano 9", t.number)
        assertEquals("ABC", t.confirmationNumber)
        assertEquals(listOf(Seat("5", "5A")), t.seats)
        assertEquals("2027-05-20T09:55", t.seatsForDeparture)
        assertNull(t.journeyId)
    }

    @Test
    fun twoRidesWithoutJourney() {
        val f = fx(shinano)
        f.editor.replaceRide(f.snapshot(), "t", listOf(ride("Nagoya", "Tajimi", "10:25", "10:55", "Local 1"), ride("Tajimi", "Nakatsugawa", "11:00", "11:45", "Local 2")))
        val trains = f.snapshot().trains.sortedBy { it.journeyOrder }
        assertEquals(2, trains.size)
        assertEquals("t", trains[0].id)
        assertTrue(trains[0].journeyId != null && trains[0].journeyId == trains[1].journeyId)
        assertEquals("Tajimi", trains[1].depart.name)
        assertEquals("ABC", trains[0].confirmationNumber)
    }

    @Test
    fun insideJourneyRestUnchanged() {
        val a = ride("Tokyo", "Nagoya", "08:00", "09:40", "Nozomi 15").copy(id = "a", journeyId = "J", journeyOrder = 0)
        val c = ride("Nakatsugawa", "Magome", "12:00", "12:30", "Bus").copy(id = "c", journeyId = "J", journeyOrder = 2)
        val f = fx(a, shinano.copy(journeyId = "J", journeyOrder = 1), c)
        f.editor.replaceRide(f.snapshot(), "t", listOf(ride("Nagoya", "Nakatsugawa", "10:25", "11:40", "Shinano 9")))
        val s = f.snapshot()
        assertEquals(listOf("a", "t", "c"), s.trains.sortedBy { it.journeyOrder }.map { it.id })
        assertEquals(a.copy(), s.trains.first { it.id == "a" })
        assertEquals(c, s.trains.first { it.id == "c" })
    }

    @Test
    fun noRidesNoChange() {
        val f = fx(shinano)
        f.editor.replaceRide(f.snapshot(), "t", emptyList())
        assertEquals(shinano, f.snapshot().trains.single())
    }
}
