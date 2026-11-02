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
import de.wanderlogplus.app.ui.trip.RouteSegment
import de.wanderlogplus.app.ui.trip.RouteStop
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

private const val LINES = "day-lines"
private const val LINE_SOLID = "day-lines-solid"
private const val LINE_DASHED = "day-lines-dashed"
private const val STOPS = "day-stops"
private const val STOP_CIRCLES = "day-stops-circles"
private const val STOP_NUMBERS = "day-stops-numbers"
private const val ROUTE_COLOR = "#A5520F"

private fun lineFeatures(segments: List<RouteSegment>) = FeatureCollection.fromFeatures(
    segments.map { s ->
        Feature.fromGeometry(LineString.fromLngLats(s.path.map { Point.fromLngLat(it.lng, it.lat) })).apply {
            addBooleanProperty("straight", s.straight)
        }
    },
)

private fun stopFeatures(stops: List<RouteStop>, selected: String?) = FeatureCollection.fromFeatures(
    stops.map { s ->
        Feature.fromGeometry(Point.fromLngLat(s.at.lng, s.at.lat)).apply {
            addStringProperty("id", s.itemId)
            addStringProperty("label", s.number.toString())
            addBooleanProperty("selected", s.itemId == selected)
        }
    },
)

/**
 * Full map of a day's program: numbered stops connected by each segment's route (solid) or, where no route is known,
 * a dashed straight line. The camera fits all stops once; a tap on a stop selects it ([onSelect]).
 */
@Composable
fun DayRouteMapView(
    stops: List<RouteStop>,
    segments: List<RouteSegment>,
    selected: String?,
    onSelect: (itemId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val select by rememberUpdatedState(onSelect)
    val currentStops by rememberUpdatedState(stops)
    val currentSegments by rememberUpdatedState(segments)
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
    Box(modifier) {
        AndroidView(
            factory = {
                mapView.getMapAsync { map ->
                    map.uiSettings.isRotateGesturesEnabled = false
                    map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                        addDayLayers(style, currentStops, currentSegments, null)
                        mapView.doOnLayout { fitStops(map, currentStops) }
                    }
                    map.addOnMapClickListener { latLng ->
                        val p = map.projection.toScreenLocation(latLng)
                        val r = 18f * mapView.resources.displayMetrics.density
                        val hit = map.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), STOP_CIRCLES, STOP_NUMBERS)
                            .firstNotNullOfOrNull { it.getStringProperty("id") }
                        if (hit != null) select(hit)
                        hit != null
                    }
                }
                mapView
            },
            update = { view ->
                view.getMapAsync { map ->
                    val style = map.style?.takeIf { it.isFullyLoaded } ?: return@getMapAsync
                    style.getSourceAs<GeoJsonSource>(LINES)?.setGeoJson(lineFeatures(segments))
                    style.getSourceAs<GeoJsonSource>(STOPS)?.setGeoJson(stopFeatures(stops, selected))
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

private fun fitStops(map: MapLibreMap, stops: List<RouteStop>) {
    when {
        stops.isEmpty() -> Unit
        stops.size == 1 -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(stops[0].at.lat, stops[0].at.lng), 14.0))
        else -> {
            val b = LatLngBounds.Builder().apply { stops.forEach { include(LatLng(it.at.lat, it.at.lng)) } }.build()
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 120))
        }
    }
}

private fun addDayLayers(style: Style, stops: List<RouteStop>, segments: List<RouteSegment>, selected: String?) {
    style.addSource(GeoJsonSource(LINES, lineFeatures(segments)))
    style.addSource(GeoJsonSource(STOPS, stopFeatures(stops, selected)))
    style.addLayer(
        LineLayer(LINE_SOLID, LINES).withFilter(Expression.not(Expression.get("straight"))).withProperties(
            PropertyFactory.lineColor(ROUTE_COLOR),
            PropertyFactory.lineWidth(5f),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        ),
    )
    style.addLayer(
        LineLayer(LINE_DASHED, LINES).withFilter(Expression.get("straight")).withProperties(
            PropertyFactory.lineColor(ROUTE_COLOR),
            PropertyFactory.lineWidth(3f),
            PropertyFactory.lineOpacity(0.7f),
            PropertyFactory.lineDasharray(arrayOf(2f, 2f)),
        ),
    )
    style.addLayer(
        CircleLayer(STOP_CIRCLES, STOPS).withProperties(
            PropertyFactory.circleColor(
                Expression.switchCase(Expression.get("selected"), Expression.color(android.graphics.Color.parseColor("#1E88E5")), Expression.color(android.graphics.Color.parseColor(ROUTE_COLOR))),
            ),
            PropertyFactory.circleRadius(13f),
            PropertyFactory.circleStrokeColor("#FFFFFF"),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    style.addLayer(
        SymbolLayer(STOP_NUMBERS, STOPS).withProperties(
            PropertyFactory.textField(Expression.get("label")),
            PropertyFactory.textFont(arrayOf("Noto Sans Bold")),
            PropertyFactory.textSize(13f),
            PropertyFactory.textColor("#FFFFFF"),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
        ),
    )
}
