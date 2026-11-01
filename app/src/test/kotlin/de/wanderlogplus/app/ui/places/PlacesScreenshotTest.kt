package de.wanderlogplus.app.ui.places

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.testing.recordScreenshot
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.app.ui.attachments.AttachmentActions
import de.wanderlogplus.client.TripSnapshot
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Records Orte (region tiles), a region's list and a place detail (Info tab): fixture, long texts, real data when present. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1500dp-xxhdpi")
class PlacesScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun suffix(dark: Boolean) = if (dark) "dark" else "light"

    private fun shot(name: String, dark: Boolean, content: @Composable () -> Unit) {
        rule.setContent {
            WanderlogPlusTheme(darkTheme = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        rule.onRoot().recordScreenshot("$name-${suffix(dark)}")
    }

    private fun overview(name: String, s: TripSnapshot, dark: Boolean) = shot(name, dark) { PlacesScreen(s, onOpenRegion = {}) }

    private fun region(name: String, s: TripSnapshot, key: String, dark: Boolean) =
        shot(name, dark) { RegionPlacesScreen(s, key, onBack = {}, onOpenPlace = {}) }

    private fun detail(name: String, s: TripSnapshot, savedPlaceId: String, dark: Boolean) {
        val actions = AttachmentActions(available = { false }, onAdd = { _, _ -> }, onAddToDay = {}, onOpen = {}, onDelete = {})
        shot(name, dark) {
            PlaceDetailScreen(
                s, savedPlaceId, onBack = {}, onSaveNote = { _, _ -> }, onSetStatus = { _, _ -> },
                attachments = actions, onOpenLink = {}, today = "2027-04-02",
            )
        }
    }

    @Test fun overviewLight() = overview("places", Fixtures.trip(), dark = false)
    @Test fun overviewDark() = overview("places", Fixtures.trip(), dark = true)
    @Test fun overviewLongLight() = overview("places-long", Fixtures.trip(longTexts = true), dark = false)

    @Test fun regionLight() = region("places-region", Fixtures.trip(), "r-berg", dark = false)
    @Test fun regionDark() = region("places-region", Fixtures.trip(), "r-berg", dark = true)
    @Test fun regionLongLight() = region("places-region-long", Fixtures.trip(longTexts = true), "r-berg", dark = false)

    @Test fun detailLight() = detail("places-detail", Fixtures.trip(), "s-tempel", dark = false)
    @Test fun detailDark() = detail("places-detail", Fixtures.trip(), "s-tempel", dark = true)
    @Test fun detailFoodDark() = detail("places-detail-food", Fixtures.trip(), "s-nudel", dark = true)
    @Test fun detailLongLight() = detail("places-detail-long", Fixtures.trip(longTexts = true), "s-tempel", dark = false)

    @Test fun realLight() = real { s ->
        overview("places-real", s, dark = false)
    }

    @Test fun realDark() = real { s -> overview("places-real", s, dark = true) }

    @Test fun realRegionLight() = real { s -> region("places-region-real", s, busiestRegion(s), dark = false) }

    @Test fun realRegionDark() = real { s -> region("places-region-real", s, busiestRegion(s), dark = true) }

    @Test fun realDetailLight() = real { s -> detail("places-detail-real", s, bestPlace(s), dark = false) }

    @Test fun realDetailDark() = real { s -> detail("places-detail-real", s, bestPlace(s), dark = true) }

    private fun busiestRegion(s: TripSnapshot): String =
        s.regions.maxByOrNull { r -> s.savedPlaces.count { it.regionId == r.id } }?.id ?: NO_REGION

    /** The saved place with most content (opening hours, description) – shows the Info tab best. */
    private fun bestPlace(s: TripSnapshot): String =
        s.savedPlaces.maxByOrNull { sp ->
            val p = s.places[sp.placeId]
            (if (p?.openingPeriods.isNullOrEmpty()) 0 else 2) + (if (p?.description.isNullOrBlank()) 0 else 1) +
                (if (p?.imageKeys.isNullOrEmpty()) 0 else 1)
        }!!.id

    private fun real(block: (TripSnapshot) -> Unit) {
        val snapshot = Fixtures.realOrNull()
        assumeTrue("no private real data", snapshot != null)
        block(snapshot!!)
    }
}
