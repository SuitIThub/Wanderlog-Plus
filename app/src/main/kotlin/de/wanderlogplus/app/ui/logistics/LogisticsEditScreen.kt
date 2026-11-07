package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.maps.coords
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.client.GeoPlace
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.EntityCodec
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.model.Flight
import de.wanderlogplus.shared.model.Train
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

const val LOGISTICS_EDIT_ROUTE = "logistik-edit/{type}/{id}"

fun logisticsEditRoute(type: EntityType, id: String) = "logistik-edit/${type.name.lowercase()}/${android.net.Uri.encode(id)}"

/** Id in an edit route for an entry that does not exist yet ("Hinzufügen"). */
const val NEW_ID = "new"

/** Id for a new train that starts with the connection search open ("ÖPNV-Verbindung suchen"). */
const val NEW_WITH_SEARCH = "new|search"

/** Whether an edit-route id stands for a new entry (plain [NEW_ID] or a new leg of a journey, [newLegId]). */
fun isNewId(id: String) = id == NEW_ID || id.startsWith("$NEW_ID|")

/**
 * Edit-route id for a new leg of journey [journeyId] between [prev] and [next] ("TYPE:id", either may be null): the
 * form is filled from the neighbours and the leg joins the journey.
 */
fun newLegId(journeyId: String, prev: Pair<EntityType, String>?, next: Pair<EntityType, String>?): String =
    listOf(NEW_ID, journeyId, prev?.let { "${it.first.name}:${it.second}" }.orEmpty(), next?.let { "${it.first.name}:${it.second}" }.orEmpty()).joinToString("|")

/** Journey id and neighbours of a [newLegId]; null for any other id. */
fun parseNewLeg(id: String): Triple<String, Pair<EntityType, String>?, Pair<EntityType, String>?>? {
    val p = id.split('|')
    if (p.size != 4 || p[0] != NEW_ID || p[1].isBlank()) return null
    fun ref(s: String) = s.split(':', limit = 2).takeIf { it.size == 2 }?.let { (t, i) -> EntityType.entries.firstOrNull { it.name == t }?.let { it to i } }
    return Triple(p[1], ref(p[2]), ref(p[3]))
}

/** Minutes to be at a flight before it leaves, and before a train. */
private const val FLIGHT_BUFFER = 90
private const val TRAIN_BUFFER = 10

private fun minusMinutes(e: Endpoint, minutes: Int): Endpoint {
    val t = e.time?.takeIf { it.length >= 4 }?.split(':')?.let { (h, m) -> (h.toIntOrNull() ?: return e) * 60 + (m.take(2).toIntOrNull() ?: 0) } ?: return e
    var v = t - minutes
    var date = e.date
    if (v < 0) {
        v += 24 * 60
        date = date?.let { runCatching { java.time.LocalDate.parse(it).minusDays(1).toString() }.getOrNull() }
    }
    return e.copy(time = "%02d:%02d".format(v / 60, v % 60), date = date)
}

/**
 * The empty draft of a new leg between [prev] and [next]: it starts where [prev] arrives (then) and ends where [next]
 * leaves, to be there a buffer before (90 min before a flight, 10 before a train).
 */
fun newLegDraft(snapshot: TripSnapshot, prev: Pair<EntityType, String>?, next: Pair<EntityType, String>?): MoveDraft {
    fun leg(r: Pair<EntityType, String>?) = r?.let { (t, i) ->
        when (t) {
            EntityType.FLIGHT -> snapshot.flights.firstOrNull { it.id == i }?.let { it.depart to it.arrive }
            EntityType.TRAIN -> snapshot.trains.firstOrNull { it.id == i }?.let { it.depart to it.arrive }
            else -> null
        }
    }
    val before = leg(prev)
    val after = leg(next)
    val depart = before?.second?.copy(date = before.second.date ?: before.first.date) ?: Endpoint(date = after?.first?.date)
    val arrive = after?.first?.let { minusMinutes(it, if (next?.first == EntityType.FLIGHT) FLIGHT_BUFFER else TRAIN_BUFFER) } ?: Endpoint(date = depart.date)
    return MoveDraft("", "", depart, emptyList(), arrive)
}

/** Searches places by name, biased towards a coordinate; throws on failure. */
typealias PlaceSearch = suspend (query: String, near: Pair<Double, Double>?) -> List<GeoPlace>

