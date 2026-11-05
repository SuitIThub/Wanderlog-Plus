package de.wanderlogplus.server

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.FlightLive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** No AirLabs key is configured. */
class FlightLiveNotConfigured : RuntimeException("flight live not configured")

/**
 * Live status of the trip's flights from AirLabs (`/api/v9/flight`), asked only around the flight and cached so the
 * monthly quota lasts: nothing earlier than [START_BEFORE] before departure, then every hour, from
 * [CLOSE_BEFORE] on every [CLOSE_TTL], in the air every [AIR_TTL]; a landed or cancelled flight is never asked again.
 * AirLabs answers the current or last flight with that number, so an answer for another day counts as "not yet".
 */
class FlightLiveService(
    private val store: SqliteStore,
    private val http: WeatherHttp?,
    private val apiKey: String?,
    private val now: () -> Instant = Instant::now,
    /** Zone of departure times without one in the data (the trip happens in Japan). */
    private val zone: ZoneId = ZoneId.of("Asia/Tokyo"),
) {
    val configured: Boolean get() = http != null && apiKey != null

    fun forFlight(id: String): FlightLive {
        if (!configured) throw FlightLiveNotConfigured()
        val rec = store.get(EntityType.FLIGHT, id)?.takeIf { !it.isDeleted } ?: throw BadInput()
        val f = runCatching { EntityCodec.decode<Flight>(rec) }.getOrNull() ?: throw BadInput()
        val number = flightIata(f) ?: return FlightLive(reason = "Keine Flugnummer hinterlegt.")
        val date = f.depart.date?.take(10)?.takeIf { it.length == 10 } ?: return FlightLive(reason = "Kein Abflugdatum hinterlegt.")
        val departs = runCatching { LocalDateTime.parse("${date}T${f.depart.time?.takeIf { it.length >= 4 } ?: "12:00"}").atZone(zone).toInstant() }.getOrNull()
            ?: return FlightLive(reason = "Abflugzeit unklar.")
        val t = now()
        if (t.isBefore(departs.minus(START_BEFORE))) {
            return FlightLive(reason = "Live-Status ab ca. 24 Stunden vor Abflug.", fetchedAt = t.toEpochMilli())
        }
        val key = "flightlive|$number|$date"
        val cached = store.cachedRoute(key)?.let { (json, at) -> runCatching { JSON.decodeFromString(FlightLive.serializer(), json) }.getOrNull()?.let { it to at } }
        if (cached != null) {
            val (live, at) = cached
            val final = live.status in FINAL
            val ttl = when {
                final -> null
                live.status == "en-route" || live.status == "active" -> AIR_TTL
                t.isAfter(departs.minus(CLOSE_BEFORE)) -> CLOSE_TTL
                else -> FAR_TTL
            }
            if (ttl == null || Instant.ofEpochMilli(at).plus(ttl).isAfter(t)) return live
        }
        val url = "https://airlabs.co/api/v9/flight?flight_iata=" + URLEncoder.encode(number, "UTF-8") + "&api_key=" + URLEncoder.encode(apiKey!!, "UTF-8")
        val (status, body) = try { http!!.get(url) } catch (e: Exception) { 0 to "" }
        val live = if (status in 200..299) parse(body, date, t.toEpochMilli()) else null
        if (live == null) return cached?.first ?: FlightLive(reason = "AirLabs gerade nicht erreichbar.", fetchedAt = t.toEpochMilli())
        store.putCachedRoute(key, JSON.encodeToString(FlightLive.serializer(), live), t.toEpochMilli())
        return live
    }

    companion object {
        val START_BEFORE: Duration = Duration.ofHours(24)
        val CLOSE_BEFORE: Duration = Duration.ofHours(3)
        val FAR_TTL: Duration = Duration.ofMinutes(60)
        val CLOSE_TTL: Duration = Duration.ofMinutes(10)
        val AIR_TTL: Duration = Duration.ofMinutes(20)
        private val FINAL = setOf("landed", "cancelled", "diverted", "incident")
        private val JSON = Json { ignoreUnknownKeys = true }

        /** "NH7" from airline code and number ("NH 7", "7", "nh007" …); null without a usable number. */
        fun flightIata(f: Flight): String? {
            val raw = f.number?.replace(" ", "")?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
            val m = Regex("""^([A-Z]{2}|[A-Z]\d|\d[A-Z])?0*(\d{1,4}[A-Z]?)$""").find(raw) ?: return null
            val airline = m.groupValues[1].ifBlank { f.airlineCode?.trim()?.uppercase().orEmpty() }
            return if (airline.length == 2) airline + m.groupValues[2] else null
        }

        /** The AirLabs answer as [FlightLive]; "not yet" when it belongs to another departure day than [date]. */
        fun parse(body: String, date: String, fetchedAt: Long): FlightLive? {
            val root = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val r = root["response"] as? JsonObject
                ?: return if (root["error"] != null) FlightLive(reason = "AirLabs kennt diesen Flug gerade nicht.", fetchedAt = fetchedAt) else null
            fun s(k: String) = (r[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
            fun i(k: String) = (r[k] as? JsonPrimitive)?.intOrNull
            val depDay = s("dep_time")?.take(10)
            if (depDay != null && depDay != date) {
                return FlightLive(reason = "Live-Status noch nicht verfügbar (AirLabs meldet den Flug vom ${depDay}).", fetchedAt = fetchedAt)
            }
            return FlightLive(
                available = true,
                status = s("status"),
                depScheduled = s("dep_time"), depEstimated = s("dep_estimated"), depActual = s("dep_actual"),
                depTerminal = s("dep_terminal"), depGate = s("dep_gate"), depDelayMinutes = i("dep_delayed"),
                arrScheduled = s("arr_time"), arrEstimated = s("arr_estimated"), arrActual = s("arr_actual"),
                arrTerminal = s("arr_terminal"), arrGate = s("arr_gate"), arrBaggage = s("arr_baggage"), arrDelayMinutes = i("arr_delayed"),
                updatedAt = (r["updated"] as? JsonPrimitive)?.longOrNull?.let { it * 1000 },
                fetchedAt = fetchedAt,
            )
        }
    }
}
