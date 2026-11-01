package de.wanderlogplus.app.ui.today

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onChildren
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.links.LinkKind
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

    @Test fun beforeTripShowsCountdownAndDeadlines() {
        seedTrip()
        show("2027-05-01T12:00")
        rule.onNodeWithTag("countdown").assertTextEquals("7 Tage")
        rule.onNodeWithText("bis Japan").assertIsDisplayed()
        rule.onNodeWithText("Anstehende Fristen", ignoreCase = true).assertIsDisplayed()
        rule.onNode(hasText("Mi, 05.05.") and hasAnyAncestor(hasTestTag("deadline-0"))).assertExists()
        rule.onNode(hasText("Hotel Sakura") and hasAnyAncestor(hasTestTag("deadline-0"))).assertExists()
        rule.onNode(hasText("Stornofrist") and hasAnyAncestor(hasTestTag("deadline-0"))).assertExists()
    }

    @Test fun countdownSingularForOneDay() {
        seedTrip()
        show("2027-05-07T12:00")
        rule.onNodeWithTag("countdown").assertTextEquals("1 Tag")
    }

    @Test fun duringTripShowsRegionTrainsAndProgram() {
        seedTrip()
        show("2027-05-09T12:00")
        rule.onNodeWithText("Tempeltag").assertIsDisplayed()
        rule.onNodeWithText("Kyoto").assertIsDisplayed()
        rule.onNodeWithText("So · 9. Mai · Tag 2", ignoreCase = true).assertIsDisplayed()
        rule.onNodeWithText("Tagesablauf", ignoreCase = true).assertIsDisplayed()
        rule.onNode(hasText("Kyoto → Osaka") and hasAnyAncestor(hasTestTag("movement-0"))).assertExists()
        rule.onNode(hasText("08:15 · JR") and hasAnyAncestor(hasTestTag("movement-0"))).assertExists()
        rule.onNode(hasText("Museum Gamma") and hasAnyAncestor(hasTestTag("today-program"))).assertExists()
        rule.onNodeWithText("Unterkunft", ignoreCase = true).assertExists()
        rule.onNode(hasText("Hotel Sakura") and hasAnyAncestor(hasTestTag("today-stay"))).assertExists()
        rule.onNode(hasText("Check-out Mi, 12.05.") and hasAnyAncestor(hasTestTag("today-stay"))).assertExists()
        rule.onNode(hasText("Hotel Sakura") and hasAnyAncestor(hasTestTag("timeline-night"))).assertExists()
    }

    @Test fun timelineOrdersMovesThenPlaces() {
        seedTrip()
        seed(
            EntityType.FLIGHT, "f1",
            Flight(
                "f1", airlineCode = "NH", number = "7",
                depart = Endpoint("Tokio", "HND", date = "2027-05-09", time = "07:00"),
                arrive = Endpoint("Kansai", "KIX", time = "08:05"),
            ),
        )
        seed(EntityType.PLACE, "pa", Place("pa", name = "Frühstück Alpha"))
        seed(EntityType.DAY_ITEM, "i0", DayItem("i0", "d1", "a-", DayItemKind.PLACE, "pa"))
        show("2027-05-09T12:00")
        rule.onNode(hasText("Tokio → Kansai") and hasAnyAncestor(hasTestTag("movement-0"))).assertExists()
        rule.onNode(hasText("07:00 – 08:05 · NH 7") and hasAnyAncestor(hasTestTag("movement-0"))).assertExists()
        val tops = listOf("movement-0", "movement-1", "today-item-i0", "today-item-i1", "timeline-night").map {
            rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot.top
        }
        assertEquals(tops.sorted(), tops)
        assertEquals(tops.distinct(), tops)
        // Moves keep their departure time as marker, program items are numbered, the stay closes the day.
        rule.onNode(hasText("07:00") and hasAnyAncestor(hasTestTag("today-program"))).assertExists()
        rule.onNode(hasText("1") and hasAnyAncestor(hasTestTag("today-program"))).assertExists()
        rule.onNode(hasText("2") and hasAnyAncestor(hasTestTag("today-program"))).assertExists()
        rule.onNode(hasText("Nacht") and hasAnyAncestor(hasTestTag("today-program"))).assertExists()
    }

    @Test fun moveWithCoordinatesShowsRouteMap() {
        seedTrip()
        seed(EntityType.PLACE, "bk", Place("bk", name = "Bahnhof Kyoto", lat = 34.98, lng = 135.75))
        seed(EntityType.PLACE, "bo", Place("bo", name = "Bahnhof Osaka", lat = 34.70, lng = 135.49))
        seed(
            EntityType.TRAIN, "tr1",
            Train(
                "tr1", "JR", depart = Endpoint("Kyoto", placeId = "bk", date = "2027-05-09", time = "08:15"),
                arrive = Endpoint("Osaka", placeId = "bo"),
            ),
        )
        show("2027-05-09T12:00")
        rule.onNode(hasTestTag("route-fallback") and hasAnyAncestor(hasTestTag("movement-0")), useUnmergedTree = true).assertExists()
    }

    @Test fun emptyProgramShowsHint() {
        seedTrip()
        show("2027-05-10T12:00")
        rule.onNodeWithText("Für heute ist noch nichts geplant").assertIsDisplayed()
        rule.onNodeWithText("Tag öffnen").performClick()
        assertEquals("2027-05-10", openedDay)
    }

    @Test fun openPlaceGetsOpenBadge() {
        seedTrip()
        seed(EntityType.PLACE, "pc", Place("pc", name = "Museum Gamma", openingPeriods = listOf(OpeningPeriod(0, "09:00", 0, "18:00"))))
        show("2027-05-09T12:00")
        rule.onNode(hasText("offen") and hasAnyAncestor(hasTestTag("today-item-i1")), useUnmergedTree = true).assertExists()
    }

    @Test fun closedPlaceHasNoOpenBadge() {
        seedTrip()
        seed(EntityType.PLACE, "pc", Place("pc", name = "Museum Gamma", openingPeriods = listOf(OpeningPeriod(0, "13:00", 0, "18:00"))))
        show("2027-05-09T12:00")
        rule.onNode(hasText("offen") and hasAnyAncestor(hasTestTag("today-item-i1")), useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun checkInHintOnArrivalDay() {
        seedTrip()
        show("2027-05-08T12:00")
        rule.onNodeWithText("Check-in heute", substring = true).assertIsDisplayed()
    }

    @Test fun stayNavigationOpensLink() {
        seedTrip()
        seed(EntityType.PLACE, "hotel", Place("hotel", name = "Hotel Sakura", lat = 35.0, lng = 135.7))
        val opened = mutableListOf<ExternalLink>()
        val snapshot = TripSnapshot.from(store.all())
        rule.setContent {
            WanderlogPlusTheme {
                TodayScreen(
                    snapshot, LocalDateTime.parse("2027-05-09T12:00"),
                    onOpenDay = {}, onOpenTrip = {}, onSyncNow = {}, onOpenLink = { opened += it },
                )
            }
        }
        rule.onNodeWithTag("today-stay-links").assertExists()
        rule.onNodeWithTag("link-NAVIGATE").performClick()
        assertEquals(LinkKind.NAVIGATE, opened.single().kind)
        rule.onNodeWithTag("link-TRANSIT").assertExists()
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
        rule.onNodeWithText("Jetzt geöffnet", ignoreCase = true).assertExists()
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
