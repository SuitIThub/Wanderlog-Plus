package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay

/** One row of a day's flow, from the stay of the night before to the stay of the coming night. */
sealed interface FlowEntry {
    /** Where the day starts: last night's stay; [checkOut] on its check-out day. */
    data class Start(val stay: Stay, val checkOut: Boolean) : FlowEntry

    /** A program item; [checkIn] when it is the stay one checks in to that day. */
    data class Item(val item: DayItem, val checkIn: Stay? = null) : FlowEntry

    /** A flight or train at its time of the day. */
    data class Ride(val movement: Movement) : FlowEntry

    /** Tonight's stay; [checkIn] when one checks in that day and the stay is not in the program. */
    data class Night(val stay: Stay, val checkIn: Boolean) : FlowEntry
}

/**
 * A way of the day: from entry [from] to entry [to] (indexes into [DayFlowView.entries]). [key] is where its mode and
 * connection are stored: the program item's id, "ride:<TYPE>:<id>" for the way to a departure, "night" for the way to
 * the stay.
 */
data class FlowLeg(val key: String, val from: Int, val to: Int)

data class DayFlowView(val entries: List<FlowEntry>, val legs: List<FlowLeg>) {
    /** The way that ends at entry [index]; null when none does. */
    fun legTo(index: Int): FlowLeg? = legs.firstOrNull { it.to == index }

    fun leg(key: String): FlowLeg? = legs.firstOrNull { it.key == key }
}

/**
 * The day as one flow: last night's stay (with "Check-out" on its day), the program and the day's rides, tonight's stay
 * (with "Check-in"). Rides stand at their time; a program item goes before or after a ride by its region (the region of
 * the stay before vs. the one after), else by its time, else by which end of the ride it is closer to, else with the
 * item before it. Ways link every two points of the flow; none between two rides (a change, see journeys) or between
 * two entries at the same place.
 */
object DayFlow {
    const val NIGHT = "night"

    fun rideKey(m: Movement): String = when (m) {
        is Movement.FlightMove -> "ride:FLIGHT:${m.flight.id}"
        is Movement.TrainMove -> "ride:TRAIN:${m.train.id}"
    }

    fun isDayLegKey(key: String) = key == NIGHT || key.startsWith("ride:")

    /** Last night's stay ([date] is after its check-in, up to its check-out). */
    fun morningStay(stays: List<Stay>, date: String): Stay? =
        stays.filter { it.checkIn.isNotBlank() && it.checkOut.isNotBlank() && it.checkIn < date && date <= it.checkOut }.maxByOrNull { it.checkIn }

    /** Tonight's stay. */
    fun nightStay(stays: List<Stay>, date: String): Stay? =
        stays.filter { it.checkIn.isNotBlank() && it.checkOut.isNotBlank() && it.checkIn <= date && date < it.checkOut }.minByOrNull { it.checkIn }

    fun build(
        date: String,
        items: List<DayItem>,
        movements: List<Movement>,
        stays: List<Stay>,
        savedPlaces: List<SavedPlace>,
        places: Map<String, Place>,
        regions: List<Region>,
        /** Coordinates of a ride's end (its own, its place's, an airport's); null when unknown. */
        endpoint: (Endpoint) -> Pair<Double, Double>? = { e -> e.lat?.let { la -> e.lng?.let { la to it } } },
    ): DayFlowView {
        val morning = morningStay(stays, date)
        val night = nightStay(stays, date)
        val rides = movements
        val segment = segments(items, rides, morning, night, stays, savedPlaces, places, regions, endpoint)

        val checkInItem = night?.takeIf { it.checkIn == date }?.let { st -> items.lastOrNull { it.kind == DayItemKind.PLACE && it.placeId == st.placeId } }
        val entries = mutableListOf<FlowEntry>()
        if (morning != null) entries += FlowEntry.Start(morning, checkOut = morning.checkOut == date)
        for (k in 0..rides.size) {
            items.forEachIndexed { i, item ->
                if (segment[i] == k) entries += FlowEntry.Item(item, checkIn = night.takeIf { item.id == checkInItem?.id })
            }
            if (k < rides.size) entries += FlowEntry.Ride(rides[k])
        }
        if (night != null) entries += FlowEntry.Night(night, checkIn = night.checkIn == date && checkInItem == null)
        return DayFlowView(entries, legs(entries))
    }

