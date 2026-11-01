package de.wanderlogplus.app.ui.logistics

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.maps.LegRepository
import de.wanderlogplus.app.maps.LegResult
import de.wanderlogplus.app.maps.LocalLegRepository
import de.wanderlogplus.app.maps.RouteGeometry
import de.wanderlogplus.app.maps.routeFor
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.design.distanceLabel
import de.wanderlogplus.app.ui.design.durationLabel
import de.wanderlogplus.app.ui.trip.DayDetailScreen
import de.wanderlogplus.client.GeoPlace
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.TravelMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w400dp-h2400dp")
class RoutesAndLegsTest {
    @get:Rule val rule = createComposeRule()
    @get:Rule val tmp = TemporaryFolder()

    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")

    private fun repo(status: HttpStatusCode = HttpStatusCode.OK, body: String = """{"durationSeconds":1500,"distanceMeters":1800,"polyline":"_p~iF~ps|U_ulLnnqC"}""", calls: IntArray = IntArray(1)) =
        LegRepository(
            api = { SyncApi(HttpClient(MockEngine { calls[0]++; respond(body, status, jsonHeaders) }), "https://s", "tok") },
            dir = tmp.newFolder(),
        )

    // --- route geometry with stopovers

    @Test fun trainRouteRunsThroughStopoversWithCoordinates() {
        val snap = Fixtures.trip()
        val t = snap.trains.first().copy(via = listOf(Endpoint("Mitte", lat = 34.85, lng = 135.6), Endpoint("Ohne Position")))
        val r = routeFor(t, snap)!!
        assertEquals(3, r.stops.size)
        assertEquals(LatLngD(34.85, 135.6), r.stops[1])
        assertEquals(r.stops, r.points())
    }

    @Test fun endpointCoordinatesWinOverPlace() {
        val snap = Fixtures.trip()
        val t = snap.trains.first().copy(depart = snap.trains.first().depart.copy(lat = 1.0, lng = 2.0))
        assertEquals(LatLngD(1.0, 2.0), routeFor(t, snap)!!.from)
    }

    @Test fun flightArcsPerSegmentAndPathWins() {
        val r = RouteGeometry(listOf(LatLngD(50.0, 8.5), LatLngD(25.0, 121.2), LatLngD(35.7, 140.4)), arc = true)
        assertEquals(63, r.points().size) // 2 × 32 arc points, shared middle point once
        val path = listOf(LatLngD(1.0, 1.0), LatLngD(1.5, 1.2), LatLngD(2.0, 2.0))
        assertEquals(path, RouteGeometry(listOf(LatLngD(1.0, 1.0), LatLngD(2.0, 2.0)), false, path).points())
        assertTrue(r.cacheKey(10, 10) != RouteGeometry(listOf(r.stops.first(), r.stops.last()), true).cacheKey(10, 10))
    }

    // --- leg repository

    @Test fun legIsComputedOnceThenCached() = runBlocking {
        val calls = IntArray(1)
        val repo = repo(calls = calls)
        val a = LatLngD(35.0, 135.7); val b = LatLngD(35.01, 135.71)
        val r = repo.leg(a, b, TravelMode.WALK) as LegResult.Route
        assertEquals(1500, r.durationSeconds)
        assertEquals(2, r.path.size)
        repo.leg(a, b, TravelMode.WALK)
        assertEquals(1, calls[0])
        assertNotNull(repo.peek(a, b, TravelMode.WALK))
        assertNull(repo.peek(a, b, TravelMode.TRANSIT))
    }

    @Test fun noRouteIsCachedButErrorsAreNot() = runBlocking {
        val calls = IntArray(1)
        val none = repo(HttpStatusCode.NotFound, """{"error":"no_route"}""", calls)
        val a = LatLngD(35.0, 135.7); val b = LatLngD(26.2, 127.6)
        assertEquals(LegResult.NoRoute, none.leg(a, b, TravelMode.WALK))
        none.leg(a, b, TravelMode.WALK)
        assertEquals(1, calls[0])
        val failing = IntArray(1)
        val noKey = repo(HttpStatusCode.ServiceUnavailable, """{"error":"routes_not_configured"}""", failing)
        assertEquals(LegResult.NotConfigured, noKey.leg(a, b, TravelMode.WALK))
        noKey.leg(a, b, TravelMode.WALK)
        assertEquals(2, failing[0])
    }

    @Test fun polylineEncodeRoundTrips() {
        val path = listOf(LatLngD(38.5, -120.2), LatLngD(40.7, -120.95), LatLngD(43.252, -126.453))
        assertEquals("_p~iF~ps|U_ulLnnqC_mqNvxq`@", LegRepository.encode(path))
    }

    @Test fun labels() {
        assertEquals("25 Min", durationLabel(1500))
        assertEquals("1 Std 5 Min", durationLabel(3900))
        assertEquals("800 m", distanceLabel(804))
        assertEquals("1,8 km", distanceLabel(1800))
        assertEquals("12 km", distanceLabel(11_600))
    }

    // --- day detail: legs between places

