package de.wanderlogplus.app.maps

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import de.wanderlogplus.app.ui.places.CameraFit
import de.wanderlogplus.app.ui.places.MapMarker
import de.wanderlogplus.app.ui.places.cameraFitFor
import de.wanderlogplus.shared.model.PlaceStatus
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** True only in MainActivity: MapLibre's native libraries cannot load under Robolectric, so tests never compose a MapView. */
val LocalMapsEnabled = staticCompositionLocalOf { false }

private const val SOURCE_ID = "places"
private const val CIRCLE_LAYER = "places-circles"
private const val LABEL_LAYER = "places-labels"

fun markerColor(status: PlaceStatus): String = when (status) {
    PlaceStatus.IDEA -> "#1E88E5"
    PlaceStatus.SHORTLIST -> "#8E24AA"
    PlaceStatus.PLANNED -> "#2E7D32"
    PlaceStatus.VISITED -> "#7B1FA2"
    PlaceStatus.DROPPED -> "#D81B60"
}

private fun featuresOf(markers: List<MapMarker>): FeatureCollection = FeatureCollection.fromFeatures(
    markers.map { m ->
        Feature.fromGeometry(Point.fromLngLat(m.lng, m.lat)).apply {
            addStringProperty("id", m.savedPlaceId)
            addStringProperty("name", m.name)
            addStringProperty("color", markerColor(m.status))
        }
    },
)

/** Interactive OpenStreetMap (MapLibre) map of a region's places; a tap on a marker opens the place. */
@Composable
fun RegionMapView(markers: List<MapMarker>, onOpenPlace: (savedPlaceId: String) -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val openPlace by rememberUpdatedState(onOpenPlace)
    val currentMarkers by rememberUpdatedState(markers)
    val mapView = remember {
        MapLibre.getInstance(context.applicationContext)
        MapView(context).apply { onCreate(null) }
    }
    DisposableEffect(lifecycle, mapView) {
        var destroyed = false
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                Lifecycle.Event.ON_DESTROY -> { mapView.onDestroy(); destroyed = true }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer) // replays the events up to the current state
        onDispose {
            lifecycle.removeObserver(observer)
            if (!destroyed) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
        }
    }
    // MapView wants onLowMemory from its host; forward the system callback while this map is shown.
    DisposableEffect(context, mapView) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = mapView.onLowMemory()
            override fun onTrimMemory(level: Int) = Unit
        }
        context.registerComponentCallbacks(callbacks)
        onDispose { context.unregisterComponentCallbacks(callbacks) }
    }
    // The fit the camera last moved to; a changed marker set (filters, edits) moves it again.
    val fitted = remember { mutableListOf<CameraFit>() }
    Box(modifier) {
        AndroidView(
            factory = {
                mapView.getMapAsync { map ->
                    map.uiSettings.isRotateGesturesEnabled = false
                    map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                        addPlaceLayers(style, currentMarkers)
                        mapView.doOnLayout { fitted.fitOnce(map, cameraFitFor(currentMarkers)) }
                    }
                    map.addOnMapClickListener { latLng ->
                        val p = map.projection.toScreenLocation(latLng)
                        val r = 14f * mapView.resources.displayMetrics.density
                        val hit = map.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), CIRCLE_LAYER, LABEL_LAYER)
                            .firstNotNullOfOrNull { it.getStringProperty("id") }
                        if (hit != null) openPlace(hit)
                        hit != null
                    }
                }
                mapView
            },
            update = { view ->
                view.getMapAsync { map ->
                    val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync // the style callback fits first
                    style.getSourceAs<GeoJsonSource>(SOURCE_ID)?.setGeoJson(featuresOf(markers))
                    view.doOnLayout { fitted.fitOnce(map, cameraFitFor(markers)) }
                }
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

private fun addPlaceLayers(style: Style, markers: List<MapMarker>) {
    style.addSource(GeoJsonSource(SOURCE_ID, featuresOf(markers)))
    style.addLayer(
        CircleLayer(CIRCLE_LAYER, SOURCE_ID).withProperties(
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))),
            PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    style.addLayer(
        SymbolLayer(LABEL_LAYER, SOURCE_ID).withProperties(
            PropertyFactory.textField(Expression.get("name")),
            PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
            PropertyFactory.textSize(12f),
            PropertyFactory.textOffset(arrayOf(0f, 1.3f)),
            PropertyFactory.textAnchor("top"),
            PropertyFactory.textOptional(true),
            PropertyFactory.textColor("#333333"),
            PropertyFactory.textHaloColor("#FFFFFF"),
            PropertyFactory.textHaloWidth(1.5f),
        ),
    )
}

/** Moves the camera to [fit] unless it is the one applied last (keeps the user's pan/zoom while markers stay the same). */
private fun MutableList<CameraFit>.fitOnce(map: MapLibreMap, fit: CameraFit) {
    if (lastOrNull() == fit) return
    clear()
    add(fit)
    when (fit) {
        CameraFit.None -> Unit
        is CameraFit.Center -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fit.lat, fit.lng), fit.zoom))
        is CameraFit.Bounds -> {
            val b = LatLngBounds.Builder().include(LatLng(fit.north, fit.east)).include(LatLng(fit.south, fit.west)).build()
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 100))
        }
    }
}
