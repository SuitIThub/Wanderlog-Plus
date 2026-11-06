package de.wanderlogplus.shared.seatmap

import de.wanderlogplus.shared.model.Seat

/** "2. Klasse · Wagen 12, 111 + 114", "Economy · 43E + 43G"; null without class and seats. */
fun seatLine(travelClass: String?, seats: List<Seat>): String? {
    val cls = travelClass?.trim()?.ifBlank { null }
    val list = seats.filter { it.seat.isNotBlank() }
    val places = list.groupBy { it.car?.trim()?.ifBlank { null } }.entries.joinToString(" + ") { (car, s) ->
        (car?.let { "Wagen $it, " } ?: "") + s.joinToString(" + ") { it.seat.trim() }
    }.ifBlank { null }
    return listOfNotNull(cls, places).joinToString(" · ").ifBlank { null }
}

/** Suggestions for the class field. */
val FLIGHT_CLASSES = listOf("Economy", "Premium Economy", "Business", "First")
val TRAIN_CLASSES = listOf("2. Klasse", "1. Klasse", "Gewöhnlich", "Green Car", "Gran Class", "Ohne Reservierung")
