package de.wanderlogplus.client

import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.sync.GooglePlaceHit
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlaceFinderTest {
    private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
    private val photonHit = """{"features":[{"geometry":{"type":"Point","coordinates":[137.596,35.5768]},
        "properties":{"name":"Tsumago","city":"Nagiso","country":"Japan","osm_key":"tourism","osm_value":"attraction"}}]}"""
    private var photonCalls = 0
    private val photon = GeoSearch(HttpClient(MockEngine { photonCalls++; respond(photonHit, HttpStatusCode.OK, jsonHeaders) }))

    @Test
    fun googleFirst() = runTest {
        val f = PlaceFinder({ _, _, _ -> listOf(GooglePlaceHit("g1", "Ichiran", "Kyoto", 35.0, 135.7, category = PlaceCategory.FOOD)) }, photon)
        val r = f.search("Ichiran", null, null)
        assertTrue(r.viaGoogle)
        val p = r.places.single()
        assertEquals("g1", p.googlePlaceId)
        assertEquals("Kyoto", p.detail)
        assertEquals(PlaceCategory.FOOD, p.category)
        assertEquals(0, photonCalls)
    }

    @Test
    fun fallsBackOnError() = runTest {
        val f = PlaceFinder({ _, _, _ -> throw ApiException(ApiErrorKind.NOT_CONFIGURED, code = "places_search_not_configured") }, photon)
        val r = f.search("Tsumago", null, null)
        assertFalse(r.viaGoogle)
        assertEquals("Tsumago", r.places.single().name)
        assertEquals(null, r.places.single().googlePlaceId)
    }

    @Test
    fun noServerUsesPhoton() = runTest {
        val r = PlaceFinder(null, photon).search("Tsumago", null, null)
        assertFalse(r.viaGoogle)
        assertEquals(1, photonCalls)
    }

    @Test
    fun emptyGoogleIsAnswer() = runTest {
        val r = PlaceFinder({ _, _, _ -> emptyList() }, photon).search("Nichts", null, null)
        assertTrue(r.viaGoogle)
        assertTrue(r.places.isEmpty())
        assertEquals(0, photonCalls)
    }
}
