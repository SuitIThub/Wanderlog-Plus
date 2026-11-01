package de.wanderlogplus.app.maps

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

private const val MAX_RETRIES = 1

/** Snapshot renderer for [RouteMap]; provided by MainActivity, null in tests (they see [RouteMapFallback]). */
val LocalRouteMapRenderer = staticCompositionLocalOf<RouteMapRenderer?> { null }

/**
 * Map picture of a trip leg: the cached/rendered snapshot, or a stylized fallback while it is missing, without a renderer
 * or when rendering failed. Renders nothing for `route == null` (no coordinates).
 */
@Composable
fun RouteMap(
    route: RouteGeometry?,
    accent: Color,
    height: Dp = 120.dp,
    renderer: RouteMapRenderer? = LocalRouteMapRenderer.current,
    modifier: Modifier = Modifier,
) {
    if (route == null) return
    BoxWithConstraints(modifier.fillMaxWidth().height(height)) {
        val density = LocalDensity.current
        val widthPx = constraints.maxWidth.takeIf { constraints.hasBoundedWidth } ?: with(density) { 360.dp.roundToPx() }
        val heightPx = with(density) { height.roundToPx() }
        val key = arrayOf<Any?>(route, widthPx, heightPx, renderer)
        var file by remember(*key) { mutableStateOf<File?>(null) }
        // An undecodable PNG (truncated, corrupt) is deleted by the effect and rendered once more; a second failure keeps the fallback.
        var corrupt by remember(*key) { mutableStateOf<File?>(null) }
        var retries by remember(*key) { mutableIntStateOf(0) }
        var broken by remember(*key) { mutableStateOf(false) }
        LaunchedEffect(route, widthPx, heightPx, renderer, retries) {
            if (renderer == null) return@LaunchedEffect
            // Disk work off the main thread: the cache check, too, never runs during composition.
            val stale = corrupt
            corrupt = null
            val cached = withContext(Dispatchers.IO) {
                stale?.delete()
                renderer.cachedFile(route, widthPx, heightPx)
            }
            file = cached ?: renderer.render(route, widthPx, heightPx)
        }
        val shown: File? = file
        if (shown == null || broken) {
            RouteMapFallback(route, accent, Modifier.fillMaxSize())
        } else {
            AsyncImage(
                model = shown,
                contentDescription = "Routenkarte",
                contentScale = ContentScale.Crop,
                onError = {
                    file = null
                    if (retries < MAX_RETRIES) { corrupt = shown; retries++ } else broken = true
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Stylized route picture without map tiles: accent gradient, the stops and the line, arcs or path. Tag `route-fallback`. */
@Composable
fun RouteMapFallback(route: RouteGeometry, accent: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .background(Brush.linearGradient(listOf(accent.copy(alpha = 0.85f), accent.copy(alpha = 0.45f))))
            .semantics { contentDescription = "Routenkarte" }
            .testTag("route-fallback"),
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val pts = route.points()
            // Equirectangular projection, longitude scaled by cos(latitude), fitted into the box with padding (aspect kept).
            val k = cos(Math.toRadians((route.from.lat + route.to.lat) / 2))
            val xs = pts.map { it.lng * k }
            val ys = pts.map { -it.lat }
            val pad = min(size.width, size.height) * 0.22f
            val spanX = (xs.max() - xs.min()).toFloat()
            val spanY = (ys.max() - ys.min()).toFloat()
            val scale = min(
                if (spanX > 0f) (size.width - 2 * pad) / spanX else Float.MAX_VALUE,
                if (spanY > 0f) (size.height - 2 * pad) / spanY else Float.MAX_VALUE,
            ).let { if (it == Float.MAX_VALUE) 0f else max(it, 0f) }
            val cx = ((xs.max() + xs.min()) / 2).toFloat()
            val cy = ((ys.max() + ys.min()) / 2).toFloat()
            val offsets = xs.indices.map { i ->
                Offset(size.width / 2 + (xs[i].toFloat() - cx) * scale, size.height / 2 + (ys[i].toFloat() - cy) * scale)
            }
            val path = Path().apply {
                moveTo(offsets.first().x, offsets.first().y)
                offsets.drop(1).forEach { lineTo(it.x, it.y) }
            }
            val stroke = 3.dp.toPx()
            drawPath(path, Color.White.copy(alpha = 0.9f), style = Stroke(width = stroke, cap = StrokeCap.Round))
            // Stops in the same projection; start and destination larger than stopovers.
            route.stops.forEachIndexed { i, st ->
                val o = Offset(size.width / 2 + ((st.lng * k).toFloat() - cx) * scale, size.height / 2 + ((-st.lat).toFloat() - cy) * scale)
                val end = i == 0 || i == route.stops.lastIndex
                drawCircle(Color.White, radius = (if (end) 6f else 4.5f).dp.toPx(), center = o)
                drawCircle(accent, radius = (if (end) 3.5f else 2.5f).dp.toPx(), center = o)
            }
        }
    }
}
