package de.wanderlogplus.app.ui.transit

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DirectionsWalk
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.design.Badge
import de.wanderlogplus.app.ui.design.BadgeSpec
import de.wanderlogplus.app.ui.design.BadgeTone
import de.wanderlogplus.app.ui.design.SurfaceCard
import de.wanderlogplus.app.ui.design.durationLabel
import de.wanderlogplus.shared.sync.TransitLeg
import de.wanderlogplus.shared.sync.TransitResult
import de.wanderlogplus.shared.sync.TransitRoute
import de.wanderlogplus.shared.sync.TransitStation
import java.util.Locale

/** "12.530 ¥". */
fun yen(amount: Int): String = String.format(Locale.GERMAN, "%,d ¥", amount)

/** "direkt", "1× umsteigen", "2× umsteigen". */
fun transfersLabel(n: Int): String = if (n <= 0) "direkt" else "$n× umsteigen"

/** "08:12–10:48 · 2 Std 36 Min · 12.530 ¥ · 1× umsteigen". */
fun transitSummary(r: TransitRoute): String =
    listOfNotNull(
        "${r.departure}–${r.arrival}",
        durationLabel(r.durationMinutes * 60L),
        r.fareYen.takeIf { it > 0 }?.let(::yen),
        transfersLabel(r.transfers),
    ).joinToString(" · ")

/** "9:5" / "9:05" → "09:05"; null when it is no time of day. */
fun normalizeTime(t: String?): String? {
    val m = Regex("""^\s*(\d{1,2})[:.](\d{1,2})\s*$""").find(t ?: return null) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    if (h !in 0..23 || min !in 0..59) return null
    return String.format(Locale.ROOT, "%02d:%02d", h, min)
}

/** When the way of a day starts: "2027-05-20T" + the time of the place before it, else 09:00. */
fun transitDateTime(date: String, time: String?): String = "${date}T${normalizeTime(time) ?: "09:00"}"

/** "Tokyo (東京)", or just the Japanese name. */
fun stationLabel(s: TransitStation): String = if (s.name.isNotBlank() && s.name != s.nameJa) "${s.name} (${s.nameJa})" else s.nameJa

/** "450 m", "1,2 km". */
fun walkLabel(meters: Int): String = if (meters < 1000) "${(meters / 10) * 10} m" else String.format(Locale.GERMAN, "%.1f km", meters / 1000.0)