    private fun legs(entries: List<FlowEntry>): List<FlowLeg> {
        val legs = mutableListOf<FlowLeg>()
        var last: Int? = null
        fun placeOf(e: FlowEntry): String? = when (e) {
            is FlowEntry.Start -> e.stay.placeId
            is FlowEntry.Item -> e.item.placeId
            is FlowEntry.Night -> e.stay.placeId
            is FlowEntry.Ride -> null
        }
        entries.forEachIndexed { i, e ->
            if (e is FlowEntry.Item && e.item.kind != DayItemKind.PLACE) return@forEachIndexed
            val prev = last?.let { entries[it] }
            val same = prev != null && placeOf(prev) != null && placeOf(prev) == placeOf(e)
            if (prev != null && !(prev is FlowEntry.Ride && e is FlowEntry.Ride) && !same) {
                val key = when (e) {
                    is FlowEntry.Item -> e.item.id
                    is FlowEntry.Ride -> rideKey(e.movement)
                    is FlowEntry.Night -> NIGHT
                    is FlowEntry.Start -> null
                }
                if (key != null) legs += FlowLeg(key, last!!, i)
            }
            last = i
        }
        return legs
    }

    /** For each item the number of rides before it (0..rides.size). */
    private fun segments(
        items: List<DayItem>,
        rides: List<Movement>,
        morning: Stay?,
        night: Stay?,
        stays: List<Stay>,
        savedPlaces: List<SavedPlace>,
        places: Map<String, Place>,
        regions: List<Region>,
        endpoint: (Endpoint) -> Pair<Double, Double>?,
    ): IntArray {
        val result = IntArray(items.size)
        if (rides.isEmpty()) return result
        val home = regions.associate { it.id to (it.dayTripFromRegionId ?: it.id) }
        fun norm(r: String?) = r?.let { home[it] ?: it }
        val before = norm(morning?.regionId)
        val after = norm(night?.regionId)
        val departs = rides.map { mins(it.time) }
        fun ends(m: Movement) = when (m) {
            is Movement.FlightMove -> m.flight.depart to m.flight.arrive
            is Movement.TrainMove -> m.train.depart to m.train.arrive
        }
        // Anchor of segment k: the first departure (k = 0), else the arrival of ride k.
        val anchors = (0..rides.size).map { k -> if (k == 0) endpoint(ends(rides[0]).first) else endpoint(ends(rides[k - 1]).second) }
        var previous = 0
        items.forEachIndexed { i, item ->
            val place = item.placeId?.let { places[it] }
            val region = norm(
                savedPlaces.filter { it.placeId == item.placeId }.minByOrNull { if (it.status == PlaceStatus.DROPPED) 1 else 0 }?.regionId
                    ?: stays.firstOrNull { it.placeId == item.placeId }?.regionId,
            )
            val time = mins(item.time)
            val located = place?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) }
            val seg = when {
                item.kind != DayItemKind.PLACE -> previous
                region != null && before != after && region == before -> 0
                region != null && before != after && region == after -> rides.size
                time != null -> departs.count { it != null && it <= time }
                located != null && anchors.any { it != null } ->
                    anchors.withIndex().filter { it.value != null }
                        .minBy { (_, a) -> PlaceMatch.distanceKm(located.lat, located.lng, a!!.first, a.second) }.index
                else -> previous
            }
            result[i] = seg
            previous = seg
        }
        return result
    }

    /** Minutes of the day of "HH:mm"; null when blank or malformed. */
    fun mins(t: String?): Int? {
        val m = t?.trim()?.let { Regex("^(\\d{1,2}):(\\d{2})").find(it) } ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }
}