    @Test fun legsToggleAndModeChange() {
        val base = Fixtures.trip()
        var toggled: Boolean? = null
        var mode: Pair<String, TravelMode>? = null
        val repo = repo()
        val snap = base.copy(days = base.days.map { if (it.id == "d-2") it.copy(showLegs = true) else it })
        rule.setContent {
            WanderlogPlusTheme {
                CompositionLocalProvider(LocalLegRepository provides repo) {
                    DayDetailScreen(
                        snap, "2027-04-02", onBack = {}, onAddSuggestion = { _, _ -> }, onMove = { _, _ -> }, onRemove = {},
                        onShowLegs = { _, s -> toggled = s },
                        onLegMode = { id, m -> mode = id to m },
                    )
                }
            }
        }
        rule.onNodeWithTag("legs-toggle").assertIsDisplayed()
        // Note i-1 comes first: the only leg leads from the temple (i-2) to the noodle shop (i-3).
        rule.onNodeWithTag("leg-i-3").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("leg-i-2").fetchSemanticsNodes().size)
        rule.waitUntil(3_000) { rule.onAllNodesWithText("25 Min · 1,8 km").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithText("25 Min · 1,8 km").performClick()
        rule.onNodeWithTag("leg-map").assertExists()
        rule.onNodeWithTag("leg-mode-TRANSIT").performClick()
        assertEquals("i-3" to TravelMode.TRANSIT, mode)
        rule.onNodeWithTag("legs-toggle").performClick()
        assertEquals(false, toggled)
    }

    @Test fun noLegsWhenSwitchedOff() {
        rule.setContent {
            WanderlogPlusTheme {
                DayDetailScreen(Fixtures.trip(), "2027-04-02", onBack = {}, onAddSuggestion = { _, _ -> }, onMove = { _, _ -> }, onRemove = {}, onShowLegs = { _, _ -> })
            }
        }
        rule.onNodeWithTag("legs-toggle").assertExists()
        assertEquals(0, rule.onAllNodesWithTag("leg-i-3").fetchSemanticsNodes().size)
    }

    // --- logistics: merge, delete, edit

    @Test fun mergeAsksThenJoinsWithNextFlight() {
        var merged: Triple<EntityType, String, String>? = null
        rule.setContent {
            WanderlogPlusTheme {
                LogisticsDetailScreen(Fixtures.trip(), EntityType.FLIGHT, "fl-1", onBack = {}, onEdit = { _, _ -> }, onMerge = { t, a, b -> merged = Triple(t, a, b) }, onDelete = { _, _ -> })
            }
        }
        rule.onNodeWithTag("move-merge").performClick()
        rule.onNodeWithText("Zusammenführen?").assertExists()
        rule.onNodeWithTag("merge-confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(Triple(EntityType.FLIGHT, "fl-1", "fl-2"), merged)
    }

    @Test fun lastLegHasNoMergeButton() {
        rule.setContent {
            WanderlogPlusTheme { LogisticsDetailScreen(Fixtures.trip(), EntityType.FLIGHT, "fl-2", onBack = {}, onMerge = { _, _, _ -> }) }
        }
        assertEquals(0, rule.onAllNodesWithTag("move-merge").fetchSemanticsNodes().size)
    }

    @Test fun deleteAsksFirst() {
        var deleted: String? = null
        rule.setContent {
            WanderlogPlusTheme { LogisticsDetailScreen(Fixtures.trip(), EntityType.TRAIN, "tr-1", onBack = {}, onDelete = { _, id -> deleted = id }) }
        }
        rule.onNodeWithTag("move-delete").performClick()
        assertNull(deleted)
        rule.onNodeWithTag("delete-confirm").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals("tr-1", deleted)
    }

    @Test fun editAddsStopoverFromSearchAndSaves() {
        val snap = Fixtures.trip()
        var saved: Map<String, JsonElement>? = null
        val hit = GeoPlace("Nagoya Station", "Nagoya, Japan", 35.1709, 136.8814)
        rule.setContent {
            WanderlogPlusTheme {
                LogisticsEditScreen(
                    snap, EntityType.TRAIN, "tr-1", onBack = {},
                    onSave = { _, _, _, after -> saved = after },
                    search = { _, _ -> listOf(hit) },
                )
            }
        }
        rule.onNodeWithTag("edit-add-via").performClick()
        rule.onNodeWithTag("place-search-field").performTextInput("Nagoya")
        rule.waitUntil(3_000) { rule.onAllNodesWithTag("place-hit-0").fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("place-hit-0").performClick()
        rule.onNodeWithTag("edit-via-0").assertExists()
        rule.onNodeWithTag("edit-save").performClick()
        val via = saved!!["via"] as JsonArray
        assertEquals(1, via.size)
        val stop = kotlinx.serialization.json.Json.decodeFromJsonElement(Endpoint.serializer(), via[0])
        assertEquals("Nagoya Station", stop.name)
        assertEquals(35.1709, stop.lat!!, 1e-9)
    }

    @Test fun invalidTimeIsRejected() {
        val d = draftOf(Fixtures.trip(), EntityType.TRAIN, "tr-1")!!
        assertNull(validateDraft(d))
        assertEquals("Uhrzeit bitte als HH:MM", validateDraft(d.copy(depart = d.depart.copy(time = "9 Uhr"))))
        assertEquals("Jeder Zwischenhalt braucht einen Namen", validateDraft(d.copy(via = listOf(Endpoint()))))
    }
}
