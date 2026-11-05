package de.wanderlogplus.app.maps

import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Train
import java.security.MessageDigest
import java.util.Locale

data class LatLngD(val lat: Double, val lng: Double)

/**
 * Version of the route snapshot rendering, part of every cache key. Raising it orphans all cached PNGs, so a changed
 * renderer (or a bug that cached bad pictures) never keeps showing old snapshots.
 */
const val RENDER_VERSION = 1

/**
 * Route through [stops] (start, stopovers, destination): straight segments (train) or one arc per segment (flight),
 * unless a real [path] is known (a computed walking/transit route), which is then drawn instead. Without a path this is
 * an "as the crow flies" picture, not real track geometry.
 */
data class RouteGeometry(val stops: List<LatLngD>, val arc: Boolean, val path: List<LatLngD>? = null) {
    init {
        require(stops.size >= 2) { "A route needs at least two stops" }
    }

    constructor(from: LatLngD, to: LatLngD, arc: Boolean) : this(listOf(from, to), arc)

    val from: LatLngD get() = stops.first()
    val to: LatLngD get() = stops.last()

    /** The [path] if known; else the stops joined by straight lines, or 32-point quadratic Bézier arcs per segment for a flight. */
    fun points(): List<LatLngD> {
        path?.takeIf { it.size >= 2 }?.let { return it }
        if (!arc) return stops
        return stops.zipWithNext().flatMapIndexed { seg, (a, b) -> arcPoints(a, b).let { if (seg == 0) it else it.drop(1) } }
    }

    private fun arcPoints(from: LatLngD, to: LatLngD): List<LatLngD> {
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

    /** SHA-256 hex of the render [version], all stops and path points (rounded to 5 decimals), [arc] and the pixel size. */
    fun cacheKey(widthPx: Int, heightPx: Int, version: Int = RENDER_VERSION): String {
        fun r(v: Double) = String.format(Locale.ROOT, "%.5f", v + 0.0) // + 0.0 folds -0.0 into 0.0
        val coords = (stops + listOf(LatLngD(Double.NaN, Double.NaN)) + path.orEmpty()).flatMap { listOf(r(it.lat), r(it.lng)) }
        val raw = (listOf("v$version") + coords + listOf(arc.toString(), widthPx.toString(), heightPx.toString())).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val ARC_POINTS = 32
        const val ARC_BULGE = 0.2
    }
}

/** The endpoint's own coordinates (picked in the app) or its place's; null without usable coordinates. */
fun Endpoint.coords(snapshot: TripSnapshot): LatLngD? {
    val la = lat
    val ln = lng
    if (la != null && ln != null && !(la == 0.0 && ln == 0.0)) return LatLngD(la, ln)
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
internal fun Endpoint.airportCoords(): LatLngD? = code?.trim()?.uppercase(Locale.ROOT)?.let { AIRPORTS[it] }

/**
 * Arc through the flight's airports: each end and stopover uses its own coordinates, its place's, or its IATA code from
 * [AIRPORTS]; stopovers without any are skipped, null when start or destination has none.
 */
fun routeFor(flight: Flight, snapshot: TripSnapshot): RouteGeometry? {
    fun c(e: Endpoint) = e.coords(snapshot) ?: e.airportCoords()
    val from = c(flight.depart) ?: return null
    val to = c(flight.arrive) ?: return null
    return RouteGeometry(listOf(from) + flight.via.mapNotNull { c(it) } + listOf(to), arc = true)
}

/** Straight lines through the train's start, stopovers and destination; stopovers without coordinates are skipped. */
fun routeFor(train: Train, snapshot: TripSnapshot): RouteGeometry? {
    val from = train.depart.coords(snapshot) ?: return null
    val to = train.arrive.coords(snapshot) ?: return null
    return RouteGeometry(listOf(from) + train.via.mapNotNull { it.coords(snapshot) } + listOf(to), arc = false)
}

/** One line through all legs of a journey (flight arcs and train lines joined); null when no leg has coordinates. */
fun routeFor(journey: de.wanderlogplus.client.Journey, snapshot: TripSnapshot): RouteGeometry? {
    val parts = journey.legs.mapNotNull { l ->
        when (val m = l.movement) {
            is de.wanderlogplus.shared.query.Movement.FlightMove -> routeFor(m.flight, snapshot)
            is de.wanderlogplus.shared.query.Movement.TrainMove -> routeFor(m.train, snapshot)
        }
    }
    if (parts.isEmpty()) return null
    val stops = parts.flatMap { it.stops }.fold(mutableListOf<LatLngD>()) { acc, p -> if (acc.lastOrNull() != p) acc += p; acc }
    if (stops.size < 2) return null
    return RouteGeometry(stops, arc = false, path = parts.flatMap { it.points() })
}
