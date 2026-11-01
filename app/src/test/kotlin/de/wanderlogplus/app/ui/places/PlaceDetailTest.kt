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
import org.robolectric.annotation.GraphicsMode

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

    // Native graphics: only real text measurement tells that the one-line hero title is cut.
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    @Test fun longNameIsShownInFullBelowHero() {
        val long = "Hotel Gion Shinmonzen Kyoto Higashiyama Annex am Shirakawa-Kanal"
        seedPlace(Place("p1", name = long))
        show()
        rule.onNode(hasTestTag("place-full-name") and hasText(long)).assertIsDisplayed()
    }

    @Test fun shortNameIsNotRepeated() {
        seedPlace()
        show()
        rule.onNodeWithText("Schrein Alpha").assertExists()
        rule.onNodeWithTag("place-full-name").assertDoesNotExist()
    }

    @Test fun editNoteSavesAndShowsNewText() {
        seedPlace()
        show()
        rule.onNodeWithTag("tab-note").performClick()
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
        rule.onNodeWithTag("tab-note").performClick()
        rule.onNodeWithText("Bearbeiten").performClick()
        rule.onNodeWithTag("note-field").performTextClearance()
        rule.onNodeWithTag("note-field").performTextInput("Sofort sichtbar")
        rule.onNodeWithText("Speichern").performClick()
        rule.onNodeWithText("Sofort sichtbar").assertIsDisplayed()
    }

    @Test fun cancelEditWritesNothing() {
        seedPlace()
        show()
        rule.onNodeWithTag("tab-note").performClick()
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

    @Test fun showsNavigationButtons() {
        seedPlace(Place("p1", name = "Schrein Alpha", lat = 34.9, lng = 135.7))
        val opened = mutableListOf<de.wanderlogplus.app.links.ExternalLink>()
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(
                    TripSnapshot.from(store.all()), "sv1", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> },
                    onOpenLink = { opened += it },
                )
            }
        }
        rule.onNodeWithTag("link-NAVIGATE").assertExists()
        rule.onNodeWithTag("link-TRANSIT").assertExists()
        rule.onNodeWithTag("link-TABELOG").assertDoesNotExist()
        rule.onNodeWithTag("link-TRANSIT").performClick()
        assertEquals(de.wanderlogplus.app.links.LinkKind.TRANSIT, opened.single().kind)
    }

    @Test fun noLinkButtonsWithoutHandler() {
        seedPlace(Place("p1", name = "Schrein Alpha", lat = 34.9, lng = 135.7))
        show()
        rule.onNodeWithTag("link-NAVIGATE").assertDoesNotExist()
    }

    @Test fun placeDetailTabs() {
        seedPlace(Place("p1", name = "Schrein Alpha", description = "Alter Schrein"))
        seed(EntityType.ATTACHMENT, "at1", Attachment("at1", EntityType.SAVED_PLACE, "sv1", "a".repeat(64), "Plan.pdf", "application/pdf", 1000))
        val actions = de.wanderlogplus.app.ui.attachments.AttachmentActions(
            available = { false }, onAdd = { _, _ -> }, onAddToDay = {}, onOpen = {}, onDelete = {},
        )
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(
                    TripSnapshot.from(store.all()), "sv1", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> },
                    attachments = actions,
                )
            }
        }
        rule.onNodeWithTag("hero").assertExists()
        rule.onNodeWithTag("hero-edit").assertExists()
        rule.onNodeWithTag("tab-info").assertExists()
        // Info is the default tab.
        rule.onNodeWithText("Alter Schrein").assertIsDisplayed()
        rule.onNodeWithTag("hours-Mo").assertExists()
        rule.onNodeWithTag("place-note").assertDoesNotExist()
        rule.onNodeWithTag("tab-note").performClick()
        rule.onNodeWithTag("place-note").assertIsDisplayed()
        rule.onNodeWithTag("hours-Mo").assertDoesNotExist()
        rule.onNodeWithTag("attachment-at1").assertDoesNotExist()
        rule.onNodeWithTag("tab-files").performClick()
        rule.onNodeWithTag("attachment-at1").assertExists()
        rule.onNodeWithTag("attachment-add").assertExists()
        rule.onNodeWithTag("place-note").assertDoesNotExist()
        // Status chips stay above the tabs.
        rule.onNodeWithText("Besucht").assertIsDisplayed()
    }

    @Test fun todayHighlightedInHours() {
        seedPlace(Place("p1", name = "Schrein Alpha", openingPeriods = listOf(OpeningPeriod(3, "09:00", 3, "17:00"))))
        rule.setContent {
            WanderlogPlusTheme {
                // 2027-05-12 is a Wednesday.
                PlaceDetailScreen(
                    TripSnapshot.from(store.all()), "sv1", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> },
                    today = "2027-05-12",
                )
            }
        }
        rule.onNode(hasText("heute") and hasAnyAncestor(hasTestTag("hours-Mi"))).assertExists()
        rule.onNode(hasText("heute") and hasAnyAncestor(hasTestTag("hours-Mo"))).assertDoesNotExist()
        rule.onNode(hasText("09:00–17:00") and hasAnyAncestor(hasTestTag("hours-Mi"))).assertExists()
    }

    @Test fun noTodayHighlightWithoutDate() {
        seedPlace()
        show()
        rule.onNode(hasText("heute")).assertDoesNotExist()
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
        rule.onNodeWithTag("tab-note").performClick()
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
