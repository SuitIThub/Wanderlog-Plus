package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.maps.LegRepository
import de.wanderlogplus.app.maps.LegResult
import de.wanderlogplus.app.maps.LocalLegRepository
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.testing.recordScreenshot
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.TravelMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h900dp-xxhdpi")
class DayMapTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    /** Day 2 of the fixture: note, temple (i-2), noodle shop (i-3, reached by transit). */
    private fun snap() = Fixtures.trip().let { s -> s.copy(dayItems = s.dayItems.map { if (it.id == "i-3") it.copy(legMode = TravelMode.TRANSIT) else it }) }

    @Test fun stopsAreThePlacesInOrderAndSegmentsUseTheirMode() {
        val s = snap()
        val stops = routeStops(s, "2027-04-02")
        assertEquals(listOf("i-2", "i-3"), stops.map { it.itemId })
        assertEquals(listOf(1, 2), stops.map { it.number })
        val segs = routeSegments(s, stops)
        assertEquals(TravelMode.TRANSIT, segs.single().mode)
        assertTrue(segs.single().straight) // not loaded yet: straight line
        assertEquals(listOf(stops[0].at, stops[1].at), segs.single().path)
    }

    @Test fun totalsCountMissingRoutes() {
        val a = RouteStop("a", 1, "A", LatLngD(0.0, 0.0))
        val b = RouteStop("b", 2, "B", LatLngD(0.0, 1.0))
        val c = RouteStop("c", 3, "C", LatLngD(0.0, 2.0))
        val segs = listOf(
            RouteSegment(a, b, TravelMode.WALK, LegResult.Route(1500, 1800, listOf(a.at, LatLngD(0.0, 0.5), b.at))),
            RouteSegment(b, c, TravelMode.TRANSIT, LegResult.NoRoute),
        )
        val t = routeTotals(segs)
        assertEquals(RouteTotals(1500, 1800, 1), t)
        assertEquals("Gesamt 25 Min · 1,8 km · 1 Abschnitt ohne Route", totalsText(t, 2))
        assertFalse(segs[0].straight)
        assertEquals(3, segs[0].path.size)
    }

    @Test fun screenListsStopsAndLoadedWays() {
        val repo = LegRepository(
            api = {
                SyncApi(
                    HttpClient(MockEngine { req ->
                        val json = headersOf(HttpHeaders.ContentType, "application/json")
                        // No timetable search on this server: the transit way falls back to the Google route.
                        if (req.url.encodedPath.endsWith("/api/transit")) respond("""{"error":"transit_not_configured"}""", HttpStatusCode.ServiceUnavailable, json)
                        else respond("""{"durationSeconds":1500,"distanceMeters":1800,"polyline":"_p~iF~ps|U_ulLnnqC"}""", HttpStatusCode.OK, json)
                    }),
                    "https://s", "tok",
                )
            },
            dir = tmp.newFolder(),
        )
        rule.setContent {
            WanderlogPlusTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    CompositionLocalProvider(LocalLegRepository provides repo) {
                        DayMapScreen(snap(), "2027-04-02", onBack = {})
                    }
                }
            }
        }
        rule.waitUntil(3_000) { rule.onAllNodesWithText("Gesamt 25 Min · 1,8 km").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("day-map-stop-1").assertExists()
        rule.onNodeWithTag("day-map-leg-2").assertExists()
        rule.onNodeWithText("Alter Bergtempel").performClick()
        rule.onRoot().recordScreenshot("day-map-light")
    }

    @Test fun dayWithoutPlacesSaysSo() {
        rule.setContent { WanderlogPlusTheme { DayMapScreen(Fixtures.trip(), "2027-04-06", onBack = {}) } }
        rule.onNodeWithText("Im Programm ist noch kein Ort mit Kartenposition.").assertExists()
    }

    @Test fun mapButtonOpensMap() {
        var opened: String? = null
        rule.setContent {
            WanderlogPlusTheme {
                DayDetailScreen(Fixtures.trip(), "2027-04-02", onBack = {}, onAddSuggestion = { _, _ -> }, onMove = { _, _ -> }, onRemove = {}, onOpenMap = { opened = it })
            }
        }
        rule.onNodeWithTag("day-map").performClick()
        assertEquals("2027-04-02", opened)
    }
}
