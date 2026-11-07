package de.wanderlogplus.client

import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.model.Trip
import de.wanderlogplus.shared.sync.EntityType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransitJourneyTest {
    private fun t(id: String, from: String, to: String, dep: String, journey: String? = null, order: Int? = null) =
        Train(id, depart = Endpoint(from, date = "2027-05-20", time = dep), arrive = Endpoint(to, date = "2027-05-20"), journeyId = journey, journeyOrder = order)

    private fun fx(vararg trains: Train) = EditorFixture().also { f ->
        f.seedEntity(EntityType.TRIP, "trip", Trip("trip", "Japan", "2027-05-08", "2027-05-29"))
        trains.forEach { f.seedEntity(EntityType.TRAIN, it.id, it) }
    }

    private fun order(f: EditorFixture, jid: String) =
        f.snapshot().trains.filter { it.journeyId == jid }.sortedBy { it.journeyOrder }.map { it.depart.name }

    @Test
    fun newTrainWithThreeRides() {
        val f = fx(t("t1", "Hotel", "Tokyo", "07:30"))
        val jid = f.editor.addRidesAfter(f.snapshot(), "t1", null, listOf(t("", "Tokyo", "Nagoya", "08:00"), t("", "Nagoya", "Nakatsugawa", "09:55")))
        assertTrue(jid.isNotBlank())
        assertEquals(3, f.snapshot().trains.count { it.journeyId == jid })
        assertEquals(listOf("Hotel", "Tokyo", "Nagoya"), order(f, jid))
    }

    @Test
    fun insertsAfterAnchorInExistingJourney() {
        val f = fx(t("a", "A", "B", "08:00", "J", 0), t("b", "C", "D", "12:00", "J", 1))
        f.editor.addRidesAfter(f.snapshot(), "a", "J", listOf(t("", "X", "Y", "09:00")))
        assertEquals(listOf("A", "X", "C"), order(f, "J"))
    }

    @Test
    fun newLegBetweenNeighbours() {
        val f = fx(t("a", "A", "B", "08:00", "J", 0), t("c", "C", "D", "12:00", "J", 1), t("n", "N", "O", "10:00", "J"))
        f.editor.addRidesAfter(f.snapshot(), "n", "J", listOf(t("", "Y", "Z", "11:00")), prev = EntityType.TRAIN to "a", next = EntityType.TRAIN to "c")
        assertEquals(listOf("A", "N", "Y", "C"), order(f, "J"))
    }

    @Test
    fun noRidesNoChange() {
        val f = fx(t("t1", "Hotel", "Tokyo", "07:30"))
        f.editor.addRidesAfter(f.snapshot(), "t1", null, emptyList())
        assertNull(f.snapshot().trains.single().journeyId)
    }
}
