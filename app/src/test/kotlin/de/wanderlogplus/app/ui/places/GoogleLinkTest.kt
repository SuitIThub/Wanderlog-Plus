package de.wanderlogplus.app.ui.places

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.GeoPlace
import de.wanderlogplus.client.InMemoryOutbox
import de.wanderlogplus.client.LocalEditor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
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
class GoogleLinkTest {
    @get:Rule val rule = createComposeRule()

    private val hit = GeoPlace("Ichiran Kyoto", "Kyoto, Shijo", 35.0108, 135.0, googlePlaceId = "g9")

    private fun editorWith(place: Place): Pair<InMemoryEntityStore, LocalEditor> {
        val store = InMemoryEntityStore()
        val e = LocalEditor(store, InMemoryOutbox(), HlcClock("dev", now = { 1000L }))
        e.create(EntityType.PLACE, EntityCodec.fields(place), id = place.id)
        return store to e
    }

    @Test
    fun unlinkedPlaceOffersSearch() {
        rule.setContent {
            WanderlogPlusTheme {
                PlaceDetailScreen(Fixtures.trip(), "s-nudel", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }, googleSearch = { _, _ -> emptyList() }, onLinkGoogle = { _, _, _ -> })
            }
        }
        rule.onNodeWithTag("google-link").assertExists()
        rule.onNodeWithText("Kein Google-Eintrag").assertExists()
        rule.onNodeWithTag("google-search", useUnmergedTree = true).assertExists()
    }

    @Test
    fun noSearchWithoutServer() {
        rule.setContent {
            WanderlogPlusTheme { PlaceDetailScreen(Fixtures.trip(), "s-nudel", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }) }
        }
        rule.onNodeWithTag("google-link").assertExists()
        rule.onNodeWithTag("google-search", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test
    fun linkedPlaceShowsACompactLine() {
        val s = Fixtures.trip().let { t -> t.copy(places = t.places + ("p-nudel" to t.places.getValue("p-nudel").copy(googlePlaceId = "g1", address = "Shijo 5, Kyoto"))) }
        rule.setContent {
            WanderlogPlusTheme { PlaceDetailScreen(s, "s-nudel", onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> }, googleSearch = { _, _ -> emptyList() }, onLinkGoogle = { _, _, _ -> }) }
        }
        // On the place page the Google row is one line; the address stands under "Details", not twice.
        rule.onNode(androidx.compose.ui.test.hasText("verknüpft", substring = true) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("google-link"))).assertExists()
        rule.onNode(androidx.compose.ui.test.hasText("Shijo 5, Kyoto", substring = true) and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("google-link"))).assertDoesNotExist()
        rule.onNodeWithText("Ändern").assertExists()
    }

    @Test
    fun pickLinksAndKeepsPosition() {
        val (store, e) = editorWith(Place("p1", name = "Ichiran", lat = 35.0, lng = 135.0))
        googleLinkEdits("p1", hit, takePosition = false)(e, TripSnapshot.from(store.all()))
        val p = TripSnapshot.from(store.all()).places.getValue("p1")
        assertEquals("g9", p.googlePlaceId)
        assertEquals(35.0, p.lat, 0.0)
        assertEquals("Kyoto, Shijo", p.address)
        googleLinkEdits("p1", hit, takePosition = true)(e, TripSnapshot.from(store.all()))
        assertEquals(35.0108, TripSnapshot.from(store.all()).places.getValue("p1").lat, 0.0)
    }

    @Test
    fun ownAddressStays() {
        val (store, e) = editorWith(Place("p1", name = "Ichiran", lat = 35.0, lng = 135.0, address = "Eigene Adresse"))
        googleLinkEdits("p1", hit, takePosition = false)(e, TripSnapshot.from(store.all()))
        assertEquals("Eigene Adresse", TripSnapshot.from(store.all()).places.getValue("p1").address)
    }

    @Test
    fun unlinkClears() {
        val (store, e) = editorWith(Place("p1", name = "Ichiran", lat = 35.0, lng = 135.0, googlePlaceId = "g1"))
        googleLinkEdits("p1", null, takePosition = false)(e, TripSnapshot.from(store.all()))
        assertEquals("", TripSnapshot.from(store.all()).places.getValue("p1").googlePlaceId)
    }

    @Test
    fun distances() {
        assertEquals("364 m", distanceText(35.0, 135.0, 35.0, 135.004))
        assertEquals("1,2 km", distanceText(35.0, 135.0, 35.0108, 135.0))
    }

    @Test
    fun sheetShowsHitsWithDistanceAndPicks() {
        var picked: Pair<GeoPlace, Boolean>? = null
        rule.setContent {
            WanderlogPlusTheme {
                GoogleLinkSheet("Ichiran Kyoto", 35.0 to 135.0, search = { _, _ -> listOf(hit) }, linked = false, onPick = { h, take -> picked = h to take }, onUnlink = {}, onDismiss = {})
            }
        }
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("google-hit-g9").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("1,2 km", substring = true).assertExists()
        rule.onNodeWithTag("google-take-position").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("google-hit-g9").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(hit to true, picked)
    }

    @Test
    fun regionListMarksMissingGoogle() {
        val s = Fixtures.trip().let { t -> t.copy(places = t.places + ("p-tempel" to t.places.getValue("p-tempel").copy(googlePlaceId = "g1"))) }
        rule.setContent { WanderlogPlusTheme { RegionPlacesScreen(s, "r-berg", onBack = {}, onOpenPlace = {}) } }
        rule.onNodeWithTag("no-google-s-nudel", useUnmergedTree = true).assertExists()
        rule.onNodeWithTag("no-google-s-tempel", useUnmergedTree = true).assertDoesNotExist()
    }
}
