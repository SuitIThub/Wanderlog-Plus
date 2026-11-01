package de.wanderlogplus.app.ui.places

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class PlaceEditTest {
    @get:Rule val rule = createComposeRule()

    private val store = InMemoryEntityStore()

    private inline fun <reified T> seed(type: EntityType, id: String, entity: T) {
        val fields: Map<String, JsonElement> = EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun seedPlace(tags: List<String>) {
        seed(EntityType.REGION, "r1", Region("r1", "Kyoto", "#3498db"))
        seed(EntityType.PLACE, "p1", Place("p1", name = "Schrein Alpha"))
        seed(EntityType.SAVED_PLACE, "sv1", SavedPlace("sv1", "p1", "r1", PlaceCategory.SIGHT, tags = tags))
    }

    @Test fun starToggleKeepsOtherTags() {
        seedPlace(listOf("🍜", "⭐"))
        val calls = mutableListOf<List<String>>()
        rule.setContent {
            WanderlogPlusTheme {
                var tags by remember { mutableStateOf(listOf("🍜", "⭐")) }
                val base = TripSnapshot.from(store.all())
                val snap = base.copy(savedPlaces = base.savedPlaces.map { it.copy(tags = tags) })
                PlaceDetailScreen(
                    snap, "sv1", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> },
                    onSetTags = { _, t -> calls += t; tags = t },
                )
            }
        }
        rule.onNodeWithTag("tag-star").performClick()
        rule.onNodeWithTag("tag-star").performClick()
        assertEquals(listOf(listOf("🍜"), listOf("🍜", "⭐")), calls)
    }

    @Test fun warnToggleAppendsWarning() {
        seedPlace(listOf("🍜"))
        val calls = mutableListOf<List<String>>()
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(
                    TripSnapshot.from(store.all()), "sv1", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> },
                    onSetTags = { _, t -> calls += t },
                )
            }
        }
        rule.onNodeWithTag("tag-warn").performClick()
        assertEquals(listOf(listOf("🍜", "⚠️")), calls)
    }

    @Test fun categoryDropdownCallsSetCategory() {
        seedPlace(emptyList())
        val calls = mutableListOf<PlaceCategory>()
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(
                    TripSnapshot.from(store.all()), "sv1", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> },
                    onSetCategory = { _, c -> calls += c },
                )
            }
        }
        rule.onNodeWithTag("category-dropdown").performClick()
        rule.onNodeWithTag("category-dropdown-option-1").performClick() // FOOD
        assertEquals(listOf(PlaceCategory.FOOD), calls)
    }

    @Test fun regionDropdownOffersNoRegion() {
        seedPlace(emptyList())
        val calls = mutableListOf<String?>()
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(
                    TripSnapshot.from(store.all()), "sv1", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> },
                    onSetRegion = { _, r -> calls += r },
                )
            }
        }
        rule.onNodeWithTag("place-region-dropdown").performClick()
        rule.onNodeWithTag("place-region-dropdown-option-0").performClick() // Keine Region
        assertEquals(listOf<String?>(null), calls)
    }
}
