package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import de.wanderlogplus.app.ui.design.PhotoOrGradient
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.heroGradient
import de.wanderlogplus.app.ui.design.regionHeroImage
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Region
import de.wanderlogplus.shared.model.SavedPlace

/** Routes of the Orte tab (nested: region list -> region places -> place detail). */
const val REGION_ROUTE = "orte/region/{regionKey}"
const val PLACE_ROUTE = "orte/place/{savedPlaceId}"
fun regionRoute(regionKey: String) = "orte/region/${android.net.Uri.encode(regionKey)}"
fun placeRoute(savedPlaceId: String) = "orte/place/${android.net.Uri.encode(savedPlaceId)}"

/** Pseudo region key for saved places without (a known) region. */
const val NO_REGION = "none"

/** Saved places of the region with [regionKey]; [NO_REGION] gives those without a (known) region. */
fun savedPlacesIn(snapshot: TripSnapshot, regionKey: String): List<SavedPlace> {
    val known = snapshot.regions.map { it.id }.toSet()
    return snapshot.savedPlaces.filter { if (regionKey == NO_REGION) it.regionId !in known else it.regionId == regionKey }
}

/** "1 Ort" / "n Orte". */
internal fun placeCountLabel(count: Int) = if (count == 1) "1 Ort" else "$count Orte"

/** True when [region] is a day trip from another known region. */
internal fun isDayTrip(snapshot: TripSnapshot, region: Region): Boolean =
    region.dayTripFromRegionId?.let { main -> main != region.id && snapshot.regions.any { it.id == main } } == true

/**
 * Regions in tile order: every main region (snapshot order) directly followed by its day-trip regions, recursively
 * (a day trip from a day trip follows that one). Day trips whose main region is unknown count as main regions.
 * Every region appears exactly once: regions not reached that way (a cycle of day trips) are appended at the end.
 */
internal fun regionTileOrder(snapshot: TripSnapshot): List<Region> {
    val dayTrips = snapshot.regions.filter { isDayTrip(snapshot, it) }.groupBy { it.dayTripFromRegionId }
    val seen = mutableSetOf<String>()
    val order = mutableListOf<Region>()
    fun add(r: Region) {
        if (!seen.add(r.id)) return
        order += r
        dayTrips[r.id].orEmpty().forEach(::add)
    }
    snapshot.regions.filterNot { isDayTrip(snapshot, it) }.forEach(::add)
    snapshot.regions.forEach(::add) // leftovers, e.g. day trips that point at each other
    return order
}

private data class RegionTile(val key: String, val name: String, val count: Int, val dayTrip: Boolean, val accent: Color, val imageUrl: String?)

private val NO_REGION_ACCENT = Color(0xFF8D7F76)

/** Orte: regions as a 2-column grid of photo tiles (best-rated place, else region-colored gradient) with counts. */
@Composable
fun PlacesScreen(snapshot: TripSnapshot, onOpenRegion: (regionKey: String) -> Unit, modifier: Modifier = Modifier) {
    val tiles = remember(snapshot) {
        regionTileOrder(snapshot).map { r ->
            RegionTile(r.id, r.name, savedPlacesIn(snapshot, r.id).size, isDayTrip(snapshot, r), regionColor(r.color), regionHeroImage(snapshot, r.id))
        } + listOfNotNull(
            // "Ohne Region" only when something is in it.
            savedPlacesIn(snapshot, NO_REGION).size.takeIf { it > 0 }
                ?.let { RegionTile(NO_REGION, "Ohne Region", it, false, NO_REGION_ACCENT, null) },
        )
    }
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        modifier = modifier.fillMaxSize().testTag("regions-list"),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item(key = "label", span = { GridItemSpan(maxLineSpan) }) { SectionLabel("Regionen") }
        items(tiles, key = { it.key }) { RegionTileCard(it, onOpenRegion) }
    }
}

private val TileScrim = Brush.verticalGradient(0f to Color.Transparent, 0.35f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))

@Composable
private fun RegionTileCard(tile: RegionTile, onOpen: (String) -> Unit) {
    Card(
        onClick = { onOpen(tile.key) },
        modifier = Modifier.fillMaxWidth().height(140.dp).testTag("region-${tile.key}"),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            PhotoOrGradient(tile.imageUrl, tile.accent, "tile-fallback", Modifier.matchParentSize(), gradient = heroGradient(tile.accent))
            Box(Modifier.matchParentSize().background(TileScrim))
            // Region color as a slim stripe on top, also over photos.
            Box(Modifier.fillMaxWidth().height(4.dp).background(tile.accent).align(Alignment.TopStart))
            Column(Modifier.align(Alignment.BottomStart).padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                if (tile.dayTrip) {
                    Text(
                        "Tagesausflug",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White.copy(alpha = 0.88f),
                        maxLines = 1,
                    )
                }
                Text(
                    tile.name,
                    fontFamily = FontFamily.Serif,
                    fontSize = 20.sp,
                    lineHeight = 23.sp,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    placeCountLabel(tile.count),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.88f),
                    maxLines = 1,
                )
            }
        }
    }
}
