package de.wanderlogplus.app.widgets

import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.design.checkInText
import de.wanderlogplus.app.ui.design.checkOutText
import de.wanderlogplus.app.ui.design.routeTitle
import de.wanderlogplus.app.ui.design.timesLine
import de.wanderlogplus.app.ui.logistics.platformText
import de.wanderlogplus.app.ui.today.deadlineRows
import de.wanderlogplus.app.ui.today.upcomingMove
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Expense
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.query.BudgetCategories
import de.wanderlogplus.shared.query.BudgetMath
import de.wanderlogplus.shared.query.DayNow
import de.wanderlogplus.shared.query.DayNowView
import de.wanderlogplus.shared.query.DayStep
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.query.StepState
import de.wanderlogplus.shared.query.TodayQueries
import de.wanderlogplus.shared.query.TodayState
import de.wanderlogplus.shared.sync.DayWeather
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.TravelMode
import de.wanderlogplus.shared.sync.TripWeather
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** The last known euro price of one yen and the day it is from. */
data class StoredRate(val eurPerJpy: Double, val date: String)

/** Everything the widgets show is built from this; nothing else is read while drawing. */
data class WidgetInput(
    val snapshot: TripSnapshot?,
    val now: LocalDateTime,
    val weather: TripWeather?,
    val rate: StoredRate?,
    val lastChatTitle: String?,
    val quick: QuickResult?,
)

enum class Phase { NO_TRIP, BEFORE, DURING, AFTER }

enum class StepKind { PLACE, TRAIN, FLIGHT }

/** One step of the day for a widget row: „09:00 Alter Bergtempel · in 25 Min.“. */
data class StepLine(
    val time: String?,
    val title: String,
    val kind: StepKind,
    val inMinutes: Long?,
    val wayText: String?,
    val done: Boolean,
    val target: WidgetTarget,
)

data class NextUpModel(
    val current: StepLine? = null,
    val upcoming: List<StepLine> = emptyList(),
    val daysUntilStart: Long? = null,
    val tripName: String? = null,
    val openDeadlines: Int = 0,
    val nextDeadline: String? = null,
    val date: String? = null,
)

data class RideModel(
    val kind: StepKind,
    val departs: String,
    val departsDate: String,
    val inMinutes: Long,
    val name: String,
    val route: String,
    val times: String,
    val arrives: String?,
    val platform: String?,
    val seat: String?,
    val transfers: List<String>,
    val tickets: List<Pair<String, String>>,
    val target: WidgetTarget,
)

data class TodayModel(
    val dateLabel: String,
    val region: String?,
    val stayName: String?,
    val steps: List<StepLine>,
    val weatherLine: String?,
)

data class StayModel(
    val name: String,
    val address: String?,
    val checkLine: String,
    val confirmation: String?,
    val mapsUrl: String?,
    val stayId: String,
)

data class BudgetModel(
    val rate: StoredRate?,
    val todayJpy: Long,
    val totalEur: Double,
    val table: List<Pair<Long, Double>>,
    val topCategories: List<Pair<String, Double>>,
)

data class WeatherModel(
    val place: String,
    val today: DayWeather?,
    val tomorrow: DayWeather?,
    val rainHint: String?,
) {
    val climate: Boolean get() = today?.isForecast == false
}

data class ChatModel(val lastTitle: String?, val quick: QuickResult?, val stale: Boolean)

data class WidgetModels(
    val phase: Phase,
    val now: LocalDateTime,
    val nextUp: NextUpModel,
    val ride: RideModel?,
    val today: TodayModel?,
    val stay: StayModel?,
    val budget: BudgetModel,
    val weather: WeatherModel?,
    val chat: ChatModel,
)

/** Builds the widget contents with the same queries as the Heute screen. Pure; safe to call on any thread. */
object WidgetData {
    /** Yen amounts of the conversion table. */
    val TABLE_YEN = listOf(500L, 1000L, 5000L, 10000L)

    /** A quick result greys out after this many hours and is dropped after [QUICK_GONE_HOURS] (or on the next day). */
    const val QUICK_STALE_HOURS = 2L
    const val QUICK_GONE_HOURS = 6L

    /** Check-out without a known time counts as done at this hour. */
    private const val CHECKOUT_DEFAULT = 12 * 60

