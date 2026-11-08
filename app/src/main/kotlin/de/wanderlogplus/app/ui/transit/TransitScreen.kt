package de.wanderlogplus.app.ui.transit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.foundation.clickable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitStationNear

const val TRANSIT_ROUTE = "trip/day/{date}/transit/{itemId}"

fun transitRoute(date: String, itemId: String) = "trip/day/$date/transit/${android.net.Uri.encode(itemId)}"

/**
 * "Verbindung abfragen": current timetable connections for one way of a day. Stations (nearby ones as chips or
 * typed in Japanese), date, time, departure/arrival, sorting and means of transport can be changed; each result can be
 * taken over for the way ([onChoose]). [chosen] is the connection the way uses now ([onClear] removes it).
 * Tags: `transit-screen`, `transit-from`, `transit-to`, `transit-date`, `transit-time`, `transit-search`, `transit-message`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransitScreen(
    title: String,
    state: TransitScreenState,
    accent: Color,
    chosen: TransitResult?,
    onEdit: ((TransitForm) -> TransitForm) -> Unit,
    onSearch: () -> Unit,
    onChoose: (TransitResult) -> Unit,
    onClear: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    /** Suggestions (trip places, then online hits) for what is typed into Von/Nach; null: none. */
    suggest: EndpointSearch? = null,
    onPickFrom: (EndpointHit) -> Unit = {},
    onPickTo: (EndpointHit) -> Unit = {},
    onClearFrom: () -> Unit = {},
    onClearTo: () -> Unit = {},
) {
    val f = state.form
    Column(modifier.fillMaxSize().imePadding().testTag("transit-screen")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Column {
                Text("Verbindung abfragen", style = MaterialTheme.typography.titleLarge)
                Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (chosen != null) {
                SectionLabel("Für diesen Weg gewählt", color = accent)
                chosen.routes.firstOrNull()?.let { TransitRouteCard(chosen, it, -1, accent, chosen = true) }
                OutlinedButton(onClick = onClear, modifier = Modifier.testTag("transit-clear")) { Text("Auswahl entfernen (wieder automatisch)") }
            }

            SectionLabel("Start und Ziel", color = accent)
            StationField("Von", f.fromStation, f.fromPoint, state.nearFrom, "transit-from", suggest, onPickFrom, onClearFrom) { v -> onEdit { it.copy(fromStation = v) } }
            StationField("Nach", f.toStation, f.toPoint, state.nearTo, "transit-to", suggest, onPickTo, onClearTo) { v -> onEdit { it.copy(toStation = v) } }
            Text(
                "Ort oder Hotel suchen und wählen – der nächste Bahnhof samt Fußweg wird gefunden, oder einen Bahnhof darunter antippen. Bahnhöfe auch von Hand: in Japan auf Japanisch (z. B. 京都), in Deutschland der Name (z. B. Hannover Kröpcke).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (smartApplies(f)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Mehrere Bahnhöfe vergleichen", style = MaterialTheme.typography.bodyLarge)
                        Text("bis zu 6 Abfragen (≈ 4 Cent); Fußweg, Fahrzeit und Umstiege werden abgewogen", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.Switch(checked = f.smart, onCheckedChange = { v -> onEdit { it.copy(smart = v) } }, modifier = Modifier.testTag("transit-smart"))
                }
            }

            SectionLabel("Zeit", color = accent)
            DateTimeFields(f.date, f.time, onDate = { v -> onEdit { it.copy(date = v) } }, onTime = { v -> onEdit { it.copy(time = v) } })
            Segments(
                listOf("depart" to "Abfahrt um", "arrive" to "Ankunft bis"),
                f.searchType, "transit-type",
            ) { v -> onEdit { it.copy(searchType = v) } }

            SectionLabel("Sortierung und Verkehrsmittel", color = accent)
            Segments(
                listOf("fastest" to "Schnellste", "cheapest" to "Günstigste", "fewest_transfers" to "Wenig Umstiege"),
                f.preference, "transit-pref",
            ) { v -> onEdit { it.copy(preference = v) } }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Toggle("Shinkansen", f.useShinkansen, "transit-shinkansen") { v -> onEdit { it.copy(useShinkansen = v) } }
                Toggle("Limited Express", f.useLimitedExpress, "transit-express") { v -> onEdit { it.copy(useLimitedExpress = v) } }
                Toggle("Fernbus", f.useHighwayBus, "transit-highway-bus") { v -> onEdit { it.copy(useHighwayBus = v) } }
                Toggle("Stadtbus", f.useLocalBus, "transit-bus") { v -> onEdit { it.copy(useLocalBus = v) } }
            }

            Button(onClick = onSearch, enabled = !state.searching, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("transit-search")) {
                if (state.searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Text(if (state.searching) "  Suche läuft (bis zu 30 Sekunden) …" else "Verbindungen suchen (aktuelle Daten)")
            }
            state.message?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("transit-message")) }

            state.result?.let { r ->
                SectionLabel(if (r.smart) "Verbindungen (mehrere Bahnhöfe verglichen)" else "Verbindungen ${stationLabel(r.fromStation)} → ${stationLabel(r.toStation)}", color = accent)
                r.stationWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Preise für einen Erwachsenen, freier Sitz. Fahrpläne für die Reisezeit können sich mit der Fahrplanumstellung im März noch ändern.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                r.routes.forEachIndexed { i, route ->
                    TransitRouteCard(r, route, i, accent, onChoose = { onChoose(chosenRoute(r, route)) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationField(
    label: String,
    value: String,
    point: de.wanderlogplus.shared.sync.TransitPoint?,
    near: List<TransitStationNear>,
    tag: String,
    suggest: EndpointSearch?,
    onPick: (EndpointHit) -> Unit,
    onClear: () -> Unit,
    onChange: (String) -> Unit,
) {
    var typed by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    var hits by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<List<EndpointHit>>(emptyList()) }
    androidx.compose.runtime.LaunchedEffect(value, typed) {
        val q = value.trim()
        if (!typed || suggest == null || q.isEmpty()) { hits = emptyList(); return@LaunchedEffect }
        // Online hits cost a request: wait until typing pauses.
        kotlinx.coroutines.delay(if (q.length >= 3) 600 else 150)
        hits = try { suggest(q).take(8) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }
    SurfaceCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (point != null && point.name.isNotBlank()) {
                androidx.compose.material3.InputChip(
                    selected = true, onClick = onClear,
                    label = { Text(point.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    leadingIcon = { Icon(androidx.compose.material.icons.Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    trailingIcon = { Icon(androidx.compose.material.icons.Icons.Filled.Close, contentDescription = "Entfernen", modifier = Modifier.size(18.dp).testTag("$tag-place-clear")) },
                    modifier = Modifier.testTag("$tag-place"),
                )
            }
            OutlinedTextField(
                value, { v -> typed = true; onChange(v) },
                label = { Text(if (point != null) "$label (Bahnhof, optional)" else "$label (Ort, Hotel oder Bahnhof)") },
                singleLine = true, modifier = Modifier.fillMaxWidth().testTag(tag),
            )
            hits.forEachIndexed { i, h ->
                Column(
                    Modifier.fillMaxWidth().clickable { typed = false; hits = emptyList(); onPick(h) }.padding(vertical = 6.dp).testTag("$tag-hit-$i"),
                ) {
                    Text(h.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (h.detail.isNotBlank()) Text(h.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (near.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    near.forEachIndexed { i, n ->
                        FilterChip(
                            selected = n.station.nameJa == value,
                            onClick = { onChange(n.station.nameJa) },
                            label = { Text(stationLabel(n.station) + " · " + walkLabel(n.meters)) },
                            modifier = Modifier.testTag("$tag-near-$i"),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Segments(options: List<Pair<String, String>>, value: String, tag: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (key, label) ->
            SegmentedButton(
                selected = value == key,
                onClick = { onSelect(key) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
                modifier = Modifier.testTag("$tag-$key"),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
private fun Toggle(label: String, on: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    FilterChip(selected = on, onClick = { onChange(!on) }, label = { Text(label) }, modifier = Modifier.testTag(tag))
}
