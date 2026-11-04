package de.wanderlogplus.server

import de.wanderlogplus.server.ai.ChatTrip
import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Trip
import de.wanderlogplus.shared.sync.DayWeather
import de.wanderlogplus.shared.sync.WeatherText
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeatherServiceTest {
    private val tmp = Files.createTempDirectory("wlp-weather")
    private val store = SqliteStore(tmp.resolve("wanderlog-plus.db"))
    private var clock = Instant.parse("2027-05-19T00:00:00Z")

    // Two models: JMA has values for the first two days, the other model also for the third.
    private val forecast = """
        {"daily":{"time":["2027-05-19","2027-05-20","2027-05-21"],
          "weather_code_jma_seamless":[63,1,null],"weather_code_best_match":[61,2,3],
          "temperature_2m_max_jma_seamless":[19.44,24.0,null],"temperature_2m_max_best_match":[18,23,22.5],
          "temperature_2m_min_jma_seamless":[14.0,15.2,null],"temperature_2m_min_best_match":[13,15,14],
          "precipitation_sum_jma_seamless":[12.3,0.0,null],"precipitation_sum_best_match":[10,0,0.2],
          "precipitation_probability_max_jma_seamless":[null,null,null],"precipitation_probability_max_best_match":[85,10,20],
          "wind_speed_10m_max_jma_seamless":[20,10,null],"wind_speed_10m_max_best_match":[18,9,8]},
         "hourly":{"time":["2027-05-19T03:00","2027-05-19T09:00","2027-05-19T12:00"],
          "weather_code_jma_seamless":[3,63,61],"temperature_2m_jma_seamless":[13,15.5,17],
          "precipitation_probability_best_match":[50,90,70],"precipitation_jma_seamless":[0,2.5,1]}}
    """.trimIndent()

    /** Ten years of daily values: 20 May always 20/10 °C with rain every other year. */
    private val archive: String = run {
        val dates = mutableListOf<String>()
        val max = mutableListOf<Double>()
        val min = mutableListOf<Double>()
        val mm = mutableListOf<Double>()
        var d = java.time.LocalDate.of(2017, 5, 10)
        while (d.year <= 2026) {
            if (d.monthValue == 5 && d.dayOfMonth in 10..31) {
                dates += "\"$d\""
                max += 20.0
                min += 10.0
                mm += if (d.year % 2 == 0) 4.0 else 0.0
            }
            d = d.plusDays(1)
        }
        """{"daily":{"time":[${dates.joinToString(",")}],"temperature_2m_max":[${max.joinToString(",")}],""" +
            """"temperature_2m_min":[${min.joinToString(",")}],"precipitation_sum":[${mm.joinToString(",")}]}}"""
    }

    private class FakeHttp(val answers: (String) -> Pair<Int, String>) : WeatherHttp {
        val urls = mutableListOf<String>()
        override fun get(url: String): Pair<Int, String> {
            urls += url
            return answers(url)
        }
    }

    private fun trip(endDate: String = "2027-06-10") = ChatTrip(
        trip = Trip("t", "Japan", "2027-05-18", endDate),
        regions = listOf(Region("r", "Kyoto")),
        places = listOf(Place("p-hotel", name = "Hotel Gion", lat = 35.0037, lng = 135.7788)).associateBy { it.id },
        saved = emptyList(),
        stays = listOf(Stay("st", "p-hotel", "r", "2027-05-18", endDate)),
        days = emptyList(),
        dayItems = emptyList(),
    )

    @Test
    fun `forecast prefers the JMA model, other models fill in, climate covers days beyond`() {
        val http = FakeHttp { url -> if ("archive-api" in url) 200 to archive else 200 to forecast }
        val service = WeatherService(store, http) { clock }
        val w = service.forTrip(trip(), "2027-05-19")

        val first = w.forDate("2027-05-19")!!
        assertEquals(DayWeather.SOURCE_JMA, first.source)
        assertEquals(63, first.code)
        assertEquals(19.4, first.tempMax)
        assertEquals(85, first.rainChance) // JMA has none: from the other model
        assertEquals("Hotel Gion", first.place)
        assertEquals(listOf("09:00", "12:00"), first.hours.map { it.time }) // night hours dropped
        assertEquals(90, first.hours.first().rainChance)
        assertTrue(WeatherText.isRainy(first))
        assertEquals("Regen, 14–19 °C, Regen 85 % (12 mm)", WeatherText.summary(first))

        assertEquals(DayWeather.SOURCE_MODEL, w.forDate("2027-05-21")!!.source)
        assertNull(w.forDate("2027-05-18")) // past
        // 2027-05-22 is within the 16 forecast days but the forecast has no value: climate average.
        val later = w.forDate("2027-05-22")!!
        assertEquals(DayWeather.SOURCE_CLIMATE, later.source)
        assertEquals(20.0, later.tempMax)
        assertEquals(50, later.rainyDays)
        assertEquals("Klima-Mittel: 10–20 °C, an 50 % der Tage Regen", WeatherText.summary(later))
        assertTrue(!WeatherText.isRainy(later))
        assertTrue(http.urls.first().contains("models=jma_seamless,best_match") && http.urls.first().contains("timezone=Asia%2FTokyo"))
        assertTrue(http.urls.any { "start_date=2017-01-01&end_date=2026-12-31" in it }, http.urls.toString())
    }

    @Test
    fun `forecasts are cached for three hours and a stale one is used when Open-Meteo fails`() {
        var fail = false
        val http = FakeHttp { url -> if (fail) 503 to "" else if ("archive-api" in url) 200 to archive else 200 to forecast }
        val service = WeatherService(store, http) { clock }
        service.forTrip(trip("2027-05-21"), "2027-05-19")
        val calls = http.urls.size
        service.forTrip(trip("2027-05-21"), "2027-05-19")
        assertEquals(calls, http.urls.size) // cached

        clock = clock.plus(Duration.ofHours(4))
        fail = true
        val w = service.forTrip(trip("2027-05-21"), "2027-05-19")
        assertTrue(http.urls.size > calls) // asked again …
        assertEquals(63, w.forDate("2027-05-19")!!.code) // … and fell back to the stale forecast
    }

    @Test
    fun `without models the plain variable names are read`() {
        val plain = """{"daily":{"time":["2027-05-19"],"weather_code":[0],"temperature_2m_max":[25],"temperature_2m_min":[16],"precipitation_sum":[0],"precipitation_probability_max":[5]}}"""
        val http = FakeHttp { url -> if ("models=" in url) 400 to "{\"error\":true}" else 200 to plain }
        val w = WeatherService(store, http) { clock }.forTrip(trip("2027-05-20"), "2027-05-19")
        val d = w.forDate("2027-05-19")!!
        assertEquals(DayWeather.SOURCE_MODEL, d.source)
        assertEquals("Klar, 16–25 °C, Regen 5 %", WeatherText.summary(d))
    }
}
