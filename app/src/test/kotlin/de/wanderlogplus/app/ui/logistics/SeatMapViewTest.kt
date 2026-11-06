package de.wanderlogplus.app.ui.logistics

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.shared.model.Seat
import de.wanderlogplus.shared.seatmap.SeatMaps
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SeatMapViewTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun aircraftSeatsAreNamedAndHighlighted() {
        rule.setContent {
            WanderlogPlusTheme { SeatCard(SeatMaps.byKey("ci-a359"), "Economy", listOf(Seat(seat = "43E"), Seat(seat = "43G"))) }
        }
        rule.onNodeWithText("43E · Mitte").assertExists()
        rule.onNodeWithText("43G · Gang").assertExists()
        rule.onNodeWithText("nebeneinander", substring = true).assertExists()
        rule.onNodeWithContentDescription("43E, Mitte, euer Platz").assertExists()
        rule.onNodeWithContentDescription("43G, Gang, euer Platz").assertExists()
        // Only your section is drawn in the card; the whole cabin opens full screen.
        rule.onAllNodesWithContentDescription("12A", substring = true).assertCountEquals(0)
        rule.onNodeWithTag("seatmap-full").performClick()
        rule.onAllNodesWithContentDescription("12A, Fenster", substring = true).assertCountEquals(1)
    }

    @Test
    fun trainCarAndUnknownSeat() {
        rule.setContent {
            WanderlogPlusTheme { SeatCard(SeatMaps.byKey("db-ice4-2kl"), "2. Klasse", listOf(Seat("12", "111"), Seat("12", "999"))) }
        }
        rule.onNodeWithText("Wagen 12, 111 · Fenster").assertExists()
        rule.onNodeWithText("Wagen 12, 999 · nicht im Plan").assertExists()
        rule.onNodeWithContentDescription("111, Fenster, euer Platz").assertExists()
    }

    @Test
    fun withoutAModelOnlyTheText() {
        rule.setContent { WanderlogPlusTheme { SeatCard(null, "Economy", listOf(Seat(seat = "20A"))) } }
        rule.onNodeWithText("Economy · 20A").assertExists()
        rule.onNodeWithText("Sitzplan wählen", substring = true).assertExists()
    }
}
