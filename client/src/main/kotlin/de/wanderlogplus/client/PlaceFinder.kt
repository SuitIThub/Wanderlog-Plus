package de.wanderlogplus.client

import de.wanderlogplus.shared.sync.GooglePlaceHit
import kotlinx.coroutines.CancellationException
import java.io.IOException

/** Hits of a place search and whether Google answered (false: OpenStreetMap only). */
data class PlaceFinderResult(val places: List<GeoPlace>, val viaGoogle: Boolean)

/**
 * "Ort suchen": Google's Text Search via the own server ([google]), so a place found here keeps its Google id. Without
 * a server, without a Google key or when the server fails it falls back to Photon (OpenStreetMap). An empty Google
 * answer is an answer, not a failure.
 */
class PlaceFinder(
    private val google: (suspend (String, Pair<Double, Double>?, BBox?) -> List<GooglePlaceHit>)?,
    private val photon: GeoSearch,
) {
    suspend fun search(q: String, near: Pair<Double, Double>?, bbox: BBox?, limit: Int = 15): PlaceFinderResult {
        val query = q.trim()
        if (query.isEmpty()) return PlaceFinderResult(emptyList(), google != null)
        google?.let { g ->
            try {
                return PlaceFinderResult(g(query, near, bbox).take(limit).map { it.toGeoPlace() }, true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: ApiException) {
                // falls through to Photon
            } catch (e: IOException) {
                // falls through to Photon
            }
        }
        return PlaceFinderResult(photon.search(query, near, limit, bbox), false)
    }
}

fun GooglePlaceHit.toGeoPlace(): GeoPlace =
    GeoPlace(name, address, lat, lng, googlePlaceId = googlePlaceId, googleCategory = category)