/** The editable part of a train or flight: who operates it and where it goes, with stopovers in travel order. */
data class MoveDraft(
    val operator: String,
    val number: String,
    val depart: Endpoint,
    val via: List<Endpoint>,
    val arrive: Endpoint,
    val bookingNumber: String = "",
    val note: String = "",
    /** Trains: the connection taken over from the transit search. */
    val transit: TransitResult? = null,
    val travelClass: String = "",
    val seats: List<de.wanderlogplus.shared.model.Seat> = emptyList(),
    /** Seat map chosen by hand; null: found by the number. */
    val seatMap: String? = null,
    val reservation: ReservationDraft = ReservationDraft(),
    /** Further rides of a picked connection: saved as the next legs of a journey. */
    val extraRides: List<Train> = emptyList(),
)

/** "JR Shinkansen Nozomi 15" → ("JR Shinkansen", "Nozomi 15"); lines without a numbered train keep their name. */
internal fun splitLine(line: String): Pair<String, String?> {
    val numbered = Regex("""^(.*?)\s*([A-Z][a-z]+ \d+)$""")
    return numbered.find(line.trim())?.let { m -> m.groupValues[1].trim().ifBlank { line.trim() } to m.groupValues[2] } ?: (line.trim() to null)
}

/** The rides of a connection named like the form does it (operator part and numbered train). */
fun namedRides(r: TransitResult): List<Train> = de.wanderlogplus.shared.query.TransitRides.ridesOf(r).map(::named)

/** A ride of [de.wanderlogplus.shared.query.TransitRides] named like the form does it: operator part and numbered train. */
private fun named(t: Train): Train {
    val (op, nr) = splitLine(t.number.orEmpty())
    return t.copy(carrier = op.ifBlank { t.carrier }, number = nr)
}

/**
 * The draft filled from a timetable connection (first route): its first ride (stations, times, line as operator and
 * the numbered train as number). Further rides go to [MoveDraft.extraRides]: saving makes them the next legs of a
 * journey. Walks between stations are left out. A connection with one ride keeps the whole connection as transit.
 */
fun draftFromTransit(d: MoveDraft, r: TransitResult): MoveDraft {
    val rides = de.wanderlogplus.shared.query.TransitRides.ridesOf(r).map(::named)
    val first = rides.firstOrNull() ?: return d
    fun Endpoint.from(e: Endpoint) = copy(name = e.name, code = null, placeId = null, lat = e.lat, lng = e.lng, date = e.date, time = e.time)
    return d.copy(
        operator = first.carrier?.takeIf { it.isNotBlank() } ?: d.operator,
        number = first.number ?: "",
        depart = d.depart.from(first.depart),
        via = emptyList(),
        arrive = d.arrive.from(first.arrive),
        transit = if (rides.size == 1) r else first.transit,
        extraRides = rides.drop(1),
    )
}

/** "Wird als Reise mit 3 Teilstrecken gespeichert" when the picked connection has more than one ride. */
@Composable
fun TransitJourneyHint(d: MoveDraft, modifier: Modifier = Modifier) {
    if (d.extraRides.isEmpty()) return
    Text(
        "Wird als Reise mit ${d.extraRides.size + 1} Teilstrecken gespeichert",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier.testTag("transit-as-journey"),
    )
}

private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
private val TIME = Regex("""\d{2}:\d{2}""")

/** First problem of [d] as a German message, or null when it can be saved. Dates "yyyy-MM-dd", times "HH:mm"; blank is fine. */
fun validateDraft(d: MoveDraft): String? {
    val ends = listOf(d.depart, d.arrive) + d.via
    if (d.depart.name.isBlank() || d.arrive.name.isBlank()) return "Start und Ziel brauchen einen Namen"
    if (d.via.any { it.name.isBlank() }) return "Jeder Zwischenhalt braucht einen Namen"
    if (ends.any { !it.date.isNullOrBlank() && !DATE.matches(it.date!!) }) return "Datum bitte als JJJJ-MM-TT"
    if (ends.any { !it.time.isNullOrBlank() && !TIME.matches(it.time!!) }) return "Uhrzeit bitte als HH:MM"
    return validateReservation(d.reservation)
}

