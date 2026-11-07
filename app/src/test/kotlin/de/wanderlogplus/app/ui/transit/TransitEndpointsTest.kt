package de.wanderlogplus.app.ui.transit

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.TransitPoint
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class TransitEndpointsTest {
    @get:Rule val rule = createComposeRule()

    private val hotel = EndpointHit("Hotel Gion", "Unterkunft", 35.0, 135.77, true)

    private val api = SyncApi(
        HttpClient(MockEngine { respond("""{"stations":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) }),
        "https://s", "tok",
    )

    private fun vm() = TransitViewModel({ api }, null, TransitPoint(35.49, 137.5, "Ziel"), "2027-05-20", "08:00", null)

    @Test
    fun tripHitsMatchPlacesAndStays() {
        val s = Fixtures.trip()
        val nudel = tripEndpointHits(s, "nudelkü").single()
        assertEquals(s.places.getValue("p-nudel").name, nudel.name)
        assertEquals("Bergstadt", nudel.detail)
        assertTrue(nudel.fromTrip)
        val hotel = tripEndpointHits(s, "gasthaus").single()
        assertEquals("Unterkunft", hotel.detail)
        assertTrue(tripEndpointHits(s, "  ").isEmpty())
        // p-tempel is in the collection and on a day: once.
        val tempel = s.places.getValue("p-tempel").name
        assertEquals(1, tripEndpointHits(s, tempel.take(5)).count { it.name == tempel })
    }

    @Test
    fun pickPlaceSetsPointAndClearsStation() {
        val vm = vm()
        vm.edit { it.copy(fromStation = "京都") }
        vm.pickFrom(hotel)
        assertEquals(TransitPoint(35.0, 135.77, "Hotel Gion"), vm.state.value.form.fromPoint)
        assertEquals("", vm.state.value.form.fromStation)
    }

    @Test
    fun requestKeepsTypedStationAndPoint() {
        val f = TransitForm(fromStation = "祇園四条", date = "2027-05-20", time = "08:00", fromPoint = TransitPoint(35.0, 135.77, "Hotel Gion"))
        val r = requestFor(f)
        assertEquals(TransitPoint(35.0, 135.77, "Hotel Gion"), r.from)
        assertEquals("祇園四条", r.fromStation)
        assertNull(r.toStation)
    }

    @Test
    fun clearRemovesPoint() {
        val vm = vm()
        vm.pickFrom(hotel)
        vm.clearFrom()
        assertNull(vm.state.value.form.fromPoint)
        vm.search()
        assertEquals("Start eintragen (Ort, Hotel oder Bahnhof).", vm.state.value.message)
    }

    @Test
    fun screenShowsSuggestionsAndPicks() {
        var state by mutableStateOf(TransitScreenState(form = TransitForm(date = "2027-05-20")))
        var picked: EndpointHit? = null
        rule.setContent {
            WanderlogPlusTheme {
                TransitScreen(
                    "Weg", state, Color.Blue, chosen = null,
                    onEdit = { f -> state = state.copy(form = f(state.form)) }, onSearch = {}, onChoose = {}, onClear = {}, onBack = {},
                    suggest = { listOf(hotel) }, onPickFrom = { picked = it; state = state.copy(form = state.form.copy(fromPoint = TransitPoint(it.lat, it.lng, it.name))) },
                )
            }
        }
        rule.onNodeWithTag("transit-from").performTextInput("Ho")
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("transit-from-hit-0").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("transit-from-hit-0").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(hotel, picked)
        rule.onNodeWithTag("transit-from-place", useUnmergedTree = true).assertExists()
    }
}
