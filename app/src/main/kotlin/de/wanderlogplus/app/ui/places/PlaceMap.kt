package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.maps.LocalMapsEnabled
import de.wanderlogplus.app.maps.PlaceMapView
import de.wanderlogplus.app.ui.design.isWideLayout
import de.wanderlogplus.app.ui.regionColor
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.Place
import de.wanderlogplus.shared.model.PlaceDossier
import de.wanderlogplus.shared.model.PoiKinds

const val PLACE_MAP_ROUTE = "orte/place/{savedPlaceId}/karte"

fun placeMapRoute(savedPlaceId: String) = "orte/place/${android.net.Uri.encode(savedPlaceId)}/karte"

/** Id of the place itself among [PlaceMapPoint]s. */
const val PLACE_POINT_ID = "place"

/** One symbol on a place's map: the place itself ([kind] null, a pin) or a related spot from the research. */
data class PlaceMapPoint(val id: String, val name: String, val lat: Double, val lng: Double, val kind: String?, val note: String = "")

private fun hasCoords(lat: Double, lng: Double) = !(lat == 0.0 && lng == 0.0)

/** The place (when it has coordinates) first, then the research's related spots with coordinates. */
fun placeMapPoints(place: Place?, dossier: PlaceDossier?): List<PlaceMapPoint> = buildList {
    if (place != null && hasCoords(place.lat, place.lng)) {
        add(PlaceMapPoint(PLACE_POINT_ID, place.name.ifBlank { "Ort" }, place.lat, place.lng, null))
    }
    dossier?.pois.orEmpty().forEachIndexed { i, p ->
        if (hasCoords(p.lat, p.lng)) add(PlaceMapPoint("poi-$i", p.name, p.lat, p.lng, PoiKinds.normalize(p.kind), p.note))
    }
}

/** Symbol drawn in a related spot's badge (same on the map and in the list). */
fun poiSymbol(kind: String): String = when (kind) {
    PoiKinds.ENTRANCE -> "🚪"
    PoiKinds.VIEWPOINT -> "🔭"
    PoiKinds.HIDDEN -> "💎"
    PoiKinds.STATION -> "🚉"
    PoiKinds.FOOD -> "🍜"
    PoiKinds.PARKING -> "🅿"
    else -> "✦"
}

/** Ring colour of a related spot's badge (ARGB). */
fun poiColor(kind: String): Int = when (kind) {
    PoiKinds.ENTRANCE -> 0xFF6D4C41.toInt()
    PoiKinds.VIEWPOINT -> 0xFF00897B.toInt()
    PoiKinds.HIDDEN -> 0xFF8E24AA.toInt()
    PoiKinds.STATION -> 0xFF1E88E5.toInt()
    PoiKinds.FOOD -> 0xFFF4511E.toInt()
    PoiKinds.PARKING -> 0xFF546E7A.toInt()
    else -> 0xFF757575.toInt()
}

/**
 * Small, still map in the place overview: pin for the place, badges for related spots. A tap or the
 * fullscreen button opens [onOpen]. Hidden when nothing has coordinates.
 */
@Composable
fun PlaceMapCard(points: List<PlaceMapPoint>, accent: Color, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (points.isEmpty()) return
    val spots = points.count { it.kind != null }
    Box(
        modifier.fillMaxWidth().height(190.dp).clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .testTag("place-map-card"),
    ) {
        if (LocalMapsEnabled.current) {
            PlaceMapView(points, accent.toArgb(), Modifier.fillMaxSize(), interactive = false)
        } else {
            Icon(Icons.Filled.Place, contentDescription = null, tint = accent, modifier = Modifier.align(Alignment.Center).size(40.dp))
        }
        // Catches taps above the still map.
        Box(Modifier.fillMaxSize().clickable(onClickLabel = "Karte öffnen", onClick = onOpen))
        FilledTonalIconButton(onClick = onOpen, modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).testTag("place-map-fullscreen")) {
            Icon(Icons.Filled.Fullscreen, contentDescription = "Karte im Vollbild")
        }
        if (spots > 0) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                modifier = Modifier.align(Alignment.TopStart).padding(10.dp),
            ) {
                Text(
                    if (spots == 1) "+ 1 Punkt aus der Recherche" else "+ $spots Punkte aus der Recherche",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                )
            }
        }
    }
}

/**
 * Full-size map of a place with its related spots; the list (beside the map when unfolded, below it otherwise)
 * explains each symbol, a tap there or on the map centres it.
 */
@Composable
fun PlaceMapScreen(snapshot: TripSnapshot, savedPlaceId: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val saved = snapshot.savedPlaces.firstOrNull { it.id == savedPlaceId }
    val place = saved?.let { snapshot.places[it.placeId] }
    val region = saved?.regionId?.let { id -> snapshot.regions.firstOrNull { it.id == id } }
    val accent = region?.let { regionColor(it.color) } ?: MaterialTheme.colorScheme.primary
    val points = remember(place, saved?.dossier) { placeMapPoints(place, saved?.dossier) }
    var focus by rememberSaveable(savedPlaceId) { mutableStateOf<String?>(null) }
    val wide = isWideLayout()

    Column(modifier.fillMaxSize().testTag("place-map-screen")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("place-map-back")) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück")
            }
            Text(
                place?.name?.ifBlank { null } ?: "Karte",
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (points.isEmpty()) {
            Text("Keine Koordinaten für diesen Ort.", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        val map: @Composable (Modifier) -> Unit = { m ->
            Box(m.background(MaterialTheme.colorScheme.surfaceContainerHigh)) {
                if (LocalMapsEnabled.current) {
                    PlaceMapView(points, accent.toArgb(), Modifier.fillMaxSize(), focus = focus, onSelect = { focus = it })
                }
            }
        }
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                map(Modifier.weight(2f).fillMaxHeight())
                PointList(points, accent, focus, { focus = it }, Modifier.weight(1f).fillMaxHeight())
            }
        } else {
            map(Modifier.fillMaxWidth().weight(if (points.size > 1) 0.62f else 1f))
            if (points.size > 1) PointList(points, accent, focus, { focus = it }, Modifier.fillMaxWidth().weight(0.38f))
        }
    }
}

@Composable
private fun PointList(points: List<PlaceMapPoint>, accent: Color, focus: String?, onFocus: (String) -> Unit, modifier: Modifier) {
    LazyColumn(modifier.testTag("place-map-points")) {
        items(points, key = { it.id }) { p ->
            Row(
                Modifier.fillMaxWidth()
                    .background(if (p.id == focus) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                    .clickable { onFocus(p.id) }
                    .padding(horizontal = 16.dp, vertical = 10.dp)
                    .testTag("map-point-${p.id}"),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PointSymbol(p.kind, accent)
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        p.kind?.let(PoiKinds::label) ?: "Der Ort selbst",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (p.note.isNotBlank()) Text(p.note, style = MaterialTheme.typography.bodySmall)
                }
            }
            HorizontalDivider()
        }
    }
}

/** The list's version of a map symbol: pin icon for the place, ringed badge with the kind's symbol for spots. */
@Composable
private fun PointSymbol(kind: String?, accent: Color) {
    if (kind == null) {
        Icon(Icons.Filled.Place, contentDescription = null, tint = accent, modifier = Modifier.size(28.dp))
    } else {
        Box(
            Modifier.size(28.dp).background(Color.White, CircleShape).border(2.dp, Color(poiColor(kind)), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(poiSymbol(kind), style = MaterialTheme.typography.labelMedium)
        }
    }
}
