package de.wanderlogplus.client

import de.wanderlogplus.shared.model.PlaceCategory
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import java.io.IOException
import java.util.Locale

/**
 * A search hit: display [name], a short [detail] line (street, city, country), its coordinates and the OpenStreetMap
 * tag it was found by ([osmKey]/[osmValue], e.g. amenity/restaurant), when known.
 */
data class GeoPlace(
    val name: String,
    val detail: String,
    val lat: Double,
    val lng: Double,
    val osmKey: String? = null,
    val osmValue: String? = null,
) {
    /** Best guess of the trip category from the OpenStreetMap tag. */
    val category: PlaceCategory
        get() = when {
            osmKey == "amenity" && osmValue in setOf("bar", "pub", "nightclub", "biergarten") -> PlaceCategory.NIGHTLIFE
            osmKey == "amenity" && osmValue in setOf("restaurant", "cafe", "fast_food", "food_court", "ice_cream") -> PlaceCategory.FOOD
            osmKey == "shop" -> PlaceCategory.SHOPPING
            osmKey in setOf("tourism", "historic", "leisure", "natural") -> PlaceCategory.SIGHT
            osmKey == "amenity" && osmValue in setOf("place_of_worship", "theatre", "arts_centre") -> PlaceCategory.SIGHT
            else -> PlaceCategory.OTHER
        }
}

/** A map area: west/south/east/north in degrees. */
data class BBox(val west: Double, val south: Double, val east: Double, val north: Double) {
    val centerLat get() = (south + north) / 2
    val centerLng get() = (west + east) / 2

    fun contains(lat: Double, lng: Double) = lat in south..north && lng in west..east

    internal fun param(): String = listOf(west, south, east, north).joinToString(",") { String.format(Locale.ROOT, "%.6f", it) }

    /**
     * This area as Photon accepts it: latitudes clamped to ±90; null when it is useless as a filter (wider than the
     * world, crossing the date line, empty or not a number). A zoomed-out map reports longitudes beyond ±180.
     */
    fun normalized(): BBox? {
        val values = listOf(west, south, east, north)
        if (values.any { it.isNaN() || it.isInfinite() }) return null
        if (east - west >= 360.0 || east <= west || north <= south) return null
        var w = west
        var e = east
        while (w < -180.0) { w += 360.0; e += 360.0 }
        while (w > 180.0) { w -= 360.0; e -= 360.0 }
        if (e > 180.0) return null // crosses the date line: Photon cannot express that
        return BBox(w, south.coerceIn(-90.0, 90.0), e, north.coerceIn(-90.0, 90.0))
    }
}

/**
 * Place search via Photon (OpenStreetMap data, no key). Never sends the server token: this is a third-party service.
 * [near] biases results towards a coordinate; [bbox] restricts them to a map area.
 */
class GeoSearch(http: HttpClient, private val baseUrl: String = "https://photon.komoot.io") {
    private val client = http.config {
        install(HttpTimeout) {
            connectTimeoutMillis = 10_000
            requestTimeoutMillis = 15_000
        }
        expectSuccess = false
    }
    private val json = Json { ignoreUnknownKeys = true }

    /** Empty list for a blank query; [ApiException] (NETWORK/SERVER) when the service fails. */
    suspend fun search(query: String, near: Pair<Double, Double>? = null, limit: Int = 8, bbox: BBox? = null): List<GeoPlace> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        return get("api/") {
            parameter("q", q)
            parameter("limit", limit)
            parameter("lang", "de")
            near?.let { (lat, lng) ->
                parameter("lat", lat.coerceIn(-90.0, 90.0))
                parameter("lon", ((lng + 540.0) % 360.0) - 180.0)
            }
            bbox?.normalized()?.let { parameter("bbox", it.param()) }
        }
    }

    /** The nearest named place or address at a point (e.g. a long press on the map); null when there is none. */
    suspend fun reverse(lat: Double, lng: Double): GeoPlace? = get("reverse") {
        parameter("lat", lat)
        parameter("lon", lng)
        parameter("lang", "de")
        parameter("limit", 1)
    }.firstOrNull()

    /** Photon refuses bursts (503/429) and has short hiccups: such answers are retried after these pauses. */
    var retryDelaysMillis: List<Long> = listOf(800, 2_000)

    private suspend fun get(path: String, params: HttpRequestBuilder.() -> Unit): List<GeoPlace> {
        var attempt = 0
        while (true) {
            val result = try {
                getOnce(path, params)
            } catch (e: ApiException) {
                val retry = e.kind == ApiErrorKind.NETWORK || e.status == 429 || (e.status ?: 0) >= 500 || e.status == null
                if (!retry || attempt >= retryDelaysMillis.size) throw e
                kotlinx.coroutines.delay(retryDelaysMillis[attempt++])
                continue
            }
            return result
        }
    }

    private suspend fun getOnce(path: String, params: HttpRequestBuilder.() -> Unit): List<GeoPlace> {
        val response = try {
            client.get("${baseUrl.trimEnd('/')}/$path") {
                params()
                header(HttpHeaders.UserAgent, "WanderlogPlus")
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            throw ApiException(ApiErrorKind.NETWORK, cause = e)
        }
        if (response.status.value !in 200..299) throw ApiException(ApiErrorKind.SERVER, status = response.status.value)
        val body = try {
            json.parseToJsonElement(response.bodyAsText()) as? JsonObject
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        } ?: throw ApiException(ApiErrorKind.SERVER, message = "Unerwartete Antwort der Ortssuche")
        return parse(body)
    }

    internal fun parse(body: JsonObject): List<GeoPlace> =
        (body["features"] as? JsonArray).orEmpty().mapNotNull { f ->
            val feature = f as? JsonObject ?: return@mapNotNull null
            val coords = ((feature["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray) ?: return@mapNotNull null
            val lng = (coords.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lat = (coords.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val props = feature["properties"] as? JsonObject ?: JsonObject(emptyMap())
            fun p(key: String) = (props[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            val street = listOfNotNull(p("street"), p("housenumber")).joinToString(" ").takeIf { it.isNotBlank() }
            val name = p("name") ?: street ?: return@mapNotNull null
            val detail = listOfNotNull(street.takeIf { p("name") != null }, p("city") ?: p("county"), p("state"), p("country"))
                .distinct().joinToString(", ")
            GeoPlace(name, detail, lat, lng, p("osm_key"), p("osm_value"))
        }
}
