package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.app.ui.trip.RegionChip
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.SavedPlace

/** Routes of the Orte tab (nested: region list -> region places -> place detail). */
const val REGION_ROUTE = "orte/region/{regionKey}"
const val PLACE_ROUTE = "orte/place/{savedPlaceId}"
fun regionRoute(regionKey: String) = "orte/region/$regionKey"
fun placeRoute(savedPlaceId: String) = "orte/place/$savedPlaceId"

/** Pseudo region key for saved places without (a known) region. */
const val NO_REGION = "none"

/** Saved places of the region with [regionKey]; [NO_REGION] gives those without a (known) region. */
fun savedPlacesIn(snapshot: TripSnapshot, regionKey: String): List<SavedPlace> {
    val known = snapshot.regions.map { it.id }.toSet()
    return snapshot.savedPlaces.filter { if (regionKey == NO_REGION) it.regionId !in known else it.regionId == regionKey }
}

@Composable
fun PlacesScreen(snapshot: TripSnapshot, onOpenRegion: (regionKey: String) -> Unit, modifier: Modifier = Modifier) {
    val counts = remember(snapshot) {
        snapshot.regions.associate { it.id to savedPlacesIn(snapshot, it.id).size } + (NO_REGION to savedPlacesIn(snapshot, NO_REGION).size)
    }
    LazyColumn(modifier.fillMaxSize().testTag("regions-list")) {
        snapshot.regions.forEach { r ->
            item(key = r.id) { RegionRow(r.id, counts[r.id] ?: 0, onOpenRegion) { RegionChip(r.name, regionColor(r.color)) } }
        }
        item(key = NO_REGION) {
            RegionRow(NO_REGION, counts[NO_REGION] ?: 0, onOpenRegion) { Text("Ohne Region", style = MaterialTheme.typography.labelLarge) }
        }
    }
}

@Composable
private fun RegionRow(key: String, count: Int, onOpen: (String) -> Unit, label: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(key) }.padding(horizontal = 16.dp, vertical = 16.dp).testTag("region-$key"),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        label()
        Text(if (count == 1) "1 Ort" else "$count Orte", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
}
