package de.wanderlogplus.shared.query

import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.OpeningPeriod
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.sync.TravelMode
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Where a step of the day stands at the current time. */
enum class StepState { DONE, CURRENT, NEXT, LATER }

/** One step of today: a program place, a note or a departing train/flight, with (estimated) minutes of the day. */
sealed interface DayStep {
    val start: Int?
    val end: Int?
    val state: StepState

    /**
     * A place of the program. [estimated]: no planned time, [start] comes from the steps before. [wayMinutes] and
     * [wayMode] describe the way to it from the step before (or the stay); null when unknown.
     */
    data class Visit(
        val item: DayItem,
        val place: Place?,
        val saved: SavedPlace?,
        override val start: Int?,
        override val end: Int?,
        val estimated: Boolean,
        val wayMinutes: Int?,
        val wayMode: TravelMode?,
        override val state: StepState,
    ) : DayStep

    data class Note(val item: DayItem, override val state: StepState) : DayStep {
        override val start: Int? get() = null
        override val end: Int? get() = null
    }

    data class Move(val movement: Movement, override val start: Int?, override val end: Int?, override val state: StepState) : DayStep
}

/** Today's steps in order, with the one happening now and the next one. */
data class DayNowView(val steps: List<DayStep>, val current: DayStep?, val next: DayStep?) {
    /** Minutes until one should leave for [next] (its start minus the way); null without a time. */
    fun leaveIn(now: Int): Int? = (next as? DayStep.Visit)?.let { v -> v.start?.let { it - (v.wayMinutes ?: 0) - now } }
        ?: next?.start?.let { it - now }
}

/**
 * Lays out today like the chat's plan check: planned times are kept, places without a time follow the step before
 * (its end plus the way); stays take the place's typical duration (else by category). Ways are estimated from the
 * straight line (×1.3): up to 1.5 km on foot, else by public transport; a chosen connection wins.
 * Departing trains and flights are sorted in by their time.
 */
object DayNow {
    const val DAY_START = 9 * 60
    private const val DETOUR = 1.3
    private const val WALK_KMH = 4.5
    private const val WALK_MAX_KM = 1.5
    private const val TRANSIT_KMH = 30.0
    private const val TRANSIT_EXTRA = 15
    private const val DRIVE_KMH = 35.0
    private const val DRIVE_EXTRA = 5

    fun mins(time: String?): Int? {
        val t = time?.trim()?.takeIf { it.length >= 4 } ?: return null
        val p = t.split(':')
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.take(2)?.toIntOrNull() ?: return null
        return if (h in 0..47 && m in 0..59) h * 60 + m else null
    }

    fun hhmm(m: Int): String = "%02d:%02d".format((m / 60) % 24, m % 60)

    fun km(aLat: Double, aLng: Double, bLat: Double, bLng: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(bLat - aLat)
        val dLng = Math.toRadians(bLng - aLng)
        val h = sin(dLat / 2) * sin(dLat / 2) + cos(Math.toRadians(aLat)) * cos(Math.toRadians(bLat)) * sin(dLng / 2) * sin(dLng / 2)
        return 2 * r * asin(sqrt(h))
    }

    /** Estimated minutes and mode for a way of [km] (straight line); [mode] forces one. */
    fun way(km: Double, mode: TravelMode?): Pair<Int, TravelMode> {
        val d = km * DETOUR
        val m = mode ?: if (km <= WALK_MAX_KM) TravelMode.WALK else TravelMode.TRANSIT
        val minutes = when (m) {
            TravelMode.WALK -> d / WALK_KMH * 60
            TravelMode.DRIVE -> DRIVE_EXTRA + d / DRIVE_KMH * 60
            TravelMode.BICYCLE -> d / 14.0 * 60
            else -> TRANSIT_EXTRA + d / TRANSIT_KMH * 60
        }
        return minutes.roundToInt().coerceAtLeast(1) to m
    }

    fun stayMinutes(place: Place?, saved: SavedPlace?): Int {
        val min = place?.minMinutes
        val max = place?.maxMinutes
        if (min != null && max != null) return (min + max) / 2
        min?.let { return it }
        max?.let { return it }
        return when (saved?.category) {
            PlaceCategory.FOOD -> 60
            PlaceCategory.SHOPPING -> 45
            PlaceCategory.NIGHTLIFE -> 90
            PlaceCategory.OTHER -> 45
            else -> 60
        }
    }

    private fun has(p: Place?) = p != null && !(p.lat == 0.0 && p.lng == 0.0)

