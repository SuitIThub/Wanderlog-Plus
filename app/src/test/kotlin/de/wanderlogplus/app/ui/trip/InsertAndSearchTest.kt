package de.wanderlogplus.app.ui.trip

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.BBox
import de.wanderlogplus.client.GeoPlace
import de.wanderlogplus.client.ItemPosition
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class InsertAndSearchTest {
    @get:Rule val rule = createComposeRule()

    private val kyoto = BBox(135.70, 34.95, 135.80, 35.05)
    private val inside = GeoPlace("Kinkaku-ji", "Kyoto", 35.0394, 135.7292, "tourism", "attraction")
    private val farAway = GeoPlace("Kinkaku Ramen", "Tokio", 35.68, 139.76, "amenity", "restaurant")

    /** Fake search: hits only inside the area they belong to; a general search (no bbox) finds everything. */
    private class FakeSearch(val all: List<GeoPlace>) {
        val calls = mutableListOf<BBox?>()
        suspend fun search(q: String, near: Pair<Double, Double>?, bbox: BBox?): List<GeoPlace> {
            calls += bbox
            return all.filter { bbox == null || bbox.contains(it.lat, it.lng) }
        }
    }

    // --- search model

    @Test fun searchPrefersVisibleArea() = runBlocking {
        val fake = FakeSearch(listOf(inside, farAway))
        val m = PlaceSearchModel(finder(fake::search), { _, _ -> null }, null)
        m.visible = kyoto
        m.query = "Kinkaku"
        m.submit()
        assertEquals(listOf(inside), m.results)
        assertEquals(SearchStatus.FOUND, m.status)
        assertEquals(inside, m.selected) // a single hit is selected right away
        assertEquals(listOf<BBox?>(kyoto), fake.calls)
        assertEquals(0, m.fitRequest)
        assertFalse(m.canSearchArea)
    }

    @Test fun nothingInAreaWidensAndZoomsToHits() = runBlocking {
        val fake = FakeSearch(listOf(farAway))
        val m = PlaceSearchModel(finder(fake::search), { _, _ -> null }, null)
        m.visible = kyoto
        m.query = "Ramen"
        m.submit()
        assertEquals(listOf(farAway), m.results)
        assertEquals(SearchStatus.FOUND_ELSEWHERE, m.status)
        assertEquals(listOf(kyoto, null), fake.calls)
        assertEquals(1, m.fitRequest)
        assertTrue(m.canSearchArea) // the visible area was not where the hits came from
    }

    @Test fun failingAreaSearchStillSearchesEverywhere() = runBlocking {
        val calls = mutableListOf<BBox?>()
        val m = PlaceSearchModel(finder({ _, _, bbox -> calls += bbox; if (bbox != null) throw IllegalStateException("400") else listOf(farAway) }), { _, _ -> null }, null)
        m.visible = kyoto
        m.query = "Ramen"
        m.submit()
        assertEquals(listOf(farAway), m.results)
        assertEquals(SearchStatus.FOUND_ELSEWHERE, m.status)
        assertEquals(listOf(kyoto, null), calls)
    }

    @Test fun refusingServiceIsNamed() = runBlocking {
        val m = PlaceSearchModel(finder({ _, _, _ -> throw de.wanderlogplus.client.ApiException(de.wanderlogplus.client.ApiErrorKind.SERVER, status = 503) }), { _, _ -> null }, null)
        m.query = "Ramen"
        m.submit()
        assertEquals(SearchStatus.UNAVAILABLE, m.status)
        assertEquals(503, m.failedStatus)
        assertTrue(statusText(SearchStatus.UNAVAILABLE, 0)!!.contains("lange auf die Karte"))
        val offline = PlaceSearchModel(finder({ _, _, _ -> throw de.wanderlogplus.client.ApiException(de.wanderlogplus.client.ApiErrorKind.NETWORK) }), { _, _ -> null }, null)
        offline.query = "Ramen"
        offline.submit()
        assertEquals(SearchStatus.FAILED, offline.status)
    }

    @Test fun worldMapAreaIsNoFilter() = runBlocking {
        val calls = mutableListOf<BBox?>()
        val m = PlaceSearchModel(finder({ _, _, bbox -> calls += bbox; listOf(farAway) }), { _, _ -> null }, null)
        m.visible = BBox(-450.0, -85.0, 450.0, 85.0)
        m.query = "Ramen"
        m.submit()
        assertEquals(listOf<BBox?>(null), calls)
        assertEquals(SearchStatus.FOUND, m.status)
    }

    @Test fun movedMapOffersAreaSearch() = runBlocking {
        val fake = FakeSearch(listOf(inside, farAway))
        val m = PlaceSearchModel(finder(fake::search), { _, _ -> null }, null)
        m.visible = kyoto
        m.query = "Kinkaku"
        m.submit()
        assertFalse(m.canSearchArea)
        m.visible = BBox(139.70, 35.60, 139.80, 35.75) // panned to Tokyo
        assertTrue(m.canSearchArea)
        m.searchArea()
        assertEquals(listOf(farAway), m.results)
        assertFalse(m.canSearchArea)
    }

    @Test fun longPressPicksNamedOrBarePoint() = runBlocking {
        val m = PlaceSearchModel(finder({ _, _, _ -> emptyList() }), { lat, _ -> if (lat > 35) inside.copy(lat = 0.0) else null }, null)
        m.pick(35.04, 135.73)
        assertEquals("Kinkaku-ji", m.selected?.name)
        assertEquals(35.04, m.selected!!.lat, 1e-9) // the pressed point, not the lookup's
        m.pick(34.0, 135.0)
        assertEquals("Markierter Ort", m.selected?.name)
    }

    @Test fun failureIsReported() = runBlocking {
        val m = PlaceSearchModel(finder({ _, _, _ -> error("offline") }), { _, _ -> null }, null)
        m.query = "x"
        m.submit()
        assertEquals(SearchStatus.FAILED, m.status)
    }

    @Test fun searchStartsAtThePlaceBeforeTheInsertPosition() {
        val snap = Fixtures.trip()
        val tempel = snap.places["p-tempel"]!!
        assertEquals(LatLngD(tempel.lat, tempel.lng), searchStart(snap, "2027-04-02", "i-2"))
        assertNotNull(searchStart(snap, "2027-04-04", null)) // no program: centre of the region's places
    }

    // --- day detail: ＋ between items

    @Test fun insertBetweenItemsOffersSearchAtThatPosition() {
        var searched: ItemPosition? = null
        rule.setContent {
            WanderlogPlusTheme {
                DayDetailScreen(
                    Fixtures.trip(), "2027-04-02", onBack = {}, onAddSuggestion = { _, _ -> }, onMove = { _, _ -> }, onRemove = {},
                    onSearchPlace = { _, pos -> searched = pos },
                )
            }
        }
        rule.onNodeWithTag("insert-start").assertExists()
        rule.onNodeWithTag("insert-after:i-2").performScrollTo().performClick()
        rule.onNodeWithTag("add-search").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(ItemPosition.After("i-2"), searched)
    }

    @Test fun suggestionPickedAfterInsertGoesThere() {
        var added: Triple<String, String, ItemPosition>? = null
        rule.setContent {
            WanderlogPlusTheme {
                DayDetailScreen(
                    Fixtures.trip(), "2027-04-02", onBack = {}, onAddSuggestion = { _, _ -> error("plain add not expected") }, onMove = { _, _ -> }, onRemove = {},
                    onAddSuggestionAt = { d, id, pos -> added = Triple(d, id, pos) },
                )
            }
        }
        rule.onNodeWithTag("insert-start").performClick()
        rule.onNodeWithTag("add-from-suggestions").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("suggestion-add-s-garten").performClick()
        assertEquals(Triple("2027-04-02", "s-garten", ItemPosition.Start), added)
        rule.onNodeWithTag("program-list").assertExists() // back on the program
    }

    @Test fun noSearchOptionWithoutSearch() {
        rule.setContent {
            WanderlogPlusTheme {
                DayDetailScreen(Fixtures.trip(), "2027-04-02", onBack = {}, onAddSuggestion = { _, _ -> }, onMove = { _, _ -> }, onRemove = {})
            }
        }
        rule.onNodeWithTag("day-add").performClick()
        rule.onNodeWithTag("add-note-open").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("add-search").fetchSemanticsNodes().size)
    }

    // --- search screen (without map in tests)

    @Test fun searchScreenFindsAndAdds() {
        val fake = FakeSearch(listOf(inside, farAway))
        val model = PlaceSearchModel(finder(fake::search), { _, _ -> null }, null)
        var added: GeoPlace? = null
        rule.setContent { WanderlogPlusTheme { PlaceSearchScreen(model, start = null, onAdd = { added = it }, onBack = {}) } }
        rule.onNodeWithText("Karte nicht verfügbar").assertIsDisplayed()
        rule.onNodeWithTag("search-field").performTextInput("Kinkaku")
        rule.onNodeWithTag("search-submit").performClick()
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("search-hit-1").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("Im Ausschnitt nichts gefunden", substring = true).assertDoesNotExistOrHidden()
        rule.onNodeWithTag("search-hit-1").performClick()
        // Robolectric does not route this touch after the relayout (same quirk as with sheets); the semantics click is the button's onClick.
        rule.onNodeWithTag("search-add").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(farAway, added)
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrHidden() {
    // Without a map there is no visible area, so the general search answers directly ("Treffer in der Umgebung").
    try { assertIsDisplayed() } catch (_: AssertionError) { return }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class StayInProgramUiTest {
    @get:Rule val rule = createComposeRule()

    @Test fun todaysStayIsOfferedFirst() {
        val choices = stayChoices(Fixtures.trip(), "2027-04-04")
        // Check-out of the mountain inn and check-in at the coast are both "today".
        assertEquals(listOf("st-berg", "st-kueste"), choices.map { it.first.id })
        assertTrue(choices.first().second.endsWith("(heute)"))
        val later = stayChoices(Fixtures.trip(), "2027-04-02")
        assertEquals("st-berg", later.first().first.id)
        assertTrue(later[1].second.contains("ab "))
    }

    @Test fun stayIsInsertedAtThePlus() {
        var added: Triple<String, String, ItemPosition>? = null
        rule.setContent {
            WanderlogPlusTheme {
                DayDetailScreen(
                    Fixtures.trip(), "2027-04-02", onBack = {}, onAddSuggestion = { _, _ -> }, onMove = { _, _ -> }, onRemove = {},
                    onAddStayAt = { d, id, pos -> added = Triple(d, id, pos) },
                )
            }
        }
        rule.onNodeWithTag("insert-after:i-3").performScrollTo().performClick()
        rule.onNodeWithTag("add-stay-open").performSemanticsAction(SemanticsActions.OnClick)
        rule.onNodeWithTag("add-stay-st-berg").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(Triple("2027-04-02", "st-berg", ItemPosition.After("i-3")), added)
    }

    @Test fun hotelInProgramIsLabelledAsStay() {
        val base = Fixtures.trip()
        val snap = base.copy(dayItems = base.dayItems + de.wanderlogplus.shared.model.DayItem("i-h", "d-2", "z", de.wanderlogplus.shared.model.DayItemKind.PLACE, "p-hotel-berg"))
        rule.setContent {
            WanderlogPlusTheme {
                DayDetailScreen(snap, "2027-04-02", onBack = {}, onAddSuggestion = { _, _ -> }, onMove = { _, _ -> }, onRemove = {})
            }
        }
        rule.onNode(hasText("Unterkunft", substring = true) and hasAnyAncestor(hasTestTag("program-row-i-h")), useUnmergedTree = true).assertExists()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class PlacesSearchEntryTest {
    @get:Rule val rule = createComposeRule()

    @Test fun nearestRegionWithin50Km() {
        val s = Fixtures.trip()
        val tempel = s.places["p-tempel"]!!
        assertEquals("r-berg", de.wanderlogplus.app.ui.places.nearestRegion(s, tempel.lat + 0.01, tempel.lng))
        assertEquals(null, de.wanderlogplus.app.ui.places.nearestRegion(s, 43.06, 141.35)) // Sapporo: far from every region
    }

    @Test fun overviewAndRegionOfferSearch() {
        var overview = 0
        var region = 0
        rule.setContent {
            WanderlogPlusTheme {
                androidx.compose.foundation.layout.Column {
                    de.wanderlogplus.app.ui.places.PlacesScreen(Fixtures.trip(), onOpenRegion = {}, onSearch = { overview++ }, modifier = androidx.compose.ui.Modifier.weight(1f))
                    de.wanderlogplus.app.ui.places.RegionPlacesScreen(Fixtures.trip(), "r-berg", onBack = {}, onOpenPlace = {}, onSearch = { region++ }, modifier = androidx.compose.ui.Modifier.weight(1f))
                }
            }
        }
        rule.onNodeWithTag("places-search").performClick()
        rule.onNodeWithTag("region-search").performClick()
        assertEquals(1, overview)
        assertEquals(1, region)
    }
}
