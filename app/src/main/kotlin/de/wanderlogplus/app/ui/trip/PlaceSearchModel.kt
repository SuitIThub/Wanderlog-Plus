package de.wanderlogplus.app.ui.trip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import de.wanderlogplus.client.BBox
import de.wanderlogplus.client.GeoPlace
import kotlinx.coroutines.CancellationException
import kotlin.math.abs

/** Name search, optionally near a point and/or inside an area; throws on failure. */
typealias AreaSearch = suspend (query: String, near: Pair<Double, Double>?, bbox: BBox?) -> List<GeoPlace>

/** The place at a point (reverse geocoding); null when nothing is known there. */
typealias PointLookup = suspend (lat: Double, lng: Double) -> GeoPlace?

enum class SearchStatus { IDLE, LOADING, FOUND, FOUND_ELSEWHERE, NOTHING, FAILED, UNAVAILABLE }

/**
 * State of "Ort suchen": a text search that prefers the visible map area and falls back to a general search when the
 * area has no hit, "search this area again" after the map moved, and picking any point on the map.
 * The map reports its area via [visible]; [fitRequest] increases whenever the map should zoom to the [results].
 */
class PlaceSearchModel(
    private val search: AreaSearch,
    private val lookup: PointLookup,
    /** Bias for searches before the map has reported an area (e.g. the day's places). */
    private val home: Pair<Double, Double>?,
) {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<GeoPlace>>(emptyList())
        private set
    var selected by mutableStateOf<GeoPlace?>(null)
    var status by mutableStateOf(SearchStatus.IDLE)
        private set
    var fitRequest by mutableIntStateOf(0)
        private set

    /** The area the map shows right now. */
    var visible by mutableStateOf<BBox?>(null)

    /** HTTP status of the last failed search, for the message (null: no answer at all). */
    var failedStatus by mutableStateOf<Int?>(null)
        private set

    /** The area the current [results] were searched in (null: general search). */
    private var searchedArea by mutableStateOf<BBox?>(null)
    private var searchedQuery by mutableStateOf("")

    /** True when the map was moved away from the area of the last search: "In diesem Bereich suchen" makes sense. */
    val canSearchArea: Boolean
        get() {
            val v = visible ?: return false
            if (searchedQuery.isBlank() || query.isBlank()) return false
            val a = searchedArea ?: return true // results came from a general search: the visible area was not searched
            return !a.sameAs(v)
        }

    /** Text search: first inside the visible area; nothing there → a general search near it, and the map zooms to the hits. */
    suspend fun submit() {
        val q = query.trim()
        if (q.isEmpty()) return
        val area = visible?.normalized()
        run(q) {
            // The area search is only a preference: if it fails, the general search below still runs.
            val inside = if (area != null) {
                try {
                    search(q, area.center(), area)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            if (inside.isNotEmpty()) {
                searchedArea = area
                inside to SearchStatus.FOUND
            } else {
                val everywhere = search(q, area?.center() ?: home, null)
                searchedArea = null
                everywhere to when {
                    everywhere.isEmpty() -> SearchStatus.NOTHING
                    area == null -> SearchStatus.FOUND // no map area known yet: nothing was "outside"
                    else -> SearchStatus.FOUND_ELSEWHERE
                }
            }
        }
        if (results.isNotEmpty() && results.any { r -> area?.contains(r.lat, r.lng) != true }) fitRequest++
    }

    /** The last query again, restricted to the area the map shows now. */
    suspend fun searchArea() {
        val area = visible?.normalized() ?: return
        val q = query.trim().ifEmpty { return }
        run(q) {
            searchedArea = area
            val hits = search(q, area.center(), area)
            hits to if (hits.isEmpty()) SearchStatus.NOTHING else SearchStatus.FOUND
        }
    }

    /** A long press on the map: the named place there, or the bare point when nothing is known. */
    suspend fun pick(lat: Double, lng: Double) {
        val found = try {
            lookup(lat, lng)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        selected = found?.copy(lat = lat, lng = lng) ?: GeoPlace("Markierter Ort", "", lat, lng)
    }

    private suspend fun run(q: String, block: suspend () -> Pair<List<GeoPlace>, SearchStatus>) {
        status = SearchStatus.LOADING
        selected = null
        try {
            val (hits, st) = block()
            results = hits
            status = st
            searchedQuery = q
            if (hits.size == 1) selected = hits.single()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            results = emptyList()
            val api = e as? de.wanderlogplus.client.ApiException
            // Offline is FAILED; the service answering with an error (overloaded, refused) is UNAVAILABLE.
            status = if (api != null && api.kind == de.wanderlogplus.client.ApiErrorKind.SERVER) SearchStatus.UNAVAILABLE else SearchStatus.FAILED
            failedStatus = api?.status
        }
    }

    private fun BBox.center() = centerLat to centerLng

    /** Equal up to a small tolerance: map projections round, and tiny jitters should not offer a new search. */
    private fun BBox.sameAs(o: BBox): Boolean {
        val tol = maxOf(abs(east - west), abs(north - south)) * 0.05
        return abs(west - o.west) < tol && abs(east - o.east) < tol && abs(south - o.south) < tol && abs(north - o.north) < tol
    }
}

fun statusText(s: SearchStatus, count: Int): String? = when (s) {
    SearchStatus.IDLE -> "Suche nach Namen – oder lange auf die Karte drücken, um einen Punkt zu wählen."
    SearchStatus.LOADING -> "Suche läuft …"
    SearchStatus.FOUND -> if (count == 1) "1 Treffer" else "$count Treffer"
    SearchStatus.FOUND_ELSEWHERE -> "Im Ausschnitt nichts gefunden – $count Treffer in der Umgebung"
    SearchStatus.NOTHING -> "Nichts gefunden"
    SearchStatus.FAILED -> "Suche fehlgeschlagen – bist du online?"
    SearchStatus.UNAVAILABLE -> "Die Ortssuche (OpenStreetMap/Photon) antwortet gerade nicht. Gleich noch einmal versuchen, oder lange auf die Karte drücken, um den Punkt selbst zu setzen."
}
