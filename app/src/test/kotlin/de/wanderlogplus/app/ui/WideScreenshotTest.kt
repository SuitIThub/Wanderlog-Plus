package de.wanderlogplus.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import de.wanderlogplus.app.testing.Fixtures
import de.wanderlogplus.app.testing.recordScreenshot
import de.wanderlogplus.app.ui.design.TwoPane
import de.wanderlogplus.app.ui.today.TodayScreen
import de.wanderlogplus.app.ui.trip.DayDetailScreen
import de.wanderlogplus.app.ui.trip.DayMapScreen
import de.wanderlogplus.app.ui.trip.TripScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDateTime

/** Records the unfolded layouts (Galaxy Z Fold inner display, about 840 × 740 dp). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w840dp-h740dp-xhdpi")
class WideScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private fun shot(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            WanderlogPlusTheme(darkTheme = false) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        rule.onRoot().recordScreenshot(name)
    }

    @Test fun tripTwoPane() = shot("wide-trip") {
        val s = Fixtures.trip()
        TwoPane(
            list = { TripScreen(s, onOpenDay = {}, today = "2027-04-02", selected = "2027-04-02") },
            detail = { DayDetailScreen(s, "2027-04-02", onBack = null, onAddSuggestion = { _, _ -> }, onMove = { _, _ -> }, onRemove = {}, onOpenMap = {}) },
        )
    }

    @Test fun dayMap() = shot("wide-day-map") { DayMapScreen(Fixtures.trip(), "2027-04-02", onBack = {}) }

    @Test fun today() = shot("wide-today") {
        TodayScreen(Fixtures.trip(), LocalDateTime.parse("2027-04-02T10:00"), onOpenDay = {}, onOpenTrip = {}, onSyncNow = {}, onOpenLink = {}, available = { false })
    }
}
