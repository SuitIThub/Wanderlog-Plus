package de.wanderlogplus.shared.guide

import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Guide
import de.wanderlogplus.shared.model.GuideTarget
import de.wanderlogplus.shared.model.GuideTargetType
import de.wanderlogplus.shared.model.GuideTrigger
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.model.TriggerOn
import de.wanderlogplus.shared.query.DayFlow
import de.wanderlogplus.shared.query.DayFlowView
import de.wanderlogplus.shared.query.FlowEntry
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.query.PlaceMatch

/** What the matching looks at; built from the app's TripSnapshot or the server's chat trip. */
data class GuideWorld(
    val places: Map<String, Place>,
    val saved: List<SavedPlace>,
    val stays: List<Stay>,
    val trains: List<Train>,
    val flights: List<Flight>,
    val guides: List<Guide>,
    val days: List<Day> = emptyList(),
    val dayItems: List<DayItem> = emptyList(),
)

/** A guide of the day: at [atMinutes] (null: the whole day), [done] when everything it is about has passed. */
data class DayGuide(val guide: Guide, val atMinutes: Int?, val done: Boolean)

/** One thing of the trip a guide can be about. */
internal sealed interface GuideThing {
    val target: GuideTarget

    data class AtPlace(val saved: SavedPlace, val place: Place?) : GuideThing {
        override val target = GuideTarget(GuideTargetType.PLACE, saved.id)
    }

    data class AtStay(val stay: Stay, val place: Place?) : GuideThing {
        override val target = GuideTarget(GuideTargetType.STAY, stay.id)
    }

    data class OnTrain(val train: Train) : GuideThing {
        override val target = GuideTarget(GuideTargetType.TRAIN, train.id)
    }

    data class OnFlight(val flight: Flight) : GuideThing {
        override val target = GuideTarget(GuideTargetType.FLIGHT, flight.id)
    }
}

object GuideMatch {
    /** Catalog topics [target] touches, in catalog order; empty for unknown targets. */
    fun topicsFor(target: GuideTarget, w: GuideWorld): List<String> {
        val things = thingsOf(target, w) ?: return emptyList()
        val day = target.type == GuideTargetType.DAY
        return GuideTopics.all.filter { t -> t.triggers.any { tr -> (day && tr is GuideTrigger.Always) || things.any { fires(tr, it) } } }.map { it.key }
    }

    /**
     * Guides to show at [target]: those whose rules (catalog topic plus extra triggers) or pins match, without those
     * hidden there; a matching sub-topic brings its parent along. Catalog order, free guides last.
     */
    fun guidesFor(target: GuideTarget, w: GuideWorld): List<Guide> {
        val things = thingsOf(target, w) ?: return emptyList()
        val day = target.type == GuideTargetType.DAY
        val targets = things.map { it.target }.toSet() + target
        fun hidden(g: Guide) = target in g.hiddenFrom
        fun matches(g: Guide): Boolean {
            if (hidden(g)) return false
            if (g.pinnedTo.any { it in targets }) return true
            return triggersOf(g).any { tr ->
                (day && tr is GuideTrigger.Always) || things.any { th -> th.target !in g.hiddenFrom && fires(tr, th) }
            }
        }
        val byId = w.guides.associateBy { it.id }
        val hits = w.guides.filter(::matches).toMutableList()
        hits.toList().forEach { g ->
            var parent = g.parentId?.let { byId[it] }
            while (parent != null && parent !in hits && !hidden(parent)) {
                hits += parent
                parent = parent.parentId?.let { byId[it] }
            }
        }
        return hits.distinctBy { it.id }.sortedWith(compareBy({ GuideTopics.orderOf(it.topic) }, { it.title.lowercase() }, { it.id }))
    }

    /**
     * The day's guides in the order things happen: each at the time of the first not yet passed entry of [flow] it is
     * about ([nowMinutes] null: nothing passed); tonight's stay never passes. Guides of the whole day (language,
     * money) have no time. Upcoming by time first, then the timeless, then the passed ones.
     */
    fun forDay(date: String, flow: DayFlowView, w: GuideWorld, nowMinutes: Int?): List<DayGuide> {
        val stamps = flowStamps(flow, w)
        val items = guidesFor(GuideTarget(GuideTargetType.DAY, date), w).map { g ->
            val own = stamps.filter { (th, _, _) -> th.target !in g.hiddenFrom && (th.target in g.pinnedTo || triggersOf(g).any { fires(it, th) }) }
            val open = own.filter { (_, min, lasting) -> lasting || nowMinutes == null || min >= nowMinutes }
            when {
                own.isEmpty() -> DayGuide(g, null, false)
                open.isNotEmpty() -> DayGuide(g, open.minOf { it.second }, false)
                else -> DayGuide(g, own.minOf { it.second }, true)
            }
        }
        return items.sortedWith(
            compareBy<DayGuide>({ if (it.done) 2 else if (it.atMinutes == null) 1 else 0 }, { it.atMinutes ?: 0 }, { GuideTopics.orderOf(it.guide.topic) }, { it.guide.title.lowercase() }),
        )
    }

