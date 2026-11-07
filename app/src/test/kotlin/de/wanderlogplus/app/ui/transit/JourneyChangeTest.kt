package de.wanderlogplus.app.ui.transit

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.logistics.JourneyScreen
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.client.journeys
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.TransitPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class JourneyChangeTest {
    @get:Rule val rule = createComposeRule()

    private val a = Train("a", number = "N1", depart = Endpoint("Tokyo", lat = 35.68, lng = 139.77, date = "2027-05-20", time = "08:00"), arrive = Endpoint("Nagoya", date = "2027-05-20", time = "09:40"),
        journeyId = "J", journeyOrder = 0, walkFrom = Endpoint("Hotel Gracery", lat = 35.69, lng = 139.70))
    private val b = Train("b", number = "S7", depart = Endpoint("Nagoya", date = "2027-05-20", time = "09:55"), arrive = Endpoint("Nakatsugawa", lat = 35.49, lng = 137.5, date = "2027-05-20", time = "11:10"),
        journeyId = "J", journeyOrder = 1, walkTo = Endpoint("Ryokan Kiso", lat = 35.55, lng = 137.60))

    private fun trip(vararg trains: Train, flights: List<Flight> = emptyList()): TripSnapshot = Fixtures.trip().copy(trains = trains.toList(), flights = flights)

    @Test
    fun seedFromWalkEnds() {
        val s = trip(a, b)
        val seed = journeyChangeSeed(journeys(s).single())
        assertEquals(TransitPoint(35.69, 139.70, "Hotel Gracery"), seed.seed.from)
        assertEquals(TransitPoint(35.55, 137.60, "Ryokan Kiso"), seed.seed.to)
        assertTrue(seed.seed.fromIsPlace && seed.seed.toIsPlace)
        assertEquals("2027-05-20", seed.date)
        assertEquals("08:00", seed.time)
    }

    @Test
    fun flightJourneyNotChangeable() {
        val f = Flight("f", depart = Endpoint("Nagoya", date = "2027-05-20", time = "12:00"), arrive = Endpoint("Sapporo", date = "2027-05-20"), journeyId = "J", journeyOrder = 2)
        val s = trip(a, b, flights = listOf(f))
        assertFalse(canChangeJourney(journeys(s).single()))
        rule.setContent {
            WanderlogPlusTheme { JourneyScreen(s, "J", onBack = {}, onOpenLeg = { _, _ -> }, onAddLeg = { _, _, _ -> }, onJoin = {}, onLeave = { _, _ -> }, onDissolve = {}, onChangeJourney = {}) }
        }
        rule.onNodeWithTag("journey-change", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun trainJourneyOffersChange() {
        val s = trip(a, b)
        assertTrue(canChangeJourney(journeys(s).single()))
        rule.setContent {
            WanderlogPlusTheme { JourneyScreen(s, "J", onBack = {}, onOpenLeg = { _, _ -> }, onAddLeg = { _, _, _ -> }, onJoin = {}, onLeave = { _, _ -> }, onDissolve = {}, onChangeJourney = {}) }
        }
        rule.onNodeWithTag("journey-change", useUnmergedTree = true).assertExists()
    }
}