    /**
     * [items] in program order, [movements] departing today, [stay] where the day starts (for the first way), [now]
     * in minutes of the day.
     */
    fun layout(
        items: List<DayItem>,
        places: Map<String, Place>,
        saved: List<SavedPlace>,
        movements: List<Movement>,
        stay: Place?,
        now: Int,
    ): DayNowView {
        val savedByPlace = saved.groupBy { it.placeId }.mapValues { (_, l) -> l.first() }
        var clock: Int? = null
        var at: Place? = stay?.takeIf { has(it) }
        val raw = mutableListOf<DayStep>()
        items.forEach { i ->
            if (i.kind != DayItemKind.PLACE) {
                raw += DayStep.Note(i, StepState.LATER)
                return@forEach
            }
            val p = places[i.placeId]
            val s = savedByPlace[i.placeId]
            val chosen = i.legTransit?.routes?.firstOrNull()?.durationMinutes
            val (wayMin, wayMode) = when {
                chosen != null -> chosen to TravelMode.TRANSIT
                has(at) && has(p) -> way(km(at!!.lat, at!!.lng, p!!.lat, p.lng), i.legMode).let { it.first to it.second }
                else -> null to null
            }
            val fixed = mins(i.time)
            val start = fixed ?: clock?.let { it + (wayMin ?: 0) } ?: (DAY_START + (wayMin ?: 0)).takeIf { raw.none { r -> r is DayStep.Visit } }
            val end = start?.let { it + stayMinutes(p, s) }
            raw += DayStep.Visit(i, p, s, start, end, fixed == null, wayMin, wayMode, StepState.LATER)
            clock = end ?: clock
            if (has(p)) at = p
        }
        // Departures by their time: before the first step that starts later (untimed ones go last).
        movements.forEach { m ->
            val dep = mins(m.time)
            val arr = when (m) {
                is Movement.FlightMove -> arrival(m.flight.depart, m.flight.arrive)
                is Movement.TrainMove -> arrival(m.train.depart, m.train.arrive)
            }
            val step = DayStep.Move(m, dep, arr, StepState.LATER)
            val at2 = if (dep == null) raw.size else raw.indexOfFirst { it.start != null && it.start!! > dep }.let { if (it < 0) raw.size else it }
            raw.add(at2, step)
        }
        val withState = raw.map { st ->
            val start = st.start
            val end = st.end
            val state = when {
                start == null || start > now -> StepState.LATER
                end != null && end > now -> StepState.CURRENT
                else -> StepState.DONE // over, or departed (no arrival time)
            }
            when (st) {
                is DayStep.Visit -> st.copy(state = state)
                is DayStep.Move -> st.copy(state = state)
                is DayStep.Note -> st
            }
        }
        val current = withState.lastOrNull { it.state == StepState.CURRENT }
        val nextIndex = withState.indexOfFirst { it !is DayStep.Note && it.state == StepState.LATER && (it.start ?: Int.MAX_VALUE) > now }
            .takeIf { it >= 0 }
            ?: withState.indexOfFirst { it !is DayStep.Note && it.state == StepState.LATER }.takeIf { it >= 0 }
        val steps = withState.mapIndexed { i, st ->
            if (i != nextIndex) st else when (st) {
                is DayStep.Visit -> st.copy(state = StepState.NEXT)
                is DayStep.Move -> st.copy(state = StepState.NEXT)
                is DayStep.Note -> st
            }
        }
        return DayNowView(steps, current?.let { c -> steps.firstOrNull { it.sameAs(c) } }, nextIndex?.let { steps[it] })
    }

    private fun DayStep.sameAs(o: DayStep): Boolean = when {
        this is DayStep.Visit && o is DayStep.Visit -> item.id == o.item.id
        this is DayStep.Move && o is DayStep.Move -> movement == o.movement
        this is DayStep.Note && o is DayStep.Note -> item.id == o.item.id
        else -> false
    }

    /** Arrival in minutes of the departure day (+24 h per day later); null without a time. */
    private fun arrival(depart: Endpoint, arrive: Endpoint): Int? {
        val t = mins(arrive.time) ?: return null
        val days = runCatching {
            java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(depart.date), java.time.LocalDate.parse(arrive.date ?: depart.date))
        }.getOrDefault(0L)
        return t + (days * 24 * 60).toInt()
    }

    /**
     * Opening state at [now] (minutes) on weekday [dow] (0 = Sunday): "offen bis 18:00", "schließt in 25 Min.",
     * "geschlossen – öffnet 10:00", "heute geschlossen"; null when the hours are unknown.
     */
    fun openText(periods: List<OpeningPeriod>, dow: Int, now: Int): String? {
        if (periods.isEmpty()) return null
        if (isOpenOn(periods, dow) == false) return "heute geschlossen"
        val open = isOpenAt(periods, dow, hhmm(now)) ?: return null
        val step = 5
        return if (open) {
            val closes = (now + step..now + 24 * 60 step step).firstOrNull { it >= 24 * 60 || isOpenAt(periods, dow, hhmm(it)) == false }
            when {
                closes == null || closes >= 24 * 60 -> "offen"
                closes - now <= 60 -> "schließt in ${closes - now} Min."
                else -> "offen bis ${hhmm(closes)}"
            }
        } else {
            val opens = (now + step until 24 * 60 step step).firstOrNull { isOpenAt(periods, dow, hhmm(it)) == true }
            if (opens != null) "geschlossen – öffnet ${hhmm(opens)}" else "jetzt geschlossen"
        }
    }
}
