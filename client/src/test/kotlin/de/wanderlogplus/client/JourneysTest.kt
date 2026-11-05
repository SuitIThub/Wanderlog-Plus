package de.wanderlogplus.client

import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
import kotlin.test.*

class JourneysTest {
    private val lh = Flight("f1", airlineCode = "LH", number = "716", depart = Endpoint("Frankfurt", "FRA", date = "2027-05-07", time = "13:30"), arrive = Endpoint("Haneda", "HND", date = "2027-05-08", time = "08:45"))
    private val nh = Flight("f2", airlineCode = "NH", number = "23", depart = Endpoint("Haneda", "HND", date = "2027-05-08", time = "09:30"), arrive = Endpoint("Itami", "ITM", date = "2027-05-08", time = "10:40"))
    private fun ride(from: String, to: String, dep: String, arr: String, dp: String?, ap: String?) = TransitResult(
        TransitStation(from), TransitStation(to), "2027-05-08T$dep",
        routes = listOf(TransitRoute(dep, arr, 30, legs = listOf(TransitLeg("x", from = TransitStation(from), to = TransitStation(to), departure = dep, arrival = arr, departurePlatform = dp, arrivalPlatform = ap)))),
    )
    private val bus = Train("t1", "Limousine Bus", Endpoint("Itami", lat = 34.785, lng = 135.438, date = "2027-05-08", time = "11:10"), Endpoint("Kyoto", lat = 34.985, lng = 135.758, date = "2027-05-08", time = "12:05"), transit = ride("Itami", "Kyoto", "11:10", "12:05", null, "8"))
    private val local = Train("t2", "JR", Endpoint("Kyoto", lat = 34.9855, lng = 135.7585, date = "2027-05-08", time = "12:10"), Endpoint("Nara", date = "2027-05-08", time = "12:55"), transit = ride("Kyoto", "Nara", "12:10", "12:55", "9", null))

    private fun fx() = EditorFixture().also {
        it.seedEntity(EntityType.TRIP, "t", Trip("t", "Japan", "2027-05-08", "2027-05-29"))
        it.seedEntity(EntityType.FLIGHT, "f1", lh)
        it.seedEntity(EntityType.FLIGHT, "f2", nh)
        it.seedEntity(EntityType.TRAIN, "t1", bus)
        it.seedEntity(EntityType.TRAIN, "t2", local)
    }

    @Test fun joinKeepsEveryLegAndOrdersThemByTime() {
        val f = fx()
        val jid = f.editor.joinJourney(f.snapshot(), listOf(EntityType.TRAIN to "t1", EntityType.FLIGHT to "f2", EntityType.FLIGHT to "f1"))
        val j = journeys(f.snapshot()).single()
        assertEquals(jid, j.id)
        assertEquals(listOf("f1", "f2", "t1"), j.legs.map { it.id })
        assertEquals(2 to 2, f.snapshot().flights.size to f.snapshot().trains.size) // nothing deleted
        assertEquals("716", f.snapshot().flights.first { it.id == "f1" }.number)
        // Extending: the local train joins the same journey.
        assertEquals(jid, f.editor.joinJourney(f.snapshot(), listOf(EntityType.TRAIN to "t1", EntityType.TRAIN to "t2")))
        assertEquals(4, journeys(f.snapshot()).single().legs.size)
        assertEquals(jid, journeyOf(f.snapshot(), EntityType.TRAIN, "t2")?.id)
        assertEquals(23L * 60 + 25, journeys(f.snapshot()).single().totalMinutes) // 13:30 → next day 12:55 (local times)
    }

    @Test fun transfersShowWaitingPlatformsDistanceAndWarnings() {
        val s = fx().snapshot()
        fun leg(m: de.wanderlogplus.shared.query.Movement) = legOf(m)
        val air = transfer(s, leg(movementOf(lh)), leg(movementOf(nh)))
        assertEquals(45L, air.minutes)
        assertTrue(air.samePlace && air.tight, "45 min between two flights is tight")
        val train = transfer(s, leg(movementOf(bus)), leg(movementOf(local)))
        assertEquals(5L, train.minutes)
        assertTrue(train.samePlace && train.tight)
        assertEquals("Gleis 8" to "Gleis 9", train.fromSpot to train.toSpot)
        val far = transfer(s, leg(movementOf(bus)), leg(movementOf(local.copy(depart = local.depart.copy(name = "Osaka Umeda", lat = 34.70, lng = 135.50)))))
        assertFalse(far.samePlace)
        assertTrue(far.distanceKm!! > 5)
        val overlap = transfer(s, leg(movementOf(nh)), leg(movementOf(bus.copy(depart = bus.depart.copy(time = "10:00")))))
        assertTrue(overlap.overlaps)
        assertEquals("2 Std. 10 Min.", durationText(130))
    }

    @Test fun leavingAndDissolving() {
        val f = fx()
        val jid = f.editor.joinJourney(f.snapshot(), listOf(EntityType.FLIGHT to "f1", EntityType.FLIGHT to "f2", EntityType.TRAIN to "t1"))
        f.editor.leaveJourney(f.snapshot(), EntityType.TRAIN, "t1")
        assertEquals(listOf("f1", "f2"), journeys(f.snapshot()).single().legs.map { it.id })
        assertNull(f.snapshot().trains.first { it.id == "t1" }.journeyId)
        f.editor.leaveJourney(f.snapshot(), EntityType.FLIGHT, "f2")
        assertTrue(journeys(f.snapshot()).isEmpty(), "one leg left: no journey")
        assertNull(f.snapshot().flights.first { it.id == "f1" }.journeyId)
        f.editor.joinJourney(f.snapshot(), listOf(EntityType.FLIGHT to "f1", EntityType.FLIGHT to "f2"))
        f.editor.dissolveJourney(f.snapshot(), journeys(f.snapshot()).single().id)
        assertTrue(journeys(f.snapshot()).isEmpty())
        assertNotEquals("", jid)
    }

    @Test fun oldMergedEntriesSplitIntoLegs() {
        val f = EditorFixture()
        f.seedEntity(EntityType.TRIP, "t", Trip("t", "Japan", "2027-05-08", "2027-05-29"))
        val merged = Flight(
            "fm", airlineCode = "LH", number = "716", confirmationNumber = "ABC",
            depart = Endpoint("Frankfurt", "FRA", date = "2027-05-07", time = "13:30"),
            via = listOf(Endpoint("Haneda", "HND", date = "2027-05-08", time = "08:45")),
            arrive = Endpoint("Itami", "ITM", date = "2027-05-08", time = "10:40"),
        )
        f.seedEntity(EntityType.FLIGHT, "fm", merged)
        f.editor.splitLegacy(f.snapshot(), EntityType.FLIGHT, "fm")
        val j = journeys(f.snapshot()).single()
        assertEquals(2, j.legs.size)
        val first = f.snapshot().flights.first { it.id == "fm" }
        assertEquals("Haneda" to "08:45", first.arrive.name to first.arrive.time)
        assertTrue(first.via.isEmpty())
        assertEquals("ABC", first.confirmationNumber)
        val second = f.snapshot().flights.first { it.id != "fm" }
        assertEquals("Haneda" to null, second.depart.name to second.depart.time) // departure time to be entered
        assertEquals("LH", second.airlineCode)
        assertNull(second.number)
    }
}
