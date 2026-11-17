package de.wanderlogplus.app.ui.transit

import androidx.compose.foundation.clickable
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.trip.dayFlowFor
import de.wanderlogplus.app.ui.trip.dayWay
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.query.DayFlow

/** A way of a day a connection can be saved to: its key (program item id, "ride:…", "night") and what it leads to. */
data class DayWayChoice(val key: String, val label: String)

/** The ways of [date] in flow order: "zu Alter Bergtempel", "zum Zug …", "zur Unterkunft". */
fun dayWayChoices(snapshot: TripSnapshot, date: String): List<DayWayChoice> {
    val flow = dayFlowFor(snapshot, date)
    return flow.legs.map { leg ->
        val way = dayWay(snapshot, date, flow, leg)
        val label = when {
            leg.key == DayFlow.NIGHT -> "zur Unterkunft (${way.toName})"
            leg.key.startsWith("ride:") -> "zur Abfahrt in ${way.toName}"
            else -> "zu ${way.toName}"
        }
        DayWayChoice(leg.key, label)
    }
}

/**
 * "Wohin speichern?" for a connection of the free search: into the Logistik as a train (journey) – [onLogistics] – or
 * as the way to a program point of a day – [onDay] with the date and the way's key. The day starts at [date] (the
 * search's date) when the trip has it. Tags: `save-logistics`, `save-day`, `save-day-date`, `save-way-<key>`.
 */
@Composable
fun SaveConnectionDialog(
    snapshot: TripSnapshot,
    date: String,
    onLogistics: () -> Unit,
    onDay: (date: String, key: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val days = remember(snapshot) { snapshot.queries()?.days().orEmpty().map { it.date } }
    var toDay by rememberSaveable { mutableStateOf(false) }
    var day by rememberSaveable { mutableStateOf(date.takeIf { it in days } ?: days.firstOrNull()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (toDay) "Als Weg in einem Tag" else "Wohin speichern?") },
        text = {
            if (!toDay) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onLogistics, modifier = Modifier.fillMaxWidth().testTag("save-logistics")) { Text("In die Logistik (als Fahrt)") }
                    OutlinedButton(onClick = { toDay = true }, enabled = days.isNotEmpty(), modifier = Modifier.fillMaxWidth().testTag("save-day")) { Text("Als Weg in einem Tag") }
                }
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // ◀ day ▶ (a dropdown inside a dialog does not lay out reliably).
                    val at = days.indexOf(day)
                    androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        androidx.compose.material3.IconButton(onClick = { day = days[at - 1] }, enabled = at > 0, modifier = Modifier.testTag("save-day-prev")) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "Tag davor")
                        }
                        Text(day?.let(::dayLabel).orEmpty(), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).testTag("save-day-date"), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        androidx.compose.material3.IconButton(onClick = { day = days[at + 1] }, enabled = at in 0 until days.lastIndex, modifier = Modifier.testTag("save-day-next")) {
                            androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "Tag danach")
                        }
                    }
                    val ways = remember(snapshot, day) { day?.let { dayWayChoices(snapshot, it) }.orEmpty() }
                    if (ways.isEmpty()) {
                        Text("An diesem Tag gibt es noch keinen Weg – füge erst Programmpunkte hinzu.", style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text("Der Weg …", style = MaterialTheme.typography.labelLarge)
                        Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
                            ways.forEach { w ->
                                Text(
                                    w.label,
                                    style = MaterialTheme.typography.bodyLarge,
                                    modifier = Modifier.fillMaxWidth().clickable { day?.let { onDay(it, w.key) } }.padding(vertical = 10.dp).testTag("save-way-${w.key}"),
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = { if (toDay) toDay = false else onDismiss() }) { Text(if (toDay) "Zurück" else "Abbrechen") } },
    )
}

/** The free connection search (Logistik → "Verbindung suchen"). */
const val TRANSIT_FREE_ROUTE = "logistik/transit-search"

/** The free search for a favourite (Schnell hin on Heute): filled in and started right away. */
const val TRANSIT_FREE_FAV_ROUTE = "logistik/transit-search/{fav}"

fun transitFreeRoute(favId: String) = "logistik/transit-search/${android.net.Uri.encode(favId)}"

/**
 * "Aktuelle Position" as the start: asks for the location permission once, then finds the device's position and hands
 * it over as a picked point ("Mein Standort"), so the nearby stations are suggested. [onMissing] gets a message when
 * no position is available.
 */
@Composable
fun rememberLocateFrom(onFound: (EndpointHit) -> Unit, onMissing: (String) -> Unit): () -> Unit {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val locate: () -> Unit = {
        scope.launchLocate(context, onFound, onMissing)
    }
    val ask = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()) { granted ->
        if (granted.values.any { it }) locate() else onMissing("Ohne Standort-Freigabe kein Start an der aktuellen Position.")
    }
    return {
        if (de.wanderlogplus.app.ui.chat.hasLocationPermission(context)) locate()
        else ask.launch(arrayOf(android.Manifest.permission.ACCESS_FINE_LOCATION, android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }
}

private fun kotlinx.coroutines.CoroutineScope.launchLocate(context: android.content.Context, onFound: (EndpointHit) -> Unit, onMissing: (String) -> Unit) {
    launch {
        val here = runCatching { de.wanderlogplus.app.ui.chat.AndroidChatLocator(context).locate() }.getOrNull()
        if (here == null) onMissing("Standort gerade nicht verfügbar.")
        else onFound(EndpointHit("Mein Standort", "", here.lat, here.lng, fromTrip = false))
    }
}
