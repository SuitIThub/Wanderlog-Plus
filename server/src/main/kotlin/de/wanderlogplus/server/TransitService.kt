package de.wanderlogplus.server

import de.wanderlogplus.server.ai.GeocoderUnavailable
import de.wanderlogplus.server.ai.PhotonGeocoder
import de.wanderlogplus.server.ai.distanceKm
import de.wanderlogplus.shared.sync.TransitFee
import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitNames
import de.wanderlogplus.shared.sync.TransitPoint
import de.wanderlogplus.shared.sync.TransitRequest
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
import de.wanderlogplus.shared.sync.TransitStationNear
import de.wanderlogplus.shared.sync.TransitStop
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/** No Apify token is configured (`WLP_APIFY_TOKEN`). */
class TransitNotConfigured : RuntimeException("transit not configured")

/** The timetable search could not be reached or refused the request; [code] is a fixed error code. */
class TransitUnavailable(val code: String = "transit_unavailable") : RuntimeException(code)

/** Runs the timetable actor with an input; returns HTTP status and body (the dataset items). Blocking. Replaced in tests. */
fun interface TransitSource {
    fun run(input: JsonObject): Pair<Int, String>
}

/**
 * Apify actor "Japan Train Route & Fare Finder" (Yahoo! 乗換案内 behind it, reachable from outside the EU only):
 * `run-sync-get-dataset-items` waits for the run and returns its items. Costs a few tenths of a cent per search.
 */
class ApifyTransitSource(private val token: String, private val actor: String = DEFAULT_ACTOR) : TransitSource {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build()

    override fun run(input: JsonObject): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create("https://api.apify.com/v2/acts/$actor/run-sync-get-dataset-items?timeout=120"))
            .timeout(Duration.ofSeconds(150))
            .header("Authorization", "Bearer $token")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(input.toString()))
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            throw TransitUnavailable()
        }
        return response.statusCode() to response.body()
    }

    companion object {
        const val DEFAULT_ACTOR = "FjxdTwrnBwvjDSBe0"
    }
}

/** Railway stations near a point and the position of a station by name. */
interface StationFinder {
    /** Stations within a few km, nearest first. */
    fun near(lat: Double, lng: Double, limit: Int = 6): List<TransitStationNear>

    /** A station by its Japanese name, near ([lat], [lng]) when given; null when not found. */
    fun locate(nameJa: String, lat: Double?, lng: Double?): TransitStation?
}

/** Stations from OpenStreetMap via Photon (Japanese names from `name`, Latin ones from the English request). */
class PhotonStationFinder(private val photon: PhotonGeocoder) : StationFinder {
    private val located = ConcurrentHashMap<String, TransitStation>()
    private val nearby = ConcurrentHashMap<String, List<TransitStationNear>>()

    override fun near(lat: Double, lng: Double, limit: Int): List<TransitStationNear> {
        val key = String.format(java.util.Locale.ROOT, "%.4f,%.4f", lat, lng)
        nearby[key]?.let { return it.take(limit) }
        for (radius in listOf(3, 10, 30)) {
            // Only stations and halts: "railway" alone also returns platforms, entrances and switches, which fill the
            // nearest results and leave no station after filtering.
            val q = "reverse?lat=$lat&lon=$lng&limit=12&radius=$radius&osm_tag=railway:station&osm_tag=railway:halt&osm_tag=railway:tram_stop"
            val ja = stations(photon.features(q))
            if (ja.isEmpty()) continue
            val en = stations(photon.features("$q&lang=en")).associateBy { it.first }
            val list = ja.map { (id, s) ->
                val station = s.copy(name = en[id]?.second?.nameJa?.takeIf { it != s.nameJa } ?: s.name)
                TransitStationNear(station, (distanceKm(lat, lng, station.lat!!, station.lng!!) * 1000).roundToInt())
            }.distinctBy { it.station.nameJa }.sortedBy { it.meters }
            nearby[key] = list
            return list.take(limit)
        }
        return emptyList()
    }

