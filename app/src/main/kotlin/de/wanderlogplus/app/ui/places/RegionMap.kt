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
