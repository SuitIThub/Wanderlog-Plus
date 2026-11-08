package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** A point of the trip the reel's places are measured against (a stay or a saved place). */
data class Anchor(val name: String, val lat: Double, val lng: Double)

/** What the assistant knows about the trip: regions (city names) and anchors. No booking data. */
/** A saved place of the trip, for spotting reel places that are already saved. */
data class SavedRef(val savedPlaceId: String, val name: String, val lat: Double, val lng: Double, val googlePlaceId: String = "")

data class TripContext(val regionNames: List<String>, val anchors: List<Anchor>, val saved: List<SavedRef> = emptyList()) {
    /**
     * The saved place a found place already is: the same Google id, or a similar name (one contains the other,
     * ignoring case, spaces and punctuation) within [SAME_PLACE_KM].
     */
    fun alreadySaved(name: String, lat: Double?, lng: Double?, googlePlaceId: String?): SavedRef? {
        googlePlaceId?.takeIf { it.isNotBlank() }?.let { g -> saved.firstOrNull { it.googlePlaceId == g }?.let { return it } }
        if (lat == null || lng == null) return null
        val n = normName(name).ifEmpty { return null }
        return saved.filter { distanceKm(lat, lng, it.lat, it.lng) <= SAME_PLACE_KM }
            .firstOrNull { s -> normName(s.name).let { it.isNotEmpty() && (n in it || it in n) } }
    }

    /** Nearest anchor and its distance in km, or null without anchors. */
    fun nearest(lat: Double, lng: Double): Pair<Anchor, Double>? =
        anchors.map { it to distanceKm(lat, lng, it.lat, it.lng) }.minByOrNull { it.second }

    companion object {
        /** Reads regions, stays and saved places from the server's store (deleted records are skipped). */
        fun from(store: EntityStore): TripContext {
            fun <T> live(type: EntityType, decode: (de.wanderlogplus.shared.sync.EntityRecord) -> T): List<T> =
                store.all(type).filter { !it.isDeleted }.mapNotNull { r -> try { decode(r) } catch (e: Exception) { null } }
            val places = live(EntityType.PLACE) { EntityCodec.decode<Place>(it) }.associateBy { it.id }
            val regions = live(EntityType.REGION) { EntityCodec.decode<Region>(it) }
            val stays = live(EntityType.STAY) { EntityCodec.decode<Stay>(it) }
            val saved = live(EntityType.SAVED_PLACE) { EntityCodec.decode<SavedPlace>(it) }
            val anchors = (stays.map { it.placeId } + saved.map { it.placeId }).distinct().mapNotNull { places[it] }
                .filter { !(it.lat == 0.0 && it.lng == 0.0) }
                .map { Anchor(it.name, it.lat, it.lng) }
            val savedRefs = saved.mapNotNull { s ->
                places[s.placeId]?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) }?.let { SavedRef(s.id, it.name, it.lat, it.lng, it.googlePlaceId) }
            }
            return TripContext(regions.map { it.name }.filter { it.isNotBlank() }, anchors, savedRefs)
        }
    }
}

const val SAME_PLACE_KM = 0.3

private fun normName(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
    return 2 * 6371.0 * asin(sqrt(h))
}

/** A geocoded place. */
data class GeoHit(
    val lat: Double, val lng: Double, val address: String?, val country: String? = null,
    /** Google's id when the hit came from Google Places (photos and details later). */
    val googlePlaceId: String? = null,
)

/** The geocoder did not answer (overloaded, rate-limited, offline) even after retries: unknown, not "not found". */
class GeocoderUnavailable : RuntimeException()

/** Finds coordinates for a place name; null when nothing matches; [GeocoderUnavailable] when it cannot tell. Blocking. */
fun interface Geocoder {
    fun find(query: String): GeoHit?
}

/**
 * Photon (OpenStreetMap) geocoding, biased towards Japan's centre. The public service refuses bursts (503/429), so
 * all requests of the process go out one after another, at least [minIntervalMillis] apart, and refused ones are
 * retried after [backoffMillis].
 */
class PhotonGeocoder(
    private val baseUrl: String = "https://photon.komoot.io",
    private val minIntervalMillis: Long = 400,
    private val backoffMillis: List<Long> = listOf(1_000, 2_000, 4_000),
    private val sleep: (Long) -> Unit = { Thread.sleep(it) },
) : Geocoder {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun find(query: String): GeoHit? {
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8)
        if (q.isEmpty()) return null
        val uri = URI.create("${baseUrl.trimEnd('/')}/api/?q=$q&limit=1&lat=36.2&lon=138.25")
        for (attempt in 0..backoffMillis.size) {
            if (attempt > 0) sleep(backoffMillis[attempt - 1])
            val (status, body) = send(uri) ?: continue // network trouble: try again
            if (status == 429 || status >= 500) continue
            if (status !in 200..299) return null
            return parse(body ?: continue) ?: if (isFeatureList(body)) null else continue // a non-JSON page means overload
        }
        throw GeocoderUnavailable()
    }

    /**
     * Any Photon request ([pathAndQuery] like `reverse?lat=…`) with the same pacing and retries as [find]: the JSON
     * feature list, or null for a refused request; [GeocoderUnavailable] when Photon does not answer.
     */
    fun features(pathAndQuery: String): kotlinx.serialization.json.JsonArray? {
        val uri = URI.create("${baseUrl.trimEnd('/')}/$pathAndQuery")
        for (attempt in 0..backoffMillis.size) {
            if (attempt > 0) sleep(backoffMillis[attempt - 1])
            val (status, body) = send(uri) ?: continue
            if (status == 429 || status >= 500) continue
            if (status !in 200..299) return null
            val list = try {
                json.parseToJsonElement(body ?: continue).jsonObject["features"]?.jsonArray
            } catch (e: Exception) {
                null
            } ?: continue
            return list
        }
        throw GeocoderUnavailable()
    }

    private fun send(uri: URI): Pair<Int, String?>? = synchronized(LOCK) {
        val wait = lastRequestAt + minIntervalMillis - System.currentTimeMillis()
        if (wait > 0) sleep(wait)
        lastRequestAt = System.currentTimeMillis()
        val request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15)).header("User-Agent", "WanderlogPlus-Server").GET().build()
        try {
            http.send(request, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
        } catch (e: IOException) {
            null
        }
    }

    private fun isFeatureList(body: String) = try {
        json.parseToJsonElement(body).jsonObject.containsKey("features")
    } catch (e: Exception) {
        false
    }

    private fun parse(body: String): GeoHit? = try {
        val f = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray?.firstOrNull()?.jsonObject
        f?.let {
            val c = it["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
            val props = it["properties"]?.jsonObject
            fun p(k: String) = (props?.get(k) as? JsonPrimitive)?.content?.takeIf { v -> v.isNotBlank() }
            GeoHit(
                lat = (c[1] as JsonPrimitive).content.toDouble(),
                lng = (c[0] as JsonPrimitive).content.toDouble(),
                address = listOfNotNull(p("street"), p("city"), p("country")).joinToString(", ").ifBlank { null },
                country = p("countrycode") ?: p("country"),
            )
        }
    } catch (e: Exception) {
        null
    }

    private companion object {
        val LOCK = Any()
        var lastRequestAt = 0L
    }
}
