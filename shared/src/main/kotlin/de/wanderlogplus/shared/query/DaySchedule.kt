package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.TravelMode
import java.time.LocalDate

/** Why a step of the estimated day does not work out. */
enum class TimeWarning { LATE, CLOSED, MISSES_RIDE }

/** A warning at a program place; [minutes] how late for [TimeWarning.LATE], else 0. */
data class ScheduleWarning(val kind: TimeWarning, val minutes: Int = 0)

/**
 * One program place of the estimated day, in minutes of the day: [start] (arrival, or its fixed time), [end] after the
 * stay; [wayMinutes]/[wayMode] the way to it (null without one).
 */
data class ScheduledStop(
    val itemId: String,
    val start: Int,
    val end: Int,
    val fixed: Boolean,
    val wayMinutes: Int?,
    val wayMode: TravelMode?,
    val warnings: List<ScheduleWarning>,
)

/** The estimated day: when it starts and every program place by item id (notes have none), in flow order. */
data class DayScheduleView(val dayStart: Int, val stops: Map<String, ScheduledStop>) {
    /** "~13:00" when estimated, "13:00" when fixed; null for notes and unknown items. */
    fun startText(itemId: String): String? = stops[itemId]?.let { (if (it.fixed) "" else "~") + DayNow.hhmm(it.start) }

    companion object {
        val EMPTY = DayScheduleView(DaySchedule.DEFAULT_START, emptyMap())
    }
}

/**
 * Estimates the times of a day's flow: the clock starts at the day's start at last night's stay (or at the first
 * place), every place is reached by its way (a [known] duration, a chosen connection, else the straight-line estimate
 * of [DayNow.way]) and stays its duration ([DayItem.stayMinutes], else the place's typical one). A fixed time holds:
 * one waits for it, or is [TimeWarning.LATE]. Rides stand at their time; the clock goes on from their arrival.
 */
object DaySchedule {
    const val DEFAULT_START = 9 * 60

    fun build(
        flow: DayFlowView,
        places: Map<String, Place>,
        saved: List<SavedPlace>,
        day: Day?,
        dow: Int?,
        endpoint: (Endpoint) -> Pair<Double, Double>? = { e -> e.lat?.let { la -> e.lng?.let { la to it } } },
        known: (legKey: String) -> Int? = { null },
    ): DayScheduleView {
        val savedByPlace = saved.groupBy { it.placeId }.mapValues { (_, l) -> l.first() }
        val dayStart = DayNow.mins(day?.startTime)?.takeIf { it < 24 * 60 } ?: DEFAULT_START
        var clock = dayStart
        var at: Pair<Double, Double>? = null
        val stops = LinkedHashMap<String, ScheduledStop>()
        val warnings = HashMap<String, MutableList<ScheduleWarning>>()
        var lastStop: String? = null

        fun placePoint(placeId: String?) = placeId?.let { places[it] }?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) }?.let { it.lat to it.lng }
        fun wayTo(idx: Int, to: Pair<Double, Double>?, mode: TravelMode?, chosen: Int?): Pair<Int?, TravelMode?> {
            val leg = flow.legTo(idx) ?: return null to null
            known(leg.key)?.let { return it to (mode ?: TravelMode.WALK) }
            chosen?.let { return it to TravelMode.TRANSIT }
            val from = at ?: return null to null
            to ?: return null to null
            return DayNow.way(DayNow.km(from.first, from.second, to.first, to.second), mode)
        }

        flow.entries.forEachIndexed { idx, e ->
            when (e) {
                is FlowEntry.Start -> at = placePoint(e.stay.placeId)
                is FlowEntry.Item -> {
                    val item = e.item
                    if (item.kind != DayItemKind.PLACE) return@forEachIndexed
                    val point = placePoint(item.placeId)
                    val (way, mode) = wayTo(idx, point, item.legMode, item.legTransit?.routes?.firstOrNull()?.durationMinutes)
                    val arrive = clock + (way ?: 0)
                    val fixed = DayNow.mins(item.fixedTime)?.takeIf { it < 24 * 60 }
                    val list = warnings.getOrPut(item.id) { mutableListOf() }
                    if (fixed != null && arrive > fixed) list += ScheduleWarning(TimeWarning.LATE, arrive - fixed)
                    val start = fixed ?: arrive
                    val place = item.placeId?.let { places[it] }
                    if (dow != null && place != null && isOpenAt(place.openingPeriods, dow, DayNow.hhmm(start)) == false) {
                        list += ScheduleWarning(TimeWarning.CLOSED)
                    }
                    val end = start + (item.stayMinutes?.takeIf { it >= 0 } ?: DayNow.stayMinutes(place, savedByPlace[item.placeId]))
                    stops[item.id] = ScheduledStop(item.id, start, end, fixed != null, way, mode, emptyList())
                    clock = end
                    if (point != null) at = point
                    lastStop = item.id
                }
                is FlowEntry.Ride -> {
                    val (dep, arr) = when (val m = e.movement) {
                        is Movement.FlightMove -> m.flight.depart to m.flight.arrive
                        is Movement.TrainMove -> m.train.depart to m.train.arrive
                    }
                    val stored = flow.legTo(idx)?.let { day?.legs?.get(it.key) }
                    val (way, _) = wayTo(idx, endpoint(dep), stored?.mode, stored?.transit?.routes?.firstOrNull()?.durationMinutes)
                    val leaves = DayNow.mins(dep.time)
                    if (leaves != null && clock + (way ?: 0) > leaves) {
                        lastStop?.let { warnings.getOrPut(it) { mutableListOf() } += ScheduleWarning(TimeWarning.MISSES_RIDE) }
                    }
                    val arrives = DayNow.mins(arr.time)?.takeIf { arr.date == null || arr.date == dep.date }
                    clock = arrives ?: leaves ?: clock
                    at = endpoint(arr) ?: at
                }
                is FlowEntry.Night -> Unit
            }
        }
        return DayScheduleView(dayStart, stops.mapValues { (id, s) -> s.copy(warnings = warnings[id].orEmpty().distinct()) })
    }

    /** The estimated day [date] from the trip's lists (flow as on the day page). */
    fun forDate(
        date: String,
        day: Day?,
        items: List<DayItem>,
        flights: List<Flight>,
        trains: List<Train>,
        stays: List<Stay>,
        saved: List<SavedPlace>,
        places: Map<String, Place>,
        regions: List<Region>,
        endpoint: (Endpoint) -> Pair<Double, Double>? = { e -> e.lat?.let { la -> e.lng?.let { la to it } } },
        known: (legKey: String) -> Int? = { null },
    ): DayScheduleView {
        val flow = DayFlow.build(date, items, movementsOn(flights, trains, date), stays, saved, places, regions, endpoint)
        val dow = runCatching { LocalDate.parse(date).dayOfWeek.value % 7 }.getOrNull()
        return build(flow, places, saved, day, dow, endpoint, known)
    }
}
