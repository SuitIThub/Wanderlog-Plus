package de.wanderlogplus.app.ui.places

import de.wanderlogplus.app.ui.logistics.toDraft
import de.wanderlogplus.app.ui.logistics.toWindow

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Directions
import androidx.compose.material.icons.filled.DirectionsTransit
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryScrollableTabRow
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
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.ConfirmationNumber
import androidx.compose.material.icons.filled.EventAvailable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.links.GoogleExternalLinks
import de.wanderlogplus.app.links.LinkKind
import de.wanderlogplus.app.ui.LabeledDropdown
import de.wanderlogplus.app.ui.MarkdownText
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.app.ui.attachments.AttachmentsFor
import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.app.ui.design.ActionCircle
import de.wanderlogplus.app.ui.design.Badge
import de.wanderlogplus.app.ui.design.EditSheet
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.HeroHeader
import de.wanderlogplus.app.ui.design.ScreenTab
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.heroImageUrl
import de.wanderlogplus.app.ui.design.heroImage
import de.wanderlogplus.app.ui.ratingLabel
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.app.ui.statusLabel
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.sync.EntityType
import java.util.Locale

private const val TAG_STAR = "⭐"
private const val TAG_WARN = "⚠️"
private const val NO_REGION_LABEL = "Keine Region"

private const val TAB_INFO = 0
private const val TAB_ASSESS = 1
private const val TAB_RESEARCH = 2
private const val TAB_PHOTOS = 3
private const val TAB_NOTE = 4
private const val TAB_FILES = 5

/**
 * Switches the marker [emoji] in [tags]: removes every tag starting with it (e.g. "⭐ Muss man sehen"), or appends the
 * bare emoji when absent. All other tags keep their order.
 */
internal fun toggleTag(tags: List<String>, emoji: String): List<String> =
    if (tags.any { it.startsWith(emoji) }) tags.filterNot { it.startsWith(emoji) } else tags + emoji

/** "★ 4.5 (1.234)"; null without rating. */
internal fun ratingText(place: Place): String? {
    val r = place.rating ?: return null
    val count = place.numRatings?.let { " (" + String.format(Locale.GERMAN, "%,d", it) + ")" }.orEmpty()
    return ratingLabel(r) + count
}

internal fun durationText(place: Place): String? {
    val lo = place.minMinutes
    val hi = place.maxMinutes
    return when {
        lo != null && hi != null && lo != hi -> "$lo–$hi Min."
        lo != null || hi != null -> "${lo ?: hi} Min."
        else -> null
    }
}

internal fun linkIcon(kind: LinkKind): ImageVector = when (kind) {
    LinkKind.NAVIGATE -> Icons.Filled.Directions
    LinkKind.TRANSIT -> Icons.Filled.DirectionsTransit
    LinkKind.WEBSITE -> Icons.Filled.Language
    LinkKind.PHONE -> Icons.Filled.Call
    LinkKind.TABELOG -> Icons.Filled.Restaurant
    LinkKind.CUSTOM -> Icons.Filled.Link
}

