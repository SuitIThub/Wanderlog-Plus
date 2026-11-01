package de.wanderlogplus.app.ui.places

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class PlacesScreenTest {
    @get:Rule val rule = createComposeRule()

    private val store = InMemoryEntityStore()

    private inline fun <reified T> seed(type: EntityType, id: String, entity: T) {
        val fields: Map<String, JsonElement> = EntityCodec.fields(entity) + ("deleted" to JsonPrimitive(false))
        var rec = EntityRecord(type, id)
        fields.forEach { (k, v) -> rec = rec.apply(FieldChange(type, id, k, v, Hlc(1, 0, "importer").encode()))!! }
        store.put(rec)
    }

    private fun seedData() {
        seed(EntityType.TRIP, "t1", Trip("t1", "Japan", "2027-05-08", "2027-05-12"))
        seed(EntityType.REGION, "r1", Region("r1", "Kyoto", "#3498db"))
        seed(EntityType.PLACE, "pa", Place("pa", name = "Schrein Alpha", rating = 4.0))
        seed(EntityType.PLACE, "pb", Place("pb", name = "Tempel Beta", rating = 4.9))
        seed(EntityType.PLACE, "pf", Place("pf", name = "Ramen Fuji"))
        seed(EntityType.PLACE, "po", Place("po", name = "Lose Idee"))
        seed(EntityType.SAVED_PLACE, "sv-a", SavedPlace("sv-a", "pa", "r1", PlaceCategory.SIGHT, tags = listOf("⭐ Muss")))
        seed(EntityType.SAVED_PLACE, "sv-b", SavedPlace("sv-b", "pb", "r1", PlaceCategory.SIGHT, status = PlaceStatus.VISITED))
        seed(EntityType.SAVED_PLACE, "sv-f", SavedPlace("sv-f", "pf", "r1", PlaceCategory.FOOD))
        seed(EntityType.SAVED_PLACE, "sv-o", SavedPlace("sv-o", "po", null, PlaceCategory.OTHER))
    }

    private fun top(tag: String) = rule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot.top

    @Test fun regionListShowsCountsAndOpensRegion() {
        seedData()
        var opened: String? = null
        rule.setContent { WanderlogPlusTheme { PlacesScreen(TripSnapshot.from(store.all()), onOpenRegion = { opened = it }) } }
        rule.onNode(hasText("3 Orte") and hasTestTag("region-r1")).assertExists()
        rule.onNode(hasText("1 Ort") and hasTestTag("region-none")).assertExists()
        rule.onNode(hasText("Ohne Region") and hasTestTag("region-none")).assertIsDisplayed()
        rule.onNodeWithTag("region-r1").performClick()
        assertEquals("r1", opened)
    }

    @Test fun regionPlacesGroupedByCategoryAndSortedWithStatusLabel() {
        seedData()
        var opened: String? = null
        rule.setContent {
            WanderlogPlusTheme {
                RegionPlacesScreen(TripSnapshot.from(store.all()), "r1", onBack = {}, onOpenPlace = { opened = it })
            }
        }
        assertTrue(top("category-SIGHT") < top("category-FOOD"))
        // The star-tagged place leads despite the lower rating.
        assertTrue(top("place-sv-a") < top("place-sv-b"))
        assertTrue(top("place-sv-b") < top("category-FOOD"))
        rule.onNode(hasText("Besucht") and hasTestTag("place-sv-b")).assertExists()
        rule.onNode(hasText("Idee") and hasTestTag("place-sv-a")).assertExists()
        rule.onNodeWithTag("place-sv-o").assertDoesNotExist()
        rule.onNodeWithTag("place-sv-f").performClick()
        assertEquals("sv-f", opened)
    }

    @Test fun noRegionKeyListsUnassignedPlaces() {
        seedData()
        rule.setContent {
            WanderlogPlusTheme {
                RegionPlacesScreen(TripSnapshot.from(store.all()), NO_REGION, onBack = {}, onOpenPlace = {})
            }
        }
        rule.onNodeWithTag("place-sv-o").assertIsDisplayed()
        rule.onNodeWithTag("place-sv-a").assertDoesNotExist()
    }
}
