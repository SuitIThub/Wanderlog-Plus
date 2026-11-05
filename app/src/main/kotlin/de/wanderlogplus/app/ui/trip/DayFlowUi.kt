package de.wanderlogplus.app.ui.trip

import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.maps.airportCoords
import de.wanderlogplus.app.maps.coords
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.query.DayFlow
import de.wanderlogplus.shared.query.DayFlowView
import de.wanderlogplus.shared.query.FlowEntry
import de.wanderlogplus.shared.query.FlowLeg
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.query.movementsOn
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TravelMode

/** The day's flow (stays, program, rides) of [date]. */
fun dayFlowFor(snapshot: TripSnapshot, date: String): DayFlowView {
    val day = snapshot.queries()?.dayFor(date)
    val items = day?.let { snapshot.dayItemsFor(it.id) }.orEmpty()
    return DayFlow.build(
        date, items, movementsOn(snapshot.flights, snapshot.trains, date), snapshot.stays, snapshot.savedPlaces,
        snapshot.places, snapshot.regions,
    ) { e -> e.point(snapshot)?.let { it.lat to it.lng } }
}

private fun Endpoint.point(snapshot: TripSnapshot): LatLngD? = coords(snapshot) ?: airportCoords()

/**
 * One way of the day with what the way row needs: both ends ([from]/[to] null without position), the time it starts
 * at ([fromTime]: the place's planned time or the ride's arrival), mode and picked connection, and [deadline]: the
 * departure the way leads to.
 */
data class DayWay(
    val key: String,
    val from: LatLngD?,
    val to: LatLngD?,
    val fromName: String,
    val toName: String,
    val fromTime: String?,
    val mode: TravelMode,
    val chosen: TransitResult?,
    val deadline: String? = null,
)

private fun ends(m: Movement) = when (m) {
    is Movement.FlightMove -> m.flight.depart to m.flight.arrive
    is Movement.TrainMove -> m.train.depart to m.train.arrive
}

private fun endName(e: Endpoint) = e.name.takeIf { it.isNotBlank() } ?: e.code ?: "Bahnhof"

fun dayWay(snapshot: TripSnapshot, date: String, flow: DayFlowView, leg: FlowLeg): DayWay {
    fun placePoint(placeId: String?): LatLngD? = placeId?.let { snapshot.places[it] }?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) }?.let { LatLngD(it.lat, it.lng) }
    fun placeName(placeId: String?) = placeId?.let { snapshot.places[it] }?.name?.takeIf { it.isNotBlank() } ?: "Ort"
    val a = flow.entries[leg.from]
    val b = flow.entries[leg.to]
    val (from, fromName, fromTime) = when (a) {
        is FlowEntry.Start -> Triple(placePoint(a.stay.placeId), placeName(a.stay.placeId), null)
        is FlowEntry.Item -> Triple(placePoint(a.item.placeId), placeName(a.item.placeId), a.item.time)
        is FlowEntry.Ride -> ends(a.movement).second.let { Triple(it.point(snapshot), endName(it), it.time) }
        is FlowEntry.Night -> Triple(placePoint(a.stay.placeId), placeName(a.stay.placeId), null)
    }
    val (to, toName) = when (b) {
        is FlowEntry.Start -> placePoint(b.stay.placeId) to placeName(b.stay.placeId)
        is FlowEntry.Item -> placePoint(b.item.placeId) to placeName(b.item.placeId)
        is FlowEntry.Ride -> ends(b.movement).first.let { it.point(snapshot) to endName(it) }
        is FlowEntry.Night -> placePoint(b.stay.placeId) to placeName(b.stay.placeId)
    }
    val item = (b as? FlowEntry.Item)?.item
    val stored = if (item == null) snapshot.queries()?.dayFor(date)?.legs?.get(leg.key) else null
    return DayWay(
        leg.key, from, to, fromName, toName, fromTime?.takeIf { it.isNotBlank() },
        mode = item?.legMode ?: stored?.mode ?: TravelMode.WALK,
        chosen = item?.legTransit ?: stored?.transit,
        deadline = (b as? FlowEntry.Ride)?.movement?.time?.takeIf { it.isNotBlank() },
    )
}

/** The way of [date] stored under [key]; null when the day has no such way. */
fun dayWay(snapshot: TripSnapshot, date: String, key: String): DayWay? {
    val flow = dayFlowFor(snapshot, date)
    return flow.leg(key)?.let { dayWay(snapshot, date, flow, it) }
}

/** "Reicht nicht bis zur Abfahrt um 11:33" when leaving at [fromTime] and travelling [seconds] misses [deadline]. */
fun deadlineWarning(fromTime: String?, seconds: Long?, deadline: String?): String? {
    val start = DayFlow.mins(fromTime) ?: return null
    val end = DayFlow.mins(deadline) ?: return null
    val way = seconds?.let { ((it + 59) / 60).toInt() } ?: return null
    return if (start + way > end) "Reicht nicht bis zur Abfahrt um $deadline" else null
}
