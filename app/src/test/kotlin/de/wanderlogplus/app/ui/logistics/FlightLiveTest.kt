package de.wanderlogplus.app.ui.logistics

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FlightLive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class FlightLiveTest {
    @get:Rule val rule = createComposeRule()

    private val flight = Flight("f1", airline = "ANA", airlineCode = "NH", number = "7", depart = Endpoint("Haneda", "HND", date = "2027-05-08", time = "07:00"), arrive = Endpoint("Itami", "ITM", time = "08:05"))
    private val snapshot = TripSnapshot(null, emptyList(), emptyMap(), emptyList(), emptyList(), listOf(flight), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())

    private val delayed = FlightLive(
        available = true, status = "scheduled", depScheduled = "2027-05-08 07:00", depEstimated = "2027-05-08 07:25",
        depTerminal = "2", depGate = "G6", depDelayMinutes = 25, arrScheduled = "2027-05-08 08:05", arrBaggage = null,
    )

    @Test fun detailShowsDelayTerminalAndGate() {
        val asked = mutableListOf<String>()
        rule.setContent {
            WanderlogPlusTheme {
                CompositionLocalProvider(LocalFlightLive provides { id -> asked += id; delayed }) {
                    LogisticsDetailScreen(snapshot, EntityType.FLIGHT, "f1", onBack = {})
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("live-status-dep"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Abflug: 07:00 → 07:25 · Terminal 2 · Gate G6"), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("+25 Min."), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Planmäßig"), useUnmergedTree = true).assertExists()
        assertEquals("f1", asked.first())
    }

    @Test fun notYetShowsTheReasonAndWithoutSourceNothing() {
        rule.setContent {
            WanderlogPlusTheme {
                CompositionLocalProvider(LocalFlightLive provides { _ -> FlightLive(reason = "Live-Status ab ca. 24 Stunden vor Abflug.") }) {
                    LogisticsDetailScreen(snapshot, EntityType.FLIGHT, "f1", onBack = {})
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasText("Live-Status ab ca. 24 Stunden vor Abflug."), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun shortLines() {
        assertEquals("Planmäßig · +25 Min. · Gate G6", delayed.shortLine())
        assertEquals("Gelandet · Gepäckband 12", FlightLive(available = true, status = "landed", arrBaggage = "12").shortLine())
        assertEquals(null, FlightLive(reason = "x").shortLine())
        assertEquals("07:00", liveTimes("2027-05-08 07:00", "2027-05-08 07:00", null))
    }

    @Test fun trainDetailShowsTheShinkansenStatus() {
        val train = de.wanderlogplus.shared.model.Train("t1", "JR Shinkansen", Endpoint("Kyoto", date = "2027-05-22", time = "08:12"), Endpoint("Hakata"), number = "Nozomi 21")
        val snap = snapshot.copy(trains = listOf(train))
        val live = de.wanderlogplus.shared.sync.TrainLive(
            true, trains = listOf(de.wanderlogplus.shared.sync.TrainLiveEntry("Nozomi 21", "Delayed 5 min", "Running between KYOTO and SHIN-OSAKA", "warning", "08:31")),
            lineStatus = "Tokaido, Sanyo Shinkansen: Delayed",
        )
        rule.setContent {
            WanderlogPlusTheme {
                CompositionLocalProvider(LocalTrainLive provides { _ -> live }) { LogisticsDetailScreen(snap, EntityType.TRAIN, "t1", onBack = {}) }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("train-live"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        rule.onNode(hasText("Delayed 5 min"), useUnmergedTree = true).assertExists()
        rule.onNode(hasText("Running between KYOTO and SHIN-OSAKA (Stand 08:31)"), useUnmergedTree = true).assertExists()
        assertEquals("Nozomi 21: Delayed 5 min", live.shortLine())
    }
}
