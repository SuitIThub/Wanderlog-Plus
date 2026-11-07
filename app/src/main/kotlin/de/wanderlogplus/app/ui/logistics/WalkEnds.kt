package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.maps.LegResult
import de.wanderlogplus.app.maps.LocalLegRepository
import de.wanderlogplus.app.maps.coords
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Endpoint
import de.wanderlogplus.shared.sync.TravelMode
import kotlin.math.ceil
import kotlin.math.roundToInt

/** "ca. 16 Min. zu Fuß" from the straight-line distance (80 m per minute, 1.3 for the detours); null beyond 3 km. */
fun walkEstimate(km: Double): String? =
    if (km > 3) null else "ca. ${ceil(km * 1000 * 1.3 / 80).toInt().coerceAtLeast(1)} Min. zu Fuß"

/**
 * The walk between a place and a station: "<Ort> · ca. 12 Min. zu Fuß zu <Bahnhof>" ([before]) or "<Bahnhof> · ca.
 * 25 Min. zu Fuß zu <Ort>". Google's walk when available, else estimated; far apart only the distance.
 */
@Composable
fun WalkEndRow(snapshot: TripSnapshot, place: Endpoint, station: Endpoint, before: Boolean, tag: String, modifier: Modifier = Modifier) {
    val a = place.coords(snapshot)
    val b = station.coords(snapshot)
    val km = if (a != null && b != null) de.wanderlogplus.app.ui.places.distanceKm(a, b) else null
    val legs = LocalLegRepository.current
    val route by produceState<LegResult?>(null, legs, a, b) {
        if (legs != null && a != null && b != null && km != null && km <= 3) {
            value = if (before) legs.leg(LatLngD(a.lat, a.lng), LatLngD(b.lat, b.lng), TravelMode.WALK) else legs.leg(LatLngD(b.lat, b.lng), LatLngD(a.lat, a.lng), TravelMode.WALK)
        }
    }
    val walk = (route as? LegResult.Route)?.let { "ca. ${(it.durationSeconds / 60.0).roundToInt().coerceAtLeast(1)} Min. zu Fuß" }
        ?: km?.let { walkEstimate(it) }
    val distance = km?.takeIf { walk == null }?.let { String.format(java.util.Locale.GERMAN, "%.1f km", it) }
    val stationName = station.name.ifBlank { station.code ?: "Bahnhof" }
    val text = if (before) {
        place.name + (walk?.let { " · $it zu $stationName" } ?: distance?.let { " ($it bis $stationName)" }.orEmpty())
    } else {
        stationName + (walk?.let { " · $it zu ${place.name}" } ?: distance?.let { " · ${place.name} ($it)" } ?: " · ${place.name}")
    }
    Row(modifier.fillMaxWidth().testTag(tag), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.padding(end = 8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** "Start (vor dem Bahnhof)" / "Ziel (nach dem Bahnhof)" in the train form: the place, search and clear. */
@Composable
internal fun WalkEndField(label: String, value: Endpoint?, tag: String, onSearch: (() -> Unit)?, onClear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value?.name.orEmpty(), {}, readOnly = true, singleLine = true, label = { Text(label) },
            placeholder = { Text("keiner") }, modifier = Modifier.weight(1f).testTag(tag),
        )
        if (onSearch != null) IconButton(onClick = onSearch, modifier = Modifier.testTag("$tag-search")) { Icon(Icons.Filled.Search, contentDescription = "Ort suchen") }
        if (value != null) IconButton(onClick = onClear, modifier = Modifier.testTag("$tag-clear")) { Icon(Icons.Filled.Close, contentDescription = "Entfernen") }
    }
}
