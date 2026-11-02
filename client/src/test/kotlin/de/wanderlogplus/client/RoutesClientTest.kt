package de.wanderlogplus.client

import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.model.Trip
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.RouteInfo
import de.wanderlogplus.shared.sync.RouteRequest
import de.wanderlogplus.shared.sync.TravelMode
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PolylineTest {
    @Test fun decodesGoogleExample() {
        // Example from Google's polyline algorithm documentation.
        val pts = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@")
        assertEquals(listOf(38.5 to -120.2, 40.7 to -120.95, 43.252 to -126.453), pts)
    }

    @Test fun emptyAndMalformedAreSafe() {
        assertTrue(Polyline.decode("").isEmpty())
        assertEquals(1, Polyline.decode("_p~iF~ps|U_").size)
    }
}

class RoutesApiTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val req = RouteRequest(35.58, 137.57, 35.53, 137.57, TravelMode.WALK)

    @Test fun computeRoutePostsWithAuth() = runTest {
        var seen: HttpRequestData? = null
        var body = ""
        val api = SyncApi(HttpClient(MockEngine { r ->
            seen = r
            body = String(r.body.toByteArray())
            respond("""{"durationSeconds":600,"distanceMeters":800,"polyline":"abc"}""", HttpStatusCode.OK, jsonHeaders)
        }), "https://s.example/wlp", "tok")
        assertEquals(RouteInfo(600, 800, "abc"), api.computeRoute(req))
        assertEquals("/wlp/api/routes", seen!!.url.encodedPath)
        assertEquals("Bearer tok", seen!!.headers[HttpHeaders.Authorization])
        assertTrue(body.contains("\"mode\":\"WALK\""), body)
    }

    @Test fun noRouteIsNull() = runTest {
        val api = SyncApi(HttpClient(MockEngine { respond("""{"error":"no_route"}""", HttpStatusCode.NotFound, jsonHeaders) }), "https://s", "tok")
        assertNull(api.computeRoute(req))
    }

    @Test fun researchSendsIdsAndConfig() = runTest {
        var body = ""
        var path = ""
        val api = SyncApi(HttpClient(MockEngine { r ->
            path = r.url.encodedPath
            body = String(r.body.toByteArray())
            respond("""{"id":"j1","kind":"research","createdAt":1,"status":"QUEUED","total":1}""", HttpStatusCode.OK, jsonHeaders)
        }), "https://s", "tok")
        val job = api.startResearch(listOf("s1"), de.wanderlogplus.shared.sync.AssessConfig(listOf("crowds"), "Ruhig bitte"))
        assertEquals("research", job.kind)
        assertEquals("/api/ai/research", path)
        assertTrue(body.contains("\"crowds\"") && body.contains("Ruhig bitte"), body)
    }

    @Test fun notConfiguredIsReported() = runTest {
        val api = SyncApi(HttpClient(MockEngine { respond("""{"error":"routes_not_configured"}""", HttpStatusCode.ServiceUnavailable, jsonHeaders) }), "https://s", "tok")
        val e = assertFailsWith<ApiException> { api.computeRoute(req) }
        assertEquals(ApiErrorKind.NOT_CONFIGURED, e.kind)
    }
}

class GeoSearchTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val photon = """{"features":[
        {"geometry":{"type":"Point","coordinates":[136.8814,35.1709]},"properties":{"name":"Nagoya Station","city":"Nagoya","state":"Aichi","country":"Japan"}},
        {"geometry":{"type":"Point","coordinates":[1.0]},"properties":{"name":"broken"}},
        {"geometry":{"type":"Point","coordinates":[8.6633,50.1071]},"properties":{"name":"Frankfurt (Main) Hauptbahnhof","city":"Frankfurt am Main","country":"Deutschland"}}
    ]}"""

    @Test fun parsesHitsAndSkipsBroken() = runTest {
        var seen: HttpRequestData? = null
        val search = GeoSearch(HttpClient(MockEngine { r -> seen = r; respond(photon, HttpStatusCode.OK, jsonHeaders) }))
        val hits = search.search("Nagoya", near = 35.0 to 137.0)
        assertEquals(2, hits.size)
        assertEquals(GeoPlace("Nagoya Station", "Nagoya, Aichi, Japan", 35.1709, 136.8814), hits[0])
        assertEquals("Nagoya", seen!!.url.parameters["q"])
        assertEquals("35.0", seen!!.url.parameters["lat"])
        assertNull(seen!!.headers[HttpHeaders.Authorization])
    }

    @Test fun blankQueryDoesNotCall() = runTest {
        var calls = 0
        val search = GeoSearch(HttpClient(MockEngine { calls++; respond("{}", HttpStatusCode.OK, jsonHeaders) }))
        assertTrue(search.search("  ").isEmpty())
        assertEquals(0, calls)
    }

    @Test fun serverErrorThrows() = runTest {
        var calls = 0
        val search = GeoSearch(HttpClient(MockEngine { calls++; respond("", HttpStatusCode.BadGateway) })).apply { retryDelaysMillis = listOf(1, 1) }
        assertEquals(ApiErrorKind.SERVER, assertFailsWith<ApiException> { search.search("x") }.kind)
        assertEquals(3, calls) // first try + two retries
    }

    @Test fun refusedSearchIsRetried() = runTest {
        var calls = 0
        val search = GeoSearch(HttpClient(MockEngine {
            calls++
            if (calls == 1) respond("busy", HttpStatusCode.ServiceUnavailable) else respond(photon, HttpStatusCode.OK, jsonHeaders)
        })).apply { retryDelaysMillis = listOf(1, 1) }
        assertEquals(2, search.search("Nagoya").size)
        assertEquals(2, calls)
    }

    @Test fun badRequestIsNotRetried() = runTest {
        var calls = 0
        val search = GeoSearch(HttpClient(MockEngine { calls++; respond("{}", HttpStatusCode.BadRequest, jsonHeaders) })).apply { retryDelaysMillis = listOf(1, 1) }
        assertFailsWith<ApiException> { search.search("x") }
        assertEquals(1, calls)
    }

    @Test fun worldSizedOrWrappedAreasAreNotSentAsFilter() = runTest {
        val seen = mutableListOf<String?>()
        val search = GeoSearch(HttpClient(MockEngine { r -> seen += r.url.parameters["bbox"]; respond(photon, HttpStatusCode.OK, jsonHeaders) }))
        search.search("x", near = 35.0 to 500.0, bbox = BBox(-450.0, -85.0, 450.0, 85.0)) // zoomed-out map
        search.search("x", bbox = BBox(-220.5, 35.0, -219.5, 36.0)) // one world to the west
        search.search("x", bbox = BBox(179.0, 35.0, 181.0, 36.0)) // across the date line
        assertEquals(listOf(null, "139.500000,35.000000,140.500000,36.000000", null), seen)
        assertEquals(BBox(10.0, -90.0, 20.0, 90.0), BBox(10.0, -95.0, 20.0, 95.0).normalized())
    }
}

