package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.SavedPlace
import de.wanderlogplus.shared.model.TabelogStatus
import de.wanderlogplus.shared.sync.Worth
import java.util.Locale

/** Where a value in the rating line comes from: each has its own symbol. */
enum class RatingKind { GOOGLE, TABELOG, AI }

data class RatingItem(val kind: RatingKind, val text: String)

/** Short form of the assistant's judgement for the rating line. */
private fun worthShort(w: Worth) = when (w) {
    Worth.MUST -> "Muss"
    Worth.WORTH -> "Lohnt"
    Worth.OPTIONAL -> "Wenn Zeit"
    Worth.SKIP -> "Auslassen"
}

/** Google rating (with count), Tabelog score (only when found) and the assistant's judgement, as far as known. */
fun ratingItems(place: Place?, saved: SavedPlace): List<RatingItem> = buildList {
    place?.rating?.let { r ->
        val count = place.numRatings?.let { " (" + String.format(Locale.GERMAN, "%,d", it) + ")" }.orEmpty()
        add(RatingItem(RatingKind.GOOGLE, String.format(Locale.GERMAN, "%.1f", r) + count))
    }
    place?.tabelog?.takeIf { it.status == TabelogStatus.FOUND }?.score?.let { add(RatingItem(RatingKind.TABELOG, String.format(Locale.GERMAN, "%.2f", it))) }
    saved.worth?.let { add(RatingItem(RatingKind.AI, worthShort(it))) }
}

/**
 * One quiet line under a place's name: ★ Google, 🍴 Tabelog, ✦ assistant – small grey symbols, only what is known.
 * Nothing at all when there is nothing. Tag `rating-strip`, each value `rating-google|tabelog|ai`.
 */
@Composable
fun RatingStrip(place: Place?, saved: SavedPlace, modifier: Modifier = Modifier) {
    val items = ratingItems(place, saved)
    if (items.isEmpty()) return
    val tint = MaterialTheme.colorScheme.onSurfaceVariant
    Row(modifier.testTag("rating-strip"), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        items.forEach { item ->
            Row(Modifier.testTag("rating-" + item.kind.name.lowercase()), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.CenterVertically) {
                val (icon, label) = when (item.kind) {
                    RatingKind.GOOGLE -> Icons.Filled.Star to "Google-Bewertung"
                    RatingKind.TABELOG -> Icons.Filled.Restaurant to "Tabelog-Bewertung"
                    RatingKind.AI -> Icons.Filled.AutoAwesome to "KI-Einschätzung"
                }
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(14.dp))
                Text(item.text, style = MaterialTheme.typography.bodySmall, color = tint, maxLines = 1)
            }
        }
    }
}

/**
 * The place's status as one small chip ("● Idee ▾"); a tap opens the five statuses. Replaces the row of five
 * buttons: it is changed rarely. Tags `status-chip`, `status-option-<STATUS>`.
 */
@Composable
fun StatusChip(status: de.wanderlogplus.shared.model.PlaceStatus, onSet: (de.wanderlogplus.shared.model.PlaceStatus) -> Unit) {
    var open by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        androidx.compose.material3.AssistChip(
            onClick = { open = true },
            label = { Text(de.wanderlogplus.app.ui.statusLabel(status)) },
            leadingIcon = {
                androidx.compose.foundation.layout.Box(
                    Modifier.size(10.dp).androidxBackground(statusColor(status)),
                )
            },
            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(18.dp)) },
            modifier = Modifier.testTag("status-chip"),
        )
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            de.wanderlogplus.shared.model.PlaceStatus.entries.forEach { s ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(de.wanderlogplus.app.ui.statusLabel(s)) },
                    leadingIcon = { androidx.compose.foundation.layout.Box(Modifier.size(10.dp).androidxBackground(statusColor(s))) },
                    onClick = { open = false; onSet(s) },
                    modifier = Modifier.testTag("status-option-${s.name}"),
                )
            }
        }
    }
}

private fun Modifier.androidxBackground(c: androidx.compose.ui.graphics.Color) =
    this.then(Modifier.background(c, androidx.compose.foundation.shape.CircleShape))
