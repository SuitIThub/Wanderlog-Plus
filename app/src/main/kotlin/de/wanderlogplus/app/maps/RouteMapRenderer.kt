package de.wanderlogplus.app.maps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.maplibre.android.MapLibre
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.io.File
import kotlin.coroutines.resume

/** OpenFreeMap's free OpenStreetMap style; needs no key. */
const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

/** Text drawn into every snapshot (license requirement). */
const val MAP_ATTRIBUTION = "© OpenStreetMap"

/**
 * Renders a route once with MapLibre's [MapSnapshotter] and keeps the PNG in [cacheDir], so it stays available offline.
 * The cache lookup comes before any MapLibre call, so a cache hit never initializes MapLibre (testable under Robolectric).
 */
class RouteMapRenderer(
    context: Context,
    private val cacheDir: File = File(context.cacheDir, "route-maps"),
    /** Only render with a validated internet connection: an offline snapshot would cache missing tiles for good. */
    private val isOnline: () -> Boolean = { hasValidatedInternet(context.applicationContext) },
    /** The MapLibre snapshot (main thread); injectable so tests can verify it is never reached. */
    private val snapshot: suspend (RouteGeometry, Int, Int) -> Bitmap? = { route, w, h ->
        RouteSnapshots.snapshot(context.applicationContext, route, w, h)
    },
) {
    private val mutex = Mutex() // one snapshotter at a time: they are memory-heavy

    private fun fileFor(route: RouteGeometry, widthPx: Int, heightPx: Int) = File(cacheDir, route.cacheKey(widthPx, heightPx) + ".png")

    /** The cached PNG, or null when it has not been rendered yet. Only a file-system check. */
    fun cachedFile(route: RouteGeometry, widthPx: Int, heightPx: Int): File? =
        fileFor(route, widthPx, heightPx).takeIf { it.isFile && it.length() > 0 }

    /** The cached or freshly rendered PNG; null on any failure (e.g. no network). Never logs coordinates. */
    suspend fun render(route: RouteGeometry, widthPx: Int, heightPx: Int): File? {
        if (widthPx <= 0 || heightPx <= 0) return null
        cachedFile(route, widthPx, heightPx)?.let { return it }
        return try {
            if (!isOnline()) return null // fallback now; rendered on a later composition once online
            mutex.withLock {
                cachedFile(route, widthPx, heightPx)?.let { return@withLock it }
                val bitmap = withTimeoutOrNull(RENDER_TIMEOUT_MS) {
                    withContext(Dispatchers.Main) { snapshot(route, widthPx, heightPx) }
                } ?: return@withLock null
                withContext(Dispatchers.IO) { writeAtomically(bitmap, fileFor(route, widthPx, heightPx)) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            null // no details: they could carry coordinates
        }
    }

    private fun writeAtomically(bitmap: Bitmap, target: File): File? {
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.outputStream().use { out -> if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) return null }
            if (!tmp.renameTo(target)) return null
            return target
        } finally {
            tmp.delete()
        }
    }

    private companion object {
        const val RENDER_TIMEOUT_MS = 30_000L
    }
}

/** True when the active network has validated internet access (not just a captive portal or a dead Wi-Fi). */
private fun hasValidatedInternet(context: Context): Boolean {
    val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
    val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
    return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
        caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
}

/** All MapLibre calls of the renderer; a separate class, so loading [RouteMapRenderer] never touches MapLibre. */
private object RouteSnapshots {
    private const val PADDING_PX = 48
    private const val ROUTE_COLOR = "#A5520F"

