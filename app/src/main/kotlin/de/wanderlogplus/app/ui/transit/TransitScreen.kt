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

            SectionLabel("Bahnhöfe", color = accent)
            StationField("Von", f.fromStation, state.nearFrom, "transit-from") { v -> onEdit { it.copy(fromStation = v) } }
            StationField("Nach", f.toStation, state.nearTo, "transit-to") { v -> onEdit { it.copy(toStation = v) } }
            Text(
                "In Japan auf Japanisch (z. B. 京都), in Deutschland der Name (z. B. Hannover Kröpcke) – DB, üstra und Nahverkehr inklusive. Leer lassen: der nächste Bahnhof wird gesucht.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SectionLabel("Zeit", color = accent)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    f.date, { v -> onEdit { it.copy(date = v) } },
                    label = { Text("Datum") }, singleLine = true,
                    modifier = Modifier.weight(1.4f).testTag("transit-date"),
                )
                OutlinedTextField(
                    f.time, { v -> onEdit { it.copy(time = v) } },
                    label = { Text("Uhrzeit") }, singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.weight(1f).testTag("transit-time"),
                )
            }
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
                SectionLabel("Verbindungen ${stationLabel(r.fromStation)} → ${stationLabel(r.toStation)}", color = accent)
                r.stationWarning?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                Text(
                    "Preise für einen Erwachsenen, freier Sitz. Fahrpläne für die Reisezeit können sich mit der Fahrplanumstellung im März noch ändern.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                r.routes.forEachIndexed { i, route ->
                    TransitRouteCard(r, route, i, accent, onChoose = { onChoose(r.copy(routes = listOf(route))) })
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StationField(label: String, value: String, near: List<TransitStationNear>, tag: String, onChange: (String) -> Unit) {
    SurfaceCard {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(value, onChange, label = { Text(label) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag(tag))
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
