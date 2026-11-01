package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MarkerInfoWindow
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberMarkerState
import de.wanderlogplus.app.ui.statusLabel
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

private fun markerHue(status: PlaceStatus): Float = when (status) {
    PlaceStatus.IDEA -> BitmapDescriptorFactory.HUE_AZURE
    PlaceStatus.PLANNED -> BitmapDescriptorFactory.HUE_GREEN
    PlaceStatus.VISITED -> BitmapDescriptorFactory.HUE_VIOLET
    PlaceStatus.DROPPED -> BitmapDescriptorFactory.HUE_ROSE
}

/** Only composed when a Maps key is configured (never in unit tests). */
@Composable
fun RegionMap(markers: List<MapMarker>, onOpenPlace: (savedPlaceId: String) -> Unit, modifier: Modifier = Modifier) {
    if (markers.isEmpty()) {
        Text("Keine Orte mit Koordinaten", modifier)
        return
    }
    val camera = rememberCameraPositionState()
    var loaded by remember { mutableStateOf(false) }
    GoogleMap(modifier = modifier.fillMaxSize(), cameraPositionState = camera, onMapLoaded = { loaded = true }) {
        markers.forEach { m ->
            MarkerInfoWindow(
                state = rememberMarkerState(key = m.savedPlaceId, position = LatLng(m.lat, m.lng)),
                icon = BitmapDescriptorFactory.defaultMarker(markerHue(m.status)),
                onInfoWindowClick = { onOpenPlace(m.savedPlaceId) },
            ) {
                Column {
                    Text(m.name)
                    Text(statusLabel(m.status))
                }
            }
        }
    }
    LaunchedEffect(loaded, markers) {
        if (!loaded) return@LaunchedEffect
        if (markers.size == 1) {
            camera.move(CameraUpdateFactory.newLatLngZoom(LatLng(markers[0].lat, markers[0].lng), 14f))
        } else {
            val b = LatLngBounds.builder().also { bb -> markers.forEach { bb.include(LatLng(it.lat, it.lng)) } }.build()
            camera.move(CameraUpdateFactory.newLatLngBounds(b, 100))
        }
    }
}