    fun build(i: WidgetInput): WidgetModels {
        val s = i.snapshot
        val now = i.now
        val today = now.toLocalDate().toString()
        val state = state(s, now.toLocalDate())
        val phase = when (state) {
            null, TodayState.Unknown -> Phase.NO_TRIP
            is TodayState.Before -> Phase.BEFORE
            is TodayState.During -> Phase.DURING
            TodayState.After -> Phase.AFTER
        }
        val dayNow = (state as? TodayState.During)?.let { dayNow(s!!, it, now) }
        val nextUp = when (state) {
            is TodayState.Before -> {
                val rows = deadlineRows(s!!, today)
                NextUpModel(daysUntilStart = state.daysUntilStart, tripName = s.trip?.title, openDeadlines = rows.size, nextDeadline = rows.firstOrNull()?.let { "${it.title} · ${it.text}" })
            }
            is TodayState.During -> {
                val current = dayNow!!.current?.let { line(it, s!!, today, now) }
                NextUpModel(
                    current = current,
                    upcoming = dayNow.steps.filter { it !is DayStep.Note && (it.state == StepState.NEXT || it.state == StepState.LATER) }
                        .mapNotNull { line(it, s!!, today, now) }.take(5),
                    date = today,
                )
            }
            else -> NextUpModel(tripName = s?.trip?.title)
        }
        val todayModel = (state as? TodayState.During)?.let { st ->
            val region = st.day.regionId?.let { id -> s!!.regions.firstOrNull { it.id == id }?.name }
            TodayModel(
                dateLabel = dayLabel(today),
                region = region,
                stayName = st.day.stay?.let { placeName(s!!, it) },
                steps = dayNow!!.steps.mapNotNull { line(it, s!!, today, now) },
                weatherLine = i.weather?.forDate(today)?.let { weatherLine(it) },
            )
        }
        return WidgetModels(
            phase = phase,
            now = now,
            nextUp = nextUp,
            ride = s?.let { ride(it, now) },
            today = todayModel,
            stay = s?.let { stay(it, state, now) },
            budget = budget(s, i.rate, today),
            weather = weather(s, i.weather, state, now),
            chat = chat(i),
        )
    }

    /** When the widgets next change by themselves: a step starts/ends or one should leave, a ride departs, the quick result ages, midnight. */
    fun nextBoundary(i: WidgetInput): LocalDateTime {
        val now = i.now
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        val times = mutableListOf(midnight)
        val s = i.snapshot
        val state = state(s, now.toLocalDate())
        if (state is TodayState.During) {
            val v = dayNow(s!!, state, now)
            val day = now.toLocalDate().atStartOfDay()
            v.steps.forEach { st ->
                val leave = (st as? DayStep.Visit)?.let { vs -> vs.start?.let { it - (vs.wayMinutes ?: 0) } }
                listOfNotNull(st.start, st.end, leave).forEach { m -> times += day.plusMinutes(m.toLong()) }
            }
        }
        s?.let { upcomingMove(it, now) }?.let { times += it.departs; it.arrives?.let { a -> times += a } }
        i.quick?.let { q ->
            val asked = LocalDateTime.ofInstant(Instant.ofEpochMilli(q.at), ZoneId.systemDefault())
            times += asked.plusHours(QUICK_STALE_HOURS)
            times += asked.plusHours(QUICK_GONE_HOURS)
        }
        return times.filter { it > now }.min()
    }

    private fun state(s: TripSnapshot?, date: LocalDate): TodayState? {
        val trip = s?.trip ?: return null
        val q = s.queries() ?: return null
        return TodayQueries(q, trip, s.stays, s.flights, s.trains).state(date)
    }

    private fun dayNow(s: TripSnapshot, st: TodayState.During, now: LocalDateTime): DayNowView {
        val stayPlace = st.day.stay?.let { s.places[it.placeId] }
        val items = st.day.day?.let { s.dayItemsFor(it.id) }.orEmpty()
        return DayNow.layout(items, s.places, s.savedPlaces, st.movements, stayPlace, now.hour * 60 + now.minute)
    }

    private fun hhmm(minutes: Int): String = "%02d:%02d".format(minutes / 60 % 24, minutes % 60)

