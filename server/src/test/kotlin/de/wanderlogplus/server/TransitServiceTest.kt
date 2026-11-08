package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.TransitPoint
import de.wanderlogplus.shared.sync.TransitRequest
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitStation
import de.wanderlogplus.shared.sync.TransitStationNear
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TransitServiceTest {
    private val tmp = Files.createTempDirectory("wlp-transit")
    private val store = SqliteStore(tmp.resolve("wanderlog-plus.db"))
    private var clock = Instant.parse("2026-11-04T10:00:00Z")

    /** What the actor returned for 東京 → 中津川 (shortened to one route). */
    private val answer = """[{"rank":1,"departure_time":"08:12","arrival_time":"10:48","duration_minutes":156,"riding_minutes":144,"transfers":1,
        "total_fare_jpy":12530,"base_fare_jpy":7150,"express_fare_jpy":5380,
        "seat_fees":[{"label_ja":"自由席","fare_jpy":4180},{"label_ja":"自由席","fare_jpy":1200}],"distance_km":445.9,
        "is_fastest":true,"is_cheapest":false,"is_fewest_transfers":true,"resolved_origin_ja":"東京","resolved_destination_ja":"中津川",
        "legs":[
          {"line_ja":"ＪＲ新幹線のぞみ15号","destination_ja":"博多行","is_walk":false,"from_station_ja":"東京","to_station_ja":"名古屋",
           "from_station_romaji":"Tokyo","to_station_romaji":"Nagoya","departure_time":"08:12","arrival_time":"09:48","departure_platform":"18","arrival_platform":"16",
           "stops":[{"name_ja":"品川","time":"08:19"},{"name_ja":"新横浜","time":"08:31"}]},
          {"line_ja":"ＪＲ特急しなの7号","destination_ja":"長野行","is_walk":false,"from_station_ja":"名古屋","to_station_ja":"中津川",
           "from_station_romaji":"Nagoya","to_station_romaji":"Nakatsugawa","departure_time":"10:00","arrival_time":"10:48","departure_platform":"10","arrival_platform":null,"stops":[]}
        ]}]"""

    private val inputs = mutableListOf<JsonObject>()
    private var status = 201
    private var body = answer

    private val source = TransitSource { input -> inputs += input; status to body }

    private val finder = object : StationFinder {
        val coords = mapOf("東京" to (35.6812 to 139.7671), "名古屋" to (35.1709 to 136.8815), "中津川" to (35.5006 to 137.5028))
        override fun near(lat: Double, lng: Double, limit: Int): List<TransitStationNear> = when {
            lat > 35.6 -> listOf(TransitStationNear(TransitStation("東京", "Tokyo", 35.6812, 139.7671), 450))
            lat > 35.4 -> listOf(TransitStationNear(TransitStation("中津川", "Nakatsugawa", 35.5006, 137.5028), 1200))
            else -> emptyList()
        }
        override fun locate(nameJa: String, lat: Double?, lng: Double?): TransitStation? =
            if (nameJa.contains("北海道")) TransitStation(nameJa, "", 43.06, 141.35) // a namesake far away
            else coords[TransitService.base(nameJa)]?.let { (a, b) -> TransitStation(nameJa, if (nameJa == "名古屋") "Nagoya" else "", a, b) }
    }

    private fun service(src: TransitSource? = source) = TransitService(store, src, finder) { clock }

    private val tokyo = TransitPoint(35.684, 139.770, "Hotel")
    private val nakatsugawa = TransitPoint(35.490, 137.500, "Wanderweg")

    @Test fun findsStationsAsksTheActorAndTranslates() {
        val r = service().search(TransitRequest(from = tokyo, to = nakatsugawa, datetime = "2027-05-20T08:00"))
        assertEquals("東京", inputs.single()["origin"]!!.jsonPrimitive.content)
        assertEquals("中津川", inputs.single()["destination"]!!.jsonPrimitive.content)
        assertEquals("true", inputs.single()["includeRomaji"]!!.jsonPrimitive.content)
        assertEquals(450, r.walkToStationMeters)
        assertEquals(1200, r.walkFromStationMeters)
        assertNull(r.stationWarning)
        val route = r.routes.single()
        assertEquals(12530, route.fareYen)
        assertEquals(7150, route.baseFareYen)
        assertEquals("Zuschlag, freier Sitz", route.fees.first().label)
        assertEquals("2027-05-20", route.date)
        val (shinkansen, ltd) = route.legs
        assertEquals("JR Shinkansen Nozomi 15", shinkansen.line)
        assertEquals("JR Ltd. Exp. Shinano 7", ltd.line)
        assertEquals("Richtung 博多", shinkansen.headsign)
        assertEquals("18", shinkansen.departurePlatform)
        assertNull(ltd.arrivalPlatform)
        // Located for the map; the OSM name wins over the plain romaji, which fills in otherwise.
        assertEquals(35.1709, shinkansen.to.lat)
        assertEquals("Nagoya", shinkansen.to.name)
        assertEquals("Nakatsugawa", ltd.to.name)
        assertEquals(listOf("品川", "新横浜"), shinkansen.stops.map { it.nameJa })
    }

    @Test fun answersAreCachedPerStationsTimeAndOptionsAndFreshSkipsTheCache() {
        val s = service()
        val req = TransitRequest(from = tokyo, to = nakatsugawa, datetime = "2027-05-20T08:00")
        s.search(req)
        s.search(req)
        assertEquals(1, inputs.size)
        s.search(req.copy(useShinkansen = false))
        assertEquals(2, inputs.size)
        // "fresh" takes an answer of the last 10 minutes; older ones are asked again.
        s.search(req.copy(fresh = true))
        assertEquals(2, inputs.size)
        clock = clock.plus(java.time.Duration.ofMinutes(11))
        s.search(req.copy(fresh = true))
        assertEquals(3, inputs.size)
        clock = clock.plus(TransitService.TTL).plusSeconds(60)
        s.search(req)
        assertEquals(4, inputs.size)
    }

    @Test fun namesakeElsewhereIsAskedAgainWithThePrefecture() {
        // 府中 near the hotel is in Hiroshima; the timetable first takes Tokyo's 府中.
        val fuchu = TransitStation("府中", "Fuchū", 34.568, 133.236, prefecture = "広島県")
        val local = object : StationFinder {
            override fun near(lat: Double, lng: Double, limit: Int) = listOf(TransitStationNear(fuchu, 300))
            override fun locate(nameJa: String, lat: Double?, lng: Double?) = fuchu.copy(nameJa = nameJa)
        }
        val src = TransitSource { input ->
            inputs += input
            val origin = input["origin"]!!.jsonPrimitive.content
            val resolved = if (origin == "府中(広島県)") "府中(広島県)" else "府中(東京都)"
            201 to answer.replace("\"resolved_origin_ja\":\"東京\"", "\"resolved_origin_ja\":\"$resolved\"")
        }
        val r = TransitService(store, src, local) { clock }.search(TransitRequest(from = TransitPoint(34.57, 133.24, "Hotel"), toStation = "中津川", datetime = "2027-05-20T08:00"))
        assertEquals(listOf("府中", "府中(広島県)"), inputs.map { it["origin"]!!.jsonPrimitive.content })
        assertNull(r.stationWarning)
    }

    @Test fun samePrefectureNeedsNoSecondAsk() {
        val kyoto = TransitStation("京都", "Kyoto", 34.985, 135.758, prefecture = "京都府")
        val local = object : StationFinder {
            override fun near(lat: Double, lng: Double, limit: Int) = listOf(TransitStationNear(kyoto, 300))
            override fun locate(nameJa: String, lat: Double?, lng: Double?) = kyoto.copy(nameJa = nameJa)
        }
        val src = TransitSource { input -> inputs += input; 201 to answer.replace("\"resolved_origin_ja\":\"東京\"", "\"resolved_origin_ja\":\"京都\"") }
        TransitService(store, src, local) { clock }.search(TransitRequest(from = TransitPoint(34.99, 135.76, "Hotel"), toStation = "中津川", datetime = "2027-05-20T08:00"))
        assertEquals(1, inputs.size)
    }

    @Test fun sameSearchRunsOnce() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val slow = TransitSource { input -> synchronized(inputs) { inputs += input }; started.countDown(); release.await(); status to body }
        val s = service(slow)
        val req = TransitRequest(from = tokyo, to = nakatsugawa, datetime = "2027-05-20T08:00", fresh = true)
        val first = java.util.concurrent.CompletableFuture.supplyAsync { s.search(req) }
        started.await()
        val second = java.util.concurrent.CompletableFuture.supplyAsync { s.search(req) }
        Thread.sleep(200)
        release.countDown()
        assertEquals(first.get(5, java.util.concurrent.TimeUnit.SECONDS).routes.size, second.get(5, java.util.concurrent.TimeUnit.SECONDS).routes.size)
        assertEquals(1, inputs.size)
    }

    @Test fun failedSearchReleasesWaiters() {
        val started = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val failing = TransitSource { _ -> started.countDown(); release.await(); 500 to "" }
        val s = service(failing)
        val req = TransitRequest(from = tokyo, to = nakatsugawa, datetime = "2027-05-20T08:00", fresh = true)
        val first = java.util.concurrent.CompletableFuture.supplyAsync { runCatching { s.search(req) }.exceptionOrNull() }
        started.await()
        val second = java.util.concurrent.CompletableFuture.supplyAsync { runCatching { s.search(req) }.exceptionOrNull() }
        Thread.sleep(200)
        release.countDown()
        assertTrue(first.get(5, java.util.concurrent.TimeUnit.SECONDS) is TransitUnavailable)
        assertTrue(second.get(5, java.util.concurrent.TimeUnit.SECONDS) is TransitUnavailable)
    }

    @Test fun givenStationNamesAreUsedAndAMismatchIsReported() {
        body = answer.replace("\"resolved_origin_ja\":\"東京\"", "\"resolved_origin_ja\":\"東京(北海道)\"")
        val r = service().search(TransitRequest(fromStation = "東京駅", toStation = "中津川", to = nakatsugawa, datetime = "2027-05-20T08:00"))
        assertEquals("東京", inputs.single()["origin"]!!.jsonPrimitive.content) // 駅 removed
        assertEquals("Die Fahrplanauskunft hat „東京(北海道)“ statt „東京駅“ gewählt.", r.stationWarning)
    }

    @Test fun aDisambiguatedNameIsNoMismatch() {
        body = answer.replace("\"resolved_destination_ja\":\"中津川\"", "\"resolved_destination_ja\":\"中津川(岐阜県)\"")
        assertNull(service().search(TransitRequest(from = tokyo, to = nakatsugawa, datetime = "2027-05-20T08:00")).stationWarning)
        assertEquals("六地蔵", TransitService.base("六地蔵(京都市営)"))
        assertEquals("稲荷", TransitService.base("稲荷（京都府）駅"))
    }

    @Test fun sameStationNoConnectionAndErrorsAreDistinct() {
        assertFailsWith<NoRoute> { service().search(TransitRequest(from = tokyo, to = tokyo, datetime = "2027-05-20T08:00")) }
        assertTrue(inputs.isEmpty()) // nothing paid for
        body = "[]"
        val s = service()
        val req = TransitRequest(from = tokyo, to = nakatsugawa, datetime = "2027-05-20T09:00")
        assertFailsWith<NoRoute> { s.search(req) }
        assertFailsWith<NoRoute> { s.search(req) } // cached "no connection"
        assertEquals(1, inputs.size)
        status = 402
        assertEquals("transit_no_credit", assertFailsWith<TransitUnavailable> { s.search(req.copy(datetime = "2027-05-20T10:00")) }.code)
        assertFailsWith<NoStation> { s.search(req.copy(from = TransitPoint(26.2, 127.7), datetime = "2027-05-20T11:00")) }
        assertFailsWith<BadInput> { s.search(req.copy(datetime = "morgen früh")) }
        assertFailsWith<TransitNotConfigured> { service(src = null).search(req) }
    }

    @Test fun endpointsAnswer() = testApplication {
        val base = testServices(tmp.resolve("app").also { Files.createDirectories(it) })
        application { module(Services(base.config, base.store, base.sync, base.files, base.imports, base.routes, base.ai, TransitService(base.store, source, finder) { clock })) }
        val r = client.post("/api/transit") {
            authed(); contentType(ContentType.Application.Json)
            setBody("""{"from":{"lat":35.684,"lng":139.770},"to":{"lat":35.49,"lng":137.5},"datetime":"2027-05-20T08:00"}""")
        }
        assertEquals(HttpStatusCode.OK, r.status)
        assertEquals(12530, Json.decodeFromString(TransitResult.serializer(), r.bodyAsText()).routes.single().fareYen)
        val st = client.get("/api/transit/stations?lat=35.684&lng=139.77") { authed() }
        assertTrue(st.bodyAsText().contains("Tokyo"))
    }

    @Test fun withoutTokenTheEndpointSaysSo() = testApplication {
        val base = testServices(tmp.resolve("app2").also { Files.createDirectories(it) })
        application { module(base) }
        val r = client.post("/api/transit") { authed(); contentType(ContentType.Application.Json); setBody("""{"from":{"lat":35.6,"lng":139.7},"to":{"lat":35.4,"lng":137.5},"datetime":"2027-05-20T08:00"}""") }
        assertEquals(HttpStatusCode.ServiceUnavailable, r.status)
        assertTrue(r.bodyAsText().contains("transit_not_configured"))
    }
}
