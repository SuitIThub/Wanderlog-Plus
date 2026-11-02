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
data class TripContext(val regionNames: List<String>, val anchors: List<Anchor>) {
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
            return TripContext(regions.map { it.name }.filter { it.isNotBlank() }, anchors)
        }
    }
}

fun distanceKm(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
    return 2 * 6371.0 * asin(sqrt(h))
}

/** A geocoded place. */
data class GeoHit(val lat: Double, val lng: Double, val address: String?)

/** Finds coordinates for a place name; null when nothing matches. Blocking. */
fun interface Geocoder {
    fun find(query: String): GeoHit?
}

/** Photon (OpenStreetMap) geocoding, biased towards Japan's centre. */
class PhotonGeocoder(private val baseUrl: String = "https://photon.komoot.io") : Geocoder {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()
    private val json = Json { ignoreUnknownKeys = true }

    override fun find(query: String): GeoHit? {
        val q = URLEncoder.encode(query.trim(), Charsets.UTF_8)
        if (q.isEmpty()) return null
        val request = HttpRequest.newBuilder(URI.create("${baseUrl.trimEnd('/')}/api/?q=$q&limit=1&lat=36.2&lon=138.25"))
            .timeout(Duration.ofSeconds(15))
            .header("User-Agent", "WanderlogPlus-Server")
            .GET().build()
        val body = try {
            http.send(request, HttpResponse.BodyHandlers.ofString()).takeIf { it.statusCode() in 200..299 }?.body()
        } catch (e: IOException) {
            null
        } ?: return null
        return try {
            val f = json.parseToJsonElement(body).jsonObject["features"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val c = f["geometry"]!!.jsonObject["coordinates"]!!.jsonArray
            val props = f["properties"]?.jsonObject
            fun p(k: String) = (props?.get(k) as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            GeoHit(
                lat = (c[1] as JsonPrimitive).content.toDouble(),
                lng = (c[0] as JsonPrimitive).content.toDouble(),
                address = listOfNotNull(p("street"), p("city"), p("country")).joinToString(", ").ifBlank { null },
            )
        } catch (e: Exception) {
            null
        }
    }
}
