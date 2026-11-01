package de.wanderlogplus.app.maps

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import android.graphics.Bitmap
import android.graphics.Color
import org.junit.Assert.assertFalse
import java.io.File

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class RouteMapCacheTest {
    @Test fun cachedFileIsReturnedWithoutRendering() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "route-maps-test").apply { deleteRecursively(); mkdirs() }
        val route = RouteGeometry(LatLngD(34.98, 135.75), LatLngD(34.70, 135.49), arc = false)
        val cached = File(dir, route.cacheKey(640, 240) + ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val renderer = RouteMapRenderer(context, dir, isOnline = { false }, snapshot = { _, _, _ -> error("must not render") })
        // A cache hit (also offline) must return before MapLibre is touched (its native libs cannot load under Robolectric).
        val result = renderer.render(route, 640, 240)
        assertEquals(cached.absolutePath, result?.absolutePath)
        assertTrue(renderer.cachedFile(route, 640, 240)!!.isFile)
    }

    @Test fun offlineWithoutCacheReturnsNullAndRendersNothing() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "route-maps-offline").apply { deleteRecursively(); mkdirs() }
        val route = RouteGeometry(LatLngD(35.55, 139.78), LatLngD(34.43, 135.23), arc = true)
        var renders = 0
        val renderer = RouteMapRenderer(context, dir, isOnline = { false }, snapshot = { _, _, _ -> renders++; null })
        assertNull(renderer.render(route, 640, 240))
        assertEquals(0, renders)
        assertEquals(0, dir.listFiles()!!.size)
    }

    private fun solid(color: Int) = Bitmap.createBitmap(64, 40, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }

    /** Blank snapshot plus a different attribution box at the bottom: still blank. */
    private fun blankWithAttribution() = solid(Color.rgb(240, 238, 230)).apply {
        for (x in 20 until 64) for (y in 32 until 40) setPixel(x, y, Color.WHITE)
    }

    private fun striped() = solid(Color.WHITE).apply {
        for (x in 0 until 64) for (y in 0 until 40) if ((x / 8 + y / 5) % 2 == 0) setPixel(x, y, Color.rgb(165, 82, 15))
    }

    @Test fun almostSolidDetection() {
        assertTrue(isAlmostSolid(solid(Color.LTGRAY)))
        assertTrue(isAlmostSolid(blankWithAttribution()))
        assertFalse(isAlmostSolid(striped()))
        // 4 of 64 samples differ (6 %): not "more than 95 % identical" any more.
        var i = 0
        assertFalse(isAlmostSolid(8, 8) { _, _ -> if (i++ < 4) 1 else 0 })
        i = 0
        assertTrue(isAlmostSolid(8, 8) { _, _ -> if (i++ < 3) 1 else 0 })
    }

    @Test fun blankSnapshotIsNotCached() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "route-maps-blank").apply { deleteRecursively(); mkdirs() }
        val route = RouteGeometry(LatLngD(34.98, 135.75), LatLngD(34.70, 135.49), arc = false)
        var renders = 0
        val blank = RouteMapRenderer(context, dir, isOnline = { true }, snapshot = { _, _, _ -> renders++; blankWithAttribution() }, snapshotDispatcher = Dispatchers.Unconfined)
        assertNull(blank.render(route, 64, 40))
        assertEquals(0, dir.listFiles()!!.size)
        val good = RouteMapRenderer(context, dir, isOnline = { true }, snapshot = { _, _, _ -> renders++; striped() }, snapshotDispatcher = Dispatchers.Unconfined)
        assertTrue(good.render(route, 64, 40)!!.isFile)
        assertEquals(2, renders)
    }

    @Test fun clearCacheDeletesAllSnapshots() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "route-maps-clear").apply { deleteRecursively(); mkdirs() }
        val route = RouteGeometry(LatLngD(34.98, 135.75), LatLngD(34.70, 135.49), arc = false)
        File(dir, route.cacheKey(640, 240) + ".png").writeBytes(byteArrayOf(1, 2, 3))
        val renderer = RouteMapRenderer(context, dir, isOnline = { false }, snapshot = { _, _, _ -> null })
        renderer.clearCache()
        assertNull(renderer.cachedFile(route, 640, 240))
        assertFalse(dir.exists())
    }
}
