package de.wanderlogplus.app.ui.places

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.SemanticsActions
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

    @Test fun regionTilesShowCounts() {
        seedData()
        // Ids sort the day trip ("r0") before its main region ("r1"); the tiles must still put it right after r1.
        seed(EntityType.REGION, "r0", Region("r0", "Nara", "#2ecc71", dayTripFromRegionId = "r1"))
        seed(EntityType.REGION, "r5", Region("r5", "Osaka", "#e74c3c"))
        seed(EntityType.PLACE, "pn", Place("pn", name = "Hirschpark"))
        seed(EntityType.SAVED_PLACE, "sv-n", SavedPlace("sv-n", "pn", "r0", PlaceCategory.SIGHT))
        rule.setContent { WanderlogPlusTheme { PlacesScreen(TripSnapshot.from(store.all()), onOpenRegion = {}) } }
        rule.onNode(hasText("Kyoto") and hasTestTag("region-r1")).assertExists()
        rule.onNode(hasText("3 Orte") and hasTestTag("region-r1")).assertExists()
        rule.onNode(hasText("0 Orte") and hasTestTag("region-r5")).assertExists()
        rule.onNode(hasText("1 Ort") and hasTestTag("region-r0")).assertExists()
        rule.onNode(hasText("Tagesausflug", substring = true) and hasTestTag("region-r0")).assertExists()
        rule.onNode(hasText("Tagesausflug", substring = true) and hasTestTag("region-r1")).assertDoesNotExist()
        val order = listOf("region-r1", "region-r0", "region-r5", "region-none").map {
            val b = rule.onNodeWithTag(it).fetchSemanticsNode().boundsInRoot
            b.top * 10_000 + b.left
        }
        assertEquals(order.sorted(), order)
    }

    @Test fun categoryFilterNarrowsList() {
        seedData()
        rule.setContent { WanderlogPlusTheme { RegionPlacesScreen(TripSnapshot.from(store.all()), "r1", onBack = {}, onOpenPlace = {}) } }
        rule.onNodeWithTag("hero").assertExists()
        rule.onNodeWithTag("filter-category-ALL").assertExists()
        rule.onNodeWithTag("place-sv-a").assertExists()
        rule.onNodeWithTag("filter-category-FOOD").performClick()
        rule.onNodeWithTag("place-sv-f").assertExists()
        rule.onNodeWithTag("place-sv-a").assertDoesNotExist()
        rule.onNodeWithTag("place-sv-b").assertDoesNotExist()
        rule.onNodeWithTag("filter-category-SHOPPING").performClick()
        rule.onNodeWithText("Keine Orte", substring = true).assertExists()
        rule.onNodeWithTag("filter-category-ALL").performClick()
        rule.onNodeWithTag("place-sv-a").assertExists()
        rule.onNodeWithTag("place-sv-f").assertExists()
    }

    @Test fun placeCardShowsStatusDotAndRating() {
        seedData()
        rule.setContent { WanderlogPlusTheme { RegionPlacesScreen(TripSnapshot.from(store.all()), "r1", onBack = {}, onOpenPlace = {}) } }
        rule.onNodeWithTag("status-dot-sv-b", useUnmergedTree = true).assertExists()
        rule.onNode(hasText("★ 4,9", substring = true) and hasTestTag("place-sv-b")).assertExists()
    }

    @Test fun toggleAlwaysShownAndMapUnavailableInTests() {
        seedData()
        rule.setContent { WanderlogPlusTheme { RegionPlacesScreen(TripSnapshot.from(store.all()), "r1", onBack = {}, onOpenPlace = {}) } }
        rule.onNodeWithTag("view-map").assertExists()
        rule.onNodeWithTag("view-list").assertExists()
        // Semantics click: Robolectric's injected touch does not reach the segmented buttons reliably.
        rule.onNodeWithTag("view-map").performSemanticsAction(SemanticsActions.OnClick)
        // LocalMapsEnabled defaults to false, so MapLibre is never loaded under Robolectric.
        rule.onNodeWithText("Karte nicht verfügbar").assertIsDisplayed()
        rule.onNodeWithTag("place-sv-a").assertDoesNotExist()
        rule.onNodeWithTag("view-list").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("place-sv-a").assertExists()
    }
}
