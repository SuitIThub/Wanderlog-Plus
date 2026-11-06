package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.ReservationWindow
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Train
import java.time.LocalDate

/** When a saved place will be visited and when to reserve it. */
object ReservationDates {
    /**
     * The first planned day of [s] from [today] on (else its last planned day, which is over), not estimated; without a
     * plan the first trip day in the place's region, estimated; null without both.
     */
    fun visitDate(s: SavedPlace, days: List<Day>, items: List<DayItem>, tripDays: List<DayView>, today: String): Pair<String, Boolean>? {
        val dateOf = days.associate { it.id to it.date }
        val planned = items.filter { it.kind == DayItemKind.PLACE && it.placeId == s.placeId }.mapNotNull { dateOf[it.dayId] }.filter { it.isNotBlank() }
        if (planned.isNotEmpty()) return (planned.filter { it >= today }.minOrNull() ?: planned.max()) to false
        val region = s.regionId ?: return null
        return tripDays.firstOrNull { it.regionId == region }?.let { it.date to true }
    }

    /** The window of [s]: set by hand, else from the research's advice and the [visit] day; null when nothing is known. */
    fun windowFor(s: SavedPlace, visit: Pair<String, Boolean>?): ReservationWindow? {
        s.reservationWindow?.let { return it }
        val a = s.dossier?.reservation ?: return null
        val day = visit?.first?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
        val opens = a.opensOn ?: a.opensDaysBefore?.let { n -> day?.minusDays(n.toLong())?.toString() }
        val by = a.byDaysBefore?.let { n -> day?.minusDays(n.toLong())?.toString() }
        if (opens == null && by == null) return null
        return ReservationWindow(opens = opens, by = by)
    }
}

enum class DeadlineKind { CANCEL, OPENS, BY, PLACE }

enum class DeadlineState { UPCOMING, OPEN_NOW, OVERDUE }

enum class DeadlineTarget { PLACE, STAY, TRAIN, FLIGHT }

/**
 * One deadline: what ([kind]), when ([date]), for what ([targetType]/[targetId], shown as [title]), how it stands;
 * [estimated] when the place has no planned day yet; [placeStatus] for places.
 */
data class Deadline(
    val kind: DeadlineKind,
    val date: String,
    val title: String,
    val targetType: DeadlineTarget,
    val targetId: String,
    val state: DeadlineState,
    val estimated: Boolean = false,
    val placeStatus: PlaceStatus? = null,
)

data class DeadlineInput(
    val places: Map<String, Place>,
    val saved: List<SavedPlace>,
    val stays: List<Stay>,
    val trains: List<Train>,
    val flights: List<Flight>,
    val days: List<Day>,
    val items: List<DayItem>,
    val tripDays: List<DayView>,
)

/** Every open deadline of the trip: cancellations, reservation windows of rides, stays and places, places to reserve. */
object Deadlines {
    fun list(s: DeadlineInput, today: String): List<Deadline> {
        val out = mutableListOf<Deadline>()
        fun window(w: ReservationWindow?, event: String?, title: String, type: DeadlineTarget, id: String, estimated: Boolean = false, status: PlaceStatus? = null) {
            if (w == null || w.done) return
            if (event != null && event < today) return
            val opens = w.opens
            val by = w.by
            when {
                by != null && by < today -> out += Deadline(DeadlineKind.BY, by, title, type, id, DeadlineState.OVERDUE, estimated, status)
                opens != null && opens > today -> {
                    out += Deadline(DeadlineKind.OPENS, opens, title, type, id, DeadlineState.UPCOMING, estimated, status)
                    if (by != null) out += Deadline(DeadlineKind.BY, by, title, type, id, DeadlineState.UPCOMING, estimated, status)
                }
                opens != null -> out += Deadline(DeadlineKind.BY, by ?: event ?: opens, title, type, id, DeadlineState.OPEN_NOW, estimated, status)
                by != null -> out += Deadline(DeadlineKind.BY, by, title, type, id, DeadlineState.UPCOMING, estimated, status)
            }
        }
        fun placeName(id: String?, fallback: String) = id?.let { s.places[it]?.name }?.takeIf { it.isNotBlank() } ?: fallback
        s.stays.forEach { st ->
            val name = placeName(st.placeId, "Unterkunft")
            st.cancellationDeadline?.take(10)?.takeIf { it.isNotBlank() && it >= today }?.let {
                out += Deadline(DeadlineKind.CANCEL, it, name, DeadlineTarget.STAY, st.id, DeadlineState.UPCOMING)
            }
            window(st.reservation, st.checkOut.ifBlank { null }, name, DeadlineTarget.STAY, st.id)
        }
        s.trains.forEach { t ->
            val name = listOfNotNull(t.carrier, t.number).joinToString(" ").ifBlank { "Zug ${t.depart.name} → ${t.arrive.name}" }
            window(t.reservation, t.depart.date, name, DeadlineTarget.TRAIN, t.id)
        }
        s.flights.forEach { f ->
            val name = listOfNotNull(f.airlineCode ?: f.airline, f.number).joinToString(" ").ifBlank { "Flug ${f.depart.name} → ${f.arrive.name}" }
            window(f.reservation, f.depart.date, name, DeadlineTarget.FLIGHT, f.id)
        }
        s.saved.filter { !it.reserved && it.status != PlaceStatus.DROPPED && it.status != PlaceStatus.VISITED }.forEach { sp ->
            val name = placeName(sp.placeId, "Ort")
            val visit = ReservationDates.visitDate(sp, s.days, s.items, s.tripDays, today)
            val w = ReservationDates.windowFor(sp, visit)
            val estimated = visit?.second == true && sp.reservationWindow == null
            // On a day it is planned, whatever the status says (imports, older data).
            val status = if (visit != null && !visit.second) PlaceStatus.PLANNED else sp.status
            if (w != null) {
                window(w, visit?.first, name, DeadlineTarget.PLACE, sp.id, estimated, status)
            } else if (visit != null && !visit.second && visit.first >= today && Reservations.reason(sp) != null) {
                out += Deadline(DeadlineKind.PLACE, visit.first, name, DeadlineTarget.PLACE, sp.id, DeadlineState.UPCOMING, false, status)
            }
        }
        return out.sortedWith(compareBy({ if (it.state == DeadlineState.OVERDUE) 0 else 1 }, { it.date }, { it.title }))
    }
}
