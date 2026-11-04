package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.model.Trip
import de.wanderlogplus.shared.query.TripQueries
import de.wanderlogplus.shared.query.isOpenOn
import de.wanderlogplus.shared.sync.ChatCard
import de.wanderlogplus.shared.sync.ChatPlan
import de.wanderlogplus.shared.sync.ChatPlanStep
import de.wanderlogplus.shared.sync.ChatContext
import de.wanderlogplus.shared.sync.ChatMessage
import de.wanderlogplus.shared.sync.ChatScreens
import de.wanderlogplus.shared.sync.EntityRecord
import de.wanderlogplus.shared.sync.EntityStore
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.Worth
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** What the chat knows about the trip, read once per answer. Booking details (confirmation numbers, names) are never used. */
class ChatTrip(
    val trip: Trip?,
    val regions: List<Region>,
    val places: Map<String, Place>,
    val saved: List<SavedPlace>,
    val stays: List<Stay>,
    val days: List<Day>,
    val dayItems: List<DayItem>,
    val flights: List<Flight> = emptyList(),
    val trains: List<Train> = emptyList(),
) {
    val regionsById = regions.associateBy { it.id }
    val savedById = saved.associateBy { it.id }
    private val savedByPlace = saved.groupBy { it.placeId }
    private val dayById = days.associateBy { it.id }
    val queries: TripQueries? = trip?.let { TripQueries(it, stays, days, regions, saved, dayItems) }

    /** placeId -> the dates its program items are on. */
    val plannedDates: Map<String, List<String>> = dayItems.filter { it.kind == DayItemKind.PLACE && it.placeId != null }
        .groupBy({ it.placeId!! }, { dayById[it.dayId]?.date.orEmpty() })
        .mapValues { (_, v) -> v.filter { it.isNotEmpty() }.distinct().sorted() }

    fun regionName(id: String?): String? = id?.let { regionsById[it]?.name }

    /** [regionId] plus the regions that are day trips from it. */
    fun regionWithDayTrips(regionId: String): Set<String> = regions.filter { it.dayTripFromRegionId == regionId }.map { it.id }.toSet() + regionId

    /** The saved place for [placeId] (the not-dropped one first), if the place is a suggestion. */
    fun savedFor(placeId: String?): SavedPlace? = placeId?.let { savedByPlace[it] }?.sortedBy { it.status == PlaceStatus.DROPPED }?.firstOrNull()

    /** Coordinates of a place; null when it has none (0/0 is "unknown"). */
    fun point(placeId: String?, name: String? = null): Anchor? = placeId?.let { places[it] }
        ?.takeIf { !(it.lat == 0.0 && it.lng == 0.0) }
        ?.let { Anchor(name ?: it.name.ifBlank { "Ort" }, it.lat, it.lng) }

    fun stayName(s: Stay): String = places[s.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft"

    private fun dated(s: Stay) = s.checkIn.isNotBlank() && s.checkOut.isNotBlank()

    /** The stay slept in the night after [date]. */
    fun nightStay(date: String): Stay? = stays.filter { dated(it) && it.checkIn <= date && date < it.checkOut }.minByOrNull { it.checkIn }

    /** The stay checked out of on [date] (slept in the night before). */
    fun morningStay(date: String): Stay? = stays.firstOrNull { dated(it) && it.checkOut == date && it.checkIn < date }

    /** Where distances on [date] are measured from: that night's stay, else the one left that morning. */
    fun dayAnchor(date: String): Anchor? =
        listOfNotNull(nightStay(date), morningStay(date)).firstNotNullOfOrNull { s -> point(s.placeId, "Unterkunft " + stayName(s)) }

    /** The first stay of [regionId] (or of the region it is a day trip from). */
    fun regionStay(regionId: String): Stay? {
        val r = regionsById[regionId]
        return stays.filter { it.regionId == regionId }.minByOrNull { it.checkIn }
            ?: r?.dayTripFromRegionId?.let { from -> stays.filter { it.regionId == from }.minByOrNull { it.checkIn } }
    }

    /** Flights and trains departing or arriving on [date], as German lines. */
    fun movementsOn(date: String): List<String> {
        fun ep(e: Endpoint) = listOfNotNull(e.name.ifBlank { null } ?: e.code, e.time).joinToString(" ")
        val f = flights.filter { it.depart.date == date || it.arrive.date == date }.sortedBy { it.depart.time.orEmpty() }.map {
            "Flug " + listOfNotNull(it.airline ?: it.airlineCode, it.number).joinToString(" ") + ": " + ep(it.depart) + " → " + ep(it.arrive)
        }
        val tr = trains.filter { it.depart.date == date || it.arrive.date == date }.sortedBy { it.depart.time.orEmpty() }.map {
            "Zug" + (it.carrier?.let { c -> " $c" } ?: "") + ": " + ep(it.depart) + " → " + ep(it.arrive)
        }
        return f + tr
    }

    companion object {
        fun from(store: EntityStore): ChatTrip {
            fun <T> live(type: EntityType, decode: (EntityRecord) -> T): List<T> =
                store.all(type).filter { !it.isDeleted }.mapNotNull { r -> try { decode(r) } catch (e: Exception) { null } }
            return ChatTrip(
                trip = live(EntityType.TRIP) { EntityCodec.decode<Trip>(it) }.firstOrNull(),
                regions = live(EntityType.REGION) { EntityCodec.decode<Region>(it) },
                places = live(EntityType.PLACE) { EntityCodec.decode<Place>(it) }.associateBy { it.id },
                saved = live(EntityType.SAVED_PLACE) { EntityCodec.decode<SavedPlace>(it) },
                stays = live(EntityType.STAY) { EntityCodec.decode<Stay>(it) },
                days = live(EntityType.DAY) { EntityCodec.decode<Day>(it) },
                dayItems = live(EntityType.DAY_ITEM) { EntityCodec.decode<DayItem>(it) },
                flights = live(EntityType.FLIGHT) { EntityCodec.decode<Flight>(it) },
                trains = live(EntityType.TRAIN) { EntityCodec.decode<Train>(it) },
            )
        }
    }
}

internal fun germanDate(date: String): String = try {
    val d = LocalDate.parse(date)
    d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.GERMAN) + ", " + "%02d.%02d.%d".format(d.dayOfMonth, d.monthValue, d.year)
} catch (e: Exception) {
    date
}

private fun shortDate(date: String): String = try {
    val d = LocalDate.parse(date)
    d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.GERMAN).take(2) + " " + "%02d.%02d.".format(d.dayOfMonth, d.monthValue)
} catch (e: Exception) {
    date
}

/** "850 m" or "4,2 km" (as the crow flies). */
internal fun distanceText(km: Double): String =
    if (km < 1) "${(km * 1000).toInt() / 10 * 10} m" else String.format(Locale.GERMAN, if (km < 20) "%.1f km" else "%.0f km", km)

/** Turns where the chat was opened into German prompt text. */
object ChatContextText {
    /** Trip, regions, stays and the plan of every day: what the trip screens show, compact. */
    fun overview(t: ChatTrip): String = buildString {
        val trip = t.trip
        if (trip != null) append("Reise: ").append(trip.title.ifBlank { "Japanreise" }).append(", ").append(trip.startDate).append(" bis ").append(trip.endDate).append('\n')
        val days = t.queries?.days().orEmpty()
        val ranges = mutableListOf<Triple<String, String, String>>() // regionId, from, to
        days.forEach { d ->
            val r = d.regionId ?: return@forEach
            val last = ranges.lastOrNull()
            if (last != null && last.first == r && LocalDate.parse(last.third).plusDays(1).toString() == d.date) {
                ranges[ranges.size - 1] = Triple(r, last.second, d.date)
            } else {
                ranges += Triple(r, d.date, d.date)
            }
        }
        append("Regionen (id in Klammern):\n")
        t.regions.forEach { r ->
            append("- ").append(r.name).append(" (").append(r.id).append(")")
            r.dayTripFromRegionId?.let { from -> append(", Tagesausflug von ").append(t.regionName(from) ?: from) }
            val own = ranges.filter { it.first == r.id }
            if (own.isNotEmpty()) append(": ").append(own.joinToString(", ") { if (it.second == it.third) it.second else "${it.second} bis ${it.third}" })
            append(", ").append(t.saved.count { it.regionId == r.id && it.status != PlaceStatus.DROPPED }).append(" Vorschläge\n")
        }
        val stays = t.stays.filter { it.placeId.isNotBlank() }.sortedBy { it.checkIn }
        if (stays.isNotEmpty()) {
            append("Unterkünfte:\n")
            stays.forEach { s -> append("- ").append(stay(s, t)).append('\n') }
        }
        if (days.isNotEmpty()) {
            append("Reiseplan (Tag: Region, Übernachtung, Titel, Programm, Fahrten):\n")
            days.forEach { d ->
                append("- ").append(shortDate(d.date)).append(" (").append(d.date).append("): ")
                append(listOfNotNull(
                    t.regionName(d.regionId),
                    t.nightStay(d.date)?.let { "Nacht: " + t.stayName(it) },
                    d.day?.title?.takeIf { it.isNotBlank() }?.let { "„$it“" },
                    programItems(d.day, t).takeIf { it.isNotEmpty() }?.joinToString(", ") { i ->
                        if (i.kind == DayItemKind.PLACE) t.places[i.placeId]?.name ?: "Ort" else "Notiz"
                    },
                    t.movementsOn(d.date).takeIf { it.isNotEmpty() }?.joinToString("; "),
                ).joinToString(" · ").ifEmpty { "noch nichts geplant" })
                append('\n')
            }
        }
    }

