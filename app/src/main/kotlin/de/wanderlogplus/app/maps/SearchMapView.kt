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
import de.wanderlogplus.client.BBox
import de.wanderlogplus.client.GeoPlace
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

private const val HITS = "hits"
private const val HIT_CIRCLES = "hits-circles"
private const val HIT_LABELS = "hits-labels"
private const val PICK = "pick"
private const val PICK_LAYER = "pick-layer"

private fun hitFeatures(results: List<GeoPlace>, selected: GeoPlace?): FeatureCollection = FeatureCollection.fromFeatures(
    results.mapIndexed { i, r ->
        Feature.fromGeometry(Point.fromLngLat(r.lng, r.lat)).apply {
            addNumberProperty("index", i)
            addStringProperty("name", r.name)
            addBooleanProperty("selected", selected != null && selected.lat == r.lat && selected.lng == r.lng)
        }
    },
)

/** The picked point when it is not one of the results (long press). */
private fun pickFeature(results: List<GeoPlace>, selected: GeoPlace?): FeatureCollection {
    if (selected == null || results.any { it.lat == selected.lat && it.lng == selected.lng }) return FeatureCollection.fromFeatures(emptyList())
    return FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(selected.lng, selected.lat)))
}

/**
 * Map for "Ort suchen": shows the search [results] (the [selected] one highlighted), reports the visible area after
 * every move ([onVisible]), selects a result on tap and reports long presses ([onLongPress]) for picking any point.
 * The camera starts at [start] (zoom 13) and zooms to the results whenever [fitRequest] changes.
 */
@Composable
fun SearchMapView(
    start: LatLngD?,
    results: List<GeoPlace>,
    selected: GeoPlace?,
    fitRequest: Int,
    onVisible: (BBox) -> Unit,
    onSelect: (GeoPlace) -> Unit,
    onLongPress: (lat: Double, lng: Double) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val visible by rememberUpdatedState(onVisible)
    val select by rememberUpdatedState(onSelect)
    val longPress by rememberUpdatedState(onLongPress)
    val currentResults by rememberUpdatedState(results)
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
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            if (!destroyed) {
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.onPause()
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) mapView.onStop()
                mapView.onDestroy()
            }
        }
    }
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
    val lastFit = remember { intArrayOf(-1) }
    Box(modifier) {
        AndroidView(
            factory = {
                mapView.getMapAsync { map ->
                    map.uiSettings.isRotateGesturesEnabled = false
                    start?.let { map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lng), 13.0)) }
                    map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                        addSearchLayers(style, currentResults, null)
                        mapView.doOnLayout { reportVisible(map, visible) }
                    }
                    map.addOnCameraIdleListener { reportVisible(map, visible) }
                    map.addOnMapClickListener { latLng ->
                        val p = map.projection.toScreenLocation(latLng)
                        val r = 16f * mapView.resources.displayMetrics.density
                        val index = map.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), HIT_CIRCLES, HIT_LABELS)
                            .firstNotNullOfOrNull { it.getNumberProperty("index")?.toInt() }
                        val hit = index?.let { currentResults.getOrNull(it) }
                        if (hit != null) select(hit)
                        hit != null
                    }
                    map.addOnMapLongClickListener { latLng -> longPress(latLng.latitude, latLng.longitude); true }
                }
                mapView
            },
            update = { view ->
                view.getMapAsync { map ->
                    val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync
                    style.getSourceAs<GeoJsonSource>(HITS)?.setGeoJson(hitFeatures(results, selected))
                    style.getSourceAs<GeoJsonSource>(PICK)?.setGeoJson(pickFeature(results, selected))
                    if (fitRequest != lastFit[0] && fitRequest > 0) {
                        lastFit[0] = fitRequest
                        view.doOnLayout { fitTo(map, results) }
                    }
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

private fun reportVisible(map: MapLibreMap, onVisible: (BBox) -> Unit) {
    val b = map.projection.visibleRegion.latLngBounds
    onVisible(BBox(west = b.longitudeWest, south = b.latitudeSouth, east = b.longitudeEast, north = b.latitudeNorth))
}

private fun fitTo(map: MapLibreMap, results: List<GeoPlace>) {
    when {
        results.isEmpty() -> Unit
        results.size == 1 || results.all { it.lat == results[0].lat && it.lng == results[0].lng } ->
            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(results[0].lat, results[0].lng), 15.0))
        else -> {
            val b = LatLngBounds.Builder().apply { results.forEach { include(LatLng(it.lat, it.lng)) } }.build()
            map.animateCamera(CameraUpdateFactory.newLatLngBounds(b, 120))
        }
    }
}

private fun addSearchLayers(style: Style, results: List<GeoPlace>, selected: GeoPlace?) {
    style.addSource(GeoJsonSource(HITS, hitFeatures(results, selected)))
    style.addSource(GeoJsonSource(PICK, pickFeature(results, selected)))
    style.addLayer(
        CircleLayer(HIT_CIRCLES, HITS).withProperties(
            PropertyFactory.circleColor(
                Expression.switchCase(Expression.get("selected"), Expression.color(android.graphics.Color.parseColor("#A5520F")), Expression.color(android.graphics.Color.parseColor("#1E88E5"))),
            ),
            PropertyFactory.circleRadius(Expression.switchCase(Expression.get("selected"), Expression.literal(11f), Expression.literal(8f))),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2f),
        ),
    )
    style.addLayer(
        SymbolLayer(HIT_LABELS, HITS).withProperties(
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
    style.addLayer(
        CircleLayer(PICK_LAYER, PICK).withProperties(
            PropertyFactory.circleColor("#A5520F"),
            PropertyFactory.circleRadius(11f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(3f),
        ),
    )
}