    private fun line(st: DayStep, s: TripSnapshot, date: String, now: LocalDateTime): StepLine? {
        val nowMin = now.hour * 60 + now.minute
        val inMin = st.start?.let { (it - nowMin).toLong() }?.takeIf { it > 0 }
        val done = st.state == StepState.DONE
        return when (st) {
            is DayStep.Note -> null
            is DayStep.Visit -> StepLine(
                st.start?.let(::hhmm), st.place?.name?.takeIf { it.isNotBlank() } ?: "Ort", StepKind.PLACE, inMin,
                wayText(st.wayMinutes, st.wayMode), done, WidgetTarget.Day(date),
            )
            is DayStep.Move -> StepLine(
                st.start?.let(::hhmm), st.movement.routeTitle(), kindOf(st.movement), inMin, null, done,
                WidgetTarget.Logistics(typeOf(st.movement), idOf(st.movement)),
            )
        }
    }

    private fun wayText(minutes: Int?, mode: TravelMode?): String? = minutes?.let { m ->
        "$m Min. " + when (mode) {
            TravelMode.WALK -> "zu Fuß"
            TravelMode.DRIVE -> "Auto"
            TravelMode.BICYCLE -> "Rad"
            else -> "ÖPNV"
        }
    }

    private fun kindOf(m: Movement) = if (m is Movement.FlightMove) StepKind.FLIGHT else StepKind.TRAIN
    private fun typeOf(m: Movement) = if (m is Movement.FlightMove) EntityType.FLIGHT else EntityType.TRAIN
    private fun idOf(m: Movement) = when (m) {
        is Movement.FlightMove -> m.flight.id
        is Movement.TrainMove -> m.train.id
    }

    private fun ride(s: TripSnapshot, now: LocalDateTime): RideModel? {
        val u = upcomingMove(s, now) ?: return null
        val m = u.movement
        val name = when (m) {
            is Movement.FlightMove -> listOfNotNull(m.flight.airlineCode ?: m.flight.airline, m.flight.number).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Flug" }
            is Movement.TrainMove -> m.train.carrier?.takeIf { it.isNotBlank() } ?: "Zug"
        }
        val seat = when (m) {
            is Movement.TrainMove -> de.wanderlogplus.shared.seatmap.seatLine(m.train.travelClass, m.train.seats)
            is Movement.FlightMove -> de.wanderlogplus.shared.seatmap.seatLine(m.flight.travelClass, m.flight.seats)
        }
        val journey = de.wanderlogplus.client.journeyOf(s, typeOf(m), idOf(m))
        val transfers = journey?.let { j ->
            val idx = j.indexOf(typeOf(m), idOf(m))
            j.legs.drop(idx + 1).map { l -> "Danach: " + de.wanderlogplus.app.ui.logistics.legTitle(l) + (l.depart.time?.let { " um $it" } ?: "") }
        }.orEmpty()
        val tickets = s.attachmentsFor(typeOf(m), idOf(m)).filter { it.kind == "file" }.map { it.fileName.ifBlank { "Ticket" } to it.id }
        return RideModel(
            kind = kindOf(m),
            departs = u.departs.toLocalTime().toString().take(5),
            departsDate = u.departs.toLocalDate().toString(),
            inMinutes = ChronoUnit.MINUTES.between(now, u.departs),
            name = name,
            route = m.routeTitle(),
            times = m.timesLine(),
            arrives = u.arrives?.toLocalTime()?.toString()?.take(5),
            platform = (m as? Movement.TrainMove)?.train?.platformText(),
            seat = seat,
            transfers = transfers,
            tickets = tickets,
            target = WidgetTarget.Logistics(typeOf(m), idOf(m)),
        )
    }

