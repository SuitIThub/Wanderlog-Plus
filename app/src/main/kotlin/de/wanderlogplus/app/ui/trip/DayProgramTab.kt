package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Map
import androidx.compose.foundation.layout.Spacer
import androidx.compose.ui.Alignment
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.ui.design.InsertButton
import de.wanderlogplus.app.ui.design.LegRow
import de.wanderlogplus.client.ItemPosition
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.sync.TravelMode
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.app.ui.design.DayTimeline
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.Stay
import de.wanderlogplus.shared.query.Movement

/** Programm tab: the day's timeline (moves, items with ⋮ menu, night); bottom space keeps the FAB off the last card. */
@Composable
internal fun DayProgramTab(
    snapshot: TripSnapshot,
    date: String,
    items: List<DayItem>,
    movements: List<Movement>,
    stay: Stay?,
    accent: Color,
    onMove: (itemId: String, up: Boolean) -> Unit,
    onRemove: (itemId: String) -> Unit,
    attachments: AttachmentActions?,
    showLegs: Boolean = false,
    onShowLegs: ((Boolean) -> Unit)? = null,
    onLegMode: (itemId: String, mode: TravelMode) -> Unit = { _, _ -> },
    onOpenLink: ((ExternalLink) -> Unit)? = null,
    /** Opens "Hinzufügen" for a position between items; null hides the ＋ elements. */
    onInsert: ((ItemPosition) -> Unit)? = null,
    /** Opens the full map of the day's route; null hides the button. */
    onOpenMap: (() -> Unit)? = null,
    /** Opens "Verbindung abfragen" for the way to this item; null hides the button. */
    onTransitDetails: ((itemId: String) -> Unit)? = null,
    /** Opens the detail of a program place; null: places are not tappable. */
    onOpenPlace: ((placeId: String) -> Unit)? = null,
    /** Saved versions of the program ("Verlauf"); empty hides the button. */
    history: List<de.wanderlogplus.shared.model.DayVersion> = emptyList(),
    /** Restores a version by its time; null hides "Verlauf". */
    onRestoreVersion: ((Long) -> Unit)? = null,
    /** The day's weather; null hides the card. */
    weather: de.wanderlogplus.shared.sync.DayWeather? = null,
    /** "Umplanen" on a rainy day; null hides the button. */
    onReplan: (() -> Unit)? = null,
) {
    var showHistory by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    if (showHistory && onRestoreVersion != null) {
        DayHistoryDialog(snapshot, history, onRestore = { at -> showHistory = false; onRestoreVersion(at) }, onDismiss = { showHistory = false })
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)) {
        if (weather != null) {
            val outdoor = androidx.compose.runtime.remember(snapshot, date) { de.wanderlogplus.app.ui.weather.outdoorPlacesOn(snapshot, date) }
            de.wanderlogplus.app.ui.weather.DayWeatherCard(weather, Modifier.padding(bottom = 8.dp), outdoor = outdoor, onReplan = onReplan)
        }
        val places = items.count { it.kind == DayItemKind.PLACE }
        val legsSwitch = onShowLegs != null && places >= 2
        val mapButton = onOpenMap != null && places >= 1
        val historyButton = onRestoreVersion != null && history.isNotEmpty()
        if (legsSwitch || mapButton || historyButton) {
            Row(
                Modifier.fillMaxWidth().padding(bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (mapButton) {
                    FilledTonalButton(onClick = { onOpenMap?.invoke() }, modifier = Modifier.testTag("day-map")) {
                        Icon(Icons.Filled.Map, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Karte")
                    }
                }
                if (historyButton) {
                    androidx.compose.material3.TextButton(onClick = { showHistory = true }, modifier = Modifier.testTag("day-history-open")) {
                        Icon(Icons.Filled.History, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
                        Text("Verlauf")
                    }
                }
                Spacer(Modifier.weight(1f))
                if (legsSwitch) {
                    Text("Wege", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                    Switch(checked = showLegs, onCheckedChange = onShowLegs, modifier = Modifier.testTag("legs-toggle"))
                }
            }
        }
        DayTimeline(
            snapshot = snapshot,
            date = date,
            items = items,
            movements = movements,
            stay = stay,
            accent = accent,
            onOpenItem = onOpenPlace?.let { open -> { item: DayItem -> item.placeId?.takeIf { item.kind == DayItemKind.PLACE }?.let(open) } },
            onOpenAttachment = { a -> attachments?.onOpen?.invoke(a) },
            available = attachments?.available ?: { true },
            itemTagPrefix = "program-row-",
            moveTagPrefix = "program-move-",
            itemActions = { item ->
                val i = items.indexOfFirst { it.id == item.id }
                ItemMenu(item.id, first = i <= 0, last = i == items.lastIndex, onMove, onRemove)
            },
            emptyProgram = {
                EmptyHint("Noch nichts geplant – füge Orte aus den Vorschlägen hinzu.", Modifier.testTag("program-empty"))
            },
            insertSlot = onInsert?.let { insert -> { pos -> InsertButton(pos.encode(), onClick = { insert(pos) }) } },
            legs = if (!showLegs) null else { from, to ->
                LegRow(
                    tag = to.id,
                    from = placeCoords(snapshot, from),
                    to = placeCoords(snapshot, to),
                    mode = to.legMode ?: TravelMode.WALK,
                    onMode = { m -> onLegMode(to.id, m) },
                    accent = accent,
                    onOpenLink = onOpenLink,
                    datetime = de.wanderlogplus.app.ui.transit.transitDateTime(date, from.time),
                    chosen = to.legTransit,
                    onTransitDetails = onTransitDetails?.let { open -> { open(to.id) } },
                )
            },
            modifier = Modifier.testTag("program-list"),
        )
    }
}

/** Coordinates of a program place; null for notes and places without position. */
internal fun placeCoords(snapshot: TripSnapshot, item: DayItem): LatLngD? {
    val p = item.placeId?.let { snapshot.places[it] } ?: return null
    return if (p.lat == 0.0 && p.lng == 0.0) null else LatLngD(p.lat, p.lng)
}

/** ⋮ menu of a program item; entries tagged `item-up-<id>`, `item-down-<id>`, `item-remove-<id>`. */
@Composable
private fun ItemMenu(id: String, first: Boolean, last: Boolean, onMove: (String, Boolean) -> Unit, onRemove: (String) -> Unit) {
    var open by remember(id) { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag("item-menu-$id")) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Aktionen")
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Nach oben") },
                onClick = { open = false; onMove(id, true) },
                enabled = !first,
                modifier = Modifier.testTag("item-up-$id"),
            )
            DropdownMenuItem(
                text = { Text("Nach unten") },
                onClick = { open = false; onMove(id, false) },
                enabled = !last,
                modifier = Modifier.testTag("item-down-$id"),
            )
            DropdownMenuItem(
                text = { Text("Entfernen") },
                onClick = { open = false; onRemove(id) },
                modifier = Modifier.testTag("item-remove-$id"),
            )
        }
    }
}
