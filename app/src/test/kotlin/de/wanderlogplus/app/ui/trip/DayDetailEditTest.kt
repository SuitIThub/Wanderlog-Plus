package de.wanderlogplus.app.ui.trip

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class DayDetailEditTest {
    @get:Rule val rule = createComposeRule()

    private val store = InMemoryEntityStore()

    private inline fun <reified T> seed(type: EntityType, id: String, entity: T) {
        val fields: Map<String, JsonElement> = EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun seedTrip(override: String? = null) {
        seed(EntityType.TRIP, "t1", Trip("t1", "Japan", "2027-05-08", "2027-05-12"))
        seed(EntityType.REGION, "r1", Region("r1", "Kyoto", "#3498db"))
        seed(EntityType.REGION, "r2", Region("r2", "Osaka", "#e74c3c"))
        seed(EntityType.PLACE, "hotel", Place("hotel", name = "Hotel Sakura"))
        seed(EntityType.STAY, "s0", Stay("s0", "hotel", "r1", "2027-05-08", "2027-05-12"))
        seed(EntityType.DAY, "d1", Day("d1", "2027-05-09", "Tempeltag", "alte Notiz", regionOverrideId = override))
        seed(EntityType.DAY_ITEM, "n1", DayItem("n1", "d1", "a0", DayItemKind.NOTE, text = "Bento kaufen"))
    }

    private class Calls {
        val titles = mutableListOf<Pair<String, String>>()
        val notes = mutableListOf<Pair<String, String>>()
        val regions = mutableListOf<Pair<String, String?>>()
        val added = mutableListOf<Pair<String, String>>()
    }

    private fun show(c: Calls) {
        rule.setContent {
            WanderlogPlusTheme {
                DayDetailScreen(
                    snapshot = TripSnapshot.from(store.all()),
                    date = "2027-05-09",
                    onBack = {},
                    onAddSuggestion = { _, _ -> },
                    onMove = { _, _ -> },
                    onRemove = {},
                    onSetTitle = { d, t -> c.titles += d to t },
                    onSetNote = { d, t -> c.notes += d to t },
                    onSetRegion = { d, r -> c.regions += d to r },
                    onAddNote = { d, t -> c.added += d to t },
                )
            }
        }
    }

    @Test fun editTitleCallsSetDayTitle() {
        seedTrip()
        val c = Calls()
        show(c)
        rule.onNodeWithTag("day-title-edit").performClick()
        rule.onNodeWithTag("day-title-field").performTextClearance()
        rule.onNodeWithTag("day-title-field").performTextInput("Neuer Titel")
        rule.onNodeWithText("Speichern").performClick()
        assertEquals(listOf("2027-05-09" to "Neuer Titel"), c.titles)
    }

    @Test fun editNoteCallsSetDayNote() {
        seedTrip()
        val c = Calls()
        show(c)
        rule.onNodeWithText("alte Notiz").assertIsDisplayed()
        rule.onNodeWithText("Bearbeiten").performClick()
        rule.onNodeWithTag("day-note-field").performTextClearance()
        rule.onNodeWithTag("day-note-field").performTextInput("neu")
        rule.onNodeWithText("Speichern").performClick()
        assertEquals(listOf("2027-05-09" to "neu"), c.notes)
        rule.onNodeWithText("neu").assertIsDisplayed()
    }

    @Test fun regionDropdownOffersAutomaticAndRegions() {
        seedTrip()
        val c = Calls()
        show(c)
        rule.onNodeWithTag("region-dropdown").performClick()
        rule.onNode(hasText("Automatisch (Kyoto)") and hasTestTag("region-dropdown-option-0")).assertExists()
        rule.onNode(hasText("Osaka") and hasTestTag("region-dropdown-option-2")).assertExists()
        rule.onNodeWithTag("region-dropdown-option-2").performClick()
        assertEquals(listOf("2027-05-09" to "r2"), c.regions)
    }

    @Test fun choosingAutomaticClearsOverride() {
        seedTrip(override = "r2")
        val c = Calls()
        show(c)
        rule.onNodeWithTag("region-dropdown").performClick()
        rule.onNodeWithTag("region-dropdown-option-0").performClick()
        assertEquals(listOf("2027-05-09" to null), c.regions)
    }

    @Test fun noteItemRendersText() {
        seedTrip()
        show(Calls())
        rule.onNode(hasText("Bento kaufen") and hasAnyAncestor(hasTestTag("program-row-n1"))).assertIsDisplayed()
        rule.onNode(hasText("Entfernen") and hasAnyAncestor(hasTestTag("program-row-n1"))).assertExists()
    }

    @Test fun addNoteCallsAddNote() {
        seedTrip()
        val c = Calls()
        show(c)
        rule.onNodeWithText("Notiz zum Programm hinzufügen").performClick()
        rule.onNodeWithTag("add-note-field").assertIsDisplayed()
        rule.onNodeWithTag("add-note-field").performTextInput("Tickets holen")
        rule.onNodeWithTag("add-note-confirm").performClick()
        assertEquals(listOf("2027-05-09" to "Tickets holen"), c.added)
    }
}