/** Draft of the train/flight [id] (empty for [NEW_ID]), or null when it does not exist. */
fun draftOf(snapshot: TripSnapshot, type: EntityType, id: String): MoveDraft? = when {
    isNewId(id) && (type == EntityType.TRAIN || type == EntityType.FLIGHT) ->
        parseNewLeg(id)?.let { (_, prev, next) -> newLegDraft(snapshot, prev, next) } ?: MoveDraft("", "", Endpoint(), emptyList(), Endpoint())
    type == EntityType.TRAIN -> snapshot.trains.firstOrNull { it.id == id }?.let {
        MoveDraft(it.carrier.orEmpty(), it.number.orEmpty(), it.depart, it.via, it.arrive, it.confirmationNumber.orEmpty(), it.note, it.transit, it.travelClass.orEmpty(), it.seats, it.seatMap, it.reservation.toDraft())
    }
    type == EntityType.FLIGHT -> snapshot.flights.firstOrNull { it.id == id }?.let {
        MoveDraft(it.airline.orEmpty(), it.number.orEmpty(), it.depart, it.via, it.arrive, it.confirmationNumber.orEmpty(), it.note, null, it.travelClass.orEmpty(), it.seats, it.seatMap, it.reservation.toDraft())
    }
    else -> null
}

/**
 * The stored fields of [id] before and after applying [d]; for [NEW_ID] "before" is empty and "after" holds every
 * field of the new entry. Empty maps for an unknown entry.
 */
fun draftChanges(snapshot: TripSnapshot, type: EntityType, id: String, d: MoveDraft): Pair<Map<String, JsonElement>, Map<String, JsonElement>> {
    fun Endpoint.clean() = copy(date = date?.trim()?.ifBlank { null }, time = time?.trim()?.ifBlank { null }, name = name.trim())
    val isNew = isNewId(id)
    val journeyId = parseNewLeg(id)?.first
    return when (type) {
        EntityType.TRAIN -> (if (isNew) Train("") else snapshot.trains.firstOrNull { it.id == id })?.let { t ->
            (if (isNew) emptyMap() else EntityCodec.fields(t)) to EntityCodec.fields(
                t.copy(
                    carrier = d.operator.trim().ifBlank { null },
                    number = d.number.trim().ifBlank { null },
                    depart = d.depart.clean(),
                    via = d.via.map { it.clean() },
                    arrive = d.arrive.clean(),
                    confirmationNumber = d.bookingNumber.trim().ifBlank { null },
                    note = d.note.trim(),
                    transit = d.transit,
                    journeyId = if (isNew) journeyId else t.journeyId,
                    travelClass = d.travelClass.trim().ifBlank { null },
                    seats = d.seats.filter { it.seat.isNotBlank() }.map { it.copy(car = it.car?.trim()?.ifBlank { null }, seat = it.seat.trim(), traveler = it.traveler?.trim()?.ifBlank { null }) },
                    seatMap = d.seatMap,
                    reservation = d.reservation.toWindow(t.reservation),
                    // Seats entered again are seats for this ride.
                    seatsForDeparture = if (d.seats != t.seats) null else t.seatsForDeparture,
                ),
            )
        }
        EntityType.FLIGHT -> (if (isNew) Flight("") else snapshot.flights.firstOrNull { it.id == id })?.let { f ->
            (if (isNew) emptyMap() else EntityCodec.fields(f)) to EntityCodec.fields(
                f.copy(
                    airline = d.operator.trim().ifBlank { null },
                    number = d.number.trim().ifBlank { null },
                    depart = d.depart.clean(),
                    via = d.via.map { it.clean() },
                    arrive = d.arrive.clean(),
                    confirmationNumber = d.bookingNumber.trim().ifBlank { null },
                    note = d.note.trim(),
                    journeyId = if (isNew) journeyId else f.journeyId,
                    travelClass = d.travelClass.trim().ifBlank { null },
                    seats = d.seats.filter { it.seat.isNotBlank() }.map { it.copy(car = null, seat = it.seat.trim(), traveler = it.traveler?.trim()?.ifBlank { null }) },
                    seatMap = d.seatMap,
                    reservation = d.reservation.toWindow(f.reservation),
                ),
            )
        }
        else -> null
    } ?: (emptyMap<String, JsonElement>() to emptyMap())
}

/** Which endpoint a place search fills in. */
private sealed interface SearchTarget {
    data object Depart : SearchTarget
    data object Arrive : SearchTarget
    data class Via(val index: Int) : SearchTarget
    data object NewVia : SearchTarget
}

/**
 * Edit a train or flight: operator (and flight number), start, stopovers and destination with date and time. Places are
 * picked with [search] (OpenStreetMap), which also stores their coordinates for the route map. Nothing is written
 * before "Speichern" ([onSave] gets the fields before and after).
 */
