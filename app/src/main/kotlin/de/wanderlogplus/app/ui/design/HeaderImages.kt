package de.wanderlogplus.app.ui.design

import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay

/**
 * Own header images. A reference is "wl:<Wanderlog image key>", "att:<file hash>" (an own photo) or
 * "g:<Google photo name>"; [resolver] turns the last two into a local file URI (set by the app; null when the file is
 * not on this device, then the default image shows).
 */
object HeaderImages {
    const val WANDERLOG = "wl:"
    const val OWN = "att:"
    const val GOOGLE = "g:"

    @Volatile
    var resolver: (String) -> String? = { null }

    fun url(ref: String?): String? = when {
        ref.isNullOrBlank() -> null
        ref.startsWith(WANDERLOG) -> placeImageUrl(ref.removePrefix(WANDERLOG))
        else -> runCatching { resolver(ref) }.getOrNull()
    }
}

/** The header of a saved place: its own image, else the place's first image. */
fun SavedPlace?.heroImage(place: Place?): String? = HeaderImages.url(this?.headerImage) ?: place?.heroImageUrl()

/** The header of a stay: its own image, else the hotel's first image. */
fun Stay?.heroImage(place: Place?): String? = HeaderImages.url(this?.headerImage) ?: place?.heroImageUrl()
