package de.wanderlogplus.shared.model

import kotlinx.serialization.Serializable

/**
 * When something can be reserved ([opens]) and must be reserved at the latest ([by]), both yyyy-MM-dd; [done] once it is
 * reserved (rides and stays; places use [SavedPlace.reserved]); [byHand] when set in the app – research leaves it alone.
 */
@Serializable
data class ReservationWindow(
    val opens: String? = null,
    val by: String? = null,
    val done: Boolean = false,
    val byHand: Boolean = false,
)
