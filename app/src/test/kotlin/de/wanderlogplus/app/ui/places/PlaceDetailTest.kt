package de.wanderlogplus.app.ui.places

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import de.wanderlogplus.app.ui.openingHoursLines
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
class PlaceDetailTest {
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

    private fun seedPlace(place: Place = Place("p1", name = "Schrein Alpha")) {
        seed(EntityType.REGION, "r1", Region("r1", "Kyoto", "#3498db"))
        seed(EntityType.PLACE, place.id, place)
        seed(
            EntityType.SAVED_PLACE, "sv1",
            SavedPlace("sv1", place.id, "r1", PlaceCategory.SIGHT, note = "alt", tags = listOf("⭐ Muss man sehen")),
        )
    }

    private fun show() {
        rule.setContent {
            var snapshot by remember { mutableStateOf(TripSnapshot.from(store.all())) }
            WanderlogPlusTheme {
                PlaceDetailScreen(
                    snapshot = snapshot,
                    savedPlaceId = "sv1",
                    onBack = {},
                    onSaveNote = { id, note ->
                        editor.set(EntityType.SAVED_PLACE, id, mapOf("note" to JsonPrimitive(note)))
                        snapshot = TripSnapshot.from(store.all())
                    },
                    onSetStatus = { id, status ->
                        editor.set(EntityType.SAVED_PLACE, id, mapOf("status" to JsonPrimitive(status.name)))
                        snapshot = TripSnapshot.from(store.all())
                    },
                )
            }
        }
    }

    private fun outboxValue(field: String): JsonElement? =
        outbox.peek(100).map { it.change }.lastOrNull { it.field == field }?.value

    @Test fun editNoteSavesAndShowsNewText() {
        seedPlace()
        show()
        rule.onNodeWithText("alt").assertIsDisplayed()
        rule.onNodeWithText("Bearbeiten").performClick()
        rule.onNodeWithTag("note-field").performTextClearance()
        rule.onNodeWithTag("note-field").performTextInput("Früh hingehen")
        rule.onNodeWithText("Speichern").performClick()
        assertEquals(JsonPrimitive("Früh hingehen"), outboxValue("note"))
        rule.onNodeWithText("Früh hingehen").assertIsDisplayed()
        rule.onNodeWithTag("note-field").assertDoesNotExist()
    }

    @Test fun savedNoteShowsImmediatelyBeforeSnapshotCatchesUp() {
        seedPlace()
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(TripSnapshot.from(store.all()), "sv1", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> })
            }
        }
        rule.onNodeWithText("Bearbeiten").performClick()
        rule.onNodeWithTag("note-field").performTextClearance()
        rule.onNodeWithTag("note-field").performTextInput("Sofort sichtbar")
        rule.onNodeWithText("Speichern").performClick()
        rule.onNodeWithText("Sofort sichtbar").assertIsDisplayed()
    }

    @Test fun cancelEditWritesNothing() {
        seedPlace()
        show()
        rule.onNodeWithText("Bearbeiten").performClick()
        rule.onNodeWithTag("note-field").performTextInput("x")
        rule.onNodeWithText("Abbrechen").performClick()
        assertTrue(outbox.peek(100).isEmpty())
        rule.onNodeWithText("alt").assertIsDisplayed()
    }

    @Test fun statusVisitedWritesEnumName() {
        seedPlace()
        show()
        rule.onNodeWithText("Besucht").performClick()
        assertEquals(JsonPrimitive("VISITED"), outboxValue("status"))
    }

    @Test fun placeWithoutOpeningHoursShowsUnbekannt() {
        seedPlace()
        show()
        rule.onNode(hasText("unbekannt") and hasAnyAncestor(hasTestTag("hours-Mo"))).assertExists()
        rule.onNode(hasText("unbekannt") and hasAnyAncestor(hasTestTag("hours-So"))).assertExists()
    }

    @Test fun showsFactsRatingDurationTagsAndHours() {
        seedPlace(
            Place(
                "p1", name = "Schrein Alpha", rating = 4.5, numRatings = 1234, minMinutes = 60, maxMinutes = 90,
                address = "Gion 1", description = "Alter Schrein",
                openingPeriods = listOf(OpeningPeriod(1, "09:00", 1, "17:00")),
            ),
        )
        show()
        rule.onNodeWithText("★ 4,5 (1.234)").assertIsDisplayed()
        rule.onNodeWithText("60–90 Min.", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Gion 1").assertIsDisplayed()
        rule.onNodeWithText("Alter Schrein").assertIsDisplayed()
        rule.onNodeWithText("⭐ Muss man sehen", substring = true).assertIsDisplayed()
        rule.onNode(hasText("09:00–17:00") and hasAnyAncestor(hasTestTag("hours-Mo"))).assertExists()
        rule.onNode(hasText("geschlossen") and hasAnyAncestor(hasTestTag("hours-Di"))).assertExists()
    }

    @Test fun openingHoursLinesRules() {
        val lines = openingHoursLines(
            listOf(
                OpeningPeriod(1, "13:00", 1, "17:00"),
                OpeningPeriod(1, "09:00", 1, "12:00"),
                OpeningPeriod(0, "00:00", null, null),
            ),
        ).toMap()
        assertEquals("09:00–12:00, 13:00–17:00", lines["Mo"])
        assertEquals("24 Stunden geöffnet", lines["So"])
        assertEquals("geschlossen", lines["Mi"])
        assertEquals(listOf("Mo", "Di", "Mi", "Do", "Fr", "Sa", "So"), openingHoursLines(emptyList()).map { it.first })
    }

    @Test fun failedSaveDropsOptimisticNote() {
        seedPlace()
        var failures by mutableStateOf(0)
        rule.setContent {
            val snapshot = remember { TripSnapshot.from(store.all()) }
            WanderlogPlusTheme {
                PlaceDetailScreen(snapshot, "sv1", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }, failureCount = failures)
            }
        }
        rule.onNodeWithText("Bearbeiten").performClick()
        rule.onNodeWithTag("note-field").performTextClearance()
        rule.onNodeWithTag("note-field").performTextInput("neu")
        rule.onNodeWithText("Speichern").performClick()
        rule.onNodeWithTag("place-note").assertIsDisplayed()
        rule.onNode(hasText("neu", substring = true)).assertIsDisplayed()
        failures = 1
        rule.waitForIdle()
        rule.onNode(hasText("alt", substring = true)).assertIsDisplayed()
    }
}
