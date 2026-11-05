package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.Train
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.maps.LegResult
import de.wanderlogplus.app.maps.LocalLegRepository
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.HeroHeader
import de.wanderlogplus.app.ui.design.routeTitle
import de.wanderlogplus.app.ui.design.timesLine
import de.wanderlogplus.client.Journey
import de.wanderlogplus.client.JourneyLeg
import de.wanderlogplus.client.Transfer
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.client.durationText
import de.wanderlogplus.client.endpointPoint
import de.wanderlogplus.client.journeys
import de.wanderlogplus.client.legOf
import de.wanderlogplus.client.movementOf
import de.wanderlogplus.client.transfer
import de.wanderlogplus.shared.model.Attachment
import de.wanderlogplus.shared.query.Movement
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.shared.sync.TravelMode
import kotlin.math.roundToInt

const val JOURNEY_SEGMENT = "journey"

/** Detail route of a journey (shares [LOGISTICS_DETAIL_ROUTE] with the type segment "journey"). */
fun journeyRoute(journeyId: String) = "logistik/$JOURNEY_SEGMENT/${android.net.Uri.encode(journeyId)}"

/** "Flug NH 23" / "Zug Nozomi 21". */
fun legTitle(l: JourneyLeg): String = when (val m = l.movement) {
    is Movement.FlightMove -> flightCardTitle(m.flight)
    is Movement.TrainMove -> trainTitle(m.train) + (m.train.number?.takeIf { it.isNotBlank() }?.let { " $it" } ?: "")
}

fun legIcon(l: JourneyLeg) = if (l.movement is Movement.FlightMove) Icons.Filled.Flight else Icons.Filled.Train

/** Trains and flights that could join [journeyId] (not in it), nearest in time to it first. */
fun joinCandidates(snapshot: TripSnapshot, journeyId: String?, around: java.time.LocalDateTime?): List<JourneyLeg> {
    val legs = (snapshot.flights.filter { it.journeyId == null || it.journeyId != journeyId }.map { legOf(movementOf(it)) } +
        snapshot.trains.filter { it.journeyId == null || it.journeyId != journeyId }.map { legOf(movementOf(it)) })
    return legs.sortedBy { l -> around?.let { a -> l.departs?.let { kotlin.math.abs(java.time.Duration.between(a, it).toMinutes()) } } ?: Long.MAX_VALUE }
}

/**
 * A journey as a compact timeline: each leg as a card (number, times, platform, live status, tickets; tap opens it),
 * the change between two legs (waiting time, platforms/gates, walk or same station, warnings), "＋ Teilstrecke"
 * before, between and after, "Aus Reise entfernen" per leg and "Reise auflösen".
 */
