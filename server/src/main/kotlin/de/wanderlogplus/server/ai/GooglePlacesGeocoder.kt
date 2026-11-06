package de.wanderlogplus.server.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** Sends one Places API (New) Text Search; returns status and body. Replaced in tests. */
fun interface PlacesSearchTransport {
    fun searchText(body: String): Pair<Int, String>
}

class HttpPlacesSearchTransport(
    private val apiKey: String,
    private val fieldMask: String = "places.id,places.displayName,places.formattedAddress,places.location",
) : PlacesSearchTransport {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun searchText(body: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("https://places.googleapis.com/v1/places:searchText"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", fieldMask)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return try {
            http.send(request, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
        } catch (e: IOException) {
            throw GeocoderUnavailable()
        }
    }
}

/**
 * Finds a named place (also small restaurants and bars that OpenStreetMap lacks) with Google's Text Search,
 * preferring Japan. The first result is the hit; [GeoHit.country] is "JP" for places in Japan, else the last part
 * of the address.
 */
class GooglePlacesGeocoder(private val transport: PlacesSearchTransport) : Geocoder {
    private val json = Json { ignoreUnknownKeys = true }

    override fun find(query: String): GeoHit? {
        val q = query.trim()
        if (q.isEmpty()) return null
        val body = buildJsonObject {
            put("textQuery", q)
            put("regionCode", "JP")
            put("languageCode", "de")
            put("pageSize", 1)
        }.toString()
        val (status, text) = transport.searchText(body)
        if (status !in 200..299) throw GeocoderUnavailable()
        val place = try {
            (json.parseToJsonElement(text).jsonObject["places"] as? JsonArray)?.firstOrNull() as? JsonObject
        } catch (e: Exception) {
            throw GeocoderUnavailable()
        } ?: return null
        val loc = place["location"] as? JsonObject ?: return null
        val lat = (loc["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val lng = (loc["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return null
        val address = (place["formattedAddress"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        // With regionCode JP Google leaves the country out of Japanese addresses, so the position decides.
        val inJapan = lat in 24.0..46.0 && lng in 122.0..154.0
        val country = if (inJapan || address?.let { "Japan" in it || "日本" in it } == true) "JP"
        else address?.substringAfterLast(',')?.trim()?.ifBlank { null }
        val id = (place["id"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
        return GeoHit(lat, lng, address, country, id)
    }
}

/** Asks [primary] first; when it finds nothing or does not answer, [fallback]. */
class FallbackGeocoder(private val primary: Geocoder, private val fallback: Geocoder) : Geocoder {
    override fun find(query: String): GeoHit? {
        val first = try { primary.find(query) } catch (e: GeocoderUnavailable) { null }
        return first ?: fallback.find(query)
    }
}
