package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitRequest
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
import de.wanderlogplus.shared.sync.TransitStop
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

/**
 * Connections outside Japan (Germany: Deutsche Bahn, üstra and every other operator in the national timetable data)
 * from Transitous (api.transitous.org, MOTIS, free, community-run). Answers are cached for [TTL]; timetables are only
 * published some weeks ahead, so a far date without results says so ([TransitUnavailable] "transit_no_timetable").
 */
class TransitousSource(
    private val store: SqliteStore,
    private val http: WeatherHttp,
    private val now: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.of("Europe/Berlin"),
) {
    fun search(req: TransitRequest): TransitResult {
        val from = place(req.fromStation, req.from?.let { it.lat to it.lng }) ?: throw TransitUnavailable("no_station")
        val to = place(req.toStation, req.to?.let { it.lat to it.lng }) ?: throw TransitUnavailable("no_station")
        val local = runCatching { LocalDateTime.parse(req.datetime) }.getOrNull() ?: throw BadInput()
        val utc = local.atZone(zone).toInstant()
        val key = listOf("transitous", from, to, req.datetime, req.searchType).joinToString("|")
        if (!req.fresh) {
            store.cachedRoute(key)?.let { (json, at) ->
                if (Instant.ofEpochMilli(at).plus(TTL).isAfter(now())) {
                    if (json == "{}") throw NoRoute()
                    return JSON.decodeFromString(TransitResult.serializer(), json)
                }
            }
        }
        val url = "$BASE/api/v1/plan?fromPlace=${enc(from)}&toPlace=${enc(to)}&time=${enc(utc.toString())}" +
            "&arriveBy=${req.searchType == "arrive"}&numItineraries=3&language=de"
        val (status, body) = try { http.get(url) } catch (e: Exception) { 0 to "" }
        if (status !in 200..299) throw TransitUnavailable()
        val root = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: throw TransitUnavailable()
        val routes = (root["itineraries"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.let(::route) }
        if (routes.isEmpty()) {
            if (local.toLocalDate().isAfter(LocalDate.ofInstant(now(), zone).plusDays(TIMETABLE_DAYS))) throw TransitUnavailable("transit_no_timetable")
            store.putCachedRoute(key, "{}", now().toEpochMilli())
            throw NoRoute()
        }
        val fastest = routes.minOf { it.durationMinutes }
        val fewest = routes.minOf { it.transfers }
        val marked = routes.map { it.copy(fastest = it.durationMinutes == fastest, fewestTransfers = it.transfers == fewest) }
        val firstRide = marked.first().legs.firstOrNull { !it.walk }
        val lastRide = marked.first().legs.lastOrNull { !it.walk }
        val result = TransitResult(
            fromStation = firstRide?.from ?: TransitStation(req.fromStation.orEmpty()),
            toStation = lastRide?.to ?: TransitStation(req.toStation.orEmpty()),
            datetime = req.datetime,
            searchType = req.searchType,
            routes = marked,
            fetchedAt = now().toEpochMilli(),
            walkToStationMeters = walks(root, first = true),
            walkFromStationMeters = walks(root, first = false),
        )
        store.putCachedRoute(key, JSON.encodeToString(TransitResult.serializer(), result), now().toEpochMilli())
        return result
    }

    /** "lat,lng" of a point, else of a stop found by [name] (near [near] when known); null when nothing is found. */
    private fun place(name: String?, near: Pair<Double, Double>?): String? {
        if (!name.isNullOrBlank()) {
            val url = "$BASE/api/v1/geocode?text=${enc(name.trim())}&language=de" +
                (near?.let { "&place=${it.first},${it.second}" } ?: "")
            val (status, body) = try { http.get(url) } catch (e: Exception) { 0 to "" }
            if (status in 200..299) {
                val hits = runCatching { JSON.parseToJsonElement(body) as? JsonArray }.getOrNull().orEmpty().mapNotNull { it as? JsonObject }
                val hit = hits.firstOrNull { (it["type"] as? JsonPrimitive)?.contentOrNull == "STOP" } ?: hits.firstOrNull()
                val lat = (hit?.get("lat") as? JsonPrimitive)?.doubleOrNull
                val lon = (hit?.get("lon") as? JsonPrimitive)?.doubleOrNull
                if (lat != null && lon != null) return fmt(lat, lon)
            }
        }
        return near?.let { fmt(it.first, it.second) }
    }

    private fun route(it: JsonObject): TransitRoute? {
        val legs = (it["legs"] as? JsonArray).orEmpty().mapNotNull { l -> l as? JsonObject }
        val rides = legs.filter { s(it, "mode") != "WALK" }
        if (rides.isEmpty()) return null
        val mapped = legs.mapIndexedNotNull { i, l ->
            val walk = s(l, "mode") == "WALK"
            // Walks to the first and from the last station belong to the places, not the connection.
            if (walk && (i == 0 || i == legs.lastIndex)) return@mapIndexedNotNull null
            val from = l["from"] as? JsonObject ?: return@mapIndexedNotNull null
            val to = l["to"] as? JsonObject ?: return@mapIndexedNotNull null
            val label = if (walk) "Fußweg" else lineLabel(l)
            TransitLeg(
                lineJa = label, line = label,
                headsignJa = s(l, "headsign").orEmpty(), headsign = s(l, "headsign").orEmpty(),
                walk = walk,
                from = station(from), to = station(to),
                departure = hm(s(l, "startTime")), arrival = hm(s(l, "endTime")),
                departurePlatform = s(from, "track"), arrivalPlatform = s(to, "track"),
                stops = (l["intermediateStops"] as? JsonArray).orEmpty().mapNotNull { st ->
                    (st as? JsonObject)?.let { o -> TransitStop(s(o, "name").orEmpty(), s(o, "name").orEmpty(), hm(s(o, "departure") ?: s(o, "arrival"))) }
                },
            )
        }
        val start = s(rides.first(), "startTime") ?: return null
        val end = s(rides.last(), "endTime") ?: return null
        val minutes = runCatching { Duration.between(OffsetDateTime.parse(start), OffsetDateTime.parse(end)).toMinutes().toInt() }.getOrDefault(0)
        val riding = rides.sumOf { (it["duration"] as? JsonPrimitive)?.intOrNull ?: 0 } / 60
        return TransitRoute(
            departure = hm(start), arrival = hm(end), durationMinutes = minutes, ridingMinutes = riding,
            transfers = (it["transfers"] as? JsonPrimitive)?.intOrNull ?: (rides.size - 1),
            legs = mapped,
            date = runCatching { OffsetDateTime.parse(start).atZoneSameInstant(zone).toLocalDate().toString() }.getOrDefault(""),
        )
    }

    private fun walks(root: JsonObject, first: Boolean): Int? {
        val it = (root["itineraries"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val legs = (it["legs"] as? JsonArray).orEmpty().mapNotNull { l -> l as? JsonObject }
        val l = (if (first) legs.firstOrNull() else legs.lastOrNull())?.takeIf { s(it, "mode") == "WALK" } ?: return null
        return (l["distance"] as? JsonPrimitive)?.doubleOrNull?.roundToInt()
    }

    private fun station(o: JsonObject) = TransitStation(
        s(o, "name").orEmpty(), s(o, "name").orEmpty(),
        (o["lat"] as? JsonPrimitive)?.doubleOrNull, (o["lon"] as? JsonPrimitive)?.doubleOrNull,
    )

    /** "HH:mm" local time of an ISO instant. */
    private fun hm(iso: String?): String = iso?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).format(HM) }.getOrNull() }.orEmpty()

    companion object {
        const val BASE = "https://api.transitous.org"
        val TTL: Duration = Duration.ofDays(2)

        /** Days ahead for which an empty answer means "no timetable yet" rather than "no connection". */
        const val TIMETABLE_DAYS = 45L
        private val HM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        private fun s(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
        private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")
        private fun fmt(lat: Double, lng: Double) = String.format(java.util.Locale.ROOT, "%.6f,%.6f", lat, lng)

        /** "ICE 575", "RE 2", "Stadtbahn 8", "Bus X61" from mode and line. */
        fun lineLabel(l: JsonObject): String {
            val short = s(l, "routeShortName") ?: s(l, "displayName") ?: ""
            val prefix = when (s(l, "mode")) {
                "SUBWAY", "TRAM" -> "Stadtbahn"
                "BUS", "COACH" -> "Bus"
                "FERRY" -> "Fähre"
                "SUBURBAN" -> if (short.startsWith("S")) "" else "S-Bahn"
                else -> ""
            }
            return listOf(prefix, short).filter { it.isNotBlank() }.joinToString(" ").ifBlank { s(l, "agencyName") ?: "Fahrt" }
        }

        /** Whether a request is for Japan (points there, or Japanese station names). */
        fun isJapan(req: TransitRequest): Boolean {
            fun inJapan(lat: Double, lng: Double) = lat in 24.0..46.0 && lng in 122.0..154.0
            val points = listOfNotNull(req.from, req.to)
            if (points.isNotEmpty()) return points.all { inJapan(it.lat, it.lng) }
            val names = listOfNotNull(req.fromStation, req.toStation)
            return names.any { n -> n.any { Character.UnicodeScript.of(it.code) in setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA) } }
        }
    }
}
