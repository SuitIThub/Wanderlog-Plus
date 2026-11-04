package de.wanderlogplus.shared.sync

import kotlinx.serialization.Serializable
import java.util.Locale
import kotlin.math.roundToInt

/** `GET /api/weather`: the weather of every trip day from today on, at that night's stay; [fetchedAt] in epoch millis. */
@Serializable
data class TripWeather(val days: List<DayWeather> = emptyList(), val fetchedAt: Long = 0) {
    fun forDate(date: String): DayWeather? = days.firstOrNull { it.date == date }
}

/**
 * The weather of one day at [place]. [source]: [SOURCE_JMA] (forecast of the Japan Meteorological Agency's models),
 * [SOURCE_MODEL] (forecast of other models, further ahead) or [SOURCE_CLIMATE] (no forecast yet: the average of the
 * last years around that date; then [rainyDays] is the share of rainy days in percent and [code] is null).
 * [code] is a WMO weather code.
 */
@Serializable
data class DayWeather(
    val date: String,
    val place: String = "",
    val lat: Double = 0.0,
    val lng: Double = 0.0,
    val source: String = SOURCE_JMA,
    val code: Int? = null,
    val tempMin: Double? = null,
    val tempMax: Double? = null,
    /** Highest chance of rain of the day, in percent. */
    val rainChance: Int? = null,
    val rainMm: Double? = null,
    val windMaxKmh: Double? = null,
    val rainyDays: Int? = null,
    /** Hourly values from 06:00 to 23:00 (forecast only). */
    val hours: List<HourWeather> = emptyList(),
) {
    val isForecast: Boolean get() = source != SOURCE_CLIMATE

    companion object {
        const val SOURCE_JMA = "jma"
        const val SOURCE_MODEL = "model"
        const val SOURCE_CLIMATE = "climate"
    }
}

@Serializable
data class HourWeather(val time: String, val code: Int? = null, val temp: Double? = null, val rainChance: Int? = null, val rainMm: Double? = null)

/** German texts and judgements for [DayWeather]. */
object WeatherText {
    /** Rain chance (percent) from which a day counts as rainy. */
    const val RAINY_CHANCE = 60

    /** Rain amount (mm) from which a day counts as rainy. */
    const val RAINY_MM = 5.0

    /** Rain heavy enough that being outside is no fun: plans should change. */
    fun isRainy(d: DayWeather): Boolean =
        if (d.isForecast) (d.rainChance ?: 0) >= RAINY_CHANCE && (d.rainMm ?: RAINY_MM) >= 1.0 || (d.rainMm ?: 0.0) >= RAINY_MM
        else false

    fun label(code: Int?): String = when (code) {
        null -> "Wetter"
        0 -> "Klar"
        1 -> "Überwiegend klar"
        2 -> "Teilweise bewölkt"
        3 -> "Bewölkt"
        45, 48 -> "Nebel"
        51, 53, 55 -> "Nieselregen"
        56, 57 -> "Gefrierender Niesel"
        61 -> "Leichter Regen"
        63 -> "Regen"
        65 -> "Starker Regen"
        66, 67 -> "Gefrierender Regen"
        71, 73, 75, 77 -> "Schnee"
        80, 81 -> "Regenschauer"
        82 -> "Heftige Schauer"
        85, 86 -> "Schneeschauer"
        95 -> "Gewitter"
        96, 99 -> "Gewitter mit Hagel"
        else -> "Wetter"
    }

    /** Short emoji-free icon key for the UI: sun, partly, cloud, fog, rain, snow, storm. */
    fun icon(code: Int?): String = when (code) {
        null -> "cloud"
        0, 1 -> "sun"
        2 -> "partly"
        3 -> "cloud"
        45, 48 -> "fog"
        71, 73, 75, 77, 85, 86 -> "snow"
        95, 96, 99 -> "storm"
        in 51..67, in 80..82 -> "rain"
        else -> "cloud"
    }

    fun temps(d: DayWeather): String? {
        val lo = d.tempMin?.roundToInt()
        val hi = d.tempMax?.roundToInt()
        return when {
            lo != null && hi != null -> "$lo–$hi °C"
            hi != null -> "$hi °C"
            lo != null -> "$lo °C"
            else -> null
        }
    }

    fun rain(d: DayWeather): String? =
        if (!d.isForecast) d.rainyDays?.let { "an $it % der Tage Regen" }
        else d.rainChance?.let { c -> "Regen $c %" + (d.rainMm?.takeIf { it >= 0.5 }?.let { " (${mm(it)})" } ?: "") }
            ?: d.rainMm?.takeIf { it >= 0.5 }?.let { "Regen ${mm(it)}" }

    /** "Regen, 14–21 °C, Regen 80 % (12 mm)" or, without forecast, "Klima-Mittel: 12–21 °C, an 40 % der Tage Regen". */
    fun summary(d: DayWeather): String {
        val parts = listOfNotNull(if (d.isForecast) label(d.code) else null, temps(d), rain(d))
        val text = parts.joinToString(", ")
        return if (d.isForecast) text else "Klima-Mittel: $text"
    }

    /** Where the values come from, for a small caption. */
    fun sourceLabel(d: DayWeather): String = when (d.source) {
        DayWeather.SOURCE_JMA -> "Vorhersage JMA"
        DayWeather.SOURCE_MODEL -> "Vorhersage (Tendenz)"
        else -> "Noch keine Vorhersage – Mittel der letzten Jahre"
    }

    private fun mm(v: Double) = if (v < 10) String.format(Locale.GERMAN, "%.1f mm", v) else "${v.roundToInt()} mm"
}

/** `GET /api/rates?from=JPY&date=…`: euros per unit of [from] on [date] (the business day the rate is from). */
@Serializable
data class RateInfo(val from: String, val to: String = "EUR", val rate: Double, val date: String)

/** A Google photo of a place (`GET /api/places/photos`); [name] fetches it, [author] must be credited. */
@Serializable
data class PlacePhoto(val name: String, val width: Int = 0, val height: Int = 0, val author: String = "", val authorUri: String? = null)

@Serializable
data class PlacePhotos(val photos: List<PlacePhoto> = emptyList())
