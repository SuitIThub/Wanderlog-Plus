package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WrongLocation
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.ui.LabeledDropdown
import de.wanderlogplus.app.ui.design.EditSheet
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.SavedPlace
import java.util.Locale

/** How far from its region's middle a place may be before the check lists it, in km. */
const val OUTLIER_KM = 50.0

/** A place far from its region: how far, and the region it likely belongs to (null: none near). */
data class RegionOutlier(val saved: SavedPlace, val place: Place, val km: Double, val suggestedRegionId: String?)

/** The middle of each region: median latitude and longitude of its places, so a few far ones do not pull it away. */
internal fun medianCentres(snapshot: TripSnapshot): Map<String, LatLngD> {
    fun median(v: List<Double>): Double = v.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 }
    return snapshot.savedPlaces.mapNotNull { s ->
        val p = snapshot.places[s.placeId] ?: return@mapNotNull null
        val r = s.regionId ?: return@mapNotNull null
        if (p.lat == 0.0 && p.lng == 0.0) null else r to LatLngD(p.lat, p.lng)
    }.groupBy({ it.first }, { it.second }).mapValues { (_, pts) -> LatLngD(median(pts.map { it.lat }), median(pts.map { it.lng })) }
}

/** Places of [regionKey] more than [OUTLIER_KM] from its middle, farthest first, with the nearest other region within reach. */
fun regionOutliers(snapshot: TripSnapshot, regionKey: String): List<RegionOutlier> {
    val centres = medianCentres(snapshot)
    val own = centres[regionKey] ?: return emptyList()
    return snapshot.savedPlaces.filter { it.regionId == regionKey }.mapNotNull { s ->
        val p = snapshot.places[s.placeId] ?: return@mapNotNull null
        if (p.lat == 0.0 && p.lng == 0.0) return@mapNotNull null
        val here = LatLngD(p.lat, p.lng)
        val km = distanceKm(own, here)
        if (km <= OUTLIER_KM) return@mapNotNull null
        val better = centres.filterKeys { it != regionKey }.mapValues { distanceKm(it.value, here) }
            .filterValues { it <= OUTLIER_KM }.minByOrNull { it.value }?.key
        RegionOutlier(s, p, km, better)
    }.sortedByDescending { it.km }
}

/**
 * "2 Orte liegen weit außerhalb von Osaka": a tap lists them with distance and a suggested region; "Verschieben" per
 * place calls [onSetRegion]. Nothing moves by itself. Nothing shows without such places. Tags `region-outliers`,
 * `outlier-move-<savedPlaceId>`.
 */
@Composable
fun RegionOutliersBanner(snapshot: TripSnapshot, regionKey: String, regionName: String, onSetRegion: (savedPlaceId: String, regionId: String?) -> Unit, modifier: Modifier = Modifier) {
    val outliers = remember(snapshot, regionKey) { regionOutliers(snapshot, regionKey) }
    if (outliers.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = modifier.fillMaxWidth().clickable { open = true }.testTag("region-outliers"),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Filled.WrongLocation, contentDescription = null)
            Text(
                (if (outliers.size == 1) "1 Ort liegt" else "${outliers.size} Orte liegen") + " weit außerhalb von $regionName – prüfen",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
    if (open) {
        val names = remember(snapshot) { snapshot.regions.associate { it.id to it.name } }
        val chosen = remember(outliers) { mutableStateMapOf<String, String?>().apply { outliers.forEach { put(it.saved.id, it.suggestedRegionId) } } }
        EditSheet("Weit außerhalb von $regionName", onDismiss = { open = false }) {
            Text("Diese Orte liegen mehr als ${OUTLIER_KM.toInt()} km von der Mitte der Region entfernt. Verschieben, falls sie woanders hingehören.", style = MaterialTheme.typography.bodySmall)
            outliers.forEach { o ->
                Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(o.place.name.ifBlank { "Unbekannter Ort" }, fontWeight = FontWeight.SemiBold)
                    Text(String.format(Locale.GERMAN, "%.0f km von der Mitte", o.km) + (o.suggestedRegionId?.let { " · näher an ${names[it]}" } ?: " · keine Region in der Nähe"), style = MaterialTheme.typography.bodySmall)
                    val options = listOf<Pair<String?, String>>(null to "Ohne Region") + snapshot.regions.filter { it.id != regionKey }.map { it.id to it.name }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LabeledDropdown(
                            tag = "outlier-region-${o.saved.id}",
                            label = "Nach",
                            value = options.firstOrNull { it.first == chosen[o.saved.id] }?.second ?: "Ohne Region",
                            options = options,
                            onSelect = { chosen[o.saved.id] = it },
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedButton(onClick = { onSetRegion(o.saved.id, chosen[o.saved.id]) }, modifier = Modifier.testTag("outlier-move-${o.saved.id}")) { Text("Verschieben") }
                    }
                }
            }
        }
    }
}
