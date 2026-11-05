package de.wanderlogplus.server

import de.wanderlogplus.shared.sync.TransitPoint
import de.wanderlogplus.shared.sync.TransitRequest
import java.nio.file.Files
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransitousSourceTest {
    private val store = SqliteStore(Files.createTempDirectory("wlp-transitous").resolve("wanderlog-plus.db"))
    private val plan = javaClass.getResource("/transitous-plan.json")!!.readText()
    private val urls = mutableListOf<String>()
    private var answer = plan
    private val http = WeatherHttp { url ->
        urls += url
        if ("/geocode" in url) 200 to """[{"type":"STOP","name":"Hannover Kröpcke","lat":52.3747,"lon":9.7383}]""" else 200 to answer
    }
    private val source = TransitousSource(store, http, { Instant.parse("2026-11-05T12:00:00Z") })
    private val req = TransitRequest(TransitPoint(52.3747, 9.7383), TransitPoint(50.0510, 8.5706), datetime = "2026-11-06T08:00")

    @Test
    fun `German connections with DB and local lines, platforms and local times`() {
        val r = source.search(req)
        val route = r.routes.first()
        val rides = route.legs.filter { !it.walk }
        assertTrue(rides.any { it.line.startsWith("ICE") }, rides.map { it.line }.toString())
        assertEquals("Hannover Hauptbahnhof", rides.first().from.name)
        assertEquals("08:53", rides.first().departure) // 07:53 UTC is 08:53 in Germany
        assertTrue(route.durationMinutes > 0)
        assertTrue(r.routes.any { it.fastest })
        assertTrue(urls.single().contains("fromPlace=52.374700%2C9.738300") && urls.single().contains("time=2026-11-06T07%3A00%3A00Z"))
        source.search(req)
        assertEquals(1, urls.size) // cached
    }

    @Test
    fun `stations by name are geocoded, far dates without timetable say so`() {
        answer = """{"itineraries":[]}"""
        val far = TransitRequest(fromStation = "Hannover Kröpcke", toStation = "Frankfurt Flughafen", datetime = "2027-05-07T08:00")
        val e = assertFailsWith<TransitUnavailable> { source.search(far) }
        assertEquals("transit_no_timetable", e.code)
        assertTrue(urls.count { "/geocode" in it } == 2)
        assertFailsWith<NoRoute> { source.search(req.copy(datetime = "2026-11-07T08:00")) }
    }

    @Test
    fun `Japan or elsewhere`() {
        assertTrue(TransitousSource.isJapan(TransitRequest(TransitPoint(35.0, 135.7), TransitPoint(34.7, 135.5), datetime = "x")))
        assertFalse(TransitousSource.isJapan(req))
        assertTrue(TransitousSource.isJapan(TransitRequest(fromStation = "京都", toStation = "奈良", datetime = "x")))
        assertFalse(TransitousSource.isJapan(TransitRequest(fromStation = "Hannover Hbf", toStation = "Celle", datetime = "x")))
    }
}
