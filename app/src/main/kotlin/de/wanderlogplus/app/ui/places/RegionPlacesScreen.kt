package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.app.ui.ratingLabel
import de.wanderlogplus.app.ui.statusLabel
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.query.Suggestion
import de.wanderlogplus.shared.query.sortSuggestions

@Composable
fun RegionPlacesScreen(
    snapshot: TripSnapshot,
    regionKey: String,
    onBack: () -> Unit,
    onOpenPlace: (savedPlaceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val title = if (regionKey == NO_REGION) "Ohne Region" else snapshot.regions.firstOrNull { it.id == regionKey }?.name.orEmpty()
    val groups = remember(snapshot, regionKey) {
        val all = savedPlacesIn(snapshot, regionKey).map { Suggestion(it, snapshot.places[it.placeId]) }
        PlaceCategory.entries.mapNotNull { c ->
            sortSuggestions(all.filter { it.saved.category == c }).takeIf { it.isNotEmpty() }?.let { c to it }
        }
    }
    Column(modifier.verticalScroll(rememberScrollState()).padding(bottom = 16.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Text(title, style = MaterialTheme.typography.titleLarge)
        }
        if (groups.isEmpty()) {
            Text("Keine Orte", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        groups.forEach { (category, list) ->
            Text(
                categoryLabel(category),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp).testTag("category-${category.name}"),
            )
            list.forEach { s ->
                Row(
                    Modifier.fillMaxWidth().clickable { onOpenPlace(s.saved.id) }.padding(horizontal = 16.dp, vertical = 10.dp)
                        .testTag("place-${s.saved.id}"),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(s.place?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort", style = MaterialTheme.typography.bodyLarge)
                        s.place?.rating?.let {
                            Text(
                                ratingLabel(it),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(statusLabel(s.saved.status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                }
                HorizontalDivider()
            }
        }
    }
}
