package de.wanderlogplus.app.ui.transit

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.logistics.LogisticsDetailScreen
import de.wanderlogplus.app.ui.logistics.seatsStale
import de.wanderlogplus.app.ui.today.TodayScreen
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Seat
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.EntityType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class TransitReplaceTest {
    @get:Rule val rule = createComposeRule()

    private val shinano = Train("t", depart = Endpoint("Nagoya", date = "2027-05-20", time = "09:55"), arrive = Endpoint("Nakatsugawa", date = "2027-05-20", time = "11:10"))

    @Test
    fun replaceStartTimes() {
        assertEquals("2027-05-20" to "10:10", replaceStart(shinano, LocalDateTime.parse("2027-05-20T10:10")))
        assertEquals("2027-05-20" to "09:56", replaceStart(shinano, LocalDateTime.parse("2027-05-19T22:00")))
        val late = shinano.copy(depart = shinano.depart.copy(time = "23:59"))
        assertEquals("2027-05-21" to "00:00", replaceStart(late, LocalDateTime.parse("2027-05-20T08:00")))
    }

    @Test
    fun seatsStaleFlag() {
        val seated = shinano.copy(seats = listOf(Seat("5", "5A")))
        assertTrue(seatsStale(seated.copy(seatsForDeparture = "2027-05-20T09:00")))
        assertFalse(seatsStale(seated.copy(seatsForDeparture = "2027-05-20T09:55")))
        assertFalse(seatsStale(seated))
        assertFalse(seatsStale(shinano.copy(seatsForDeparture = "2027-05-20T09:00")))
    }

    @Test
    fun detailShowsReplaceAndStaleHint() {
        val s = Fixtures.trip().let { t ->
            t.copy(trains = t.trains.map { it.copy(seats = listOf(Seat("3", "12A")), seatsForDeparture = "2027-04-04T09:40") })
        }
        rule.setContent {
            WanderlogPlusTheme { LogisticsDetailScreen(s, EntityType.TRAIN, "tr-1", onBack = {}, onReplaceRide = {}, onSeatsConfirmed = {}) }
        }
        rule.onNodeWithTag("train-replace", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("train-seats-stale", useUnmergedTree = true).assertExists()
    }

    @Test
    fun todayOffersReplace() {
        rule.setContent {
            WanderlogPlusTheme {
                TodayScreen(Fixtures.trip(), LocalDateTime.parse("2027-04-04T08:00"), onOpenDay = {}, onOpenTrip = {}, onSyncNow = {}, onReplaceTrain = {})
            }
        }
        rule.onNodeWithTag("today-replace", useUnmergedTree = true).assertExists()
    }
}
