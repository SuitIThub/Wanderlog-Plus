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

/**
 * Airport reference points by IATA code, for flights whose endpoints have no place with coordinates (Wanderlog often
 * stores only the code). Small on purpose: the airports of the trip plus a few common Japanese ones.
 */
internal val AIRPORTS: Map<String, LatLngD> = mapOf(
    "FRA" to LatLngD(50.0333, 8.5706), // Frankfurt
    "HND" to LatLngD(35.5494, 139.7798), // Tokio-Haneda
    "NRT" to LatLngD(35.7720, 140.3929), // Tokio-Narita
    "KIX" to LatLngD(34.4347, 135.2440), // Osaka-Kansai
    "ITM" to LatLngD(34.7855, 135.4382), // Osaka-Itami
    "NGO" to LatLngD(34.8584, 136.8054), // Nagoya-Chubu
    "HIJ" to LatLngD(34.4361, 132.9194), // Hiroshima
    "FUK" to LatLngD(33.5859, 130.4511), // Fukuoka
    "CTS" to LatLngD(42.7752, 141.6923), // Sapporo-New Chitose
    "OKA" to LatLngD(26.1958, 127.6459), // Okinawa-Naha
    "TPE" to LatLngD(25.0777, 121.2328), // Taipeh-Taoyuan
    "HKD" to LatLngD(41.7700, 140.8219), // Hakodate
    "SDJ" to LatLngD(38.1397, 140.9170), // Sendai
    "KOJ" to LatLngD(31.8034, 130.7194), // Kagoshima
)

/** The airport of [Endpoint.code] from [AIRPORTS] (case-insensitive), or null. */
private fun Endpoint.airportCoords(): LatLngD? = code?.trim()?.uppercase(Locale.ROOT)?.let { AIRPORTS[it] }

private fun route(depart: Endpoint, arrive: Endpoint, snapshot: TripSnapshot, arc: Boolean): RouteGeometry? {
    val from = depart.coords(snapshot) ?: return null
    val to = arrive.coords(snapshot) ?: return null
    return RouteGeometry(from, to, arc)
}

/**
 * Arc between the flight's endpoints: each end uses its place's coordinates, otherwise its IATA code from [AIRPORTS];
 * null when an end has neither.
 */
fun routeFor(flight: Flight, snapshot: TripSnapshot): RouteGeometry? {
    val from = flight.depart.coords(snapshot) ?: flight.depart.airportCoords() ?: return null
    val to = flight.arrive.coords(snapshot) ?: flight.arrive.airportCoords() ?: return null
    return RouteGeometry(from, to, arc = true)
}

/** Straight line between the train's endpoint places; null when a coordinate is missing or (0,0). */
fun routeFor(train: Train, snapshot: TripSnapshot): RouteGeometry? = route(train.depart, train.arrive, snapshot, arc = false)