    override fun locate(nameJa: String, lat: Double?, lng: Double?): TransitStation? {
        val key = nameJa + "|" + (lat?.let { String.format(java.util.Locale.ROOT, "%.1f,%.1f", it, lng) } ?: "")
        located[key]?.let { return it }
        val bias = if (lat != null && lng != null) "&lat=$lat&lon=$lng" else "&lat=36.2&lon=138.25"
        val q = URLEncoder.encode(TransitService.base(nameJa) + "駅", Charsets.UTF_8)
        val hits = stations(photon.features("api/?q=$q&limit=3&osm_tag=railway:station&osm_tag=railway:halt&lang=en$bias"))
        val hit = hits.map { it.second }.firstOrNull { s ->
            lat == null || lng == null || distanceKm(lat, lng, s.lat!!, s.lng!!) <= MAX_LOCATE_KM
        } ?: return null
        val station = TransitStation(nameJa, hit.nameJa.takeIf { it != nameJa }.orEmpty(), hit.lat, hit.lng)
        located[key] = station
        return station
    }

    /** (osm id, station) of the station/halt features; the name sits in [TransitStation.nameJa] (whatever language came back). */
    private fun stations(features: JsonArray?): List<Pair<String, TransitStation>> = features.orEmpty().mapNotNull { f ->
        val o = f as? JsonObject ?: return@mapNotNull null
        val props = o["properties"] as? JsonObject ?: return@mapNotNull null
        fun p(k: String) = (props[k] as? JsonPrimitive)?.content
        if (p("osm_key") != "railway" || p("osm_value") !in setOf("station", "halt", "tram_stop")) return@mapNotNull null
        val name = p("name")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        val c = (o["geometry"] as? JsonObject)?.get("coordinates") as? JsonArray ?: return@mapNotNull null
        val lng = (c.getOrNull(0) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        val lat = (c.getOrNull(1) as? JsonPrimitive)?.doubleOrNull ?: return@mapNotNull null
        (p("osm_type").orEmpty() + p("osm_id").orEmpty()) to TransitStation(name, "", lat, lng)
    }

    private companion object {
        /** A station of the route farther than this from the previous one is a namesake elsewhere. */
        const val MAX_LOCATE_KM = 400.0
    }
}

/**
 * Public-transport connections in Japan: finds the stations near the two places (unless given), asks the timetable
 * actor, translates names, locates the stations of each leg for the map, and caches the answer for [TTL] per
 * stations, time and options (also "no connection"), so both devices pay for a search once.
 */
class TransitService(
    private val store: SqliteStore,
    private val source: TransitSource?,
    private val stations: StationFinder?,
    /** Connections outside Japan (Germany: DB, üstra …); null: Japan only. */
    private val elsewhere: TransitousSource? = null,
    private val now: () -> Instant = Instant::now,
) {
    val configured: Boolean get() = source != null && stations != null

    fun stationsNear(lat: Double, lng: Double): List<TransitStationNear> {
        val f = stations ?: throw TransitNotConfigured()
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) throw BadInput()
        return try { f.near(lat, lng) } catch (e: GeocoderUnavailable) { throw TransitUnavailable("stations_unavailable") }
    }

    fun search(req: TransitRequest): TransitResult {
        if (elsewhere != null && !TransitousSource.isJapan(req)) {
            validate(req)
            return elsewhere.search(req)
        }
        val src = source ?: throw TransitNotConfigured()
        val finder = stations ?: throw TransitNotConfigured()
        validate(req)
        val (fromStation, fromMeters) = station(req.fromStation, req.from, finder)
        val (toStation, toMeters) = station(req.toStation, req.to, finder)
        if (plain(fromStation.nameJa) == plain(toStation.nameJa)) throw NoRoute() // same station: a walk
        val key = cacheKey(req, fromStation.nameJa, toStation.nameJa)
        // "fresh" still takes an answer of the last minutes: a search the app lost to a dropped connection is not paid twice.
        val maxAge = if (req.fresh) FRESH_WINDOW else TTL
        store.cachedRoute(key)?.let { (json, createdAt) ->
            if (Instant.ofEpochMilli(createdAt).plus(maxAge).isAfter(now())) {
                if (json == "{}") throw NoRoute()
                return JSON.decodeFromString(TransitResult.serializer(), json)
            }
        }
        // The same search asked again while it runs waits for that run instead of starting another one.
        val mine = java.util.concurrent.CompletableFuture<TransitResult>()
        val running = inFlight.putIfAbsent(key, mine)
        if (running != null) {
            return try {
                running.get()
            } catch (e: java.util.concurrent.ExecutionException) {
                throw e.cause ?: e
            }
        }
        return try {
            fetch(req, src, finder, fromStation, toStation, fromMeters, toMeters, key).also { mine.complete(it) }
        } catch (e: Throwable) {
            mine.completeExceptionally(e)
            throw e
        } finally {
            inFlight.remove(key, mine)
        }
    }

