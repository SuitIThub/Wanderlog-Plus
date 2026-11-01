package de.wanderlogplus.app.ui.design

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.core.app.ApplicationProvider
import coil.Coil
import coil.ImageLoader
import coil.intercept.Interceptor
import coil.request.ErrorResult
import de.wanderlogplus.app.maps.LatLngD
import de.wanderlogplus.app.maps.RouteGeometry
import de.wanderlogplus.app.maps.RouteMap
import de.wanderlogplus.app.maps.RouteMapRenderer
import de.wanderlogplus.app.ui.LocalDarkTheme
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Image loads that fail (no network, broken file) fall back silently; Coil is replaced by a loader that always errors. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ImageFallbackTest {
    @get:Rule val rule = createComposeRule()

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Before fun failingCoil() {
        Coil.setImageLoader(
            ImageLoader.Builder(context)
                .components { add(Interceptor { chain -> ErrorResult(null, chain.request, IllegalStateException("offline")) }) }
                .build(),
        )
    }

    @After fun resetCoil() = Coil.reset()

    @Test fun failedPhotoShowsFallback() {
        rule.setContent {
            WanderlogPlusTheme {
                PhotoCard(
                    title = "Tempel",
                    subtitle = null,
                    imageUrl = placeImageUrl("img-tempel"),
                    placeholderSymbol = "⛩",
                    accent = Color(0xFFC0603A),
                )
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("photo-fallback")).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("photo-fallback").assertExists()
    }

    @Test fun undecodableRouteMapIsDeletedAndFallsBack() {
        val dir = File(context.cacheDir, "route-maps-broken").apply { deleteRecursively(); mkdirs() }
        val route = RouteGeometry(LatLngD(34.98, 135.75), LatLngD(34.70, 135.49), arc = false)
        val renderer = RouteMapRenderer(context, dir, isOnline = { false }, snapshot = { _, _, _ -> error("must not render") })
        // Pre-fill the cache for every size RouteMap could ask for: only the file name matters, the bytes are garbage.
        rule.setContent {
            WanderlogPlusTheme {
                val density = LocalDensity.current
                BoxWithConstraints {
                    val w = constraints.maxWidth
                    val h = with(density) { 120.dp.roundToPx() }
                    remember(w, h) { File(dir, route.cacheKey(w, h) + ".png").writeBytes(byteArrayOf(1, 2, 3)) }
                    RouteMap(route, accent = Color(0xFF2E86AB), height = 120.dp, renderer = renderer)
                }
            }
        }
        rule.waitUntil(5_000) { rule.onAllNodes(hasTestTag("route-fallback")).fetchSemanticsNodes().isNotEmpty() }
        rule.waitUntil(5_000) { dir.listFiles().orEmpty().isEmpty() }
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun darkThemeIsExplicitNotGuessed() {
        var inAppDark: Boolean? = null
        var inAppLight: Boolean? = null
        var bareDarkScheme: Boolean? = null
        rule.setContent {
            WanderlogPlusTheme(darkTheme = true) { inAppDark = LocalDarkTheme.current }
            WanderlogPlusTheme(darkTheme = false) { inAppLight = LocalDarkTheme.current }
            // A dark color scheme alone no longer switches badges: only the theme's flag does.
            MaterialTheme(colorScheme = darkColorScheme()) { bareDarkScheme = LocalDarkTheme.current }
        }
        rule.waitForIdle()
        assertEquals(true, inAppDark)
        assertEquals(false, inAppLight)
        assertFalse(bareDarkScheme!!)
    }
}
