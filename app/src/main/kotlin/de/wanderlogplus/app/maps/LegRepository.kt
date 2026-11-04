package de.wanderlogplus.app.maps

import androidx.compose.runtime.staticCompositionLocalOf
import de.wanderlogplus.client.ApiErrorKind
import de.wanderlogplus.client.ApiException
import de.wanderlogplus.client.Polyline
import de.wanderlogplus.client.SyncApi
import de.wanderlogplus.shared.sync.RouteInfo
import de.wanderlogplus.shared.sync.RouteRequest
import de.wanderlogplus.shared.sync.TravelMode
import de.wanderlogplus.shared.sync.TransitPoint
import de.wanderlogplus.shared.sync.TransitRequest
import de.wanderlogplus.shared.sync.TransitResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Outcome of asking for the way between two places of a day. */
sealed interface LegResult {
    /**
     * A route: travel time, length and its [path] (decoded polyline; empty if the API sent none). For public transport
     * in Japan [transit] holds the connection (first route = the one shown); time and length include the walks.
     */
    data class Route(val durationSeconds: Long, val distanceMeters: Long, val path: List<LatLngD>, val transit: TransitResult? = null) : LegResult
    /** The mode has no route here (e.g. no transit connection). */
    data object NoRoute : LegResult
    /** The server has no Google Routes key. */
    data object NotConfigured : LegResult
    /** No connection right now and nothing cached; asking again later may work. */
    data object Offline : LegResult
    /** Anything else went wrong. */
    data object Failed : LegResult
}

/** Leg lookups for the day timeline; null in tests (legs then show without times). */
val LocalLegRepository = staticCompositionLocalOf<LegRepository?> { null }

/**
 * Ways between two places, via the sync server's route endpoint (Google Routes API). Results – including "no route" –
 * are kept in memory and as small JSON files in [dir], so a leg seen once is available offline afterwards.
 */
class LegRepository(private val api: suspend () -> SyncApi?, private val dir: File) {
    private val memory = ConcurrentHashMap<String, LegResult>()
    private val json = Json { ignoreUnknownKeys = true }

    /** The in-memory result, without disk or network; for the first frame. */
    fun peek(from: LatLngD, to: LatLngD, mode: TravelMode): LegResult? = memory[key(from, to, mode)]

    suspend fun leg(from: LatLngD, to: LatLngD, mode: TravelMode): LegResult {
        val k = key(from, to, mode)
        memory[k]?.let { return it }
        readDisk(k)?.let { memory[k] = it; return it }
        val api = api() ?: return LegResult.NotConfigured
        val result = try {
            api.computeRoute(RouteRequest(from.lat, from.lng, to.lat, to.lng, mode))?.toResult() ?: LegResult.NoRoute
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            when (e.kind) {
                ApiErrorKind.NOT_CONFIGURED -> LegResult.NotConfigured
                ApiErrorKind.NETWORK -> LegResult.Offline
                else -> LegResult.Failed
            }
        } catch (e: Exception) {
            LegResult.Failed
        }
        // Only real answers are kept: errors may go away (key added, connection back).
        if (result is LegResult.Route || result == LegResult.NoRoute) {
            memory[k] = result
            writeDisk(k, result)
        }
        return result
    }

    /** Like [peek], but a transit way at a known [datetime] uses the timetable connection ([chosen] when picked). */
    fun peekWay(from: LatLngD, to: LatLngD, mode: TravelMode, datetime: String?, chosen: TransitResult?): LegResult? = when {
        mode != TravelMode.TRANSIT || (datetime == null && chosen == null) -> peek(from, to, mode)
        chosen != null -> transitRoute(from, to, chosen) ?: LegResult.NoRoute
        else -> memory[transitKey(from, to, datetime!!)]
    }

    /**
     * The way for a day: public transport with a time asks the timetable search (cached in memory and on disk like
     * the others); without a timetable search on the server (no Apify token) it falls back to [leg].
     */
    suspend fun way(from: LatLngD, to: LatLngD, mode: TravelMode, datetime: String?, chosen: TransitResult?): LegResult {
        if (mode != TravelMode.TRANSIT || (datetime == null && chosen == null)) return leg(from, to, mode)
        if (chosen != null) return transitRoute(from, to, chosen) ?: LegResult.NoRoute
        val k = transitKey(from, to, datetime!!)
        memory[k]?.let { return it }
        readTransitDisk(k, from, to)?.let { memory[k] = it; return it }
        val api = api() ?: return LegResult.NotConfigured
        val found = try {
            api.transit(TransitRequest(TransitPoint(from.lat, from.lng), TransitPoint(to.lat, to.lng), datetime = datetime))
        } catch (e: CancellationException) {
            throw e
        } catch (e: ApiException) {
            return when (e.kind) {
                ApiErrorKind.NOT_CONFIGURED -> leg(from, to, mode)
                ApiErrorKind.NETWORK -> LegResult.Offline
                else -> LegResult.Failed
            }
        } catch (e: Exception) {
            return LegResult.Failed
        }
        val result = found?.let { transitRoute(from, to, it) } ?: LegResult.NoRoute
        memory[k] = result
        writeTransitDisk(k, found)
        return result
    }