/**
 * Place detail: photo hero (✎ opens "Ort bearbeiten" for tags, region and category), rating/category/tags, status
 * chips, link action circles, then the tabs Info, Einschätzung, Recherche, Notiz and Anhänge. The whole page scrolls.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun PlaceDetailScreen(
    snapshot: TripSnapshot,
    savedPlaceId: String,
    /** Null hides the back button (detail pane beside its list). */
    onBack: (() -> Unit)?,
    onSaveNote: (savedPlaceId: String, note: String) -> Unit,
    onSetStatus: (savedPlaceId: String, status: PlaceStatus) -> Unit,
    modifier: Modifier = Modifier,
    /** Increases whenever a save failed; the optimistic note is dropped then. */
    failureCount: Int = 0,
    attachments: AttachmentActions? = null,
    /** Null hides the link buttons. */
    onOpenLink: ((ExternalLink) -> Unit)? = null,
    onSetTags: (savedPlaceId: String, tags: List<String>) -> Unit = { _, _ -> },
    /** Null regionId = "Keine Region". */
    onSetRegion: (savedPlaceId: String, regionId: String?) -> Unit = { _, _ -> },
    onSetCategory: (savedPlaceId: String, category: PlaceCategory) -> Unit = { _, _ -> },
    /** ISO date whose weekday is highlighted in the opening hours; null highlights none. */
    today: String? = null,
    /** "Recherchieren (KI)" in the Einschätzung/Recherche tabs; null hides the button. */
    research: ResearchAction? = null,
    /** Opens the full-size map of the place; null hides the embedded map. */
    onOpenMap: (() -> Unit)? = null,
    /** Opens photo [index] of the gallery full screen; null: photos are only shown. */
    onOpenPhoto: ((EntityType, String, Int) -> Unit)? = null,
    /** "Reserviert" for a place that needs a reservation. */
    onSetReserved: (savedPlaceId: String, reserved: Boolean) -> Unit = { _, _ -> },
    /** "Fristen ändern" in the reservation card; null hides it. */
    onSetReservationWindow: ((savedPlaceId: String, window: de.wanderlogplus.shared.model.ReservationWindow?) -> Unit)? = null,
    /** Own header image (null: default); null callback hides "Titelbild ändern". */
    onSetHeaderImage: ((savedPlaceId: String, ref: String?) -> Unit)? = null,
    /** Own links; null hides "Link". */
    onSetLinks: ((savedPlaceId: String, links: List<de.wanderlogplus.shared.model.CustomLink>) -> Unit)? = null,
    /** "Ort löschen" in "Ort bearbeiten" (after a confirmation); null hides it. */
    onDelete: ((savedPlaceId: String) -> Unit)? = null,
) {
    val saved = snapshot.savedPlaces.firstOrNull { it.id == savedPlaceId }
    val place = saved?.let { snapshot.places[it.placeId] }
    val region = remember(snapshot, saved?.regionId) { saved?.regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id } } }
    val accent = region?.let { regionColor(it.color) } ?: MaterialTheme.colorScheme.primary

    var tab by rememberSaveable(savedPlaceId) { mutableIntStateOf(TAB_INFO) }
    var editingTags by rememberSaveable(savedPlaceId) { mutableStateOf(false) }
    var deleting by rememberSaveable(savedPlaceId) { mutableStateOf(false) }
    var choosingImage by rememberSaveable(savedPlaceId) { mutableStateOf(false) }
    var editing by rememberSaveable(savedPlaceId) { mutableStateOf(false) }
    var draft by rememberSaveable(savedPlaceId) { mutableStateOf("") }
    // Shown right after saving, until the snapshot carries the new note.
    var pendingNote by remember(savedPlaceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(saved?.note, failureCount) { pendingNote = null }

    if (saved == null) {
        Column(modifier.fillMaxSize()) {
            HeroHeader("Ort nicht gefunden", kicker = null, imageUrl = null, accent = accent, height = 140.dp, onBack = onBack, titleMaxLines = 1)
            EmptyHint("Dieser Ort existiert nicht mehr.", Modifier.padding(16.dp))
        }
        return
    }

    val fileCount = snapshot.attachmentsFor(EntityType.SAVED_PLACE, saved.id).size

    // The one-line hero cuts long names; the full name then opens the details below it.
    var titleCut by remember(saved.id) { mutableStateOf(false) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("place-detail")) {
        HeroHeader(
            title = place?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort",
            kicker = region?.name ?: NO_REGION_LABEL,
            imageUrl = saved.heroImage(place),
            accent = accent,
            height = 140.dp,
            onBack = onBack,
            onEdit = { editingTags = true },
            onChangeImage = onSetHeaderImage?.let { { choosingImage = true } },
            titleMaxLines = 1,
            onTitleOverflow = { titleCut = it },
        )

        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            place?.name?.takeIf { titleCut && it.isNotBlank() }?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.testTag("place-full-name"),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                place?.let(::ratingText)?.let { Text(it, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold) }
                Text(
                    categoryLabel(saved.category),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (saved.tags.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    saved.tags.forEach { Badge(tagBadge(it)) }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.testTag("status-chips")) {
                PlaceStatus.entries.forEach { s ->
                    FilterChip(
                        selected = saved.status == s,
                        onClick = { if (saved.status != s) onSetStatus(saved.id, s) },
                        label = { Text(statusLabel(s)) },
                    )
                }
            }
            val reason = remember(saved) { de.wanderlogplus.shared.query.Reservations.reason(saved) }
            val visit = remember(snapshot, saved, today) {
                de.wanderlogplus.shared.query.ReservationDates.visitDate(
                    saved, snapshot.days, snapshot.dayItems, snapshot.queries()?.days().orEmpty(), today ?: java.time.LocalDate.now().toString(),
                )
            }
            val window = remember(saved, visit) { de.wanderlogplus.shared.query.ReservationDates.windowFor(saved, visit) }
            val estimated = visit?.second == true && saved.reservationWindow == null
            if (reason != null || saved.reserved || window != null) {
                ReservationRow(
                    reason, saved.reserved, window?.let { de.wanderlogplus.app.ui.logistics.reservationText(it, estimated) },
                    onEditWindow = onSetReservationWindow?.let { set -> { set(saved.id, it) } }, current = saved.reservationWindow ?: window,
                ) { onSetReserved(saved.id, it) }
            }
        }

        val builtIn = remember(place, saved.category) { place?.let { GoogleExternalLinks.linksFor(it, saved.category) }.orEmpty() }
        de.wanderlogplus.app.ui.design.LinkRow(builtIn, saved.links, onOpenLink, onSetLinks?.let { set -> { l: List<de.wanderlogplus.shared.model.CustomLink> -> set(saved.id, l) } })

        if (onOpenMap != null) {
            val points = remember(place, saved.dossier) { placeMapPoints(place, saved.dossier) }
            PlaceMapCard(points, accent, onOpenMap, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp))
        }

        de.wanderlogplus.app.ui.guides.LocalOpenGuide.current?.let { open ->
            de.wanderlogplus.app.ui.guides.GuidesSection(
                snapshot, de.wanderlogplus.shared.model.GuideTarget(de.wanderlogplus.shared.model.GuideTargetType.PLACE, saved.id), open,
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        de.wanderlogplus.app.ui.budget.CostsSection(
            snapshot, saved.id, today ?: java.time.LocalDate.now().toString(), accent,
            Modifier.padding(horizontal = 16.dp),
        )

        PrimaryScrollableTabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 4.dp), edgePadding = 8.dp) {
            ScreenTab("Info", tab == TAB_INFO, "tab-info") { tab = TAB_INFO }
            ScreenTab("Einschätzung", tab == TAB_ASSESS, "tab-assessment") { tab = TAB_ASSESS }
            ScreenTab("Recherche", tab == TAB_RESEARCH, "tab-research") { tab = TAB_RESEARCH }
            // Always shown: the tab positions behind it must not shift.
            val photoCount = snapshot.photosFor(EntityType.SAVED_PLACE, saved.id).size
            ScreenTab(if (photoCount > 0) "Fotos ($photoCount)" else "Fotos", tab == TAB_PHOTOS, "tab-photos") { tab = TAB_PHOTOS }
            ScreenTab("Notiz", tab == TAB_NOTE, "tab-note") { tab = TAB_NOTE }
            ScreenTab(if (fileCount > 0) "Anhänge ($fileCount)" else "Anhänge", tab == TAB_FILES, "tab-files") { tab = TAB_FILES }
        }

        when (tab) {
            TAB_INFO -> PlaceInfoTab(place, accent, today, saved)
            TAB_ASSESS -> PlaceAssessmentTab(saved, accent, research)
            TAB_RESEARCH -> PlaceResearchTab(saved, accent, research)
            TAB_PHOTOS -> Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PlacePhotosRow(place)
                Text("Eigene Fotos", style = MaterialTheme.typography.titleSmall)
                de.wanderlogplus.app.ui.attachments.PhotoGrid(
                    snapshot.photosFor(EntityType.SAVED_PLACE, saved.id),
                    attachments?.files,
                    onAdd = attachments?.onAddPhotos?.let { add -> { add(EntityType.SAVED_PLACE, saved.id) } },
                    onOpen = { i -> onOpenPhoto?.invoke(EntityType.SAVED_PLACE, saved.id, i) },
                )
            }
            TAB_NOTE -> PlaceNoteTab(
                note = pendingNote ?: saved.note,
                accent = accent,
                editing = editing,
                draft = draft,
                onDraft = { draft = it },
                onStartEdit = { draft = pendingNote ?: saved.note; editing = true },
                onSave = { pendingNote = draft; onSaveNote(saved.id, draft); editing = false },
                onCancel = { editing = false },
            )
            else -> Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
                SectionLabel("Anhänge", color = accent)
                if (attachments == null) {
                    EmptyHint("Anhänge sind hier nicht verfügbar", Modifier.padding(top = 6.dp))
                } else {
                    AttachmentsFor(
                        snapshot.attachmentsFor(EntityType.SAVED_PLACE, saved.id),
                        attachments,
                        onAdd = { attachments.onAdd(EntityType.SAVED_PLACE, saved.id) },
                        showTitle = false, // "Anhänge" label above
                    )
                }
            }
        }
    }

    if (choosingImage && onSetHeaderImage != null) {
        HeaderImagePicker(
            place, snapshot.photosFor(EntityType.SAVED_PLACE, saved.id), attachments?.files, saved.headerImage,
            onPick = { ref -> onSetHeaderImage(saved.id, ref); choosingImage = false },
            onUpload = attachments?.onAddPhotos?.let { add -> { add(EntityType.SAVED_PLACE, saved.id) } },
            onDismiss = { choosingImage = false },
        )
    }
    if (editingTags) {
        EditSheet("Ort bearbeiten", onDismiss = { editingTags = false }) {
            PlaceEditForm(
                snapshot, saved, onSetTags, onSetRegion, onSetCategory, onDone = { editingTags = false },
                onDelete = onDelete?.let { { editingTags = false; deleting = true } },
            )
        }
    }
    if (deleting && onDelete != null) {
        val name = place?.name?.takeIf { it.isNotBlank() } ?: "Ort"
        val days = remember(snapshot, saved.id) { programDaysOf(snapshot, saved) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("„$name“ löschen?") },
            text = {
                Text(
                    "Der Ort verschwindet aus der Sammlung" +
                        (if (days > 0) " und aus dem Programm von ${if (days == 1) "einem Tag" else "$days Tagen"}." else ".") +
                        " Notiz, Recherche und eigene Fotos des Orts gehen dabei verloren.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = { deleting = false; onDelete(saved.id) },
                    modifier = Modifier.testTag("place-delete-confirm"),
                ) { Text("Löschen", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Abbrechen") } },
        )
    }
}

/** Notiz tab: the place note (Markdown), edited inline. */
@Composable
private fun PlaceNoteTab(
    note: String,
    accent: Color,
    editing: Boolean,
    draft: String,
    onDraft: (String) -> Unit,
    onStartEdit: () -> Unit,
    onSave: () -> Unit,
    onCancel: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) {
        SectionLabel("Notiz", color = accent)
        Surface(
            Modifier.fillMaxWidth().padding(top = 6.dp),
            shape = RoundedCornerShape(18.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 1.dp,
        ) {
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                if (editing) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = onDraft,
                        modifier = Modifier.fillMaxWidth().padding(end = 8.dp).testTag("note-field"),
                        minLines = 3,
                    )
                    Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onCancel) { Text("Abbrechen") }
                        Button(onClick = onSave) { Text("Speichern") }
                    }
                } else {
                    if (note.isNotBlank()) {
                        MarkdownText(note, Modifier.padding(end = 8.dp).testTag("place-note"))
                    } else {
                        Text("Keine Notiz", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = onStartEdit, modifier = Modifier.align(Alignment.End)) { Text("Bearbeiten") }
                }
            }
        }
    }
}

