package de.wanderlogplus.app.maps

import de.wanderlogplus.shared.sync.TransitRoute

/** Colors of the rides of a connection, in order (a journey continues the sequence). */
val RIDE_COLORS = listOf("#D32F2F", "#1565C0", "#2E7D32", "#6A1B9A", "#EF6C00", "#00838F")

/** Walks: grey and dashed. */
const val WALK_COLOR = "#8A8A8A"

/** One drawn part of a route: a ride in its color, or a walk. */
data class RouteSegment(val points: List<LatLngD>, val color: String, val walk: Boolean = false)

/** Rides of a connection (walks left out). */
fun rideCount(route: TransitRoute): Int = route.legs.count { !it.walk }

/**
 * The map of a connection: from [fromPlace] (walk) through each ride's stations and the stops with known positions, in
 * [RIDE_COLORS] starting at [colorOffset], to [toPlace] (walk). Big dots at the places and where one boards, changes and
 * leaves; small ones at the stops. Null with fewer than two known points.
 */
fun transitGeometry(route: TransitRoute, fromPlace: LatLngD?, toPlace: LatLngD?, colorOffset: Int = 0): RouteGeometry? {
    val segments = mutableListOf<RouteSegment>()
    val major = mutableListOf<LatLngD>()
    val minor = mutableListOf<LatLngD>()
    fun addMajor(p: LatLngD) { if (major.lastOrNull() != p) major += p }
    var last: LatLngD? = fromPlace
    fromPlace?.let(::addMajor)
    route.legs.filter { !it.walk }.forEachIndexed { i, leg ->
        val a = leg.from.lat?.let { la -> leg.from.lng?.let { LatLngD(la, it) } }
        val b = leg.to.lat?.let { la -> leg.to.lng?.let { LatLngD(la, it) } }
        val stops = leg.stops.mapNotNull { s -> s.lat?.let { la -> s.lng?.let { LatLngD(la, it) } } }
        val start = a ?: stops.firstOrNull() ?: b
        if (start != null && last != null && last != start) segments += RouteSegment(listOf(last!!, start), WALK_COLOR, walk = true)
        val points = listOfNotNull(a) + stops + listOfNotNull(b)
        if (points.size >= 2) segments += RouteSegment(points, RIDE_COLORS[(colorOffset + i) % RIDE_COLORS.size])
        a?.let(::addMajor)
        minor += stops
        b?.let(::addMajor)
        last = b ?: points.lastOrNull() ?: last
    }
    if (toPlace != null) {
        if (last != null && last != toPlace) segments += RouteSegment(listOf(last!!, toPlace), WALK_COLOR, walk = true)
        addMajor(toPlace)
    }
    if (major.size < 2) return null
    return RouteGeometry(major, arc = false, segments = segments.takeIf { it.isNotEmpty() }, minorStops = minor)
}
