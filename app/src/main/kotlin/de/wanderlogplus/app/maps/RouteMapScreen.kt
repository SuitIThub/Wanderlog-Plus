package de.wanderlogplus.app.maps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds

/** Full-screen route map of a journey or a leg (`logistik/karte/...`). */
const val ROUTE_MAP_ROUTE = "logistik/karte/{type}/{id}"

/** [type] is "journey", "train" or "flight". */
fun routeMapRoute(type: String, id: String) = "logistik/karte/$type/${android.net.Uri.encode(id)}"

/**
 * A route full screen and movable (pan, zoom, rotate), drawn like the preview picture; starts showing the whole route.
 * Without maps (tests) the stylized picture. Tag `route-map-screen`.
 */
@Composable
fun RouteMapScreen(route: RouteGeometry, title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().testTag("route-map-screen")) {
        Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Zurück") }
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box(Modifier.fillMaxSize()) {
            if (LocalMapsEnabled.current) RouteMapView(route, Modifier.fillMaxSize())
            else RouteMapFallback(route, MaterialTheme.colorScheme.primary, Modifier.fillMaxSize())
        }
    }
}

/** The interactive MapLibre map of [route]. */
@Composable
fun RouteMapView(route: RouteGeometry, modifier: Modifier = Modifier) {
    val mapView = rememberLifecycleMapView()
    Box(modifier) {
        AndroidView(
            factory = { context ->
                val padding = (48 * context.resources.displayMetrics.density).toInt()
                mapView.getMapAsync { map ->
                    map.setStyle(routeStyle(route)) {
                        mapView.doOnLayout { map.moveCamera(CameraUpdateFactory.newLatLngBounds(routeBounds(route), padding)) }
                    }
                }
                mapView
            },
            modifier = Modifier.fillMaxSize(),
        )
        Text(
            MAP_ATTRIBUTION,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF3C3C3C),
            modifier = Modifier.align(Alignment.BottomEnd).background(Color.White.copy(alpha = 0.75f)).padding(horizontal = 4.dp, vertical = 1.dp),
        )
    }
}

/** Bounds of all route points; a route of (almost) no extent gets a small area instead of an endless zoom. */
private fun routeBounds(route: RouteGeometry): LatLngBounds {
    val pts = route.points()
    var south = pts.minOf { it.lat }
    var north = pts.maxOf { it.lat }
    var west = pts.minOf { it.lng }
    var east = pts.maxOf { it.lng }
    val minSpan = 0.01
    if (north - south < minSpan) { val c = (north + south) / 2; south = c - minSpan / 2; north = c + minSpan / 2 }
    if (east - west < minSpan) { val c = (east + west) / 2; west = c - minSpan / 2; east = c + minSpan / 2 }
    return LatLngBounds.Builder().include(LatLng(north, east)).include(LatLng(south, west)).build()
}
