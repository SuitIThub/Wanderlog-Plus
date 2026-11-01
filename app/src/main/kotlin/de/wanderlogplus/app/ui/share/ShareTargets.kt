package de.wanderlogplus.app.ui.share

import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.logistics.LogisticsEntry
import de.wanderlogplus.app.ui.logistics.flightTitle
import de.wanderlogplus.app.ui.logistics.logisticsEntries
import de.wanderlogplus.app.ui.logistics.trainTitle
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.sync.EntityType
import java.time.LocalDate
import java.time.format.DateTimeParseException

enum class ShareGroup(val label: String) {
    FLIGHT("Flüge"),
    TRAIN("Züge"),
    STAY("Unterkünfte"),
    DAY("Tage"),
    PLACE("Orte"),
}

/** Something a shared file can be attached to. [id] is `date:<yyyy-mm-dd>` for a day that does not exist yet. */
data class ShareTarget(val type: EntityType, val id: String, val label: String, val subtitle: String?, val group: ShareGroup)

const val DATE_ID_PREFIX = "date:"

private const val MAX_TRIP_DAYS = 366

private fun parse(date: String?): LocalDate? =
    try { date?.takeIf { it.isNotBlank() }?.let(LocalDate::parse) } catch (_: DateTimeParseException) { null }

private fun route(from: Endpoint, to: Endpoint): String? =
    listOf(from.name, to.name).takeIf { l -> l.any { it.isNotBlank() } }?.joinToString(" → ") { it.ifBlank { "–" } }

private fun whenText(e: Endpoint): String? = e.date?.takeIf { it.isNotBlank() }?.let(::dayLabel)

private fun joinSubtitle(vararg parts: String?): String? = parts.filterNotNull().joinToString(" · ").ifEmpty { null }

/** Never contains confirmation numbers or traveler names. */
fun shareTargets(snapshot: TripSnapshot): List<ShareTarget> {
    val logistics = logisticsEntries(snapshot).map { e ->
        when (e) {
            is LogisticsEntry.FlightEntry ->
                ShareTarget(EntityType.FLIGHT, e.id, flightTitle(e.flight).trim(), joinSubtitle(route(e.flight.depart, e.flight.arrive), whenText(e.flight.depart)), ShareGroup.FLIGHT)
            is LogisticsEntry.TrainEntry ->
                ShareTarget(EntityType.TRAIN, e.id, trainTitle(e.train), joinSubtitle(route(e.train.depart, e.train.arrive), whenText(e.train.depart)), ShareGroup.TRAIN)
            is LogisticsEntry.StayEntry -> {
                val s = e.stay
                val range = listOf(s.checkIn, s.checkOut).filter { it.isNotBlank() }.joinToString(" – ") { dayLabel(it) }
                ShareTarget(EntityType.STAY, e.id, e.name, range.ifEmpty { null }, ShareGroup.STAY)
            }
        }
    }

    val existing = snapshot.days.filter { it.date.isNotBlank() }.associateBy { it.date }
    val range = buildList {
        val start = parse(snapshot.trip?.startDate)
        val end = parse(snapshot.trip?.endDate)
        if (start != null && end != null) {
            var d: LocalDate = start
            while (!d.isAfter(end) && size < MAX_TRIP_DAYS) {
                add(d.toString())
                d = d.plusDays(1)
            }
        }
    }
    val days = (range + existing.keys).distinct().sorted().map { date ->
        val day = snapshot.queries()?.dayFor(date) ?: existing[date]
        ShareTarget(EntityType.DAY, day?.id ?: "$DATE_ID_PREFIX$date", dayLabel(date), day?.title?.takeIf { it.isNotBlank() }, ShareGroup.DAY)
    }

    val places = snapshot.savedPlaces.map { sp ->
        val name = snapshot.places[sp.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Ort"
        ShareTarget(EntityType.SAVED_PLACE, sp.id, name, categoryLabel(sp.category), ShareGroup.PLACE)
    }.sortedWith(compareBy<ShareTarget, String>(String.CASE_INSENSITIVE_ORDER) { it.label }.thenBy { it.id })

    return logistics.filter { it.group == ShareGroup.FLIGHT } +
        logistics.filter { it.group == ShareGroup.TRAIN } +
        logistics.filter { it.group == ShareGroup.STAY } +
        days + places
}

/** Case-insensitive match on label and subtitle; a blank query keeps everything. */
fun filterTargets(targets: List<ShareTarget>, query: String): List<ShareTarget> {
    val q = query.trim()
    if (q.isEmpty()) return targets
    return targets.filter { it.label.contains(q, ignoreCase = true) || it.subtitle?.contains(q, ignoreCase = true) == true }
}
