package de.wanderlogplus.app.ui.logistics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.shared.sync.FlightLive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Asks the server for a flight's live status (by flight id); null when it cannot be asked. Null local: no live status. */
val LocalFlightLive = compositionLocalOf<(suspend (flightId: String) -> FlightLive?)?> { null }

/** How often a visible live status asks the server (which itself asks AirLabs much less often). */
const val FLIGHT_LIVE_REFRESH_MILLIS = 5 * 60_000L

/** The live status of [flightId], refreshed while shown; null while loading or without a source. */
@Composable
fun rememberFlightLive(flightId: String): FlightLive? {
    val source = LocalFlightLive.current
    val live by produceState<FlightLive?>(null, source, flightId) {
        if (source == null) return@produceState
        while (true) {
            value = try { source(flightId) ?: value } catch (e: CancellationException) { throw e } catch (e: Exception) { value }
            delay(FLIGHT_LIVE_REFRESH_MILLIS)
        }
    }
    return live
}

fun flightStatusLabel(status: String?): String = when (status) {
    "scheduled" -> "Planmäßig"
    "active", "en-route" -> "In der Luft"
    "landed" -> "Gelandet"
    "cancelled" -> "Annulliert"
    "diverted" -> "Umgeleitet"
    "incident" -> "Störung"
    else -> "Status unbekannt"
}

/** "HH:mm" of a local "yyyy-MM-dd HH:mm". */
private fun hm(v: String?): String? = v?.takeIf { it.length >= 16 }?.substring(11, 16)

/** "07:00 → 07:25" (planned → expected or actual); the planned time alone when nothing changed. */
fun liveTimes(scheduled: String?, estimated: String?, actual: String?): String? {
    val plan = hm(scheduled)
    val now = hm(actual) ?: hm(estimated)
    return when {
        plan == null -> now
        now == null || now == plan -> plan
        else -> "$plan → $now"
    }
}

/** Biggest known delay in minutes (departure or arrival). */
fun FlightLive.delay(): Int? = listOfNotNull(depDelayMinutes, arrDelayMinutes).maxOrNull()?.takeIf { it > 0 }

/** One short line for widgets: "In der Luft · +25 Min. · Gate G6". */
fun FlightLive.shortLine(): String? {
    if (!available) return null
    return listOfNotNull(
        flightStatusLabel(status),
        delay()?.let { "+$it Min." },
        depGate?.takeIf { status == "scheduled" }?.let { "Gate $it" },
        arrBaggage?.takeIf { status == "landed" }?.let { "Gepäckband $it" },
    ).joinToString(" · ")
}

@Composable
private fun statusColor(l: FlightLive): Color = when {
    l.status == "cancelled" || l.status == "diverted" || l.status == "incident" -> MaterialTheme.colorScheme.error
    (l.delay() ?: 0) >= 15 -> Color(0xFFE08A00)
    else -> Color(0xFF2E7D32)
}

/** Live status of a flight in its detail page (tag `live-status`). */
@Composable
fun FlightLiveCard(flightId: String, modifier: Modifier = Modifier) {
    if (LocalFlightLive.current == null) return
    val live = rememberFlightLive(flightId)
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth().padding(top = 6.dp).testTag("live-status")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            when {
                live == null -> Text("Live-Status wird geladen …", style = MaterialTheme.typography.bodyMedium)
                !live.available -> Text(live.reason ?: "Noch kein Live-Status.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(flightStatusLabel(live.status), fontWeight = FontWeight.SemiBold, color = statusColor(live), modifier = Modifier.testTag("live-status-label"))
                        live.delay()?.let { Text("+$it Min.", fontWeight = FontWeight.SemiBold, color = statusColor(live)) }
                    }
                    val dep = listOfNotNull(
                        liveTimes(live.depScheduled, live.depEstimated, live.depActual),
                        live.depTerminal?.let { "Terminal $it" },
                        live.depGate?.let { "Gate $it" },
                    ).joinToString(" · ")
                    if (dep.isNotBlank()) Text("Abflug: $dep", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("live-status-dep"))
                    val arr = listOfNotNull(
                        liveTimes(live.arrScheduled, live.arrEstimated, live.arrActual),
                        live.arrTerminal?.let { "Terminal $it" },
                        live.arrGate?.let { "Gate $it" },
                        live.arrBaggage?.let { "Gepäckband $it" },
                    ).joinToString(" · ")
                    if (arr.isNotBlank()) Text("Ankunft: $arr", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.testTag("live-status-arr"))
                    Text("Quelle: AirLabs", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** Asks the server for a train's Shinkansen live status (by train id); null local: none. */
val LocalTrainLive = compositionLocalOf<(suspend (trainId: String) -> de.wanderlogplus.shared.sync.TrainLive?)?> { null }

/** The live status of [trainId], refreshed every 2 minutes while shown. */
@Composable
fun rememberTrainLive(trainId: String): de.wanderlogplus.shared.sync.TrainLive? {
    val source = LocalTrainLive.current
    val live by produceState<de.wanderlogplus.shared.sync.TrainLive?>(null, source, trainId) {
        if (source == null) return@produceState
        while (true) {
            value = try { source(trainId) ?: value } catch (e: CancellationException) { throw e } catch (e: Exception) { value }
            delay(2 * 60_000L)
        }
    }
    return live
}

/** "Nozomi 21: Running between KYOTO and NAGOYA · On time". */
fun de.wanderlogplus.shared.sync.TrainLive.shortLine(): String? =
    if (!available) null else trains.joinToString(" / ") { e -> listOf(e.name, e.status).filter { it.isNotBlank() }.joinToString(": ") }

@Composable
private fun toneColor(tone: String): Color = when (tone) {
    "success" -> Color(0xFF2E7D32)
    "warning" -> Color(0xFFE08A00)
    "danger", "error" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

/** Live status of the Shinkansen of a train in its detail page (tag `train-live`). */
@Composable
fun TrainLiveCard(trainId: String, modifier: Modifier = Modifier) {
    if (LocalTrainLive.current == null) return
    val live = rememberTrainLive(trainId) ?: return
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = modifier.fillMaxWidth().padding(top = 6.dp).testTag("train-live")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!live.available) {
                Text(live.reason ?: "Noch kein Live-Status.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            live.trains.forEach { e ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(e.name, fontWeight = FontWeight.SemiBold)
                    Text(e.status, fontWeight = FontWeight.SemiBold, color = toneColor(e.tone))
                }
                if (e.detail.isNotBlank()) Text(e.detail + (e.updatedAt.takeIf { it.isNotBlank() }?.let { " (Stand $it)" } ?: ""), style = MaterialTheme.typography.bodyMedium)
            }
            live.lineStatus?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Text("Quelle: JR Cyberstation (über shinkansen-live)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
