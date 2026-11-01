package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.RouteInfo
import de.wanderlogplus.shared.sync.RouteRequest
import de.wanderlogplus.shared.sync.TravelMode
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoutesServiceTest {
    private val tmp = Files.createTempDirectory("wlp-routes")
    private val store = SqliteStore(tmp.resolve("wanderlog-plus.db"))
    private val req = RouteRequest(35.5806, 137.5694, 35.5320, 137.5700, TravelMode.WALK)

    private val okBody = """{"routes":[{"distanceMeters":7834,"duration":"7260s","polyline":{"encodedPolyline":"_p~iF~ps|U_ulLnnqC"}}]}"""

    private class CountingTransport(var status: Int = 200, var body: String) : RoutesTransport {
        val sent = mutableListOf<String>()
        override fun post(body: String): Pair<Int, String> {
            sent += body
            return status to this.body
        }
    }

    @Test
    fun parsesDurationDistanceAndPolyline() {
        assertEquals(RouteInfo(7260, 7834, "_p~iF~ps|U_ulLnnqC"), RoutesService.parse(okBody))
    }

    @Test
    fun emptyResponseMeansNoRoute() {
        assertEquals(null, RoutesService.parse("{}"))
    }

    @Test
    fun requestBodyHasOriginDestinationAndMode() {
        val body = Json.parseToJsonElement(RoutesService.requestBody(req)).jsonObject
        assertEquals("WALK", body["travelMode"]!!.jsonPrimitive.content)
        val origin = body["origin"]!!.jsonObject["location"]!!.jsonObject["latLng"]!!.jsonObject
        assertEquals(35.5806, origin["latitude"]!!.jsonPrimitive.content.toDouble())
        assertEquals("de", body["languageCode"]!!.jsonPrimitive.content)
    }

    @Test
    fun secondCallIsServedFromCache() {
        val t = CountingTransport(body = okBody)
        val service = RoutesService(store, t)
        val first = service.compute(req)
        val second = service.compute(req)
        assertEquals(first, second)
        assertEquals(1, t.sent.size)
    }

    @Test
    fun noRouteIsCachedToo() {
        val t = CountingTransport(body = "{}")
        val service = RoutesService(store, t)
        assertFailsWith<NoRoute> { service.compute(req) }
        assertFailsWith<NoRoute> { service.compute(req) }
        assertEquals(1, t.sent.size)
    }

    @Test
    fun transitExpiresAfterSevenDays() {
        val t = CountingTransport(body = okBody)
        var now = Instant.parse("2027-05-01T10:00:00Z")
        val service = RoutesService(store, t) { now }
        val transit = req.copy(mode = TravelMode.TRANSIT)
        service.compute(transit)
        now = now.plus(Duration.ofDays(6))
        service.compute(transit)
        assertEquals(1, t.sent.size)
        now = now.plus(Duration.ofDays(2))
        service.compute(transit)
        assertEquals(2, t.sent.size)
    }

    @Test
    fun upstreamErrorIsUnavailableAndNotCached() {
        val t = CountingTransport(status = 403, body = """{"error":{}}""")
        val service = RoutesService(store, t)
        assertFailsWith<RoutesUnavailable> { service.compute(req) }
        t.status = 200; t.body = okBody
        assertEquals(7260, service.compute(req).durationSeconds)
    }

    @Test
    fun invalidCoordinatesAreBadInput() {
        val service = RoutesService(store, CountingTransport(body = okBody))
        assertFailsWith<BadInput> { service.compute(req.copy(fromLat = 120.0)) }
    }

    @Test
    fun endpointWithoutKeyAnswers503() = testApplication {
        application { module(testServices(tmp)) }
        val r = client.post("/api/routes") {
            authed()
            contentType(ContentType.Application.Json)
            setBody("""{"fromLat":35.5,"fromLng":137.5,"toLat":35.6,"toLng":137.6,"mode":"WALK"}""")
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(r.bodyAsText().contains("routes_not_configured"))
    }

    @Test
    fun endpointReturnsRoute() = testApplication {
        val base = testServices(tmp)
        val services = Services(base.config, base.store, base.sync, base.files, base.imports, RoutesService(base.store, CountingTransport(body = okBody)))
        application { module(services) }
        val r = client.post("/api/routes") {
            authed()
            contentType(ContentType.Application.Json)
            setBody("""{"fromLat":35.5,"fromLng":137.5,"toLat":35.6,"toLng":137.6,"mode":"WALK"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertTrue(r.bodyAsText().contains("\"durationSeconds\":7260"))
    }

    @Test
    fun endpointRequiresAuth() = testApplication {
        application { module(testServices(tmp)) }
        val r = client.post("/api/routes") {
            contentType(ContentType.Application.Json)
            setBody("""{"fromLat":35.5,"fromLng":137.5,"toLat":35.6,"toLng":137.6,"mode":"WALK"}""")
        }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }
}
