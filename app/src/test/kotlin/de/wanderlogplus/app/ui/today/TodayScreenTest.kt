package de.wanderlogplus.app.ui.today

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDateTime

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class TodayScreenTest {
    @get:Rule val rule = createComposeRule()

    private val store = InMemoryEntityStore()

    private inline fun <reified T> seed(type: EntityType, id: String, entity: T) {
        val fields: Map<String, JsonElement> = EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun seedTrip() {
        seed(EntityType.TRIP, "t1", Trip("t1", "Japan", "2027-05-08", "2027-05-12"))
        seed(EntityType.REGION, "r1", Region("r1", "Kyoto", "#3498db"))
        seed(EntityType.PLACE, "hotel", Place("hotel", name = "Hotel Sakura"))
        seed(
            EntityType.STAY, "s0",
            Stay("s0", "hotel", "r1", "2027-05-08", "2027-05-12", cancellationDeadline = "2027-05-05T23:59"),
        )
        seed(EntityType.DAY, "d1", Day("d1", "2027-05-09", "Tempeltag"))
        seed(EntityType.PLACE, "pc", Place("pc", name = "Museum Gamma"))
        seed(EntityType.DAY_ITEM, "i1", DayItem("i1", "d1", "a0", DayItemKind.PLACE, "pc", time = "10:00"))
        seed(
            EntityType.TRAIN, "tr1",
            Train("tr1", "JR", depart = Endpoint("Kyoto", date = "2027-05-09", time = "08:15"), arrive = Endpoint("Osaka")),
        )
    }

    private var openedDay: String? = null
    private var openedTrip = 0

    private fun show(now: String) {
        val snapshot = TripSnapshot.from(store.all())
        rule.setContent {
            WanderlogPlusTheme {
                TodayScreen(
                    snapshot, LocalDateTime.parse(now),
                    onOpenDay = { openedDay = it }, onOpenTrip = { openedTrip++ }, onSyncNow = {},
                )
            }
        }
    }

    @Test fun beforeTripShowsCountdownAndDeadline() {
        seedTrip()
        show("2027-05-01T12:00")
        rule.onNodeWithText("Noch 7 Tage").assertIsDisplayed()
        rule.onNodeWithText("Fristen").assertIsDisplayed()
        rule.onNode(hasText("Mi, 05.05.") and hasAnyAncestor(hasTestTag("deadline-0"))).assertExists()
        rule.onNode(hasText("Hotel Sakura") and hasAnyAncestor(hasTestTag("deadline-0"))).assertExists()
        rule.onNode(hasText("Stornofrist") and hasAnyAncestor(hasTestTag("deadline-0"))).assertExists()
    }

    @Test fun duringTripShowsRegionTrainsAndProgram() {
        seedTrip()
        show("2027-05-09T12:00")
        rule.onNodeWithText("Tempeltag").assertIsDisplayed()
        rule.onNodeWithText("Kyoto").assertIsDisplayed()
        rule.onNodeWithText("Fahrten heute").assertIsDisplayed()
        rule.onNode(hasText("08:15", substring = true) and hasTestTag("movement-0")).assertExists()
        rule.onNode(hasText("Osaka", substring = true) and hasTestTag("movement-0")).assertExists()
        rule.onNodeWithText("Programm").assertIsDisplayed()
        rule.onNode(hasText("Museum Gamma") and hasAnyAncestor(hasTestTag("today-program"))).assertExists()
    }

    @Test fun checkInHintOnArrivalDay() {
        seedTrip()
        show("2027-05-08T12:00")
        rule.onNodeWithText("Check-in heute", substring = true).assertIsDisplayed()
    }

    @Test fun programRowAndHeaderOpenDayDetail() {
        seedTrip()
        show("2027-05-09T12:00")
        rule.onNodeWithTag("today-header").performClick()
        assertEquals("2027-05-09", openedDay)
        openedDay = null
        rule.onNodeWithTag("today-item-i1").performClick()
        assertEquals("2027-05-09", openedDay)
    }

    @Test fun openNowFiltersClosedSortsAndLimitsToFive() {
        seedTrip()
        // 2027-05-09 is a Sunday (day 0); "now" is 12:00.
        seed(EntityType.PLACE, "closed", Place("closed", name = "Zu", openingPeriods = listOf(OpeningPeriod(0, "13:00", 0, "18:00"))))
        seed(EntityType.SAVED_PLACE, "sv-closed", SavedPlace("sv-closed", "closed", "r1"))
        seed(EntityType.PLACE, "open", Place("open", name = "Auf", rating = 4.8, openingPeriods = listOf(OpeningPeriod(0, "09:00", 0, "18:00"))))
        seed(EntityType.SAVED_PLACE, "sv-open", SavedPlace("sv-open", "open", "r1"))
        for (i in 1..6) {
            seed(EntityType.PLACE, "u$i", Place("u$i", name = "Unbekannt $i", rating = 3.0))
            seed(EntityType.SAVED_PLACE, "sv-u$i", SavedPlace("sv-u$i", "u$i", "r1"))
        }
        show("2027-05-09T12:00")
        rule.onNodeWithText("Jetzt geöffnet in der Nähe").assertIsDisplayed()
        rule.onNodeWithTag("open-now-sv-closed").assertDoesNotExist()
        rule.onNodeWithTag("open-now-sv-open").assertExists()
        rule.onNodeWithTag("open-now-list").onChildren().assertCountEquals(5)
    }

    @Test fun afterTripWelcomesBack() {
        seedTrip()
        show("2027-06-01T12:00")
        rule.onNodeWithText("Willkommen zurück").assertIsDisplayed()
        rule.onNodeWithText("Zur Reise").performClick()
        assertEquals(1, openedTrip)
    }

    @Test fun emptySnapshotShowsEmptyState() {
        show("2027-05-09T12:00")
        rule.onNodeWithText("Noch keine Daten", substring = true).assertIsDisplayed()
    }
}
