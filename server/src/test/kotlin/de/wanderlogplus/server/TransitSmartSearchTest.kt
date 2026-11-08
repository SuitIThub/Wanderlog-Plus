package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.TransitPoint
import de.wanderlogplus.shared.sync.TransitRequest
import de.wanderlogplus.shared.sync.TransitStation
import de.wanderlogplus.shared.sync.TransitStationNear
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Instant
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitSmartSearchTest {
    private val store = SqliteStore(Files.createTempDirectory("wlp-smart").resolve("t.db"))
    private val clock = Instant.parse("2026-11-08T10:00:00Z")
    private val inputs = Collections.synchronizedList(mutableListOf<JsonObject>())

    private val hotel = TransitPoint(35.6740, 139.7370, "Hotel Akasaka")
    private val ryokan = TransitPoint(35.5500, 137.6000, "Ryokan Kiso")

    // Start stations: 赤坂 300 m, 赤坂見附 700 m, 溜池山王 1000 m, 青山一丁目 1300 m, 六本木 1600 m (too far).
    private val starts = listOf(
        TransitStationNear(TransitStation("赤坂", "Akasaka", 35.6720, 139.7365), 300),
        TransitStationNear(TransitStation("赤坂見附", "Akasaka-mitsuke", 35.6770, 139.7370), 700),
        TransitStationNear(TransitStation("溜池山王", "Tameike-sannō", 35.6735, 139.7410), 1000),
        TransitStationNear(TransitStation("青山一丁目", "Aoyama-itchōme", 35.6725, 139.7240), 1300),
        TransitStationNear(TransitStation("六本木", "Roppongi", 35.6630, 139.7310), 1600),
    )
    private val goals = listOf(
        TransitStationNear(TransitStation("中津川", "Nakatsugawa", 35.5006, 137.5028), 1200),
        TransitStationNear(TransitStation("美乃坂本", "Minosakamoto", 35.4700, 137.4800), 1400),
        TransitStationNear(TransitStation("恵那", "Ena", 35.4500, 137.4100), 2500),
    )

    private val finder = object : StationFinder {
        override fun near(lat: Double, lng: Double, limit: Int) = (if (lat > 35.6) starts else goals).take(limit)
        override fun locate(nameJa: String, lat: Double?, lng: Double?): TransitStation? =
            (starts + goals).map { it.station }.firstOrNull { it.nameJa == nameJa }
    }

    /** Arrival and transfers per start station: 赤坂見附 is 10 min faster than 赤坂, 溜池山王 has 2 transfers. */
    private fun answer(origin: String, dest: String): String? {
        if (origin == "青山一丁目") return null // no connection
        val (arr, transfers) = when (origin) {
            "赤坂" -> "11:40" to 1
            "赤坂見附" -> "11:30" to 1
            else -> "11:25" to 3
        }
        val arrival = if (dest == "美乃坂本") "11:50" else arr
        return """[{"rank":1,"departure_time":"09:20","arrival_time":"$arrival","duration_minutes":120,"transfers":$transfers,"total_fare_jpy":9000,
            "resolved_origin_ja":"$origin","resolved_destination_ja":"$dest",
            "legs":[{"line_ja":"東京メトロ銀座線","is_walk":false,"from_station_ja":"$origin","to_station_ja":"$dest","departure_time":"09:20","arrival_time":"$arrival","stops":[]}]}]"""
    }

    private val source = TransitSource { input ->
        inputs += input
        val body = answer(input["origin"]!!.jsonPrimitive.content, input["destination"]!!.jsonPrimitive.content)
        if (body == null) 201 to "[]" else 201 to body
    }

    private fun service(src: TransitSource = source) = TransitService(store, src, finder) { clock }

    @Test
    fun comparesStationsWithinWalkingDistance() {
        val r = service().search(TransitRequest(from = hotel, to = ryokan, datetime = "2027-05-20T09:00", smart = true))
        assertTrue(r.smart)
        // 4 start stations within 1.5 km (六本木 is too far) × 2 goals = 8 pairs, at most 6 asked.
        assertEquals(6, inputs.size)
        assertFalse(inputs.any { it["origin"]!!.jsonPrimitive.content == "六本木" })
        assertFalse(inputs.any { it["destination"]!!.jsonPrimitive.content == "恵那" })
        // The search from 赤坂見附 (700 m → 12 min walk) starts at 09:12.
        assertTrue(inputs.any { it["origin"]!!.jsonPrimitive.content == "赤坂見附" && it["datetime"]!!.jsonPrimitive.content == "2027-05-20T09:12" })
        // Ranked by balance: 赤坂見附 → 中津川 first (arrives earliest with one transfer).
        val best = r.routes.first()
        assertTrue(best.best)
        assertEquals("赤坂見附", best.legs.first().from.nameJa)
        assertEquals(12, best.accessMinutes)
        assertEquals(700, best.accessMeters)
        assertEquals(20, best.egressMinutes)
        assertTrue(r.routes.drop(1).none { it.best })
        assertTrue(r.routes.size <= 5)
        val scores = r.routes.map { it.score!! }
        assertEquals(scores.sorted(), scores)
        // door: 11:30 + 20 min walk − 09:00 = 170; + 5 per transfer; + half of 32 min walking = 191.
        assertEquals(170, best.doorMinutes)
        assertEquals(191, best.score)
        assertEquals("赤坂見附", r.fromStation.nameJa)
    }

    @Test
    fun arriveBySubtractsTheWalkAtTheGoal() {
        service().search(TransitRequest(from = hotel, to = ryokan, datetime = "2027-05-20T12:00", searchType = "arrive", smart = true))
        // 中津川 is 1200 m (20 min) from the ryokan: be there at 11:40.
        assertTrue(inputs.any { it["destination"]!!.jsonPrimitive.content == "中津川" && it["datetime"]!!.jsonPrimitive.content == "2027-05-20T11:40" })
    }

    @Test
    fun crossingMidnight() {
        service().search(TransitRequest(from = hotel, to = ryokan, datetime = "2027-05-20T23:55", smart = true))
        assertTrue(inputs.any { it["origin"]!!.jsonPrimitive.content == "赤坂見附" && it["datetime"]!!.jsonPrimitive.content == "2027-05-21T00:07" })
    }

    @Test
    fun givenStationsAreNotSmart() {
        service().search(TransitRequest(from = hotel, fromStation = "赤坂", to = ryokan, toStation = "中津川", datetime = "2027-05-20T09:00", smart = true))
        assertEquals(1, inputs.size)
    }

    @Test
    fun allPairsFailing() {
        assertFailsWith<TransitUnavailable> { service(TransitSource { inputs += it; 500 to "" }).search(TransitRequest(from = hotel, to = ryokan, datetime = "2027-05-20T09:00", smart = true)) }
    }
}
