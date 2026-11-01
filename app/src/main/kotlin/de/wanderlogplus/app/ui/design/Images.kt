package de.wanderlogplus.app.ui.design

import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Place

/** Wanderlog's public medium-size image endpoint; append an image key. */
const val PLACE_IMAGE_BASE = "https://itin-dev.wanderlogstatic.com/freeImageMedium/"

fun placeImageUrl(imageKey: String): String = PLACE_IMAGE_BASE + imageKey

/** URL of the first non-blank image key, or null when the place has no image. */
fun Place.heroImageUrl(): String? = imageKeys.firstOrNull { it.isNotBlank() }?.let { placeImageUrl(it.trim()) }

/** Photo of the first PLACE item (in program order) of [date] that has an image; null without one. */
fun dayHeroImage(snapshot: TripSnapshot, date: String): String? {
    val day = snapshot.queries()?.dayFor(date) ?: snapshot.days.filter { it.date == date }.maxByOrNull { it.id } ?: return null
    return snapshot.dayItemsFor(day.id).asSequence()
        .filter { it.kind == DayItemKind.PLACE }
        .mapNotNull { item -> item.placeId?.let { snapshot.places[it] }?.heroImageUrl() }
        .firstOrNull()
}

/** Photo of the best-rated place saved in [regionId] that has an image (ties: name, then id); null without one. */
fun regionHeroImage(snapshot: TripSnapshot, regionId: String): String? =
    snapshot.savedPlaces.asSequence()
        .filter { it.regionId == regionId }
        .mapNotNull { snapshot.places[it.placeId] }
        .filter { it.heroImageUrl() != null }
        .sortedWith(compareByDescending<Place> { it.rating ?: Double.NEGATIVE_INFINITY }.thenBy { it.name }.thenBy { it.id })
        .firstOrNull()
        ?.heroImageUrl()
