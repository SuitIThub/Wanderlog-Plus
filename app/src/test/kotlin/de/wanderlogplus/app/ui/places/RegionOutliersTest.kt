package de.wanderlogplus.app.ui.places

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FieldValue
import de.wanderlogplus.shared.sync.Hlc
import de.wanderlogplus.shared.sync.encode
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class RegionOutliersTest {
    @get:Rule val rule = createComposeRule()

    private fun rec(type: EntityType, id: String, fields: Map<String, JsonElement>) =
        EntityRecord(type, id, fields.mapValues { (_, v) -> FieldValue(v, Hlc(1, 0, "a").encode()) })

    // Osaka with five places in town, Okunoin (Kōyasan, ~60 km) and Tōdai-ji (Nara, ~30 km); Nara is its own region.
    private fun snapshot(): TripSnapshot {
        val recs = mutableListOf(
            rec(EntityType.REGION, "osaka", EntityCodec.fields(Region("osaka", "Osaka"))),
            rec(EntityType.REGION, "nara", EntityCodec.fields(Region("nara", "Nara"))),
        )
        fun place(id: String, region: String, lat: Double, lng: Double) {
            recs += rec(EntityType.PLACE, "p-$id", EntityCodec.fields(Place("p-$id", name = id, lat = lat, lng = lng)))
            recs += rec(EntityType.SAVED_PLACE, id, EntityCodec.fields(SavedPlace(id, "p-$id", region)))
        }
        place("dotonbori", "osaka", 34.6687, 135.5013)
        place("kuromon", "osaka", 34.6654, 135.5066)
        place("castle", "osaka", 34.6873, 135.5262)
        place("umeda", "osaka", 34.7025, 135.4959)
        place("tsutenkaku", "osaka", 34.6525, 135.5063)
        place("okunoin", "osaka", 34.2131, 135.6030)
        place("todaiji", "osaka", 34.6890, 135.8398)
        place("kasuga", "nara", 34.6812, 135.8485)
        place("naramachi", "nara", 34.6765, 135.8290)
        return TripSnapshot.from(recs)
    }

    @Test fun farPlacesAreFoundWithASuggestion() {
        val out = regionOutliers(snapshot(), "osaka").associateBy { it.saved.id }
        assertEquals(setOf("okunoin"), out.keys) // Tōdai-ji is ~30 km off: within 50 km, not flagged
        assertEquals(null, out.getValue("okunoin").suggestedRegionId) // no region near Kōyasan
        assertTrue(out.getValue("okunoin").km > 50)
    }

    @Test fun aPlaceCloserToAnotherRegionGetsItSuggested() {
        val base = snapshot()
        // Tōdai-ji moved east of Nara: about 54 km from Osaka's middle, about 24 km from Nara's.
        val far = base.copy(places = base.places + ("p-todaiji" to base.places.getValue("p-todaiji").copy(lat = 34.68, lng = 136.10)))
        val out = regionOutliers(far, "osaka").associateBy { it.saved.id }
        assertEquals("nara", out.getValue("todaiji").suggestedRegionId)
    }

    @Test fun bannerListsAndMovesOnTap() {
        val moved = mutableListOf<Pair<String, String?>>()
        rule.setContent {
            WanderlogPlusTheme {
                RegionPlacesScreen(snapshot(), "osaka", onBack = {}, onOpenPlace = {}, onSetRegion = { id, r -> moved += id to r })
            }
        }
        rule.onNodeWithText("1 Ort liegt weit außerhalb von Osaka", substring = true).assertExists()
        rule.onNodeWithTag("region-outliers").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("outlier-move-okunoin").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("okunoin" to null), moved)
    }
}
