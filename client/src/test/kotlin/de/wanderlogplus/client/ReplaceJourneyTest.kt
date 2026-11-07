package de.wanderlogplus.client

import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.model.Trip
import de.wanderlogplus.shared.sync.EntityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ReplaceJourneyTest {
    private fun leg(id: String, from: String, to: String, dep: String, order: Int, journey: String? = "J") =
        Train(id, number = id, depart = Endpoint(from, date = "2027-05-20", time = dep), arrive = Endpoint(to, date = "2027-05-20"), journeyId = journey, journeyOrder = order)
    private fun ride(from: String, to: String, dep: String, n: String) = Train("", number = n, depart = Endpoint(from, date = "2027-05-20", time = dep), arrive = Endpoint(to, date = "2027-05-20"))

    private fun fx(vararg trains: Train) = EditorFixture().also { f ->
        f.seedEntity(EntityType.TRIP, "trip", Trip("trip", "Japan", "2027-05-08", "2027-05-29"))
        trains.forEach { f.seedEntity(EntityType.TRAIN, it.id, it) }
    }

    private val a = leg("a", "Tokyo", "Nagoya", "08:00", 0).copy(confirmationNumber = "ABC", walkFrom = Endpoint("Hotel"))
    private val b = leg("b", "Nagoya", "Tajimi", "09:55", 1)
    private val c = leg("c", "Tajimi", "Nakatsugawa", "10:40", 2).copy(walkTo = Endpoint("Ryokan"))

    private fun ordered(f: EditorFixture) = f.snapshot().trains.sortedBy { it.journeyOrder }

    @Test
    fun replaceThreeByTwo() {
        val f = fx(a, b, c)
        f.editor.replaceJourney(f.snapshot(), "J", listOf(ride("Tokyo", "Nagoya", "08:30", "N2"), ride("Nagoya", "Nakatsugawa", "10:10", "S9")))
        val t = ordered(f)
        assertEquals(2, t.size)
        assertEquals("a", t[0].id)
        assertEquals("N2", t[0].number)
        assertEquals("ABC", t[0].confirmationNumber)
        assertEquals("Hotel", t[0].walkFrom!!.name)
        assertEquals("S9", t[1].number)
        assertEquals("Ryokan", t[1].walkTo!!.name)
        assertEquals("J", t[1].journeyId)
    }

    @Test
    fun replaceTwoByThree() {
        val f = fx(a, c.copy(journeyOrder = 1))
        f.editor.replaceJourney(f.snapshot(), "J", listOf(ride("Tokyo", "Nagoya", "08:30", "1"), ride("Nagoya", "Tajimi", "10:00", "2"), ride("Tajimi", "Nakatsugawa", "10:50", "3")))
        assertEquals(listOf("1", "2", "3"), ordered(f).map { it.number })
        assertEquals("Ryokan", ordered(f).last().walkTo!!.name)
    }

    @Test
    fun oneRideLeavesNoJourney() {
        val f = fx(a, b)
        f.editor.replaceJourney(f.snapshot(), "J", listOf(ride("Tokyo", "Nagoya", "08:30", "N2")))
        val t = f.snapshot().trains.single()
        assertNull(t.journeyId)
        assertEquals("N2", t.number)
    }

    @Test
    fun flightJourneyIsLeftAlone() {
        val f = fx(a)
        f.seedEntity(EntityType.FLIGHT, "f", Flight("f", depart = Endpoint("Nagoya", date = "2027-05-20", time = "12:00"), arrive = Endpoint("Sapporo", date = "2027-05-20"), journeyId = "J", journeyOrder = 1))
        f.editor.replaceJourney(f.snapshot(), "J", listOf(ride("Tokyo", "Nagoya", "08:30", "N2")))
        assertEquals("a", f.snapshot().trains.single().number)
    }
}
