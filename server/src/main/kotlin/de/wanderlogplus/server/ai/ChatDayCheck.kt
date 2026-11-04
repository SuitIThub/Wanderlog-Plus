package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.query.isOpenAt
import de.wanderlogplus.shared.query.isOpenOn
import de.wanderlogplus.shared.sync.TravelMode
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * One stop of a day plan to check: a place of the trip ([placeId]) or a free point ([lat]/[lng]), with a fixed [time]
 * ("HH:mm") if planned, the stay in [minutes] if known, and how the way to it goes ([legMode], [legMinutes] from a
 * chosen connection).
 */
data class PlanStop(
    val name: String,
    val placeId: String? = null,
    val lat: Double? = null,
    val lng: Double? = null,
    val time: String? = null,
    val minutes: Int? = null,
    val legMode: TravelMode? = null,
    val legMinutes: Int? = null,
    val category: PlaceCategory? = null,
)

/**
 * Walks through a day plan without the model: estimated ways (straight line with a detour factor, or the chosen
 * connection), stays (typical duration), opening hours and the day's trains and flights. Returns German text with the
 * estimated timeline and the problems found, for the chat to explain.
 */
class ChatDayCheck(private val t: ChatTrip) {

    /** The day's program as stops. */
    fun programStops(date: String): List<PlanStop> {
        val day = t.queries?.dayFor(date) ?: return emptyList()
        return t.dayItems.filter { it.dayId == day.id && it.kind == DayItemKind.PLACE }.sortedBy { it.sortKey }.map { i ->
            val p = t.places[i.placeId]
            PlanStop(
                name = p?.name ?: "Ort", placeId = i.placeId, time = i.time?.takeIf { it.isNotBlank() },
                legMode = i.legMode, legMinutes = i.legTransit?.routes?.firstOrNull()?.durationMinutes,
                category = t.savedFor(i.placeId)?.category,
            )
        }
    }

