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
import de.wanderlogplus.shared.sync.EntityType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonElement

const val LOGISTICS_EDIT_ROUTE = "logistik-edit/{type}/{id}"

fun logisticsEditRoute(type: EntityType, id: String) = "logistik-edit/${type.name.lowercase()}/${android.net.Uri.encode(id)}"

/** Searches places by name, biased towards a coordinate; throws on failure. */
typealias PlaceSearch = suspend (query: String, near: Pair<Double, Double>?) -> List<GeoPlace>

/** The editable part of a train or flight: who operates it and where it goes, with stopovers in travel order. */
data class MoveDraft(
    val operator: String,
    val number: String,
    val depart: Endpoint,
    val via: List<Endpoint>,
    val arrive: Endpoint,
)

private val DATE = Regex("""\d{4}-\d{2}-\d{2}""")
private val TIME = Regex("""\d{2}:\d{2}""")

/** First problem of [d] as a German message, or null when it can be saved. Dates "yyyy-MM-dd", times "HH:mm"; blank is fine. */
fun validateDraft(d: MoveDraft): String? {
    val ends = listOf(d.depart, d.arrive) + d.via
    if (d.depart.name.isBlank() || d.arrive.name.isBlank()) return "Start und Ziel brauchen einen Namen"
    if (d.via.any { it.name.isBlank() }) return "Jeder Zwischenhalt braucht einen Namen"
    if (ends.any { !it.date.isNullOrBlank() && !DATE.matches(it.date!!) }) return "Datum bitte als JJJJ-MM-TT"
    if (ends.any { !it.time.isNullOrBlank() && !TIME.matches(it.time!!) }) return "Uhrzeit bitte als HH:MM"
    return null
}

/** Draft of the train/flight [id], or null when it does not exist. */
fun draftOf(snapshot: TripSnapshot, type: EntityType, id: String): MoveDraft? = when (type) {
    EntityType.TRAIN -> snapshot.trains.firstOrNull { it.id == id }?.let { MoveDraft(it.carrier.orEmpty(), "", it.depart, it.via, it.arrive) }
    EntityType.FLIGHT -> snapshot.flights.firstOrNull { it.id == id }?.let { MoveDraft(it.airline.orEmpty(), it.number.orEmpty(), it.depart, it.via, it.arrive) }
    else -> null
}

/** The stored fields of [id] before and after applying [d]; empty maps for an unknown entry. */
fun draftChanges(snapshot: TripSnapshot, type: EntityType, id: String, d: MoveDraft): Pair<Map<String, JsonElement>, Map<String, JsonElement>> {
    fun Endpoint.clean() = copy(date = date?.trim()?.ifBlank { null }, time = time?.trim()?.ifBlank { null }, name = name.trim())
    return when (type) {
        EntityType.TRAIN -> snapshot.trains.firstOrNull { it.id == id }?.let { t ->
            EntityCodec.fields(t) to EntityCodec.fields(
                t.copy(carrier = d.operator.trim().ifBlank { null }, depart = d.depart.clean(), via = d.via.map { it.clean() }, arrive = d.arrive.clean()),
            )
        }
        EntityType.FLIGHT -> snapshot.flights.firstOrNull { it.id == id }?.let { f ->
            EntityCodec.fields(f) to EntityCodec.fields(
                f.copy(
                    airline = d.operator.trim().ifBlank { null },
                    number = d.number.trim().ifBlank { null },
                    depart = d.depart.clean(),
                    via = d.via.map { it.clean() },
                    arrive = d.arrive.clean(),
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
    onSave: (type: EntityType, id: String, before: Map<String, JsonElement>, after: Map<String, JsonElement>) -> Unit,
    search: PlaceSearch?,
    modifier: Modifier = Modifier,
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
                if (type == EntityType.FLIGHT) "Flug bearbeiten" else "Fahrt bearbeiten",
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
                        onSave(type, id, before, after)
                        onBack()
                    }
                },
                modifier = Modifier.padding(end = 8.dp).testTag("edit-save"),
            ) { Text("Speichern") }
        }
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("edit-error")) }
            SectionLabel(if (type == EntityType.FLIGHT) "Airline" else "Gesellschaft")
            OutlinedTextField(
                draft.operator, { draft = draft.copy(operator = it) },
                label = { Text(if (type == EntityType.FLIGHT) "Airline" else "z. B. JR Central") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("edit-operator"),
            )
            if (type == EntityType.FLIGHT) {
                OutlinedTextField(
                    draft.number, { draft = draft.copy(number = it) },
                    label = { Text("Flugnummer") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("edit-number"),
                )
            }

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
private fun PlaceSearchPanel(
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
        if (query.isBlank()) { hits = emptyList(); return@LaunchedEffect }
        delay(400) // debounce while typing
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
