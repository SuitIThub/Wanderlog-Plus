package de.wanderlogplus.server

import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.TrainLive
import de.wanderlogplus.shared.sync.TrainLiveEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap

/**
 * Live status of Shinkansen (JR Cyberstation, read through the unofficial shinkansen-live.vercel.app service): the
 * train's position and delay by name and number ("Nozomi 21"), plus its line's status. Asked only from
 * [BEFORE] before departure until [AFTER] after arrival (Japan time); answers per line and direction are kept for
 * [TTL]. Other trains (local and limited express) have no live data here.
 */
class TrainLiveService(
    private val store: SqliteStore,
    private val http: WeatherHttp?,
    private val now: () -> Instant = Instant::now,
    private val zone: ZoneId = ZoneId.of("Asia/Tokyo"),
) {
    private val cache = ConcurrentHashMap<String, Pair<Instant, JsonObject>>()

    fun forTrain(id: String): TrainLive {
        val h = http ?: return TrainLive(reason = "Live-Status für Züge ist nicht eingerichtet.")
        val rec = store.get(EntityType.TRAIN, id)?.takeIf { !it.isDeleted } ?: throw BadInput()
        val t = runCatching { EntityCodec.decode<Train>(rec) }.getOrNull() ?: throw BadInput()
        val wanted = shinkansen(t)
        if (wanted.isEmpty()) return TrainLive(reason = "Live-Status gibt es nur für Shinkansen (Zugnummer wie „Nozomi 21“ eintragen).")
        val date = t.depart.date?.take(10) ?: return TrainLive(reason = "Kein Abfahrtsdatum hinterlegt.")
        val dep = at(date, t.depart.time) ?: return TrainLive(reason = "Keine Abfahrtszeit hinterlegt.")
        val arr = at(t.arrive.date?.take(10) ?: date, t.arrive.time)?.takeIf { it.isAfter(dep) } ?: dep.plus(Duration.ofHours(4))
        val n = now()
        if (n.isBefore(dep.minus(BEFORE)) || n.isAfter(arr.plus(AFTER))) {
            return TrainLive(reason = "Live-Status nur rund um die Fahrt (ab 1 Stunde vorher).", fetchedAt = n.toEpochMilli())
        }
        val found = mutableListOf<TrainLiveEntry>()
        val lines = mutableListOf<String>()
        wanted.groupBy { lineOf(it.first) }.forEach { (line, list) ->
            if (line == null) return@forEach
            for (dir in listOf("D", "U")) {
                val o = fetch(h, line, dir, n) ?: continue
                if (dir == "D") summary(o, line)?.let { lines += it }
                list.forEach { (name, number) ->
                    entries(o).firstOrNull { matches(it.name, name, number) }?.let { e -> if (found.none { it.name == e.name }) found += e }
                }
                if (list.all { (name, number) -> found.any { matches(it.name, name, number) } }) break
            }
        }
        return when {
            found.isNotEmpty() -> TrainLive(true, trains = found, lineStatus = lines.joinToString(" · ").ifBlank { null }, fetchedAt = n.toEpochMilli())
            lines.isNotEmpty() -> TrainLive(reason = "Zug gerade nicht in der Auskunft (noch nicht unterwegs oder schon angekommen).", lineStatus = lines.joinToString(" · "), fetchedAt = n.toEpochMilli())
            else -> TrainLive(reason = "Shinkansen-Auskunft gerade nicht erreichbar.", fetchedAt = n.toEpochMilli())
        }
    }

    private fun at(date: String, time: String?): Instant? =
        runCatching { LocalDateTime.parse("${date}T${time?.take(5)}").atZone(zone).toInstant() }.getOrNull()

    private fun fetch(h: WeatherHttp, line: String, dir: String, n: Instant): JsonObject? {
        val key = "$line|$dir"
        cache[key]?.let { (at, o) -> if (at.plus(TTL).isAfter(n)) return o }
        val (status, body) = try { h.get("$BASE/api/status?lang=en&lineId=$line&directionId=$dir") } catch (e: Exception) { 0 to "" }
        if (status !in 200..299) return cache[key]?.second
        val o = runCatching { JSON.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return cache[key]?.second
        cache[key] = n to o
        return o
    }

    companion object {
        const val BASE = "https://shinkansen-live.vercel.app"
        val BEFORE: Duration = Duration.ofHours(1)
        val AFTER: Duration = Duration.ofMinutes(30)
        val TTL: Duration = Duration.ofMinutes(2)
        private val JSON = Json { ignoreUnknownKeys = true }

        /** Train name → line id of the service (1 Tokaido/Sanyo, 2 Tohoku, 3 Joetsu, 4 Hokuriku, 5 Kyushu, 6 Hokkaido, 7 Nishi-Kyushu). */
        private val LINES = mapOf(
            "nozomi" to "1", "hikari" to "1", "kodama" to "1", "mizuho" to "1", "sakura" to "1",
            "tsubame" to "5",
            "hayabusa" to "2", "hayate" to "2", "yamabiko" to "2", "nasuno" to "2", "tsubasa" to "2", "komachi" to "2",
            "toki" to "3", "tanigawa" to "3",
            "kagayaki" to "4", "hakutaka" to "4", "tsurugi" to "4", "asama" to "4",
            "kamome" to "7",
        )

        fun lineOf(name: String): String? = LINES[name.lowercase()]

        /** The Shinkansen named in the train's number ("Nozomi 21 / Shinano 7" → Nozomi 21) as (name, number). */
        fun shinkansen(t: Train): List<Pair<String, String>> =
            (t.number.orEmpty().split('/', ',', '+') + listOfNotNull(t.carrier))
                .mapNotNull { Regex("""([A-Za-z]+)\s*(\d{1,4})""").find(it.trim())?.let { m -> m.groupValues[1] to m.groupValues[2] } }
                .filter { lineOf(it.first) != null }
                .distinct()

        fun matches(trainName: String, name: String, number: String): Boolean {
            val m = Regex("""^([A-Za-z]+)\s*(\d{1,4})""").find(trainName.trim()) ?: return false
            return m.groupValues[1].equals(name, ignoreCase = true) && m.groupValues[2].trimStart('0') == number.trimStart('0')
        }

        fun entries(o: JsonObject): List<TrainLiveEntry> = (o["trains"] as? JsonArray).orEmpty().mapNotNull { e ->
            val x = e as? JsonObject ?: return@mapNotNull null
            fun s(k: String) = (x[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
            TrainLiveEntry(s("trainName").ifBlank { return@mapNotNull null }, s("status"), s("detail"), s("tone"), s("updatedAt"))
        }

        fun summary(o: JsonObject, line: String): String? = (o["summary"] as? JsonArray).orEmpty()
            .mapNotNull { it as? JsonObject }
            .firstOrNull { (it["lineId"] as? JsonPrimitive)?.contentOrNull == line }
            ?.let { "${(it["lineName"] as? JsonPrimitive)?.contentOrNull}: ${(it["status"] as? JsonPrimitive)?.contentOrNull}" }
    }
}
