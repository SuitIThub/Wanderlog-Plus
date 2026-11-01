package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.LabeledDropdown
import de.wanderlogplus.app.ui.MarkdownText
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.app.ui.attachments.AttachmentsFor
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.ratingLabel
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.DayItem
import de.wanderlogplus.shared.model.DayItemKind
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.query.Suggestion
import de.wanderlogplus.shared.query.filterSuggestions
import de.wanderlogplus.shared.query.sortSuggestions
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** 0 = Sunday (OpeningPeriod convention); null for unparsable dates. */
private fun dayOfWeekSunday0(date: String): Int? =
    try { LocalDate.parse(date).dayOfWeek.value % 7 } catch (_: DateTimeParseException) { null }

@Composable
fun DayDetailScreen(
    snapshot: TripSnapshot,
    date: String,
    onBack: () -> Unit,
    onAddSuggestion: (date: String, savedPlaceId: String) -> Unit,
    onMove: (itemId: String, up: Boolean) -> Unit,
    onRemove: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
    attachments: AttachmentActions? = null,
    onSetTitle: (date: String, title: String) -> Unit = { _, _ -> },
    onSetNote: (date: String, note: String) -> Unit = { _, _ -> },
    /** Null regionId = "Automatisch" (clears the override). */
    onSetRegion: (date: String, regionId: String?) -> Unit = { _, _ -> },
    onAddNote: (date: String, text: String) -> Unit = { _, _ -> },
    /** Increases whenever a save failed; optimistic note/title are dropped then. */
    failureCount: Int = 0,
) {
    val queries = remember(snapshot) { snapshot.queries() }
    val view = remember(queries, date) { queries?.days()?.firstOrNull { it.date == date } }
    val day = view?.day
    val region = remember(snapshot, view) { view?.regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id } } }
    val stayName = view?.stay?.let { snapshot.places[it.placeId]?.name }?.takeIf { it.isNotBlank() }
    val items = remember(snapshot, day) { day?.let { snapshot.dayItemsFor(it.id) }.orEmpty() }

    val derivedRegion = remember(snapshot, date) {
        queries?.derivedRegionFor(date)?.let { id -> snapshot.regions.firstOrNull { it.id == id } }
    }
    var editingTitle by rememberSaveable(date) { mutableStateOf(false) }
    var titleDraft by rememberSaveable(date) { mutableStateOf("") }
    var pendingTitle by remember(date) { mutableStateOf<String?>(null) }
    LaunchedEffect(day?.title, failureCount) { pendingTitle = null }
    var editingNote by rememberSaveable(date) { mutableStateOf(false) }
    var noteDraft by rememberSaveable(date) { mutableStateOf("") }
    // Shown right after saving, until the snapshot carries the new note.
    var pendingNote by remember(date) { mutableStateOf<String?>(null) }
    LaunchedEffect(day?.note, failureCount) { pendingNote = null }
    var addingNote by rememberSaveable(date) { mutableStateOf(false) }
    var newNoteText by rememberSaveable(date) { mutableStateOf("") }

    var category by rememberSaveable { mutableStateOf<PlaceCategory?>(null) }
    var openOnly by rememberSaveable { mutableStateOf(false) }
    val dow = if (openOnly) dayOfWeekSunday0(date) else null
    val shown = remember(snapshot, date, category, dow) {
        val all = queries?.suggestionsFor(date).orEmpty().map { Suggestion(it, snapshot.places[it.placeId]) }
        sortSuggestions(filterSuggestions(all, category, dow))
    }

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Column(Modifier.padding(end = 16.dp)) {
                Text(dayLabel(date), style = MaterialTheme.typography.titleLarge)
                if (editingTitle) {
                    OutlinedTextField(
                        value = titleDraft,
                        onValueChange = { titleDraft = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("day-title-field"),
                    )
                    Row {
                        Button(onClick = { pendingTitle = titleDraft; onSetTitle(date, titleDraft); editingTitle = false }) { Text("Speichern") }
                        TextButton(onClick = { editingTitle = false }) { Text("Abbrechen") }
                    }
                } else {
                    val shownTitle = pendingTitle ?: day?.title.orEmpty()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (shownTitle.isNotBlank()) Text(shownTitle, style = MaterialTheme.typography.titleMedium)
                        else Text("Kein Titel", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        IconButton(onClick = { titleDraft = shownTitle; editingTitle = true }, modifier = Modifier.testTag("day-title-edit")) {
                            Icon(Icons.Filled.Edit, contentDescription = "Titel bearbeiten")
                        }
                    }
                }
                region?.let { RegionChip(it.name, regionColor(it.color)) }
                stayName?.let {
                    Text("Unterkunft: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        Section("Notiz") {
            if (editingNote) {
                OutlinedTextField(
                    value = noteDraft,
                    onValueChange = { noteDraft = it },
                    modifier = Modifier.fillMaxWidth().testTag("day-note-field"),
                    minLines = 3,
                )
                Row {
                    Button(onClick = { pendingNote = noteDraft; onSetNote(date, noteDraft); editingNote = false }) { Text("Speichern") }
                    TextButton(onClick = { editingNote = false }) { Text("Abbrechen") }
                }
            } else {
                val shownNote = pendingNote ?: day?.note.orEmpty()
                if (shownNote.isNotBlank()) MarkdownText(shownNote, Modifier.testTag("day-note"))
                else Text("Keine Notiz", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { noteDraft = shownNote; editingNote = true }) { Text("Bearbeiten") }
            }
        }

        Section("Region") {
            val override = day?.regionOverrideId
            val auto = "Automatisch (" + (derivedRegion?.name ?: "keine Region") + ")"
            val current = override?.let { id -> snapshot.regions.firstOrNull { it.id == id }?.name ?: id } ?: auto
            LabeledDropdown(
                tag = "region-dropdown",
                label = "Region",
                value = current,
                options = listOf<Pair<String?, String>>(null to auto) + snapshot.regions.map { it.id to it.name },
                onSelect = { onSetRegion(date, it) },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Section("Programm") {
            if (items.isEmpty()) {
                Text("Noch nichts geplant", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Column(Modifier.testTag("program-list")) {
                items.forEachIndexed { i, item -> ProgramRow(item, snapshot, i == 0, i == items.lastIndex, onMove, onRemove) }
            }
            if (addingNote) {
                // Inline instead of a dialog: text fields inside dialog windows hang the Robolectric text layout.
                OutlinedTextField(
                    value = newNoteText,
                    onValueChange = { newNoteText = it },
                    modifier = Modifier.fillMaxWidth().testTag("add-note-field"),
                    minLines = 2,
                )
                Row {
                    Button(
                        onClick = { onAddNote(date, newNoteText.trim()); addingNote = false },
                        enabled = newNoteText.isNotBlank(),
                        modifier = Modifier.testTag("add-note-confirm"),
                    ) { Text("Hinzufügen") }
                    TextButton(onClick = { addingNote = false }) { Text("Abbrechen") }
                }
            } else {
                TextButton(onClick = { newNoteText = ""; addingNote = true }) { Text("Notiz zum Programm hinzufügen") }
            }
        }

        if (attachments != null) {
            AttachmentsFor(
                day?.let { snapshot.attachmentsFor(EntityType.DAY, it.id) }.orEmpty(),
                attachments,
                onAdd = { attachments.onAddToDay(date) },
                modifier = Modifier.padding(horizontal = 16.dp),
            )
        }

        Section("Vorschläge") {
            if (view == null || view.regionId == null) {
                Text("Reisetag – keine Vorschläge", style = MaterialTheme.typography.bodyMedium)
            } else {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    (listOf<PlaceCategory?>(null) + PlaceCategory.entries).forEach { c ->
                        FilterChip(selected = category == c, onClick = { category = c }, label = { Text(categoryLabel(c)) })
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
                    Switch(checked = openOnly, onCheckedChange = { openOnly = it })
                    Text("an diesem Wochentag geöffnet", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.bodyMedium)
                }
                if (shown.isEmpty()) Text("Keine Vorschläge", style = MaterialTheme.typography.bodyMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    shown.forEach { s -> SuggestionCard(s, date, onAddSuggestion) }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        HorizontalDivider(Modifier.padding(bottom = 8.dp))
        Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
        content()
    }
}

@Composable
private fun ProgramRow(
    item: DayItem,
    snapshot: TripSnapshot,
    first: Boolean,
    last: Boolean,
    onMove: (String, Boolean) -> Unit,
    onRemove: (String) -> Unit,
) {
    val name = when (item.kind) {
        DayItemKind.PLACE -> snapshot.places[item.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort"
        DayItemKind.NOTE -> item.text
    }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("program-row-${item.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item.time?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.labelLarge) }
            Text(name, style = MaterialTheme.typography.bodyLarge)
        }
        Row {
            TextButton(onClick = { onMove(item.id, true) }, enabled = !first) { Text("Nach oben") }
            TextButton(onClick = { onMove(item.id, false) }, enabled = !last) { Text("Nach unten") }
            TextButton(onClick = { onRemove(item.id) }) { Text("Entfernen") }
        }
    }
}

@Composable
private fun SuggestionCard(s: Suggestion, date: String, onAdd: (String, String) -> Unit) {
    Card(Modifier.fillMaxWidth().testTag("suggestion-${s.saved.id}")) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(s.place?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort", style = MaterialTheme.typography.titleSmall)
            if (s.saved.tags.isNotEmpty()) Text(s.saved.tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            val rating = s.place?.rating?.let { ratingLabel(it) }
            Text(
                listOfNotNull(categoryLabel(s.saved.category), rating).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { onAdd(date, s.saved.id) }) { Text("Zum Tag hinzufügen") }
        }
    }
}
