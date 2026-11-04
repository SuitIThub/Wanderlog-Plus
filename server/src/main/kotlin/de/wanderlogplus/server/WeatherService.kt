package de.wanderlogplus.server

import de.wanderlogplus.server.ai.ChatTrip
import de.wanderlogplus.shared.sync.DayWeather
import de.wanderlogplus.shared.sync.HourWeather
import de.wanderlogplus.shared.sync.TripWeather
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import org.slf4j.LoggerFactory
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.MonthDay
import java.util.Locale
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.roundToInt

/** Blocking HTTP GET; returns status code and body. */
fun interface WeatherHttp {
    fun get(url: String): Pair<Int, String>
}

/** Plain GET against the free Open-Meteo API (no key). */
class OpenMeteoHttp : WeatherHttp {
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun get(url: String): Pair<Int, String> {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(30))
            .header("User-Agent", "WanderlogPlus/1.0")
            .GET()
            .build()
        val response = try {
            http.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: IOException) {
            return 0 to ""
        }
        return response.statusCode() to response.body()
    }
}

/**
 * The weather for the trip days, at each night's stay: the forecast of the JMA models via Open-Meteo (5 km MSM for
 * the next days, then GSM; other models fill the days after), and for days beyond the forecast the average of the
 * last [CLIMATE_YEARS] years around that date. Forecasts are cached for [FORECAST_TTL] (a stale one is used when
 * Open-Meteo cannot be reached), climate averages for good.
 */
