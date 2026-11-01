package de.wanderlogplus.app.ui.more

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.client.keepApp
import de.wanderlogplus.client.takeWanderlog
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Note
import de.wanderlogplus.shared.sync.*
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class ConflictsScreenTest {
    @get:Rule val rule = createComposeRule()

    private val store = InMemoryEntityStore()
    private val outbox = InMemoryOutbox()
    private val editor = LocalEditor(store, outbox, HlcClock("dev-a", now = { 1000L }))

    private fun seedConflict(wanderlog: String = "Wanderlog Titel") {
        val fields: Map<String, JsonElement> = EntityCodec.fields(Note("n1", title = "App Titel", body = "")) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(EntityType.NOTE, "n1")
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(EntityType.NOTE, "n1", k, v, Hlc(1, 0, "importer").encode()))!! }
        rec = rec.apply(FieldChange(EntityType.NOTE, "n1", "_conflict.title", JsonPrimitive(wanderlog), Hlc(2, 0, "importer").encode()))!!
        store.put(rec)
    }

    private fun show() {
        rule.setContent {
            var snapshot by remember { mutableStateOf(TripSnapshot.from(store.all())) }
            WanderlogPlusTheme {
                ConflictsScreen(
                    snapshot,
                    onKeepApp = { editor.keepApp(it); snapshot = TripSnapshot.from(store.all()) },
                    onTakeWanderlog = { editor.takeWanderlog(it); snapshot = TripSnapshot.from(store.all()) },
                )
            }
        }
    }

    @Test fun showsBothValuesWithoutQuotes() {
        seedConflict()
        show()
        rule.onAllNodesWithText("App Titel").assertCountEquals(2) // label and App value
        rule.onNodeWithText("Wanderlog Titel").assertIsDisplayed()
        rule.onNodeWithText("Titel").assertIsDisplayed() // German label of the field "title"
    }

    @Test fun takeWanderlogClearsListAndWritesBothFields() {
        seedConflict()
        show()
        rule.onNodeWithText("Wanderlog übernehmen").performClick()
        rule.onNodeWithText("Keine Konflikte").assertIsDisplayed()
        val changes = outbox.peek(100).map { it.change }
        assertTrue(changes.any { it.field == "title" } && changes.any { it.field == "_conflict.title" })
        assertEquals(JsonPrimitive("Wanderlog Titel"), changes.last { it.field == "title" }.value)
        assertEquals(JsonNull, changes.last { it.field == "_conflict.title" }.value)
    }

    @Test fun keepAppOnlyClearsMarker() {
        seedConflict()
        show()
        rule.onNodeWithText("App behalten").performClick()
        rule.onNodeWithText("Keine Konflikte").assertIsDisplayed()
        assertEquals(listOf("_conflict.title"), outbox.peek(100).map { it.change.field })
    }

    @Test fun longTextsAreTruncatedWithMoreToggle() {
        val long = "x".repeat(300)
        seedConflict(long)
        show()
        rule.onNodeWithText(long).assertDoesNotExist()
        rule.onNodeWithText("mehr").performClick()
        rule.onNodeWithText(long).assertIsDisplayed()
    }

    @Test fun displayTextFormatsPrimitivesAndOthers() {
        assertEquals("abc", conflictValueText(JsonPrimitive("abc")))
        assertEquals("12", conflictValueText(JsonPrimitive(12)))
        assertEquals("null", conflictValueText(JsonNull))
        assertEquals("[\"a\",\"b\"]", conflictValueText(buildJsonArray { add(JsonPrimitive("a")); add(JsonPrimitive("b")) }))
    }

    @Test fun staleConflictIsNotResolvedBlindly() {
        seedConflict()
        val stale = TripSnapshot.from(store.all()).conflicts.single()
        val rec = store.get(EntityType.NOTE, "n1")!!
            .apply(FieldChange(EntityType.NOTE, "n1", "_conflict.title", JsonPrimitive("Neuer Wert"), Hlc(3, 0, "importer").encode()))!!
        store.put(rec)
        assertEquals(false, resolveConflict(editor, TripSnapshot.from(store.all()), stale, takeWanderlog = true))
        assertTrue(outbox.peek(100).isEmpty())
    }
}
