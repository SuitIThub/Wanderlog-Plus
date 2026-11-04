package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.RateInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Exchange rates could not be fetched. */
class RatesUnavailable : RuntimeException("rates unavailable")

/**
 * Euro exchange rates (ECB reference rates via the free Frankfurter API, no key). A rate of a past day never
 * changes and is kept for good; "today" and later days use the latest rate, kept for [LATEST_TTL]. Weekends and
 * holidays answer with the business day before.
 */
class RatesService(
    private val store: SqliteStore,
    private val http: WeatherHttp?,
    private val now: () -> Instant = Instant::now,
    private val today: () -> LocalDate = { LocalDate.now(java.time.ZoneOffset.UTC) },
) {
    fun rate(from: String, date: String?): RateInfo {
        val cur = from.trim().uppercase()
        if (!cur.matches(Regex("[A-Z]{3}"))) throw BadInput()
        val day = date?.takeIf { it.isNotBlank() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() ?: throw BadInput() }
        if (cur == BASE) return RateInfo(cur, BASE, 1.0, (day ?: today()).toString())
        val latest = day == null || day >= today()
        val key = "rate|$cur|" + (if (latest) "latest" else day.toString())
        store.cachedRoute(key)?.let { (json, at) ->
            val fresh = !latest || Instant.ofEpochMilli(at).plus(LATEST_TTL).isAfter(now())
            if (fresh) parse(json, cur)?.let { return it }
        }
        val h = http ?: throw RatesUnavailable()
        val url = "https://api.frankfurter.dev/v1/" + (if (latest) "latest" else day.toString()) + "?base=$cur&symbols=$BASE"
        val (status, body) = try { h.get(url) } catch (e: Exception) { 0 to "" }
        val info = if (status in 200..299) parse(body, cur) else null
        if (info == null) {
            // A stale latest rate is better than none.
            store.cachedRoute(key)?.let { (json, _) -> parse(json, cur)?.let { return it } }
            throw RatesUnavailable()
        }
        store.putCachedRoute(key, body, now().toEpochMilli())
        return info
    }

    companion object {
        const val BASE = "EUR"
        val LATEST_TTL: Duration = Duration.ofHours(6)
        private val JSON = Json { ignoreUnknownKeys = true }

        fun parse(body: String, from: String): RateInfo? {
            val root = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
            val rate = ((root["rates"] as? JsonObject)?.get(BASE) as? JsonPrimitive)?.doubleOrNull ?: return null
            val date = (root["date"] as? JsonPrimitive)?.contentOrNull ?: return null
            return RateInfo(from, BASE, rate, date)
        }
    }
}
