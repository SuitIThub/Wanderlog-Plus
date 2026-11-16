package de.wanderlogplus.app.ui.places

import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.Vote
import de.wanderlogplus.shared.model.Votes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceRatingTest {
    private fun vote(place: String, voter: String, value: Int) = Vote(Votes.id(place, voter), place, voter, value)
    private fun saved(id: String) = Fixtures.trip().savedPlaces.first { it.id == id }

    @Test
    fun queueFollowsRegionOrderAndName() {
        assertEquals(listOf("s-tempel", "s-garten", "s-nudel", "s-ufer", "s-markt"), ratingQueue(Fixtures.trip(), "daniel"))
    }

    @Test
    fun queueSkipsRatedAndClosed() {
        val t = Fixtures.trip()
        val snap = t.copy(
            votes = listOf(vote("s-garten", "daniel", 0), vote("s-nudel", "selina", Votes.LIKE)),
            savedPlaces = t.savedPlaces.map { if (it.id == "s-markt") it.copy(status = PlaceStatus.VISITED) else it },
        )
        assertEquals(listOf("s-tempel", "s-nudel", "s-ufer"), ratingQueue(snap, "daniel"))
        assertTrue(isRated(snap, "s-garten", "daniel"))
    }

    @Test
    fun distanceLabelFormats() {
        assertEquals("850 m", distanceLabel(0.853))
        assertEquals("4,2 km", distanceLabel(4.24))
        assertEquals("38 km", distanceLabel(37.6))
    }

    @Test
    fun dayTripUsesMainRegionStay() {
        val snap = Fixtures.trip()
        val stay = stayDistance(snap, saved("s-ufer"))!!
        assertEquals("Bergstadt", stay.regionName)
        assertTrue(stay.km in 30.0..38.0)
        val centre = centreDistance(snap, saved("s-ufer"))!!
        assertEquals("Seenland", centre.regionName)
        assertTrue(centre.km < 1.0)
    }

    @Test
    fun ownRegionStayAndNoCentre() {
        val snap = Fixtures.trip()
        val stay = stayDistance(snap, saved("s-tempel"))!!
        assertEquals("Bergstadt", stay.regionName)
        assertTrue(stay.km < 3.0)
        assertNull(centreDistance(snap, saved("s-tempel")))
    }

    @Test
    fun noStayNoLine() {
        assertNull(stayDistance(Fixtures.trip().copy(stays = emptyList()), saved("s-tempel")))
    }

    @Test
    fun noCoordinatesNoDistance() {
        val t = Fixtures.trip()
        val snap = t.copy(places = t.places + ("p-ufer" to t.places.getValue("p-ufer").copy(lat = 0.0, lng = 0.0)))
        assertNull(stayDistance(snap, saved("s-ufer")))
        assertNull(centreDistance(snap, saved("s-ufer")))
    }
}
