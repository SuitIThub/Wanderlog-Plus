package de.wanderlogplus.app.data

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A pretended "now" for testing the Heute screen before the trip (debug mode only): [date] as ISO "2027-05-20", and
 * [time] "HH:MM" or null to keep the real time of day.
 */
data class SimulatedTime(val date: String, val time: String? = null) {
    /** The simulated moment for the real [now] (its time of day when [time] is null); null when [date] is invalid. */
    fun at(now: LocalDateTime): LocalDateTime? {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return null
        val t = time?.let { runCatching { LocalTime.parse(it) }.getOrNull() } ?: now.toLocalTime()
        return LocalDateTime.of(d, t)
    }

    /** The same simulation [days] later (or earlier). */
    fun shiftDays(days: Long): SimulatedTime =
        runCatching { copy(date = LocalDate.parse(date).plusDays(days).toString()) }.getOrDefault(this)

    /** "Do., 20.05.2027 · 09:00" or "… · echte Uhrzeit". */
    fun label(): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return date
        return DateTimeFormatter.ofPattern("EE, dd.MM.yyyy", Locale.GERMAN).format(d) + " · " + (time ?: "echte Uhrzeit")
    }
}

/** "2027-05-20" is a date; "09:00" or blank is a time (blank = real time of day). */
fun simulatedTimeOf(date: String, time: String): SimulatedTime? {
    val d = date.trim()
    if (runCatching { LocalDate.parse(d) }.isFailure) return null
    val t = time.trim().ifBlank { null }
    if (t != null && runCatching { LocalTime.parse(t) }.isFailure) return null
    return SimulatedTime(d, t)
}
