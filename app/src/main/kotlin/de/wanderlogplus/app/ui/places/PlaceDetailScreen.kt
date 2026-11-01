package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import de.wanderlogplus.app.ui.MarkdownText
import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.app.ui.openingHoursLines
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
    return "★ " + String.format(Locale.ROOT, "%.1f", r) + count
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

@Composable
fun PlaceDetailScreen(
    snapshot: TripSnapshot,
    savedPlaceId: String,
    onBack: () -> Unit,
    onSaveNote: (savedPlaceId: String, note: String) -> Unit,
    onSetStatus: (savedPlaceId: String, status: PlaceStatus) -> Unit,
    modifier: Modifier = Modifier,
) {
    val saved = snapshot.savedPlaces.firstOrNull { it.id == savedPlaceId }
    val place = saved?.let { snapshot.places[it.placeId] }
    var editing by rememberSaveable(savedPlaceId) { mutableStateOf(false) }
    var draft by rememberSaveable(savedPlaceId) { mutableStateOf("") }

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
            val statuses = PlaceStatus.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                statuses.forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = saved.status == s,
                        onClick = { if (saved.status != s) onSetStatus(saved.id, s) },
                        shape = SegmentedButtonDefaults.itemShape(i, statuses.size),
                    ) { Text(statusLabel(s), maxLines = 1) }
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
                    Button(onClick = { onSaveNote(saved.id, draft); editing = false }) { Text("Speichern") }
                    TextButton(onClick = { editing = false }) { Text("Abbrechen") }
                }
            } else {
                if (saved.note.isNotBlank()) MarkdownText(saved.note, Modifier.testTag("place-note"))
                else Text("Keine Notiz", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { draft = saved.note; editing = true }) { Text("Bearbeiten") }
            }
        }
    }
}