@Composable
fun LogisticsEditScreen(
    snapshot: TripSnapshot,
    type: EntityType?,
    id: String,
    onBack: () -> Unit,
    /** Saves; [extraRides] become the next legs of a journey (a connection with several rides). */
    onSave: (type: EntityType, id: String, before: Map<String, JsonElement>, after: Map<String, JsonElement>, extraRides: List<Train>) -> Unit,
    search: PlaceSearch?,
    modifier: Modifier = Modifier,
    /** The timetable search for trains ("Verbindung suchen"); null hides it. */
    transitApi: (suspend () -> de.wanderlogplus.client.SyncApi?)? = null,
) {
    val initial = remember(type, id) { type?.let { draftOf(snapshot, it, id) } }
    if (type == null || initial == null) {
        Column(modifier.fillMaxSize().padding(16.dp)) {
            TextButton(onClick = onBack) { Text("Zurück") }
            EmptyHint("Diese Buchung existiert nicht mehr.")
        }
        return
    }
    var draft by remember(type, id) { mutableStateOf(initial) }
    var searching by remember(type, id) { mutableStateOf<SearchTarget?>(null) }
    var error by remember(type, id) { mutableStateOf<String?>(null) }
    var transitOpen by remember(type, id) { mutableStateOf(id == NEW_WITH_SEARCH) }

    if (transitOpen && transitApi != null) {
        TransitPanel(snapshot, draft, transitApi, search, onPick = { r -> draft = draftFromTransit(draft, r); transitOpen = false }, onCancel = { transitOpen = false }, modifier = modifier)
        return
    }

    val target = searching
    if (target != null && search != null) {
        val near = (draft.depart.coords(snapshot) ?: draft.arrive.coords(snapshot))?.let { it.lat to it.lng }
        PlaceSearchPanel(
            search = search,
            near = near,
            onPick = { p ->
                fun Endpoint.with(p: GeoPlace) = copy(name = p.name, lat = p.lat, lng = p.lng)
                draft = when (target) {
                    SearchTarget.Depart -> draft.copy(depart = draft.depart.with(p))
                    SearchTarget.Arrive -> draft.copy(arrive = draft.arrive.with(p))
                    is SearchTarget.Via -> draft.copy(via = draft.via.mapIndexed { i, e -> if (i == target.index) e.with(p) else e })
                    SearchTarget.NewVia -> draft.copy(via = draft.via + Endpoint(name = p.name, lat = p.lat, lng = p.lng, date = draft.depart.date))
                }
                searching = null
            },
            onCancel = { searching = null },
            modifier = modifier,
        )
        return
    }

    Column(modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).testTag("logistics-edit")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Abbrechen") }
            Text(
                when {
                    isNewId(id) && type == EntityType.FLIGHT -> "Neuer Flug"
                    isNewId(id) -> "Neue Fahrt"
                    type == EntityType.FLIGHT -> "Flug bearbeiten"
                    else -> "Fahrt bearbeiten"
                },
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = {
                    val problem = validateDraft(draft)
                    if (problem != null) {
                        error = problem
                    } else {
                        val (before, after) = draftChanges(snapshot, type, id, draft)
                        onSave(type, id, before, after, draft.extraRides)
                        onBack()
                    }
                },
                modifier = Modifier.padding(end = 8.dp).testTag("edit-save"),
            ) { Text("Speichern") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("edit-error")) }
            if (type == EntityType.TRAIN && transitApi != null) {
                TransitJourneyHint(draft, Modifier.padding(top = 8.dp))
                OutlinedButton(onClick = { transitOpen = true }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).testTag("edit-transit")) {
                    Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                    Text(if (draft.transit != null) "Andere Verbindung suchen" else "Verbindung suchen (ÖPNV)")
                }
                draft.transit?.routes?.firstOrNull()?.let { r ->
                    Text(
                        "Übernommen: ${r.departure}–${r.arrival}, " + (if (r.transfers == 0) "ohne Umstieg" else "${r.transfers}× umsteigen") +
                            r.legs.firstOrNull { !it.walk }?.departurePlatform?.let { " · Gleis $it" }.orEmpty(),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.testTag("edit-transit-chosen"),
                    )
                }
            }
            SectionLabel(if (type == EntityType.FLIGHT) "Airline" else "Gesellschaft")
            OutlinedTextField(
                draft.operator, { draft = draft.copy(operator = it) },
                label = { Text(if (type == EntityType.FLIGHT) "Airline" else "z. B. JR Central") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("edit-operator"),
            )
            OutlinedTextField(
                draft.number, { draft = draft.copy(number = it) },
                label = { Text(if (type == EntityType.FLIGHT) "Flugnummer (z. B. NH 7)" else "Zugnummer (z. B. Nozomi 21)") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("edit-number"),
            )

            SectionLabel(if (type == EntityType.FLIGHT) "Abflug" else "Abfahrt")
            EndpointFields("depart", draft.depart, { draft = draft.copy(depart = it) }, search?.let { { searching = SearchTarget.Depart } })

            SectionLabel("Zwischenhalte")
            if (draft.via.isEmpty()) EmptyHint("Direkt, ohne Zwischenhalt")
            draft.via.forEachIndexed { i, stop ->
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("edit-via-$i")) {
                    Column(Modifier.weight(1f)) {
                        EndpointFields("via-$i", stop, { e -> draft = draft.copy(via = draft.via.mapIndexed { j, s -> if (j == i) e else s }) }, search?.let { { searching = SearchTarget.Via(i) } }, withDate = false)
                    }
                    Column {
                        IconButton(onClick = { draft = draft.copy(via = draft.via.move(i, i - 1)) }, enabled = i > 0) {
                            Icon(Icons.Filled.ArrowUpward, contentDescription = "Nach oben")
                        }
                        IconButton(onClick = { draft = draft.copy(via = draft.via.move(i, i + 1)) }, enabled = i < draft.via.lastIndex) {
                            Icon(Icons.Filled.ArrowDownward, contentDescription = "Nach unten")
                        }
                        IconButton(onClick = { draft = draft.copy(via = draft.via.filterIndexed { j, _ -> j != i }) }, modifier = Modifier.testTag("edit-via-remove-$i")) {
                            Icon(Icons.Filled.Close, contentDescription = "Halt entfernen")
                        }
                    }
                }
            }
            OutlinedButton(
                onClick = { if (search != null) searching = SearchTarget.NewVia else draft = draft.copy(via = draft.via + Endpoint(date = draft.depart.date)) },
                modifier = Modifier.testTag("edit-add-via"),
            ) { Text("＋ Halt hinzufügen") }

            SectionLabel(if (type == EntityType.FLIGHT) "Landung" else "Ankunft")
            EndpointFields("arrive", draft.arrive, { draft = draft.copy(arrive = it) }, search?.let { { searching = SearchTarget.Arrive } })

            SectionLabel("Buchung")
            OutlinedTextField(
                draft.bookingNumber, { draft = draft.copy(bookingNumber = it) },
                label = { Text("Buchungsnummer") }, singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("edit-booking"),
            )
            SectionLabel("Reservierung")
            ReservationFields(draft.reservation, showDone = true, onChange = { draft = draft.copy(reservation = it) })
            SectionLabel("Sitzplätze")
            SeatFields(train = type == EntityType.TRAIN, draft = draft, onChange = { draft = it })
            OutlinedTextField(
                draft.note, { draft = draft.copy(note = it) },
                label = { Text("Notiz") }, minLines = 2,
                modifier = Modifier.fillMaxWidth().testTag("edit-note"),
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(bottom = 24.dp))
        }
    }
}