@Composable
fun JourneyScreen(
    snapshot: TripSnapshot,
    journeyId: String,
    onBack: (() -> Unit)?,
    onOpenLeg: (EntityType, String) -> Unit,
    /** New leg of [type] between [prev] and [next] (either may be null). */
    onAddLeg: (type: EntityType, prev: Pair<EntityType, String>?, next: Pair<EntityType, String>?) -> Unit,
    onJoin: (List<Pair<EntityType, String>>) -> Unit,
    onLeave: (EntityType, String) -> Unit,
    onDissolve: (String) -> Unit,
    modifier: Modifier = Modifier,
    onOpenAttachment: (Attachment) -> Unit = {},
    /** Moves the leg at an index one up or down; null hides the arrows. */
    onMoveLeg: ((index: Int, up: Boolean) -> Unit)? = null,
) {
    val journey = remember(snapshot, journeyId) { journeys(snapshot).firstOrNull { it.id == journeyId } }
    val accent = MaterialTheme.colorScheme.primary
    if (journey == null) {
        Column(modifier.fillMaxSize()) {
            HeroHeader("Reise nicht gefunden", kicker = null, imageUrl = null, accent = accent, height = 120.dp, onBack = onBack, titleMaxLines = 1)
            EmptyHint("Diese Reise gibt es nicht mehr.", Modifier.padding(16.dp))
        }
        return
    }
    var adding by remember { mutableStateOf<Int?>(null) } // insert position: 0 = before the first leg
    var picking by remember { mutableStateOf(false) }
    var confirmDissolve by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("journey-screen")) {
        HeroHeader(
            title = "${journey.first.depart.name.ifBlank { journey.first.depart.code ?: "?" }} → ${journey.last.arrive.name.ifBlank { journey.last.arrive.code ?: "?" }}",
            kicker = listOfNotNull(
                journey.first.depart.date?.let(::dayLabel),
                "${journey.legs.size} Teilstrecken",
                journey.totalMinutes?.let { "gesamt ${durationText(it)}" },
            ).joinToString(" · "),
            imageUrl = null, accent = accent, height = 120.dp, onBack = onBack, titleMaxLines = 1,
        )
        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InsertSlot(0) { adding = 0 }
            journey.legs.forEachIndexed { i, leg ->
                LegCard(
                    snapshot, leg, i, journey.legs.size, onOpen = { onOpenLeg(leg.type, leg.id) }, onLeave = { onLeave(leg.type, leg.id) },
                    onOpenAttachment = onOpenAttachment, onMove = onMoveLeg?.let { move -> { up: Boolean -> move(i, up) } },
                )
                if (i < journey.legs.lastIndex) {
                    TransferBlock(snapshot, leg, journey.legs[i + 1], i)
                    InsertSlot(i + 1) { adding = i + 1 }
                }
            }
            InsertSlot(journey.legs.size) { adding = journey.legs.size }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = { picking = true }, modifier = Modifier.testTag("journey-add-existing")) { Text("Vorhandene Fahrt hinzufügen") }
                TextButton(onClick = { confirmDissolve = true }, modifier = Modifier.testTag("journey-dissolve")) { Text("Reise auflösen") }
            }
        }
    }

    adding?.let { pos ->
        val prev = journey.legs.getOrNull(pos - 1)?.let { it.type to it.id }
        val next = journey.legs.getOrNull(pos)?.let { it.type to it.id }
        AlertDialog(
            onDismissRequest = { adding = null },
            title = { Text("Teilstrecke einfügen") },
            text = { Text(insertHint(journey, pos)) },
            confirmButton = {
                Column(horizontalAlignment = Alignment.End) {
                    TextButton(onClick = { adding = null; onAddLeg(EntityType.TRAIN, prev, next) }, modifier = Modifier.testTag("journey-add-train")) { Text("Zug / ÖPNV (mit Verbindungssuche)") }
                    TextButton(onClick = { adding = null; onAddLeg(EntityType.FLIGHT, prev, next) }, modifier = Modifier.testTag("journey-add-flight")) { Text("Flug") }
                    TextButton(onClick = { adding = null; picking = true }) { Text("Vorhandene Fahrt") }
                }
            },
            dismissButton = { TextButton(onClick = { adding = null }) { Text("Abbrechen") } },
        )
    }
    if (picking) {
        JoinDialog(snapshot, journey.id, journey.first.departs, preselected = emptySet(), onDismiss = { picking = false }) { picked ->
            picking = false
            onJoin(listOf(journey.first.type to journey.first.id) + picked)
        }
    }
    if (confirmDissolve) {
        AlertDialog(
            onDismissRequest = { confirmDissolve = false },
            title = { Text("Reise auflösen?") },
            text = { Text("Die Teilstrecken bleiben als einzelne Fahrten erhalten.") },
            confirmButton = { TextButton(onClick = { confirmDissolve = false; onDissolve(journey.id) }, modifier = Modifier.testTag("journey-dissolve-confirm")) { Text("Auflösen") } },
            dismissButton = { TextButton(onClick = { confirmDissolve = false }) { Text("Abbrechen") } },
        )
    }
}

private fun insertHint(j: Journey, pos: Int): String = when {
    pos == 0 -> "Vor ${legTitle(j.first)}, z. B. die Fahrt zum Flughafen oder Bahnhof."
    pos >= j.legs.size -> "Nach ${legTitle(j.last)}, z. B. die Fahrt vom Flughafen zur Unterkunft."
    else -> "Zwischen ${legTitle(j.legs[pos - 1])} und ${legTitle(j.legs[pos])}."
}

