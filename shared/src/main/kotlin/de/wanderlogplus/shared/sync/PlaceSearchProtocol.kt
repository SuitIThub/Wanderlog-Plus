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

/** Where a web photo comes from. */
@Serializable
enum class WebPhotoSource { GOOGLE, WIKIMEDIA }

/**
 * A photo found online (`GET /api/photos/search`); the image itself comes from `/api/photos/media?id=`. [id] is
 * `g:<Google photo name>` or `w:<Commons file title without "File:">`.
 */
@Serializable
data class WebPhoto(
    val id: String,
    val source: WebPhotoSource,
    val title: String = "",
    val credit: String = "",
    val width: Int = 0,
    val height: Int = 0,
)

@Serializable
data class WebPhotos(val photos: List<WebPhoto> = emptyList())
