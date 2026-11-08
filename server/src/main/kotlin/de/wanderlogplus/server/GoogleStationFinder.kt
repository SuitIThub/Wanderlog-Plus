package de.wanderlogplus.server

import de.wanderlogplus.server.ai.distanceKm
import de.wanderlogplus.shared.sync.TransitStation
import de.wanderlogplus.shared.sync.TransitStationNear
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.roundToInt

/** One POST to the Places API (New), e.g. "places:searchNearby"; returns status and body. Replaced in tests. */
fun interface PlacesApi {
    fun post(path: String, body: String, fieldMask: String): Pair<Int, String>
}

class HttpPlacesApi(private val apiKey: String) : PlacesApi {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun post(path: String, body: String, fieldMask: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("https://places.googleapis.com/v1/$path"))
            .timeout(Duration.ofSeconds(20))
            .header("Content-Type", "application/json")
            .header("X-Goog-Api-Key", apiKey)
            .header("X-Goog-FieldMask", fieldMask)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build()
        return try {
            http.send(request, HttpResponse.BodyHandlers.ofString()).let { it.statusCode() to it.body() }
        } catch (e: IOException) {
            599 to ""
        }
    }
}

/**
 * Railway and subway stations from Google Places: the nearest ones to a point (Nearby Search, 3 → 10 → 30 km) and a
 * station by its Japanese name (Text Search). Names come in Japanese for the timetable (京都駅 → 京都) and in Latin
 * letters for display. Stations do not move, so answers are kept for good. When Google fails or finds nothing,
 * [fallback] (OpenStreetMap) answers.
 */