@Composable
private fun InsertSlot(position: Int, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.testTag("journey-insert-$position")) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(4.dp))
        Text("Teilstrecke", style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun LegCard(
    snapshot: TripSnapshot,
    leg: JourneyLeg,
    index: Int,
    count: Int,
    onOpen: () -> Unit,
    onLeave: () -> Unit,
    onOpenAttachment: (Attachment) -> Unit,
    onMove: ((up: Boolean) -> Unit)?,
) {
    Card(
        onClick = onOpen,
        modifier = Modifier.fillMaxWidth().testTag("journey-leg-$index"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(legIcon(leg), contentDescription = null, modifier = Modifier.padding(end = 8.dp))
                Text(legTitle(leg), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${index + 1}/$count", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(leg.movement.routeTitle(), style = MaterialTheme.typography.bodyMedium)
            val times = listOfNotNull(leg.depart.date?.takeIf { it != leg.arrive.date && leg.arrive.date != null }?.let(::dayLabel), leg.movement.timesLine().ifBlank { null }).joinToString(" · ")
            if (times.isNotBlank()) Text(times, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            when (val m = leg.movement) {
                is Movement.TrainMove -> {
                    m.train.platformText()?.let { Text("Ab $it", style = MaterialTheme.typography.labelLarge) }
                    if (LocalTrainLive.current != null) rememberTrainLive(m.train.id)?.shortLine()?.let {
                        Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("journey-leg-$index-live"))
                    }
                }
                is Movement.FlightMove -> if (LocalFlightLive.current != null) rememberFlightLive(m.flight.id)?.let { live ->
                    live.shortLine()?.let { Text(it, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.testTag("journey-leg-$index-live")) }
                    listOfNotNull(live.depTerminal?.let { "Terminal $it" }, live.depGate?.let { "Gate $it" }).takeIf { it.isNotEmpty() }?.let {
                        Text("Ab " + it.joinToString(" · "), style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            val tickets = snapshot.attachmentsFor(leg.type, leg.id)
            if (tickets.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    tickets.take(3).forEach { a -> TextButton(onClick = { onOpenAttachment(a) }) { Text("🎟 " + a.fileName, maxLines = 1) } }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (onMove != null) {
                    androidx.compose.material3.IconButton(onClick = { onMove(true) }, enabled = index > 0, modifier = Modifier.testTag("journey-leg-$index-up")) {
                        Icon(androidx.compose.material.icons.Icons.Filled.ArrowUpward, contentDescription = "Nach oben")
                    }
                    androidx.compose.material3.IconButton(onClick = { onMove(false) }, enabled = index < count - 1, modifier = Modifier.testTag("journey-leg-$index-down")) {
                        Icon(androidx.compose.material.icons.Icons.Filled.ArrowDownward, contentDescription = "Nach unten")
                    }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onLeave, modifier = Modifier.testTag("journey-leg-$index-leave")) { Text("Aus Reise entfernen") }
            }
        }
    }
}

/** The change between [from] and [to]: waiting time, platforms/gates, walk or "gleicher Ort", warnings. */
@Composable
private fun TransferBlock(snapshot: TripSnapshot, from: JourneyLeg, to: JourneyLeg, index: Int) {
    val t: Transfer = remember(snapshot, from, to) { transfer(snapshot, from, to) }
    val scheme = MaterialTheme.colorScheme
    val warn = t.tight || t.overlaps
    val fromLive = (from.movement as? Movement.FlightMove)?.takeIf { LocalFlightLive.current != null }?.let { rememberFlightLive(it.flight.id) }
    val toLive = (to.movement as? Movement.FlightMove)?.takeIf { LocalFlightLive.current != null }?.let { rememberFlightLive(it.flight.id) }
    val fromSpot = t.fromSpot ?: fromLive?.let { l -> listOfNotNull(l.arrTerminal?.let { "Terminal $it" }, l.arrGate?.let { "Gate $it" }).joinToString(" · ").ifBlank { null } }
    val toSpot = t.toSpot ?: toLive?.let { l -> listOfNotNull(l.depTerminal?.let { "Terminal $it" }, l.depGate?.let { "Gate $it" }).joinToString(" · ").ifBlank { null } }
    val walk = walkText(snapshot, from, to, t)
    Row(Modifier.fillMaxWidth().padding(start = 12.dp).testTag("journey-transfer-$index"), verticalAlignment = Alignment.Top) {
        Icon(if (warn) Icons.Filled.Warning else Icons.Filled.SwapVert, contentDescription = null, tint = if (warn) scheme.error else scheme.onSurfaceVariant, modifier = Modifier.padding(end = 8.dp, top = 2.dp).size(18.dp))
        Column {
            Text(
                when {
                    t.overlaps -> "Überschneidung: Abfahrt vor der Ankunft"
                    t.minutes != null -> "Umstieg ${durationText(t.minutes!!)}" + if (t.tight) " – knapp" else ""
                    else -> "Umstieg (Zeit unbekannt)"
                },
                style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = if (warn) scheme.error else scheme.onSurface,
            )
            val spots = listOfNotNull(fromSpot, toSpot).takeIf { it.isNotEmpty() }?.let { listOf(fromSpot ?: "?", toSpot ?: "?").joinToString(" → ") }
            spots?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            walk?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant) }
        }
    }
}

/** "gleicher Bahnhof", "Fußweg ca. 12 Min. (850 m)" (Google walk when available) or "Ortswechsel, 4,2 km Luftlinie". */
@Composable
private fun walkText(snapshot: TripSnapshot, from: JourneyLeg, to: JourneyLeg, t: Transfer): String? {
    if (t.samePlace) return if (from.movement is Movement.FlightMove || to.movement is Movement.FlightMove) "gleicher Flughafen" else "gleicher Bahnhof"
    val km = t.distanceKm ?: return null
    val legs = LocalLegRepository.current
    val a = endpointPoint(snapshot, from.arrive)
    val b = endpointPoint(snapshot, to.depart)
    val walk by produceState<LegResult?>(null, legs, a, b) {
        if (legs != null && a != null && b != null && km <= 3) value = legs.leg(LatLngD(a.first, a.second), LatLngD(b.first, b.second), TravelMode.WALK)
    }
    val w = walk as? LegResult.Route
    return if (w != null) {
        val m = (w.durationSeconds / 60.0).roundToInt().coerceAtLeast(1)
        "Fußweg ca. $m Min. (" + (if (w.distanceMeters < 1000) "${w.distanceMeters / 10 * 10} m" else String.format(java.util.Locale.GERMAN, "%.1f km", w.distanceMeters / 1000.0)) + ")"
    } else {
        "Ortswechsel, " + (if (km < 1) "${(km * 1000).toInt() / 10 * 10} m" else String.format(java.util.Locale.GERMAN, "%.1f km", km)) + " Luftlinie"
    }
}

/** Pick trains/flights to join a journey (checkboxes, nearest in time first). */
@Composable
fun JoinDialog(
    snapshot: TripSnapshot,
    journeyId: String?,
    around: java.time.LocalDateTime?,
    preselected: Set<Pair<EntityType, String>>,
    exclude: Set<Pair<EntityType, String>> = emptySet(),
    onDismiss: () -> Unit,
    onConfirm: (List<Pair<EntityType, String>>) -> Unit,
) {
    val candidates = remember(snapshot, journeyId) { joinCandidates(snapshot, journeyId, around).filter { (it.type to it.id) !in exclude } }
    var picked by remember { mutableStateOf(preselected) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Zu einer Reise zusammenführen") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) Text("Keine weiteren Fahrten oder Flüge.")
                candidates.forEach { l ->
                    val key = l.type to l.id
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().testTag("join-${l.id}")) {
                        Checkbox(checked = key in picked, onCheckedChange = { on -> picked = if (on) picked + key else picked - key }, modifier = Modifier.testTag("join-${l.id}-check"))
                        Column {
                            Text(legTitle(l), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                            Text(listOfNotNull(l.depart.date?.let(::dayLabel), l.movement.routeTitle(), l.movement.timesLine().ifBlank { null }).joinToString(" · "), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(picked.toList()) }, enabled = picked.isNotEmpty(), modifier = Modifier.testTag("join-confirm")) { Text("Zusammenführen") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