    private val inFlight = java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<TransitResult>>()

    private fun fetch(
        req: TransitRequest,
        src: TransitSource,
        finder: StationFinder,
        fromStation: TransitStation,
        toStation: TransitStation,
        fromMeters: Int?,
        toMeters: Int?,
        key: String,
    ): TransitResult {
        var routes = ask(src, req, fromStation.nameJa, toStation.nameJa)
        if (routes.isNotEmpty()) {
            // A namesake in another prefecture ("府中(東京都)" for Hiroshima's 府中): ask again with Yahoo's own
            // disambiguation "府中(広島県)"; keep the first answer when that finds nothing.
            val fixFrom = otherPrefecture(routes.first().str("resolved_origin_ja"), fromStation)
            val fixTo = otherPrefecture(routes.first().str("resolved_destination_ja"), toStation)
            if (fixFrom || fixTo) {
                val again = ask(
                    src, req,
                    if (fixFrom) "${base(fromStation.nameJa)}(${fromStation.prefecture})" else fromStation.nameJa,
                    if (fixTo) "${base(toStation.nameJa)}(${toStation.prefecture})" else toStation.nameJa,
                )
                if (again.isNotEmpty()) routes = again
            }
        }
        if (routes.isEmpty()) {
            store.putCachedRoute(key, "{}", now().toEpochMilli())
            throw NoRoute()
        }
        val first = routes.first()
        val resolvedFrom = first.str("resolved_origin_ja") ?: fromStation.nameJa
        val resolvedTo = first.str("resolved_destination_ja") ?: toStation.nameJa
        val warning = listOfNotNull(
            "„$resolvedFrom“ statt „${fromStation.nameJa}“".takeIf { mismatch(resolvedFrom, fromStation, finder) },
            "„$resolvedTo“ statt „${toStation.nameJa}“".takeIf { mismatch(resolvedTo, toStation, finder) },
        ).takeIf { it.isNotEmpty() }?.joinToString(", ", prefix = "Die Fahrplanauskunft hat ", postfix = " gewählt.")
        val located = locateLegStations(routes, fromStation, finder)
        val result = TransitResult(
            fromStation = located[plain(fromStation.nameJa)] ?: fromStation,
            toStation = located[plain(toStation.nameJa)] ?: toStation,
            datetime = req.datetime,
            searchType = req.searchType,
            routes = routes.map { parseRoute(it, located, req.datetime.take(10)) },
            fetchedAt = now().toEpochMilli(),
            walkToStationMeters = fromMeters,
            walkFromStationMeters = toMeters,
            stationWarning = warning,
        )
        store.putCachedRoute(key, JSON.encodeToString(TransitResult.serializer(), result), now().toEpochMilli())
        return result
    }

    /** One timetable request; the connections found (empty: none). */
    private fun ask(src: TransitSource, req: TransitRequest, from: String, to: String): List<JsonObject> {
        val (status, body) = src.run(actorInput(req, from, to))
        when {
            status == 401 || status == 403 -> throw TransitUnavailable("transit_key_rejected")
            status == 402 -> throw TransitUnavailable("transit_no_credit")
            status !in 200..299 -> throw TransitUnavailable()
        }
        val items = try {
            JSON.parseToJsonElement(body) as? JsonArray
        } catch (e: Exception) {
            null
        } ?: throw TransitUnavailable()
        return items.mapNotNull { it as? JsonObject }.filter { it.containsKey("rank") }
    }

