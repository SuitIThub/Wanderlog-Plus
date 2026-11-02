package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.maps.DayRouteMapView
import de.wanderlogplus.app.maps.LegRepository
import de.wanderlogplus.app.maps.LocalLegRepository
import de.wanderlogplus.app.maps.LocalMapsEnabled
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.distanceLabel
import de.wanderlogplus.app.ui.design.durationLabel
import de.wanderlogplus.app.ui.design.legSummary
import de.wanderlogplus.app.ui.design.modeIcon
import de.wanderlogplus.app.ui.design.modeLabel
import de.wanderlogplus.client.TripSnapshot
import kotlinx.coroutines.launch
import kotlinx.coroutines.coroutineScope

const val DAY_MAP_ROUTE = "trip/day/{date}/map"

fun dayMapRoute(date: String) = "trip/day/$date/map"

/** "Gesamt 2 Std 10 Min · 8,4 km" plus a note on segments without a computed route; null without segments. */
fun totalsText(t: RouteTotals, segments: Int): String? {
    if (segments == 0) return null
    val sum = if (t.seconds > 0) "Gesamt " + durationLabel(t.seconds) + " · " + distanceLabel(t.meters) else "Gesamt –"
    val missing = when (t.missing) {
        0 -> ""
        1 -> " · 1 Abschnitt ohne Route"
        else -> " · ${t.missing} Abschnitte ohne Route"
    }
    return sum + missing
}

/**
 * Full map of a day's route: the program's places in order, joined by each segment's route in its chosen mode (dashed
 * straight line where none is known). Below: totals and the stops with the ways in between. Tags: `day-map-screen`,
 * `day-map-totals`, `day-map-stop-<n>`, `day-map-leg-<n>` (way to stop n).
 */
@Composable
fun DayMapScreen(
    snapshot: TripSnapshot,
    date: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    repository: LegRepository? = LocalLegRepository.current,
) {
    val stops = remember(snapshot, date) { routeStops(snapshot, date) }
    val plain = remember(snapshot, stops) { routeSegments(snapshot, stops) }
    var segments by remember(plain) { mutableStateOf(plain.map { s -> s.copy(result = repository?.peek(s.from.at, s.to.at, s.mode)) }) }
    LaunchedEffect(plain, repository) {
        if (repository == null) return@LaunchedEffect
        coroutineScope {
            plain.forEachIndexed { i, s ->
                launch {
                    val r = repository.leg(s.from.at, s.to.at, s.mode)
                    segments = segments.toMutableList().also { list -> if (i < list.size) list[i] = list[i].copy(result = r) }
                }
            }
        }
    }
    var selected by rememberSaveable(date) { mutableStateOf<String?>(null) }
    val totals = remember(segments) { routeTotals(segments) }

    Column(modifier.fillMaxSize().testTag("day-map-screen")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Column {
                Text("Tagesroute", style = MaterialTheme.typography.titleLarge)
                Text(dayLabel(date), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (stops.isEmpty()) {
            EmptyHint("Im Programm ist noch kein Ort mit Kartenposition.", Modifier.padding(16.dp))
            return@Column
        }
        Box(Modifier.fillMaxWidth().weight(0.6f)) {
            if (LocalMapsEnabled.current) {
                DayRouteMapView(stops, segments, selected, onSelect = { selected = it }, modifier = Modifier.fillMaxSize())
            } else {
                Text("Karte nicht verfügbar", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.align(Alignment.Center))
            }
        }
        totalsText(totals, segments.size)?.let {
            Text(
                it,
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp).testTag("day-map-totals"),
            )
        }
        LazyColumn(Modifier.fillMaxWidth().weight(0.4f)) {
            itemsIndexed(stops) { i, stop ->
                if (i > 0) {
                    val seg = segments.getOrNull(i - 1)
                    if (seg != null) {
                        Row(
                            Modifier.padding(start = 30.dp, top = 2.dp, bottom = 2.dp).testTag("day-map-leg-${stop.number}"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Icon(modeIcon(seg.mode), contentDescription = modeLabel(seg.mode), tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                            Text(legSummary(seg.result, seg.mode), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                val isSel = stop.itemId == selected
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { selected = stop.itemId }
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                        .testTag("day-map-stop-${stop.number}"),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = CircleShape,
                        color = if (isSel) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(28.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(stop.number.toString(), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                    Text(
                        stop.name,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
