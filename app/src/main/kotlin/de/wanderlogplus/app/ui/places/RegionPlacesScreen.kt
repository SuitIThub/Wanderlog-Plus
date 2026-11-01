package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.maps.markerColor
import de.wanderlogplus.app.ui.categoryLabel
import de.wanderlogplus.app.ui.categorySymbol
import de.wanderlogplus.app.ui.design.BadgeSpec
import de.wanderlogplus.app.ui.design.BadgeTone
import de.wanderlogplus.app.ui.design.EmptyHint
import de.wanderlogplus.app.ui.design.HeroHeader
import de.wanderlogplus.app.ui.design.PhotoCard
import de.wanderlogplus.app.ui.design.PhotoCardStyle
import de.wanderlogplus.app.ui.design.SectionLabel
import de.wanderlogplus.app.ui.design.heroImageUrl
import de.wanderlogplus.app.ui.design.regionHeroImage
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.app.ui.statusLabel
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceCategory
import de.wanderlogplus.shared.model.PlaceStatus
import de.wanderlogplus.shared.query.Suggestion
import de.wanderlogplus.shared.query.TagRank
import de.wanderlogplus.shared.query.sortSuggestions
import de.wanderlogplus.shared.query.tagRank

/** Status dot color; the same colors as the map markers. */
internal fun statusColor(status: PlaceStatus): Color = regionColor(markerColor(status))

/** Badge tone of a tag by its marker (⭐ accent, ⚠️ warning, others neutral). */
internal fun tagBadge(tag: String) = BadgeSpec(
    tag,
    when (tagRank(listOf(tag))) { TagRank.STAR -> BadgeTone.Accent; TagRank.WARNING -> BadgeTone.Warning; else -> BadgeTone.Neutral },
)

/**
 * A region's places: hero header, category filter chips, "Liste | Karte" toggle; the list groups compact photo cards
 * by category ("Alle") or shows the chosen category only. The map shows the same filtered selection.
 */
@Composable
fun RegionPlacesScreen(
    snapshot: TripSnapshot,
    regionKey: String,
    onBack: () -> Unit,
    onOpenPlace: (savedPlaceId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val region = remember(snapshot, regionKey) { snapshot.regions.firstOrNull { it.id == regionKey } }
    val title = if (regionKey == NO_REGION) "Ohne Region" else region?.name.orEmpty()
    val accent = region?.let { regionColor(it.color) } ?: MaterialTheme.colorScheme.primary
    val all = remember(snapshot, regionKey) { savedPlacesIn(snapshot, regionKey).map { Suggestion(it, snapshot.places[it.placeId]) } }
    val heroImage = remember(snapshot, regionKey) { if (regionKey == NO_REGION) null else regionHeroImage(snapshot, regionKey) }
    val dayTripFrom = remember(snapshot, region) {
        region?.takeIf { isDayTrip(snapshot, it) }?.dayTripFromRegionId?.let { id -> snapshot.regions.firstOrNull { it.id == id }?.name }
    }

    var category by rememberSaveable(regionKey) { mutableStateOf<PlaceCategory?>(null) }
    var showMap by rememberSaveable { mutableStateOf(false) }

    val groups = remember(all, category) {
        PlaceCategory.entries.filter { category == null || it == category }.mapNotNull { c ->
            sortSuggestions(all.filter { it.saved.category == c }).takeIf { it.isNotEmpty() }?.let { c to it }
        }
    }
    val markers = remember(snapshot, regionKey, category) {
        val categoryOf = all.associate { it.saved.id to it.saved.category }
        mapMarkers(snapshot, regionKey).filter { category == null || categoryOf[it.savedPlaceId] == category }
    }

    Column(modifier.fillMaxSize()) {
        HeroHeader(
            title = title,
            kicker = listOfNotNull(dayTripFrom?.let { "Tagesausflug ab $it" }, placeCountLabel(all.size)).joinToString(" · "),
            imageUrl = heroImage,
            accent = accent,
            height = 140.dp,
            onBack = onBack,
            titleMaxLines = 1,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (listOf<PlaceCategory?>(null) + PlaceCategory.entries).forEach { c ->
                FilterChip(
                    selected = category == c,
                    onClick = { category = c },
                    label = { Text(categoryLabel(c)) },
                    modifier = Modifier.testTag("filter-category-${c?.name ?: "ALL"}"),
                )
            }
        }
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            SegmentedButton(
                selected = !showMap,
                onClick = { showMap = false },
                shape = SegmentedButtonDefaults.itemShape(0, 2),
                modifier = Modifier.testTag("view-list"),
            ) { Text("Liste") }
            SegmentedButton(
                selected = showMap,
                onClick = { showMap = true },
                shape = SegmentedButtonDefaults.itemShape(1, 2),
                modifier = Modifier.testTag("view-map"),
            ) { Text("Karte") }
        }
        if (showMap) {
            RegionMap(markers, onOpenPlace, Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp))
            return@Column
        }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (groups.isEmpty()) {
                item(key = "empty") {
                    EmptyHint(if (category == null) "Keine Orte" else "Keine Orte in „${categoryLabel(category)}“", Modifier.padding(top = 8.dp))
                }
            }
            groups.forEach { (c, list) ->
                item(key = "category-${c.name}") { SectionLabel(categoryLabel(c), Modifier.testTag("category-${c.name}"), color = accent) }
                items(list, key = { it.saved.id }) { s -> PlaceCard(s, accent) { onOpenPlace(s.saved.id) } }
            }
        }
    }
}

@Composable
private fun PlaceCard(s: Suggestion, accent: Color, onClick: () -> Unit) {
    PhotoCard(
        title = s.place?.name?.takeIf { it.isNotBlank() } ?: "Unbekannter Ort",
        subtitle = s.place?.let(::ratingText),
        imageUrl = s.place?.heroImageUrl(),
        placeholderSymbol = categorySymbol(s.saved.category),
        accent = accent,
        style = PhotoCardStyle.Compact,
        badges = s.saved.tags.map(::tagBadge),
        onClick = onClick,
        trailing = { StatusIndicator(s.saved.id, s.saved.status) },
        modifier = Modifier.testTag("place-${s.saved.id}"),
    )
}

@Composable
private fun StatusIndicator(savedPlaceId: String, status: PlaceStatus) {
    Row(Modifier.padding(end = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(10.dp).background(statusColor(status), CircleShape).testTag("status-dot-$savedPlaceId"))
        Text(statusLabel(status), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