    private fun stay(s: Stay, t: ChatTrip): String = buildString {
        val p = t.places[s.placeId]
        append(t.stayName(s))
        t.regionName(s.regionId)?.let { append(" (").append(it).append(")") }
        if (s.checkIn.isNotBlank()) append(", ").append(s.checkIn).append(" bis ").append(s.checkOut)
        p?.address?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
        t.point(s.placeId)?.let { append(", Koordinaten ").append(coords(it)) }
    }

    private fun coords(a: Anchor) = String.format(Locale.ROOT, "%.5f,%.5f", a.lat, a.lng)

    private fun programItems(day: Day?, t: ChatTrip): List<DayItem> =
        day?.let { d -> t.dayItems.filter { it.dayId == d.id }.sortedBy { it.sortKey } }.orEmpty()

    /** The user's shared position: coordinates, the nearest stay and saved places around it. */
    fun location(l: de.wanderlogplus.shared.sync.ChatLocation, t: ChatTrip): String = buildString {
        val here = Anchor("Dein Standort", l.lat, l.lng)
        append("Standort des Nutzers (jetzt, \"hier\"): ").append(coords(here))
        l.accuracyMeters?.let { append(", Genauigkeit ca. ").append(distanceText(it / 1000.0)) }
        append(". Entfernungen in den Werkzeugen beziehen sich darauf.\n")
        t.stays.mapNotNull { s -> t.point(s.placeId, t.stayName(s))?.let { p -> p to distanceKm(l.lat, l.lng, p.lat, p.lng) } }.minByOrNull { it.second }?.let { (p, km) ->
            append("Nächste Unterkunft: ").append(p.name).append(", ").append(distanceText(km)).append(" entfernt\n")
        }
        val near = t.saved.filter { it.status != PlaceStatus.DROPPED }
            .mapNotNull { s -> t.point(s.placeId)?.let { p -> s to distanceKm(l.lat, l.lng, p.lat, p.lng) } }
            .filter { it.second <= 3.0 }.sortedBy { it.second }.take(10)
        if (near.isNotEmpty()) {
            append("Hinterlegte Orte im Umkreis von 3 km:\n")
            near.forEach { (s, _) -> append("- ").append(row(s, t, here)).append('\n') }
        }
    }

    /** Where the chat was opened from: the point distances are measured from. */
    fun anchor(c: ChatContext, t: ChatTrip, today: String): Anchor? = when (c.screen) {
        ChatScreens.DAY, ChatScreens.TODAY -> t.dayAnchor(c.date ?: today)
        ChatScreens.PLACE -> c.savedPlaceId?.let { t.savedById[it] }?.let { t.point(it.placeId) }
        ChatScreens.REGION -> c.regionId?.let { t.regionStay(it) }?.let { s -> t.point(s.placeId, "Unterkunft " + t.stayName(s)) }
        else -> null
    }

    /** What the user is looking at right now, with what that screen shows. */
    fun current(c: ChatContext, t: ChatTrip, today: String): String = buildString {
        append("Heutiges Datum: ").append(germanDate(today)).append('\n')
        val anchor = anchor(c, t, today)
        when (c.screen) {
            ChatScreens.DAY, ChatScreens.TODAY -> {
                val date = c.date ?: today
                append(if (c.screen == ChatScreens.TODAY) "Der Nutzer ist auf der Heute-Ansicht. " else "Der Nutzer schaut sich einen Reisetag an. ")
                append(day(date, t))
                val open = t.queries?.suggestionsFor(date).orEmpty()
                if (open.isNotEmpty()) {
                    append("Offene Vorschläge für diesen Tag (Region und Tagesausflüge, Reiter „Vorschläge“; ").append(open.size).append(" insgesamt):\n")
                    sortByDistance(open, t, anchor).take(MAX_CONTEXT_ROWS).forEach { append("- ").append(row(it, t, anchor)).append('\n') }
                }
            }
            ChatScreens.REGION -> {
                val r = c.regionId?.let { t.regionsById[it] }
                if (r == null) {
                    append("Der Nutzer schaut sich Orte ohne Region an.\n")
                    t.saved.filter { it.regionId == null && it.status != PlaceStatus.DROPPED }.take(MAX_CONTEXT_ROWS)
                        .forEach { append("- ").append(row(it, t, null)).append('\n') }
                } else {
                    append("Der Nutzer schaut sich die Region ").append(r.name).append(" (").append(r.id).append(") an.\n")
                    t.stays.filter { it.regionId == r.id }.forEach { append("Unterkunft: ").append(stay(it, t)).append('\n') }
                    val list = t.saved.filter { it.regionId == r.id && it.status != PlaceStatus.DROPPED }
                    append("Hinterlegte Orte der Region (").append(list.size).append("):\n")
                    list.sortedWith(compareBy<SavedPlace> { ChatTools.worthRank(it.worth) }.thenByDescending { t.places[it.placeId]?.rating ?: 0.0 })
                        .take(MAX_CONTEXT_ROWS).forEach { append("- ").append(row(it, t, anchor)).append('\n') }
                }
            }
            ChatScreens.PLACE -> {
                val s = c.savedPlaceId?.let { t.savedById[it] }
                if (s != null) {
                    append("Der Nutzer schaut sich diesen hinterlegten Ort an:\n").append(placeDetail(s, t))
                    val here = t.point(s.placeId)
                    if (here != null) {
                        val stays = t.stays.filter { it.regionId == s.regionId || it.regionId == t.regionsById[s.regionId]?.dayTripFromRegionId }
                        stays.forEach { st ->
                            t.point(st.placeId)?.let { p -> append("Entfernung zur Unterkunft ").append(t.stayName(st)).append(": ").append(distanceText(distanceKm(here.lat, here.lng, p.lat, p.lng))).append('\n') }
                        }
                        val near = t.saved.filter { it.id != s.id && it.status != PlaceStatus.DROPPED }
                            .mapNotNull { o -> t.point(o.placeId)?.let { p -> o to distanceKm(here.lat, here.lng, p.lat, p.lng) } }
                            .filter { it.second <= NEARBY_KM }.sortedBy { it.second }.take(8)
                        if (near.isNotEmpty()) {
                            append("Hinterlegte Orte in der Nähe:\n")
                            near.forEach { (o, _) -> append("- ").append(row(o, t, here)).append('\n') }
                        }
                    }
                }
            }
            ChatScreens.TRIP -> append("Der Nutzer ist in der Reiseübersicht (alle Tage, siehe Reiseplan).\n")
            ChatScreens.PLACES -> append("Der Nutzer ist in der Ortsübersicht (alle Regionen).\n")
            ChatScreens.LOGISTICS -> {
                append("Der Nutzer ist bei der Logistik (Flüge, Züge, Unterkünfte).\n")
                val dates = (t.flights.flatMap { listOfNotNull(it.depart.date) } + t.trains.flatMap { listOfNotNull(it.depart.date) }).distinct().sorted()
                dates.forEach { d -> t.movementsOn(d).forEach { append("- ").append(d).append(' ').append(it).append('\n') } }
            }
            else -> Unit
        }
        anchor?.let { append("Bezugspunkt für Entfernungen: ").append(it.name).append(" (").append(coords(it)).append(")\n") }
    }

