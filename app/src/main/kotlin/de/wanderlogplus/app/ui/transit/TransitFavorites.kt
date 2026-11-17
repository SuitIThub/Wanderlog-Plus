package de.wanderlogplus.app.ui.transit

import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.ui.places.distanceKm
import de.wanderlogplus.app.ui.places.nearestRegion
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.FavoriteEnd
import de.wanderlogplus.shared.model.TransitFavorite
import de.wanderlogplus.shared.sync.TransitPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Two ends closer than this count as the same place. */
private const val SAME_KM = 0.15

/** The same place: the same station, else positions within 150 m, else (both without position) the same name. */
fun FavoriteEnd.sameAs(o: FavoriteEnd): Boolean {
    if (station.isNotBlank() && station == o.station) return true
    val a = lat?.let { la -> lng?.let { LatLngD(la, it) } }
    val b = o.lat?.let { la -> o.lng?.let { LatLngD(la, it) } }
    if (a != null && b != null) return distanceKm(a, b) <= SAME_KM
    return a == null && b == null && name.isNotBlank() && name.trim() == o.name.trim() && station.isBlank() && o.station.isBlank()
}

/** The end of the search form: a picked place (with the station chosen below it, if any) or a typed station. */
fun formEnd(point: TransitPoint?, station: String): FavoriteEnd? {
    val st = station.trim()
    return when {
        point != null -> FavoriteEnd(point.name.ifBlank { st }, point.lat, point.lng, st)
        st.isNotEmpty() -> FavoriteEnd(st, station = st)
        else -> null
    }
}

/** The goal favourite for [end], if there is one. */
fun TripSnapshot.placeFavorite(end: FavoriteEnd?): TransitFavorite? =
    end?.let { e -> transitFavorites.firstOrNull { it.kind == TransitFavorite.KIND_PLACE && it.to.sameAs(e) } }

/** The route favourite [from] → [to], if there is one. */
fun TripSnapshot.routeFavorite(from: FavoriteEnd?, to: FavoriteEnd?): TransitFavorite? =
    if (from == null || to == null) null
    else transitFavorites.firstOrNull { it.kind == TransitFavorite.KIND_ROUTE && it.from?.sameAs(from) == true && it.to.sameAs(to) }

/**
 * Favourites for "Heute" in [regionId]: a goal whose position lies in the region, a route with an end there. A
 * day-trip region counts for its home region and the other way round. Positionless ends (stations) never match.
 */
fun favoritesForRegion(snapshot: TripSnapshot, regionId: String?): List<TransitFavorite> {
    if (regionId == null) return emptyList()
    val byId = snapshot.regions.associateBy { it.id }
    fun home(r: String?) = r?.let { byId[it]?.dayTripFromRegionId ?: it }
    val today = home(regionId)
    fun matches(e: FavoriteEnd?): Boolean {
        val la = e?.lat ?: return false
        val ln = e.lng ?: return false
        return home(nearestRegion(snapshot, la, ln)) == today
    }
    return snapshot.transitFavorites.filter { f -> matches(f.to) || (f.kind == TransitFavorite.KIND_ROUTE && matches(f.from)) }
}

/** A searched route of this phone's history. */
@Serializable
data class HistoryRoute(val from: FavoriteEnd, val to: FavoriteEnd)

/** The last [MAX] searched routes of this phone, newest first, kept in [file] (never synchronised). */
class TransitHistory(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(HistoryRoute.serializer())

    fun read(): List<HistoryRoute> =
        runCatching { if (file.exists()) json.decodeFromString(serializer, file.readText()) else emptyList() }.getOrDefault(emptyList())

    /** Puts [route] first (dropping the same route further down) and keeps the newest [MAX]. */
    @Synchronized
    fun add(route: HistoryRoute) {
        val rest = read().filterNot { it.from.sameAs(route.from) && it.to.sameAs(route.to) }
        val list = (listOf(route) + rest).take(MAX)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(serializer, list))
        }
    }

    companion object {
        const val MAX = 10
    }
}

/** [base] suggestions plus the places of this phone's last searches whose name contains the query (those first). */
fun withHistory(base: EndpointSearch?, history: List<HistoryRoute>): EndpointSearch? {
    val ends = history.flatMap { listOf(it.from, it.to) }.filter { it.lat != null && it.lng != null }
    if (ends.isEmpty()) return base
    return { q ->
        val t = q.trim().lowercase()
        val mine = ends.filter { t.isNotEmpty() && it.name.lowercase().contains(t) }
            .distinctBy { it.name }
            .map { EndpointHit(it.name, "zuletzt gesucht", it.lat!!, it.lng!!, fromTrip = false) }
        mine + (base?.invoke(q).orEmpty()).filterNot { h -> mine.any { it.name == h.name } }
    }
}