    fun check(date: String, stops: List<PlanStop>): String {
        val d = runCatching { LocalDate.parse(date) }.getOrNull() ?: return "Ungültiges Datum."
        val dow = if (d.dayOfWeek == DayOfWeek.SUNDAY) 0 else d.dayOfWeek.value
        val issues = mutableListOf<String>()
        val lines = mutableListOf<String>()
        val start = t.morningStay(date) ?: t.nightStay(date)
        val end = t.nightStay(date)
        var at: Anchor? = start?.let { t.point(it.placeId, "Unterkunft " + t.stayName(it)) }

        // Trains and flights of the day: a departure ends the program before it, an arrival starts it after.
        data class Move(val label: String, val departs: Int?, val arrives: Int?, val from: Anchor?, val to: Anchor?, val buffer: Int)
        fun ep(e: Endpoint, label: String): Anchor? = when {
            e.lat != null && e.lng != null -> Anchor(label, e.lat!!, e.lng!!)
            else -> t.point(e.placeId, label)
        }
        val moves = t.flights.filter { it.depart.date == date || it.arrive.date == date }.map {
            Move("Flug " + listOfNotNull(it.airline ?: it.airlineCode, it.number).joinToString(" "), it.depart.time.takeIf { _ -> it.depart.date == date }?.let(::mins),
                it.arrive.time.takeIf { _ -> it.arrive.date == date }?.let(::mins), ep(it.depart, it.depart.name), ep(it.arrive, it.arrive.name), FLIGHT_BUFFER)
        } + t.trains.filter { it.depart.date == date || it.arrive.date == date }.map {
            Move("Zug" + (it.carrier?.let { c -> " $c" } ?: ""), it.depart.time.takeIf { _ -> it.depart.date == date }?.let(::mins),
                it.arrive.time.takeIf { _ -> it.arrive.date == date }?.let(::mins), ep(it.depart, it.depart.name.ifBlank { "Bahnhof" }), ep(it.arrive, it.arrive.name.ifBlank { "Bahnhof" }), TRAIN_BUFFER)
        }

        var clock = stops.firstOrNull()?.time?.let(::mins)?.let { first ->
            val p = point(stops.first())
            first - (if (at != null && p != null) travel(at!!, p, stops.first()) else 0)
        } ?: DEFAULT_START
        // An arrival from an earlier day (overnight flight) moves start and position to the arrival point.
        moves.filter { it.arrives != null && it.departs == null }.minByOrNull { it.arrives!! }?.let { m ->
            if (m.arrives!! + ARRIVAL_BUFFER > clock || stops.isEmpty()) {
                clock = m.arrives + ARRIVAL_BUFFER
                lines += "${hhmm(m.arrives)} Ankunft ${m.label}" + (m.to?.name?.let { " in $it" } ?: "")
                m.to?.let { at = it }
            }
        }
        if (lines.isEmpty()) lines += "${hhmm(clock)} ab " + (at?.name ?: "Start")

        val pendingMoves = moves.filter { it.departs != null }.sortedBy { it.departs }.toMutableList()
        fun departBefore(nextStart: Int?) {
            while (pendingMoves.isNotEmpty()) {
                val m = pendingMoves.first()
                // The move comes next unless the next stop is planned before it.
                if (nextStart != null && nextStart < m.departs!!) return
                pendingMoves.removeAt(0)
                val way = if (at != null && m.from != null) travel(at!!, m.from, null) else 0
                val latest = m.departs!! - m.buffer - way
                if (clock > latest) {
                    issues += "${m.label} um ${hhmm(m.departs)}: Das Programm davor endet erst ca. ${hhmm(clock)}; " +
                        "mit Weg (ca. $way Min.) und ${m.buffer} Min. Puffer müsstet ihr spätestens ${hhmm(latest)} los."
                }
                lines += "${hhmm(m.departs)} ${m.label}" + (m.from?.name?.let { " ab $it" } ?: "") + (m.arrives?.let { " → an ${hhmm(it)}" } ?: "")
                clock = maxOf(clock, m.arrives ?: m.departs)
                at = m.to ?: at
            }
        }

        stops.forEach { s ->
            val p = point(s)
            val fixed = s.time?.let(::mins)
            departBefore(fixed ?: (clock + 1))
            val way = if (at != null && p != null) travel(at!!, p, s) else UNKNOWN_WAY
            val wayText = when {
                p == null -> "Lage unbekannt"
                at == null -> null
                else -> "Weg ca. $way Min." + (if (s.legMinutes != null) " (gewählte Verbindung)" else modeLabel(s, at!!, p))
            }
            var arrive = clock + way
            if (fixed != null) {
                if (arrive > fixed + LATE_TOLERANCE) issues += "${s.name}: geplant ${hhmm(fixed)}, frühestens ca. ${hhmm(arrive)} erreichbar (${wayText ?: "Weg unbekannt"})."
                arrive = maxOf(arrive, fixed)
            }
            val stay = s.minutes ?: stayMinutes(s)
            val leave = arrive + stay
            t.places[s.placeId]?.let { place ->
                val periods = place.openingPeriods
                when {
                    periods.isEmpty() -> Unit
                    isOpenOn(periods, dow) == false -> issues += "${s.name} hat am ${germanDate(date)} geschlossen (Ruhetag)."
                    isOpenAt(periods, dow, hhmm(arrive)) == false -> {
                        val opens = (arrive..LAST_MINUTE step 15).firstOrNull { isOpenAt(periods, dow, hhmm(it)) == true }
                        issues += if (opens != null) "${s.name}: Ankunft ca. ${hhmm(arrive)}, öffnet erst ca. ${hhmm(opens)}."
                        else "${s.name}: Ankunft ca. ${hhmm(arrive)}, dann schon geschlossen."
                    }
                    isOpenAt(periods, dow, hhmm(leave - 1)) == false -> {
                        val closes = (arrive..leave).firstOrNull { isOpenAt(periods, dow, hhmm(it)) == false }
                        issues += "${s.name} schließt ca. ${hhmm(closes ?: leave)}; bei Ankunft ca. ${hhmm(arrive)} bleibt nur ${(closes ?: leave) - arrive} von ca. $stay Min."
                    }
                }
            }
            lines += "${hhmm(arrive)}–${hhmm(leave)} ${s.name}" + (wayText?.let { " ($it)" } ?: "")
            clock = leave
            p?.let { at = it }
        }
        departBefore(null)
        end?.let { e -> t.point(e.placeId, "Unterkunft " + t.stayName(e)) }?.let { home ->
            val way = at?.let { travel(it, home, null) } ?: 0
            if (at != null && distanceKm(at!!.lat, at!!.lng, home.lat, home.lng) > 0.05) {
                clock += way
                lines += "ca. ${hhmm(clock)} zurück: ${home.name} (Weg ca. $way Min.)"
                if (clock > LATE_RETURN) issues += "Zurück an der Unterkunft erst ca. ${hhmm(clock)}."
            }
        }
        if (clock >= 24 * 60) issues += "Der Plan geht über Mitternacht hinaus."
        return buildString {
            append("Zeitplan ").append(germanDate(date)).append(" (geschätzt: Wege aus Luftlinie oder gewählter Verbindung, Aufenthalt aus typischer Dauer):\n")
            lines.forEach { append("- ").append(it).append('\n') }
            if (issues.isEmpty()) append("Keine Probleme gefunden.\n") else {
                append("Probleme:\n")
                issues.forEach { append("- ").append(it).append('\n') }
            }
        }
    }