    /** Must run on the main thread: MapSnapshotter's callbacks arrive there. */
    suspend fun snapshot(context: Context, route: RouteGeometry, widthPx: Int, heightPx: Int): Bitmap? {
        MapLibre.getInstance(context) // lazy: only when a snapshot is really needed
        val points = route.points().map { Point.fromLngLat(it.lng, it.lat) }
        val style = Style.Builder().fromUri(MAP_STYLE_URL)
            .withSource(GeoJsonSource("route", FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points)))))
            .withSource(
                GeoJsonSource(
                    "route-ends",
                    FeatureCollection.fromFeatures(listOf(Feature.fromGeometry(points.first()), Feature.fromGeometry(points.last()))),
                ),
            )
            .withLayer(
                LineLayer("route-line", "route").withProperties(
                    PropertyFactory.lineColor(ROUTE_COLOR),
                    PropertyFactory.lineWidth(4f),
                    PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
                    PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            .withLayer(
                CircleLayer("route-ends-layer", "route-ends").withProperties(
                    PropertyFactory.circleColor(ROUTE_COLOR),
                    PropertyFactory.circleRadius(7f),
                    PropertyFactory.circleStrokeColor("#FFFFFF"),
                    PropertyFactory.circleStrokeWidth(2.5f),
                ),
            )
        // Options take logical pixels; with the screen's pixel ratio the bitmap has ~widthPx x heightPx and crisp labels.
        val ratio = context.resources.displayMetrics.density.coerceAtLeast(1f)
        val options = MapSnapshotter.Options((widthPx / ratio).toInt().coerceAtLeast(1), (heightPx / ratio).toInt().coerceAtLeast(1))
            .withStyleBuilder(style)
            .withRegion(paddedBounds(route, widthPx, heightPx))
            .withLogo(false)
            .withPixelRatio(ratio)
        val snapshotter = MapSnapshotter(context, options)
        val main = Handler(Looper.getMainLooper())
        val bitmap = suspendCancellableCoroutine<Bitmap?> { cont ->
            cont.invokeOnCancellation { main.post { snapshotter.cancel() } }
            snapshotter.start(
                { snap -> if (cont.isActive) cont.resume(snap.bitmap.copy(Bitmap.Config.ARGB_8888, true)) },
                { _ -> if (cont.isActive) cont.resume(null) },
            )
        } ?: return null
        drawAttribution(bitmap, ratio)
        return bitmap
    }

    /**
     * Bounds of the route widened so that it keeps [PADDING_PX] to every edge (linear approximation, fine at route scale).
     * A route of (almost) zero extent still gets a small region instead of an infinite zoom.
     */
    private fun paddedBounds(route: RouteGeometry, widthPx: Int, heightPx: Int): LatLngBounds {
        val pts = route.points()
        var south = pts.minOf { it.lat }
        var north = pts.maxOf { it.lat }
        var west = pts.minOf { it.lng }
        var east = pts.maxOf { it.lng }
        val minSpan = 0.01
        if (north - south < minSpan) { val c = (north + south) / 2; south = c - minSpan / 2; north = c + minSpan / 2 }
        if (east - west < minSpan) { val c = (east + west) / 2; west = c - minSpan / 2; east = c + minSpan / 2 }
        val innerW = (widthPx - 2 * PADDING_PX).coerceAtLeast(widthPx / 3)
        val innerH = (heightPx - 2 * PADDING_PX).coerceAtLeast(heightPx / 3)
        val padLng = (east - west) * (widthPx.toDouble() / innerW - 1) / 2
        val padLat = (north - south) * (heightPx.toDouble() / innerH - 1) / 2
        return LatLngBounds.Builder()
            .include(LatLng((north + padLat).coerceAtMost(85.0), east + padLng))
            .include(LatLng((south - padLat).coerceAtLeast(-85.0), west - padLng))
            .build()
    }

    private fun drawAttribution(bitmap: Bitmap, density: Float) {
        val canvas = Canvas(bitmap)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(60, 60, 60); textSize = 10f * density }
        val pad = 3f * density
        val w = text.measureText(MAP_ATTRIBUTION)
        val h = text.fontMetrics.let { it.descent - it.ascent }
        val box = RectF(bitmap.width - w - 2 * pad, bitmap.height - h - 2 * pad, bitmap.width.toFloat(), bitmap.height.toFloat())
        canvas.drawRect(box, Paint().apply { color = Color.argb(190, 255, 255, 255) })
        canvas.drawText(MAP_ATTRIBUTION, box.left + pad, box.bottom - pad - text.fontMetrics.descent, text)
    }
}
