package de.wanderlogplus.server

import de.wanderlogplus.server.ai.PlacesSearchTransport
import de.wanderlogplus.shared.model.PlaceCategories
import de.wanderlogplus.shared.model.PlaceCategory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class GooglePlacesSearchTest {
    private val store = SqliteStore(Files.createTempDirectory("wlp-gsearch").resolve("t.db"))
    private var clock = Instant.parse("2026-11-06T10:00:00Z")
    private val bodies = mutableListOf<String>()
    private var answer: Pair<Int, String> = 200 to """{"places":[]}"""
    private val transport = PlacesSearchTransport { body -> bodies += body; answer }
    private val search = GooglePlacesSearch(transport, store) { clock }

    private fun lastBody(): JsonObject = Json.parseToJsonElement(bodies.last()).jsonObject

    @Test
    fun biasWithCircleNearPoint() {
        search.search("Ichiran", 35.0 to 135.7, null)
        val b = lastBody()
        val circle = b["locationBias"]!!.jsonObject["circle"]!!.jsonObject
        assertEquals(35.0, circle["center"]!!.jsonObject["latitude"]!!.jsonPrimitive.double)
        assertEquals(30000.0, circle["radius"]!!.jsonPrimitive.double)
        assertEquals("de", b["languageCode"]!!.jsonPrimitive.content)
        assertEquals(10, b["pageSize"]!!.jsonPrimitive.int)
    }

    @Test
    fun restrictToRectangle() {
        search.search("Ichiran", null, BBox4(135.6, 34.9, 135.8, 35.1))
        val b = lastBody()
        val rect = b["locationRestriction"]!!.jsonObject["rectangle"]!!.jsonObject
        assertEquals(34.9, rect["low"]!!.jsonObject["latitude"]!!.jsonPrimitive.double)
        assertEquals(135.6, rect["low"]!!.jsonObject["longitude"]!!.jsonPrimitive.double)
        assertEquals(35.1, rect["high"]!!.jsonObject["latitude"]!!.jsonPrimitive.double)
        assertEquals(135.8, rect["high"]!!.jsonObject["longitude"]!!.jsonPrimitive.double)
        assertNull(b["locationBias"])
    }

    @Test
    fun mapsHits() {
        answer = 200 to """{"places":[{"id":"g1","displayName":{"text":"Ichiran"},"formattedAddress":"Kyoto","location":{"latitude":35.0,"longitude":135.7},
            "types":["ramen_restaurant","restaurant"],"rating":4.3,"userRatingCount":1200,
            "photos":[{"name":"places/g1/photos/p1","widthPx":800,"heightPx":600}]}]}"""
        val hit = search.search("Ichiran", null, null).single()
        assertEquals("g1", hit.googlePlaceId)
        assertEquals("Ichiran", hit.name)
        assertEquals(PlaceCategory.FOOD, hit.category)
        assertEquals(4.3, hit.rating)
        assertEquals(1200, hit.ratingCount)
        assertEquals("places/g1/photos/p1", hit.photos.single().name)
        assertEquals(800, hit.photos.single().width)
    }

    @Test
    fun cachesSameQuery() {
        search.search("Ichiran", null, null)
        search.search("Ichiran", null, null)
        assertEquals(1, bodies.size)
        clock = clock.plus(Duration.ofHours(25))
        search.search("Ichiran", null, null)
        assertEquals(2, bodies.size)
    }

    @Test
    fun googleErrorIsUnavailable() {
        answer = 429 to "{}"
        assertFailsWith<PlacesSearchUnavailable> { search.search("a b", null, null) }
        answer = 200 to "nope"
        assertFailsWith<PlacesSearchUnavailable> { search.search("c d", null, null) }
    }

    @Test
    fun noTransportIsNotConfigured() {
        assertFailsWith<PlacesSearchNotConfigured> { GooglePlacesSearch(null, store).search("x y", null, null) }
    }

    @Test
    fun categoriesFromGoogleTypes() {
        assertEquals(PlaceCategory.SIGHT, PlaceCategories.fromGoogle(listOf("shinto_shrine")))
        assertEquals(PlaceCategory.OTHER, PlaceCategories.fromGoogle(listOf("lodging")))
        assertEquals(PlaceCategory.SHOPPING, PlaceCategories.fromGoogle(listOf("clothing_store")))
        assertEquals(PlaceCategory.NIGHTLIFE, PlaceCategories.fromGoogle(listOf("bar", "restaurant")))
    }
}
