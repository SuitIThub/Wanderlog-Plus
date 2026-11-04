package de.wanderlogplus.shared.sync

import de.wanderlogplus.shared.model.CriterionScore
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.query.WeatherFit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class WeatherTextTest {
    @Test
    fun `rainy days need a real chance and some rain`() {
        assertTrue(WeatherText.isRainy(DayWeather("d", rainChance = 70, rainMm = 3.0)))
        assertFalse(WeatherText.isRainy(DayWeather("d", rainChance = 70, rainMm = 0.2))) // likely, but only drops
        assertTrue(WeatherText.isRainy(DayWeather("d", rainChance = 40, rainMm = 8.0)))
        assertFalse(WeatherText.isRainy(DayWeather("d", rainChance = 30, rainMm = 1.0)))
        assertFalse(WeatherText.isRainy(DayWeather("d", source = DayWeather.SOURCE_CLIMATE, rainMm = 9.0, rainyDays = 80)))
    }

    @Test
    fun `summaries and icons`() {
        assertEquals("Klar, 16–25 °C", WeatherText.summary(DayWeather("d", code = 0, tempMin = 16.2, tempMax = 24.6)))
        assertEquals("Regenschauer, 20 °C, Regen 60 % (2,5 mm)", WeatherText.summary(DayWeather("d", code = 80, tempMax = 20.0, rainChance = 60, rainMm = 2.5)))
        assertEquals("rain", WeatherText.icon(61))
        assertEquals("storm", WeatherText.icon(95))
        assertEquals("sun", WeatherText.icon(1))
    }

    @Test
    fun `outdoor places by research, types or name`() {
        val judged = SavedPlace("s", "p", dossier = PlaceDossier(criteria = listOf(CriterionScore("weather", score = 4))))
        assertFalse(WeatherFit.isWeatherSensitive(judged, Place("p", name = "Ryoan-ji Garten"))) // research wins
        assertTrue(WeatherFit.isWeatherSensitive(null, Place("p", name = "Fushimi", categories = listOf("hiking_area"))))
        assertTrue(WeatherFit.isWeatherSensitive(null, Place("p", name = "Magome–Tsumago Wanderung")))
        assertTrue(WeatherFit.isWeatherSensitive(null, Place("p", name = "富士山")))
        assertFalse(WeatherFit.isWeatherSensitive(null, Place("p", name = "Nationalmuseum", categories = listOf("museum"))))
    }
}
