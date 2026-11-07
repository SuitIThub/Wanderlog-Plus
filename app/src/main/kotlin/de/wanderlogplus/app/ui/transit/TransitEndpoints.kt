package de.wanderlogplus.app.ui.transit

import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.DayItemKind
import java.text.Normalizer

/** A start or goal for the transit search: a place of the trip or a search hit, with its coordinates. */
data class EndpointHit(val name: String, val detail: String, val lat: Double, val lng: Double, val fromTrip: Boolean)

/** Suggestions for what was typed: trip places first, then online hits. */
typealias EndpointSearch = suspend (String) -> List<EndpointHit>

private fun plain(s: String): String =
    Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "").lowercase()

/**
 * Places of the trip whose name contains [query] (ignoring case and accents): saved places (detail: region), stays
 * ("Unterkunft") and places of the day programs; each place once. Blank query: none.
 */
fun tripEndpointHits(snapshot: TripSnapshot, query: String, limit: Int = 8): List<EndpointHit> {
    val q = plain(query.trim())
    if (q.isEmpty()) return emptyList()
    val seen = mutableSetOf<String>()
    val out = mutableListOf<EndpointHit>()
    fun add(placeId: String?, detail: String) {
        val p = placeId?.let { snapshot.places[it] } ?: return
        if (p.name.isBlank() || (p.lat == 0.0 && p.lng == 0.0) || !plain(p.name).contains(q) || !seen.add(p.id)) return
        out += EndpointHit(p.name, detail, p.lat, p.lng, true)
    }
    snapshot.stays.forEach { add(it.placeId, "Unterkunft") }
    snapshot.savedPlaces.forEach { s -> add(s.placeId, s.regionId?.let { r -> snapshot.regions.firstOrNull { it.id == r }?.name }.orEmpty()) }
    snapshot.dayItems.filter { it.kind == DayItemKind.PLACE }.forEach { add(it.placeId, "Tagesprogramm") }
    return out.take(limit)
}

/** Trip hits first, then online hits that are not the same place (name and position); at most [limit]. */
fun mergeHits(trip: List<EndpointHit>, online: List<EndpointHit>, limit: Int = 8): List<EndpointHit> {
    fun key(h: EndpointHit) = plain(h.name) + "|" + Math.round(h.lat * 1000) + "|" + Math.round(h.lng * 1000)
    val seen = trip.map(::key).toMutableSet()
    return (trip + online.filter { seen.add(key(it)) }).take(limit)
}

/** Suggestions for Von/Nach: places of [snapshot] and, from 3 characters, hits of [finder] near [near]. */
fun transitSuggest(snapshot: TripSnapshot, near: de.wanderlogplus.shared.sync.TransitPoint?, finder: de.wanderlogplus.client.PlaceFinder?): EndpointSearch = { q ->
    val trip = tripEndpointHits(snapshot, q)
    val online = if (finder != null && q.trim().length >= 3) {
        try {
            finder.search(q, near?.let { it.lat to it.lng }, null).places.map { EndpointHit(it.name, it.detail, it.lat, it.lng, false) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            emptyList()
        }
    } else emptyList()
    mergeHits(trip, online)
}
