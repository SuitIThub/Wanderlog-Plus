package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.RouteInfo
import de.wanderlogplus.shared.sync.RouteRequest
import de.wanderlogplus.shared.sync.TravelMode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.Locale

/** No Google Routes API key is configured. */
class RoutesNotConfigured : RuntimeException("routes not configured")

/** The Routes API answered, but without a route (e.g. no transit connection). */
class NoRoute : RuntimeException("no route")

/** The Routes API could not be reached or answered with an error. */
class RoutesUnavailable : RuntimeException("routes unavailable")

/** Sends a Routes API request body; returns status code and response body. Blocking. */
fun interface RoutesTransport {
    fun post(body: String): Pair<Int, String>
}

/** Talks to `routes.googleapis.com/directions/v2:computeRoutes` with the given API key. */
class GoogleRoutesTransport(private val apiKey: String) : RoutesTransport {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun post(body: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create(ENDPOINT))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", "routes.duration,routes.distanceMeters,routes.polyline.encodedPolyline")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw RoutesUnavailable()
        }
        return response.statusCode() to response.body()
    }

    private companion object {
        const val ENDPOINT = "https://routes.googleapis.com/directions/v2:computeRoutes"
    }
}

/**
 * Computes routes via the Google Routes API and caches them in the database, so each leg costs quota only once for both
 * devices. Transit routes depend on timetables and expire after [TRANSIT_TTL]; all others are kept.
 */
class RoutesService(
    private val store: SqliteStore,
    private val transport: RoutesTransport?,
    private val now: () -> Instant = Instant::now,
) {
    val configured: Boolean get() = transport != null

    fun compute(req: RouteRequest): RouteInfo {
        val t = transport ?: throw RoutesNotConfigured()
        validate(req)
        val key = cacheKey(req)
        store.cachedRoute(key)?.let { (json, createdAt) ->
            val fresh = req.mode != TravelMode.TRANSIT || Instant.ofEpochMilli(createdAt).plus(TRANSIT_TTL).isAfter(now())
            if (fresh) return json.decodeRoute() ?: throw NoRoute()
        }
        val (status, body) = t.post(requestBody(req))
        if (status !in 200..299) throw RoutesUnavailable()
        val info = parse(body)
        // A missing route is cached too (as an empty object), so a walk across the sea is not asked again and again.
        store.putCachedRoute(key, info?.let { JSON.encodeToString(RouteInfo.serializer(), it) } ?: "{}", now().toEpochMilli())
        return info ?: throw NoRoute()
    }

    private fun validate(req: RouteRequest) {
        val ok = listOf(req.fromLat, req.toLat).all { it in -90.0..90.0 } && listOf(req.fromLng, req.toLng).all { it in -180.0..180.0 }
        if (!ok) throw BadInput()
    }

    private fun String.decodeRoute(): RouteInfo? =
        if (this == "{}") null else JSON.decodeFromString(RouteInfo.serializer(), this)

    companion object {
        val TRANSIT_TTL: Duration = Duration.ofDays(7)
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Coordinates rounded to 5 decimals (about 1 m) plus the mode. */
        fun cacheKey(req: RouteRequest): String {
            fun r(v: Double) = String.format(Locale.ROOT, "%.5f", v + 0.0)
            return listOf(req.mode.name, r(req.fromLat), r(req.fromLng), r(req.toLat), r(req.toLng)).joinToString("|")
        }

        fun requestBody(req: RouteRequest): String = buildJsonObject {
            putJsonObject("origin") { putLatLng(req.fromLat, req.fromLng) }
            putJsonObject("destination") { putLatLng(req.toLat, req.toLng) }
            put("travelMode", req.mode.name)
            put("languageCode", "de")
            put("units", "METRIC")
        }.toString()

        private fun kotlinx.serialization.json.JsonObjectBuilder.putLatLng(lat: Double, lng: Double) {
            putJsonObject("location") {
                putJsonObject("latLng") {
                    put("latitude", lat)
                    put("longitude", lng)
                }
            }
        }

        /** First route of a computeRoutes response; null when the response has no route. Duration comes as "123s". */
        fun parse(body: String): RouteInfo? {
            val root = try {
                JSON.parseToJsonElement(body) as? JsonObject
            } catch (e: Exception) {
                throw RoutesUnavailable()
            } ?: throw RoutesUnavailable()
            val route = (root["routes"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
            val duration = (route["duration"] as? JsonPrimitive)?.content?.removeSuffix("s")?.toDoubleOrNull()?.toLong() ?: return null
            val distance = (route["distanceMeters"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            val polyline = ((route["polyline"] as? JsonObject)?.get("encodedPolyline") as? JsonPrimitive)?.content ?: ""
            return RouteInfo(duration, distance, polyline)
        }
    }
}
