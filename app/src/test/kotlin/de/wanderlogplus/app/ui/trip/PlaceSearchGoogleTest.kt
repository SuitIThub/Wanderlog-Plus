package de.wanderlogplus.app.ui.trip

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.logistics.StayDraft
import de.wanderlogplus.app.ui.logistics.stayToSave
import de.wanderlogplus.client.GeoPlace
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.PlaceFinderResult
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.HlcClock
import de.wanderlogplus.shared.sync.InMemoryEntityStore
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class PlaceSearchGoogleTest {
    @get:Rule val rule = createComposeRule()

    private val hit = GeoPlace("Ichiran", "Kyoto", 35.0, 135.7)

    private fun searchWith(viaGoogle: Boolean) {
        val model = PlaceSearchModel({ _, _, _ -> PlaceFinderResult(listOf(hit), viaGoogle) }, { _, _ -> null }, null)
        rule.setContent { WanderlogPlusTheme { PlaceSearchScreen(model, start = null, onAdd = {}, onBack = {}) } }
        rule.onNodeWithTag("search-field").performTextInput("Ichiran")
        rule.onNodeWithTag("search-submit").performClick()
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("search-hit-0").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun hintWithoutGoogle() {
        searchWith(viaGoogle = false)
        rule.onNodeWithTag("search-no-google").assertExists()
    }

    @Test
    fun noHintWithGoogle() {
        searchWith(viaGoogle = true)
        rule.onNodeWithTag("search-no-google").assertDoesNotExist()
    }

    @Test
    fun addToDayKeepsGoogleId() {
        val store = InMemoryEntityStore()
        val editor = LocalEditor(store, InMemoryOutbox(), HlcClock("dev", now = { 1000L }))
        Fixtures.trip().trip!!.let { editor.create(EntityType.TRIP, EntityCodec.fields(it), id = it.id) }
        val snap = TripSnapshot.from(store.all())
        editor.addPlaceToDay(snap, "2027-04-02", "Ichiran", 35.0, 135.7, null, PlaceCategory.FOOD, googlePlaceId = "g1")
        val place = TripSnapshot.from(store.all()).places.values.single { it.name == "Ichiran" }
        assertEquals("g1", place.googlePlaceId)
    }

    @Test
    fun newStayPlaceKeepsGoogleId() {
        val save = stayToSave(Fixtures.trip(), StayDraft(name = "Hotel", lat = 1.0, lng = 2.0, picked = true, googlePlaceId = "g2", checkIn = "2027-04-01", checkOut = "2027-04-02"), null, "p-new")
        assertEquals("g2", save.newPlace!!.googlePlaceId)
    }
}