    /** A day as the day screen shows it: region, stays, title, note, program (with places and distances), trips. */
    fun day(date: String, t: ChatTrip): String = buildString {
        val q = t.queries
        val regionId = q?.regionFor(date)
        val dayEntity = q?.dayFor(date)
        append("Tag: ").append(germanDate(date)).append(" (").append(date).append(")")
        regionId?.let { append(", Region ").append(t.regionName(it) ?: it).append(" (").append(it).append(")") }
        if (q != null && q.days().firstOrNull { it.date == date }?.isTravelDay == true) append(", Reisetag (Ortswechsel)")
        append('\n')
        val morning = t.morningStay(date)
        val night = t.nightStay(date)
        if (morning != null && morning.id != night?.id) append("Auschecken: ").append(stay(morning, t)).append('\n')
        if (night != null) append("Übernachtung: ").append(stay(night, t)).append('\n')
        t.movementsOn(date).forEach { append("Fahrt: ").append(it).append('\n') }
        dayEntity?.title?.takeIf { it.isNotBlank() }?.let { append("Titel: ").append(it).append('\n') }
        dayEntity?.note?.takeIf { it.isNotBlank() }?.let { append("Notiz: ").append(it.take(800)).append('\n') }
        val anchor = t.dayAnchor(date)
        val items = programItems(dayEntity, t)
        if (items.isEmpty()) {
            append("Programm: noch nichts geplant\n")
        } else {
            append("Programm:\n")
            items.forEach { i ->
                append("- ")
                i.time?.let { append(it).append(' ') }
                if (i.kind == DayItemKind.PLACE) {
                    val p = t.places[i.placeId]
                    append(p?.name ?: "Ort")
                    val s = t.savedFor(i.placeId)
                    append(" [id=").append(s?.id ?: i.placeId).append("]")
                    p?.address?.takeIf { it.isNotBlank() }?.let { append(", ").append(it) }
                    val pt = t.point(i.placeId)
                    if (pt != null && anchor != null) append(", ").append(distanceText(distanceKm(anchor.lat, anchor.lng, pt.lat, pt.lng))).append(" von der Unterkunft")
                    s?.dossier?.verdict?.takeIf { it.isNotBlank() }?.let { append(" – ").append(it.take(160)) }
                    i.legMode?.let { append(" (Anreise: ").append(it.name).append(")") }
                } else {
                    append("Notiz [id=").append(i.id).append("]: ").append(i.text.take(300))
                }
                append('\n')
            }
        }
    }

    fun placeDetail(s: SavedPlace, t: ChatTrip): String = buildString {
        val p = t.places[s.placeId]
        append("Name: ").append(p?.name ?: "?").append(" (id ").append(s.id).append(")\n")
        append("Region: ").append(t.regionName(s.regionId) ?: "keine").append(", Kategorie: ").append(s.category.name).append(", Status: ").append(s.status.name).append('\n')
        p?.address?.let { append("Adresse: ").append(it).append('\n') }
        t.point(s.placeId)?.let { append("Koordinaten: ").append(coords(it)).append('\n') }
        p?.categories?.takeIf { it.isNotEmpty() }?.let { append("Art: ").append(it.joinToString(", ")).append('\n') }
        p?.description?.takeIf { it.isNotBlank() }?.let { append("Beschreibung: ").append(it.take(600)).append('\n') }
        p?.rating?.let { append("Bewertung: ").append(it).append(p.numRatings?.let { n -> " ($n)" } ?: "").append('\n') }
        p?.priceLevel?.let { append("Preisniveau: ").append("¥".repeat(it.coerceIn(1, 4))).append('\n') }
        p?.let { openDays(it) }?.let { append("Geöffnet an: ").append(it).append('\n') }
        p?.openingPeriods?.takeIf { it.isNotEmpty() }?.let { periods ->
            val names = listOf("So", "Mo", "Di", "Mi", "Do", "Fr", "Sa")
            append("Öffnungszeiten: ").append(periods.joinToString(", ") { "${names.getOrElse(it.openDay) { "?" }} ${it.openTime}–${it.closeTime.orEmpty()}" }).append('\n')
        }
        p?.minMinutes?.let { append("Typische Dauer: ").append(it).append(p.maxMinutes?.let { m -> "–$m" } ?: "").append(" Min.\n") }
        p?.website?.takeIf { it.isNotBlank() }?.let { append("Website: ").append(it).append('\n') }
        if (s.note.isNotBlank()) append("Notiz der Reisenden: ").append(s.note.take(500)).append('\n')
        if (s.tags.isNotEmpty()) append("Tags: ").append(s.tags.joinToString(", ")).append('\n')
        s.worth?.let { append("Einschätzung: ").append(it.name).append(s.worthReason.takeIf { r -> r.isNotBlank() }?.let { r -> " – $r" } ?: "").append('\n') }
        s.dossier?.let { d ->
            if (d.verdict.isNotBlank()) append("Fazit: ").append(d.verdict).append('\n')
            if (d.criteria.isNotEmpty()) append("Kriterien: ").append(d.criteria.joinToString("; ") { "${it.label.ifBlank { it.key }} ${it.score}/5" }).append('\n')
            fun list(label: String, l: List<String>) { if (l.isNotEmpty()) append(label).append(": ").append(l.joinToString(" | ")).append('\n') }
            list("Geheimtipps", d.insiderTips)
            list("Versteckte Ecken", d.hiddenSpots)
            list("Gut zu wissen", d.goodToKnow)
            list("Tipps", d.tips)
            list("Hinweise", d.hints)
        }
        t.plannedDates[s.placeId]?.takeIf { it.isNotEmpty() }?.let { append("Eingeplant am: ").append(it.joinToString(", ")).append('\n') }
    }

    /** One compact line per saved place; with the distance to [from] when both have coordinates. */
    fun row(s: SavedPlace, t: ChatTrip, from: Anchor?): String {
        val p = t.places[s.placeId]
        val km = from?.let { a -> t.point(s.placeId)?.let { distanceKm(a.lat, a.lng, it.lat, it.lng) } }
        return listOfNotNull(
            "id=${s.id}",
            p?.name ?: "?",
            t.regionName(s.regionId),
            s.category.name,
            km?.let { distanceText(it) + " entfernt" },
            s.worth?.name,
            p?.rating?.let { "★$it" },
            s.tags.takeIf { it.isNotEmpty() }?.joinToString(" "),
            t.plannedDates[s.placeId]?.takeIf { it.isNotEmpty() }?.let { "eingeplant ${it.joinToString(",")}" },
            (s.dossier?.verdict?.takeIf { it.isNotBlank() } ?: s.worthReason.takeIf { it.isNotBlank() } ?: p?.description)?.take(160),
        ).joinToString(" | ")
    }

    private fun sortByDistance(list: List<SavedPlace>, t: ChatTrip, from: Anchor?): List<SavedPlace> =
        if (from == null) list.sortedBy { ChatTools.worthRank(it.worth) }
        else list.sortedWith(compareBy<SavedPlace> { s -> t.point(s.placeId)?.let { distanceKm(from.lat, from.lng, it.lat, it.lng) } ?: Double.MAX_VALUE }.thenBy { ChatTools.worthRank(it.worth) })

    /** "Mo, Di, …" from the opening periods; null when unknown. */
    fun openDays(p: Place): String? {
        if (p.openingPeriods.isEmpty()) return null
        val names = listOf("So", "Mo", "Di", "Mi", "Do", "Fr", "Sa")
        val open = (0..6).filter { isOpenOn(p.openingPeriods, it) == true }
        return if (open.size == 7) "täglich" else open.joinToString(", ") { names[it] }.ifEmpty { "unbekannt" }
    }

    const val MAX_CONTEXT_ROWS = 30
    const val NEARBY_KM = 15.0
}

private fun shortDateOf(date: String): String = try {
    val d = LocalDate.parse(date)
    "%02d.%02d.".format(d.dayOfMonth, d.monthValue)
} catch (e: Exception) {
    date
}

/** Day of week with 0 = Sunday, as in [isOpenOn]. */
private fun sunday0(d: LocalDate): Int = if (d.dayOfWeek == DayOfWeek.SUNDAY) 0 else d.dayOfWeek.value

/**
 * The chat's tools over the trip and the web. One instance per answer: it collects the cards to show and
 * remembers the web pages found, so web cards can only link to real search results.
 */
