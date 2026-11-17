package de.wanderlogplus.app.ui.transit

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.logistics.LogisticsScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The free connection search: its entry in the Logistik, the 📍 start and "Wohin speichern?". */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class FreeTransitSearchTest {
    @get:Rule val rule = createComposeRule()
    private fun tap(tag: String) = rule.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)

    @Test fun logistikOffersTheSearchOnTheRidesTabOnly() {
        var opened = 0
        rule.setContent { WanderlogPlusTheme { LogisticsScreen(Fixtures.trip(), onSearchTransit = { opened++ }) } }
        tap("logistics-search-transit")
        assertEquals(1, opened)
        tap("tab-stays")
        rule.onNodeWithTag("logistics-search-transit").assertDoesNotExist()
    }

    @Test fun theStartCanBeTheCurrentPosition() {
        var located = 0
        rule.setContent {
            WanderlogPlusTheme {
                TransitScreen("frei", TransitScreenState(form = TransitForm(date = "2027-04-02")), Color.Blue, chosen = null,
                    onEdit = {}, onSearch = {}, onChoose = {}, onClear = {}, onBack = {}, onLocateFrom = { located++ }, heading = "Verbindung suchen")
            }
        }
        rule.onNodeWithText("Verbindung suchen").assertExists()
        tap("transit-from-locate")
        assertEquals(1, located)
        rule.onNodeWithTag("transit-to-locate").assertDoesNotExist()
    }

    @Test fun savingToTheLogistik() {
        var logistics = 0
        rule.setContent { WanderlogPlusTheme { SaveConnectionDialog(Fixtures.trip(), "2027-04-02", onLogistics = { logistics++ }, onDay = { _, _ -> }, onDismiss = {}) } }
        tap("save-logistics")
        assertEquals(1, logistics)
    }

    @Test fun savingAsTheWayToAProgramPoint() {
        var saved: Pair<String, String>? = null
        rule.setContent { WanderlogPlusTheme { SaveConnectionDialog(Fixtures.trip(), "2027-04-02", onLogistics = {}, onDay = { d, k -> saved = d to k }, onDismiss = {}) } }
        tap("save-day")
        // Another day and back: the search's date is preselected.
        tap("save-day-next")
        tap("save-day-prev")
        rule.onNodeWithText("zu Nudelküche Weizenhalm").assertExists()
        tap("save-way-i-3")
        assertEquals("2027-04-02" to "i-3", saved)
    }

    @Test fun waysOfADayLeadToPlacesAndTheStay() {
        val ways = dayWayChoices(Fixtures.trip(), "2027-04-02")
        assertTrue(ways.toString(), ways.any { it.key == "i-2" && it.label.startsWith("zu ") })
        assertTrue(ways.toString(), ways.any { it.key == "night" && it.label.startsWith("zur Unterkunft") })
    }
}
