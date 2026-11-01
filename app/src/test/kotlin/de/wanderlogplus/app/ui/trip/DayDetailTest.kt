package de.wanderlogplus.app.ui.trip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.*
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
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

    private fun show(date: String) {
        rule.setContent {
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
                )
            }
        }
    }

    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    private fun button(label: String, rowTag: String) =
        rule.onNode(hasText(label) and hasAnyAncestor(hasTestTag(rowTag)))

    @Test fun suggestionsStarBeforeWarning() {
        seedTrip()
        show("2027-05-09")
        rule.onNodeWithText("Tempeltag").assertIsDisplayed()
        assertTrue(top("suggestion-sv-star") < top("suggestion-sv-warn"))
        rule.onNodeWithTag("program-row-i1").assertExists()
        rule.onNodeWithTag("suggestion-sv-plan").assertDoesNotExist()
    }

    @Test fun addMovesSuggestionIntoProgramAndRemoveRestoresIt() {
        seedTrip()
        show("2027-05-09")
        button("Zum Tag hinzufügen", "suggestion-sv-star").performClick()
        rule.onNodeWithTag("suggestion-sv-star").assertDoesNotExist()
        rule.onNode(hasText("Schrein Alpha") and hasAnyAncestor(hasTestTag("program-list"))).assertExists()

        val newRow = store.all().first { it.type == EntityType.DAY_ITEM && it.id != "i1" }.id
        button("Entfernen", "program-row-$newRow").performClick()
        rule.onNodeWithTag("suggestion-sv-star").assertExists()
    }

    @Test fun moveUpReordersProgram() {
        seedTrip()
        show("2027-05-09")
        button("Zum Tag hinzufügen", "suggestion-sv-star").performClick()
        val newRow = store.all().first { it.type == EntityType.DAY_ITEM && it.id != "i1" }.id
        assertTrue(top("program-row-i1") < top("program-row-$newRow"))
        button("Nach oben", "program-row-$newRow").performClick()
        assertTrue(top("program-row-$newRow") < top("program-row-i1"))
    }

    @Test fun travelDayWithoutRegionShowsHint() {
        seedTrip(withStay = false)
        show("2027-05-10")
        rule.onNodeWithText("Reisetag – keine Vorschläge").assertIsDisplayed()
    }

    @Test fun weekdaySwitchHidesPlacesClosedOnThatDay() {
        seedTrip()
        // 2027-05-09 is a Sunday; this place only opens on Mondays.
        seed(EntityType.PLACE, "pm", Place("pm", name = "Montagsladen", openingPeriods = listOf(OpeningPeriod(1, "09:00", 1, "17:00"))))
        seed(EntityType.SAVED_PLACE, "sv-mon", SavedPlace("sv-mon", "pm", "r1", PlaceCategory.SHOPPING))
        show("2027-05-09")
        rule.onNodeWithTag("suggestion-sv-mon").assertExists()
        rule.onNode(isToggleable()).performClick()
        rule.onNodeWithTag("suggestion-sv-mon").assertDoesNotExist()
        rule.onNodeWithTag("suggestion-sv-star").assertExists() // unknown hours pass the filter
    }

    @Test fun addOnDateWithoutDayCreatesDay() {
        seedTrip()
        show("2027-05-10")
        assertTrue(TripSnapshot.from(store.all()).days.none { it.date == "2027-05-10" })
        button("Zum Tag hinzufügen", "suggestion-sv-star").performClick()
        assertTrue(TripSnapshot.from(store.all()).days.any { it.date == "2027-05-10" })
        rule.onNode(hasText("Schrein Alpha") and hasAnyAncestor(hasTestTag("program-list"))).assertExists()
    }

    @Test fun tripListShowsDaysRegionStayAndTravelDay() {
        seedTrip()
        var opened: String? = null
        rule.setContent { WanderlogPlusTheme { TripScreen(TripSnapshot.from(store.all()), onOpenDay = { opened = it }) } }
        rule.onNodeWithText("So, 09.05.").assertIsDisplayed()
        rule.onNodeWithText("Tempeltag").assertIsDisplayed()
        rule.onNode(hasText("Reisetag") and hasTestTag("day-2027-05-08")).assertExists()
        rule.onNode(hasText("Kyoto") and hasTestTag("day-2027-05-09")).assertExists()
        rule.onNode(hasText("Hotel Sakura") and hasTestTag("day-2027-05-09")).assertExists()
        rule.onNodeWithTag("day-2027-05-09").performClick()
        assertTrue(opened == "2027-05-09")
    }
}
