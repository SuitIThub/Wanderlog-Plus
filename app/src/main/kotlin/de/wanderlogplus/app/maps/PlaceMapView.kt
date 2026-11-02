package de.wanderlogplus.app.maps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import de.wanderlogplus.app.ui.places.CameraFit
import de.wanderlogplus.app.ui.places.PlaceMapPoint
import de.wanderlogplus.app.ui.places.cameraFitForCoords
import de.wanderlogplus.app.ui.places.poiColor
import de.wanderlogplus.app.ui.places.poiSymbol
import de.wanderlogplus.shared.model.PoiKinds
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

private const val PLACE_SOURCE = "pm-place"
private const val POI_SOURCE = "pm-pois"
private const val PLACE_LAYER = "pm-place-layer"
private const val POI_LAYER = "pm-poi-layer"
private const val PIN_ICON = "pm-pin"
private const val FOCUS_ZOOM = 17.0

private fun poiIcon(kind: String) = "pm-poi-$kind"

private fun features(points: List<PlaceMapPoint>) = FeatureCollection.fromFeatures(
    points.map { p ->
        Feature.fromGeometry(Point.fromLngLat(p.lng, p.lat)).apply {
            addStringProperty("id", p.id)
            addStringProperty("name", p.name)
            addStringProperty("icon", p.kind?.let(::poiIcon) ?: PIN_ICON)
        }
    },
)

/**
 * The place as a pin and its related spots (research) as round badges with a symbol per kind, on an OpenStreetMap
 * map. [interactive] false: a still preview (no gestures). [focus] moves the camera to that point; a tap on a
 * symbol reports its id.
 */
@Composable
fun PlaceMapView(
    points: List<PlaceMapPoint>,
    accent: Int,
    modifier: Modifier = Modifier,
    interactive: Boolean = true,
    focus: String? = null,
    onSelect: (id: String) -> Unit = {},
) {
    val mapView = rememberLifecycleMapView()
    val select by rememberUpdatedState(onSelect)
    val current by rememberUpdatedState(points)
    val state = remember { MapState() }
    Box(modifier) {
        AndroidView(
            factory = { context ->
                val density = context.resources.displayMetrics.density
                mapView.getMapAsync { map ->
                    map.uiSettings.isRotateGesturesEnabled = false
                    if (!interactive) map.uiSettings.setAllGesturesEnabled(false)
                    map.setStyle(Style.Builder().fromUri(MAP_STYLE_URL)) { style ->
                        style.addImage(PIN_ICON, pinBitmap(accent, density))
                        PoiKinds.LABELS.keys.forEach { k -> style.addImage(poiIcon(k), poiBitmap(k, density)) }
                        addLayers(style, current)
                        mapView.doOnLayout { fit(map, current, state) }
                    }
                    map.addOnMapClickListener { latLng ->
                        val p = map.projection.toScreenLocation(latLng)
                        val r = 16f * density
                        val hit = map.queryRenderedFeatures(RectF(p.x - r, p.y - r, p.x + r, p.y + r), POI_LAYER, PLACE_LAYER)
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
                    style.getSourceAs<GeoJsonSource>(PLACE_SOURCE)?.setGeoJson(features(points.filter { it.kind == null }))
                    style.getSourceAs<GeoJsonSource>(POI_SOURCE)?.setGeoJson(features(points.filter { it.kind != null }))
                    view.doOnLayout { fit(map, points, state) }
                    if (focus != state.focused) {
                        state.focused = focus
                        points.firstOrNull { it.id == focus }?.let {
                            map.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(it.lat, it.lng), FOCUS_ZOOM))
                        }
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

private fun addLayers(style: Style, points: List<PlaceMapPoint>) {
    style.addSource(GeoJsonSource(POI_SOURCE, features(points.filter { it.kind != null })))
    style.addSource(GeoJsonSource(PLACE_SOURCE, features(points.filter { it.kind == null })))
    fun label(offsetY: Float) = arrayOf(
        PropertyFactory.textField(Expression.get("name")),
        PropertyFactory.textFont(arrayOf("Noto Sans Regular")),
        PropertyFactory.textSize(12f),
        PropertyFactory.textOffset(arrayOf(0f, offsetY)),
        PropertyFactory.textAnchor("top"),
        PropertyFactory.textOptional(true),
        PropertyFactory.textColor("#333333"),
        PropertyFactory.textHaloColor("#FFFFFF"),
        PropertyFactory.textHaloWidth(1.5f),
    )
    style.addLayer(
        SymbolLayer(POI_LAYER, POI_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAllowOverlap(true),
            *label(1.4f),
        ),
    )
    // The place itself is drawn last: its pin stays on top of nearby spots.
    style.addLayer(
        SymbolLayer(PLACE_LAYER, PLACE_SOURCE).withProperties(
            PropertyFactory.iconImage(Expression.get("icon")),
            PropertyFactory.iconAnchor("bottom"),
            PropertyFactory.iconAllowOverlap(true),
            *label(0.4f),
        ),
    )
}

/** What the camera last did, so recompositions keep the user's pan/zoom. */
private class MapState(var fitted: CameraFit? = null, var focused: String? = null)

/** Fits all points once per distinct point set. */
private fun fit(map: MapLibreMap, points: List<PlaceMapPoint>, state: MapState) {
    val fit = cameraFitForCoords(points.map { it.lat to it.lng })
    if (state.fitted == fit) return
    state.fitted = fit
    when (fit) {
        CameraFit.None -> Unit
        is CameraFit.Center -> map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fit.lat, fit.lng), 15.5))
        is CameraFit.Bounds -> {
            val b = LatLngBounds.Builder().include(LatLng(fit.north, fit.east)).include(LatLng(fit.south, fit.west)).build()
            map.moveCamera(CameraUpdateFactory.newLatLngBounds(b, 90))
            if (map.cameraPosition.zoom > FOCUS_ZOOM) map.moveCamera(CameraUpdateFactory.zoomTo(FOCUS_ZOOM))
        }
    }
}

/** Teardrop pin in [color] with a white dot: the place itself. */
private fun pinBitmap(color: Int, density: Float): Bitmap {
    val w = (30 * density).toInt()
    val h = (40 * density).toInt()
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val r = w / 2f - density
    val cx = w / 2f
    val cy = r + density
    val path = Path().apply {
        addCircle(cx, cy, r, Path.Direction.CW)
        moveTo(cx - r * 0.62f, cy + r * 0.78f)
        lineTo(cx, h.toFloat() - density)
        lineTo(cx + r * 0.62f, cy + r * 0.78f)
        close()
    }
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE; style = Paint.Style.STROKE; strokeWidth = 2 * density })
    c.drawCircle(cx, cy, r * 0.38f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE })
    return bmp
}

/** Round white badge with a coloured ring and the kind's symbol: a related spot, clearly unlike the pin. */
private fun poiBitmap(kind: String, density: Float): Bitmap {
    val size = (28 * density).toInt()
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val half = size / 2f
    val ring = poiColor(kind)
    c.drawCircle(half, half, half - density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE })
    c.drawCircle(half, half, half - 2 * density, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ring; style = Paint.Style.STROKE; strokeWidth = 2.5f * density })
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 14 * density; textAlign = Paint.Align.CENTER; color = ring }
    val fm = text.fontMetrics
    c.drawText(poiSymbol(kind), half, half - (fm.ascent + fm.descent) / 2, text)
    return bmp
}
