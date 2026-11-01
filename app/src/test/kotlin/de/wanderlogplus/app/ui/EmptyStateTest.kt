package de.wanderlogplus.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import de.wanderlogplus.client.TripSnapshot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class EmptyStateTest {
    @get:Rule val rule = createComposeRule()

    @Test fun emptySnapshotShowsEmptyStateInHeuteAndButtonRequestsSync() {
        var refreshes = 0
        rule.setContent {
            WanderlogPlusTheme {
                AppNavigation(hasServer = true, snapshot = TripSnapshot.from(emptyList()), onRefresh = { refreshes++ })
            }
        }
        rule.onNodeWithText("Noch keine Daten", substring = true).assertIsDisplayed()
        rule.onNodeWithText("Jetzt synchronisieren").performClick()
        assertEquals(1, refreshes)
    }

    @Test fun relativeAgoFormats() {
        val now = 10_000_000_000L
        assertEquals("gerade eben", relativeAgo(now - 20_000, now))
        assertEquals("vor 3 Min.", relativeAgo(now - 3 * 60_000, now))
        assertEquals("vor 2 Std.", relativeAgo(now - 2 * 3_600_000L, now))
        assertEquals("vor 1 Tag", relativeAgo(now - 86_400_000L, now))
        assertEquals("vor 3 Tagen", relativeAgo(now - 3 * 86_400_000L, now))
    }
}
