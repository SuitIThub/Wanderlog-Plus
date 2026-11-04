package de.wanderlogplus.app.ui.trip

import de.wanderlogplus.shared.sync.EntityType
import androidx.compose.foundation.verticalScroll
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
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.client.ItemPosition
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Hotel
import de.wanderlogplus.shared.sync.TravelMode
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.LabeledDropdown
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.app.ui.dayKicker
import de.wanderlogplus.app.ui.dayLabel
import de.wanderlogplus.app.ui.design.EditSheet
import de.wanderlogplus.app.ui.design.HeroHeader
import de.wanderlogplus.app.ui.design.ScreenTab
import de.wanderlogplus.app.ui.design.dayHeroImage
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.query.Suggestion
import de.wanderlogplus.shared.query.filterSuggestions
import de.wanderlogplus.shared.query.movementsOn
import de.wanderlogplus.shared.query.sortSuggestions
import java.time.LocalDate
import java.time.format.DateTimeParseException

/** 0 = Sunday (OpeningPeriod convention); null for unparsable dates. */
internal fun dayOfWeekSunday0(date: String): Int? =
    try { LocalDate.parse(date).dayOfWeek.value % 7 } catch (_: DateTimeParseException) { null }

private const val TAB_PROGRAM = 0
private const val TAB_SUGGESTIONS = 1
private const val TAB_NOTES = 2
private const val TAB_PHOTOS = 3

