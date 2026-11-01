package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import de.wanderlogplus.app.maps.LocalMapsEnabled
import de.wanderlogplus.app.maps.RegionMapView
import de.wanderlogplus.client.TripSnapshot
import de.wanderlogplus.shared.model.PlaceStatus

data class MapMarker(val savedPlaceId: String, val name: String, val lat: Double, val lng: Double, val status: PlaceStatus)

/** Same selection as the region's list ([savedPlacesIn]), without places lacking coordinates. */
fun mapMarkers(snapshot: TripSnapshot, regionKey: String): List<MapMarker> =
    savedPlacesIn(snapshot, regionKey).mapNotNull { s ->
        val p = snapshot.places[s.placeId] ?: return@mapNotNull null
        if (p.lat == 0.0 && p.lng == 0.0) return@mapNotNull null
        MapMarker(s.id, p.name.takeIf { it.isNotBlank() } ?: "Unbekannter Ort", p.lat, p.lng, s.status)
    }

/** Where the region map's camera goes for a set of markers. */
sealed interface CameraFit {
    data object None : CameraFit
    data class Center(val lat: Double, val lng: Double, val zoom: Double) : CameraFit
    data class Bounds(val south: Double, val west: Double, val north: Double, val east: Double) : CameraFit
}

/** Zoom for a single place (also used when all markers share one coordinate: bounds of zero size would zoom to the max). */
const val SINGLE_MARKER_ZOOM = 14.0

/** Camera for [markers]: nothing, the single-marker zoom on one distinct coordinate, else their bounds. */
fun cameraFitFor(markers: List<MapMarker>): CameraFit {
    val coords = markers.map { it.lat to it.lng }.distinct()
    return when (coords.size) {
        0 -> CameraFit.None
        1 -> CameraFit.Center(coords[0].first, coords[0].second, SINGLE_MARKER_ZOOM)
        else -> CameraFit.Bounds(
            south = coords.minOf { it.first },
            west = coords.minOf { it.second },
            north = coords.maxOf { it.first },
            east = coords.maxOf { it.second },
        )
    }
}

/** The region's places on an OpenStreetMap map; MapLibre is only composed when [LocalMapsEnabled] (never in tests). */
@Composable
fun RegionMap(markers: List<MapMarker>, onOpenPlace: (savedPlaceId: String) -> Unit, modifier: Modifier = Modifier) {
    when {
        !LocalMapsEnabled.current -> MapHint("Karte nicht verfügbar", modifier)
        markers.isEmpty() -> MapHint("Keine Orte mit Koordinaten", modifier)
        else -> RegionMapView(markers, onOpenPlace, modifier)
    }
}

@Composable
private fun MapHint(text: String, modifier: Modifier) {
    Box(modifier.padding(16.dp), contentAlignment = Alignment.TopCenter) {
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