    private fun placeName(s: TripSnapshot, stay: Stay): String = s.places[stay.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft"

    private fun stay(s: TripSnapshot, state: TodayState?, now: LocalDateTime): StayModel? {
        val today = now.toLocalDate().toString()
        val nowMin = now.hour * 60 + now.minute
        val leaving = s.stays.firstOrNull { it.checkOut == today && nowMin < (DayNow.mins(it.checkOutTime) ?: CHECKOUT_DEFAULT) }
        val stay = leaving
            ?: (state as? TodayState.During)?.day?.stay
            ?: s.stays.filter { it.checkIn <= today && today < it.checkOut }.firstOrNull()
            ?: (if (state is TodayState.Before) s.stays.minByOrNull { it.checkIn } else null)
            ?: return null
        val place: Place? = s.places[stay.placeId]
        val line = when {
            stay === leaving -> checkOutText(stay).replaceFirst("Check-out", "Check-out heute")
            stay.checkIn == today -> checkInText(stay, null) + " (heute)"
            stay.checkIn > today -> "Check-in " + dayLabel(stay.checkIn)
            else -> "Check-out " + dayLabel(stay.checkOut)
        }
        return StayModel(
            name = placeName(s, stay),
            address = place?.address?.takeIf { it.isNotBlank() },
            checkLine = line,
            confirmation = stay.confirmationNumber?.takeIf { it.isNotBlank() },
            mapsUrl = place?.let { de.wanderlogplus.app.ui.places.googleMapsUrl(it) },
            stayId = stay.id,
        )
    }

    private fun isJpy(e: Expense) = e.currency.equals("JPY", ignoreCase = true)

    private fun budget(s: TripSnapshot?, stored: StoredRate?, today: String): BudgetModel {
        val expenses = s?.expenses.orEmpty().filter { !it.isTransfer }
        val rate = stored ?: expenses.filter { isJpy(it) && it.rate != null }
            .maxByOrNull { it.rateDate ?: it.date ?: "" }
            ?.let { StoredRate(it.rate!!, it.rateDate ?: it.date ?: today) }
        val todayJpy = expenses.filter { isJpy(it) && it.date?.take(10) == today }.sumOf { it.amount }.toLong()
        val summary = s?.let { BudgetMath.summary(it.expenses, it.trip?.travelers.orEmpty()) }
        return BudgetModel(
            rate = rate,
            todayJpy = todayJpy,
            totalEur = summary?.total ?: 0.0,
            table = rate?.let { r -> TABLE_YEN.map { y -> y to BudgetMath.cents(y * r.eurPerJpy) } }.orEmpty(),
            topCategories = summary?.byCategory.orEmpty().entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(3)
                .map { BudgetCategories.label(it.key) to it.value },
        )
    }

    private fun weather(s: TripSnapshot?, w: TripWeather?, state: TodayState?, now: LocalDateTime): WeatherModel? {
        w ?: return null
        val date = if (state is TodayState.Before) s?.trip?.startDate ?: return null else now.toLocalDate().toString()
        val today = w.forDate(date) ?: return null
        val tomorrow = w.forDate(LocalDate.parse(date).plusDays(1).toString())
        val outdoor = if (s != null) de.wanderlogplus.app.ui.weather.outdoorPlacesOn(s, date) else emptyList()
        val hint = outdoor.takeIf { it.isNotEmpty() && de.wanderlogplus.shared.sync.WeatherText.isRainy(today) }
            ?.let { "Regen erwartet – " + it.joinToString(", ") + (if (it.size == 1) " ist" else " sind") + " eher etwas für gutes Wetter." }
        return WeatherModel(today.place.ifBlank { "Heute" }, today, tomorrow, hint)
    }

    /** „Bewölkt · 12–18° · Regen 40 %“. */
    fun weatherLine(d: DayWeather): String = listOfNotNull(
        if (d.isForecast) de.wanderlogplus.shared.sync.WeatherText.label(d.code) else "Klima-Mittel",
        tempRange(d),
        d.rainChance?.let { "Regen $it %" },
    ).joinToString(" · ")

    fun tempRange(d: DayWeather): String? {
        val hi = d.tempMax?.let { Math.round(it) } ?: return null
        val lo = d.tempMin?.let { Math.round(it) }
        return if (lo != null) "$lo–$hi°" else "$hi°"
    }

    private fun chat(i: WidgetInput): ChatModel {
        val q = i.quick ?: return ChatModel(i.lastChatTitle, null, false)
        val asked = LocalDateTime.ofInstant(Instant.ofEpochMilli(q.at), ZoneId.systemDefault())
        val hours = ChronoUnit.MINUTES.between(asked, i.now) / 60.0
        val gone = q.date != i.now.toLocalDate().toString() || hours >= QUICK_GONE_HOURS
        if (gone) return ChatModel(i.lastChatTitle, null, false)
        return ChatModel(i.lastChatTitle, q, hours >= QUICK_STALE_HOURS)
    }
}
