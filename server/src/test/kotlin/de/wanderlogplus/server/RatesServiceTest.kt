package de.wanderlogplus.server

import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RatesServiceTest {
    private val store = SqliteStore(Files.createTempDirectory("wlp-rates").resolve("wanderlog-plus.db"))
    private var clock = Instant.parse("2027-05-10T09:00:00Z")
    private val urls = mutableListOf<String>()
    private var down = false
    private val http = WeatherHttp { url ->
        urls += url
        if (down) 503 to "" else {
            val date = if ("/latest" in url) "2027-05-10" else url.substringAfter("/v1/").substringBefore("?")
            200 to """{"amount":1.0,"base":"JPY","date":"$date","rates":{"EUR":0.0061}}"""
        }
    }
    private val service = RatesService(store, http, { clock }, { LocalDate.parse("2027-05-10") })

    @Test
    fun `past days are kept for good, latest for six hours, euros need no request`() {
        val old = service.rate("jpy", "2027-05-01")
        assertEquals(0.0061, old.rate)
        assertEquals("2027-05-01", old.date)
        assertTrue(urls.single().startsWith("https://api.frankfurter.dev/v1/2027-05-01?base=JPY&symbols=EUR"))
        service.rate("JPY", "2027-05-01")
        assertEquals(1, urls.size)

        assertEquals("2027-05-10", service.rate("JPY", null).date) // latest
        service.rate("JPY", "2027-05-12") // a future day uses the latest rate too
        assertEquals(2, urls.size)
        clock = clock.plus(Duration.ofHours(7))
        down = true
        assertEquals(0.0061, service.rate("JPY", null).rate) // stale latest when Frankfurter is down
        assertEquals(3, urls.size)
        assertFailsWith<RatesUnavailable> { service.rate("USD", "2027-05-02") }

        assertEquals(1.0, service.rate("EUR", "2027-05-02").rate)
        assertEquals(4, urls.size)
        assertFailsWith<BadInput> { service.rate("YEN!", null) }
    }
}
