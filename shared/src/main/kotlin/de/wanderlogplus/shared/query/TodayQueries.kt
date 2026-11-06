package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.*
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

data class StayDeadline(val date: String, val stay: Stay)

sealed interface Movement {
    val date: String
    val time: String?

    data class FlightMove(val flight: Flight) : Movement {
        override val date get() = flight.depart.date.orEmpty()
        override val time get() = flight.depart.time
    }

    data class TrainMove(val train: Train) : Movement {
        override val date get() = train.depart.date.orEmpty()
        override val time get() = train.depart.time
    }
}

typealias FlightMove = Movement.FlightMove
typealias TrainMove = Movement.TrainMove

/**
 * Flights and trains departing on [date], by departure time ("HH:mm"; missing or blank last, stable otherwise).
 * The one implementation used by the "Heute" screen, the trip list and the day detail.
 */
fun movementsOn(flights: List<Flight>, trains: List<Train>, date: String): List<Movement> =
    (flights.filter { it.depart.date == date }.map { Movement.FlightMove(it) } +
        trains.filter { it.depart.date == date }.map { Movement.TrainMove(it) })
        .sortedWith(compareBy(nullsLast()) { it.time?.takeIf { t -> t.isNotBlank() } })

sealed interface TodayState {
    data class Before(val daysUntilStart: Long, val deadlines: List<StayDeadline>) : TodayState
    data class During(val day: DayView, val movements: List<Movement>) : TodayState
    data object After : TodayState
    data object Unknown : TodayState
}

/** What the "today" screen shows, relative to the trip dates. */
class TodayQueries(
    private val queries: TripQueries,
    private val trip: Trip,
    private val stays: List<Stay>,
    private val flights: List<Flight>,
    private val trains: List<Train>,
) {
    private fun parseOrNull(s: String?): LocalDate? =
        try { s?.let { LocalDate.parse(it) } } catch (_: DateTimeParseException) { null }

    /** Flights and trains departing on [date], by departure time (missing last). */
    fun movementsOn(date: String): List<Movement> = movementsOn(flights, trains, date)

    private fun deadlines(today: LocalDate): List<StayDeadline> =
        stays.mapNotNull { s ->
            val d = parseOrNull(s.cancellationDeadline?.take(10)) ?: return@mapNotNull null
            if (d < today) null else StayDeadline(d.toString(), s)
        }.sortedBy { it.date }

    fun state(today: LocalDate): TodayState {
        val start = parseOrNull(trip.startDate) ?: return TodayState.Unknown
        val end = parseOrNull(trip.endDate) ?: return TodayState.Unknown
        return when {
            today < start -> TodayState.Before(ChronoUnit.DAYS.between(today, start), deadlines(today))
            today > end -> TodayState.After
            else -> {
                val date = today.toString()
                TodayState.During(queries.days().first { it.date == date }, movementsOn(date))
            }
        }
    }
}
