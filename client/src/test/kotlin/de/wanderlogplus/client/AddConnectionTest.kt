package de.wanderlogplus.client

import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.model.Trip
import de.wanderlogplus.shared.sync.EntityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AddConnectionTest {
    private fun ride(from: String, to: String, dep: String, arr: String, number: String) =
        Train("", carrier = "JR", number = number, depart = Endpoint(from, date = "2027-05-20", time = dep), arrive = Endpoint(to, date = "2027-05-20", time = arr))

    private fun fx() = EditorFixture().also { it.seedEntity(EntityType.TRIP, "trip", Trip("trip", "Japan", "2027-05-08", "2027-05-29")) }

    @Test fun aSingleRideIsOneTrain() {
        val f = fx()
        val id = f.editor.addConnection(listOf(ride("Kyoto", "Nara", "09:00", "09:45", "Miyakoji 3")))
        val t = f.snapshot().trains.single()
        assertEquals(id, t.id)
        assertEquals("Miyakoji 3", t.number)
        assertNull(t.journeyId)
    }

    @Test fun ridesWithChangesBecomeOneJourneyInOrder() {
        val f = fx()
        val id = f.editor.addConnection(listOf(ride("Kyoto", "Nagoya", "08:12", "08:47", "Nozomi 21"), ride("Nagoya", "Nakatsugawa", "09:00", "10:05", "Shinano 7")))
        val j = journeys(f.snapshot()).single()
        assertEquals(listOf("Nozomi 21", "Shinano 7"), j.legs.map { (it.movement as de.wanderlogplus.shared.query.Movement.TrainMove).train.number })
        assertEquals(id, j.legs.first().id)
    }

    @Test fun noRidesNoChange() {
        val f = fx()
        assertNull(f.editor.addConnection(emptyList()))
        assertEquals(0, f.snapshot().trains.size)
    }
}
