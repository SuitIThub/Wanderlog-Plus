package de.wanderlogplus.app.reminders

import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.query.OpenReservation
import de.wanderlogplus.shared.query.Reservations
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit

/** Days before a cancellation deadline on which a reminder is due. */
val REMINDER_OFFSETS = listOf(7, 3, 1, 0)

/** A notification to post; [savedPlaceId] is set for a reservation reminder (it gets a "Reserviert" button). */
data class Reminder(
    val key: String,
    val stayId: String,
    val title: String,
    val text: String,
    val savedPlaceId: String? = null,
    /** Longer text for the expanded notification. */
    val detail: String? = null,
)

/**
 * Reservation reminders for planned places that still need one: like [dueReminders], at most one per place for the
 * smallest offset of [Reservations.REMINDER_OFFSETS] already due before its planned day, up to that day.
 */
fun dueReservationReminders(open: List<OpenReservation>, places: Map<String, Place>, today: LocalDate, alreadySent: Set<String>): List<Reminder> =
    open.mapNotNull { r ->
        val date = try { LocalDate.parse(r.date) } catch (_: DateTimeParseException) { return@mapNotNull null }
        if (today > date) return@mapNotNull null
        val offset = Reservations.REMINDER_OFFSETS.filter { today >= date.minusDays(it.toLong()) }.minOrNull() ?: return@mapNotNull null
        val key = "res|${r.saved.id}|$date|$offset"
        if (key in alreadySent) return@mapNotNull null
        val name = places[r.saved.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Ort"
        val days = ChronoUnit.DAYS.between(today, date)
        val `when` = when (days) {
            0L -> "Heute eingeplant"
            1L -> "Morgen eingeplant"
            else -> "Eingeplant am ${dayLabel(r.date)} (noch $days Tage)"
        }
        Reminder(key, "", "Reservierung: $name", "$`when` – schon reserviert?", r.saved.id, "$`when`. ${r.reason}")
    }.sortedBy { it.key }

/**
 * At most one reminder per stay: for the smallest offset that is already due (today >= deadline - offset) and
 * not yet sent. Larger offsets count as done and are never caught up. Nothing is due after the deadline.
 * Text carries neither confirmation numbers nor traveler names.
 */
fun dueReminders(stays: List<Stay>, places: Map<String, Place>, today: LocalDate, alreadySent: Set<String>): List<Reminder> =
    stays.mapNotNull { stay ->
        val deadline = try { LocalDate.parse(stay.cancellationDeadline?.take(10) ?: return@mapNotNull null) } catch (_: DateTimeParseException) { return@mapNotNull null }
        if (today > deadline) return@mapNotNull null
        val offset = REMINDER_OFFSETS.filter { today >= deadline.minusDays(it.toLong()) }.minOrNull() ?: return@mapNotNull null
        val key = "${stay.id}|$deadline|$offset"
        if (key in alreadySent) return@mapNotNull null
        val name = places[stay.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft"
        val days = ChronoUnit.DAYS.between(today, deadline)
        val `when` = when (days) {
            0L -> "Heute"
            1L -> "Morgen"
            else -> "Noch $days Tage"
        }
        val text = if (days <= 1L) "$`when` (${dayLabel(deadline.toString())})" else "$`when` (bis ${dayLabel(deadline.toString())})"
        Reminder(key, stay.id, "Stornofrist: $name", text)
    }.sortedBy { it.key }
