package de.wanderlogplus.app.widgets

import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.query.DayNow
import de.wanderlogplus.shared.query.DayStep
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.query.StepState
import de.wanderlogplus.shared.query.TodayQueries
import de.wanderlogplus.shared.query.TodayState
import de.wanderlogplus.shared.sync.ChatLocation
import de.wanderlogplus.shared.sync.QuickAsk
import java.time.LocalDateTime

/** A quick ask ready to send: the [quick] block and where the user probably is ([anchor]) when the position is unknown. */
data class QuickRequest(val question: String, val quick: QuickAsk, val anchor: ChatLocation?)

/** The "Was jetzt?" asks: the fixed question, the free time until the next planned step and the anchor place. */
object QuickAsks {
    fun question(kind: String): String =
        if (kind == QuickAsk.KIND_FOOD) "Wo kann ich jetzt gut essen?" else "Was kann ich jetzt in der Nähe besichtigen?"

    fun request(kind: String, s: TripSnapshot?, now: LocalDateTime): QuickRequest {
        val nowText = "%02d:%02d".format(now.hour, now.minute)
        val base = QuickAsk(kind, nowText)
        val trip = s?.trip
        val q = s?.queries()
        val state = if (trip != null && q != null) TodayQueries(q, trip, s.stays, s.flights, s.trains).state(now.toLocalDate()) else null
        val during = state as? TodayState.During
        val stayPlace = (during?.day?.stay ?: s?.stays?.firstOrNull { it.checkIn <= now.toLocalDate().toString() && now.toLocalDate().toString() < it.checkOut })
            ?.let { s?.places?.get(it.placeId) }
        if (during == null || s == null) return QuickRequest(question(kind), base, stayPlace?.location())
        val nowMin = now.hour * 60 + now.minute
        val items = during.day.day?.let { s.dayItemsFor(it.id) }.orEmpty()
        val view = DayNow.layout(items, s.places, s.savedPlaces, during.movements, stayPlace, nowMin)
        val next = view.steps.firstOrNull { it !is DayStep.Note && it.state != StepState.DONE && it.state != StepState.CURRENT && (it.start ?: -1) > nowMin }
        val nextPlace: Place? = when (next) {
            is DayStep.Visit -> next.place
            is DayStep.Move -> when (val m = next.movement) {
                is Movement.TrainMove -> m.train.depart.placeId?.let { s.places[it] }
                is Movement.FlightMove -> m.flight.depart.placeId?.let { s.places[it] }
            }
            else -> null
        }
        val nextLabel = when (next) {
            is DayStep.Visit -> next.place?.name?.takeIf { it.isNotBlank() }
            is DayStep.Move -> when (val m = next.movement) {
                is Movement.TrainMove -> "Zug ab " + m.train.depart.name
                is Movement.FlightMove -> "Flug ab " + m.flight.depart.name
            }
            else -> null
        }
        // Where the user probably is: the step running now, else the next one, else tonight's stay.
        val here = ((view.current as? DayStep.Visit)?.place?.takeIf { it.has() })
            ?: (next as? DayStep.Visit)?.place?.takeIf { it.has() }
            ?: stayPlace?.takeIf { it.has() }
        val quick = if (next?.start == null) base else base.copy(
            freeUntil = "%02d:%02d".format(next.start!! / 60 % 24, next.start!! % 60),
            nextLabel = nextLabel?.take(120),
            nextLat = nextPlace?.takeIf { it.has() }?.lat,
            nextLng = nextPlace?.takeIf { it.has() }?.lng,
        )
        return QuickRequest(question(kind), quick, here?.location())
    }

    private fun Place.has() = !(lat == 0.0 && lng == 0.0)
    private fun Place.location() = takeIf { it.has() }?.let { ChatLocation(it.lat, it.lng) }
}
