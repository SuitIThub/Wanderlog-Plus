package de.wanderlogplus.app.ui.trip

import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.maps.LegResult
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.sync.TravelMode

/** A place of the day's program on the map: [number] is its 1-based position among the places. */
data class RouteStop(val itemId: String, val number: Int, val name: String, val at: LatLngD)

/** The way between two consecutive stops in the mode chosen for it; [result] null while loading. */
data class RouteSegment(val from: RouteStop, val to: RouteStop, val mode: TravelMode, val result: LegResult? = null) {
    /** The drawn line: the computed route's path, else the straight line. */
    val path: List<LatLngD>
        get() = (result as? LegResult.Route)?.path?.takeIf { it.size >= 2 } ?: listOf(from.at, to.at)

    /** True when only the straight line is known (no route, not loaded, error). */
    val straight: Boolean get() = (result as? LegResult.Route)?.path?.takeIf { it.size >= 2 } == null
}

/** The program's places with coordinates, in program order (notes and places without position are skipped). */
fun routeStops(snapshot: TripSnapshot, date: String): List<RouteStop> {
    val day = snapshot.queries()?.dayFor(date) ?: return emptyList()
    return snapshot.dayItemsFor(day.id)
        .filter { it.kind == DayItemKind.PLACE }
        .mapNotNull { item -> snapshot.places[item.placeId]?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) }?.let { item to it } }
        .mapIndexed { i, (item, place) -> RouteStop(item.id, i + 1, place.name.ifBlank { "Ort" }, LatLngD(place.lat, place.lng)) }
}

/** Segments between consecutive stops; each uses the mode stored on its destination item (default on foot). */
fun routeSegments(snapshot: TripSnapshot, stops: List<RouteStop>): List<RouteSegment> =
    stops.zipWithNext { a, b ->
        val mode = snapshot.dayItems.firstOrNull { it.id == b.itemId }?.legMode ?: TravelMode.WALK
        RouteSegment(a, b, mode)
    }

/** Sum of the computed segments: time, distance, segments answered without a route, and segments still loading. */
data class RouteTotals(val seconds: Long, val meters: Long, val missing: Int, val loading: Int = 0)

fun routeTotals(segments: List<RouteSegment>): RouteTotals {
    val routes = segments.mapNotNull { it.result as? LegResult.Route }
    val loading = segments.count { it.result == null }
    return RouteTotals(routes.sumOf { it.durationSeconds }, routes.sumOf { it.distanceMeters }, segments.size - routes.size - loading, loading)
}