class WeatherService(
    private val store: SqliteStore,
    private val http: WeatherHttp?,
    private val now: () -> Instant = Instant::now,
) {
    private val log = LoggerFactory.getLogger(WeatherService::class.java)
    val configured: Boolean get() = http != null

    /** Weather of every trip day from [today] on that has a stay with coordinates. */
    fun forTrip(t: ChatTrip, today: String): TripWeather {
        val h = http ?: return TripWeather(fetchedAt = now().toEpochMilli())
        val start = runCatching { LocalDate.parse(today) }.getOrNull() ?: return TripWeather()
        val lastForecast = start.plusDays(FORECAST_DAYS - 1L)
        data class Want(val date: LocalDate, val place: String, val lat: Double, val lng: Double)
        val wants = t.queries?.days().orEmpty().mapNotNull { d ->
            val date = runCatching { LocalDate.parse(d.date) }.getOrNull() ?: return@mapNotNull null
            if (date < start) return@mapNotNull null
            val stay = t.nightStay(d.date) ?: t.morningStay(d.date) ?: return@mapNotNull null
            val p = t.point(stay.placeId) ?: return@mapNotNull null
            Want(date, t.stayName(stay), p.lat, p.lng)
        }
        var oldest = now().toEpochMilli()
        val out = mutableListOf<DayWeather>()
        wants.groupBy { loc(it.lat, it.lng) }.forEach { (key, list) ->
            val lat = list.first().lat
            val lng = list.first().lng
            val forecast = if (list.any { it.date <= lastForecast }) forecast(h, key, lat, lng)?.also { oldest = min(oldest, it.second) }?.first else null
            val climate by lazy { climate(h, key, lat, lng) }
            list.forEach { w ->
                val f = forecast?.get(w.date.toString())?.takeIf { w.date <= lastForecast && (it.tempMax != null || it.code != null) }
                val d = f ?: climate?.get(monthDay(w.date))?.let { c ->
                    DayWeather(w.date.toString(), source = DayWeather.SOURCE_CLIMATE, tempMin = c.tMin, tempMax = c.tMax, rainMm = c.mm, rainyDays = c.rainy)
                }
                if (d != null) out += d.copy(place = w.place, lat = w.lat, lng = w.lng)
            }
        }
        return TripWeather(out.sortedBy { it.date }, oldest)
    }

    /** Forecast by date plus when it was fetched; null when there is none (not even a stale one). */
    private fun forecast(h: WeatherHttp, key: String, lat: Double, lng: Double): Pair<Map<String, DayWeather>, Long>? {
        val cacheKey = "weather|f|$key"
        val cached = store.cachedRoute(cacheKey)
        if (cached != null && Instant.ofEpochMilli(cached.second).plus(FORECAST_TTL).isAfter(now())) {
            parseForecast(cached.first)?.let { return it to cached.second }
        }
        val body = fetch(h, forecastUrl(lat, lng, withModels = true)) ?: fetch(h, forecastUrl(lat, lng, withModels = false))
        val parsed = body?.let { parseForecast(it) }
        if (body != null && parsed != null) {
            val at = now().toEpochMilli()
            store.putCachedRoute(cacheKey, body, at)
            return parsed to at
        }
        return cached?.let { c -> parseForecast(c.first)?.let { it to c.second } }
    }

    private fun climate(h: WeatherHttp, key: String, lat: Double, lng: Double): Map<String, Normal>? {
        val cacheKey = "weather|c|$key"
        store.cachedRoute(cacheKey)?.let { (json, _) ->
            return runCatching { JSON.decodeFromString(NORMALS, json) }.getOrNull()?.mapValues { (_, v) -> Normal.decode(v) }
        }
        val year = LocalDate.ofInstant(now(), java.time.ZoneOffset.UTC).year
        val body = fetch(h, archiveUrl(lat, lng, year - CLIMATE_YEARS, year - 1)) ?: return null
        val normals = parseNormals(body) ?: return null
        store.putCachedRoute(cacheKey, JSON.encodeToString(NORMALS, normals.mapValues { (_, v) -> v.encode() }), now().toEpochMilli())
        return normals
    }

    private fun fetch(h: WeatherHttp, url: String): String? {
        val (status, body) = try {
            h.get(url)
        } catch (e: Exception) {
            0 to ""
        }
        if (status !in 200..299) {
            log.warn("Open-Meteo answered {}", status)
            return null
        }
        return body
    }

    /** Climate average of one calendar day: temperatures, mean rain (mm) and the share of rainy days (percent). */
    data class Normal(val tMin: Double?, val tMax: Double?, val mm: Double?, val rainy: Int?) {
        fun encode(): String = listOf(tMin, tMax, mm, rainy?.toDouble()).joinToString(";") { it?.toString() ?: "" }

        companion object {
            fun decode(s: String): Normal {
                val v = s.split(';').map { it.toDoubleOrNull() }
                return Normal(v.getOrNull(0), v.getOrNull(1), v.getOrNull(2), v.getOrNull(3)?.roundToInt())
            }
        }
    }

    companion object {
        const val FORECAST_DAYS = 16
        const val CLIMATE_YEARS = 10

        /** Days on both sides of a date that count for its climate average. */
        const val CLIMATE_WINDOW = 3
        val FORECAST_TTL: Duration = Duration.ofHours(3)
        private const val MODELS = "jma_seamless,best_match"
        private val MODEL_ORDER = listOf("jma_seamless", "best_match")
        private val JSON = Json { ignoreUnknownKeys = true }
        private val NORMALS = MapSerializer(String.serializer(), String.serializer())

        /** Coordinates rounded to 2 decimals (about 1 km): stays close together share one request. */
        fun loc(lat: Double, lng: Double): String = String.format(Locale.ROOT, "%.2f,%.2f", lat + 0.0, lng + 0.0)

        fun monthDay(d: LocalDate): String = "%02d-%02d".format(d.monthValue, d.dayOfMonth)

        fun forecastUrl(lat: Double, lng: Double, withModels: Boolean): String = buildString {
            append("https://api.open-meteo.com/v1/forecast?latitude=").append(fmt(lat)).append("&longitude=").append(fmt(lng))
            append("&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_sum,precipitation_probability_max,wind_speed_10m_max")
            append("&hourly=weather_code,temperature_2m,precipitation_probability,precipitation")
            if (withModels) append("&models=").append(MODELS)
            append("&timezone=Asia%2FTokyo&forecast_days=").append(FORECAST_DAYS)
        }

        fun archiveUrl(lat: Double, lng: Double, fromYear: Int, toYear: Int): String =
            "https://archive-api.open-meteo.com/v1/archive?latitude=${fmt(lat)}&longitude=${fmt(lng)}" +
                "&start_date=$fromYear-01-01&end_date=$toYear-12-31" +
                "&daily=temperature_2m_max,temperature_2m_min,precipitation_sum&timezone=Asia%2FTokyo"

        private fun fmt(v: Double) = String.format(Locale.ROOT, "%.4f", v)

        /**
         * Values of [name] in a block: the JMA model's, where it has none the other model's (`name_<model>` with
         * several models, plain `name` with one).
         */
        private fun series(block: JsonObject, name: String, size: Int): List<Double?> {
            val lists = (MODEL_ORDER.map { "${name}_$it" } + name).mapNotNull { k -> (block[k] as? JsonArray)?.let { k to it } }
            return List(size) { i ->
                lists.firstNotNullOfOrNull { (_, arr) -> (arr.getOrNull(i) as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull }
            }
        }

        /** Whether the JMA model itself has a value at [i] (else another model filled in). */
        private fun jmaHas(block: JsonObject, name: String, i: Int): Boolean =
            ((block["${name}_jma_seamless"] as? JsonArray)?.getOrNull(i) as? JsonPrimitive)?.let { it !is JsonNull && it.doubleOrNull != null } == true

        private fun times(block: JsonObject): List<String> =
            (block["time"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }

        fun parseForecast(body: String): Map<String, DayWeather>? {
            val root = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val daily = root["daily"] as? JsonObject ?: return null
            val dates = times(daily)
            if (dates.isEmpty()) return null
            val n = dates.size
            val code = series(daily, "weather_code", n)
            val tMax = series(daily, "temperature_2m_max", n)
            val tMin = series(daily, "temperature_2m_min", n)
            val mm = series(daily, "precipitation_sum", n)
            val chance = series(daily, "precipitation_probability_max", n)
            val wind = series(daily, "wind_speed_10m_max", n)
            val hourly = root["hourly"] as? JsonObject
            val hours: Map<String, List<HourWeather>> = hourly?.let { hb ->
                val ts = times(hb)
                val hc = series(hb, "weather_code", ts.size)
                val ht = series(hb, "temperature_2m", ts.size)
                val hp = series(hb, "precipitation_probability", ts.size)
                val hm = series(hb, "precipitation", ts.size)
                ts.indices.mapNotNull { i ->
                    val date = ts[i].substringBefore('T')
                    val time = ts[i].substringAfter('T', "").take(5)
                    val hour = time.take(2).toIntOrNull() ?: return@mapNotNull null
                    if (hour < FIRST_HOUR) return@mapNotNull null
                    if (ht[i] == null && hc[i] == null) return@mapNotNull null
                    date to HourWeather(time, hc[i]?.roundToInt(), ht[i]?.round1(), hp[i]?.roundToInt(), hm[i]?.round1())
                }.groupBy({ it.first }, { it.second })
            }.orEmpty()
            return dates.indices.associate { i ->
                dates[i] to DayWeather(
                    date = dates[i],
                    source = if (jmaHas(daily, "temperature_2m_max", i)) DayWeather.SOURCE_JMA else DayWeather.SOURCE_MODEL,
                    code = code[i]?.roundToInt(),
                    tempMin = tMin[i]?.round1(),
                    tempMax = tMax[i]?.round1(),
                    rainChance = chance[i]?.roundToInt(),
                    rainMm = mm[i]?.round1(),
                    windMaxKmh = wind[i]?.round1(),
                    hours = hours[dates[i]].orEmpty(),
                )
            }
        }

        /** Per calendar day ("MM-dd"): the average over the years and the days around it. */
        fun parseNormals(body: String): Map<String, Normal>? {
            val root = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val daily = root["daily"] as? JsonObject ?: return null
            val dates = times(daily).mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
            if (dates.isEmpty()) return null
            val tMax = series(daily, "temperature_2m_max", dates.size)
            val tMin = series(daily, "temperature_2m_min", dates.size)
            val mm = series(daily, "precipitation_sum", dates.size)
            // Day of a 365-day year (29 Feb counts as 28 Feb).
            fun doy(md: MonthDay): Int = LocalDate.of(2023, md.monthValue, min(md.dayOfMonth, if (md.monthValue == 2) 28 else 31)).dayOfYear
            val byDoy = dates.indices.groupBy { doy(MonthDay.from(dates[it])) }
            val out = mutableMapOf<String, Normal>()
            var d = LocalDate.of(2024, 1, 1) // leap year: all 366 calendar days
            while (d.year == 2024) {
                val center = doy(MonthDay.from(d))
                val idx = byDoy.filterKeys { k -> abs(k - center).let { min(it, 365 - it) } <= CLIMATE_WINDOW }.values.flatten()
                fun avg(v: List<Double?>) = idx.mapNotNull { v[it] }.takeIf { it.isNotEmpty() }?.average()?.round1()
                val rain = idx.mapNotNull { mm[it] }
                out[monthDay(d)] = Normal(avg(tMin), avg(tMax), avg(mm), rain.takeIf { it.isNotEmpty() }?.let { r -> (r.count { it >= 1.0 } * 100.0 / r.size).roundToInt() })
                d = d.plusDays(1)
            }
            return out
        }

        private const val FIRST_HOUR = 6
        private fun Double.round1(): Double = (this * 10).roundToInt() / 10.0
    }
}