/** Content of "Ort bearbeiten": ⭐/⚠️ toggles, region and category; every change is written right away. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlaceEditForm(
    snapshot: TripSnapshot,
    saved: SavedPlace,
    onSetTags: (savedPlaceId: String, tags: List<String>) -> Unit,
    onSetRegion: (savedPlaceId: String, regionId: String?) -> Unit,
    onSetCategory: (savedPlaceId: String, category: PlaceCategory) -> Unit,
    onDone: () -> Unit,
    onDelete: (() -> Unit)? = null,
) {
    SectionLabel("Markierungen")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple(TAG_STAR, "tag-star", "Highlight"), Triple(TAG_WARN, "tag-warn", "Achtung")).forEach { (emoji, tag, label) ->
            FilterChip(
                selected = saved.tags.any { it.startsWith(emoji) },
                onClick = { onSetTags(saved.id, toggleTag(saved.tags, emoji)) },
                label = { Text("$emoji $label") },
                modifier = Modifier.testTag(tag),
            )
        }
    }
    val regionName = saved.regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id }?.name ?: id } ?: NO_REGION_LABEL
    LabeledDropdown(
        tag = "place-region-dropdown",
        label = "Region",
        value = regionName,
        options = listOf<Pair<String?, String>>(null to NO_REGION_LABEL) + snapshot.regions.map { it.id to it.name },
        onSelect = { onSetRegion(saved.id, it) },
        modifier = Modifier.fillMaxWidth(),
    )
    LabeledDropdown(
        tag = "category-dropdown",
        label = "Kategorie",
        value = categoryLabel(saved.category),
        options = PlaceCategory.entries.map { it to categoryLabel(it) },
        onSelect = { onSetCategory(saved.id, it) },
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = onDone, modifier = Modifier.fillMaxWidth().testTag("place-edit-done")) { Text("Fertig") }
    if (onDelete != null) {
        TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().testTag("place-delete")) {
            Text("Ort löschen", color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Days whose program has this place (0 when another saved place of the same place keeps them). */
