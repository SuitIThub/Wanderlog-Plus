package de.wanderlogplus.client

import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.sync.EntityType
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** One train or flight of a journey, with its departure and arrival (local time) when known. */
data class JourneyLeg(val movement: Movement, val departs: LocalDateTime?, val arrives: LocalDateTime?) {
    val type: EntityType get() = if (movement is Movement.FlightMove) EntityType.FLIGHT else EntityType.TRAIN
    val id: String get() = when (movement) {
        is Movement.FlightMove -> movement.flight.id
        is Movement.TrainMove -> movement.train.id
    }
    val depart: Endpoint get() = when (movement) {
        is Movement.FlightMove -> movement.flight.depart
        is Movement.TrainMove -> movement.train.depart
    }
    val arrive: Endpoint get() = when (movement) {
        is Movement.FlightMove -> movement.flight.arrive
        is Movement.TrainMove -> movement.train.arrive
    }
    val via: List<Endpoint> get() = when (movement) {
        is Movement.FlightMove -> movement.flight.via
        is Movement.TrainMove -> movement.train.via
    }
}

/** Trains and flights sharing a journey id, in travel order (two or more). */
data class Journey(val id: String, val legs: List<JourneyLeg>) {
    val first: JourneyLeg get() = legs.first()
    val last: JourneyLeg get() = legs.last()

    /** Minutes from the first departure to the last arrival; null when a time is missing. */
    val totalMinutes: Long? get() = first.departs?.let { d -> last.arrives?.let { ChronoUnit.MINUTES.between(d, it) } }

    fun indexOf(type: EntityType, id: String): Int = legs.indexOfFirst { it.type == type && it.id == id }
}

/**
 * The change between two legs: waiting time (arrival of [from] to departure of [to]), whether it is the same place
 * (same name or code, or closer than [SAME_PLACE_KM]), the straight-line distance otherwise, the platform/terminal
 * known from the data, and warnings.
 */
data class Transfer(
    val minutes: Long?,
    val samePlace: Boolean,
    val distanceKm: Double?,
    val fromSpot: String?,
    val toSpot: String?,
    val tight: Boolean,
    val overlaps: Boolean,
) {
    companion object {
        const val SAME_PLACE_KM = 0.3
        const val TIGHT_TRAINS = 8L
        const val TIGHT_FLIGHT = 60L
    }
}

fun movementOf(f: Flight): Movement = Movement.FlightMove(f)
fun movementOf(t: Train): Movement = Movement.TrainMove(t)

private fun at(e: Endpoint, fallbackDate: String?): LocalDateTime? {
    val d = (e.date ?: fallbackDate)?.take(10)?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: return null
    val t = e.time?.trim()?.takeIf { it.length >= 4 }?.split(':')?.let { p -> p[0].toIntOrNull()?.let { h -> h * 60 + (p.getOrNull(1)?.take(2)?.toIntOrNull() ?: 0) } }
    return d.atStartOfDay().plusMinutes((t ?: 0).toLong())
}

fun legOf(m: Movement): JourneyLeg {
    val (dep, arr) = when (m) {
        is Movement.FlightMove -> m.flight.depart to m.flight.arrive
        is Movement.TrainMove -> m.train.depart to m.train.arrive
    }
    val departs = at(dep, null)?.takeIf { dep.time != null }
    var arrives = at(arr, dep.date)?.takeIf { arr.time != null }
    // An arrival "earlier" on the same (unknown) date is the next day.
    if (departs != null && arrives != null && arr.date == null && arrives < departs) arrives = arrives.plusDays(1)
    return JourneyLeg(m, departs, arrives)
}

private fun Movement.journeyId(): String? = when (this) {
    is Movement.FlightMove -> flight.journeyId
    is Movement.TrainMove -> train.journeyId
}

private val legOrder: Comparator<JourneyLeg> = compareBy<JourneyLeg> { it.departs?.toString() ?: it.depart.date ?: "9999" }.thenBy { it.id }

/** Every journey of the trip (only ids with at least two legs), by first departure. */
fun journeys(snapshot: TripSnapshot): List<Journey> =
    (snapshot.flights.map(::movementOf) + snapshot.trains.map(::movementOf))
        .filter { !it.journeyId().isNullOrBlank() }
        .groupBy { it.journeyId()!! }
        .filterValues { it.size >= 2 }
        .map { (id, ms) -> Journey(id, ms.map(::legOf).sortedWith(legOrder)) }
        .sortedBy { it.first.departs?.toString() ?: it.first.depart.date ?: "9999" }

/** The journey a train or flight belongs to, or null. */
fun journeyOf(snapshot: TripSnapshot, type: EntityType, id: String): Journey? =
    journeys(snapshot).firstOrNull { j -> j.indexOf(type, id) >= 0 }

private fun km(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
    val dLat = Math.toRadians(bLat - aLat)
    val dLng = Math.toRadians(bLng - aLng)
    val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * 6371.0 * asin(sqrt(h))
}

/** Coordinates of an endpoint: its own, else those of its place; null when unknown. */
fun endpointPoint(snapshot: TripSnapshot, e: Endpoint): Pair<Double, Double>? =
    if (e.lat != null && e.lng != null) e.lat!! to e.lng!!
    else e.placeId?.let { snapshot.places[it] }?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) }?.let { it.lat to it.lng }

/** "Gleis 14" where a train arrives / leaves (from its taken-over connection). */
private fun platform(m: Movement, arriving: Boolean): String? {
    val t = (m as? Movement.TrainMove)?.train ?: return null
    val rides = t.transit?.routes?.firstOrNull()?.legs?.filter { !it.walk }.orEmpty()
    val p = if (arriving) rides.lastOrNull()?.arrivalPlatform else rides.firstOrNull()?.departurePlatform
    return p?.takeIf { it.isNotBlank() }?.let { "Gleis $it" }
}

fun transfer(snapshot: TripSnapshot, from: JourneyLeg, to: JourneyLeg): Transfer {
    val minutes = from.arrives?.let { a -> to.departs?.let { d -> ChronoUnit.MINUTES.between(a, d) } }
    val a = from.arrive
    val b = to.depart
    val sameName = (a.code != null && a.code.equals(b.code, ignoreCase = true)) || (a.name.isNotBlank() && a.name.trim().equals(b.name.trim(), ignoreCase = true))
    val pa = endpointPoint(snapshot, a)
    val pb = endpointPoint(snapshot, b)
    val dist = if (pa != null && pb != null) km(pa.first, pa.second, pb.first, pb.second) else null
    val same = sameName || (dist != null && dist < Transfer.SAME_PLACE_KM)
    val flight = from.movement is Movement.FlightMove || to.movement is Movement.FlightMove
    val limit = if (flight) Transfer.TIGHT_FLIGHT else Transfer.TIGHT_TRAINS
    return Transfer(
        minutes = minutes,
        samePlace = same,
        distanceKm = dist?.takeIf { !same },
        fromSpot = platform(from.movement, arriving = true),
        toSpot = platform(to.movement, arriving = false),
        tight = minutes != null && minutes in 0 until limit,
        overlaps = minutes != null && minutes < 0,
    )
}

/** "2 Std. 10 Min." / "45 Min.". */
fun durationText(minutes: Long): String = when {
    minutes < 60 -> "$minutes Min."
    minutes % 60 == 0L -> "${minutes / 60} Std."
    else -> "${minutes / 60} Std. ${minutes % 60} Min."
}
