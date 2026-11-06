package de.wanderlogplus.server

import de.wanderlogplus.server.ai.GeocoderUnavailable
import de.wanderlogplus.server.ai.PlacesSearchTransport
import de.wanderlogplus.shared.model.PlaceCategories
import de.wanderlogplus.shared.sync.GooglePlaceHit
import de.wanderlogplus.shared.sync.PlacePhoto
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.time.Duration
import java.time.Instant

/** No Google key for the place search is configured. */
class PlacesSearchNotConfigured : RuntimeException("places search not configured")

/** Google could not be reached or answered with an error. */
class PlacesSearchUnavailable : RuntimeException("places search unavailable")

/** A map area: west/south/east/north in degrees. */
data class BBox4(val west: Double, val south: Double, val east: Double, val north: Double)

/** Field mask of the full search: what the app shows and keeps (id, name, address, position, types, rating, photos). */
const val SEARCH_FIELD_MASK =
    "places.id,places.displayName,places.formattedAddress,places.location,places.types,places.rating,places.userRatingCount,places.photos"

/**
 * Google's Text Search (Places API New) for the app: biased to a point (30 km) or restricted to a map area. Same
 * requests are answered from the cache for 24 h, so typing the same name again costs nothing. Blocking.
 */
class GooglePlacesSearch(
    private val transport: PlacesSearchTransport?,
    private val store: SqliteStore,
    private val now: () -> Instant = Instant::now,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val listSer = ListSerializer(GooglePlaceHit.serializer())

    val configured: Boolean get() = transport != null

    fun search(q: String, near: Pair<Double, Double>?, bbox: BBox4?, limit: Int = 10): List<GooglePlaceHit> {
        val t = transport ?: throw PlacesSearchNotConfigured()
        val body = requestBody(q.trim(), near, bbox, limit.coerceIn(1, 20))
        val key = "gsearch:" + sha256(body)
        val nowMs = now().toEpochMilli()
        store.cachedRoute(key)?.let { (cached, at) ->
            if (nowMs - at < CACHE.toMillis()) runCatching { json.decodeFromString(listSer, cached) }.getOrNull()?.let { return it }
        }
        val (status, text) = try {
            t.searchText(body)
        } catch (e: GeocoderUnavailable) {
            throw PlacesSearchUnavailable()
        }
        if (status !in 200..299) throw PlacesSearchUnavailable()
        val hits = try {
            parse(text)
        } catch (e: Exception) {
            throw PlacesSearchUnavailable()
        }
        store.putCachedRoute(key, json.encodeToString(listSer, hits), nowMs)
        return hits
    }

    private fun requestBody(q: String, near: Pair<Double, Double>?, bbox: BBox4?, limit: Int): String = buildJsonObject {
        put("textQuery", q)
        put("languageCode", "de")
        put("pageSize", limit)
        if (bbox != null) {
            putJsonObject("locationRestriction") {
                putJsonObject("rectangle") {
                    putJsonObject("low") { put("latitude", bbox.south); put("longitude", bbox.west) }
                    putJsonObject("high") { put("latitude", bbox.north); put("longitude", bbox.east) }
                }
            }
        } else if (near != null) {
            putJsonObject("locationBias") {
                putJsonObject("circle") {
                    putJsonObject("center") { put("latitude", near.first); put("longitude", near.second) }
                    put("radius", BIAS_RADIUS_M)
                }
            }
        }
    }.toString()

    private fun parse(text: String): List<GooglePlaceHit> {
        val places = json.parseToJsonElement(text).jsonObject["places"] as? JsonArray ?: return emptyList()
        return places.mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val id = p.str("id") ?: return@mapNotNull null
            val name = (p["displayName"] as? JsonObject)?.str("text") ?: return@mapNotNull null
            val loc = p["location"] as? JsonObject ?: return@mapNotNull null
            val lat = (loc["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val lng = (loc["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val types = (p["types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            val photos = (p["photos"] as? JsonArray)?.mapNotNull { ph ->
                val o = ph as? JsonObject ?: return@mapNotNull null
                val n = o.str("name") ?: return@mapNotNull null
                PlacePhoto(n, (o["widthPx"] as? JsonPrimitive)?.intOrNull ?: 0, (o["heightPx"] as? JsonPrimitive)?.intOrNull ?: 0)
            }.orEmpty()
            GooglePlaceHit(
                googlePlaceId = id, name = name, address = p.str("formattedAddress").orEmpty(), lat = lat, lng = lng, types = types,
                rating = (p["rating"] as? JsonPrimitive)?.doubleOrNull, ratingCount = (p["userRatingCount"] as? JsonPrimitive)?.intOrNull,
                photos = photos, category = PlaceCategories.fromGoogle(types),
            )
        }
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }

    private fun sha256(s: String): String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

    companion object {
        val CACHE: Duration = Duration.ofHours(24)
        const val BIAS_RADIUS_M = 30000.0
    }
}
