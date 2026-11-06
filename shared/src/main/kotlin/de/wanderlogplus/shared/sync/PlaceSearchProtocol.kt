package de.wanderlogplus.shared.sync

import de.wanderlogplus.shared.model.PlaceCategory
import kotlinx.serialization.Serializable

/** A place found by Google's Text Search (`GET /api/places/search`). */
@Serializable
data class GooglePlaceHit(
    val googlePlaceId: String,
    val name: String,
    val address: String = "",
    val lat: Double,
    val lng: Double,
    val types: List<String> = emptyList(),
    val rating: Double? = null,
    val ratingCount: Int? = null,
    /** Photo names (fetch via `/api/places/photos/media`), not the images. */
    val photos: List<PlacePhoto> = emptyList(),
    val category: PlaceCategory = PlaceCategory.OTHER,
)

@Serializable
data class PlaceSearchHits(val hits: List<GooglePlaceHit> = emptyList())
