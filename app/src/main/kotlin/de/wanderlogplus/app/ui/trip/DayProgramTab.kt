package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.layout.Box
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
    items: List<DayItem>,
    movements: List<Movement>,
    stay: Stay?,
    accent: Color,
    onMove: (itemId: String, up: Boolean) -> Unit,
    onRemove: (itemId: String) -> Unit,
    attachments: AttachmentActions?,
) {
    Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 96.dp)) {
        DayTimeline(
            snapshot = snapshot,
            items = items,
            movements = movements,
            stay = stay,
            accent = accent,
            onOpenItem = null,
            onOpenAttachment = { a -> attachments?.onOpen?.invoke(a) },
            available = attachments?.available ?: { true },
            itemTagPrefix = "program-row-",
            itemActions = { item ->
                val i = items.indexOfFirst { it.id == item.id }
                ItemMenu(item.id, first = i <= 0, last = i == items.lastIndex, onMove, onRemove)
            },
            emptyProgram = {
                EmptyHint("Noch nichts geplant – füge Orte aus den Vorschlägen hinzu.", Modifier.testTag("program-empty"))
            },
            modifier = Modifier.testTag("program-list"),
        )
    }
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