class ChatTools(
    private val t: ChatTrip,
    private val web: WebSearch?,
    /** Where the chat was opened (the day's stay, the place): rows show their distance to it. */
    private val anchor: Anchor? = null,
    /** Looks up where a web find is, so it can be added to the trip; null: web finds stay links. */
    private val geocoder: Geocoder? = null,
    /** Timetable connections and routes; null: the chat cannot look them up. */
    private val ways: ChatWays? = null,
    /** Where the user is right now (shared from the app), as "hier" for the tools. */
    private val here: Anchor? = null,
    private val say: (String) -> Unit,
) {
    private var transitCalls = 0
    private val json = Json { ignoreUnknownKeys = true }
    val cards = mutableListOf<ChatCard>()
    private val seenUrls = LinkedHashMap<String, WebSource>()

    val definitions: JsonArray = buildJsonArray {
        add(
            tool(
                "search_saved_places",
                "Durchsucht die hinterlegten Vorschläge (gespeicherte Orte) der Reisenden. Liefert kompakte Zeilen mit id, Name, Region, Kategorie, Entfernung (Luftlinie), Einschätzung, Fazit.",
            ) {
                putJsonObject("query") { put("type", "string"); put("description", "Suchbegriffe (Name, Art, Stichworte), optional") }
                putJsonObject("regionId") { put("type", "string"); put("description", "nur diese Region (inklusive ihrer Tagesausflüge), optional") }
                putJsonObject("category") { put("type", "string"); putJsonArray("enum") { PlaceCategory.entries.forEach { add(JsonPrimitive(it.name)) } } }
                putJsonObject("openOn") { put("type", "string"); put("description", "Datum yyyy-MM-dd: nur Orte, die an diesem Wochentag nicht sicher geschlossen sind") }
                putJsonObject("includePlanned") { put("type", "boolean"); put("description", "auch schon eingeplante/besuchte Orte zeigen (Standard: nein)") }
                putJsonObject("near") {
                    put("type", "string")
                    put("description", "Umkreissuche, nach Entfernung sortiert: " + POINT_HELP)
                }
                putJsonObject("maxKm") { put("type", "number"); put("description", "Radius für near in km (Standard 15)") }
            },
        )
        add(tool("get_place", "Alle Details zu einem hinterlegten Ort (Dossier, Tipps, Öffnungstage).") {
            putJsonObject("id") { put("type", "string"); put("description", "id aus search_saved_places") }
        })
        if (ways != null) {
            add(tool("transit", "Fahrplanauskunft (Bahn/Bus in Japan) zwischen zwei Punkten: Abfahrts-/Ankunftszeiten, Umstiege, Preis. Kostet Geld und dauert: nur für konkrete Fragen, höchstens $MAX_TRANSIT pro Antwort.") {
                putJsonObject("from") { put("type", "string"); put("description", POINT_HELP) }
                putJsonObject("to") { put("type", "string"); put("description", POINT_HELP) }
                putJsonObject("fromStation") { put("type", "string"); put("description", "statt from: japanischer Bahnhofsname, z. B. 中津川") }
                putJsonObject("toStation") { put("type", "string"); put("description", "statt to: japanischer Bahnhofsname") }
                putJsonObject("datetime") { put("type", "string"); put("description", "yyyy-MM-ddTHH:mm, japanische Zeit") }
                putJsonObject("searchType") { put("type", "string"); putJsonArray("enum") { add(JsonPrimitive("depart")); add(JsonPrimitive("arrive")) }; put("description", "depart = ab Uhrzeit, arrive = an bis Uhrzeit (z. B. letzter Zug: arrive spät abends)") }
            })
            add(tool("route", "Weg zu Fuß, mit Rad oder Auto zwischen zwei Punkten: Dauer und Länge.") {
                putJsonObject("from") { put("type", "string"); put("description", POINT_HELP) }
                putJsonObject("to") { put("type", "string"); put("description", POINT_HELP) }
                putJsonObject("mode") { put("type", "string"); putJsonArray("enum") { add(JsonPrimitive("WALK")); add(JsonPrimitive("BICYCLE")); add(JsonPrimitive("DRIVE")) } }
            })
        }
        add(tool("check_day", "Prüft einen Tagesablauf ohne KI: geschätzte Wege und Aufenthalte, Öffnungszeiten/Ruhetage, Zug- und Flugzeiten des Tages. Ohne plan wird das aktuelle Programm geprüft; mit plan ein Vorschlag.") {
            putJsonObject("date") { put("type", "string"); put("description", "yyyy-MM-dd") }
            putJsonObject("plan") {
                put("type", "array")
                put("description", "optional: geplante Reihenfolge")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") { put("type", "string"); put("description", "Orts-id (hinterlegt oder Programm)") }
                        putJsonObject("name") { put("type", "string"); put("description", "für Orte ohne id (Webfund)") }
                        putJsonObject("lat") { put("type", "number") }
                        putJsonObject("lng") { put("type", "number") }
                        putJsonObject("time") { put("type", "string"); put("description", "HH:mm, optional") }
                        putJsonObject("minutes") { put("type", "integer"); put("description", "Aufenthalt, optional") }
                    }
                }
            }
        })
        add(tool("get_day", "Ein Reisetag mit Unterkünften, Fahrten, Programm (mit Adressen und Entfernungen) und Notizen.") {
            putJsonObject("date") { put("type", "string"); put("description", "yyyy-MM-dd") }
        })
        if (web != null) {
            add(tool("web_search", "Websuche (Parallel). Nur wenn der Nutzer nach neuen Orten, aktuellen Infos oder Webrecherche fragt oder die hinterlegten Vorschläge nicht reichen.") {
                putJsonObject("objective") { put("type", "string"); put("description", "was genau gesucht wird, ein Satz") }
                putJsonObject("queries") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "1-3 kurze Suchanfragen") }
            })
        }
        add(tool("show_day_plan", "Zeigt einen vollständigen Ablaufvorschlag für einen Tag als Plan-Karte; der Nutzer übernimmt ihn per Button. Wird automatisch geprüft (Ergebnis kommt zurück); ein erneuter Aufruf für denselben Tag ersetzt die Karte.") {
            putJsonObject("date") { put("type", "string"); put("description", "yyyy-MM-dd") }
            putJsonObject("steps") {
                put("type", "array")
                put("description", "in Reihenfolge des neuen Ablaufs; jeder bisherige Programmpunkt als keep oder remove")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("action") { put("type", "string"); putJsonArray("enum") { add(JsonPrimitive("keep")); add(JsonPrimitive("add")); add(JsonPrimitive("remove")) } }
                        putJsonObject("id") { put("type", "string"); put("description", "keep/remove: id des Programmpunkts; add: id eines hinterlegten Orts") }
                        putJsonObject("place") { put("type", "string"); put("description", "add ohne id: Name und Ort eines Webfunds zum Nachschlagen der Lage") }
                        putJsonObject("title") { put("type", "string"); put("description", "Anzeigename für place") }
                        putJsonObject("category") { put("type", "string"); putJsonArray("enum") { PlaceCategory.entries.forEach { add(JsonPrimitive(it.name)) } } }
                        putJsonObject("time") { put("type", "string"); put("description", "HH:mm, optional") }
                        putJsonObject("reason") { put("type", "string"); put("description", "kurzer Grund, ein paar Wörter") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("action")) }
                }
            }
        })
        add(tool("show_place_cards", "Zeigt hinterlegte Orte als antippbare Karten unter der Antwort (Reihenfolge = Empfehlung). Die Karten haben einen Button „Einplanen“; date/after belegen ihn vor.") {
            putJsonObject("ids") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "ids ohne Planungsvorschlag") }
            putJsonObject("items") {
                put("type", "array")
                put("description", "alternativ zu ids: Orte mit Planungsvorschlag")
                putJsonObject("items") {
                    put("type", "object")
                    putJsonObject("properties") {
                        putJsonObject("id") { put("type", "string") }
                        putJsonObject("date") { put("type", "string"); put("description", "vorgeschlagener Tag yyyy-MM-dd, optional") }
                        putJsonObject("after") { put("type", "string"); put("description", "id des Programmpunkts dieses Tages, nach dem er passt, optional") }
                    }
                    putJsonArray("required") { add(JsonPrimitive("id")) }
                }
            }
        })
        if (web != null) {
            add(tool("show_web_cards", "Zeigt Webseiten aus web_search als antippbare Karten (nur URLs aus den Suchergebnissen).") {
                putJsonObject("items") {
                    put("type", "array")
                    putJsonObject("items") {
                        put("type", "object")
                        putJsonObject("properties") {
                            putJsonObject("title") { put("type", "string") }
                            putJsonObject("url") { put("type", "string") }
                            putJsonObject("snippet") { put("type", "string"); put("description", "ein kurzer Satz auf Deutsch") }
                            putJsonObject("place") { put("type", "string"); put("description", "nur für einen konkreten Ort: Name und Ort zum Nachschlagen der Lage, z. B. „Kiso Onsen Hotel, Agematsu“. Dann kann der Nutzer ihn zur Reise hinzufügen.") }
                            putJsonObject("category") { put("type", "string"); putJsonArray("enum") { PlaceCategory.entries.forEach { add(JsonPrimitive(it.name)) } } }
                            putJsonObject("date") { put("type", "string"); put("description", "vorgeschlagener Tag yyyy-MM-dd, optional") }
                            putJsonObject("after") { put("type", "string"); put("description", "id des Programmpunkts dieses Tages, nach dem er passt, optional") }
                        }
                        putJsonArray("required") { add(JsonPrimitive("title")); add(JsonPrimitive("url")) }
                    }
                }
            })
        }
    }

    private fun tool(name: String, description: String, props: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", name)
            put("description", description)
            putJsonObject("parameters") {
                put("type", "object")
                putJsonObject("properties", props)
            }
        }
    }

    /** Runs one tool call; the result is text for the model (errors too, so the model can carry on). */
    fun run(name: String, arguments: String): String {
        val args = try { json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject } catch (e: Exception) { JsonObject(emptyMap()) }
        return when (name) {
            "search_saved_places" -> { say("Durchsuche hinterlegte Vorschläge …"); search(args) }
            "get_place" -> getPlace(args)
            "get_day" -> args.s("date")?.takeIf { d -> runCatching { LocalDate.parse(d) }.isSuccess }
                ?.let { d -> say("Lese Tag ${shortDateOf(d)} …"); ChatContextText.day(d, t) } ?: "Ungültiges Datum."
            "web_search" -> webSearch(args)
            "transit" -> transit(args)
            "check_day" -> checkDay(args)
            "route" -> route(args)
            "show_day_plan" -> showDayPlan(args)
            "show_place_cards" -> showPlaces(args)
            "show_web_cards" -> showWeb(args)
            else -> "Unbekanntes Werkzeug."
        }
    }

    private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    private fun JsonObject.list(k: String): List<String> = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }

    private fun resolveRegion(v: String?): String? = v?.let { r -> t.regionsById[r]?.id ?: t.regions.firstOrNull { it.name.equals(r, ignoreCase = true) }?.id }

    fun search(args: JsonObject): String {
        val regionId = resolveRegion(args.s("regionId"))
        if (args.s("regionId") != null && regionId == null) return "Unbekannte Region ${args.s("regionId")}. Gültige ids stehen in der Reiseübersicht."
        val category = args.s("category")?.let { c -> PlaceCategory.entries.firstOrNull { it.name.equals(c, ignoreCase = true) } }
        val dow = args.s("openOn")?.let { runCatching { sunday0(LocalDate.parse(it)) }.getOrNull() }
        val includePlanned = (args["includePlanned"] as? JsonPrimitive)?.contentOrNull == "true"
        val regionIds = regionId?.let { t.regionWithDayTrips(it) }
        val nearArg = args.s("near")
        val near = nearArg?.let { resolvePoint(it) }
        if (nearArg != null && near == null) return "Unbekannter Bezugspunkt $nearArg (ohne Koordinaten?). Nutze eine Orts-id, ein Datum oder lat,lng."
        val maxKm = (args["maxKm"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()?.takeIf { it > 0 } ?: DEFAULT_KM
        val from = near ?: anchor
        fun km(s: SavedPlace): Double? = from?.let { a -> t.point(s.placeId)?.let { distanceKm(a.lat, a.lng, it.lat, it.lng) } }
        var noCoords = 0
        val pool = t.saved.filter { s ->
            s.status != PlaceStatus.DROPPED &&
                (regionIds == null || s.regionId in regionIds) &&
                (category == null || s.category == category) &&
                (includePlanned || (s.status == PlaceStatus.IDEA && t.plannedDates[s.placeId].isNullOrEmpty())) &&
                (dow == null || t.places[s.placeId]?.let { p -> isOpenOn(p.openingPeriods, dow) } != false)
        }.filter { s ->
            if (near == null) return@filter true
            val d = km(s)
            if (d == null) noCoords++
            d != null && d <= maxKm
        }
        val tokens = args.s("query")?.lowercase()?.split(Regex("[^\\p{L}\\p{N}]+"))?.filter { it.length >= 2 }.orEmpty()
        val scored = pool.map { it to matchScore(it, tokens) }
        val hits = if (tokens.isEmpty()) scored else scored.filter { it.second > 0 }
        val ranked = hits.sortedWith(
            compareByDescending<Pair<SavedPlace, Int>> { it.second }
                .thenBy { if (near != null) km(it.first) ?: Double.MAX_VALUE else 0.0 }
                .thenBy { worthRank(it.first.worth) }
                .thenByDescending { t.places[it.first.placeId]?.rating ?: 0.0 },
        ).map { it.first }
        val shown = (if (ranked.isEmpty() && tokens.isNotEmpty()) {
            pool.sortedWith(compareBy<SavedPlace> { if (near != null) km(it) ?: Double.MAX_VALUE else 0.0 }.thenBy { worthRank(it.worth) }.thenByDescending { t.places[it.placeId]?.rating ?: 0.0 })
        } else {
            ranked
        }).take(MAX_ROWS)
        if (shown.isEmpty()) {
            return "Keine passenden hinterlegten Vorschläge" + (near?.let { " im Umkreis von ${distanceText(maxKm)} um ${it.name}" } ?: "") +
                (if (noCoords > 0) " ($noCoords ohne Koordinaten ausgelassen)" else "") + "."
        }
        return buildString {
            near?.let { append("Umkreis ").append(distanceText(maxKm)).append(" um ").append(it.name).append(if (noCoords > 0) " ($noCoords ohne Koordinaten ausgelassen)" else "").append(".\n") }
            if (ranked.isEmpty() && tokens.isNotEmpty()) append("Keine Treffer für die Suchbegriffe; stattdessen die besten Orte der Auswahl:\n")
            append(pool.size).append(" Orte in der Auswahl, gezeigt ").append(shown.size).append(":\n")
            shown.forEach { s -> append(ChatContextText.row(s, t, from)).append('\n') }
        }
    }

    private fun checkDay(args: JsonObject): String {
        val date = args.s("date")?.takeIf { d -> t.queries?.days()?.any { it.date == d } == true } ?: return "date fehlt oder ist kein Reisetag."
        say("Prüfe den Ablauf am ${shortDateOf(date)} …")
        val check = ChatDayCheck(t)
        val plan = (args["plan"] as? JsonArray)?.mapNotNull { it as? JsonObject }
        val stops = plan?.mapNotNull { o -> planStop(o) } ?: check.programStops(date)
        if (stops.isEmpty()) return "Am ${shortDateOf(date)} ist noch nichts geplant."
        return check.check(date, stops)
    }

    private fun planStop(o: JsonObject): PlanStop? {
        val id = o.s("id")
        val placeId = id?.let { t.savedById[it]?.placeId ?: it.takeIf { p -> t.places.containsKey(p) } }
        val lat = (o["lat"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val lng = (o["lng"] as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()
        val name = placeId?.let { t.places[it]?.name } ?: o.s("name") ?: return null
        return PlanStop(
            name, placeId, lat.takeIf { placeId == null }, lng.takeIf { placeId == null },
            time = o.s("time")?.takeIf { ChatDayCheck.mins(it) != null },
            minutes = (o["minutes"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()?.takeIf { it in 5..600 },
        )
    }

    private fun transit(args: JsonObject): String {
        val w = ways ?: return "Fahrplanauskunft ist nicht eingerichtet."
        if (transitCalls >= MAX_TRANSIT) return "Schon $MAX_TRANSIT Fahrplanauskünfte in dieser Antwort; antworte mit dem, was du hast."
        val datetime = args.s("datetime")?.takeIf { runCatching { java.time.LocalDateTime.parse(it) }.isSuccess }
            ?: return "datetime fehlt oder ist ungültig (yyyy-MM-ddTHH:mm)."
        val fromStation = args.s("fromStation")
        val toStation = args.s("toStation")
        val from = args.s("from")?.let { resolvePoint(it) ?: return "Unbekannter Startpunkt $it." }
        val to = args.s("to")?.let { resolvePoint(it) ?: return "Unbekanntes Ziel $it." }
        if ((from == null && fromStation == null) || (to == null && toStation == null)) return "Start und Ziel fehlen (from/to oder fromStation/toStation)."
        transitCalls++
        val fromName = from?.name ?: fromStation!!
        val toName = to?.name ?: toStation!!
        say("Suche Verbindung ${fromName.take(30)} → ${toName.take(30)} …")
        val req = de.wanderlogplus.shared.sync.TransitRequest(
            from = from?.let { de.wanderlogplus.shared.sync.TransitPoint(it.lat, it.lng, it.name) },
            to = to?.let { de.wanderlogplus.shared.sync.TransitPoint(it.lat, it.lng, it.name) },
            fromStation = fromStation, toStation = toStation, datetime = datetime,
            searchType = if (args.s("searchType") == "arrive") "arrive" else "depart",
        )
        return try {
            ChatWaysText.transit(w.transit(req), fromName, toName)
        } catch (e: de.wanderlogplus.server.TransitNotConfigured) {
            "Fahrplanauskunft ist auf dem Server nicht eingerichtet."
        } catch (e: de.wanderlogplus.server.NoRoute) {
            "Keine Verbindung gefunden (evtl. gleicher Bahnhof: dann ist es ein Fußweg)."
        } catch (e: de.wanderlogplus.server.NoStation) {
            "Kein Bahnhof in der Nähe gefunden."
        } catch (e: de.wanderlogplus.server.TransitUnavailable) {
            "Fahrplanauskunft gerade nicht verfügbar (${e.code}). Sag das dem Nutzer."
        } catch (e: de.wanderlogplus.server.BadInput) {
            "Ungültige Anfrage."
        }
    }

    private fun route(args: JsonObject): String {
        val w = ways ?: return "Routen sind nicht eingerichtet."
        val from = args.s("from")?.let { resolvePoint(it) } ?: return "Unbekannter oder fehlender Startpunkt."
        val to = args.s("to")?.let { resolvePoint(it) } ?: return "Unbekanntes oder fehlendes Ziel."
        val mode = when (args.s("mode")?.uppercase()) {
            "DRIVE" -> de.wanderlogplus.shared.sync.TravelMode.DRIVE
            "BICYCLE" -> de.wanderlogplus.shared.sync.TravelMode.BICYCLE
            else -> de.wanderlogplus.shared.sync.TravelMode.WALK
        }
        val label = when (mode) {
            de.wanderlogplus.shared.sync.TravelMode.DRIVE -> "Auto"
            de.wanderlogplus.shared.sync.TravelMode.BICYCLE -> "Rad"
            else -> "Zu Fuß"
        }
        say("Berechne Weg ${from.name.take(30)} → ${to.name.take(30)} …")
        val crow = distanceText(distanceKm(from.lat, from.lng, to.lat, to.lng))
        return try {
            "${from.name} → ${to.name}: " + ChatWaysText.route(w.route(de.wanderlogplus.shared.sync.RouteRequest(from.lat, from.lng, to.lat, to.lng, mode)), label)
        } catch (e: de.wanderlogplus.server.RoutesNotConfigured) {
            "Routen sind auf dem Server nicht eingerichtet; Luftlinie $crow."
        } catch (e: de.wanderlogplus.server.NoRoute) {
            "Keine Route ($label) gefunden; Luftlinie $crow."
        } catch (e: de.wanderlogplus.server.RoutesUnavailable) {
            "Routen gerade nicht verfügbar; Luftlinie $crow."
        } catch (e: de.wanderlogplus.server.BadInput) {
            "Ungültige Anfrage."
        }
    }

    /** A saved place id, a place id, a date (that night's stay), "kontext" or "lat,lng" as a point. */
    private fun resolvePoint(v: String): Anchor? {
        Regex("^\\s*(-?\\d+(?:\\.\\d+)?)\\s*,\\s*(-?\\d+(?:\\.\\d+)?)\\s*$").find(v)?.let { m ->
            return Anchor("Punkt $v", m.groupValues[1].toDouble(), m.groupValues[2].toDouble())
        }
        if (v.equals("kontext", ignoreCase = true)) return anchor
        if (v.equals("hier", ignoreCase = true) || v.equals("standort", ignoreCase = true)) return here
        if (runCatching { LocalDate.parse(v) }.isSuccess) return t.dayAnchor(v)
        t.savedById[v]?.let { return t.point(it.placeId) }
        return t.point(v)
    }

    private fun matchScore(s: SavedPlace, tokens: List<String>): Int {
        if (tokens.isEmpty()) return 0
        val p = t.places[s.placeId]
        val name = p?.name.orEmpty().lowercase()
        val hay = listOfNotNull(
            p?.description, p?.address, s.note, s.worthReason, s.dossier?.verdict, t.regionName(s.regionId), s.category.name,
        ).plus(p?.categories.orEmpty()).plus(s.tags).plus(s.dossier?.let { it.insiderTips + it.tips + it.hiddenSpots + it.goodToKnow }.orEmpty())
            .joinToString(" ").lowercase()
        return tokens.sumOf { tok -> (if (tok in name) 3 else 0) + (if (tok in hay) 1 else 0) }
    }

    private fun getPlace(args: JsonObject): String {
        val s = args.s("id")?.let { t.savedById[it] } ?: return "Unbekannte id."
        say("Lese Details zu ${t.places[s.placeId]?.name ?: "einem Ort"} …")
        return ChatContextText.placeDetail(s, t)
    }

    private fun webSearch(args: JsonObject): String {
        val w = web ?: return "Websuche ist nicht eingerichtet."
        val queries = args.list("queries").ifEmpty { listOfNotNull(args.s("objective")) }
        val objective = args.s("objective") ?: queries.firstOrNull() ?: return "Keine Suchanfrage."
        say("Websuche: " + queries.firstOrNull().orEmpty().take(60) + " …")
        val found = try {
            w.search(objective, queries)
        } catch (e: AiFailed) {
            say("Websuche fehlgeschlagen")
            return "Websuche fehlgeschlagen (${e.code}). Antworte ohne Webergebnisse und sage das dem Nutzer."
        }
        if (found.isEmpty()) return "Websuche ohne Ergebnis."
        found.forEach { seenUrls[it.url] = it }
        return buildString {
            found.forEach { r ->
                append("- ").append(r.title ?: r.url).append(" (").append(r.url).append("): ")
                append(r.excerpts.joinToString(" ").replace(Regex("\\s+"), " ").take(900)).append('\n')
            }
        }
    }

    /** The suggested day if it is a trip day, else null. */
    private fun planDate(v: String?): String? = v?.takeIf { d -> t.queries?.days()?.any { it.date == d } == true }

    /** The suggested "after" if it is a program place of [date] (saved place or place id), else null. */
    private fun planAfter(date: String?, v: String?): String? {
        if (date == null || v == null) return null
        val day = t.queries?.dayFor(date) ?: return null
        val placeId = t.savedById[v]?.placeId ?: v
        return v.takeIf { t.dayItems.any { it.dayId == day.id && it.kind == DayItemKind.PLACE && it.placeId == placeId } }
    }

    /** The program item [ref] points at on [dayId]: an item id, a saved place id or a place id. */
    private fun itemFor(dayId: String, ref: String): DayItem? {
        val items = t.dayItems.filter { it.dayId == dayId }
        items.firstOrNull { it.id == ref }?.let { return it }
        val placeId = t.savedById[ref]?.placeId ?: ref
        return items.firstOrNull { it.kind == DayItemKind.PLACE && it.placeId == placeId }
    }

    private fun showDayPlan(args: JsonObject): String {
        val date = planDate(args.s("date")) ?: return "date fehlt oder ist kein Reisetag."
        val day = t.queries?.dayFor(date)
        val program = day?.let { d -> t.dayItems.filter { it.dayId == d.id }.sortedBy { it.sortKey } }.orEmpty()
        val raw = (args["steps"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        if (raw.isEmpty()) return "steps fehlen."
        say("Stelle den Plan zusammen …")
        val steps = mutableListOf<ChatPlanStep>()
        val used = mutableSetOf<String>()
        val notes = mutableListOf<String>()
        fun itemTitle(i: DayItem) = if (i.kind == DayItemKind.PLACE) t.places[i.placeId]?.name ?: "Ort" else "Notiz: " + i.text.lineSequence().firstOrNull().orEmpty().take(40)
        raw.forEach { o ->
            val time = o.s("time")?.takeIf { ChatDayCheck.mins(it) != null }
            val reason = o.s("reason")?.take(80)
            when (o.s("action")?.lowercase()) {
                "keep", "remove" -> {
                    val item = o.s("id")?.let { ref -> day?.let { itemFor(it.id, ref) } } ?: run { notes += "unbekannter Programmpunkt ${o.s("id")}"; return@forEach }
                    if (!used.add(item.id)) return@forEach
                    val keep = o.s("action") == "keep"
                    steps += ChatPlanStep(
                        if (keep) ChatPlanStep.KEEP else ChatPlanStep.REMOVE, itemTitle(item), itemId = item.id,
                        time = time.takeIf { keep && it != item.time }, oldTime = item.time, reason = reason,
                    )
                }
                "add" -> {
                    val id = o.s("id")
                    val saved = id?.let { t.savedById[it] }
                    when {
                        saved != null -> {
                            if (program.any { it.kind == DayItemKind.PLACE && it.placeId == saved.placeId }) { notes += "${t.places[saved.placeId]?.name} ist schon im Programm (keep nutzen)"; return@forEach }
                            if (!used.add("s:" + saved.id)) return@forEach
                            steps += ChatPlanStep(ChatPlanStep.ADD, t.places[saved.placeId]?.name ?: "Ort", savedPlaceId = saved.id, time = time, reason = reason)
                        }
                        o.s("place") != null -> {
                            val title = o.s("title") ?: o.s("place")!!
                            val probe = ChatCard(ChatCard.KIND_WEB, title)
                            val found = locate(o.s("place")!!, probe, o.s("category"), date) ?: run { notes += "Lage von ${o.s("place")} nicht gefunden"; return@forEach }
                            val known = found.savedPlaceId
                            if (known != null) {
                                if (program.any { it.kind == DayItemKind.PLACE && it.placeId == t.savedById[known]?.placeId }) return@forEach
                                steps += ChatPlanStep(ChatPlanStep.ADD, t.places[t.savedById[known]?.placeId]?.name ?: title, savedPlaceId = known, time = time, reason = reason)
                            } else {
                                steps += ChatPlanStep(
                                    ChatPlanStep.ADD, title, lat = found.lat, lng = found.lng, address = found.address,
                                    category = found.category ?: PlaceCategory.SIGHT.name, time = time, reason = reason,
                                )
                            }
                        }
                        else -> notes += "add ohne gültige id oder place"
                    }
                }
                else -> notes += "unbekannte action"
            }
        }
        // Items the plan forgot stay where they were: after the step of the item before them.
        program.forEachIndexed { idx, item ->
            if (item.id in used) return@forEachIndexed
            val own = item.time?.let { ChatDayCheck.mins(it) }
            val at = if (own != null) {
                // A fixed time decides: before the first step planned later.
                fun timeOf(st: ChatPlanStep) = (st.time ?: st.oldTime)?.let { ChatDayCheck.mins(it) }
                steps.indexOfFirst { st -> st.action != ChatPlanStep.REMOVE && (timeOf(st) ?: -1) > own }.takeIf { it >= 0 } ?: steps.size
            } else {
                val before = program.take(idx).lastOrNull { b -> steps.any { it.itemId == b.id } }
                before?.let { b -> steps.indexOfFirst { it.itemId == b.id } + 1 } ?: 0
            }
            steps.add(at, ChatPlanStep(ChatPlanStep.KEEP, itemTitle(item), itemId = item.id, oldTime = item.time))
            used += item.id
        }
        if (steps.none { it.action != ChatPlanStep.REMOVE }) return "Der Plan ist leer."
        val stops = steps.filter { it.action != ChatPlanStep.REMOVE }.mapNotNull { st ->
            val item = st.itemId?.let { id -> program.firstOrNull { it.id == id } }
            when {
                item != null && item.kind != DayItemKind.PLACE -> null
                item != null -> PlanStop(st.title, item.placeId, time = st.time ?: item.time, legMode = item.legMode, legMinutes = item.legTransit?.routes?.firstOrNull()?.durationMinutes)
                st.savedPlaceId != null -> PlanStop(st.title, t.savedById[st.savedPlaceId]?.placeId, time = st.time)
                else -> PlanStop(st.title, lat = st.lat, lng = st.lng, time = st.time, category = st.category?.let { c -> PlaceCategory.entries.firstOrNull { it.name == c } })
            }
        }
        val check = ChatDayCheck(t).check(date, stops)
        val problems = check.substringAfter("Probleme:\n", "").trim().takeIf { it.isNotEmpty() }
        cards.removeAll { it.kind == ChatCard.KIND_PLAN && it.plan?.date == date }
        if (cards.size >= MAX_CARDS) cards.removeAt(cards.lastIndex)
        cards += ChatCard(
            ChatCard.KIND_PLAN, "Plan für " + germanDate(date),
            subtitle = "${steps.count { it.action == ChatPlanStep.ADD }} neu · ${steps.count { it.action == ChatPlanStep.REMOVE }} entfällt",
            plan = ChatPlan(date, steps, check = problems ?: "Keine Probleme gefunden."),
        )
        return buildString {
            append("Plan-Karte gezeigt.")
            if (notes.isNotEmpty()) append(" Hinweise: ").append(notes.joinToString("; ")).append('.')
            append("\nPrüfung:\n").append(check)
            if (problems != null) append("Wenn sich die Probleme lösen lassen, zeig einen verbesserten Plan (gleicher Tag ersetzt die Karte); sonst erkläre sie.")
        }
    }

    private fun showPlaces(args: JsonObject): String {
        val wanted = args.list("ids").map { Triple(it, null as String?, null as String?) } +
            (args["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.mapNotNull { o -> o.s("id")?.let { Triple(it, o.s("date"), o.s("after")) } }
        val unknown = mutableListOf<String>()
        wanted.forEach { (id, date, after) ->
            val s = t.savedById[id] ?: run { unknown += id; return@forEach }
            if (cards.any { it.savedPlaceId == id }) return@forEach
            if (cards.size >= MAX_CARDS) return@forEach
            val subtitle = listOfNotNull(t.regionName(s.regionId), worthLabel(s.worth)).joinToString(" · ").ifEmpty { null }
            val day = planDate(date)
            cards += ChatCard(
                ChatCard.KIND_PLACE, t.places[s.placeId]?.name ?: "Ort", savedPlaceId = id, subtitle = subtitle,
                planDate = day, planAfter = planAfter(day, after),
            )
        }
        return if (unknown.isEmpty()) "Karten werden gezeigt." else "Karten gezeigt; unbekannte ids ignoriert: ${unknown.joinToString(", ")}"
    }

    private fun showWeb(args: JsonObject): String {
        val items = (args["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        var skipped = 0
        val notFound = mutableListOf<String>()
        items.forEach { o ->
            val url = o.s("url") ?: return@forEach
            val source = seenUrls[url] ?: run { skipped++; return@forEach }
            if (cards.any { it.url == url } || cards.size >= MAX_CARDS) return@forEach
            val title = (o.s("title") ?: source.title ?: ParallelResearcher.hostOf(url)).take(120)
            val day = planDate(o.s("date"))
            val card = ChatCard(
                ChatCard.KIND_WEB, title, subtitle = (o.s("snippet")?.take(160) ?: ParallelResearcher.hostOf(url)), url = url,
                planDate = day, planAfter = planAfter(day, o.s("after")),
            )
            val located = o.s("place")?.let { q -> locate(q, card, o.s("category"), day) ?: run { notFound += q; null } }
            cards += located ?: card
        }
        return buildString {
            append(if (skipped == 0) "Karten werden gezeigt." else "$skipped Links ignoriert: nur URLs aus web_search sind erlaubt.")
            if (notFound.isNotEmpty()) append(" Lage nicht gefunden (nur als Link): ").append(notFound.joinToString(", "))
        }
    }

    /**
     * Looks up where [query] is; the hit must be near the trip (a stay or a saved place within [MAX_LOCATE_KM]).
     * An already saved place at the spot is linked instead of offered again.
     */
    private fun locate(query: String, card: ChatCard, category: String?, day: String?): ChatCard? {
        val g = geocoder ?: return null
        say("Suche Lage von ${query.take(40)} …")
        val hit = try { g.find(query) } catch (e: GeocoderUnavailable) { null } ?: return null
        val known = (t.stays.map { it.placeId to it.regionId } + t.saved.map { it.placeId to it.regionId })
            .mapNotNull { (pid, region) -> t.point(pid)?.let { Triple(pid, region, distanceKm(hit.lat, hit.lng, it.lat, it.lng)) } }
        val nearest = known.minByOrNull { it.third } ?: return null
        if (nearest.third > MAX_LOCATE_KM) return null
        val same = t.saved.firstOrNull { s ->
            s.status != PlaceStatus.DROPPED && t.point(s.placeId)?.let { distanceKm(hit.lat, hit.lng, it.lat, it.lng) < SAME_PLACE_KM } == true
        }
        val region = day?.let { t.queries?.regionFor(it) } ?: known.filter { it.second != null }.minByOrNull { it.third }?.second
        return card.copy(
            lat = hit.lat, lng = hit.lng, address = hit.address,
            category = category?.let { c -> PlaceCategory.entries.firstOrNull { it.name.equals(c, ignoreCase = true) }?.name },
            regionId = region, savedPlaceId = same?.id,
        )
    }

    companion object {
        const val MAX_ROWS = 25
        const val DEFAULT_KM = 15.0
        const val MAX_TRANSIT = 3
        const val POINT_HELP = "id eines Orts (hinterlegt oder aus dem Programm), ein Datum yyyy-MM-dd (= Unterkunft dieser Nacht), 'kontext', 'hier' (Standort des Nutzers, falls geteilt) oder 'lat,lng'"
        const val MAX_LOCATE_KM = 60.0
        const val SAME_PLACE_KM = 0.15
        const val MAX_CARDS = 10

        fun worthRank(w: Worth?): Int = when (w) {
            Worth.MUST -> 0
            Worth.WORTH -> 1
            null -> 2
            Worth.OPTIONAL -> 3
            Worth.SKIP -> 4
        }

        fun worthLabel(w: Worth?): String? = when (w) {
            Worth.MUST -> "Muss man sehen"
            Worth.WORTH -> "Lohnt sich"
            Worth.OPTIONAL -> "Wenn Zeit ist"
            Worth.SKIP -> "Eher auslassen"
            null -> null
        }
    }
}

/** An assistant answer: text (Markdown) and the cards below it. */
data class ChatAnswer(val text: String, val cards: List<ChatCard>)

/** Answers chat messages with the model and the [ChatTools]. */
class ChatAgent(
    private val model: ToolChatModel,
    private val web: WebSearch?,
    private val geocoder: Geocoder? = null,
    private val ways: ChatWays? = null,
    private val today: () -> String = { LocalDate.now().toString() },
) {

    /** Answers the last user message of [history] (the earlier ones are context). Blocking; throws [AiFailed]. */
    fun answer(history: List<ChatMessage>, trip: ChatTrip, location: de.wanderlogplus.shared.sync.ChatLocation? = null, say: (String) -> Unit = {}): ChatAnswer {
        val last = history.lastOrNull { it.role == ChatMessage.ROLE_USER } ?: throw AiFailed("ai_bad_answer")
        val context = last.context ?: ChatContext()
        val here = location?.let { Anchor("Dein Standort", it.lat, it.lng) }
        val tools = ChatTools(trip, web, here ?: ChatContextText.anchor(context, trip, today()), geocoder, ways, here, say)
        val messages = mutableListOf(system(systemPrompt(trip, context, location)))
        history.filter { it.role != ChatMessage.ROLE_ERROR }.takeLast(MAX_HISTORY).forEach { m ->
            messages += if (m.role == ChatMessage.ROLE_USER) {
                msg("user", (m.context?.label?.let { "[Geöffnet bei: $it] " } ?: "") + m.text)
            } else {
                msg("assistant", m.text + (m.cards.takeIf { it.isNotEmpty() }?.let { c -> "\n(Gezeigte Karten: " + c.joinToString(", ") { k -> k.title + (k.savedPlaceId?.let { " [id=$it]" } ?: k.url?.let { " [$it]" } ?: "") } + ")" } ?: ""))
            }
        }
        say("Denke nach …")
        repeat(MAX_ROUNDS + 1) { round ->
            if (round == MAX_ROUNDS) messages += system("Antworte jetzt dem Nutzer ohne weitere Werkzeuge.")
            val turn = model.turn(messages, tools.definitions)
            if (turn.calls.isEmpty() || round == MAX_ROUNDS) {
                val text = turn.text?.trim().orEmpty()
                if (text.isEmpty() && tools.cards.isEmpty()) throw AiFailed("ai_bad_answer")
                return ChatAnswer(text.ifEmpty { "Hier meine Vorschläge:" }, tools.cards.toList())
            }
            messages += turn.message
            turn.calls.forEach { c ->
                messages += buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", c.id)
                    put("content", tools.run(c.name, c.arguments).take(MAX_TOOL_CHARS))
                }
            }
            say("Formuliere Antwort …")
        }
        throw AiFailed("ai_bad_answer")
    }

    fun systemPrompt(trip: ChatTrip, context: ChatContext, location: de.wanderlogplus.shared.sync.ChatLocation? = null): String = buildString {
        append(
            """
            Du bist der Reiseassistent der App „Wanderlog Plus“ für eine Japanreise. Antworte auf Deutsch, freundlich und knapp
            (Markdown: kurze Absätze, Listen, **fett**; keine Tabellen, keine Links im Text).
            - Für Vorschläge suchst du zuerst mit search_saved_places in den hinterlegten Vorschlägen der Reisenden und empfiehlst bevorzugt diese.
              Nutze dafür den Kontext (Region und Datum des Tages, Öffnungstage mit openOn) und die Wünsche des Nutzers.
            - Achte auf Entfernungen (Luftlinie, in den Zeilen angegeben). Unterkünfte und Programm mit Adressen stehen unten.
              Bei „in der Nähe“, „nicht weit“, „auf dem Weg“ suchst du mit near (Bezugspunkt: Unterkunft, ein Programmpunkt oder Koordinaten)
              und maxKm (in der Nähe ≈ bis 15 km, zu Fuß ≈ 2 km). Nenne bei Empfehlungen die Entfernung; schlage nichts weit Entferntes vor,
              ohne das zu sagen. Für andere Tage nutzt du get_day.
            - Wünscht sich der Nutzer einen Tagesablauf („plan mir den Tag“, „füll die Lücke“, „bau den Tag regenfest um“), stellst du ihn
              aus hinterlegten Orten (und bei Bedarf Webfunden) zusammen und zeigst ihn mit show_day_plan: alle bisherigen Programmpunkte
              als keep (in neuer Reihenfolge) oder remove, neue als add, mit sinnvollen Uhrzeiten. Entferne nur, was dem Wunsch im Weg
              steht, und sag im Text kurz, was sich ändert. Löse Probleme der Prüfung, bevor du antwortest, wenn es geht.
            - Teilt der Nutzer seinen Standort, beziehen sich „hier“, „in der Nähe“ und „von hier“ darauf (near/from/to = "hier").
            - Fragt der Nutzer, ob ein Tag passt („schaffen wir das?“, „Plan-Check“), nutzt du check_day und erklärst die Probleme
              mit konkreten Lösungen (früher los, Reihenfolge tauschen, etwas streichen). Die Zeiten dort sind Schätzungen; sag das.
            """.trimIndent(),
        ).append('\n')
        if (ways != null) {
            append(
                """
                - Für Fragen nach Verbindungen (Zug/Bus, „wann fährt der letzte Zug“, „wie komme ich zurück“) nutzt du transit,
                  für Fußwege und Autofahrten route. Nenne Abfahrt, Umstiege, Ankunft und Dauer. Rate keine Fahrzeiten.
                """.trimIndent(),
            ).append('\n')
        }
        append(
            """
            - Jeden hinterlegten Ort, den du empfiehlst, zeigst du zusätzlich mit show_place_cards (beste zuerst, höchstens 8). Erfinde keine ids.
            - Unter den Karten kann der Nutzer selbst „Einplanen“ bzw. „Zu Orten“ tippen. Passt ein Ort zu einem bestimmten Tag oder
              hinter einen Programmpunkt, gib das als date/after mit. Du änderst selbst nichts; sag nie, du hättest etwas eingeplant.
            """.trimIndent(),
        ).append('\n')
        if (web != null) {
            append(
                """
                - web_search nutzt du, wenn der Nutzer ausdrücklich im Web suchen will, nach neuen Orten fragt, die nicht hinterlegt sind,
                  aktuelle Infos braucht (Events, Öffnungszeiten, Preise) oder eine allgemeine Frage hat, die du nicht sicher weißt.
                  Im Web gefundene Orte oder Seiten zeigst du mit show_web_cards. Kennzeichne Webfunde als „nicht hinterlegt“.
                  Ist ein Webfund ein konkreter Ort (Onsen, Restaurant, Sehenswürdigkeit), gib place (Name und Ort) und category mit,
                  dann kann der Nutzer ihn hinzufügen.
                  Suchst du Orte in der Nähe, nenne in objective und queries den konkreten Ort (Stadt/Ortsteil aus der Adresse der
                  Unterkunft oder des Programmpunkts), nicht nur den Regionsnamen, und prüfe die Lage der Funde.
                """.trimIndent(),
            ).append('\n')
        } else {
            append("- Eine Websuche ist nicht eingerichtet; sag das, wenn der Nutzer danach fragt.\n")
        }
        append("- Du kannst nichts an der Reise ändern; der Nutzer plant selbst. Bei Unsicherheit frag kurz nach.\n\n")
        append(ChatContextText.overview(trip)).append('\n')
        append("Aktueller Kontext:\n").append(ChatContextText.current(context, trip, today()))
        location?.let { append(ChatContextText.location(it, trip)) }
    }

    private fun system(text: String) = msg("system", text)
    private fun msg(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }

    companion object {
        const val MAX_ROUNDS = 6
        const val MAX_HISTORY = 20
        const val MAX_TOOL_CHARS = 12_000
    }
}
