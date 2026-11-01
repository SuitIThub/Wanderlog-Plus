package de.wanderlogplus.app.maps

import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Train
import java.security.MessageDigest
import java.util.Locale

data class LatLngD(val lat: Double, val lng: Double)

/** Straight line (train) or arc (flight) between two endpoints; an "as the crow flies" route, not real track geometry. */
data class RouteGeometry(val from: LatLngD, val to: LatLngD, val arc: Boolean) {

    /** 2 points for a straight route; 32 points on a quadratic Bézier arc for a flight. */
    fun points(): List<LatLngD> {
        if (!arc) return listOf(from, to)
        val dLat = to.lat - from.lat
        val dLng = to.lng - from.lng
        // Control point: midpoint, offset perpendicular by 20 % of the distance (perpendicular of (dLng, dLat) is (-dLat, dLng)).
        val cLat = (from.lat + to.lat) / 2 + dLng * ARC_BULGE
        val cLng = (from.lng + to.lng) / 2 - dLat * ARC_BULGE
        return (0 until ARC_POINTS).map { i ->
            val t = i.toDouble() / (ARC_POINTS - 1)
            val u = 1 - t
            LatLngD(
                u * u * from.lat + 2 * u * t * cLat + t * t * to.lat,
                u * u * from.lng + 2 * u * t * cLng + t * t * to.lng,
            )
        }
    }

    /** SHA-256 hex of the coordinates (rounded to 5 decimals), [arc] and the pixel size. */
    fun cacheKey(widthPx: Int, heightPx: Int): String {
        fun r(v: Double) = String.format(Locale.ROOT, "%.5f", v + 0.0) // + 0.0 folds -0.0 into 0.0
        val raw = listOf(r(from.lat), r(from.lng), r(to.lat), r(to.lng), arc.toString(), widthPx.toString(), heightPx.toString())
            .joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ARC_POINTS = 32
        const val ARC_BULGE = 0.2
    }
}

private fun Endpoint.coords(snapshot: TripSnapshot): LatLngD? {
    val p = placeId?.let { snapshot.places[it] } ?: return null
    if (p.lat == 0.0 && p.lng == 0.0) return null
    return LatLngD(p.lat, p.lng)
}

private fun route(depart: Endpoint, arrive: Endpoint, snapshot: TripSnapshot, arc: Boolean): RouteGeometry? {
    val from = depart.coords(snapshot) ?: return null
    val to = arrive.coords(snapshot) ?: return null
    return RouteGeometry(from, to, arc)
}

/** Arc between the flight's endpoint places; null when a coordinate is missing or (0,0). */
fun routeFor(flight: Flight, snapshot: TripSnapshot): RouteGeometry? = route(flight.depart, flight.arrive, snapshot, arc = true)

/** Straight line between the train's endpoint places; null when a coordinate is missing or (0,0). */
fun routeFor(train: Train, snapshot: TripSnapshot): RouteGeometry? = route(train.depart, train.arrive, snapshot, arc = false)
