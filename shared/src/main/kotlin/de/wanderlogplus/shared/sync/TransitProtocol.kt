package de.wanderlogplus.shared.sync

import kotlinx.serialization.Serializable

/** A coordinate with an optional label (the program place a way starts or ends at). */
@Serializable
data class TransitPoint(val lat: Double, val lng: Double, val name: String = "")

/**
 * Body of `POST /api/transit`: a public-transport connection in Japan. Stations are found near [from]/[to] unless
 * [fromStation]/[toStation] (Japanese names, e.g. 中津川) are given. [datetime] is Japan time, ISO "2027-05-20T08:00".
 * [fresh] skips the server cache (a new paid request).
 */
@Serializable
data class TransitRequest(
    val from: TransitPoint? = null,
    val to: TransitPoint? = null,
    val fromStation: String? = null,
    val toStation: String? = null,
    val datetime: String,
    /** "depart" (leave at [datetime]) or "arrive" (be there by it). */
    val searchType: String = "depart",
    /** "fastest", "cheapest" or "fewest_transfers". */
    val preference: String = "fastest",
    val useShinkansen: Boolean = true,
    val useLimitedExpress: Boolean = true,
    val useHighwayBus: Boolean = true,
    val useLocalBus: Boolean = true,
    val fresh: Boolean = false,
)

/** A station: Japanese name (what the timetable search understands), Latin name for display, position when known. */
@Serializable
data class TransitStation(val nameJa: String, val name: String = "", val lat: Double? = null, val lng: Double? = null)

/** An intermediate stop of a leg. */
@Serializable
data class TransitStop(val nameJa: String, val name: String = "", val time: String = "")

/** One ride (or walk between stations) of a connection; [line] is a readable version of [lineJa]. */
@Serializable
data class TransitLeg(
    val lineJa: String,
    val line: String = "",
    val headsignJa: String = "",
    val headsign: String = "",
    val walk: Boolean = false,
    val from: TransitStation,
    val to: TransitStation,
    val departure: String = "",
    val arrival: String = "",
    val departurePlatform: String? = null,
    val arrivalPlatform: String? = null,
    val stops: List<TransitStop> = emptyList(),
)

/** A supplement of the fare, e.g. 自由席 (non-reserved seat) for a Shinkansen part. */
@Serializable
data class TransitFee(val labelJa: String, val label: String = "", val yen: Int)

/** One connection option. Fares in yen for one adult, non-reserved seats; times "HH:MM" Japan time. */
@Serializable
data class TransitRoute(
    val departure: String,
    val arrival: String,
    val durationMinutes: Int,
    val ridingMinutes: Int = 0,
    val transfers: Int = 0,
    val fareYen: Int = 0,
    val baseFareYen: Int = 0,
    val expressFareYen: Int = 0,
    val fees: List<TransitFee> = emptyList(),
    val distanceKm: Double? = null,
    val fastest: Boolean = false,
    val cheapest: Boolean = false,
    val fewestTransfers: Boolean = false,
    val legs: List<TransitLeg> = emptyList(),
    /** Station-to-station part only; [TransitResult] carries the walks to and from the stations. */
    val date: String = "",
)

/**
 * Answer of `POST /api/transit`: the stations used, up to three connections and when they were fetched (epoch ms).
 * [walkToStationMeters]/[walkFromStationMeters]: straight-line distance between the places and their stations.
 * [stationWarning]: the timetable matched a station far from the requested one (same name elsewhere).
 */
@Serializable
data class TransitResult(
    val fromStation: TransitStation,
    val toStation: TransitStation,
    val datetime: String,
    val searchType: String = "depart",
    val routes: List<TransitRoute> = emptyList(),
    val fetchedAt: Long = 0,
    val walkToStationMeters: Int? = null,
    val walkFromStationMeters: Int? = null,
    val stationWarning: String? = null,
)

/** Stations near a point, nearest first (`GET /api/transit/stations`). */
@Serializable
data class TransitStations(val stations: List<TransitStationNear> = emptyList())

@Serializable
data class TransitStationNear(val station: TransitStation, val meters: Int)
