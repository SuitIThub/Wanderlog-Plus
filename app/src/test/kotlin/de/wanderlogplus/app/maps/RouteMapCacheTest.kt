package de.wanderlogplus.app.maps

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteMapCacheTest {
    @Test fun cachedFileIsReturnedWithoutRendering() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.cacheDir, "route-maps-test").apply { deleteRecursively(); mkdirs() }
        val route = RouteGeometry(LatLngD(34.98, 135.75), LatLngD(34.70, 135.49), arc = false)
        val cached = File(dir, route.cacheKey(640, 240) + ".png").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val renderer = RouteMapRenderer(context, dir)
        // A cache hit must return before MapLibre is touched (its native libs cannot load under Robolectric).
        val result = renderer.render(route, 640, 240)
        assertEquals(cached.absolutePath, result?.absolutePath)
        assertTrue(renderer.cachedFile(route, 640, 240)!!.isFile)
    }
}
