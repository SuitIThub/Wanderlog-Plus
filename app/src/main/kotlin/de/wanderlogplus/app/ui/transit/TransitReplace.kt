package de.wanderlogplus.app.ui.transit

import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.TransitPoint
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

const val TRANSIT_REPLACE_ROUTE = "logistik/replace/{id}"

/** "Andere Fahrt suchen" for train [trainId]. */
fun transitReplaceRoute(trainId: String) = "logistik/replace/${android.net.Uri.encode(trainId)}"

/**
 * Where the search for another ride starts: the later of (planned departure + 1 min) and [now]; returns date and
 * "HH:mm". Without a date: today; without a time: [now].
 */
fun replaceStart(t: Train, now: LocalDateTime): Pair<String, String> {
    val date = t.depart.date?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: now.toLocalDate()
    val time = t.depart.time?.let { runCatching { LocalTime.parse(it.take(5)) }.getOrNull() }
    val planned = time?.let { date.atTime(it).plusMinutes(1) } ?: now
    val start = if (now.isAfter(planned)) now else planned
    return start.toLocalDate().toString() to start.toLocalTime().toString().take(5)
}

/** Start and goal of the search for another ride: the stations' points (when known) and their Japanese names. */
data class ReplaceSeed(
    val from: TransitPoint?,
    val to: TransitPoint?,
    val fromStation: String,
    val toStation: String,
    /** Whether [from]/[to] are the places before/after the stations (hotel, ryokan) rather than the stations. */
    val fromIsPlace: Boolean = false,
    val toIsPlace: Boolean = false,
)

fun replaceSeed(t: Train): ReplaceSeed {
    val legs = t.transit?.routes?.firstOrNull()?.legs?.filter { !it.walk }.orEmpty()
    fun point(lat: Double?, lng: Double?, name: String) = if (lat != null && lng != null) TransitPoint(lat, lng, name) else null
    val walkFrom = t.walkFrom?.let { point(it.lat, it.lng, it.name) }
    val walkTo = t.walkTo?.let { point(it.lat, it.lng, it.name) }
    return ReplaceSeed(
        walkFrom ?: point(t.depart.lat, t.depart.lng, t.depart.name),
        walkTo ?: point(t.arrive.lat, t.arrive.lng, t.arrive.name),
        legs.firstOrNull()?.from?.nameJa.orEmpty(),
        legs.lastOrNull()?.to?.nameJa.orEmpty(),
        fromIsPlace = walkFrom != null,
        toIsPlace = walkTo != null,
    )
}
