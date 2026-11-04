package de.wanderlogplus.app.ui.trip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class DayDetailTest {
    @get:Rule val rule = createComposeRule()

    private val store = InMemoryEntityStore()
    private val outbox = InMemoryOutbox()
    private val editor = LocalEditor(store, outbox, HlcClock("dev-a", now = { 1000L }))

    private inline fun <reified T> seed(type: EntityType, id: String, entity: T) {
        val fields: Map<String, JsonElement> = EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun seedTrip(withStay: Boolean = true) {
        seed(EntityType.TRIP, "t1", Trip("t1", "Japan", "2027-05-08", "2027-05-12"))
        seed(EntityType.REGION, "r1", Region("r1", "Kyoto", "#3498db"))
        seed(EntityType.PLACE, "hotel", Place("hotel", name = "Hotel Sakura"))
        seed(EntityType.PLACE, "pa", Place("pa", name = "Schrein Alpha", rating = 4.0))
        seed(EntityType.PLACE, "pb", Place("pb", name = "Tempel Beta", rating = 4.9))
        seed(EntityType.PLACE, "pc", Place("pc", name = "Museum Gamma"))
        if (withStay) seed(EntityType.STAY, "s0", Stay("s0", "hotel", "r1", "2027-05-08", "2027-05-12"))
        // The warning-tagged place has the better rating; the star-tagged one must still come first.
        seed(EntityType.SAVED_PLACE, "sv-warn", SavedPlace("sv-warn", "pb", "r1", PlaceCategory.SIGHT, tags = listOf("⚠️ Geschlossen?")))
        seed(EntityType.SAVED_PLACE, "sv-star", SavedPlace("sv-star", "pa", "r1", PlaceCategory.SIGHT, tags = listOf("⭐ Muss man sehen")))
        seed(EntityType.DAY, "d1", Day("d1", "2027-05-09", "Tempeltag", "**Wichtig:** früh los"))
        seed(EntityType.SAVED_PLACE, "sv-plan", SavedPlace("sv-plan", "pc", "r1", PlaceCategory.SIGHT, status = PlaceStatus.PLANNED))
        seed(EntityType.DAY_ITEM, "i1", DayItem("i1", "d1", "a0", DayItemKind.PLACE, "pc", time = "10:00"))
    }

    @androidx.compose.runtime.Composable
    private fun Screen(date: String) {
        var snapshot by remember { mutableStateOf(TripSnapshot.from(store.all())) }
        fun refresh() { snapshot = TripSnapshot.from(store.all()) }
        WanderlogPlusTheme {
            DayDetailScreen(
                snapshot = snapshot,
                date = date,
                onBack = {},
                onAddSuggestion = { d, id -> editor.addSuggestionToDay(snapshot, d, id); refresh() },
                onMove = { id, up -> editor.moveDayItem(snapshot, id, up); refresh() },
                onRemove = { id -> editor.removeDayItem(snapshot, id); refresh() },
                onRestoreVersion = { d, at -> editor.restoreDayVersion(snapshot, d, at, now = 5000); refresh() },
            )
        }
    }

    private fun show(date: String) = rule.setContent { Screen(date) }

    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun tab(tag: String) = rule.onNodeWithTag(tag).performClick()

    private fun newItemId() = store.all().first { it.type == EntityType.DAY_ITEM && it.id != "i1" }.id

    /** Opens the ⋮ menu of [itemId] and clicks the entry [entry] ("up", "down" or "remove"). */
    private fun menu(itemId: String, entry: String) {
        rule.onNodeWithTag("item-menu-$itemId").performClick()
        rule.onNodeWithTag("item-$entry-$itemId").performClick()
    }

    @Test fun historyRestoresTheProgramBeforeAChatPlan() {
        seedTrip()
        // A chat plan replaced the museum by the shrine.
        editor.applyDayPlan(TripSnapshot.from(store.all()), "2027-05-09", listOf(de.wanderlogplus.client.PlanOp.AddSaved("sv-star", "09:00")), setOf("i1"), "Vor Chat-Plan", now = 1_800_000_000_000)
        show("2027-05-09")
        rule.onNode(hasText("Schrein Alpha") and hasAnyAncestor(hasTestTag("program-list"))).assertExists()
        rule.onNodeWithTag("program-row-i1").assertDoesNotExist()
        rule.onNodeWithTag("day-history-open").performClick()
        rule.onNodeWithText("Vor Chat-Plan", substring = true).assertExists()
        rule.onNodeWithText("10:00 Museum Gamma").assertExists()
        rule.onNodeWithTag("day-restore-1800000000000").performClick()
        rule.onNodeWithTag("program-row-i1").assertExists()
        rule.onNode(hasText("Schrein Alpha") and hasAnyAncestor(hasTestTag("program-list"))).assertDoesNotExist()
        // The plan is a version now, so it can come back.
        rule.onNodeWithTag("day-history-open").performClick()
        rule.onNodeWithTag("day-restore-5000").assertExists()
    }

    @Test fun suggestionsStarBeforeWarning() {
        seedTrip()
        show("2027-05-09")
        rule.onNodeWithText("Tempeltag").assertIsDisplayed()
        rule.onNodeWithTag("program-row-i1").assertExists()
        tab("tab-suggestions")
        assertTrue(top("suggestion-sv-star") < top("suggestion-sv-warn"))
        rule.onNodeWithTag("suggestion-sv-plan").assertDoesNotExist()
    }

    @Test fun addMovesSuggestionIntoProgramAndRemoveRestoresIt() {
        seedTrip()
        show("2027-05-09")
        tab("tab-suggestions")
        rule.onNodeWithTag("suggestion-add-sv-star").performClick()
        rule.onNodeWithTag("suggestion-sv-star").assertDoesNotExist()
        tab("tab-program")
        rule.onNode(hasText("Schrein Alpha") and hasAnyAncestor(hasTestTag("program-list"))).assertExists()

        menu(newItemId(), "remove")
        tab("tab-suggestions")
        rule.onNodeWithTag("suggestion-sv-star").assertExists()
    }

    @Test fun moveUpReordersProgram() {
        seedTrip()
        show("2027-05-09")
        tab("tab-suggestions")
        rule.onNodeWithTag("suggestion-add-sv-star").performClick()
        tab("tab-program")
        val newRow = newItemId()
        assertTrue(top("program-row-i1") < top("program-row-$newRow"))
        menu(newRow, "up")
        assertTrue(top("program-row-$newRow") < top("program-row-i1"))
    }

    @Test fun itemMenuMovesAndRemoves() {
        seedTrip()
        seed(EntityType.DAY_ITEM, "i2", DayItem("i2", "d1", "a1", DayItemKind.NOTE, text = "Bento kaufen"))
        show("2027-05-09")
        assertTrue(top("program-row-i1") < top("program-row-i2"))
        menu("i1", "down")
        assertTrue(top("program-row-i2") < top("program-row-i1"))
        menu("i1", "up")
        assertTrue(top("program-row-i1") < top("program-row-i2"))
        menu("i2", "remove")
        rule.onNodeWithTag("program-row-i2").assertDoesNotExist()
        rule.onNodeWithTag("program-row-i1").assertExists()
    }

    @Test fun travelDayWithoutRegionShowsHint() {
        seedTrip(withStay = false)
        show("2027-05-10")
        tab("tab-suggestions")
        rule.onNodeWithText("Reisetag – keine Vorschläge").assertIsDisplayed()
    }

    @Test fun weekdayFilterHidesPlacesClosedOnThatDay() {
        seedTrip()
        // 2027-05-09 is a Sunday; this place only opens on Mondays.
        seed(EntityType.PLACE, "pm", Place("pm", name = "Montagsladen", openingPeriods = listOf(OpeningPeriod(1, "09:00", 1, "17:00"))))
        seed(EntityType.SAVED_PLACE, "sv-mon", SavedPlace("sv-mon", "pm", "r1", PlaceCategory.SHOPPING))
        show("2027-05-09")
        tab("tab-suggestions")
        rule.onNodeWithTag("suggestion-sv-mon").assertExists()
        rule.onNodeWithTag("filter-weekday").performClick()
        rule.onNodeWithTag("suggestion-sv-mon").assertDoesNotExist()
        rule.onNodeWithTag("suggestion-sv-star").assertExists() // unknown hours pass the filter
    }

    @Test fun addOnDateWithoutDayCreatesDay() {
        seedTrip()
        show("2027-05-10")
        assertTrue(TripSnapshot.from(store.all()).days.none { it.date == "2027-05-10" })
        tab("tab-suggestions")
        rule.onNodeWithTag("suggestion-add-sv-star").performClick()
        assertTrue(TripSnapshot.from(store.all()).days.any { it.date == "2027-05-10" })
        tab("tab-program")
        rule.onNode(hasText("Schrein Alpha") and hasAnyAncestor(hasTestTag("program-list"))).assertExists()
    }

    @Test fun tabsSwitchContent() {
        seedTrip()
        show("2027-05-09")
        rule.onNodeWithTag("tab-program").assertIsSelected()
        rule.onNodeWithTag("program-list").assertExists()
        rule.onNodeWithTag("day-add").assertExists()
        rule.onNodeWithText("Vorschläge (2)").assertExists()

        tab("tab-suggestions")
        rule.onNodeWithTag("program-list").assertDoesNotExist()
        rule.onNodeWithTag("day-add").assertDoesNotExist()
        rule.onNodeWithTag("suggestion-list").assertExists()

        tab("tab-notes")
        rule.onNodeWithTag("suggestion-list").assertDoesNotExist()
        rule.onNodeWithTag("day-note").assertIsDisplayed()

        tab("tab-program")
        rule.onNodeWithTag("program-list").assertExists()
    }

    @Test fun selectedTabSurvivesRecreation() {
        seedTrip()
        val tester = StateRestorationTester(rule)
        tester.setContent { Screen("2027-05-09") }
        tab("tab-notes")
        rule.onNodeWithTag("day-note").assertExists()
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("tab-notes").assertIsSelected()
        rule.onNodeWithTag("day-note").assertExists()
        rule.onNodeWithTag("program-list").assertDoesNotExist()
    }

    @Test fun fabOffersPlaceAndNote() {
        seedTrip()
        show("2027-05-09")
        rule.onNodeWithTag("day-add").performClick()
        rule.onNodeWithTag("add-from-suggestions").assertExists()
        rule.onNodeWithTag("add-note-open").assertExists()
        rule.onNodeWithTag("add-from-suggestions").performSemanticsAction(SemanticsActions.OnClick) // touch does not reach sheets under Robolectric
        rule.onNodeWithTag("tab-suggestions").assertIsSelected()
        rule.onNodeWithTag("suggestion-list").assertExists()

        tab("tab-program")
        rule.onNodeWithTag("day-add").performClick()
        rule.onNodeWithTag("add-note-open").performSemanticsAction(SemanticsActions.OnClick) // touch does not reach sheets under Robolectric
        rule.onNodeWithTag("add-note-field").assertExists()
        rule.onNodeWithTag("add-note-confirm").assertExists()
    }

    @Test fun emptyProgramHint() {
        seedTrip(withStay = false)
        show("2027-05-10")
        rule.onNodeWithText("Noch nichts geplant – füge Orte aus den Vorschlägen hinzu.").assertIsDisplayed()
    }

    @Test fun tripListShowsDaysRegionPlannedCountAndTravelDay() {
        seedTrip()
        var opened: String? = null
        rule.setContent { WanderlogPlusTheme { TripScreen(TripSnapshot.from(store.all()), onOpenDay = { opened = it }) } }
        rule.onNodeWithText("So 09.05.").assertIsDisplayed()
        rule.onNodeWithText("Tempeltag").assertIsDisplayed()
        rule.onNode(hasText("Reisetag", substring = true) and hasTestTag("day-2027-05-08")).assertExists()
        // Without a title the region name stands in.
        rule.onNode(hasText("Kyoto") and hasTestTag("day-2027-05-10")).assertExists()
        rule.onNode(hasText("1 geplant", substring = true) and hasTestTag("day-2027-05-09")).assertExists()
        rule.onNodeWithTag("day-2027-05-09").performClick()
        assertEquals("2027-05-09", opened)
    }

    @Test fun tripListTravelDayFallbackTitleIsNotRepeated() {
        seedTrip(withStay = false) // no stay: no region, every day is a travel day
        seed(EntityType.DAY, "d3", Day("d3", "2027-05-11", "Reisetag"))
        rule.setContent { WanderlogPlusTheme { TripScreen(TripSnapshot.from(store.all()), onOpenDay = {}) } }
        // Untitled travel day: "Reisetag" is the title, the subtitle does not repeat it.
        rule.onNode(hasText("Reisetag") and hasTestTag("day-2027-05-10")).assertExists()
        rule.onNode(hasText("Noch nichts geplant") and hasTestTag("day-2027-05-10")).assertExists()
        // A day the user titled himself keeps the travel-day hint, whatever the title says.
        rule.onNode(hasText("Tempeltag") and hasTestTag("day-2027-05-09")).assertExists()
        rule.onNode(hasText("Reisetag", substring = true) and hasText("1 geplant", substring = true) and hasTestTag("day-2027-05-09")).assertExists()
        // Even when that title is literally "Reisetag": the subtitle carries the hint instead of "Noch nichts geplant".
        rule.onNode(hasText("Noch nichts geplant") and hasTestTag("day-2027-05-11")).assertDoesNotExist()
        rule.onNode(hasText("Noch nichts geplant") and hasTestTag("day-2027-05-12")).assertExists()
    }

    @Test fun tripListShowsMovementSymbolAndHighlightsToday() {
        seedTrip()
        seed(
            EntityType.TRAIN, "tr1",
            Train("tr1", carrier = "Shinkansen", depart = Endpoint("Kyoto", date = "2027-05-10", time = "09:00"), arrive = Endpoint("Osaka", date = "2027-05-10", time = "09:30")),
        )
        rule.setContent { WanderlogPlusTheme { TripScreen(TripSnapshot.from(store.all()), onOpenDay = {}, today = "2027-05-09") } }
        rule.onNode(hasText("🚄", substring = true) and hasTestTag("day-2027-05-10")).assertExists()
        rule.onNode(hasText("Heute") and hasTestTag("day-2027-05-09")).assertExists()
        rule.onNode(hasText("Heute") and hasTestTag("day-2027-05-10")).assertDoesNotExist()
    }
}