/**
 * Day detail: hero header (✎ opens "Tag bearbeiten" for title and region) above the tabs Programm (timeline with ⋮
 * menus and an add FAB), Vorschläge and Notizen & Anhänge. The selected tab survives recreation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DayDetailScreen(
    snapshot: TripSnapshot,
    date: String,
    /** Null hides the back button (detail pane beside its list). */
    onBack: (() -> Unit)?,
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
    /** Null hides the "Wege" switch. */
    onShowLegs: ((date: String, show: Boolean) -> Unit)? = null,
    onLegMode: (itemId: String, mode: TravelMode) -> Unit = { _, _ -> },
    onOpenLink: ((ExternalLink) -> Unit)? = null,
    /** Suggestion added at a position (from a ＋ between items). */
    onAddSuggestionAt: (date: String, savedPlaceId: String, position: ItemPosition) -> Unit = { d, id, _ -> onAddSuggestion(d, id) },
    onAddNoteAt: (date: String, text: String, position: ItemPosition) -> Unit = { d, t, _ -> onAddNote(d, t) },
    /** Opens the place search (text + map) for a position; null hides "Ort suchen". */
    onSearchPlace: ((date: String, position: ItemPosition) -> Unit)? = null,
    /** Opens the full map of the day's route; null hides "Karte". */
    onOpenMap: ((date: String) -> Unit)? = null,
    /** "Verbindung abfragen" for the way to a program item; null hides it. */
    onTransitDetails: ((date: String, itemId: String) -> Unit)? = null,
    /** Opens the detail of a program place (by Place id); null: not tappable. */
    onOpenPlace: ((placeId: String) -> Unit)? = null,
    /** Opens photo [index] of the day's gallery full screen; null: photos are only shown. */
    onOpenPhoto: ((EntityType, String, Int) -> Unit)? = null,
    /** Restores a saved version of the program ("Verlauf"); null hides it. */
    onRestoreVersion: ((date: String, at: Long) -> Unit)? = null,
    /** Puts a stay's hotel into the program at a position; null hides "Unterkunft". */
    onAddStayAt: ((date: String, stayId: String, position: ItemPosition) -> Unit)? = null,
) {
    val queries = remember(snapshot) { snapshot.queries() }
    val view = remember(queries, date) { queries?.days()?.firstOrNull { it.date == date } }
    val day = view?.day
    val region = remember(snapshot, view) { view?.regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id } } }
    val accent = region?.let { regionColor(it.color) } ?: MaterialTheme.colorScheme.primary
    val items = remember(snapshot, day) { day?.let { snapshot.dayItemsFor(it.id) }.orEmpty() }
    val movements = remember(snapshot, date) { movementsOn(snapshot.flights, snapshot.trains, date) }
    val heroImage = remember(snapshot, date) { dayHeroImage(snapshot, date) }
    val derivedRegion = remember(snapshot, date) {
        queries?.derivedRegionFor(date)?.let { id -> snapshot.regions.firstOrNull { it.id == id } }
    }

    var tab by rememberSaveable(date) { mutableIntStateOf(TAB_PROGRAM) }
    var editingDay by rememberSaveable(date) { mutableStateOf(false) }
    var adding by rememberSaveable(date) { mutableStateOf(false) }
    var addingNote by rememberSaveable(date) { mutableStateOf(false) }
    var addingStay by rememberSaveable(date) { mutableStateOf(false) }
    // Where "Hinzufügen" puts new items (encoded ItemPosition); a suggestion picked after "Ort aus Vorschlägen" goes there too.
    var insertAt by rememberSaveable(date) { mutableStateOf(ItemPosition.End.encode()) }
    var suggestionAt by rememberSaveable(date) { mutableStateOf<String?>(null) }

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
            kicker = listOfNotNull(dayKicker(date, snapshot.trip?.startDate, snapshot.trip?.endDate), region?.name?.takeIf { shownTitle.isNotBlank() })
                .joinToString(" · "),
            imageUrl = heroImage,
            accent = accent,
            height = 140.dp,
            onBack = onBack,
            onEdit = { editingDay = true },
            titleMaxLines = 1,
        )
        PrimaryTabRow(selectedTabIndex = tab) {
            ScreenTab("Programm", tab == TAB_PROGRAM, "tab-program", maxLines = 2) { tab = TAB_PROGRAM }
            ScreenTab(if (hasSuggestions) "Vorschläge (${shown.size})" else "Vorschläge", tab == TAB_SUGGESTIONS, "tab-suggestions", maxLines = 2) {
                tab = TAB_SUGGESTIONS
            }
            ScreenTab("Notizen & Anhänge", tab == TAB_NOTES, "tab-notes", maxLines = 2) { tab = TAB_NOTES }
            val photoCount = day?.let { snapshot.photosFor(EntityType.DAY, it.id).size } ?: 0
            ScreenTab(if (photoCount > 0) "Fotos ($photoCount)" else "Fotos", tab == TAB_PHOTOS, "tab-photos", maxLines = 2) { tab = TAB_PHOTOS }
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                TAB_PROGRAM -> DayProgramTab(
                    snapshot = snapshot,
                    date = date,
                    items = items,
                    movements = movements,
                    stay = view?.stay,
                    accent = accent,
                    onMove = onMove,
                    onRemove = onRemove,
                    attachments = attachments,
                    showLegs = day?.showLegs == true,
                    onShowLegs = onShowLegs?.let { f -> { show: Boolean -> f(date, show) } },
                    onLegMode = onLegMode,
                    onOpenLink = onOpenLink,
                    onInsert = { pos -> insertAt = pos.encode(); addingNote = false; adding = true },
                    onOpenMap = onOpenMap?.let { open -> { open(date) } },
                    onTransitDetails = onTransitDetails?.let { open -> { id -> open(date, id) } },
                    onOpenPlace = onOpenPlace,
                    history = day?.history.orEmpty(),
                    onRestoreVersion = onRestoreVersion?.let { restore -> { at: Long -> restore(date, at) } },
                    weather = de.wanderlogplus.app.ui.weather.LocalTripWeather.current?.forDate(date),
                    onReplan = de.wanderlogplus.app.ui.design.LocalOpenChat.current,
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
                    onAdd = { d, id ->
                        val pos = suggestionAt
                        if (pos == null) {
                            onAddSuggestion(d, id)
                        } else {
                            onAddSuggestionAt(d, id, ItemPosition.decode(pos))
                            suggestionAt = null
                            tab = TAB_PROGRAM
                        }
                    },
                )
                TAB_PHOTOS -> androidx.compose.foundation.layout.Column(Modifier.fillMaxSize().verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(16.dp)) {
                    de.wanderlogplus.app.ui.attachments.PhotoGrid(
                        day?.let { snapshot.photosFor(EntityType.DAY, it.id) }.orEmpty(),
                        attachments?.files,
                        onAdd = attachments?.onAddPhotosToDay?.let { add -> { add(date) } },
                        onOpen = { i -> day?.let { onOpenPhoto?.invoke(EntityType.DAY, it.id, i) } },
                    )
                }
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
                    onClick = { insertAt = ItemPosition.End.encode(); addingNote = false; adding = true },
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
            DayEditForm(shownTitle, day?.regionOverrideId, options, onSave = { changes ->
                changes.title?.let { title ->
                    pendingTitle = title
                    onSetTitle(date, title)
                }
                if (changes.regionChanged) onSetRegion(date, changes.regionId)
                editingDay = false
            })
        }
    }

    if (adding) {
        EditSheet(
            when {
                addingNote -> "Notiz hinzufügen"
                addingStay -> "Unterkunft hinzufügen"
                else -> "Hinzufügen"
            },
            onDismiss = { adding = false; addingNote = false; addingStay = false },
        ) {
            if (addingStay && onAddStayAt != null) {
                stayChoices(snapshot, date).forEach { (stay, label) ->
                    AddOption(Icons.Filled.Hotel, label, "add-stay-${stay.id}") {
                        onAddStayAt(date, stay.id, ItemPosition.decode(insertAt))
                        adding = false; addingStay = false
                    }
                }
            } else if (addingNote) {
                AddNoteForm(
                    onAdd = { text ->
                        val pos = ItemPosition.decode(insertAt)
                        if (pos == ItemPosition.End) onAddNote(date, text) else onAddNoteAt(date, text, pos)
                        adding = false; addingNote = false
                    },
                    onCancel = { addingNote = false },
                )
            } else {
                AddOption(Icons.Filled.Place, "Ort aus Vorschlägen", "add-from-suggestions") {
                    adding = false
                    suggestionAt = insertAt.takeIf { ItemPosition.decode(it) != ItemPosition.End }
                    tab = TAB_SUGGESTIONS
                }
                onSearchPlace?.let { search ->
                    AddOption(Icons.Filled.Search, "Ort suchen (Name oder Karte)", "add-search") {
                        adding = false
                        search(date, ItemPosition.decode(insertAt))
                    }
                }
                if (onAddStayAt != null && snapshot.stays.any { it.placeId.isNotBlank() }) {
                    AddOption(Icons.Filled.Hotel, "Unterkunft", "add-stay-open") { addingStay = true }
                }
                AddOption(Icons.AutoMirrored.Filled.Notes, "Notiz", "add-note-open") { addingNote = true }
            }
        }
    }
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

