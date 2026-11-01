package de.wanderlogplus.app.ui.design

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsBike
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.links.LinkKind
import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.maps.LegRepository
import de.wanderlogplus.app.maps.LegResult
import de.wanderlogplus.app.maps.LocalLegRepository
import de.wanderlogplus.app.maps.RouteGeometry
import de.wanderlogplus.app.maps.RouteMap
import de.wanderlogplus.shared.sync.TravelMode
import java.util.Locale

fun modeLabel(m: TravelMode) = when (m) {
    TravelMode.WALK -> "Zu Fuß"
    TravelMode.BICYCLE -> "Rad"
    TravelMode.DRIVE -> "Auto"
    TravelMode.TRANSIT -> "ÖPNV"
}

fun modeIcon(m: TravelMode): ImageVector = when (m) {
    TravelMode.WALK -> Icons.AutoMirrored.Filled.DirectionsWalk
    TravelMode.BICYCLE -> Icons.AutoMirrored.Filled.DirectionsBike
    TravelMode.DRIVE -> Icons.Filled.DirectionsCar
    TravelMode.TRANSIT -> Icons.Filled.DirectionsTransit
}

/** "25 Min", "1 Std 5 Min", "2 Std". */
fun durationLabel(seconds: Long): String {
    val min = ((seconds + 30) / 60).coerceAtLeast(1)
    val h = min / 60
    val m = min % 60
    return when {
        h == 0L -> "$m Min"
        m == 0L -> "$h Std"
        else -> "$h Std $m Min"
    }
}

/** "800 m", "1,8 km", "12 km". */
fun distanceLabel(meters: Long): String = when {
    meters < 1000 -> "${(meters / 10) * 10} m"
    meters < 10_000 -> String.format(Locale.GERMAN, "%.1f km", meters / 1000.0)
    else -> "${(meters + 500) / 1000} km"
}

/**
 * One-line summary of a leg result. Google's API has no transit timetables for Japan, so a missing transit route points
 * to the Google Maps app, which does have them.
 */
fun legSummary(r: LegResult?, mode: TravelMode = TravelMode.WALK): String = when (r) {
    null -> "Weg wird berechnet …"
    is LegResult.Route -> durationLabel(r.durationSeconds) + " · " + distanceLabel(r.distanceMeters)
    LegResult.NoRoute ->
        if (mode == TravelMode.TRANSIT) "ÖPNV-Zeiten nur in Google Maps (aufklappen)" else "Keine Route mit diesem Verkehrsmittel"
    LegResult.NotConfigured -> "Wegzeiten brauchen einen Routen-Key auf dem Server"
    LegResult.Offline -> "Offline – Weg noch nicht geladen"
    LegResult.Failed -> "Weg konnte nicht berechnet werden"
}

/** Google Maps directions between the two points in [mode] (also offers live transit departures). */
fun legMapsLink(from: LatLngD, to: LatLngD, mode: TravelMode): ExternalLink {
    val travel = when (mode) {
        TravelMode.WALK -> "walking"
        TravelMode.BICYCLE -> "bicycling"
        TravelMode.DRIVE -> "driving"
        TravelMode.TRANSIT -> "transit"
    }
    return ExternalLink(
        LinkKind.NAVIGATE,
        "In Google Maps öffnen",
        "https://www.google.com/maps/dir/?api=1&origin=${from.lat},${from.lng}&destination=${to.lat},${to.lng}&travelmode=$travel",
    )
}

/**
 * The way to a program place from the previous one: a compact line (mode icon, time, distance) that expands into mode
 * chips, a map of the route and a Google Maps link. Without coordinates it only says so. Tags: `leg-<tag>`,
 * `leg-mode-<MODE>`, `leg-map`.
 */
@Composable
fun LegRow(
    tag: String,
    from: LatLngD?,
    to: LatLngD?,
    mode: TravelMode,
    onMode: (TravelMode) -> Unit,
    accent: Color,
    onOpenLink: ((ExternalLink) -> Unit)?,
    modifier: Modifier = Modifier,
    repository: LegRepository? = LocalLegRepository.current,
) {
    var expanded by rememberSaveable(tag) { mutableStateOf(false) }
    if (from == null || to == null) {
        Text(
            "Weg: ohne Kartenposition",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(vertical = 4.dp).testTag("leg-$tag"),
        )
        return
    }
    var result by remember(from, to, mode, repository) { mutableStateOf(repository?.peek(from, to, mode)) }
    LaunchedEffect(from, to, mode, repository) {
        if (repository != null && result == null) result = repository.leg(from, to, mode)
    }
    Surface(
        modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("leg-$tag"),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(modeIcon(mode), contentDescription = modeLabel(mode), tint = accent, modifier = Modifier.size(20.dp))
                Text(legSummary(result, mode), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).testTag("leg-summary"))
                Icon(
                    if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = if (expanded) "Weg zuklappen" else "Weg aufklappen",
                )
            }
            if (expanded) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TravelMode.entries.forEach { m ->
                        FilterChip(
                            selected = m == mode,
                            onClick = { if (m != mode) onMode(m) },
                            label = { Text(modeLabel(m)) },
                            leadingIcon = { Icon(modeIcon(m), contentDescription = null, modifier = Modifier.size(18.dp)) },
                            modifier = Modifier.testTag("leg-mode-${m.name}"),
                        )
                    }
                }
                val path = (result as? LegResult.Route)?.path?.takeIf { it.size >= 2 }
                RouteMap(
                    RouteGeometry(listOf(from, to), arc = false, path = path),
                    accent,
                    height = 160.dp,
                    modifier = Modifier.padding(top = 8.dp).testTag("leg-map"),
                )
                if (onOpenLink != null) {
                    TextButton(onClick = { onOpenLink(legMapsLink(from, to, mode)) }, modifier = Modifier.padding(start = 4.dp)) {
                        Text("In Google Maps öffnen")
                    }
                }
            }
        }
    }
}
