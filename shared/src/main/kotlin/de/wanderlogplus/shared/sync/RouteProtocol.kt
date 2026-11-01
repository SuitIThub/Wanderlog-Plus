package de.wanderlogplus.shared.sync

import kotlinx.serialization.Serializable

/** Ways to get from one place to the next; names match the Google Routes API travel modes. */
enum class TravelMode { WALK, BICYCLE, DRIVE, TRANSIT }

/** Body of `POST /api/routes`: a route between two coordinates. */
@Serializable
data class RouteRequest(
    val fromLat: Double,
    val fromLng: Double,
    val toLat: Double,
    val toLng: Double,
    val mode: TravelMode,
)

/** A computed route: travel time, length and the path as an encoded polyline (Google polyline algorithm, precision 5). */
@Serializable
data class RouteInfo(val durationSeconds: Long, val distanceMeters: Long, val polyline: String)
