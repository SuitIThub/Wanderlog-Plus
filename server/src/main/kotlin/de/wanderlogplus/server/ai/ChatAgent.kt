package de.wanderlogplus.server.ai

import de.wanderlogplus.shared.model.Day
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.model.Trip
import de.wanderlogplus.shared.query.TripQueries
import de.wanderlogplus.shared.query.isOpenOn
import de.wanderlogplus.shared.sync.ChatCard
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
) {
    val regionsById = regions.associateBy { it.id }
    val savedById = saved.associateBy { it.id }
    private val dayById = days.associateBy { it.id }
    val queries: TripQueries? = trip?.let { TripQueries(it, stays, days, regions, saved, dayItems) }

    /** placeId -> the dates its program items are on. */
    val plannedDates: Map<String, List<String>> = dayItems.filter { it.kind == DayItemKind.PLACE && it.placeId != null }
        .groupBy({ it.placeId!! }, { dayById[it.dayId]?.date.orEmpty() })
        .mapValues { (_, v) -> v.filter { it.isNotEmpty() }.distinct().sorted() }

    fun regionName(id: String?): String? = id?.let { regionsById[it]?.name }

    /** [regionId] plus the regions that are day trips from it. */
    fun regionWithDayTrips(regionId: String): Set<String> = regions.filter { it.dayTripFromRegionId == regionId }.map { it.id }.toSet() + regionId

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

/** Turns where the chat was opened into German prompt text. */
object ChatContextText {
    /** Trip dates and regions with their date ranges (and ids, for the tools). */
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
    }

    /** What the user is looking at right now. */
    fun current(c: ChatContext, t: ChatTrip, today: String): String = buildString {
        append("Heutiges Datum: ").append(germanDate(today)).append('\n')
        when (c.screen) {
            ChatScreens.DAY, ChatScreens.TODAY -> {
                val date = c.date ?: today
                append(if (c.screen == ChatScreens.TODAY) "Der Nutzer ist auf der Heute-Ansicht. " else "Der Nutzer schaut sich einen Reisetag an. ")
                append(day(date, t))
            }
            ChatScreens.REGION -> {
                val r = c.regionId?.let { t.regionsById[it] }
                if (r == null) {
                    append("Der Nutzer schaut sich Orte ohne Region an.\n")
                } else {
                    append("Der Nutzer schaut sich die Region ").append(r.name).append(" (").append(r.id).append(") an; ")
                    append(t.saved.count { it.regionId == r.id }).append(" hinterlegte Vorschläge.\n")
                }
            }
            ChatScreens.PLACE -> {
                val s = c.savedPlaceId?.let { t.savedById[it] }
                if (s != null) {
                    append("Der Nutzer schaut sich diesen hinterlegten Ort an:\n").append(placeDetail(s, t)).append('\n')
                }
            }
            ChatScreens.TRIP -> append("Der Nutzer ist in der Reiseübersicht (alle Tage).\n")
            ChatScreens.PLACES -> append("Der Nutzer ist in der Ortsübersicht (alle Regionen).\n")
            ChatScreens.LOGISTICS -> append("Der Nutzer ist bei der Logistik (Flüge, Züge, Unterkünfte).\n")
            else -> Unit
        }
    }

    fun day(date: String, t: ChatTrip): String = buildString {
        val q = t.queries
        val regionId = q?.regionFor(date)
        val dayEntity = q?.dayFor(date)
        append("Tag: ").append(germanDate(date)).append(" (").append(date).append(")")
        regionId?.let { append(", Region ").append(t.regionName(it) ?: it).append(" (").append(it).append(")") }
        if (q != null && q.days().firstOrNull { it.date == date }?.isTravelDay == true) append(", Reisetag (Ortswechsel)")
        append('\n')
        dayEntity?.title?.takeIf { it.isNotBlank() }?.let { append("Titel: ").append(it).append('\n') }
        dayEntity?.note?.takeIf { it.isNotBlank() }?.let { append("Notiz: ").append(it.take(500)).append('\n') }
        val items = dayEntity?.let { d -> t.dayItems.filter { it.dayId == d.id }.sortedBy { it.sortKey } }.orEmpty()
        if (items.isEmpty()) {
            append("Programm: noch nichts geplant\n")
        } else {
            append("Programm:\n")
            items.forEach { i ->
                append("- ")
                i.time?.let { append(it).append(' ') }
                append(if (i.kind == DayItemKind.PLACE) t.places[i.placeId]?.name ?: "Ort" else "Notiz: " + i.text.take(200))
                append('\n')
            }
        }
    }

    fun placeDetail(s: SavedPlace, t: ChatTrip): String = buildString {
        val p = t.places[s.placeId]
        append("Name: ").append(p?.name ?: "?").append(" (id ").append(s.id).append(")\n")
        append("Region: ").append(t.regionName(s.regionId) ?: "keine").append(", Kategorie: ").append(s.category.name).append(", Status: ").append(s.status.name).append('\n')
        p?.address?.let { append("Adresse: ").append(it).append('\n') }
        p?.description?.takeIf { it.isNotBlank() }?.let { append("Beschreibung: ").append(it.take(600)).append('\n') }
        p?.rating?.let { append("Bewertung: ").append(it).append(p.numRatings?.let { n -> " ($n)" } ?: "").append('\n') }
        p?.let { openDays(it) }?.let { append("Geöffnet an: ").append(it).append('\n') }
        p?.minMinutes?.let { append("Typische Dauer: ").append(it).append(p.maxMinutes?.let { m -> "–$m" } ?: "").append(" Min.\n") }
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

    /** "Mo, Di, …" from the opening periods; null when unknown. */
    fun openDays(p: Place): String? {
        if (p.openingPeriods.isEmpty()) return null
        val names = listOf("So", "Mo", "Di", "Mi", "Do", "Fr", "Sa")
        val open = (0..6).filter { isOpenOn(p.openingPeriods, it) == true }
        return if (open.size == 7) "täglich" else open.joinToString(", ") { names[it] }.ifEmpty { "unbekannt" }
    }
}

/** Day of week with 0 = Sunday, as in [isOpenOn]. */
private fun sunday0(d: LocalDate): Int = if (d.dayOfWeek == DayOfWeek.SUNDAY) 0 else d.dayOfWeek.value

/**
 * The chat's tools over the trip and the web. One instance per answer: it collects the cards to show and
 * remembers the web pages found, so web cards can only link to real search results.
 */
class ChatTools(private val t: ChatTrip, private val web: WebSearch?, private val say: (String) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true }
    val cards = mutableListOf<ChatCard>()
    private val seenUrls = LinkedHashMap<String, WebSource>()

    val definitions: JsonArray = buildJsonArray {
        add(
            tool(
                "search_saved_places",
                "Durchsucht die hinterlegten Vorschläge (gespeicherte Orte) der Reisenden. Liefert kompakte Zeilen mit id, Name, Region, Kategorie, Einschätzung, Fazit.",
            ) {
                putJsonObject("query") { put("type", "string"); put("description", "Suchbegriffe (Name, Art, Stichworte), optional") }
                putJsonObject("regionId") { put("type", "string"); put("description", "nur diese Region (inklusive ihrer Tagesausflüge), optional") }
                putJsonObject("category") { put("type", "string"); putJsonArray("enum") { PlaceCategory.entries.forEach { add(JsonPrimitive(it.name)) } } }
                putJsonObject("openOn") { put("type", "string"); put("description", "Datum yyyy-MM-dd: nur Orte, die an diesem Wochentag nicht sicher geschlossen sind") }
                putJsonObject("includePlanned") { put("type", "boolean"); put("description", "auch schon eingeplante/besuchte Orte zeigen (Standard: nein)") }
            },
        )
        add(tool("get_place", "Alle Details zu einem hinterlegten Ort (Dossier, Tipps, Öffnungstage).") {
            putJsonObject("id") { put("type", "string"); put("description", "id aus search_saved_places") }
        })
        if (web != null) {
            add(tool("web_search", "Websuche (Parallel). Nur wenn der Nutzer nach neuen Orten, aktuellen Infos oder Webrecherche fragt oder die hinterlegten Vorschläge nicht reichen.") {
                putJsonObject("objective") { put("type", "string"); put("description", "was genau gesucht wird, ein Satz") }
                putJsonObject("queries") { put("type", "array"); putJsonObject("items") { put("type", "string") }; put("description", "1-3 kurze Suchanfragen") }
            })
        }
        add(tool("show_place_cards", "Zeigt hinterlegte Orte als antippbare Karten unter der Antwort (Reihenfolge = Empfehlung).") {
            putJsonObject("ids") { put("type", "array"); putJsonObject("items") { put("type", "string") } }
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
            "web_search" -> webSearch(args)
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
        val pool = t.saved.filter { s ->
            s.status != PlaceStatus.DROPPED &&
                (regionIds == null || s.regionId in regionIds) &&
                (category == null || s.category == category) &&
                (includePlanned || (s.status == PlaceStatus.IDEA && t.plannedDates[s.placeId].isNullOrEmpty())) &&
                (dow == null || t.places[s.placeId]?.let { p -> isOpenOn(p.openingPeriods, dow) } != false)
        }
        val tokens = args.s("query")?.lowercase()?.split(Regex("[^\\p{L}\\p{N}]+"))?.filter { it.length >= 2 }.orEmpty()
        val scored = pool.map { it to matchScore(it, tokens) }
        val hits = if (tokens.isEmpty()) scored else scored.filter { it.second > 0 }
        val ranked = hits.sortedWith(
            compareByDescending<Pair<SavedPlace, Int>> { it.second }
                .thenBy { worthRank(it.first.worth) }
                .thenByDescending { t.places[it.first.placeId]?.rating ?: 0.0 },
        ).map { it.first }
        val shown = (if (ranked.isEmpty() && tokens.isNotEmpty()) {
            pool.sortedWith(compareBy<SavedPlace> { worthRank(it.worth) }.thenByDescending { t.places[it.placeId]?.rating ?: 0.0 })
        } else {
            ranked
        }).take(MAX_ROWS)
        if (shown.isEmpty()) return "Keine passenden hinterlegten Vorschläge."
        return buildString {
            if (ranked.isEmpty() && tokens.isNotEmpty()) append("Keine Treffer für die Suchbegriffe; stattdessen die besten Orte der Auswahl:\n")
            append(pool.size).append(" Orte in der Auswahl, gezeigt ").append(shown.size).append(":\n")
            shown.forEach { s -> append(row(s)).append('\n') }
        }
    }

    private fun row(s: SavedPlace): String {
        val p = t.places[s.placeId]
        return listOfNotNull(
            "id=${s.id}",
            p?.name ?: "?",
            t.regionName(s.regionId),
            s.category.name,
            s.worth?.name,
            p?.rating?.let { "★$it" },
            s.tags.takeIf { it.isNotEmpty() }?.joinToString(" "),
            t.plannedDates[s.placeId]?.takeIf { it.isNotEmpty() }?.let { "eingeplant ${it.joinToString(",")}" },
            (s.dossier?.verdict?.takeIf { it.isNotBlank() } ?: s.worthReason.takeIf { it.isNotBlank() } ?: p?.description)?.take(160),
        ).joinToString(" | ")
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

    private fun showPlaces(args: JsonObject): String {
        val ids = args.list("ids")
        val unknown = mutableListOf<String>()
        ids.forEach { id ->
            val s = t.savedById[id] ?: run { unknown += id; return@forEach }
            if (cards.any { it.savedPlaceId == id }) return@forEach
            if (cards.size >= MAX_CARDS) return@forEach
            val subtitle = listOfNotNull(t.regionName(s.regionId), worthLabel(s.worth)).joinToString(" · ").ifEmpty { null }
            cards += ChatCard(ChatCard.KIND_PLACE, t.places[s.placeId]?.name ?: "Ort", savedPlaceId = id, subtitle = subtitle)
        }
        return if (unknown.isEmpty()) "Karten werden gezeigt." else "Karten gezeigt; unbekannte ids ignoriert: ${unknown.joinToString(", ")}"
    }

    private fun showWeb(args: JsonObject): String {
        val items = (args["items"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        var skipped = 0
        items.forEach { o ->
            val url = o.s("url") ?: return@forEach
            val source = seenUrls[url] ?: run { skipped++; return@forEach }
            if (cards.any { it.url == url } || cards.size >= MAX_CARDS) return@forEach
            cards += ChatCard(
                ChatCard.KIND_WEB, (o.s("title") ?: source.title ?: ParallelResearcher.hostOf(url)).take(120),
                subtitle = (o.s("snippet")?.take(160) ?: ParallelResearcher.hostOf(url)), url = url,
            )
        }
        return if (skipped == 0) "Karten werden gezeigt." else "$skipped Links ignoriert: nur URLs aus web_search sind erlaubt."
    }

    companion object {
        const val MAX_ROWS = 25
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
class ChatAgent(private val model: ToolChatModel, private val web: WebSearch?, private val today: () -> String = { LocalDate.now().toString() }) {

    /** Answers the last user message of [history] (the earlier ones are context). Blocking; throws [AiFailed]. */
    fun answer(history: List<ChatMessage>, trip: ChatTrip, say: (String) -> Unit = {}): ChatAnswer {
        val last = history.lastOrNull { it.role == ChatMessage.ROLE_USER } ?: throw AiFailed("ai_bad_answer")
        val tools = ChatTools(trip, web, say)
        val messages = mutableListOf(system(systemPrompt(trip, last.context ?: ChatContext())))
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

    fun systemPrompt(trip: ChatTrip, context: ChatContext): String = buildString {
        append(
            """
            Du bist der Reiseassistent der App „Wanderlog Plus“ für eine Japanreise. Antworte auf Deutsch, freundlich und knapp
            (Markdown: kurze Absätze, Listen, **fett**; keine Tabellen, keine Links im Text).
            - Für Vorschläge suchst du zuerst mit search_saved_places in den hinterlegten Vorschlägen der Reisenden und empfiehlst bevorzugt diese.
              Nutze dafür den Kontext (Region und Datum des Tages, Öffnungstage mit openOn) und die Wünsche des Nutzers.
            - Jeden hinterlegten Ort, den du empfiehlst, zeigst du zusätzlich mit show_place_cards (beste zuerst, höchstens 8). Erfinde keine ids.
            """.trimIndent(),
        ).append('\n')
        if (web != null) {
            append(
                """
                - web_search nutzt du, wenn der Nutzer ausdrücklich im Web suchen will, nach neuen Orten fragt, die nicht hinterlegt sind,
                  aktuelle Infos braucht (Events, Öffnungszeiten, Preise) oder eine allgemeine Frage hat, die du nicht sicher weißt.
                  Im Web gefundene Orte oder Seiten zeigst du mit show_web_cards. Kennzeichne Webfunde als „nicht hinterlegt“.
                """.trimIndent(),
            ).append('\n')
        } else {
            append("- Eine Websuche ist nicht eingerichtet; sag das, wenn der Nutzer danach fragt.\n")
        }
        append("- Du kannst nichts an der Reise ändern; der Nutzer plant selbst. Bei Unsicherheit frag kurz nach.\n\n")
        append(ChatContextText.overview(trip)).append('\n')
        append("Aktueller Kontext:\n").append(ChatContextText.current(context, trip, today()))
    }

    private fun system(text: String) = msg("system", text)
    private fun msg(role: String, text: String) = buildJsonObject { put("role", role); put("content", text) }

    companion object {
        const val MAX_ROUNDS = 6
        const val MAX_HISTORY = 20
        const val MAX_TOOL_CHARS = 12_000
    }
}