private fun <T> List<T>.move(from: Int, to: Int): List<T> {
    if (to !in indices) return this
    return toMutableList().apply { add(to, removeAt(from)) }
}

/** Name (with a search button that also sets coordinates), date and time of one endpoint. */
@Composable
private fun EndpointFields(tag: String, e: Endpoint, onChange: (Endpoint) -> Unit, onSearch: (() -> Unit)?, withDate: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            e.name,
            // Typing a name by hand drops coordinates picked before: they would belong to another place.
            { onChange(e.copy(name = it, lat = null, lng = null)) },
            label = { Text("Ort") },
            singleLine = true,
            supportingText = { Text(if (e.lat != null && e.lng != null) "📍 auf der Karte" else "ohne Kartenposition") },
            trailingIcon = onSearch?.let { s ->
                { IconButton(onClick = s, modifier = Modifier.testTag("edit-$tag-search")) { Icon(Icons.Filled.Search, contentDescription = "Ort suchen") } }
            },
            modifier = Modifier.fillMaxWidth().testTag("edit-$tag-name"),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (withDate) {
                OutlinedTextField(
                    e.date.orEmpty(), { onChange(e.copy(date = it)) },
                    label = { Text("Datum") }, placeholder = { Text("2027-05-08") }, singleLine = true,
                    modifier = Modifier.weight(1f).testTag("edit-$tag-date"),
                )
            }
            OutlinedTextField(
                e.time.orEmpty(), { onChange(e.copy(time = it)) },
                label = { Text("Uhrzeit") }, placeholder = { Text("09:12") }, singleLine = true,
                modifier = Modifier.width(120.dp).testTag("edit-$tag-time"),
            )
        }
    }
}

