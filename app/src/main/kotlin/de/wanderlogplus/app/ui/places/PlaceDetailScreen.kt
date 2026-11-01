package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import de.wanderlogplus.app.links.ExternalLink
import de.wanderlogplus.app.links.GoogleExternalLinks
import de.wanderlogplus.app.ui.LinkButtons
import de.wanderlogplus.app.ui.MarkdownText
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.app.ui.attachments.AttachmentsFor
import de.wanderlogplus.shared.sync.EntityType
import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.app.ui.openingHoursLines
import de.wanderlogplus.app.ui.ratingLabel
import de.wanderlogplus.app.ui.statusLabel
import de.wanderlogplus.app.ui.today.Section
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceStatus
import java.util.Locale

private const val IMAGE_BASE = "https://itin-dev.wanderlogstatic.com/freeImageMedium/"

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

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaceDetailScreen(
    snapshot: TripSnapshot,
    savedPlaceId: String,
    onBack: () -> Unit,
    onSaveNote: (savedPlaceId: String, note: String) -> Unit,
    onSetStatus: (savedPlaceId: String, status: PlaceStatus) -> Unit,
    modifier: Modifier = Modifier,
    /** Increases whenever a save failed; the optimistic note is dropped then. */
    failureCount: Int = 0,
    attachments: AttachmentActions? = null,
    /** Null hides the link buttons. */
    onOpenLink: ((ExternalLink) -> Unit)? = null,
) {
    val saved = snapshot.savedPlaces.firstOrNull { it.id == savedPlaceId }
    val place = saved?.let { snapshot.places[it.placeId] }
    var editing by rememberSaveable(savedPlaceId) { mutableStateOf(false) }
    var draft by rememberSaveable(savedPlaceId) { mutableStateOf("") }
    // Shown right after saving, until the snapshot carries the new note.
    var pendingNote by remember(savedPlaceId) { mutableStateOf<String?>(null) }
    LaunchedEffect(saved?.note, failureCount) { pendingNote = null }

    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
        }
        if (saved == null) {
            Text("Ort nicht gefunden", Modifier.padding(16.dp))
            return@Column
        }

        place?.imageKeys?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { key ->
            var failed by remember(key) { mutableStateOf(false) }
            if (!failed) {
                AsyncImage(
                    model = IMAGE_BASE + key,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    onError = { failed = true },
                    modifier = Modifier.fillMaxWidth().height(200.dp).testTag("place-image"),
                )
            }
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(place?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort", style = MaterialTheme.typography.headlineSmall)
            place?.let(::ratingText)?.let { Text(it, style = MaterialTheme.typography.bodyLarge) }
            Text(categoryLabel(saved.category), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (saved.tags.isNotEmpty()) Text(saved.tags.joinToString(" · "), style = MaterialTheme.typography.bodyMedium)
            place?.address?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            place?.let(::durationText)?.let { Text("Dauer: $it", style = MaterialTheme.typography.bodyMedium) }
        }

        if (place != null && onOpenLink != null) {
            val links = remember(place, saved.category) { GoogleExternalLinks.linksFor(place, saved.category) }
            LinkButtons(links, onOpenLink, Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        place?.description?.takeIf { it.isNotBlank() }?.let { Section("Beschreibung") { Text(it, style = MaterialTheme.typography.bodyMedium) } }

        Section("Öffnungszeiten") {
            openingHoursLines(place?.openingPeriods.orEmpty()).forEach { (day, text) ->
                Row(Modifier.fillMaxWidth().testTag("hours-$day"), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(day, Modifier.padding(end = 4.dp), style = MaterialTheme.typography.labelLarge)
                    Text(text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        Section("Status") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PlaceStatus.entries.forEach { s ->
                    FilterChip(
                        selected = saved.status == s,
                        onClick = { if (saved.status != s) onSetStatus(saved.id, s) },
                        label = { Text(statusLabel(s)) },
                    )
                }
            }
        }

        Section("Notiz") {
            if (editing) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.fillMaxWidth().testTag("note-field"),
                    minLines = 3,
                )
                Row {
                    Button(onClick = { pendingNote = draft; onSaveNote(saved.id, draft); editing = false }) { Text("Speichern") }
                    TextButton(onClick = { editing = false }) { Text("Abbrechen") }
                }
            } else {
                val shownNote = pendingNote ?: saved.note
                if (shownNote.isNotBlank()) MarkdownText(shownNote, Modifier.testTag("place-note"))
                else Text("Keine Notiz", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { draft = pendingNote ?: saved.note; editing = true }) { Text("Bearbeiten") }
            }
        }

        AttachmentsFor(
            snapshot.attachmentsFor(EntityType.SAVED_PLACE, saved.id),
            attachments,
            onAdd = { attachments?.onAdd?.invoke(EntityType.SAVED_PLACE, saved.id) },
            modifier = Modifier.padding(horizontal = 16.dp),
        )
    }
}