class GoogleStationFinder(
    private val api: PlacesApi,
    private val store: SqliteStore,
    private val fallback: StationFinder?,
) : StationFinder {
    private val json = Json { ignoreUnknownKeys = true }
    private val nearSer = ListSerializer(TransitStationNear.serializer())

    override fun near(lat: Double, lng: Double, limit: Int): List<TransitStationNear> {
        // "2": entries before the prefecture was kept are asked again.
        val key = "gstations2:" + String.format(java.util.Locale.ROOT, "%.3f,%.3f", lat, lng)
        store.cachedRoute(key)?.let { (cached, _) -> runCatching { json.decodeFromString(nearSer, cached) }.getOrNull()?.let { return it.take(limit) } }
        for (radius in RADII) {
            val ja = nearby(lat, lng, radius, "ja") ?: return fallback?.near(lat, lng, limit).orEmpty()
            if (ja.isEmpty()) continue
            val en = nearby(lat, lng, radius, "en").orEmpty().associate { it.id to it.name }
            val list = ja.map { p ->
                TransitStationNear(
                    TransitStation(nameJa(p.name), en[p.id]?.let(::nameLatin).orEmpty(), p.lat, p.lng, prefectureOf(p.address)),
                    (distanceKm(lat, lng, p.lat, p.lng) * 1000).roundToInt(),
                )
            }.filter { it.station.nameJa.isNotBlank() }.distinctBy { it.station.nameJa }.sortedBy { it.meters }
            if (list.isEmpty()) continue
            store.putCachedRoute(key, json.encodeToString(nearSer, list), System.currentTimeMillis())
            return list.take(limit)
        }
        return fallback?.near(lat, lng, limit).orEmpty()
    }

    override fun locate(nameJa: String, lat: Double?, lng: Double?): TransitStation? {
        val key = "gstation2:" + nameJa + "|" + (lat?.let { String.format(java.util.Locale.ROOT, "%.1f,%.1f", it, lng) } ?: "")
        store.cachedRoute(key)?.let { (cached, _) -> runCatching { json.decodeFromString(TransitStation.serializer(), cached) }.getOrNull()?.let { return it } }
        val query = TransitService.base(nameJa) + "駅"
        val ja = textSearch(query, lat, lng, "ja") ?: return fallback?.locate(nameJa, lat, lng)
        val hit = ja.firstOrNull { p -> lat == null || lng == null || distanceKm(lat, lng, p.lat, p.lng) <= MAX_LOCATE_KM }
            ?: return fallback?.locate(nameJa, lat, lng)
        val latin = textSearch(query, lat, lng, "en").orEmpty().firstOrNull { it.id == hit.id }?.let { nameLatin(it.name) }.orEmpty()
        val station = TransitStation(nameJa, latin, hit.lat, hit.lng, prefectureOf(hit.address))
        store.putCachedRoute(key, json.encodeToString(TransitStation.serializer(), station), System.currentTimeMillis())
        return station
    }

    /** A stop of a leg near [lat]/[lng] (Japanese search only, kept for good); null beyond [MAX_POINT_KM] or when unknown. */
    override fun point(nameJa: String, lat: Double, lng: Double): TransitStation? {
        val key = "gpoint:" + nameJa + "|" + String.format(java.util.Locale.ROOT, "%.1f,%.1f", lat, lng)
        store.cachedRoute(key)?.let { (cached, _) ->
            if (cached == "{}") return null
            runCatching { json.decodeFromString(TransitStation.serializer(), cached) }.getOrNull()?.let { return it }
        }
        val hits = textSearch(TransitService.base(nameJa) + "駅", lat, lng, "ja") ?: return null // Google failed: try again next time
        val hit = hits.firstOrNull { distanceKm(lat, lng, it.lat, it.lng) <= MAX_POINT_KM }
        val station = hit?.let { TransitStation(nameJa, "", it.lat, it.lng, prefectureOf(it.address)) }
        store.putCachedRoute(key, station?.let { json.encodeToString(TransitStation.serializer(), it) } ?: "{}", System.currentTimeMillis())
        return station
    }

    private class Hit(val id: String, val name: String, val lat: Double, val lng: Double, val address: String)

    /** Stations within [radius] m, nearest first; null when Google failed. */
    private fun nearby(lat: Double, lng: Double, radius: Double, lang: String): List<Hit>? {
        val body = buildJsonObject {
            putJsonArray("includedTypes") { (STATION_TYPES + "transit_station").forEach { add(it) } }
            // Tram stops are often only "transit_station"; bus stops are that too, so they are left out.
            putJsonArray("excludedTypes") { EXCLUDED_TYPES.forEach { add(it) } }
            put("maxResultCount", 10)
            put("rankPreference", "DISTANCE")
            put("languageCode", lang)
            putJsonObject("locationRestriction") {
                putJsonObject("circle") {
                    putJsonObject("center") { put("latitude", lat); put("longitude", lng) }
                    put("radius", radius)
                }
            }
        }.toString()
        return parse(api.post("places:searchNearby", body, FIELD_MASK))
    }

    /** Stations named like [query]; null when Google failed. */
    private fun textSearch(query: String, lat: Double?, lng: Double?, lang: String): List<Hit>? {
        val body = buildJsonObject {
            put("textQuery", query)
            put("languageCode", lang)
            put("regionCode", "JP")
            put("pageSize", 5)
            if (lat != null && lng != null) {
                putJsonObject("locationBias") {
                    putJsonObject("circle") {
                        putJsonObject("center") { put("latitude", lat); put("longitude", lng) }
                        put("radius", 50_000.0)
                    }
                }
            }
        }.toString()
        return parse(api.post("places:searchText", body, FIELD_MASK))
    }

    private fun parse(answer: Pair<Int, String>): List<Hit>? {
        val (status, text) = answer
        if (status !in 200..299) return null
        val places = try {
            json.parseToJsonElement(text).jsonObject["places"] as? JsonArray
        } catch (e: Exception) {
            return null
        } ?: return emptyList()
        return places.mapNotNull { e ->
            val p = e as? JsonObject ?: return@mapNotNull null
            val types = (p["types"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty()
            val rail = types.any { it in STATION_TYPES }
            if (!rail && ("transit_station" !in types || types.any { it in EXCLUDED_TYPES })) return@mapNotNull null
            val id = (p["id"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val name = ((p["displayName"] as? JsonObject)?.get("text") as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val loc = p["location"] as? JsonObject ?: return@mapNotNull null
            val la = (loc["latitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            val ln = (loc["longitude"] as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
            Hit(id, name, la, ln, (p["formattedAddress"] as? JsonPrimitive)?.content.orEmpty())
        }
    }

    companion object {
        val STATION_TYPES = listOf("train_station", "subway_station", "light_rail_station")
        val RADII = listOf(3_000.0, 10_000.0, 30_000.0)
        const val FIELD_MASK = "places.id,places.displayName,places.location,places.types,places.formattedAddress"

        /** Stops that are no rail: never offered as a station. */
        val EXCLUDED_TYPES = listOf("bus_stop", "bus_station", "taxi_stand", "ferry_terminal", "airport")

        private val PREFECTURE = Regex("""(北海道|東京都|大阪府|京都府|[^\s、〒0-9-]{2,3}県)""")

        /** "日本、〒726-0005 広島県府中市…" → "広島県"; null without a Japanese prefecture. */
        fun prefectureOf(address: String): String? = PREFECTURE.find(address)?.value
        const val MAX_LOCATE_KM = 400.0
        const val MAX_POINT_KM = 200.0

        /** "四条駅（京都市営）" → "四条": the name the timetable search understands. */
        fun nameJa(display: String): String =
            display.replace(Regex("""[(（][^)）]*[)）]"""), "").trim().removeSuffix("停留場").removeSuffix("停留所").removeSuffix("電停").removeSuffix("駅").trim()

        /** "Shijō Station (Kyoto Municipal Subway)" → "Shijō". */
        fun nameLatin(display: String): String =
            display.replace(Regex("""\s*\([^)]*\)"""), "").trim().removeSuffix(" Station").removeSuffix(" Sta.").trim()
    }
}