/** Full-screen place search: type a name, pick a hit (OpenStreetMap via Photon). */
@Composable
internal fun PlaceSearchPanel(
    search: PlaceSearch,
    near: Pair<Double, Double>?,
    onPick: (GeoPlace) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var query by remember { mutableStateOf("") }
    var hits by remember { mutableStateOf<List<GeoPlace>>(emptyList()) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(query) {
        if (query.trim().length < 3) { hits = emptyList(); return@LaunchedEffect }
        delay(600) // debounce while typing: every Google search costs
        hits = try {
            search(query, near).also { failed = false }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed = true
            emptyList()
        }
    }
    Column(modifier.fillMaxSize().imePadding().testTag("place-search")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onCancel) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Abbrechen") }
            OutlinedTextField(
                query, { query = it },
                placeholder = { Text("Bahnhof, Flughafen, Ort …") },
                singleLine = true,
                modifier = Modifier.weight(1f).padding(end = 8.dp).testTag("place-search-field"),
            )
        }
        if (failed) Text("Suche fehlgeschlagen – bist du online?", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(16.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            hits.forEachIndexed { i, p ->
                Column(
                    Modifier.fillMaxWidth().clickable { onPick(p) }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("place-hit-$i"),
                ) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                    if (p.detail.isNotBlank()) Text(p.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider()
            }
        }
    }
}

/** "Verbindung suchen" inside the edit screen: the timetable search seeded with the draft's stations and time. */
@Composable
private fun TransitPanel(
    snapshot: TripSnapshot,
    draft: MoveDraft,
    api: suspend () -> de.wanderlogplus.client.SyncApi?,
    /** Place search for start/goal suggestions; null: trip places only. */
    search: PlaceSearch?,
    onPick: (TransitResult) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier,
) {
    fun point(e: Endpoint) = e.coords(snapshot)?.let { de.wanderlogplus.shared.sync.TransitPoint(it.lat, it.lng, e.name) }
    val from = point(draft.depart)
    val to = point(draft.arrive)
    val date = draft.depart.date?.takeIf { it.isNotBlank() } ?: java.time.LocalDate.now().toString()
    val vm: de.wanderlogplus.app.ui.transit.TransitViewModel = androidx.lifecycle.viewmodel.compose.viewModel(
        key = "edit-transit-${draft.depart.name}-${draft.arrive.name}-$date",
        factory = remember(from, to, date) {
            object : androidx.lifecycle.ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : androidx.lifecycle.ViewModel> create(modelClass: Class<T>): T =
                    de.wanderlogplus.app.ui.transit.TransitViewModel(
                        api, from, to, date,
                        draft.depart.time ?: draft.arrive.time, draft.transit,
                        // Only a time to be there (e.g. before a flight): search by arrival.
                        searchType = if (draft.depart.time.isNullOrBlank() && !draft.arrive.time.isNullOrBlank()) "arrive" else "depart",
                    ) as T
            }
        },
    )
    val state by vm.state.collectAsState()
    de.wanderlogplus.app.ui.transit.TransitScreen(
        title = listOf(draft.depart.name, draft.arrive.name).filter { it.isNotBlank() }.joinToString(" → ").ifBlank { "Neue Fahrt" },
        state = state,
        accent = MaterialTheme.colorScheme.primary,
        chosen = null,
        onEdit = vm::edit,
        onSearch = vm::search,
        onChoose = onPick,
        onClear = {},
        onBack = onCancel,
        modifier = modifier,
        suggest = { q ->
            val near = (state.form.fromPoint ?: state.form.toPoint)?.let { it.lat to it.lng }
            val online = if (search != null && q.trim().length >= 3) {
                try {
                    search(q, near).map { de.wanderlogplus.app.ui.transit.EndpointHit(it.name, it.detail, it.lat, it.lng, false) }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    emptyList()
                }
            } else emptyList()
            de.wanderlogplus.app.ui.transit.mergeHits(de.wanderlogplus.app.ui.transit.tripEndpointHits(snapshot, q), online)
        },
        onPickFrom = vm::pickFrom, onPickTo = vm::pickTo, onClearFrom = vm::clearFrom, onClearTo = vm::clearTo,
    )
}