    /** True when the timetable took a station of another prefecture than [wanted]'s ("府中(東京都)" for 広島県). */
    private fun otherPrefecture(resolved: String?, wanted: TransitStation): Boolean {
        val pref = resolved?.let { prefectureIn(it) } ?: return false
        return wanted.prefecture != null && pref != wanted.prefecture
    }

    /** The station to use: the given Japanese name (located near the place) or the nearest one; with the walk in metres. */
    private fun station(name: String?, at: TransitPoint?, finder: StationFinder): Pair<TransitStation, Int?> = try {
        val given = name?.trim()?.takeIf { it.isNotEmpty() }
        when {
            given != null -> {
                val s = finder.locate(given, at?.lat, at?.lng) ?: TransitStation(given)
                s to at?.let { p -> s.lat?.let { (distanceKm(p.lat, p.lng, it, s.lng!!) * 1000).roundToInt() } }
            }
            at != null -> finder.near(at.lat, at.lng, 1).firstOrNull()?.let { it.station to it.meters } ?: throw NoStation()
            else -> throw BadInput()
        }
    } catch (e: GeocoderUnavailable) {
        throw TransitUnavailable("stations_unavailable")
    }

    /**
     * True when the timetable matched another station than [wanted]: a different name, or the same name with a
     * disambiguation ("府中(広島県)") that lies more than [SAME_STATION_KM] away. "稲荷(京都府)" for 稲荷 is fine.
     */
    private fun mismatch(resolved: String, wanted: TransitStation, finder: StationFinder): Boolean {
        if (plain(resolved) == plain(wanted.nameJa)) return false
        if (base(resolved) != base(wanted.nameJa)) return true
        // The disambiguation names another prefecture: a namesake elsewhere, whatever lies near the place.
        if (otherPrefecture(resolved, wanted)) return true
        val lat = wanted.lat ?: return false
        val lng = wanted.lng ?: return false
        val found = try { finder.locate(resolved, lat, lng) } catch (e: GeocoderUnavailable) { return false } ?: return true
        return distanceKm(lat, lng, found.lat ?: return false, found.lng ?: return false) > SAME_STATION_KM
    }