/**
 * One connection: times, duration, transfers and fare (with its parts), then every leg with line, platforms and
 * direction; the walks to and from the stations around it. [onChoose] shows "Übernehmen". Tags: `transit-route-<i>`,
 * `transit-choose-<i>`, `transit-leg-<i>-<n>`.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TransitRouteCard(
    result: TransitResult,
    route: TransitRoute,
    index: Int,
    accent: Color,
    modifier: Modifier = Modifier,
    chosen: Boolean = false,
    onChoose: (() -> Unit)? = null,
) {
    SurfaceCard(modifier.fillMaxWidth().testTag("transit-route-$index")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${route.departure} – ${route.arrival}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (route.fareYen > 0) Text(yen(route.fareYen), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Text(
                listOfNotNull(
                    durationLabel(route.durationMinutes * 60L),
                    transfersLabel(route.transfers),
                    route.distanceKm?.let { String.format(Locale.GERMAN, "%.0f km", it) },
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (chosen) Badge(BadgeSpec("gewählt", BadgeTone.Positive))
                if (route.best) Box(Modifier.testTag("transit-best-$index")) { Badge(BadgeSpec("Beste Balance", BadgeTone.Positive)) }
                if (route.fastest) Badge(BadgeSpec("schnellste", BadgeTone.Accent))
                if (route.cheapest) Badge(BadgeSpec("günstigste", BadgeTone.Accent))
                if (route.fewestTransfers) Badge(BadgeSpec("wenigste Umstiege", BadgeTone.Accent))
            }
            if (route.fareYen > 0) FareBreakdown(route)
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            val rides = route.legs.filter { !it.walk }
            if (route.accessMinutes != null || route.egressMinutes != null) {
                // Smart search: each connection has its own stations and walks.
                route.doorMinutes?.let { d ->
                    Text(
                        if (result.searchType == "arrive") "losgehen ${durationLabel(d * 60L)} vorher" else "Tür zu Tür ${durationLabel(d * 60L)}",
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    )
                }
                route.accessMinutes?.takeIf { it > 0 }?.let { m -> rides.firstOrNull()?.let { WalkLine("ab ${stationLabel(it.from)} · $m Min. zu Fuß") } }
                route.legs.forEachIndexed { n, leg -> LegLine(leg, accent, Modifier.testTag("transit-leg-$index-$n")) }
                route.egressMinutes?.takeIf { it > 0 }?.let { m -> rides.lastOrNull()?.let { WalkLine("an ${stationLabel(it.to)} · $m Min. zu Fuß") } }
            } else {
                result.walkToStationMeters?.takeIf { it > 0 }?.let { WalkLine("${walkLabel(it)} zu Fuß zum Bahnhof ${stationLabel(result.fromStation)}") }
                route.legs.forEachIndexed { n, leg -> LegLine(leg, accent, Modifier.testTag("transit-leg-$index-$n")) }
                result.walkFromStationMeters?.takeIf { it > 0 }?.let { WalkLine("${walkLabel(it)} zu Fuß vom Bahnhof ${stationLabel(result.toStation)}") }
            }
            if (onChoose != null) {
                Button(onClick = onChoose, modifier = Modifier.fillMaxWidth().padding(top = 4.dp).testTag("transit-choose-$index")) {
                    Text(if (chosen) "Erneut übernehmen" else "Für diesen Weg übernehmen")
                }
            }
        }
    }
}

/** "Fahrpreis 7.150 ¥ · Zuschlag, freier Sitz 4.180 ¥ · …" (prices for one adult, non-reserved seat). */
@Composable
private fun FareBreakdown(r: TransitRoute) {
    val parts = buildList {
        if (r.baseFareYen > 0) add("Fahrpreis ${yen(r.baseFareYen)}")
        if (r.fees.isNotEmpty()) r.fees.forEach { add("${it.label.ifBlank { it.labelJa }} ${yen(it.yen)}") }
        else if (r.expressFareYen > 0) add("Zuschläge ${yen(r.expressFareYen)}")
    }
    if (parts.isEmpty()) return
    Text(
        parts.joinToString(" · ") + " · pro Erwachsenem",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.testTag("transit-fare"),
    )
}

@Composable
private fun WalkLine(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.AutoMirrored.Filled.DirectionsWalk, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** A ride: times on the left; line, direction, from/to with platforms; tap shows the intermediate stops. */
@Composable
private fun LegLine(leg: TransitLeg, accent: Color, modifier: Modifier = Modifier) {
    if (leg.walk) {
        WalkLine("${leg.departure}–${leg.arrival} zu Fuß: ${stationLabel(leg.from)} → ${stationLabel(leg.to)}")
        return
    }
    var stopsOpen by rememberSaveable(leg.lineJa, leg.departure) { mutableStateOf(false) }
    Row(modifier.fillMaxWidth().clickable(enabled = leg.stops.isNotEmpty()) { stopsOpen = !stopsOpen }.padding(vertical = 2.dp)) {
        Column(Modifier.width(52.dp)) {
            Text(leg.departure, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
            Text(leg.arrival, style = MaterialTheme.typography.labelLarge)
        }
        Icon(Icons.Filled.DirectionsTransit, contentDescription = null, tint = accent, modifier = Modifier.size(18.dp).padding(top = 2.dp))
        Column(Modifier.padding(start = 8.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(leg.line.ifBlank { leg.lineJa }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            if (leg.line.isNotBlank() && leg.line != leg.lineJa) {
                Text(leg.lineJa + leg.headsignJa.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(
                stationLabel(leg.from) + (leg.departurePlatform?.let { ", Gleis $it" } ?: "") + "  →  " +
                    stationLabel(leg.to) + (leg.arrivalPlatform?.let { ", Gleis $it" } ?: ""),
                style = MaterialTheme.typography.bodySmall,
            )
            if (leg.stops.isNotEmpty()) {
                Text(
                    if (stopsOpen) leg.stops.joinToString(" · ") { "${it.time} ${it.name.ifBlank { it.nameJa }}" }
                    else "${leg.stops.size} Zwischenhalte",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
