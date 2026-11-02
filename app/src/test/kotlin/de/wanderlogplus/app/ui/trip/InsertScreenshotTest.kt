package de.wanderlogplus.app.ui.trip

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.testing.recordScreenshot
import de.wanderlogplus.app.ui.WanderlogPlusTheme
import de.wanderlogplus.client.GeoPlace
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Records the program with ＋ insert elements and the place search with hits. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h1100dp-xxhdpi")
class InsertScreenshotTest {
    @get:Rule val rule = createComposeRule()

    @Test fun programWithInsertElements() {
        rule.setContent {
            WanderlogPlusTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    DayDetailScreen(Fixtures.trip(), "2027-04-02", onBack = {}, onAddSuggestion = { _, _ -> }, onMove = { _, _ -> }, onRemove = {}, onSearchPlace = { _, _ -> })
                }
            }
        }
        rule.onRoot().recordScreenshot("insert-program-light")
    }

    @Test fun searchWithHits() {
        val hits = listOf(
            GeoPlace("Tsumago-juku", "Nakasendo, Nagiso, Japan", 35.5768, 137.596, "historic", "district"),
            GeoPlace("Tsumago Honjin", "Nagiso, Japan", 35.577, 137.595, "tourism", "museum"),
            GeoPlace("Yoshimura-ya", "Nagiso, Japan", 35.576, 137.597, "amenity", "restaurant"),
        )
        val model = PlaceSearchModel({ _, _, _ -> hits }, { _, _ -> null }, null)
        model.query = "Tsumago"
        runBlocking { model.submit() }
        model.selected = hits[1]
        rule.setContent {
            WanderlogPlusTheme(darkTheme = true) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    PlaceSearchScreen(model, start = null, onAdd = {}, onBack = {})
                }
            }
        }
        rule.onRoot().recordScreenshot("insert-search-dark")
    }
}