    private fun point(s: PlanStop): Anchor? =
        if (s.lat != null && s.lng != null) Anchor(s.name, s.lat, s.lng) else t.point(s.placeId, s.name)

    /** Estimated minutes from [a] to [b]: the chosen connection, else by mode from the straight line. */
    private fun travel(a: Anchor, b: Anchor, s: PlanStop?): Int {
        s?.legMinutes?.let { return it }
        val km = distanceKm(a.lat, a.lng, b.lat, b.lng) * DETOUR
        val mode = s?.legMode ?: if (km <= WALK_KM) TravelMode.WALK else TravelMode.TRANSIT
        val m = when (mode) {
            TravelMode.WALK -> km / 4.5 * 60
            TravelMode.BICYCLE -> km / 14 * 60
            TravelMode.DRIVE -> 5 + km / 35 * 60
            TravelMode.TRANSIT -> 15 + km / 30 * 60
        }
        return kotlin.math.ceil(m).toInt().coerceAtLeast(if (km < 0.05) 0 else 3)
    }

    private fun modeLabel(s: PlanStop, a: Anchor, b: Anchor): String {
        val km = distanceKm(a.lat, a.lng, b.lat, b.lng) * DETOUR
        return when (s.legMode ?: if (km <= WALK_KM) TravelMode.WALK else TravelMode.TRANSIT) {
            TravelMode.WALK -> " zu Fuß"
            TravelMode.BICYCLE -> " Rad"
            TravelMode.DRIVE -> " Auto"
            TravelMode.TRANSIT -> " ÖPNV, grob"
        }
    }

    private fun stayMinutes(s: PlanStop): Int {
        val p = t.places[s.placeId]
        val min = p?.minMinutes
        val max = p?.maxMinutes
        if (min != null) return (if (max != null) (min + max) / 2 else min).coerceIn(10, 8 * 60)
        return when (s.category ?: t.savedFor(s.placeId)?.category) {
            PlaceCategory.FOOD -> 60
            PlaceCategory.SHOPPING -> 45
            PlaceCategory.NIGHTLIFE -> 90
            PlaceCategory.OTHER -> 45
            else -> 75
        }
    }

    companion object {
        const val DEFAULT_START = 9 * 60
        const val LAST_MINUTE = 23 * 60 + 59
        const val LATE_RETURN = 23 * 60
        const val LATE_TOLERANCE = 10
        const val ARRIVAL_BUFFER = 30
        const val TRAIN_BUFFER = 15
        const val FLIGHT_BUFFER = 120
        const val UNKNOWN_WAY = 20
        const val DETOUR = 1.3
        const val WALK_KM = 1.5

        fun mins(time: String): Int? {
            val p = time.trim().split(':')
            val h = p.getOrNull(0)?.toIntOrNull() ?: return null
            val m = p.getOrNull(1)?.toIntOrNull() ?: return null
            return h * 60 + m
        }

        fun hhmm(m: Int): String = "%02d:%02d".format((m / 60) % 24, m % 60)
    }
}