class MergeMovesTest {
    private fun fx() = EditorFixture().also {
        it.seedEntity(EntityType.TRIP, "t1", Trip("t1", "Reise", "2027-05-08", "2027-05-29"))
        it.seedEntity(
            EntityType.TRAIN, "a",
            Train("a", "Deutsche Bahn", Endpoint("Hannover Hbf", date = "2027-05-08", time = "05:09"), Endpoint("Frankfurt Hbf", date = "2027-05-08", time = "07:11"), confirmationNumber = "X1", travelerNames = listOf("A")),
        )
        it.seedEntity(
            EntityType.TRAIN, "b",
            Train("b", "Deutsche Bahn", Endpoint("Frankfurt Hbf", date = "2027-05-08", time = "07:32"), Endpoint("Frankfurt Flughafen", date = "2027-05-08", time = "07:44"), confirmationNumber = "X1", travelerNames = listOf("A", "B"), note = "Gleis 7"),
        )
        it.seedEntity(EntityType.ATTACHMENT, "att", Attachment("att", EntityType.TRAIN, "b", "h".repeat(64), "ticket.pdf", "application/pdf", 3))
    }

    @Test fun trainsBecomeOneJourneyWithStopover() {
        val f = fx()
        f.editor.mergeMoves(f.snapshot(), EntityType.TRAIN, "a", "b")
        val snap = f.snapshot()
        val t = snap.trains.single()
        assertEquals("a", t.id)
        assertEquals("Hannover Hbf", t.depart.name)
        assertEquals("Frankfurt Flughafen", t.arrive.name)
        assertEquals(listOf("Frankfurt Hbf"), t.via.map { it.name })
        assertEquals("07:11", t.via.single().time)
        assertEquals("X1", t.confirmationNumber)
        assertEquals(listOf("A", "B"), t.travelerNames)
        assertEquals("Gleis 7", t.note)
        assertEquals("a", snap.attachments.single().targetId)
        assertEquals(1, f.changes)
    }

    @Test fun flightsKeepEarlierStopovers() {
        val f = EditorFixture()
        f.seedEntity(EntityType.FLIGHT, "f1", Flight("f1", depart = Endpoint("Frankfurt", code = "FRA"), arrive = Endpoint("Taipeh", code = "TPE")))
        f.seedEntity(EntityType.FLIGHT, "f2", Flight("f2", depart = Endpoint("Taipeh", code = "TPE"), arrive = Endpoint("Tokio", code = "NRT")))
        f.editor.mergeMoves(f.snapshot(), EntityType.FLIGHT, "f1", "f2")
        val fl = f.snapshot().flights.single()
        assertEquals(listOf("TPE"), fl.via.map { it.code })
        assertEquals("NRT", fl.arrive.code)
    }

    @Test fun rejectsMixedOrSelf() {
        val f = fx()
        assertFailsWith<IllegalArgumentException> { f.editor.mergeMoves(f.snapshot(), EntityType.TRAIN, "a", "a") }
        assertFailsWith<IllegalArgumentException> { f.editor.mergeMoves(f.snapshot(), EntityType.STAY, "a", "b") }
        assertEquals(2, f.snapshot().trains.size)
    }

    @Test fun setChangedWritesOnlyDifferences() {
        val f = fx()
        val before = f.outbox.size()
        f.editor.setChanged(EntityType.TRAIN, "a", mapOf("carrier" to JsonPrimitive("DB"), "note" to JsonPrimitive("")), mapOf("carrier" to JsonPrimitive("DB"), "note" to JsonPrimitive("neu")))
        assertEquals(before + 1, f.outbox.size())
        assertEquals("neu", f.snapshot().trains.first { it.id == "a" }.note)
    }

    @Test fun dayShowLegsIsEditable() {
        val f = fx()
        f.editor.setDayFields(f.snapshot(), "2027-05-10", mapOf("showLegs" to JsonPrimitive(true)))
        assertTrue(f.snapshot().days.single().showLegs)
    }
}
