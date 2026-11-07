package de.wanderlogplus.app.ui.logistics

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.transit.TransitForm
import de.wanderlogplus.app.ui.transit.replaceSeed
import de.wanderlogplus.app.ui.transit.resultWithPlaces
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitPoint
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class WalkEndsTest {
    @get:Rule val rule = createComposeRule()

    private val tokyo = TransitStation("東京", "Tokyo", 35.68, 139.77)
    private val nagoya = TransitStation("名古屋", "Nagoya", 35.17, 136.88)
    private val naka = TransitStation("中津川", "Nakatsugawa", 35.49, 137.5)
    private val hotel = TransitPoint(35.69, 139.70, "Hotel Gracery")
    private val ryokan = TransitPoint(35.55, 137.60, "Ryokan Kiso")

    private val twoRides = TransitResult(
        tokyo, naka, "2027-05-20T08:00",
        routes = listOf(
            TransitRoute(
                "08:00", "11:10", 190, date = "2027-05-20",
                legs = listOf(
                    TransitLeg("A", "JR Shinkansen Nozomi 15", from = tokyo, to = nagoya, departure = "08:00", arrival = "09:40"),
                    TransitLeg("B", "JR Ltd. Exp. Shinano 7", from = nagoya, to = naka, departure = "09:55", arrival = "11:10"),
                ),
            ),
        ),
    )
    private val empty = MoveDraft("", "", Endpoint(), emptyList(), Endpoint())

    @Test
    fun pickedPlacesBecomeWalkEnds() {
        val d = draftFromTransit(empty, twoRides.copy(fromPlace = hotel, toPlace = ryokan))
        assertEquals("Hotel Gracery", d.walkFrom!!.name)
        assertEquals(35.69, d.walkFrom!!.lat)
        assertNull(d.walkTo)
        assertEquals("Ryokan Kiso", d.extraRides.last().walkTo!!.name)
        val single = draftFromTransit(empty, twoRides.copy(routes = listOf(twoRides.routes[0].copy(legs = twoRides.routes[0].legs.take(1))), toPlace = ryokan))
        assertEquals("Ryokan Kiso", single.walkTo!!.name)
    }

    @Test
    fun stationsOnlyNoWalkEnds() {
        val d = draftFromTransit(empty, twoRides)
        assertNull(d.walkFrom)
        assertNull(d.extraRides.last().walkTo)
    }

    @Test
    fun resultGetsOnlyRealPlaces() {
        val form = TransitForm(fromPoint = hotel, toPoint = TransitPoint(35.49, 137.5, "Nakatsugawa"), fromIsPlace = true, toIsPlace = false)
        val r = resultWithPlaces(twoRides, form)
        assertEquals(hotel, r.fromPlace)
        assertNull(r.toPlace)
    }

    @Test
    fun walkEstimates() {
        assertEquals("ca. 16 Min. zu Fuß", walkEstimate(0.95))
        assertNull(walkEstimate(4.2))
    }

    @Test
    fun detailShowsWalkRows() {
        val s = Fixtures.trip().let { t ->
            t.copy(trains = t.trains.map { it.copy(walkFrom = Endpoint("Gasthaus am Hang", lat = 35.01, lng = 135.76), walkTo = Endpoint("Fischmarkt", lat = 34.68, lng = 135.51)) })
        }
        rule.setContent { WanderlogPlusTheme { LogisticsDetailScreen(s, EntityType.TRAIN, "tr-1", onBack = {}) } }
        rule.onNodeWithTag("walk-from", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("walk-to", useUnmergedTree = true).assertExists()
    }

    @Test
    fun formEditsWalkEnds() {
        val s = Fixtures.trip()
        val d = draftOf(s, EntityType.TRAIN, "tr-1")!!.copy(walkFrom = Endpoint("Gasthaus am Hang", lat = 35.01, lng = 135.76))
        val (_, after) = draftChanges(s, EntityType.TRAIN, "tr-1", d)
        assertEquals(true, after.getValue("walkFrom").toString().contains("Gasthaus am Hang"))
    }

    @Test
    fun replaceSeedUsesWalkEnds() {
        val t = Train("t", depart = Endpoint("Tokyo", lat = 35.68, lng = 139.77, date = "2027-05-20", time = "08:00"), arrive = Endpoint("Nakatsugawa", lat = 35.49, lng = 137.5),
            walkFrom = Endpoint("Hotel Gracery", lat = 35.69, lng = 139.70), walkTo = Endpoint("Ryokan Kiso", lat = 35.55, lng = 137.60))
        val seed = replaceSeed(t)
        assertEquals(hotel, seed.from)
        assertEquals(ryokan, seed.to)
        assertEquals(true, seed.fromIsPlace)
        val plain = replaceSeed(t.copy(walkFrom = null, walkTo = null))
        assertEquals(false, plain.fromIsPlace)
        assertEquals("Tokyo", plain.from!!.name)
    }
}
