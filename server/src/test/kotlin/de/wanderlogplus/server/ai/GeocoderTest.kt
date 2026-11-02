package de.wanderlogplus.server.ai

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GeocoderTest {
    private val hit = """{"features":[{"geometry":{"type":"Point","coordinates":[139.7178,35.7295]},"properties":{"name":"Sunshine 60","city":"Tokyo","country":"Japan","countrycode":"JP"}}]}"""
    private val none = """{"features":[]}"""
    private var server: HttpServer? = null
    private val sleeps = mutableListOf<Long>()

    @AfterTest fun stop() { server?.stop(0) }

    /** A local Photon stand-in answering the given (status, body) pairs in turn (the last one repeats). */
    private fun photon(vararg answers: Pair<Int, String>): Pair<PhotonGeocoder, AtomicInteger> {
        val calls = AtomicInteger()
        val s = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        s.createContext("/api/") { ex ->
            val (status, body) = answers[minOf(calls.getAndIncrement(), answers.size - 1)]
            val bytes = body.toByteArray()
            ex.sendResponseHeaders(status, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        s.start()
        server = s
        return PhotonGeocoder("http://127.0.0.1:${s.address.port}", minIntervalMillis = 0, sleep = { sleeps += it }) to calls
    }

    @Test fun refusedRequestsAreRetriedWithBackoff() {
        val (g, calls) = photon(503 to "busy", 429 to "slow down", 200 to hit)
        val h = g.find("Sunshine 60")!!
        assertEquals(35.7295, h.lat)
        assertEquals("JP", h.country)
        assertEquals(3, calls.get())
        assertEquals(listOf(1_000L, 2_000L), sleeps)
    }

    @Test fun anOverloadPageIsNotANoMatch() {
        val (g, calls) = photon(200 to "<html>Too many requests</html>", 200 to none)
        assertNull(g.find("Nirgendwo"))
        assertEquals(2, calls.get())
    }

    @Test fun givingUpIsReportedAsUnavailable() {
        val (g, calls) = photon(503 to "busy")
        assertFailsWith<GeocoderUnavailable> { g.find("Ghibli Museum") }
        assertEquals(4, calls.get()) // first try + three retries
    }

    @Test fun shortenedNamesOnlyNearTheCity() {
        val tokyo = GeoHit(35.68, 139.76, null)
        val geocoder = Geocoder { q ->
            when (q) {
                "Tokio" -> tokyo
                "Sunshine 60 Tokio" -> GeoHit(35.7295, 139.7178, "Sunshine 60") // ~6 km from the centre
                "Weit Weg Tokio" -> GeoHit(34.69, 135.50, "Osaka") // a namesake far away
                else -> null
            }
        }
        val a = Assistant(object : ChatModel { override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean) = "{}" }, geocoder)
        val said = mutableListOf<String>()
        val (h, issue) = a.locate(FoundPlace("Sunshine 60 Observatory TENBOU PARK", null, "Tokio", "Japan", "sight", ""), { said += it })
        assertEquals(139.7178, h!!.lng)
        assertNull(issue)
        assertTrue(said.contains("Lage über den kürzeren Namen „Sunshine 60“ gefunden"))
        val (far, why) = a.locate(FoundPlace("Weit Weg Aussicht Turm", null, "Tokio", "Japan", "sight", ""))
        assertNull(far)
        assertEquals("not_found", why)
    }

    @Test fun unavailableGeocoderIsNamedAsSuch() {
        val a = Assistant(object : ChatModel { override fun complete(system: String, parts: List<AiPart>, webSearch: Boolean) = "{}" }, Geocoder { throw GeocoderUnavailable() })
        val said = mutableListOf<String>()
        val (h, issue) = a.locate(FoundPlace("Ghibli Museum", null, "Tokio", "Japan", "sight", ""), { said += it })
        assertNull(h)
        assertEquals("unavailable", issue)
        assertEquals(listOf("Ortsdienst (Photon) antwortet nicht, Lage fehlt"), said)
    }
}
