package de.wanderlogplus.app.ui.places

import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Votes
import java.util.Locale
import kotlin.math.roundToInt

/** Places still worth rating: not visited and not dropped. */
private val RATEABLE = setOf(PlaceStatus.IDEA, PlaceStatus.SHORTLIST, PlaceStatus.PLANNED)

/** [me] has a vote record on the place, whatever its value (0 = neutral, also a withdrawn vote). */
fun isRated(snapshot: TripSnapshot, savedPlaceId: String, me: String): Boolean {
    val id = Votes.id(savedPlaceId, me)
    return snapshot.votes.any { it.id == id }
}

/** Rateable places in region tile order (places without a known region last), by name inside a region. */
private fun rateablePlaces(snapshot: TripSnapshot): List<SavedPlace> {
    val order = regionTileOrder(snapshot).mapIndexed { i, r -> r.id to i }.toMap()
    return snapshot.savedPlaces.filter { it.status in RATEABLE }.sortedWith(
        compareBy<SavedPlace> { order[it.regionId] ?: Int.MAX_VALUE }
            .thenBy { snapshot.places[it.placeId]?.name.orEmpty().lowercase() }
            .thenBy { it.id },
    )
}

/** Saved place ids [me] has not rated yet, in the order the Bewerten mode shows them. */
fun ratingQueue(snapshot: TripSnapshot, me: String): List<String> =
    rateablePlaces(snapshot).filter { !isRated(snapshot, it.id, me) }.map { it.id }

/** "850 m" (under 1 km, to 10 m), "4,2 km" (under 10 km), else "38 km". */
fun distanceLabel(km: Double): String = when {
    km < 1.0 -> "${((km * 1000) / 10).roundToInt() * 10} m"
    km < 10.0 -> String.format(Locale.GERMAN, "%.1f km", km)
    else -> "${km.roundToInt()} km"
}

/** A distance and the region it refers to (the stay's region, or the place's own one for the centre). */
data class StayDistance(val km: Double, val regionName: String)

private fun pointOf(snapshot: TripSnapshot, placeId: String): LatLngD? =
    snapshot.places[placeId]?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) }?.let { LatLngD(it.lat, it.lng) }

/**
 * Distance to the nearest stay of the place's region; for a day-trip region, to the stays of the region it is a day
 * trip from. Null without coordinates or without such a stay.
 */
fun stayDistance(snapshot: TripSnapshot, saved: SavedPlace): StayDistance? {
    val at = pointOf(snapshot, saved.placeId) ?: return null
    val region = snapshot.regions.firstOrNull { it.id == saved.regionId } ?: return null
    val base = if (isDayTrip(snapshot, region)) snapshot.regions.first { it.id == region.dayTripFromRegionId } else region
    return snapshot.stays.filter { it.regionId == base.id }
        .mapNotNull { s -> pointOf(snapshot, s.placeId)?.let { distanceKm(at, it) } }
        .minOrNull()?.let { StayDistance(it, base.name) }
}

/** Only for day-trip regions: distance to the centre of the region's saved places. */
fun centreDistance(snapshot: TripSnapshot, saved: SavedPlace): StayDistance? {
    val at = pointOf(snapshot, saved.placeId) ?: return null
    val region = snapshot.regions.firstOrNull { it.id == saved.regionId }?.takeIf { isDayTrip(snapshot, it) } ?: return null
    val centre = regionCentres(snapshot)[region.id] ?: return null
    return StayDistance(distanceKm(at, centre), region.name)
}
