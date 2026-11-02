package de.wanderlogplus.app.ui.places

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
private const val TAB_NOTE = 3
private const val TAB_FILES = 4

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
) {
    val saved = snapshot.savedPlaces.firstOrNull { it.id == savedPlaceId }
    val place = saved?.let { snapshot.places[it.placeId] }
    val region = remember(snapshot, saved?.regionId) { saved?.regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id } } }
    val accent = region?.let { regionColor(it.color) } ?: MaterialTheme.colorScheme.primary

    var tab by rememberSaveable(savedPlaceId) { mutableIntStateOf(TAB_INFO) }
    var editingTags by rememberSaveable(savedPlaceId) { mutableStateOf(false) }
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
            imageUrl = place?.heroImageUrl(),
            accent = accent,
            height = 140.dp,
            onBack = onBack,
            onEdit = { editingTags = true },
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
        }

        if (place != null && onOpenLink != null) {
            val links = remember(place, saved.category) { GoogleExternalLinks.linksFor(place, saved.category) }
            if (links.isNotEmpty()) {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    links.forEach { l -> ActionCircle(linkIcon(l.kind), l.label, { onOpenLink(l) }, "link-${l.kind}") }
                }
            }
        }

        if (onOpenMap != null) {
            val points = remember(place, saved.dossier) { placeMapPoints(place, saved.dossier) }
            PlaceMapCard(points, accent, onOpenMap, Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 4.dp))
        }

        PrimaryScrollableTabRow(selectedTabIndex = tab, modifier = Modifier.padding(top = 4.dp), edgePadding = 8.dp) {
            ScreenTab("Info", tab == TAB_INFO, "tab-info") { tab = TAB_INFO }
            ScreenTab("Einschätzung", tab == TAB_ASSESS, "tab-assessment") { tab = TAB_ASSESS }
            ScreenTab("Recherche", tab == TAB_RESEARCH, "tab-research") { tab = TAB_RESEARCH }
            ScreenTab("Notiz", tab == TAB_NOTE, "tab-note") { tab = TAB_NOTE }
            ScreenTab(if (fileCount > 0) "Anhänge ($fileCount)" else "Anhänge", tab == TAB_FILES, "tab-files") { tab = TAB_FILES }
        }

        when (tab) {
            TAB_INFO -> PlaceInfoTab(place, accent, today, saved)
            TAB_ASSESS -> PlaceAssessmentTab(saved, accent, research)
            TAB_RESEARCH -> PlaceResearchTab(saved, accent, research)
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

    if (editingTags) {
        EditSheet("Ort bearbeiten", onDismiss = { editingTags = false }) {
            PlaceEditForm(snapshot, saved, onSetTags, onSetRegion, onSetCategory, onDone = { editingTags = false })
        }
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
}