    /** Every flow entry as (thing, minutes, lasts the evening); entries without a time take the time before them. */
    private fun flowStamps(flow: DayFlowView, w: GuideWorld): List<Triple<GuideThing, Int, Boolean>> {
        var clock = 0
        fun at(t: String?, fallback: Int = clock) = (DayFlow.mins(t) ?: fallback).also { clock = maxOf(clock, it) }
        return flow.entries.flatMap { e ->
            when (e) {
                is FlowEntry.Start -> listOf(Triple(GuideThing.AtStay(e.stay, w.places[e.stay.placeId]) as GuideThing, at(e.stay.checkOutTime, 0), false))
                is FlowEntry.Item -> {
                    val min = at(e.item.time)
                    listOfNotNull(
                        w.saved.firstOrNull { it.placeId == e.item.placeId && it.status != PlaceStatus.DROPPED }?.let { Triple(GuideThing.AtPlace(it, w.places[it.placeId]) as GuideThing, min, false) },
                        e.checkIn?.let { Triple(GuideThing.AtStay(it, w.places[it.placeId]) as GuideThing, at(it.checkInTime, min), true) },
                    )
                }
                is FlowEntry.Ride -> listOf(
                    when (val m = e.movement) {
                        is Movement.TrainMove -> Triple(GuideThing.OnTrain(m.train) as GuideThing, at(m.time), false)
                        is Movement.FlightMove -> Triple(GuideThing.OnFlight(m.flight) as GuideThing, at(m.time), false)
                    },
                )
                is FlowEntry.Night -> listOf(Triple(GuideThing.AtStay(e.stay, w.places[e.stay.placeId]) as GuideThing, at(e.stay.checkInTime, maxOf(clock, EVENING)), true))
            }
        }
    }

    private const val EVENING = 18 * 60

    /** An existing guide on the same catalog topic, or with (nearly) the same title. */
    fun duplicateOf(topic: String?, title: String, guides: List<Guide>): Guide? {
        if (topic != null) guides.firstOrNull { it.topic == topic }?.let { return it }
        val words = PlaceMatch.words(title).toSet()
        if (words.isEmpty()) return null
        return guides.firstOrNull { g ->
            val other = PlaceMatch.words(g.title).toSet()
            other.isNotEmpty() && (other.containsAll(words) || words.containsAll(other))
        }
    }

    internal fun triggersOf(g: Guide): List<GuideTrigger> = GuideTopics.byKey(g.topic)?.triggers.orEmpty() + g.extraTriggers

    /** The things of [target]; a day has its program places, its stays (left in the morning, slept in at night) and rides. Null: unknown target. */
    internal fun thingsOf(target: GuideTarget, w: GuideWorld): List<GuideThing>? = when (target.type) {
        GuideTargetType.PLACE -> w.saved.firstOrNull { it.id == target.id }?.let { listOf(GuideThing.AtPlace(it, w.places[it.placeId])) }
        GuideTargetType.STAY -> w.stays.firstOrNull { it.id == target.id }?.let { listOf(GuideThing.AtStay(it, w.places[it.placeId])) }
        GuideTargetType.TRAIN -> w.trains.firstOrNull { it.id == target.id }?.let { listOf(GuideThing.OnTrain(it)) }
        GuideTargetType.FLIGHT -> w.flights.firstOrNull { it.id == target.id }?.let { listOf(GuideThing.OnFlight(it)) }
        GuideTargetType.DAY -> dayThings(target.id, w)
    }

    private fun dayThings(date: String, w: GuideWorld): List<GuideThing> {
        val dayIds = w.days.filter { it.date == date }.map { it.id }.toSet()
        val placeIds = w.dayItems.filter { it.dayId in dayIds && it.kind == DayItemKind.PLACE }.mapNotNull { it.placeId }.toSet()
        val places = w.saved.filter { it.placeId in placeIds && it.status != PlaceStatus.DROPPED }.distinctBy { it.placeId }
            .map { GuideThing.AtPlace(it, w.places[it.placeId]) }
        val stays = w.stays.filter { s -> s.checkIn.isNotBlank() && s.checkOut.isNotBlank() && s.checkIn <= date && date <= s.checkOut }
            .map { GuideThing.AtStay(it, w.places[it.placeId]) }
        val trains = w.trains.filter { it.depart.date == date || it.arrive.date == date }.map { GuideThing.OnTrain(it) }
        val flights = w.flights.filter { it.depart.date == date || it.arrive.date == date }.map { GuideThing.OnFlight(it) }
        return places + stays + trains + flights
    }

    internal fun fires(tr: GuideTrigger, th: GuideThing): Boolean = when (tr) {
        is GuideTrigger.Text -> when (th) {
            is GuideThing.AtPlace -> tr.on != TriggerOn.STAY && containsAny(placeText(th.place), tr.words)
            is GuideThing.AtStay -> tr.on != TriggerOn.PLACE && containsAny(placeText(th.place), tr.words)
            else -> false
        }
        is GuideTrigger.Category -> th is GuideThing.AtPlace && th.saved.category == tr.category
        GuideTrigger.AnyStay -> th is GuideThing.AtStay
        is GuideTrigger.Train -> th is GuideThing.OnTrain && (tr.words.isEmpty() || containsAny(trainText(th.train), tr.words))
        GuideTrigger.AnyFlight -> th is GuideThing.OnFlight
        GuideTrigger.Always -> false
    }

    private fun placeText(p: Place?): String = p?.let { (listOf(it.name) + it.categories).joinToString(" | ") }.orEmpty().lowercase()

    private fun trainText(t: Train): String = buildList {
        add(t.carrier.orEmpty())
        add(t.number.orEmpty())
        (listOf(t.depart, t.arrive) + t.via).forEach { add(it.name) }
        t.transit?.routes?.firstOrNull()?.legs?.forEach { add(it.line); add(it.lineJa) }
    }.joinToString(" | ").lowercase()

    private fun containsAny(text: String, words: List<String>) = text.isNotBlank() && words.any { it.isNotBlank() && it.lowercase() in text }
}