/**
 * Stays to offer for [date] with a label: the ones the night before and after the date first ("heute"), then all
 * others by check-in. Stays without a place are left out.
 */
internal fun stayChoices(snapshot: TripSnapshot, date: String): List<Pair<de.wanderlogplus.shared.model.Stay, String>> {
    fun name(s: de.wanderlogplus.shared.model.Stay) = snapshot.places[s.placeId]?.name?.takeIf { it.isNotBlank() } ?: "Unterkunft"
    val usable = snapshot.stays.filter { it.placeId.isNotBlank() }
    val current = usable.filter { it.checkIn.isNotBlank() && it.checkOut.isNotBlank() && date >= it.checkIn && date <= it.checkOut }
        .sortedBy { it.checkIn }
    val others = (usable - current.toSet()).sortedBy { it.checkIn }
    return current.map { it to "${name(it)} (heute)" } + others.map { s ->
        s to name(s) + (s.checkIn.takeIf { it.isNotBlank() }?.let { " · ab " + dayLabel(it) } ?: "")
    }
}

/** What "Tag bearbeiten" changed: [title] null = untouched; [regionId] only counts when [regionChanged] (null = "Automatisch"). */
internal data class DayEditChanges(val title: String?, val regionChanged: Boolean, val regionId: String?)

/**
 * Content of "Tag bearbeiten": title field and region dropdown. [onSave] gets only the fields the user changed, compared
 * with the values when the sheet opened, so a sync arriving meanwhile is never overwritten with stale values.
 */
@Composable
internal fun DayEditForm(
    initialTitle: String,
    initialRegion: String?,
    regionOptions: List<Pair<String?, String>>,
    onSave: (DayEditChanges) -> Unit,
) {
    // Kept from the first composition: later recompositions may pass newer (synced) initial values.
    val openedTitle = rememberSaveable { initialTitle }
    val openedRegion = rememberSaveable { mutableStateOf(initialRegion) }.value
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
        val save = {
            onSave(DayEditChanges(title.takeIf { it != openedTitle }, regionId != openedRegion, regionId))
        }
        Button(onClick = save, modifier = Modifier.align(Alignment.End).testTag("day-edit-save")) {
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