    /** Positions of every station where a leg starts or ends (for the map), each looked up near the previous one. */
    private fun locateLegStations(routes: List<JsonObject>, start: TransitStation, finder: StationFinder): Map<String, TransitStation> {
        val out = HashMap<String, TransitStation>()
        if (start.lat != null) out[plain(start.nameJa)] = start
        for (r in routes) {
            var lat = start.lat
            var lng = start.lng
            for (leg in (r["legs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }) {
                for (k in listOf("from_station_ja", "to_station_ja")) {
                    val name = leg.str(k) ?: continue
                    val s = out[plain(name)] ?: try {
                        finder.locate(name, lat, lng)?.also { out[plain(name)] = it }
                    } catch (e: GeocoderUnavailable) {
                        null
                    }
                    if (s?.lat != null) { lat = s.lat; lng = s.lng }
                }
            }
        }
        return out
    }

    private fun parseRoute(o: JsonObject, located: Map<String, TransitStation>, date: String): TransitRoute {
        fun station(nameJa: String, romaji: String?): TransitStation {
            val s = located[plain(nameJa)] ?: TransitStation(nameJa)
            // OpenStreetMap's English name ("Kiyomizu-Gojō") reads better than the plain romaji ("Kiyomizugojo").
            return s.copy(name = s.name.ifBlank { romaji?.takeIf { it.isNotBlank() }.orEmpty() })
        }
        val legs = (o["legs"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { l ->
            val lineJa = l.str("line_ja").orEmpty()
            val walk = (l["is_walk"] as? JsonPrimitive)?.booleanOrNull == true
            TransitLeg(
                lineJa = lineJa,
                line = if (walk) "zu Fuß" else TransitNames.line(lineJa),
                headsignJa = l.str("destination_ja").orEmpty(),
                headsign = TransitNames.headsign(l.str("destination_ja").orEmpty()),
                walk = walk,
                from = station(l.str("from_station_ja").orEmpty(), l.str("from_station_romaji")),
                to = station(l.str("to_station_ja").orEmpty(), l.str("to_station_romaji")),
                departure = l.str("departure_time").orEmpty(),
                arrival = l.str("arrival_time").orEmpty(),
                departurePlatform = l.str("departure_platform"),
                arrivalPlatform = l.str("arrival_platform"),
                stops = (l["stops"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { s ->
                    TransitStop(s.str("name_ja").orEmpty(), s.str("name_romaji").orEmpty(), s.str("time").orEmpty())
                },
            )
        }
        return TransitRoute(
            departure = o.str("departure_time").orEmpty(),
            arrival = o.str("arrival_time").orEmpty(),
            durationMinutes = o.int("duration_minutes") ?: 0,
            ridingMinutes = o.int("riding_minutes") ?: 0,
            transfers = o.int("transfers") ?: 0,
            fareYen = o.int("total_fare_jpy") ?: 0,
            baseFareYen = o.int("base_fare_jpy") ?: 0,
            expressFareYen = o.int("express_fare_jpy") ?: 0,
            fees = (o["seat_fees"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.map { f ->
                val label = f.str("label_ja").orEmpty()
                TransitFee(label, TransitNames.fee(label), f.int("fare_jpy") ?: 0)
            },
            distanceKm = (o["distance_km"] as? JsonPrimitive)?.doubleOrNull,
            fastest = o.bool("is_fastest"),
            cheapest = o.bool("is_cheapest"),
            fewestTransfers = o.bool("is_fewest_transfers"),
            legs = legs,
            date = date,
        )
    }

    private fun validate(req: TransitRequest) {
        val ok = DATETIME.matches(req.datetime) &&
            req.searchType in setOf("depart", "arrive") &&
            req.preference in setOf("fastest", "cheapest", "fewest_transfers") &&
            listOfNotNull(req.from, req.to).all { it.lat in -90.0..90.0 && it.lng in -180.0..180.0 } &&
            listOfNotNull(req.fromStation, req.toStation).all { it.length <= 60 }
        if (!ok) throw BadInput()
    }

    companion object {
        val TTL: Duration = Duration.ofDays(7)

        /** How old an answer may be for a "fresh" search (a retry after a dropped connection). */
        val FRESH_WINDOW: Duration = Duration.ofMinutes(10)
        const val SAME_STATION_KM = 5.0
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val DATETIME = Regex("""\d{4}-\d{2}-\d{2}T\d{2}:\d{2}""")

        /** Station names compare without the 駅 suffix and spaces. */
        /** "府中(広島県)" → "広島県"; null without a prefecture in brackets. */
        fun prefectureIn(nameJa: String): String? =
            Regex("""[(（]([^)）]*(?:都|道|府|県))[)）]\s*$""").find(nameJa.trim())?.groupValues?.get(1)

        fun plain(nameJa: String) = nameJa.trim().removeSuffix("駅").replace(" ", "").replace("　", "")

        /** The name without a disambiguation in brackets: "六地蔵(京都市営)" → "六地蔵", "稲荷（京都府）" → "稲荷". */
        fun base(nameJa: String) = plain(nameJa).replace(Regex("""[(（][^)）]*[)）]"""), "").removeSuffix("駅")

        fun cacheKey(req: TransitRequest, from: String, to: String): String = listOf(
            "transit", plain(from), plain(to), req.datetime, req.searchType, req.preference,
            req.useShinkansen, req.useLimitedExpress, req.useHighwayBus, req.useLocalBus,
        ).joinToString("|")

        fun actorInput(req: TransitRequest, from: String, to: String): JsonObject = buildJsonObject {
            put("origin", plain(from))
            put("destination", plain(to))
            put("datetime", req.datetime)
            put("searchType", req.searchType)
            put("preference", req.preference)
            put("maxRoutes", 3)
            put("useShinkansen", req.useShinkansen)
            put("useLimitedExpress", req.useLimitedExpress)
            put("useHighwayBus", req.useHighwayBus)
            put("useLocalBus", req.useLocalBus)
            put("includeRomaji", true)
        }

        private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it.isString || it.content != "null" }?.content?.takeIf { it.isNotBlank() && it != "null" }
        private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull ?: (this[k] as? JsonPrimitive)?.doubleOrNull?.roundToInt()
        private fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull == true
    }
}

/** No railway station near one of the places. */
class NoStation : RuntimeException("no station")
