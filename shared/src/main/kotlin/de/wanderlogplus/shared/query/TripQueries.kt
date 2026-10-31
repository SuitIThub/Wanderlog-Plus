package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.*
import java.time.LocalDate

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

    private fun stayFor(date: String): Stay? {
        val d = parse(date)
        stays.firstOrNull { parse(it.checkIn) <= d && d < parse(it.checkOut) }?.let { return it }
        val last = stays.maxByOrNull { parse(it.checkOut) } ?: return null
        return if (d >= parse(last.checkOut)) last else null
    }

    fun days(): List<DayView> {
        val start = parse(trip.startDate)
        val end = parse(trip.endDate)
        val byDate = days.associateBy { it.date }
        val result = mutableListOf<DayView>()
        var d = start
        while (d <= end) {
            val date = d.toString()
            val region = regionFor(date)
            result += DayView(
                date = date,
                day = byDate[date],
                regionId = region,
                stay = stayFor(date),
                isTravelDay = region == null || stays.any { it.checkIn == date || it.checkOut == date },
            )
            d = d.plusDays(1)
        }
        return result
    }

    fun regionFor(date: String): String? =
        days.firstOrNull { it.date == date }?.regionOverrideId ?: stayFor(date)?.regionId

    fun suggestionsFor(date: String): List<SavedPlace> {
        val r = regionFor(date) ?: return emptyList()
        val regionIds = regions.filter { it.dayTripFromRegionId == r }.map { it.id }.toSet() + r
        val planned = dayItems.filter { it.kind == DayItemKind.PLACE }.mapNotNull { it.placeId }.toSet()
        return savedPlaces.filter {
            it.regionId in regionIds && it.status == PlaceStatus.IDEA && it.placeId !in planned
        }
    }
}