internal fun programDaysOf(snapshot: TripSnapshot, saved: SavedPlace): Int {
    if (snapshot.savedPlaces.any { it.id != saved.id && it.placeId == saved.placeId }) return 0
    return snapshot.dayItems.filter { it.kind == de.wanderlogplus.shared.model.DayItemKind.PLACE && it.placeId == saved.placeId }.map { it.dayId }.distinct().size
}

/** "Reservierung nötig" with the reason and the "Reserviert" switch. */
@Composable
private fun ReservationRow(
    reason: String?,
    reserved: Boolean,
    windowText: String?,
    onEditWindow: ((de.wanderlogplus.shared.model.ReservationWindow?) -> Unit)?,
    current: de.wanderlogplus.shared.model.ReservationWindow?,
    onReserved: (Boolean) -> Unit,
) {
    var editing by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = if (reserved) scheme.secondaryContainer else scheme.tertiaryContainer,
        contentColor = if (reserved) scheme.onSecondaryContainer else scheme.onTertiaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("place-reservation"),
    ) {
        Row(Modifier.padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.material3.Icon(
                if (reserved) androidx.compose.material.icons.Icons.Filled.EventAvailable else androidx.compose.material.icons.Icons.Filled.ConfirmationNumber,
                contentDescription = null,
                modifier = Modifier.padding(end = 10.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(if (reserved) "Reserviert" else "Reservierung nötig", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                reason?.let { Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis) }
                windowText?.let { Text(it, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold) }
                if (onEditWindow != null) {
                    androidx.compose.material3.TextButton(onClick = { editing = true }, modifier = Modifier.testTag("place-reservation-edit")) { Text("Fristen ändern") }
                }
            }
            androidx.compose.material3.Switch(checked = reserved, onCheckedChange = onReserved, modifier = Modifier.testTag("place-reserved-toggle"))
        }
    }
    if (editing && onEditWindow != null) {
        var draft by remember { mutableStateOf(current.toDraft()) }
        val problem = de.wanderlogplus.app.ui.logistics.validateReservation(draft)
        EditSheet("Reservierungsfristen", onDismiss = { editing = false }) {
            de.wanderlogplus.app.ui.logistics.ReservationFields(draft, showDone = false, onChange = { draft = it })
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                androidx.compose.material3.TextButton(onClick = { editing = false }) { Text("Abbrechen") }
                androidx.compose.material3.Button(
                    onClick = { onEditWindow(draft.toWindow(current)); editing = false },
                    enabled = problem == null,
                    modifier = Modifier.testTag("place-reservation-save"),
                ) { Text("Speichern") }
            }
        }
    }
}
