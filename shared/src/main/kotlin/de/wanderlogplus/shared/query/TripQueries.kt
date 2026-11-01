package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.*
import java.time.LocalDate
import java.time.format.DateTimeParseException

data class DayView(
    val date: String,
    val day: Day?,
    val regionId: String?,
    val stay: Stay?,
    val isTravelDay: Boolean,
)

class TripQueries(
    private val trip: Trip,
    private val stays: List<Stay>,
    private val days: List<Day>,
    private val regions: List<Region>,
    private val savedPlaces: List<SavedPlace>,
    private val dayItems: List<DayItem>,
) {
    private fun parse(date: String): LocalDate = LocalDate.parse(date)

    private fun parseOrNull(date: String): LocalDate? = try { LocalDate.parse(date) } catch (_: DateTimeParseException) { null }

    /** Stays with parsable check-in/out dates; others are ignored. */
    private val datedStays: List<Triple<Stay, LocalDate, LocalDate>> by lazy {
        stays.mapNotNull { s ->
            val i = parseOrNull(s.checkIn) ?: return@mapNotNull null
            val o = parseOrNull(s.checkOut) ?: return@mapNotNull null
            Triple(s, i, o)
        }
    }

    private fun stayFor(date: String): Stay? {
        val d = parse(date)
        datedStays.firstOrNull { (_, i, o) -> i <= d && d < o }?.let { return it.first }
        val last = datedStays.maxByOrNull { it.third } ?: return null
        return if (d >= last.third) last.first else null
    }

    // Several Days may share a date (e.g. created on two devices); the largest id wins, deterministically.
    private val dayByDate: Map<String, Day> by lazy {
        days.groupBy { it.date }.mapValues { (_, v) -> v.maxByOrNull { it.id }!! }
    }

    /** The one Day that represents [date], or null if there is none. */
    fun dayFor(date: String): Day? = dayByDate[date]

    fun days(): List<DayView> {
        val start = parseOrNull(trip.startDate) ?: return emptyList()
        val end = parseOrNull(trip.endDate) ?: return emptyList()
        val result = mutableListOf<DayView>()
        var d = start
        while (d <= end) {
            val date = d.toString()
            val region = regionFor(date)
            result += DayView(
                date = date,
                day = dayByDate[date],
                regionId = region,
                stay = stayFor(date),
                isTravelDay = region == null || stays.any { it.checkIn == date || it.checkOut == date },
            )
            d = d.plusDays(1)
        }
        return result
    }

    fun regionFor(date: String): String? =
        dayByDate[date]?.regionOverrideId ?: derivedRegionFor(date)

    /** The region [date] gets from its stay, ignoring any manual override ("Automatisch"). */
    fun derivedRegionFor(date: String): String? = stayFor(date)?.regionId

    fun suggestionsFor(date: String): List<SavedPlace> {
        val r = regionFor(date) ?: return emptyList()
        val regionIds = regions.filter { it.dayTripFromRegionId == r }.map { it.id }.toSet() + r
        val planned = dayItems.filter { it.kind == DayItemKind.PLACE }.mapNotNull { it.placeId }.toSet()
        return savedPlaces.filter {
            it.regionId in regionIds && it.status == PlaceStatus.IDEA && it.placeId !in planned
        }
    }
}
