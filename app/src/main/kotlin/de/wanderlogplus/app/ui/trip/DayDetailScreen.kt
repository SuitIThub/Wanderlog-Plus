package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.LabeledDropdown
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.app.ui.dayKicker
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.design.EditSheet
import de.wanderlogplus.app.ui.design.HeroHeader
import de.wanderlogplus.app.ui.design.dayHeroImage
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.query.Suggestion
import de.wanderlogplus.shared.query.filterSuggestions
import de.wanderlogplus.shared.query.sortSuggestions
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** 0 = Sunday (OpeningPeriod convention); null for unparsable dates. */
internal fun dayOfWeekSunday0(date: String): Int? =
    try { LocalDate.parse(date).dayOfWeek.value % 7 } catch (_: DateTimeParseException) { null }

private const val TAB_PROGRAM = 0
private const val TAB_SUGGESTIONS = 1
private const val TAB_NOTES = 2

/**
 * Day detail: hero header (✎ opens "Tag bearbeiten" for title and region) above the tabs Programm (timeline with ⋮
 * menus and an add FAB), Vorschläge and Notizen & Dateien. The selected tab survives recreation.
 */
@OptIn(ExperimentalMaterial3Api::class)
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
    val accent = region?.let { regionColor(it.color) } ?: MaterialTheme.colorScheme.primary
    val items = remember(snapshot, day) { day?.let { snapshot.dayItemsFor(it.id) }.orEmpty() }
    val movements = remember(snapshot, date) { movementsOn(snapshot, date) }
    val heroImage = remember(snapshot, date) { dayHeroImage(snapshot, date) }
    val derivedRegion = remember(snapshot, date) {
        queries?.derivedRegionFor(date)?.let { id -> snapshot.regions.firstOrNull { it.id == id } }
    }

    var tab by rememberSaveable(date) { mutableIntStateOf(TAB_PROGRAM) }
    var editingDay by rememberSaveable(date) { mutableStateOf(false) }
    var adding by rememberSaveable(date) { mutableStateOf(false) }
    var addingNote by rememberSaveable(date) { mutableStateOf(false) }

    // Shown right after saving, until the snapshot carries the new value (or a save failed).
    var pendingTitle by remember(date) { mutableStateOf<String?>(null) }
    LaunchedEffect(day?.title, failureCount) { pendingTitle = null }
    var pendingNote by remember(date) { mutableStateOf<String?>(null) }
    LaunchedEffect(day?.note, failureCount) { pendingNote = null }
    var editingNote by rememberSaveable(date) { mutableStateOf(false) }
    var noteDraft by rememberSaveable(date) { mutableStateOf("") }

    var category by rememberSaveable { mutableStateOf<PlaceCategory?>(null) }
    var openOnly by rememberSaveable { mutableStateOf(false) }
    val hasSuggestions = view?.regionId != null
    val shown = remember(snapshot, date, category, openOnly) {
        val dow = if (openOnly) dayOfWeekSunday0(date) else null
        val all = queries?.suggestionsFor(date).orEmpty().map { Suggestion(it, snapshot.places[it.placeId]) }
        sortSuggestions(filterSuggestions(all, category, dow))
    }

    val shownTitle = pendingTitle ?: day?.title.orEmpty()
    val shownNote = pendingNote ?: day?.note.orEmpty()

    Column(modifier.fillMaxSize()) {
        HeroHeader(
            title = shownTitle.ifBlank { region?.name ?: dayLabel(date) },
            // Region in the kicker instead of a chip: the 140 dp header has no room for chips below the buttons.
            kicker = listOfNotNull(dayKicker(date, snapshot.trip?.startDate), region?.name?.takeIf { shownTitle.isNotBlank() })
                .joinToString(" · "),
            imageUrl = heroImage,
            accent = accent,
            height = 140.dp,
            onBack = onBack,
            onEdit = { editingDay = true },
            titleMaxLines = 1,
        )
        PrimaryTabRow(selectedTabIndex = tab) {
            DayTab("Programm", tab == TAB_PROGRAM, "tab-program") { tab = TAB_PROGRAM }
            DayTab(if (hasSuggestions) "Vorschläge (${shown.size})" else "Vorschläge", tab == TAB_SUGGESTIONS, "tab-suggestions") {
                tab = TAB_SUGGESTIONS
            }
            DayTab("Notizen & Dateien", tab == TAB_NOTES, "tab-notes") { tab = TAB_NOTES }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                TAB_PROGRAM -> DayProgramTab(
                    snapshot = snapshot,
                    items = items,
                    movements = movements,
                    stay = view?.stay,
                    accent = accent,
                    onMove = onMove,
                    onRemove = onRemove,
                    attachments = attachments,
                )
                TAB_SUGGESTIONS -> DaySuggestionsTab(
                    hasRegion = hasSuggestions,
                    date = date,
                    shown = shown,
                    accent = accent,
                    category = category,
                    onCategory = { category = it },
                    openOnly = openOnly,
                    onOpenOnly = { openOnly = it },
                    onAdd = onAddSuggestion,
                )
                else -> DayNotesTab(
                    snapshot = snapshot,
                    date = date,
                    day = day,
                    note = shownNote,
                    accent = accent,
                    editing = editingNote,
                    draft = noteDraft,
                    onDraft = { noteDraft = it },
                    onStartEdit = { noteDraft = shownNote; editingNote = true },
                    onSave = { pendingNote = noteDraft; onSetNote(date, noteDraft); editingNote = false },
                    onCancel = { editingNote = false },
                    attachments = attachments,
                )
            }
            if (tab == TAB_PROGRAM) {
                ExtendedFloatingActionButton(
                    onClick = { addingNote = false; adding = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Hinzufügen") },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("day-add"),
                )
            }
        }
    }

    if (editingDay) {
        val auto = "Automatisch (" + (derivedRegion?.name ?: "keine Region") + ")"
        val options = listOf<Pair<String?, String>>(null to auto) + snapshot.regions.map { it.id to it.name }
        EditSheet("Tag bearbeiten", onDismiss = { editingDay = false }) {
            DayEditForm(shownTitle, day?.regionOverrideId, options, onSave = { title, regionId ->
                if (title != shownTitle) {
                    pendingTitle = title
                    onSetTitle(date, title)
                }
                if (regionId != day?.regionOverrideId) onSetRegion(date, regionId)
                editingDay = false
            })
        }
    }

    if (adding) {
        EditSheet(if (addingNote) "Notiz hinzufügen" else "Hinzufügen", onDismiss = { adding = false; addingNote = false }) {
            if (addingNote) {
                AddNoteForm(
                    onAdd = { text -> onAddNote(date, text); adding = false; addingNote = false },
                    onCancel = { addingNote = false },
                )
            } else {
                AddOption(Icons.Filled.Place, "Ort aus Vorschlägen", "add-from-suggestions") { adding = false; tab = TAB_SUGGESTIONS }
                AddOption(Icons.AutoMirrored.Filled.Notes, "Notiz", "add-note-open") { addingNote = true }
            }
        }
    }
}