    /** Forgets the stored connection between the two points at [datetime] (after "Verbindung abfragen" changed it). */
    fun forgetTransit(from: LatLngD, to: LatLngD, datetime: String) {
        val k = transitKey(from, to, datetime)
        memory.remove(k)
        File(dir, "$k.json").delete()
    }

    private suspend fun readTransitDisk(k: String, from: LatLngD, to: LatLngD): LegResult? = withContext(Dispatchers.IO) {
        val f = File(dir, "$k.json")
        if (!f.isFile) return@withContext null
        try {
            val text = f.readText()
            if (text == "{}") LegResult.NoRoute else transitRoute(from, to, json.decodeFromString(TransitResult.serializer(), text)) ?: LegResult.NoRoute
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    private suspend fun writeTransitDisk(k: String, r: TransitResult?) = withContext(Dispatchers.IO) {
        try {
            dir.mkdirs()
            val tmp = File(dir, "$k.tmp")
            tmp.writeText(r?.let { json.encodeToString(TransitResult.serializer(), it) } ?: "{}")
            if (!tmp.renameTo(File(dir, "$k.json"))) tmp.delete()
        } catch (e: Exception) {
        }
    }

    private fun RouteInfo.toResult() = LegResult.Route(
        durationSeconds,
        distanceMeters,
        Polyline.decode(polyline).map { (lat, lng) -> LatLngD(lat, lng) },
    )

    private suspend fun readDisk(k: String): LegResult? = withContext(Dispatchers.IO) {
        val f = File(dir, "$k.json")
        if (!f.isFile) return@withContext null
        try {
            val text = f.readText()
            if (text == "{}") LegResult.NoRoute else json.decodeFromString(RouteInfo.serializer(), text).toResult()
        } catch (e: Exception) {
            f.delete()
            null
        }
    }

    private suspend fun writeDisk(k: String, r: LegResult) = withContext(Dispatchers.IO) {
        try {
            dir.mkdirs()
            val text = when (r) {
                is LegResult.Route -> json.encodeToString(
                    RouteInfo.serializer(),
                    RouteInfo(r.durationSeconds, r.distanceMeters, encode(r.path)),
                )
                else -> "{}"
            }
            val tmp = File(dir, "$k.tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(File(dir, "$k.json"))) tmp.delete()
        } catch (e: Exception) {
            // A missing cache file only costs a new request later.
        }
    }

    companion object {
        /** Walking pace for the ways to and from the stations (about 4.3 km/h). */
        private const val WALK_METERS_PER_SECOND = 1.2

        fun transitKey(from: LatLngD, to: LatLngD, datetime: String): String = key(from, to, TravelMode.TRANSIT).let { base ->
            MessageDigest.getInstance("SHA-256").digest("$base|$datetime".toByteArray()).joinToString("") { "%02x".format(it) }
        }

        /** The first connection of [t] as a leg: walks to/from the stations added; path through the stations of each leg. */
        fun transitRoute(from: LatLngD, to: LatLngD, t: TransitResult): LegResult.Route? {
            val r = t.routes.firstOrNull() ?: return null
            val walk = (t.walkToStationMeters ?: 0) + (t.walkFromStationMeters ?: 0)
            val stations = (listOf(t.fromStation) + r.legs.flatMap { listOf(it.from, it.to) } + listOf(t.toStation))
                .mapNotNull { s -> if (s.lat != null && s.lng != null) LatLngD(s.lat!!, s.lng!!) else null }
            val path = (listOf(from) + stations + listOf(to)).fold(mutableListOf<LatLngD>()) { acc, p -> if (acc.lastOrNull() != p) acc += p; acc }
            return LegResult.Route(
                durationSeconds = r.durationMinutes * 60L + (walk / WALK_METERS_PER_SECOND).toLong(),
                distanceMeters = ((r.distanceKm ?: 0.0) * 1000).toLong() + walk,
                path = path,
                transit = t,
            )
        }

        fun key(from: LatLngD, to: LatLngD, mode: TravelMode): String {
            fun r(v: Double) = String.format(Locale.ROOT, "%.5f", v + 0.0)
            val raw = listOf(mode.name, r(from.lat), r(from.lng), r(to.lat), r(to.lng)).joinToString("|")
            return MessageDigest.getInstance("SHA-256").digest(raw.toByteArray()).joinToString("") { "%02x".format(it) }
        }

        /** Google polyline encoding (precision 5), the inverse of [Polyline.decode]. */
        fun encode(path: List<LatLngD>): String {
            val sb = StringBuilder()
            var pLat = 0
            var pLng = 0
            fun enc(v: Int) {
                var x = if (v < 0) (v shl 1).inv() else v shl 1
                while (x >= 0x20) {
                    sb.append(((0x20 or (x and 0x1f)) + 63).toChar())
                    x = x shr 5
                }
                sb.append((x + 63).toChar())
            }
            for (p in path) {
                val lat = Math.round(p.lat * 1e5).toInt()
                val lng = Math.round(p.lng * 1e5).toInt()
                enc(lat - pLat); enc(lng - pLng)
                pLat = lat; pLng = lng
            }
            return sb.toString()
        }
    }
}
