package de.wanderlogplus.app.maps

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.testing.recordScreenshot
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RouteMapFallbackTest {
    @get:Rule val rule = createComposeRule()

    @Test fun showsFallbackWithoutRenderer() {
        val route = RouteGeometry(LatLngD(35.0, 135.0), LatLngD(36.0, 137.0), arc = true)
        rule.setContent {
            WanderlogPlusTheme {
                Column {
                    RouteMap(route, accent = Color(0xFFA5520F), modifier = Modifier.fillMaxWidth())
                    Column(Modifier.testTag("empty-holder")) { RouteMap(null, accent = Color.Red) }
                }
            }
        }
        rule.onNodeWithTag("route-fallback").assertIsDisplayed()
        // route == null renders nothing: the holder has no height and only the one fallback exists.
        assertEquals(0f, rule.onNodeWithTag("empty-holder").fetchSemanticsNode().size.height.toFloat())
        assertEquals(1, rule.onAllNodesWithTagCount("route-fallback"))
    }

    @Test fun fallbackScreenshot() {
        val snap = Fixtures.trip()
        val train = routeFor(snap.trains.single(), snap)
        val flight = routeFor(snap.flights.first(), snap)
        rule.setContent {
            WanderlogPlusTheme {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    RouteMap(train, accent = Color(0xFF2E86AB), modifier = Modifier.clip(RoundedCornerShape(12.dp)))
                    RouteMap(flight, accent = Color(0xFFA5520F), modifier = Modifier.clip(RoundedCornerShape(12.dp)))
                }
            }
        }
        rule.onRoot().recordScreenshot("route-map-fallback")
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagCount(tag: String) =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().size
}