@Composable
private fun DayTab(label: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    Tab(
        selected = selected,
        onClick = onClick,
        text = { Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        modifier = Modifier.testTag(tag),
    )
}

@Composable
private fun AddOption(icon: ImageVector, label: String, tag: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(label, style = MaterialTheme.typography.titleMedium)
        }
    }
}

/** Content of "Tag bearbeiten": title field and region dropdown; [onSave] gets the drafts. */
@Composable
internal fun DayEditForm(
    initialTitle: String,
    initialRegion: String?,
    regionOptions: List<Pair<String?, String>>,
    onSave: (title: String, regionId: String?) -> Unit,
) {
    var title by rememberSaveable { mutableStateOf(initialTitle) }
    var regionId by rememberSaveable { mutableStateOf(initialRegion) }
    Column(Modifier.fillMaxWidth().testTag("day-title-edit"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            label = { Text("Titel") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("day-title-field"),
        )
        LabeledDropdown(
            tag = "region-dropdown",
            label = "Region",
            value = regionOptions.firstOrNull { it.first == regionId }?.second ?: regionId.orEmpty(),
            options = regionOptions,
            onSelect = { regionId = it },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = { onSave(title, regionId) }, modifier = Modifier.align(Alignment.End).testTag("day-edit-save")) {
            Text("Speichern")
        }
    }
}

/** Note for the program; [onAdd] gets the trimmed text. */
@Composable
internal fun AddNoteForm(onAdd: (String) -> Unit, onCancel: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Notiz") },
            modifier = Modifier.fillMaxWidth().testTag("add-note-field"),
            minLines = 2,
        )
        Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onCancel) { Text("Abbrechen") }
            Button(onClick = { onAdd(text.trim()) }, enabled = text.isNotBlank(), modifier = Modifier.testTag("add-note-confirm")) {
                Text("Hinzufügen")
            }
        }
    }
}
