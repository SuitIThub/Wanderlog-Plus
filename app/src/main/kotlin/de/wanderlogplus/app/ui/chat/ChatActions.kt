package de.wanderlogplus.app.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.dayCardLabel
import de.wanderlogplus.client.ItemPosition
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.sync.ChatCard
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a chat card's buttons do to the trip. The chat itself never writes: the user taps, the app writes through its
 * usual editor (offline, synced like any other edit).
 */
interface ChatActions {
    /** Puts the saved place [savedPlaceId] into the program of [date] at [position]. */
    fun planSaved(savedPlaceId: String, date: String, position: ItemPosition)

    /** Puts the web find [card] (with a location) into the program of [date]; it becomes a planned place too. */
    fun planFound(card: ChatCard, date: String, position: ItemPosition)

    /** Adds the web find [card] (with a location) to the places of its region as an idea. */
    fun addFound(card: ChatCard)
}

/** The saved place a card stands for: its own id, or a saved place at the web find's spot (added meanwhile). */
internal fun savedFor(c: ChatCard, snapshot: TripSnapshot?): SavedPlace? {
    snapshot ?: return null
    c.savedPlaceId?.let { id -> snapshot.savedPlaces.firstOrNull { it.id == id }?.let { return it } }
    val lat = c.lat ?: return null
    val lng = c.lng ?: return null
    return snapshot.savedPlaces.firstOrNull { s ->
        s.status != PlaceStatus.DROPPED && snapshot.places[s.placeId]?.let { p ->
            !(p.lat == 0.0 && p.lng == 0.0) && kmBetween(lat, lng, p.lat, p.lng) < 0.15
        } == true
    }
}

private fun kmBetween(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val h = sin(dLat / 2).let { it * it } + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
    return 2 * 6371.0 * asin(sqrt(h))
}

/** Dates a place is in the program, sorted. */
private fun plannedDates(snapshot: TripSnapshot, placeId: String): List<String> {
    val dayIds = snapshot.dayItems.filter { it.kind == DayItemKind.PLACE && it.placeId == placeId }.map { it.dayId }.toSet()
    return snapshot.days.filter { it.id in dayIds }.map { it.date }.distinct().sorted()
}

/** "Einplanen" and, for web finds with a location, "Zu Orten" below a card. [key] makes the test tags unique. */
@Composable
internal fun ChatCardActions(c: ChatCard, key: String, snapshot: TripSnapshot?, defaultDate: String?, actions: ChatActions) {
    val saved = savedFor(c, snapshot)
    val canPlan = snapshot?.trip != null && (saved != null || c.canAdd)
    if (!canPlan && !c.canAdd) return
    var planning by remember { mutableStateOf(false) }
    val planned = saved?.let { s -> snapshot?.let { plannedDates(it, s.placeId) } }.orEmpty()
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (canPlan) {
            FilledTonalButton(onClick = { planning = true }, modifier = Modifier.testTag("chat-plan-$key")) {
                Icon(Icons.Filled.Event, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("Einplanen", Modifier.padding(start = 6.dp))
            }
        }
        if (c.kind == ChatCard.KIND_WEB && c.canAdd) {
            if (saved == null) {
                OutlinedButton(onClick = { actions.addFound(c) }, modifier = Modifier.testTag("chat-add-$key")) {
                    Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text("Zu Orten", Modifier.padding(start = 6.dp))
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.testTag("chat-saved-$key")) {
                    Icon(Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text("Hinterlegt", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 4.dp))
                }
            }
        }
        val hint = when {
            planned.isNotEmpty() -> "Eingeplant: " + planned.joinToString(", ") { dayCardLabel(it) }
            c.planDate != null -> "Vorschlag: " + dayCardLabel(c.planDate!!)
            else -> null
        }
        hint?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
    if (planning && snapshot != null) {
        PlanDialog(
            title = snapshot.places[saved?.placeId]?.name ?: c.title,
            snapshot = snapshot,
            initialDate = c.planDate ?: defaultDate,
            suggestedDate = c.planDate,
            suggestedAfter = c.planAfter,
            onDismiss = { planning = false },
            onPlan = { date, position ->
                planning = false
                if (saved != null) actions.planSaved(saved.id, date, position) else actions.planFound(c, date, position)
            },
        )
    }
}

/** The category a web find is added with. */
internal fun ChatCard.placeCategory(): PlaceCategory =
    category?.let { c -> PlaceCategory.entries.firstOrNull { it.name == c } } ?: PlaceCategory.SIGHT

/** Picks a trip day and where in its program: start, after an item, or end (the default; or the chat's suggestion). */
@Composable
internal fun PlanDialog(
    title: String,
    snapshot: TripSnapshot,
    initialDate: String?,
    suggestedDate: String?,
    suggestedAfter: String?,
    onDismiss: () -> Unit,
    onPlan: (String, ItemPosition) -> Unit,
) {
    val queries = remember(snapshot) { snapshot.queries() }
    val days = remember(queries) { queries?.days().orEmpty() }
    if (days.isEmpty()) return
    var date by rememberSaveable { mutableStateOf(initialDate?.takeIf { d -> days.any { it.date == d } } ?: days.first().date) }
    val items = remember(snapshot, date) { queries?.dayFor(date)?.let { snapshot.dayItemsFor(it.id) }.orEmpty() }
    fun suggested(): String {
        if (date != suggestedDate || suggestedAfter == null) return ItemPosition.End.encode()
        val placeId = snapshot.savedPlaces.firstOrNull { it.id == suggestedAfter }?.placeId ?: suggestedAfter
        return items.firstOrNull { it.kind == DayItemKind.PLACE && it.placeId == placeId }?.let { ItemPosition.After(it.id).encode() } ?: ItemPosition.End.encode()
    }
    var position by rememberSaveable(date) { mutableStateOf(suggested()) }
    val dayList = rememberLazyListState()
    LaunchedEffect(Unit) { days.indexOfFirst { it.date == date }.takeIf { it > 0 }?.let { dayList.scrollToItem(it) } }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("„$title“ einplanen", maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                LazyRow(state = dayList, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("plan-days")) {
                    items(days, key = { it.date }) { d ->
                        val region = d.regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id }?.name }
                        FilterChip(
                            selected = d.date == date,
                            onClick = { date = d.date },
                            label = { Text(listOfNotNull(dayCardLabel(d.date), region).joinToString(" · "), maxLines = 1) },
                            modifier = Modifier.testTag("plan-day-${d.date}"),
                        )
                    }
                }
                Text("Wo im Programm?", style = MaterialTheme.typography.labelLarge)
                val options = buildList {
                    add(ItemPosition.Start.encode() to "Am Anfang")
                    items.forEach { i ->
                        val name = if (i.kind == DayItemKind.PLACE) snapshot.places[i.placeId]?.name ?: "Ort" else i.text.lineSequence().firstOrNull().orEmpty().take(40).ifBlank { "Notiz" }
                        add(ItemPosition.After(i.id).encode() to "Nach " + (i.time?.let { "$it " } ?: "") + name)
                    }
                    add(ItemPosition.End.encode() to "Am Ende")
                }.distinctBy { it.first }
                Column(Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState())) {
                    options.forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = position == value, onClick = { position = value }, role = Role.RadioButton)
                                .padding(vertical = 2.dp).testTag("plan-pos-$value"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = position == value, onClick = null)
                            Text(label, Modifier.padding(start = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onPlan(date, ItemPosition.decode(position)) }, modifier = Modifier.testTag("plan-confirm")) { Text("Einplanen") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Abbrechen") } },
    )
}
